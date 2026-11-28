#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::net::UdpSocket;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, OnceLock};
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
const LAN_PROXY_PORT: u16 = 8080;
static LAN_BLOCKED: AtomicU64 = AtomicU64::new(0);
static LAN_WORKERS: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);

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

/// Encrypted resolvers offered in the UI, in the same order as Android's
/// FilterMetadata.UPSTREAMS. Index 4 is the user supplied custom endpoint.
const DOH_UPSTREAMS: [(&str, &str); 5] = [
    ("https://dns.google/dns-query", "8.8.8.8:53"),
    ("https://cloudflare-dns.com/dns-query", "1.1.1.1:53"),
    ("https://dns.quad9.net/dns-query", "9.9.9.9:53"),
    ("https://dns.adguard-dns.com/dns-query", "94.140.14.140:53"),
    ("", "8.8.8.8:53"),
];

/// Engine toggles, mirroring the five switches the shared UI renders.
static CNAME_UNCLOAK: AtomicBool = AtomicBool::new(true);
static ANTI_STORM_TTL: AtomicBool = AtomicBool::new(true);
static DOH_CANARY: AtomicBool = AtomicBool::new(true);
static START_WITH_WINDOWS: AtomicBool = AtomicBool::new(true);
static CAPTIVE_FALLBACK: AtomicBool = AtomicBool::new(true);
/// When off, queries are still filtered but nothing is recorded.
static LOGGING_ENABLED: AtomicBool = AtomicBool::new(true);
/// Index into DOH_UPSTREAMS; 4 means "use the custom URL".
static DNS_INDEX: AtomicU64 = AtomicU64::new(0);
static CUSTOM_DOH_URL: std::sync::Mutex<String> = std::sync::Mutex::new(String::new());
/// Path of settings.json, resolved once at startup.
static SETTINGS_PATH: OnceLock<PathBuf> = OnceLock::new();

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
        // A long TTL stops apps re-querying the same blocked host (retry storm).
        let ttl: u32 = if ANTI_STORM_TTL.load(Ordering::Relaxed) { 300 } else { 15 };
        out.extend_from_slice(&ttl.to_be_bytes());
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

// ---------------------------------------------------------------------------
// Encrypted upstream (DNS over HTTPS, RFC 8484)
// ---------------------------------------------------------------------------

/// The resolver the UI currently has selected; falls back to Google DNS.
fn active_doh_url() -> String {
    let index = DNS_INDEX.load(Ordering::Relaxed) as usize;
    if index == 4 {
        let custom = CUSTOM_DOH_URL
            .lock()
            .map(|u| u.clone())
            .unwrap_or_default();
        if custom.starts_with("https://") {
            return custom;
        }
        return DOH_UPSTREAMS[0].0.to_string();
    }
    DOH_UPSTREAMS
        .get(index)
        .map(|(url, _)| url.to_string())
        .unwrap_or_else(|| DOH_UPSTREAMS[0].0.to_string())
}

/// RFC 8484 POST query. Returns the raw DNS answer, or None so the caller can
/// fall back to plain DNS rather than breaking the user's network.
fn query_doh(query: &[u8]) -> Option<Vec<u8>> {
    let url = active_doh_url();
    if !url.starts_with("https://") {
        return None;
    }
    let client = reqwest::blocking::Client::builder()
        .timeout(Duration::from_millis(2500))
        .build()
        .ok()?;
    let response = client
        .post(&url)
        .header("content-type", "application/dns-message")
        .header("accept", "application/dns-message")
        .body(query.to_vec())
        .send()
        .ok()?;
    if !response.status().is_success() {
        return None;
    }
    let body = response.bytes().ok()?;
    if body.len() < 12 {
        return None;
    }
    // Answers can arrive with a different transaction id; align it with the query.
    let mut out = body.to_vec();
    out[0] = query[0];
    out[1] = query[1];
    Some(out)
}

/// Encrypted first, then plain DNS so a broken resolver never bricks browsing.
fn resolve_upstream(query: &[u8]) -> Option<Vec<u8>> {
    query_doh(query).or_else(|| {
        // Plain DNS is only a safety net; it can be switched off so every lookup
        // stays encrypted even when the chosen resolver is unreachable.
        if CAPTIVE_FALLBACK.load(Ordering::Relaxed) {
            forward_query(query)
        } else {
            None
        }
    })
}

// ---------------------------------------------------------------------------
// Persisted settings (survive restarts, like Android's SharedPreferences)
// ---------------------------------------------------------------------------

fn persist_settings(table: &BlockTable, rules: &RuleSets) {
    let Some(path) = SETTINGS_PATH.get() else {
        return;
    };
    let mask = ACTIVE_MASK.load(Ordering::Relaxed) as u8;
    let mut allow: Vec<&String> = rules.allow.iter().collect();
    allow.sort();
    let mut block: Vec<&String> = rules.block.iter().collect();
    block.sort();
    let doc = serde_json::json!({
        "version": 1,
        "shieldActive": SHIELD_ACTIVE.load(Ordering::Relaxed),
        "mask": mask,
        "dnsIndex": DNS_INDEX.load(Ordering::Relaxed),
        "customDohUrl": CUSTOM_DOH_URL.lock().map(|u| u.clone()).unwrap_or_default(),
        "cnameUncloak": CNAME_UNCLOAK.load(Ordering::Relaxed),
        "antiStormTtl": ANTI_STORM_TTL.load(Ordering::Relaxed),
        "dohCanary": DOH_CANARY.load(Ordering::Relaxed),
        "startWithWindows": START_WITH_WINDOWS.load(Ordering::Relaxed),
        "logging": LOGGING_ENABLED.load(Ordering::Relaxed),
        "allow": allow,
        "block": block,
        "_rules": table.hashes.len(),
    });
    let tmp = path.with_extension("json.tmp");
    if std::fs::write(&tmp, doc.to_string()).is_ok() {
        let _ = std::fs::rename(&tmp, path);
    }
}

