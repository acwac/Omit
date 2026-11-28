#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::net::UdpSocket;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Duration;
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::Manager;

static SHIELD_ACTIVE: AtomicBool = AtomicBool::new(false);
static TOTAL_QUERIES: AtomicU64 = AtomicU64::new(0);
static BLOCKED_QUERIES: AtomicU64 = AtomicU64::new(0);
static LISTENER_RUNNING: AtomicBool = AtomicBool::new(false);
/// Live listener sockets (IPv4 + IPv6 loopback), kept here so disabling the
/// shield releases port 53 for the next start.
static LISTENER_SOCKETS: std::sync::Mutex<Vec<Arc<UdpSocket>>> = std::sync::Mutex::new(Vec::new());
/// Worker threads still parked in recv_from.
static WORKERS_ALIVE: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);
/// Most recent DNS decisions, capped so memory stays flat on long sessions.
static LOG_RING: std::sync::Mutex<std::collections::VecDeque<LogEntry>> =
    std::sync::Mutex::new(std::collections::VecDeque::new());
static LOG_SEQ: AtomicU64 = AtomicU64::new(1);
const LOG_CAPACITY: usize = 200;
/// Rules present in the loaded vbin table (0 when the resource is missing).
static RULE_COUNT: AtomicU64 = AtomicU64::new(0);
/// Category bitmask the user has switched on (mirrors the APK's category_mask).
static ACTIVE_MASK: AtomicU64 = AtomicU64::new(MASK_BALANCED as u64);

const MASK_BALANCED: u8 = 0x3F; // game, web, trackers, malware, OEM, gambling
const MASK_STRICT: u8 = 0xFF;

const BLOCKLIST_FILE: &str = "omit_starter.vbin";
const VELLUM_MAGIC: &[u8; 8] = b"VELLUM01";
const UPSTREAM_DNS: &str = "8.8.8.8:53";
const UPSTREAM_DNS_ALT: &str = "1.1.1.1:53";

// ---------------------------------------------------------------------------
// Blocklist: the same 64-bit sorted hash + 8-bit category table the APK uses.
// ---------------------------------------------------------------------------

struct BlockTable {
    hashes: Vec<i64>,
    masks: Vec<u8>,
}

impl BlockTable {
    fn empty() -> Self {
        Self {
            hashes: Vec::new(),
            masks: Vec::new(),
        }
    }

    fn load(path: &PathBuf) -> Option<Self> {
        let data = std::fs::read(path).ok()?;
        if data.len() < 16 || &data[0..8] != VELLUM_MAGIC {
            return None;
        }
        let count = i32::from_be_bytes([data[8], data[9], data[10], data[11]]);
        if count < 0 {
            return None;
        }
        let count = count as usize;
        let expected = 16 + count * 8 + count;
        if data.len() < expected {
            return None;
        }
        let mut hashes = Vec::with_capacity(count);
        for i in 0..count {
            let base = 16 + i * 8;
            hashes.push(i64::from_be_bytes([
                data[base],
                data[base + 1],
                data[base + 2],
                data[base + 3],
                data[base + 4],
                data[base + 5],
                data[base + 6],
                data[base + 7],
            ]));
        }
        let mask_base = 16 + count * 8;
        let masks = data[mask_base..mask_base + count].to_vec();
        RULE_COUNT.store(count as u64, Ordering::Relaxed);
        Some(Self { hashes, masks })
    }

    /// Same FNV-1a + MurmurHash3 fmix64 the Kotlin engine and compiler use.
    fn hash64(domain: &str) -> i64 {
        let mut h: u64 = 0xcbf2_9ce4_8422_2325;
        const PRIME: u64 = 0x0000_0100_0000_01b3;
        for b in domain.as_bytes() {
            let c = if b.is_ascii_uppercase() { b + 32 } else { *b };
            h ^= c as u64;
            h = h.wrapping_mul(PRIME);
        }
        h ^= h >> 33;
        h = h.wrapping_mul(0xff51_afd7_ed55_8ccd);
        h ^= h >> 33;
        h = h.wrapping_mul(0xc4ce_b9fe_1a85_ec53);
        h ^= h >> 33;
        h as i64
    }

