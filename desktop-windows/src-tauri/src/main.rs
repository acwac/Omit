#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::sync::atomic::{AtomicBool, Ordering};
use tauri::Manager;

static SHIELD_ACTIVE: AtomicBool = AtomicBool::new(false);

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

#[tauri::command]
fn get_platform() -> &'static str {
    "windows"
}

#[tauri::command]
fn toggle_windows_shield(enable: bool) -> bool {
    SHIELD_ACTIVE.store(enable, Ordering::SeqCst);
    if !enable {
        restore_windows_dns();
    }
    enable
}

fn main() {
    let default_hook = std::panic::take_hook();
    std::panic::set_hook(Box::new(move |info| {
        restore_windows_dns();
        default_hook(info);
    }));

    tauri::Builder::default()
        .invoke_handler(tauri::generate_handler![get_platform, toggle_windows_shield])
        .on_window_event(|_window, event| {
            if let tauri::WindowEvent::CloseRequested { .. } = event {
                restore_windows_dns();
            }
        })
        .run(tauri::generate_context!())
        .expect("error while running Omit for Windows");

    restore_windows_dns();
}