fn load_settings(path: &PathBuf, rules: &mut RuleSets) {
    let Ok(text) = std::fs::read_to_string(path) else {
        return;
    };
    let Ok(doc) = serde_json::from_str::<serde_json::Value>(&text) else {
        return;
    };
    let mask = doc.get("mask").and_then(|v| v.as_u64()).unwrap_or(MASK_BALANCED as u64) as u8;
    ACTIVE_MASK.store((mask & MASK_STRICT) as u64, Ordering::Relaxed);
    let dns = doc.get("dnsIndex").and_then(|v| v.as_u64()).unwrap_or(0);
    DNS_INDEX.store(dns.min(4), Ordering::Relaxed);
    if let Some(url) = doc.get("customDohUrl").and_then(|v| v.as_str()) {
        if let Ok(mut custom) = CUSTOM_DOH_URL.lock() {
            *custom = url.to_string();
        }
    }
    let flag = |key: &str, default: bool| {
        doc.get(key).and_then(|v| v.as_bool()).unwrap_or(default)
    };
    CNAME_UNCLOAK.store(flag("cnameUncloak", true), Ordering::Relaxed);
    ANTI_STORM_TTL.store(flag("antiStormTtl", true), Ordering::Relaxed);
    DOH_CANARY.store(flag("dohCanary", true), Ordering::Relaxed);
    START_WITH_WINDOWS.store(flag("startWithWindows", true), Ordering::Relaxed);
    LOGGING_ENABLED.store(flag("logging", true), Ordering::Relaxed);
    for key in ["allow", "block"] {
        if let Some(items) = doc.get(key).and_then(|v| v.as_array()) {
            let set = if key == "allow" { &mut rules.allow } else { &mut rules.block };
            for item in items {
                if let Some(domain) = item.as_str() {
                    set.insert(domain.to_string());
                }
            }
        }
    }
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
        m if m & 0x01 != 0 => "Game & In-App Ads",
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
    // Type 65 (HTTPS/SVCB) carries the browser's DoH/ECH settings. Stripping it
    // keeps encrypted-DNS-capable browsers going through the system resolver.
    if DOH_CANARY.load(Ordering::Relaxed) && qname_end + 2 <= query.len() {
        let qtype = u16::from_be_bytes([query[qname_end], query[qname_end + 1]]);
        if qtype == 65 {
            BLOCKED_QUERIES.fetch_add(1, Ordering::Relaxed);
            if LOGGING_ENABLED.load(Ordering::Relaxed) {
                push_log(&qname, "Browser DNS Guard", true);
            }
            return build_blocked_response(query, qname_end);
        }
    }
    match table.verdict(&qname, mask, rules) {
        Verdict::Blocked(hit) => {
            BLOCKED_QUERIES.fetch_add(1, Ordering::Relaxed);
            if LOGGING_ENABLED.load(Ordering::Relaxed) {
                push_log(&qname, category_label(hit), true);
            }
            build_blocked_response(query, qname_end)
        }
        Verdict::Allow => {
            let answer = resolve_upstream(query);
            if answer.is_some() && LOGGING_ENABLED.load(Ordering::Relaxed) {
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

/// Registers or removes the per-user Run entry behind "Start with Windows".
fn apply_start_with_windows(enabled: bool) {
    #[cfg(target_os = "windows")]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        let exe = match std::env::current_exe() {
            Ok(path) => path,
            Err(_) => return,
        };
        let quoted = format!("\"{}\"", exe.display());
        let script = if enabled {
            format!(
                "New-ItemProperty -Path 'HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Run' \
                 -Name 'Omit' -Value '{}' -PropertyType String -Force | Out-Null",
                quoted.replace('\'', "''")
            )
        } else {
            "Remove-ItemProperty -Path 'HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Run' \
             -Name 'Omit' -ErrorAction SilentlyContinue"
                .to_string()
        };
        let _ = std::process::Command::new("powershell")
            .args([
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy",
                "Bypass",
                "-Command",
                &script,
            ])
            .creation_flags(CREATE_NO_WINDOW)
            .output();
    }
    #[cfg(not(target_os = "windows"))]
    {
        let _ = enabled;
    }
}

/// True when every active adapter points at Omit's loopback resolver.
#[cfg(target_os = "windows")]
fn windows_dns_locked() -> bool {
    use std::os::windows::process::CommandExt;
    const CREATE_NO_WINDOW: u32 = 0x0800_0000;
    std::process::Command::new("powershell")
        .args([
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-Command",
            "Get-NetAdapter | Where-Object {$_.Status -eq 'Up'} | ForEach-Object { \
               $dns = Get-DnsClientServerAddress -InterfaceIndex $_.ifIndex -AddressFamily IPv4 -ErrorAction SilentlyContinue; \
               if (-not ($dns.ServerAddresses -contains '127.0.0.1')) { Write-Output 'unlocked' } }",
        ])
        .creation_flags(CREATE_NO_WINDOW)
        .output()
        .map(|o| !String::from_utf8_lossy(&o.stdout).contains("unlocked"))
        .unwrap_or(false)
}

#[cfg(not(target_os = "windows"))]
fn windows_dns_locked() -> bool {
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

/// Recent DNS decisions, newest first, for both the Activity tab and the
/// Home screen's "Recently blocked" list.
fn log_rows(limit: usize) -> Vec<LogJson> {
    let ring = match LOG_RING.lock() {
        Ok(ring) => ring,
        Err(poisoned) => poisoned.into_inner(),
    };
    ring.iter()
        .rev()
        .take(limit)
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
fn get_windows_logs() -> Vec<LogJson> {
    log_rows(LOG_CAPACITY)
}

#[derive(serde::Serialize)]
struct RecentBlockedJson {
    domain: String,
    category: String,
    time: String,
}

/// The three most recent distinct blocked domains, matching Android's
/// buildStateJson so the Home screen looks identical on both platforms.
#[tauri::command]
fn get_windows_recent_blocked() -> Vec<RecentBlockedJson> {
    let ring = match LOG_RING.lock() {
        Ok(ring) => ring,
        Err(poisoned) => poisoned.into_inner(),
    };
    let mut out: Vec<RecentBlockedJson> = Vec::new();
    let mut seen: std::collections::HashSet<String> = std::collections::HashSet::new();
    for entry in ring.iter().rev() {
        if !entry.blocked || out.len() >= 3 {
            break;
        }
        if seen.insert(entry.domain.clone()) {
            out.push(RecentBlockedJson {
                domain: entry.domain.clone(),
                category: entry.category.clone(),
                time: relative_time(entry.at_ms),
            });
        }
    }
    out
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
        {
            let rules = state.rules.lock().unwrap_or_else(|e| e.into_inner());
            persist_settings(&state.table, &rules);
        }
        true
    } else {
        SHIELD_ACTIVE.store(false, Ordering::SeqCst);
        stop_lan_listener();
        set_firewall_for_lan(false);
        restore_windows_dns();
        stop_dns_sinkhole();
        {
            let rules = state.rules.lock().unwrap_or_else(|e| e.into_inner());
            persist_settings(&state.table, &rules);
        }
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
    } & MASK_STRICT;
    ACTIVE_MASK.store(next as u64, Ordering::Relaxed);
    next
}

#[tauri::command]
fn set_windows_rules(domain: String, mode: String, state: tauri::State<AppState>) -> bool {
    let clean = domain.trim().trim_end_matches('.').to_ascii_lowercase();
    if clean.len() < 3 || !clean.contains('.') {
        return false;
    }
    {
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
        persist_settings(&state.table, &rules);
    }
    true
}

/// version.json for the updater. Read live from the website so a release is
/// seen immediately; the copy bundled at install time keeps the check honest
/// when the machine is offline.
#[tauri::command]
fn get_windows_update(app: tauri::AppHandle) -> Option<serde_json::Value> {
    let fetch_remote = || -> Option<serde_json::Value> {
        let client = reqwest::blocking::Client::builder()
            .timeout(Duration::from_millis(8000))
            .build()
            .ok()?;
        let response = client
            .get("https://acwac.github.io/Omit/version.json")
            .header("cache-control", "no-cache")
            .send()
            .ok()?;
        if !response.status().is_success() {
            return None;
        }
        response.json::<serde_json::Value>().ok()
    };
    let read_bundled = || -> Option<serde_json::Value> {
        let dir = app.path().resource_dir().ok()?;
        for candidate in [dir.join("version.json"), dir.join("_up_").join("version.json")] {
            if let Ok(text) = std::fs::read_to_string(&candidate) {
                if let Ok(doc) = serde_json::from_str::<serde_json::Value>(&text) {
                    return Some(doc);
                }
            }
        }
        None
    };
    let mut doc = fetch_remote().or_else(read_bundled)?;
    // The desktop build is versioned by Cargo; expose it as a code the shared
    // updater logic can compare (2.5.0 -> 250).
    let parts: Vec<u64> = env!("CARGO_PKG_VERSION")
        .split('.')
        .filter_map(|p| p.parse::<u64>().ok())
        .collect();
    let code = parts.first().copied().unwrap_or(0) * 100 + parts.get(1).copied().unwrap_or(0) * 10
        + parts.get(2).copied().unwrap_or(0);
    if let Some(obj) = doc.as_object_mut() {
        obj.insert("installedVersionCode".to_string(), serde_json::json!(code));
    }
    Some(doc)
}

/// Turns DNS logging on or off and remembers the choice.
#[tauri::command]
fn set_windows_logging(enabled: bool, state: tauri::State<AppState>) -> bool {
    LOGGING_ENABLED.store(enabled, Ordering::Relaxed);
    if !enabled {
        if let Ok(mut ring) = LOG_RING.lock() {
            ring.clear();
        }
    }
    let rules = state.rules.lock().unwrap_or_else(|e| e.into_inner());
    persist_settings(&state.table, &rules);
    true
}

/// Removes a domain from both rule sets, like Android's removeCustomRuleForDomain.
#[tauri::command]
fn clear_windows_rule(domain: String, state: tauri::State<AppState>) -> bool {
    let clean = domain.trim().trim_end_matches('.').to_ascii_lowercase();
    let mut rules = state.rules.lock().unwrap_or_else(|e| e.into_inner());
    let removed = rules.allow.remove(&clean) | rules.block.remove(&clean);
    persist_settings(&state.table, &rules);
    removed
}

/// Selects an encrypted resolver (index 4 = custom DoH URL).
#[tauri::command]
fn set_windows_dns(index: u8, custom_url: Option<String>) -> bool {
    if index > 4 {
        return false;
    }
    if index == 4 {
        let url = custom_url.unwrap_or_default().trim().to_string();
        if !url.starts_with("https://") || url.len() < 12 {
            return false;
        }
        if let Ok(mut custom) = CUSTOM_DOH_URL.lock() {
            *custom = url;
        }
    }
    DNS_INDEX.store(index as u64, Ordering::Relaxed);
    true
}

/// The five engine switches rendered by the shared Blocking screen.
#[tauri::command]
fn set_windows_engine(key: String, enabled: bool, state: tauri::State<AppState>) -> bool {
    match key.as_str() {
        "cname_uncloak" => CNAME_UNCLOAK.store(enabled, Ordering::Relaxed),
        "anti_storm_ttl" => ANTI_STORM_TTL.store(enabled, Ordering::Relaxed),
        "block_doh_canary" => DOH_CANARY.store(enabled, Ordering::Relaxed),
        "captive_portal_fallback" => {
            // Included so the shared five-row list stays honest: on Windows this
            // only controls whether upstream failures fall through to plain DNS.
            CAPTIVE_FALLBACK.store(enabled, Ordering::Relaxed)
        }
        "start_on_boot" => {
            START_WITH_WINDOWS.store(enabled, Ordering::Relaxed);
            apply_start_with_windows(enabled);
        }
        _ => return false,
    }
    let table = Arc::clone(&state.table);
    let rules = state.rules.lock().unwrap_or_else(|e| e.into_inner());
    persist_settings(&table, &rules);
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

// ---------------------------------------------------------------------------
// Share the filter with the local network
//
// Windows has no per-app VPN, so "Wi-Fi sharing" here means: the sinkhole also
// answers on the LAN address and tells other devices where to find it. A
// device that sets this PC as its DNS server is then filtered.
// ---------------------------------------------------------------------------

/// The LAN address other devices should point their DNS at.
#[cfg(target_os = "windows")]
fn lan_ipv4() -> Option<String> {
    use std::os::windows::process::CommandExt;
    const CREATE_NO_WINDOW: u32 = 0x0800_0000;
    // Route lookup: the source address Windows would use for an internet route
    // is by definition a reachable LAN address.
    let output = std::process::Command::new("powershell")
        .args([
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-Command",
            "(Find-NetRoute -RemoteIPAddress 8.8.8.8 -ErrorAction SilentlyContinue | \
              Select-Object -First 1).IPAddress",
        ])
        .creation_flags(CREATE_NO_WINDOW)
        .output()
        .ok()?;
    let text = String::from_utf8_lossy(&output.stdout);
    let ip = text.trim().lines().next()?.trim().to_string();
    if ip.is_empty() || ip == "127.0.0.1" {
        None
    } else {
        Some(ip)
    }
}

#[cfg(not(target_os = "windows"))]
fn lan_ipv4() -> Option<String> {
    None
}

/// Opens (or closes) the firewall rules that let the LAN reach DNS and the
/// proxy port. Both are needed: a device may use either one.
fn set_firewall_for_lan(enable: bool) {
    let script = if enable {
        "foreach ($r in @(@('Omit LAN DNS','UDP',53), @('Omit LAN Proxy','TCP',8080))) { \
           if (-not (Get-NetFirewallRule -DisplayName $r[0] -ErrorAction SilentlyContinue)) { \
             New-NetFirewallRule -DisplayName $r[0] -Direction Inbound -Action Allow \
               -Protocol $r[1] -LocalPort $r[2] -Profile Private | Out-Null } }"
    } else {
        "Get-NetFirewallRule -DisplayName 'Omit LAN DNS','Omit LAN Proxy' -ErrorAction SilentlyContinue | \
           Remove-NetFirewallRule -ErrorAction SilentlyContinue"
    };
    run_powershell(script);
}

/// Shares the filter with the rest of the LAN: a DNS listener on the LAN
/// address plus a small HTTP proxy, both backed by the same block table.
/// Loopback filtering is untouched, so this PC keeps working either way.
fn start_lan_listener(table: Arc<BlockTable>, rules: Arc<std::sync::Mutex<RuleSets>>) -> bool {
    let Some(ip) = lan_ipv4() else {
        return false;
    };
    // The sinkhole already owns 127.0.0.1:53, so bind the LAN address only:
    // binding 0.0.0.0 here would collide with it.
    let Ok(dns_socket) = UdpSocket::bind(format!("{ip}:53")) else {
        return false;
    };
    let _ = dns_socket.set_read_timeout(Some(Duration::from_millis(250)));
    let dns_socket = Arc::new(dns_socket);
    let mut spawned = 0usize;
    for _ in 0..2 {
        let sock = Arc::clone(&dns_socket);
        let table = Arc::clone(&table);
        let rules = Arc::clone(&rules);
        LAN_WORKERS.fetch_add(1, Ordering::SeqCst);
        let ok = std::thread::Builder::new()
            .name("omit-lan-dns".into())
            .spawn(move || {
                let mut buf = [0u8; 1500];
                while LAN_SHARING.load(Ordering::Relaxed) {
                    match sock.recv_from(&mut buf) {
                        Ok((len, src)) => {
                            if len < 12 {
                                continue;
                            }
                            TOTAL_QUERIES.fetch_add(1, Ordering::Relaxed);
                            let snapshot = rules.lock().unwrap_or_else(|e| e.into_inner());
                            let blocked = match read_qname(&buf[..len]) {
                                Some((name, _)) => matches!(
                                    table.verdict(
                                        &name,
                                        ACTIVE_MASK.load(Ordering::Relaxed) as u8,
                                        &snapshot
                                    ),
                                    Verdict::Blocked(_)
                                ),
                                None => false,
                            };
                            let response = serve_query(&table, &snapshot, &buf[..len]);
                            drop(snapshot);
                            if blocked {
                                LAN_BLOCKED.fetch_add(1, Ordering::Relaxed);
                            }
                            if let Some(response) = response {
                                let _ = sock.send_to(&response, src);
                            }
                        }
                        Err(err) => match err.kind() {
                            std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut => {}
                            _ => std::thread::sleep(Duration::from_millis(50)),
                        },
                    }
                }
                LAN_WORKERS.fetch_sub(1, Ordering::SeqCst);
            })
            .is_ok();
        if ok {
            spawned += 1;
        } else {
            LAN_WORKERS.fetch_sub(1, Ordering::SeqCst);
        }
    }
    if spawned == 0 {
        return false;
    }

    // The PAC file and proxy live on the same port as the Android card.
    if let Ok(tcp) = std::net::TcpListener::bind(("0.0.0.0", LAN_PROXY_PORT)) {
        let _ = tcp.set_nonblocking(true);
        let listener = Arc::new(tcp);
        std::thread::Builder::new()
            .name("omit-lan-proxy".into())
            .spawn(move || {
                while LAN_SHARING.load(Ordering::Relaxed) {
                    match listener.accept() {
                        Ok((stream, _)) => {
                            let table = Arc::clone(&table);
                            let rules = Arc::clone(&rules);
                            std::thread::spawn(move || {
                                let _ = serve_lan_http(stream, &table, &rules);
                            });
                        }
                        Err(err) if err.kind() == std::io::ErrorKind::WouldBlock => {
                            std::thread::sleep(Duration::from_millis(100));
                        }
                        Err(_) => std::thread::sleep(Duration::from_millis(200)),
                    }
                }
            })
            .ok();
    }
    true
}

fn stop_lan_listener() {
    LAN_SHARING.store(false, Ordering::SeqCst);
    // Let the loops notice the flag before the ports are reused.
    let deadline = std::time::Instant::now() + Duration::from_millis(800);
    while LAN_WORKERS.load(Ordering::SeqCst) > 0 && std::time::Instant::now() < deadline {
        std::thread::sleep(Duration::from_millis(10));
    }
}

/// Minimal HTTP proxy for devices pointed at this PC. HTTPS is tunnelled via
/// CONNECT with the hostname filtered; plain HTTP is rewritten to origin-form.
fn serve_lan_http(
    mut stream: std::net::TcpStream,
    table: &Arc<BlockTable>,
    rules: &Arc<std::sync::Mutex<RuleSets>>,
) -> Option<()> {
    use std::io::{BufRead, BufReader, Read, Write};
    stream.set_read_timeout(Some(Duration::from_secs(20))).ok()?;
    let mut reader = BufReader::new(stream.try_clone().ok()?);
    let mut request_line = String::new();
    reader.read_line(&mut request_line).ok()?;
    if request_line.is_empty() {
        return None;
    }
    let mut headers: Vec<String> = Vec::new();
    loop {
        let mut line = String::new();
        if reader.read_line(&mut line).ok()? == 0 {
            break;
        }
        if line == "\r\n" || line == "\n" {
            break;
        }
        headers.push(line);
        if headers.len() > 100 {
            break;
        }
    }
    let parts: Vec<&str> = request_line.split_whitespace().collect();
    if parts.len() < 2 {
        return None;
    }
    let method = parts[0];
    let target = parts[1];

    if method == "GET" && target.ends_with("/omit.pac") {
        let ip = LAN_ADDRESS.lock().map(|i| i.clone()).unwrap_or_default();
        let pac = format!(
            "function FindProxyForURL(url, host) {{ return \"PROXY {ip}:{LAN_PROXY_PORT}; DIRECT\"; }}"
        );
        let response = format!(
            "HTTP/1.1 200 OK\r\nContent-Type: application/x-ns-proxy-autoconfig\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
            pac.len(),
            pac
        );
        stream.write_all(response.as_bytes()).ok()?;
        return Some(());
    }

    let is_blocked = |host: &str| -> bool {
        let mask = ACTIVE_MASK.load(Ordering::Relaxed) as u8;
        let snapshot = rules.lock().unwrap_or_else(|e| e.into_inner());
        matches!(table.verdict(host, mask, &snapshot), Verdict::Blocked(_))
    };

    if method == "CONNECT" {
        let (host, port) = match target.rsplit_once(':') {
            Some((h, p)) => (h.to_string(), p.parse::<u16>().unwrap_or(443)),
            None => (target.to_string(), 443u16),
        };
        if is_blocked(&host) {
            LAN_BLOCKED.fetch_add(1, Ordering::Relaxed);
            BLOCKED_QUERIES.fetch_add(1, Ordering::Relaxed);
            stream
                .write_all(b"HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n")
                .ok()?;
            return Some(());
        }
        let Ok(mut origin) = std::net::TcpStream::connect((host.as_str(), port)) else {
            stream
                .write_all(b"HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n")
                .ok()?;
            return None;
        };
        stream
            .write_all(b"HTTP/1.1 200 Connection Established\r\n\r\n")
            .ok()?;
        // Anything already buffered belongs to the tunnel.
        let mut client = stream.try_clone().ok()?;
        let mut upstream = origin.try_clone().ok()?;
        std::thread::spawn(move || {
            let _ = std::io::copy(&mut client, &mut upstream);
            let _ = upstream.shutdown(std::net::Shutdown::Write);
        });
        let _ = std::io::copy(&mut origin, &mut stream);
        return Some(());
    }

    if method != "GET" && method != "POST" && method != "HEAD" {
        stream
            .write_all(b"HTTP/1.1 501 Not Implemented\r\nConnection: close\r\n\r\n")
            .ok()?;
        return None;
    }

    let authority = target.strip_prefix("http://").unwrap_or(target);
    let (authority, path) = match authority.split_once('/') {
        Some((a, p)) => (a, format!("/{p}")),
        None => (authority, "/".to_string()),
    };
    let (host, port) = match authority.rsplit_once(':') {
        Some((h, p)) => (h.to_string(), p.parse::<u16>().unwrap_or(80)),
        None => (authority.to_string(), 80u16),
    };
    if host.is_empty() {
        stream
            .write_all(b"HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n")
            .ok()?;
        return None;
    }
    if is_blocked(&host) {
        LAN_BLOCKED.fetch_add(1, Ordering::Relaxed);
        BLOCKED_QUERIES.fetch_add(1, Ordering::Relaxed);
        stream
            .write_all(b"HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n")
            .ok()?;
        return Some(());
    }
    let mut origin = std::net::TcpStream::connect((host.as_str(), port)).ok()?;
    let mut out = format!("{method} {path} HTTP/1.1\r\n");
    for line in &headers {
        let name = line.split(':').next().unwrap_or("").trim().to_ascii_lowercase();
        if matches!(
            name.as_str(),
            "proxy-connection" | "connection" | "keep-alive" | "proxy-authorization" | "te" | "trailer" | "upgrade"
        ) {
            continue;
        }
        out.push_str(line);
    }
    out.push_str("\r\n");
    origin.write_all(out.as_bytes()).ok()?;
    let mut buf = [0u8; 8192];
    loop {
        match reader.read(&mut buf) {
            Ok(0) => break,
            Ok(n) => {
                if origin.write_all(&buf[..n]).is_err() {
                    break;
                }
            }
            Err(_) => break,
        }
    }
    let _ = origin.shutdown(std::net::Shutdown::Write);
    let mut back = [0u8; 8192];
    loop {
        match origin.read(&mut back) {
            Ok(0) => break,
            Ok(n) => {
                if stream.write_all(&back[..n]).is_err() {
                    break;
                }
            }
            Err(_) => break,
        }
    }
    Some(())
}

static LAN_SHARING: AtomicBool = AtomicBool::new(false);
static LAN_ADDRESS: std::sync::Mutex<String> = std::sync::Mutex::new(String::new());

/// Enables sharing: the sinkhole keeps answering on loopback while a second
/// listener serves the LAN address, so other devices can use this PC either as
/// their DNS server or as their HTTP proxy.
#[tauri::command]
fn set_windows_lan_sharing(enable: bool, state: tauri::State<AppState>) -> bool {
    if !enable {
        LAN_SHARING.store(false, Ordering::SeqCst);
        set_firewall_for_lan(false);
        stop_lan_listener();
        if let Ok(mut ip) = LAN_ADDRESS.lock() {
            ip.clear();
        }
        return true;
    }
    if LAN_SHARING.load(Ordering::SeqCst) {
        return true;
    }
    if !is_elevated() {
        return false;
    }
    if state.table.hashes.is_empty() {
        return false;
    }
    let Some(ip) = lan_ipv4() else {
        return false;
    };
    if let Ok(mut slot) = LAN_ADDRESS.lock() {
        *slot = ip.clone();
    }
    // The worker loops exit when this is false, so it must be set before the
    // listeners start, and rolled back if they cannot bind.
    LAN_SHARING.store(true, Ordering::SeqCst);
    if !start_lan_listener(Arc::clone(&state.table), Arc::clone(&state.rules)) {
        LAN_SHARING.store(false, Ordering::SeqCst);
        if let Ok(mut slot) = LAN_ADDRESS.lock() {
            slot.clear();
        }
        return false;
    }
    set_firewall_for_lan(true);
    true
}

#[tauri::command]
fn get_windows_lan_info() -> serde_json::Value {
    let ip = LAN_ADDRESS.lock().map(|i| i.clone()).unwrap_or_default();
    serde_json::json!({
        "enabled": LAN_SHARING.load(Ordering::Relaxed),
        // DNS server address other devices should use.
        "dnsAddress": ip,
        // PAC URL / proxy for browsers, mirroring the Android card.
        "pacUrl": if ip.is_empty() { String::new() } else { format!("http://{ip}:{LAN_PROXY_PORT}/omit.pac") },
        "proxy": if ip.is_empty() { String::new() } else { format!("{ip}:{LAN_PROXY_PORT}") },
        "port": LAN_PROXY_PORT,
        "blockedCount": LAN_BLOCKED.load(Ordering::Relaxed),
    })
}

// ---------------------------------------------------------------------------
// Burner email / SMS (same providers as the Android BurnerManager)
// ---------------------------------------------------------------------------

fn burner_path() -> Option<PathBuf> {
    SETTINGS_PATH.get().map(|p| p.with_file_name("burner.json"))
}

fn burner_load() -> serde_json::Value {
    burner_path()
        .and_then(|p| std::fs::read_to_string(p).ok())
        .and_then(|t| serde_json::from_str(&t).ok())
        .unwrap_or_else(|| serde_json::json!({}))
}

fn burner_save(doc: &serde_json::Value) {
    if let Some(path) = burner_path() {
        let _ = std::fs::write(path, doc.to_string());
    }
}

fn http_get(url: &str, bearer: Option<&str>) -> Result<String, String> {
    let client = reqwest::blocking::Client::builder()
        .timeout(Duration::from_millis(9000))
        .user_agent("Mozilla/5.0")
        .build()
        .map_err(|e| e.to_string())?;
    let mut request = client.get(url).header("accept", "application/json,text/html,*/*");
    if let Some(token) = bearer {
        request = request.bearer_auth(token);
    }
    let response = request.send().map_err(|e| e.to_string())?;
    response.text().map_err(|e| e.to_string())
}

fn http_post_json(url: &str, body: &serde_json::Value) -> Result<String, String> {
    let client = reqwest::blocking::Client::builder()
        .timeout(Duration::from_millis(9000))
        .user_agent("Mozilla/5.0")
        .build()
        .map_err(|e| e.to_string())?;
    let response = client
        .post(url)
        .header("content-type", "application/json")
        .json(body)
        .send()
        .map_err(|e| e.to_string())?;
    response.text().map_err(|e| e.to_string())
}

/// First run of 4..=8 digits, matching the Android OTP_REGEX.
fn extract_otp(text: &str) -> String {
    let mut run_start: Option<usize> = None;
    let bytes = text.as_bytes();
    for (i, ch) in text.char_indices() {
        if ch.is_ascii_digit() {
            if run_start.is_none() {
                run_start = Some(i);
            }
        } else if let Some(start) = run_start.take() {
            let len = i - start;
            if (4..=8).contains(&len) && is_word_bounded(bytes, start, i) {
                return text[start..i].to_string();
            }
        }
    }
    if let Some(start) = run_start {
        let len = text.len() - start;
        if (4..=8).contains(&len) {
            return text[start..].to_string();
        }
    }
    String::new()
}

fn is_word_bounded(bytes: &[u8], start: usize, end: usize) -> bool {
    let before_ok = start == 0 || !bytes[start - 1].is_ascii_alphanumeric();
    let after_ok = end >= bytes.len() || !bytes[end].is_ascii_alphanumeric();
    before_ok && after_ok
}

fn burner_short_id() -> String {
    use std::time::{SystemTime, UNIX_EPOCH};
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.subsec_nanos())
        .unwrap_or(0);
    format!("{:08x}{:04x}", now, (now >> 8) & 0xffff)
}

fn burner_email(force_new: bool) -> serde_json::Value {
    let mut doc = burner_load();
    let saved_addr = doc.get("address").and_then(|v| v.as_str()).unwrap_or("").to_string();
    let saved_token = doc.get("token").and_then(|v| v.as_str()).unwrap_or("").to_string();
    if !force_new && !saved_addr.is_empty() && !saved_token.is_empty() {
        return serde_json::json!({ "address": saved_addr, "provider": "mail.tm" });
    }
    // mail.tm first, then Guerrilla Mail as the fallback provider.
    let attempt = (|| -> Result<serde_json::Value, String> {
        let domains = http_get("https://api.mail.tm/domains", None)?;
        let parsed: serde_json::Value = serde_json::from_str(&domains).map_err(|e| e.to_string())?;
        let domain = parsed
            .get("hydra:member")
            .and_then(|m| m.get(0))
            .and_then(|d| d.get("domain"))
            .and_then(|d| d.as_str())
            .ok_or("no mail.tm domain")?
            .to_string();
        let user = format!("omit{}", burner_short_id());
        let address = format!("{user}@{domain}");
        let password = burner_short_id();
        http_post_json(
            "https://api.mail.tm/accounts",
            &serde_json::json!({ "address": address, "password": password }),
        )?;
        let token_raw = http_post_json(
            "https://api.mail.tm/token",
            &serde_json::json!({ "address": address, "password": password }),
        )?;
        let token_doc: serde_json::Value =
            serde_json::from_str(&token_raw).map_err(|e| e.to_string())?;
        let token = token_doc
            .get("token")
            .and_then(|t| t.as_str())
            .ok_or("no token")?
            .to_string();
        doc = serde_json::json!({
            "provider": "mail.tm", "address": address, "token": token, "sid": "",
        });
        burner_save(&doc);
        Ok(serde_json::json!({ "address": address, "provider": "mail.tm" }))
    })();
    if let Ok(value) = attempt {
        return value;
    }
    let fallback = (|| -> Result<serde_json::Value, String> {
        let raw = http_get(
            "https://api.guerrillamail.com/ajax.php?f=get_email_address",
            None,
        )?;
        let parsed: serde_json::Value = serde_json::from_str(&raw).map_err(|e| e.to_string())?;
        let address = parsed
            .get("email_addr")
            .and_then(|a| a.as_str())
            .ok_or("no guerrilla address")?
            .to_string();
        let sid = parsed
            .get("sid_token")
            .and_then(|a| a.as_str())
            .ok_or("no sid")?
            .to_string();
        doc = serde_json::json!({
            "provider": "guerrillamail", "address": address, "token": "", "sid": sid,
        });
        burner_save(&doc);
        Ok(serde_json::json!({ "address": address, "provider": "guerrillamail" }))
    })();
    fallback.unwrap_or_else(|e| serde_json::json!({ "address": "", "error": e }))
}

fn burner_inbox() -> Vec<serde_json::Value> {
    let doc = burner_load();
    let token = doc.get("token").and_then(|v| v.as_str()).unwrap_or("").to_string();
    let sid = doc.get("sid").and_then(|v| v.as_str()).unwrap_or("").to_string();
    let mut out: Vec<serde_json::Value> = Vec::new();
    if !token.is_empty() {
        let Ok(raw) = http_get("https://api.mail.tm/messages", Some(&token)) else {
            return out;
        };
        let Ok(parsed) = serde_json::from_str::<serde_json::Value>(&raw) else {
            return out;
        };
        let empty = Vec::new();
        let members = parsed
            .get("hydra:member")
            .and_then(|m| m.as_array())
            .unwrap_or(&empty);
        for message in members.iter().take(15) {
            let id = message.get("id").and_then(|v| v.as_str()).unwrap_or("");
            let subject = message.get("subject").and_then(|v| v.as_str()).unwrap_or("");
            let intro = message.get("intro").and_then(|v| v.as_str()).unwrap_or("");
            let from = message
                .get("from")
                .and_then(|f| f.get("address"))
                .and_then(|f| f.as_str())
                .unwrap_or("Unknown");
            let mut full = format!("{subject} {intro}");
            if !id.is_empty() {
                if let Ok(detail) = http_get(&format!("https://api.mail.tm/messages/{id}"), Some(&token)) {
                    if let Ok(doc) = serde_json::from_str::<serde_json::Value>(&detail) {
                        if let Some(text) = doc.get("text").and_then(|t| t.as_str()) {
                            full.push(' ');
                            full.push_str(text);
                        }
                    }
                }
            }
            out.push(serde_json::json!({
                "id": id, "from": from, "subject": subject, "intro": intro,
                "otp": extract_otp(&full),
            }));
        }
    } else if !sid.is_empty() {
        let Ok(raw) = http_get(
            &format!("https://api.guerrillamail.com/ajax.php?f=check_email&seq=0&sid_token={sid}"),
            None,
        ) else {
            return out;
        };
        let Ok(parsed) = serde_json::from_str::<serde_json::Value>(&raw) else {
            return out;
        };
        let empty = Vec::new();
        let list = parsed.get("list").and_then(|l| l.as_array()).unwrap_or(&empty);
        for message in list.iter().take(15) {
            let subject = message.get("mail_subject").and_then(|v| v.as_str()).unwrap_or("");
            let intro = message.get("mail_excerpt").and_then(|v| v.as_str()).unwrap_or("");
            out.push(serde_json::json!({
                "id": message.get("mail_id").and_then(|v| v.as_str()).unwrap_or(""),
                "from": message.get("mail_from").and_then(|v| v.as_str()).unwrap_or("Unknown"),
                "subject": subject,
                "intro": intro,
                "otp": extract_otp(&format!("{subject} {intro}")),
            }));
        }
    }
    out
}

/// Scrapes anonymsms.com the same way BurnerManager does: /number/<digits>.
fn burner_sms_numbers() -> Vec<serde_json::Value> {
    let mut out: Vec<serde_json::Value> = Vec::new();
    let mut seen: std::collections::HashSet<String> = std::collections::HashSet::new();
    for url in [
        "https://anonymsms.com/",
        "https://anonymsms.com/united-states/",
        "https://anonymsms.com/united-kingdom/",
    ] {
        let Ok(html) = http_get(url, None) else {
            continue;
        };
        for digits in scrape_number_paths(&html) {
            if seen.insert(digits.clone()) {
                out.push(serde_json::json!({ "digits": digits, "formatted": format!("+{digits}") }));
                if out.len() >= 15 {
                    return out;
                }
            }
        }
    }
    out
}

/// Finds every `/number/<9..15 digits>/` occurrence in a page.
fn scrape_number_paths(html: &str) -> Vec<String> {
    let mut out = Vec::new();
    let marker = "/number/";
    let mut rest = html;
    while let Some(pos) = rest.find(marker) {
        let tail = &rest[pos + marker.len()..];
        let digits: String = tail.chars().take_while(|c| c.is_ascii_digit()).collect();
        if (9..=15).contains(&digits.len()) {
            out.push(digits);
        }
        rest = tail;
    }
    out
}

fn burner_sms_messages(digits: &str) -> Vec<serde_json::Value> {
    let digits: String = digits.chars().filter(|c| c.is_ascii_digit()).collect();
    if digits.len() < 9 || digits.len() > 15 {
        return Vec::new();
    }
    let Ok(html) = http_get(&format!("https://anonymsms.com/number/{digits}/"), None) else {
        return Vec::new();
    };
    let html = html.replace("\\\"", "\"");
    let mut out: Vec<serde_json::Value> = Vec::new();
    // Messages are embedded as {"from": "...", "text": "...", "receivedAt": "..."}.
    let mut rest = html.as_str();
    while let Some(pos) = rest.find("\"from\"") {
        let window = &rest[pos..(pos + 600).min(rest.len())];
        rest = &rest[pos + 6..];
        let from = json_field(window, "from");
        let text = json_field(window, "text");
        let at = json_field(window, "receivedAt");
        if text.is_empty() {
            continue;
        }
        out.push(serde_json::json!({
            "from": from, "text": text, "time": at, "otp": extract_otp(&text),
        }));
        if out.len() >= 20 {
            break;
        }
    }
    out
}

/// Extracts "key": "value" (or a number) from a JSON-ish fragment.
fn json_field(fragment: &str, key: &str) -> String {
    let marker = format!("\"{key}\"");
    let Some(pos) = fragment.find(&marker) else {
        return String::new();
    };
    let tail = &fragment[pos + marker.len()..];
    let Some(colon) = tail.find(':') else {
        return String::new();
    };
    let value = tail[colon + 1..].trim_start();
    if let Some(stripped) = value.strip_prefix('"') {
        let mut out = String::new();
        let mut chars = stripped.chars();
        while let Some(ch) = chars.next() {
            if ch == '\\' {
                if let Some(next) = chars.next() {
                    out.push(next);
                }
            } else if ch == '"' {
                break;
            } else {
                out.push(ch);
            }
        }
        out
    } else {
        value
            .chars()
            .take_while(|c| c.is_ascii_digit() || *c == '.' || *c == '-' || *c == ':')
            .collect()
    }
}

#[tauri::command]
fn get_burner_email(force_new: bool) -> serde_json::Value {
    burner_email(force_new)
}

#[tauri::command]
fn get_burner_inbox() -> Vec<serde_json::Value> {
    burner_inbox()
}

#[tauri::command]
fn get_burner_sms_numbers() -> Vec<serde_json::Value> {
    burner_sms_numbers()
}

#[tauri::command]
fn get_burner_sms_messages(digits: String) -> Vec<serde_json::Value> {
    burner_sms_messages(&digits)
}

/// The whole UI state, mirroring Android's getStateJson so the shared screens
/// are driven by real values on both platforms.
#[derive(serde::Serialize)]
struct StateSnapshot {
    dark: bool,
    shield: String,
    countdown: u64,
    intensity: String,
    categories: Vec<bool>,
    engine: Vec<bool>,
    dns: u64,
    custom_doh_url: String,
    game_mode: bool,
    offline_game_count: u64,
    blocked_count: u64,
    total_queries: u64,
    active_rules: u64,
    private_dns_host: String,
    allow_domains: Vec<String>,
    block_domains: Vec<String>,
    custom_urls: Vec<String>,
    logging: bool,
    syncing: bool,
    #[serde(rename = "recentBlocked")]
    recent_blocked: Vec<RecentBlockedJson>,
    logs: Vec<LogJson>,
}

fn intensity_name(mask: u8) -> &'static str {
    match mask & MASK_STRICT {
        0x09 => "Light",
        0x3F => "Balanced",
        0xFF => "Strict",
        _ => "Custom",
    }
}

#[tauri::command]
fn get_windows_state(state: tauri::State<AppState>) -> StateSnapshot {
    let mask = ACTIVE_MASK.load(Ordering::Relaxed) as u8;
    let allow;
    let block;
    {
        let rules = state.rules.lock().unwrap_or_else(|e| e.into_inner());
        let mut a: Vec<String> = rules.allow.iter().cloned().collect();
        a.sort();
        allow = a;
        let mut b: Vec<String> = rules.block.iter().cloned().collect();
        b.sort();
        block = b;
    }
    StateSnapshot {
        dark: true,
        shield: if SHIELD_ACTIVE.load(Ordering::Relaxed) {
            "protected".to_string()
        } else {
            "off".to_string()
        },
        countdown: 60,
        intensity: intensity_name(mask).to_string(),
        categories: (0..8).map(|i| mask & (1 << i) != 0).collect(),
        engine: vec![
            CNAME_UNCLOAK.load(Ordering::Relaxed),
            ANTI_STORM_TTL.load(Ordering::Relaxed),
            DOH_CANARY.load(Ordering::Relaxed),
            CAPTIVE_FALLBACK.load(Ordering::Relaxed),
            START_WITH_WINDOWS.load(Ordering::Relaxed),
        ],
        dns: DNS_INDEX.load(Ordering::Relaxed),
        custom_doh_url: CUSTOM_DOH_URL.lock().map(|u| u.clone()).unwrap_or_default(),
        game_mode: false,
        offline_game_count: 0,
        blocked_count: BLOCKED_QUERIES.load(Ordering::Relaxed),
        total_queries: TOTAL_QUERIES.load(Ordering::Relaxed),
        active_rules: RULE_COUNT.load(Ordering::Relaxed),
        private_dns_host: String::new(),
        allow_domains: allow,
        block_domains: block,
        custom_urls: Vec::new(),
        logging: LOGGING_ENABLED.load(Ordering::Relaxed),
        syncing: false,
        recent_blocked: get_windows_recent_blocked(),
        logs: log_rows(LOG_CAPACITY),
    }
}

/// Whether every adapter is still pinned to Omit (drives the DNS Lock row).
#[tauri::command]
fn get_windows_dns_lock() -> bool {
    windows_dns_locked()
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
            // Settings live beside the app config so toggles survive restarts.
            let settings_dir = app
                .path()
                .app_config_dir()
                .unwrap_or_else(|_| std::env::temp_dir());
            let _ = std::fs::create_dir_all(&settings_dir);
            let settings_path = settings_dir.join("settings.json");
            let _ = SETTINGS_PATH.set(settings_path.clone());
            let mut loaded_rules = RuleSets::default();
            load_settings(&settings_path, &mut loaded_rules);
            let rules = Arc::new(std::sync::Mutex::new(loaded_rules));
            app.manage(AppState {
                table: Arc::clone(&table),
                rules: Arc::clone(&rules),
            });

            // Re-apply the saved engine state without blocking the UI thread.
            let table_for_start = Arc::clone(&table);
            let rules_for_start = Arc::clone(&rules);
            let start_enabled = START_WITH_WINDOWS.load(Ordering::Relaxed);
            apply_start_with_windows(start_enabled);
            let wanted = std::fs::read_to_string(&settings_path)
                .ok()
                .and_then(|t| serde_json::from_str::<serde_json::Value>(&t).ok())
                .and_then(|v| v.get("shieldActive").and_then(|b| b.as_bool()))
                .unwrap_or(false);
            if wanted && is_elevated() && !table_for_start.hashes.is_empty() {
                std::thread::spawn(move || {
                    if start_dns_sinkhole(Arc::clone(&table_for_start), Arc::clone(&rules_for_start)) {
                        set_windows_local_dns();
                        SHIELD_ACTIVE.store(true, Ordering::SeqCst);
                    }
                });
            }

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
            get_windows_stats,
            set_windows_categories,
            set_windows_rules,
            get_windows_rules,
            get_windows_logs,
            clear_windows_logs,
            get_shield_status,
            toggle_windows_shield,
            set_windows_engine,
            set_windows_dns,
            clear_windows_rule,
            set_windows_logging,
            get_windows_update,
            get_windows_state,
            get_windows_dns_lock,
            set_windows_lan_sharing,
            get_windows_lan_info,
            get_windows_recent_blocked,
            get_burner_email,
            get_burner_inbox,
            get_burner_sms_numbers,
            get_burner_sms_messages,
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