    /// Exact-or-parent suffix match against a user supplied rule set.
    fn matches_set(domain: &str, set: &std::collections::HashSet<String>) -> bool {
        if set.is_empty() {
            return false;
        }
        let mut start = 0usize;
        loop {
            if set.contains(&domain[start..]) {
                return true;
            }
            match domain[start..].find('.') {
                Some(dot) => start += dot + 1,
                None => return false,
            }
        }
    }

    /// Same precedence the Android engine uses: allow-list, DoH canary guard,
    /// custom block rules, essential safe list, then the masked hash table.
    /// Walks every parent label so `ads.example.com` also matches `example.com`.
    fn verdict(&self, raw: &str, mask: u8, rules: &RuleSets) -> Verdict {
        let domain = raw.trim_end_matches('.').to_ascii_lowercase();
        if domain.is_empty() || !domain.contains('.') {
            return Verdict::Allow;
        }
        if Self::matches_set(&domain, &rules.allow) {
            return Verdict::Allow;
        }
        if domain == "use-application-dns.net"
            || domain == "mask.icloud.com"
            || domain == "mask-h2.icloud.com"
        {
            return Verdict::Blocked(0);
        }
        if Self::matches_set(&domain, &rules.block) {
            return Verdict::Blocked(0);
        }
        if ESSENTIAL_SAFE.contains(&domain.as_str()) || AUTH_SAFE.contains(&domain.as_str()) {
            return Verdict::Allow;
        }
        if mask == 0 || self.hashes.is_empty() {
            return Verdict::Allow;
        }
        let mut start = 0usize;
        while let Some(dot) = domain[start..].find('.') {
            let candidate = &domain[start..];
            let h = Self::hash64(candidate);
            if let Ok(idx) = self.hashes.binary_search(&h) {
                let hit = self.masks[idx] & mask;
                if hit != 0 {
                    return Verdict::Blocked(hit);
                }
            }
            start += dot + 1;
        }
        Verdict::Allow
    }
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum Verdict {
    Allow,
    Blocked(u8),
}

/// Identity + auth endpoints are never filtered (mirrors BlockEngine.kt).
const AUTH_SAFE: &[&str] = &[
    "games.googleapis.com",
    "play.googleapis.com",
    "accounts.google.com",
    "oauth2.googleapis.com",
    "apis.google.com",
    "www.googleapis.com",
    "appleid.apple.com",
    "idmsa.apple.com",
    "graph.facebook.com",
];

const ESSENTIAL_SAFE: &[&str] = &[
    "localhost", "google.com", "www.google.com", "google.co.in", "www.google.co.in",
    "accounts.google.com", "dns.google", "googleapis.com", "gstatic.com",
    "googleusercontent.com", "googlevideo.com", "youtube.com", "www.youtube.com",
    "ytimg.com", "play.google.com", "android.com", "mtalk.google.com",
    "fcm.googleapis.com", "cloudflare.com", "cloudflare-dns.com",
    "whatsapp.com", "whatsapp.net", "instagram.com", "cdninstagram.com",
    "facebook.com", "fbcdn.net", "spotify.com", "api.spotify.com", "scdn.co",
    "amazon.in", "amazon.com", "flipkart.com", "paytm.com", "phonepe.com",
    "razorpay.com", "billdesk.com", "npci.org.in", "connectivitycheck.gstatic.com",
    "windowsupdate.com", "update.microsoft.com", "login.microsoftonline.com",
    "login.live.com", "microsoft.com", "live.com", "msftconnecttest.com",
    "msftncsi.com", "digicert.com", "ocsp.digicert.com", "letsencrypt.org",
];

// ---------------------------------------------------------------------------
// DNS helpers
// ---------------------------------------------------------------------------

fn read_qname(packet: &[u8]) -> Option<(String, usize)> {
    if packet.len() < 13 {
        return None;
    }
    let mut pos = 12usize;
    let mut labels: Vec<String> = Vec::new();
    loop {
        let len = *packet.get(pos)? as usize;
        if len == 0 {
            pos += 1;
            break;
        }
        if len & 0xC0 != 0 || pos + 1 + len > packet.len() {
            return None;
        }
        let label = std::str::from_utf8(&packet[pos + 1..pos + 1 + len]).ok()?;
        labels.push(label.to_ascii_lowercase());
        pos += 1 + len;
        if labels.len() > 32 {
            return None;
        }
    }
    Some((labels.join("."), pos))
}

/// Answers A/AAAA queries with the sinkhole address and everything else with NXDOMAIN.
fn build_blocked_response(query: &[u8], qname_end: usize) -> Option<Vec<u8>> {
    if query.len() < 12 || qname_end + 4 > query.len() {
        return None;
    }
    let qtype = u16::from_be_bytes([query[qname_end], query[qname_end + 1]]);
    let synthetic = qtype == 1 || qtype == 28;

    let mut out = Vec::with_capacity(64);
    out.extend_from_slice(&query[0..2]); // transaction id
    let rd = query[2] & 0x01;
    out.push(0x81); // QR + RD
    out.push(0x80 | rd); // RA
    out.extend_from_slice(&[0x00, 0x01]); // QDCOUNT
    out.extend_from_slice(&[0x00, if synthetic { 0x01 } else { 0x00 }]); // ANCOUNT
    out.extend_from_slice(&[0x00, 0x00, 0x00, 0x00]);
    out.extend_from_slice(&query[12..qname_end + 4]);

    if synthetic {
        out.extend_from_slice(&[0xC0, 0x0C]); // pointer to qname
        out.extend_from_slice(&qtype.to_be_bytes());
        out.extend_from_slice(&[0x00, 0x01]); // IN
        out.extend_from_slice(&60u32.to_be_bytes()); // 60s TTL (anti retry storm)
        if qtype == 1 {
            out.extend_from_slice(&[0x00, 0x04, 0x00, 0x00, 0x00, 0x00]);
        } else {
            out.extend_from_slice(&[0x00, 0x10]);
            out.extend_from_slice(&[0u8; 16]);
        }
    } else {
        // NXDOMAIN: rewrite flags with rcode 3.
        out[3] |= 0x03;
    }
    Some(out)
}

fn forward_query(query: &[u8]) -> Option<Vec<u8>> {
    for upstream in [UPSTREAM_DNS, UPSTREAM_DNS_ALT] {
        if let Ok(sock) = UdpSocket::bind("0.0.0.0:0") {
            if sock.set_read_timeout(Some(Duration::from_millis(1800))).is_err() {
                continue;
            }
            if sock.send_to(query, upstream).is_err() {
                continue;
            }
            let mut buf = [0u8; 1500];
            if let Ok((len, _)) = sock.recv_from(&mut buf) {
                if len >= 12 {
                    return Some(buf[..len].to_vec());
                }
            }
        }
    }
    None
}

#[derive(Clone)]
struct LogEntry {
    id: u64,
    domain: String,
    category: String,
    blocked: bool,
    at_ms: u64,
}

fn category_label(mask: u8) -> &'static str {
    match mask {
        0 => "Custom rule",
        m if m & 0x01 != 0 => "Game & Store Ads",
        m if m & 0x02 != 0 => "Web Banners & Popups",
        m if m & 0x04 != 0 => "Cross-App Trackers",
        m if m & 0x08 != 0 => "Malware & Scam Shield",
        m if m & 0x10 != 0 => "Windows Telemetry",
        m if m & 0x20 != 0 => "Betting & Casino Popups",
        m if m & 0x40 != 0 => "Shopping & Affiliate Links",
        _ => "Social Media Trackers",
    }
}

