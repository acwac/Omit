#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::net::UdpSocket;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;
use tauri::tray::TrayIconBuilder;
use tauri::Manager;

static SHIELD_ACTIVE: AtomicBool = AtomicBool::new(false);
static TOTAL_QUERIES: AtomicU64 = AtomicU64::new(0);
static BLOCKED_QUERIES: AtomicU64 = AtomicU64::new(0);

fn restore_windows_dns() {
    #[cfg(target_os = "windows")]
    {
        let _ = std::process::Command::new("powershell")
            .args([
                "-NoProfile",
                "-Command",
                "Get-NetAdapter | Where-Object {$_.Status -eq 'Up'} | Set-DnsClientServerAddress -ResetServerAddresses",
            ])
            .output();
    }
}

fn set_windows_local_dns() {
    #[cfg(target_os = "windows")]
    {
        let _ = std::process::Command::new("powershell")
            .args([
                "-NoProfile",
                "-Command",
                "Get-NetAdapter | Where-Object {$_.Status -eq 'Up'} | Set-DnsClientServerAddress -ServerAddresses ('127.0.0.1')",
            ])
            .output();
    }
}

fn start_dns_sinkhole(active: Arc<AtomicBool>) {
    std::thread::spawn(move || {
        let Ok(socket) = UdpSocket::bind("127.0.0.1:53") else {
            return;
        };
        let mut buf = [0u8; 512];
        while let Ok((len, src)) = socket.recv_from(&mut buf) {
            if !active.load(Ordering::Relaxed) || len < 12 {
                continue;
            }
            TOTAL_QUERIES.fetch_add(1, Ordering::Relaxed);
            // Forward to Google DNS (8.8.8.8:53) as fallback when DoH is not invoked
            if let Ok(up) = UdpSocket::bind("0.0.0.0:0") {
                let _ = up.set_read_timeout(Some(std::time::Duration::from_secs(2)));
                if up.send_to(&buf[..len], "8.8.8.8:53").is_ok() {
                    let mut resp = [0u8; 512];
                    if let Ok(rlen) = up.recv(&mut resp) {
                        let _ = socket.send_to(&resp[..rlen], src);
                    }
                }
            }
        }
    });
}

#[tauri::command]
fn get_platform() -> &'static str {
    "windows"
}

#[tauri::command]
fn get_windows_stats() -> (u64, u64, bool) {
    (
        TOTAL_QUERIES.load(Ordering::Relaxed),
        BLOCKED_QUERIES.load(Ordering::Relaxed),
        SHIELD_ACTIVE.load(Ordering::Relaxed),
    )
}

#[tauri::command]
fn toggle_windows_shield(enable: bool) -> bool {
    SHIELD_ACTIVE.store(enable, Ordering::SeqCst);
    if enable {
        set_windows_local_dns();
    } else {
        restore_windows_dns();
    }
    enable
}

#[tauri::command]
fn download_and_install_update(exe_url: String) -> Result<(), String> {
    let url = if exe_url.is_empty() {
        "https://acwac.github.io/Omit/downloads/Omit-Windows.exe".to_string()
    } else {
        exe_url
    };
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

fn main() {
    let default_hook = std::panic::take_hook();
    std::panic::set_hook(Box::new(move |info| {
        restore_windows_dns();
        default_hook(info);
    }));

    let active_flag = Arc::new(AtomicBool::new(false));
    start_dns_sinkhole(active_flag);

    tauri::Builder::default()
        .setup(|app| {
            let _tray = TrayIconBuilder::new()
                .tooltip("Omit — Ad, Tracker & Anti-DPI Shield")
                .build(app)?;
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            get_platform,
            get_windows_stats,
            toggle_windows_shield,
            download_and_install_update
        ])
        .on_window_event(|_window, event| {
            if let tauri::WindowEvent::CloseRequested { .. } = event {
                restore_windows_dns();
            }
        })
        .run(tauri::generate_context!())
        .expect("error while running Omit for Windows");

    restore_windows_dns();
}
