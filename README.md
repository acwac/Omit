# Omit

**Featherlight, battery-friendly Ad, Tracker & Anti-DPI Shield for Android, Android TV, and Windows 10/11.**

- **Official Website:** https://acwac.github.io/Omit/
- **Latest Releases:** https://github.com/acwac/Omit/releases/latest
- **Package ID:** `app.omit.shield`

---

## Downloads

| Platform | Download | Notes |
| --- | --- | --- |
| **Android Phone & Android TV** | [`Omit.apk`](https://github.com/acwac/Omit/releases/latest/download/Omit.apk) | Single APK with adaptive 10-foot TV D-Pad UI |
| **Windows 10 / 11** | [`Omit-Windows.exe`](https://github.com/acwac/Omit/releases/latest/download/Omit-Windows.exe) | Lightweight Tauri v2 desktop app with crash-safe DNS restore |

---

## Core Architecture (~0% Extra Battery)

Unlike traditional VPN-based blockers that route `0.0.0.0/0` through userspace (`tun2socks`), **Omit** routes **only DNS** (`10.215.173.2/32` & `fd00:7665:6c6c::2/128`) and a **selective virtual Trap-IP range** (`198.18.0.0/15`). 99.9% of your video, gaming, and download traffic flows directly through the OS hardware kernel.

- **Zero-Setup Defaults:** Works immediately on first turn-on with **Balanced** mode + **Google Encrypted DoH** (`https://8.8.8.8/dns-query`) + pre-compiled binary blocklists (`omit_starter.vbin`).
- **ISP Site Unblocker:** Local TLS `ClientHello` SNI segmentation for targeted ISP-blocked domains + Cloudflare Anycast fallback.
  - *Benefit:* Opens DNS- and DPI-blocked websites at 100% native 5G/Wi-Fi speed (`0 ms` extra ping) while keeping your local IP so UPI, banking apps, Hotstar, and Google searches never break.
  - *Trade-off:* Your real IP remains visible to websites, it cannot spoof your country for Geo-blocks (e.g., US-only Netflix), and raw non-CDN server IP blocks require an external relay VPN.
- **Who's Spying? & Background Vampire Tracker:** Maps blocked tracker domains to `198.18.x.y`, identifies the exact culprit app via `ConnectivityManager.getConnectionOwnerUid()` (API 29+) + `PowerManager.isInteractive`, flags screen-off wakeups, and drops with `TCP RST`.
- **Play-Games-Safe Smart Context Mode:** Permanent Global Never-Block Auth Safe List (`games.googleapis.com`, `play.googleapis.com`, `accounts.google.com`, `oauth2.googleapis.com`, Facebook OAuth, Apple Sign-In) so logins and cloud saves never break.
- **Clean Open in Omit:** Share or open popup-heavy links from WhatsApp/Chrome/Google inside Omit with tracking-tag stripping (`?igsh=`, `?utm_*`, `?si=`) and 1-tap Reader Mode.

---

## Building from Source

```bash
# 1. Compile blocklists into app/src/main/assets/omit_starter.vbin
python3 scripts/compile_blocklists.py

# 2. Build the Web UI (outputs to app/src/main/assets/ui/)
cd ui-web && npm ci && npm run build && cd ..

# 3. Build Android APK
./scripts/build_apk.sh
```

## License

MIT License — see [LICENSE](LICENSE).