fn push_log(domain: &str, category: &str, blocked: bool) {
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0);
    let entry = LogEntry {
        id: LOG_SEQ.fetch_add(1, Ordering::Relaxed),
        domain: domain.to_string(),
        category: category.to_string(),
        blocked,
        at_ms: now,
    };
    if let Ok(mut ring) = LOG_RING.lock() {
        if ring.len() >= LOG_CAPACITY {
            ring.pop_front();
        }
        ring.push_back(entry);
    }
}

fn serve_query(table: &Arc<BlockTable>, rules: &RuleSets, query: &[u8]) -> Option<Vec<u8>> {
    let (qname, qname_end) = match read_qname(query) {
        Some(v) => v,
        None => return None,
    };
    let mask = ACTIVE_MASK.load(Ordering::Relaxed) as u8;
    match table.verdict(&qname, mask, rules) {
        Verdict::Blocked(hit) => {
            BLOCKED_QUERIES.fetch_add(1, Ordering::Relaxed);
            push_log(&qname, category_label(hit), true);
            build_blocked_response(query, qname_end)
        }
        Verdict::Allow => {
            let answer = forward_query(query);
            if answer.is_some() {
                push_log(&qname, "Upstream", false);
            }
            answer
        }
    }
}

/// Stops the workers, releases port 53 and waits (briefly) for the threads to
/// notice. Called before every start so toggling off -> on always rebinds.
fn stop_dns_sinkhole() {
    LISTENER_RUNNING.store(false, Ordering::SeqCst);
    let deadline = std::time::Instant::now() + Duration::from_millis(1500);
    while WORKERS_ALIVE.load(Ordering::SeqCst) > 0 && std::time::Instant::now() < deadline {
        std::thread::sleep(Duration::from_millis(10));
    }
    if let Ok(mut sockets) = LISTENER_SOCKETS.lock() {
        sockets.clear();
    }
}

fn spawn_workers(
    socket: &Arc<UdpSocket>,
    table: &Arc<BlockTable>,
    rules: &Arc<std::sync::Mutex<RuleSets>>,
    count: usize,
) -> usize {
    let mut spawned = 0usize;
    for _ in 0..count {
        let sock = Arc::clone(socket);
        let table = Arc::clone(table);
        let rules = Arc::clone(rules);
        // Counted before the spawn so a thread that exits instantly can never
        // make the counter wrap below zero.
        WORKERS_ALIVE.fetch_add(1, Ordering::SeqCst);
        let ok = std::thread::Builder::new()
            .name("omit-dns-worker".into())
            .spawn(move || {
                let mut buf = [0u8; 1500];
                while LISTENER_RUNNING.load(Ordering::Relaxed) {
                    match sock.recv_from(&mut buf) {
                        Ok((len, src)) => {
                            if len < 12 {
                                continue;
                            }
                            TOTAL_QUERIES.fetch_add(1, Ordering::Relaxed);
                            let snapshot = rules.lock().unwrap_or_else(|e| e.into_inner());
                            let resp = serve_query(&table, &snapshot, &buf[..len]);
                            drop(snapshot);
                            if let Some(resp) = resp {
                                let _ = sock.send_to(&resp, src);
                            }
                        }
                        Err(err) => match err.kind() {
                            std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut => {}
                            _ => std::thread::sleep(Duration::from_millis(50)),
                        },
                    }
                }
                WORKERS_ALIVE.fetch_sub(1, Ordering::SeqCst);
            })
            .is_ok();
        if ok {
            spawned += 1;
        } else {
            WORKERS_ALIVE.fetch_sub(1, Ordering::SeqCst);
        }
    }
    spawned
}

fn start_dns_sinkhole(table: Arc<BlockTable>, rules: Arc<std::sync::Mutex<RuleSets>>) -> bool {
    stop_dns_sinkhole();
    let mut bound: Vec<Arc<UdpSocket>> = Vec::new();
    // Both loopback families: a router-supplied IPv6 resolver would otherwise
    // keep answering while the adapter's IPv4 DNS points at us.
    for addr in ["127.0.0.1:53", "[::1]:53"] {
        if let Ok(socket) = UdpSocket::bind(addr) {
            // A short timeout keeps the workers responsive to LISTENER_RUNNING.
            let _ = socket.set_read_timeout(Some(Duration::from_millis(250)));
            bound.push(Arc::new(socket));
        }
    }
    if bound.is_empty() {
        return false;
    }

    LISTENER_RUNNING.store(true, Ordering::SeqCst);
    let mut spawned = 0usize;
    for socket in &bound {
        spawned += spawn_workers(socket, &table, &rules, 2);
    }
    if spawned == 0 {
        stop_dns_sinkhole();
        return false;
    }
    if let Ok(mut sockets) = LISTENER_SOCKETS.lock() {
        *sockets = bound;
    }
    true
}

// ---------------------------------------------------------------------------
// Windows network plumbing
// ---------------------------------------------------------------------------

#[cfg(target_os = "windows")]
fn run_powershell(script: &str) {
    use std::os::windows::process::CommandExt;
    const CREATE_NO_WINDOW: u32 = 0x0800_0000;
    let _ = std::process::Command::new("powershell")
        .args(["-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", script])
        .creation_flags(CREATE_NO_WINDOW)
        .output();
}

fn restore_windows_dns() {
    // Still points the adapters back even if the OS is not Windows (builds/tests),
    // so the logic is identical on every target.
    #[cfg(target_os = "windows")]
    run_powershell(
        "Get-NetAdapter | Where-Object {$_.Status -eq 'Up'} | ForEach-Object { \
         $v4 = Get-DnsClientServerAddress -InterfaceIndex $_.ifIndex -AddressFamily IPv4 -ErrorAction SilentlyContinue; \
         $v6 = Get-DnsClientServerAddress -InterfaceIndex $_.ifIndex -AddressFamily IPv6 -ErrorAction SilentlyContinue; \
         if ($v4 -and ($v4.ServerAddresses -contains '127.0.0.1') -or $v6 -and ($v6.ServerAddresses -contains '::1')) { \
           Set-DnsClientServerAddress -InterfaceIndex $_.ifIndex -ResetServerAddresses -ErrorAction SilentlyContinue } }",
    );
}

fn set_windows_local_dns() {
    #[cfg(target_os = "windows")]
    run_powershell(
        "Get-NetAdapter | Where-Object {$_.Status -eq 'Up'} | ForEach-Object { \
         Set-DnsClientServerAddress -InterfaceIndex $_.ifIndex -ServerAddresses ('127.0.0.1') -ErrorAction SilentlyContinue; \
         Set-DnsClientServerAddress -InterfaceIndex $_.ifIndex -AddressFamily IPv6 -ServerAddresses ('::1') -ErrorAction SilentlyContinue }",
    );
}

#[cfg(target_os = "windows")]
fn is_elevated() -> bool {
    use std::os::windows::process::CommandExt;
    const CREATE_NO_WINDOW: u32 = 0x0800_0000;
    std::process::Command::new("net")
        .arg("session")
        .creation_flags(CREATE_NO_WINDOW)
        .output()
        .map(|o| o.status.success())
        .unwrap_or(false)
}

#[cfg(not(target_os = "windows"))]
fn is_elevated() -> bool {
    false
}

fn blocklist_path(app: &tauri::AppHandle) -> Option<PathBuf> {
    // Bundled resource first, then the path next to the executable (portable builds).
    if let Ok(resource) = app.path().resource_dir() {
        let candidate = resource.join(BLOCKLIST_FILE);
        if candidate.is_file() {
            return Some(candidate);
        }
    }
    if let Ok(exe) = std::env::current_exe() {
        if let Some(dir) = exe.parent() {
            let candidate = dir.join(BLOCKLIST_FILE);
            if candidate.is_file() {
                return Some(candidate);
            }
            let nested = dir.join("assets").join(BLOCKLIST_FILE);
            if nested.is_file() {
                return Some(nested);
            }
        }
    }
    None
}

// ---------------------------------------------------------------------------
// Tauri commands
// ---------------------------------------------------------------------------

#[tauri::command]
fn get_platform() -> &'static str {
    "windows"
}

#[tauri::command]
fn get_windows_stats() -> WindowsStats {
    WindowsStats {
        total: TOTAL_QUERIES.load(Ordering::Relaxed),
        blocked: BLOCKED_QUERIES.load(Ordering::Relaxed),
        active: SHIELD_ACTIVE.load(Ordering::Relaxed),
        rules: RULE_COUNT.load(Ordering::Relaxed),
        mask: ACTIVE_MASK.load(Ordering::Relaxed),
    }
}

#[derive(serde::Serialize)]
struct WindowsStats {
    total: u64,
    blocked: u64,
    active: bool,
    rules: u64,
    mask: u64,
}

#[derive(serde::Serialize)]
struct ShieldStatus {
    active: bool,
    listening: bool,
    admin: bool,
    message: String,
}

#[derive(serde::Serialize)]
struct LogJson {
    id: u64,
    domain: String,
    time: String,
    status: String,
    category: String,
}

fn relative_time(at_ms: u64) -> String {
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(at_ms);
    let secs = now.saturating_sub(at_ms) / 1000;
    if secs < 2 {
        "Now".to_string()
    } else if secs < 60 {
        format!("{secs}s")
    } else if secs < 3600 {
        format!("{}m", secs / 60)
    } else {
        format!("{}h", secs / 3600)
    }
}

#[tauri::command]
fn get_windows_logs() -> Vec<LogJson> {
    let ring = match LOG_RING.lock() {
        Ok(ring) => ring,
        Err(poisoned) => poisoned.into_inner(),
    };
    ring.iter()
        .rev()
        .map(|e| LogJson {
            id: e.id,
            domain: e.domain.clone(),
            time: relative_time(e.at_ms),
            status: if e.blocked { "Blocked".into() } else { "Allowed".into() },
            category: e.category.clone(),
        })
        .collect()
}

#[tauri::command]
fn clear_windows_logs() {
    if let Ok(mut ring) = LOG_RING.lock() {
        ring.clear();
    }
}

#[tauri::command]
fn get_shield_status() -> ShieldStatus {
    let active = SHIELD_ACTIVE.load(Ordering::Relaxed);
    let listening = LISTENER_RUNNING.load(Ordering::Relaxed);
    let admin = is_elevated();
    let message = if active && !listening {
        "Shield is on, but the local DNS port is busy.".to_string()
    } else if active && !admin {
        "Restart Omit as administrator so Windows DNS can be redirected.".to_string()
    } else if active {
        "Every app on this PC now uses Omit's filtered DNS.".to_string()
    } else {
        "Shield is off. Turn it on for system-wide blocking.".to_string()
    };
    ShieldStatus {
        active,
        listening,
        admin,
        message,
    }
}

#[tauri::command]
fn toggle_windows_shield(enable: bool, state: tauri::State<AppState>) -> bool {
    if enable {
        if !is_elevated() {
            SHIELD_ACTIVE.store(false, Ordering::SeqCst);
            return false;
        }
        if state.table.hashes.is_empty() {
            // Without rules a sinkhole would only slow things down; refuse to enable.
            SHIELD_ACTIVE.store(false, Ordering::SeqCst);
            return false;
        }
        if !start_dns_sinkhole(Arc::clone(&state.table), Arc::clone(&state.rules)) {
            SHIELD_ACTIVE.store(false, Ordering::SeqCst);
            return false;
        }
        set_windows_local_dns();
        SHIELD_ACTIVE.store(true, Ordering::SeqCst);
        true
    } else {
        SHIELD_ACTIVE.store(false, Ordering::SeqCst);
        restore_windows_dns();
        stop_dns_sinkhole();
        false
    }
}

/// Sets the 8-bit category mask directly, or flips one bit by index
/// (0 game ads, 1 web, 2 trackers, 3 malware, 4 OEM, 5 gambling, 6 affiliate, 7 social).
#[tauri::command]
fn set_windows_categories(mask: Option<u8>, toggle_index: Option<u8>) -> u8 {
    let current = ACTIVE_MASK.load(Ordering::Relaxed) as u8;
    let next = match (mask, toggle_index) {
        (Some(mask), _) => mask,
        (None, Some(index)) if index < 8 => current ^ (1u8 << index),
        _ => current,
    };
    ACTIVE_MASK.store(next as u64, Ordering::Relaxed);
    next
}

#[tauri::command]
fn set_windows_rules(domain: String, mode: String, state: tauri::State<AppState>) -> bool {
    let clean = domain.trim().trim_end_matches('.').to_ascii_lowercase();
    if clean.len() < 3 || !clean.contains('.') {
        return false;
    }
    let mut rules = state.rules.lock().unwrap_or_else(|e| e.into_inner());
    match mode.as_str() {
        "allow" => {
            rules.block.remove(&clean);
            rules.allow.insert(clean);
        }
        "block" => {
            rules.allow.remove(&clean);
            rules.block.insert(clean);
        }
        "clear" => {
            rules.allow.remove(&clean);
            rules.block.remove(&clean);
        }
        _ => return false,
    }
    true
}

#[tauri::command]
fn get_windows_rules(state: tauri::State<AppState>) -> (Vec<String>, Vec<String>) {
    let rules = state.rules.lock().unwrap_or_else(|e| e.into_inner());
    let mut allow: Vec<String> = rules.allow.iter().cloned().collect();
    let mut block: Vec<String> = rules.block.iter().cloned().collect();
    allow.sort();
    block.sort();
    (allow, block)
}

#[tauri::command]
fn download_and_install_update(exe_url: String) -> Result<(), String> {
    let url = if exe_url.is_empty() {
        "https://acwac.github.io/Omit/downloads/Omit-Windows.exe".to_string()
    } else {
        exe_url
    };
    if !url.starts_with("https://") {
        return Err("Refusing to download over an insecure connection".into());
    }
    let bytes = reqwest::blocking::get(&url)
        .map_err(|e| e.to_string())?
        .bytes()
        .map_err(|e| e.to_string())?;
    let installer_path = std::env::temp_dir().join("Omit-Update.exe");
    std::fs::write(&installer_path, &bytes).map_err(|e| e.to_string())?;
    restore_windows_dns();
    std::process::Command::new(&installer_path)
        .spawn()
        .map_err(|e| e.to_string())?;
    std::process::exit(0);
}

#[tauri::command]
fn set_windows_theme(dark: bool, window: tauri::WebviewWindow) {
    let _ = window.set_theme(if dark {
        Some(tauri::Theme::Dark)
    } else {
        Some(tauri::Theme::Light)
    });
}

struct AppState {
    table: Arc<BlockTable>,
    /// Shared with the DNS workers, so allow/block edits apply to the next query.
    rules: Arc<std::sync::Mutex<RuleSets>>,
}

#[derive(Default)]
struct RuleSets {
    allow: std::collections::HashSet<String>,
    block: std::collections::HashSet<String>,
}


fn main() {
    let default_hook = std::panic::take_hook();
    std::panic::set_hook(Box::new(move |info| {
        restore_windows_dns();
        default_hook(info);
    }));

    let builder = tauri::Builder::default();
    // Must be the first plugin on Windows: a shortcut launch has to create the
    // window rather than a second windowless process (Tauri v2 requirement).
    #[cfg(target_os = "windows")]
    let builder = builder.plugin(tauri_plugin_single_instance::init(|app, _argv, _cwd| {
        if let Some(window) = app.get_webview_window("main") {
            let _ = window.show();
            let _ = window.set_focus();
        }
    }));

    builder
        .setup(|app| {
            let handle = app.handle().clone();
            // `panic = "abort"` in release means the panic hook above is the only
            // thing that restores adapter DNS on a crash.
            // Parsed once at startup so the first shield toggle is instant.
            let table = Arc::new(
                blocklist_path(&handle)
                    .and_then(|p| BlockTable::load(&p))
                    .unwrap_or_else(BlockTable::empty),
            );
            app.manage(AppState {
                table,
                rules: Arc::new(std::sync::Mutex::new(RuleSets::default())),
            });

            // Carry the app icon when the bundle provides one so the tray entry is
            // visible; a missing icon must never stop the app from starting.
            let mut tray = TrayIconBuilder::with_id("omit-tray");
            if let Some(icon) = app.default_window_icon().cloned() {
                tray = tray.icon(icon);
            }
            let _tray = tray
                .tooltip("Omit — Ad, Tracker & Anti-DPI Shield")
                .on_tray_icon_event(|tray, event| {
                    if let TrayIconEvent::Click {
                        button: MouseButton::Left,
                        button_state: MouseButtonState::Up,
                        ..
                    } = event
                    {
                        if let Some(window) = tray.app_handle().get_webview_window("main") {
                            let _ = window.show();
                            let _ = window.unminimize();
                            let _ = window.set_focus();
                        }
                    }
                })
                .build(app)?;
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            get_platform,
            get_windows_stats,
            set_windows_categories,
            set_windows_rules,
            get_windows_rules,
            get_windows_logs,
            clear_windows_logs,
            get_shield_status,
            toggle_windows_shield,
            download_and_install_update,
            set_windows_theme
        ])
        .on_window_event(|_window, event| {
            if let tauri::WindowEvent::CloseRequested { .. } = event {
                SHIELD_ACTIVE.store(false, Ordering::SeqCst);
                stop_dns_sinkhole();
                restore_windows_dns();
            }
        })
        .run(tauri::generate_context!())
        .expect("error while running Omit for Windows");

    SHIELD_ACTIVE.store(false, Ordering::SeqCst);
    restore_windows_dns();
}
