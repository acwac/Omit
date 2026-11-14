# Vellum — Complete Handoff Context & Master Roadmap (For Next Arena Agent Mode Session)

> **Instructions for the Next Agent:**
> Read this file completely before modifying code or pushing to GitHub. This folder (`vellum/`) contains the complete, working v1.1.1 Android codebase, compiled 209,332-rule blocklist binary, integrated Figma React/TypeScript UI, original Figma reference files (`design-reference/`), working signed Android binary (`releases/Vellum-v1.1.1.apk`), and all conversation context/decisions agreed with the user.

---

## 1. User's Standing Rules & Non-Negotiable Constraints

1. **App Name & Identity**: **Vellum** (`app.vellum.shield`).
2. **Ultra-Low Battery & CPU Footprint**:
   - Must feel featherlight and consume almost zero processing power or battery (~1% or less per day on mobile; near-0% CPU/RAM on Windows & TV).
   - **Never** route normal internet/video/download traffic (`0.0.0.0/0`) through a full userspace packet stack (`tun2socks`). Always use **split-route DNS + selective virtual subnet (`198.18.0.0/15`)** so 99.9% of traffic goes straight through the OS hardware kernel.
3. **Zero-to-No Setup Out of the Box + Full Customization Inside**:
   - Must work immediately on first turn-on with **Balanced** intensity + **Google Encrypted DNS (`https://8.8.8.8/dns-query`)** + pre-bundled **209,332** rules (`vellum_starter.vbin`), while allowing deep customization inside the app.
4. **Keep Google DNS Connectivity**:
   - Default resolver must stay **Google Encrypted DoH** (`8.8.8.8` / `8.8.4.4` IP-bootstrapped so no ISP DNS lookup is needed) so ISP-blocked websites remain accessible, rather than falling back to ISP DNS.
5. **Preserve the Uploaded Figma Make UI Design**:
   - Original untouched Figma Make files are in `design-reference/` (`App.tsx`, `index.css`, `image-1.png`).
   - Current integrated React + TypeScript UI is in `ui-web/`.
   - **Do NOT alter the core visual aesthetic** (Light mode `#f4f6f3` / `#21634c`, Dark mode `#111512` / `#7fd4ae`, `Manrope` + `DM Sans` fonts, clean card/pill layout). Extend new features seamlessly in the exact same visual language.
6. **Transparent Benefits & Trade-offs (Zero Text Clutter)**:
   - Every blocking intensity, category, engine toggle, and unblocker mode must clearly state both its **Benefit** and **Trade-off / Consequence** inside clean expandable `Detail` disclosures—never dump walls of text onto the main screen.
7. **Deliver Finished Binaries Directly**:
   - The user does not compile code manually—always deliver ready-to-download/install `.apk` and `.exe` builds (via direct build and/or automated GitHub Actions Releases).

---

## 2. What Is Already Built & Working in This Folder (`v1.1.1`)

### Directory Map
- `app/src/main/assets/vellum_starter.vbin` — Pre-compiled `VELLUM01` sorted 64-bit hash (`FNV-1a + MurmurHash3 fmix64`) + 8-bit category bitmask table containing **209,332** suffix-pruned root domains (`1.84 MB`, ~3.2 µs lookup, 0 heap allocations) compiled from 10 live sources (OISD Small, AdGuard SDNS Filter, HaGeZi Light, StevenBlack, AdAway, Peter Lowe, Phishing Army, OEM Telemetry, Gambling, Affiliate, Social).
- `app/src/main/assets/ui/` — Production Vite build of `ui-web/` (`index.html`, CSS, JS, and 100% offline `dm-sans.woff2` + `manrope.woff2`).
- `app/src/main/java/app/vellum/shield/`:
  - `data/FilterMetadata.kt` — Category bits `0..7` (`0x01` Game Ads, `0x02` Web Ads, `0x04` Cross-App Trackers, `0x08` Malware/Scams, `0x10` Brand Telemetry, `0x20` Betting/Casino, `0x40` Shopping/Affiliate, `0x80` Social Trackers), intensity masks (`Light = 0x09`, `Balanced = 0x3F`, `Strict = 0xFF`), and DoH upstream definitions (Google, Cloudflare, Quad9, AdGuard Unfiltered, Custom DoH).
  - `data/VellumPrefs.kt` — Lock-free atomic hot-path config (`AtomicInteger`/`@Volatile`), RAM-only log ring buffer (`ArrayDeque`), per-app modes (`Protected`, `Bypassed`, `Offline Game`), custom allow/block domains, and zero-wakeup listener notifications (`if (listeners.isEmpty()) return`).
  - `dns/BlockEngine.kt` — Suffix-walking binary search domain matcher, browser DoH canary `NXDOMAIN` guard, essential safe-domain whitelist, and background online blocklist sync (`syncOnlineLists`).
  - `dns/DnsPacket.kt` — RFC 1035 wire parser, synthetic `0.0.0.0`/`::` (`TTL=300s` anti-retry-storm) & `NXDOMAIN` generator, and deep CNAME uncloaking inspector.
  - `dns/IpUdpPacket.kt` — IPv4 + IPv6 UDP/53 packet parser/builder + instant TCP `RST+ACK` on port `853` to prevent Android's 3-second opportunistic DoT stall.
  - `dns/DohClient.kt` — `VpnService.protect(socket)` RFC 8484 DoH client with primary/secondary IP bootstrap, Google <-> Cloudflare cross-provider failover, and optional captive-portal helper.
  - `service/VellumVpnService.kt` — Split-route `/32` (`10.215.173.2`) + `/128` (`fd00:7665:6c6c::2`) TUN loop, Offline Game sandbox (`addAllowedApplication`), per-app bypass (`addDisallowedApplication`), 60s live pause countdown with auto-resume, 2,048-entry RAM DNS cache (`180s..3600s`), 0-idle-thread `ThreadPoolExecutor`, and strict Android `LinkProperties.privateDnsServerName` conflict detector.
  - `service/VellumTileService.kt` & `service/BootReceiver.kt` — Android Quick Settings Tile (API 26–34 safe) and boot auto-resume.
  - `ui/MainActivity.kt` & `ui/VellumJsBridge.kt` — Offline `WebView` serving `https://app.vellum.local/` + real Android app launcher icons (`/app-icon/<pkg>`), dynamic Light/Dark system bars, and typed `@JavascriptInterface` (`window.VellumAndroid`).
- `ui-web/` — Full React 18 + TypeScript + Vite Figma UI (`src/App.tsx`, `src/index.css`, `src/bridge.ts`).
- `scripts/compile_blocklists.py` & `scripts/build_apk.sh` — Blocklist compiler and fast standalone Android SDK CLI builder (`aapt2` + `kotlinc` + `d8` + `zipalign` + `apksigner`).
- `releases/Vellum-v1.1.1.apk` — Tested, working Android APK (`v1.1.1`, `versionCode 103`).

---

## 3. Critical Android Crash Traps Already Solved (DO NOT REGRESS!)

1. **System Bar `NullPointerException` on Android 15/16 & OEM ROMs (Realme/ColorOS/OneUI/HyperOS)**:
   - **NEVER** call `window.insetsController` or `applySystemBarAppearance(...)` before `setContentView(...)` in `MainActivity.onCreate()`.
   - Always call `setContentView(...)` **first**, and inside `applySystemBarAppearance` use `window?.decorView?.post { decor.windowInsetsController?.setSystemBarsAppearance(...) }` wrapped in `try/catch`.
2. **Android 8–12 `NoClassDefFoundError: Landroid/window/OnBackInvokedCallback;`**:
   - **NEVER** declare `OnBackInvokedCallback` as a field or direct lambda on `MainActivity`. Keep `android:enableOnBackInvokedCallback="false"` in `AndroidManifest.xml` and handle back navigation via `onBackPressed()`.
3. **WebView Virtual Origin (`https://app.vellum.local`)**:
   - Keep `WebSettings.blockNetworkLoads = false` so Chromium WebView loads ES module `<script type="module">` bundles cleanly, while strictly returning `403 Blocked` inside `shouldInterceptRequest` for any host other than `app.vellum.local`. Pass `encoding = null` (not `"UTF-8"`) for binary `.woff2` fonts and `.png` app icons.
4. **Low-RAM Build Environment Tip**:
   - In 2 GB RAM Linux containers, avoid heavy Gradle daemons; compile Android APKs directly with `scripts/build_apk.sh` (`aapt2`, `kotlinc`, `d8`, `zipalign`, `apksigner`).
5. **Persistent Signing Key for In-App Updates**:
   - Generate **one permanent release keystore** (store its base64 + passwords in GitHub Actions Secrets when setting up the GitHub repo) so every future `Vellum.apk` update installs cleanly *on top of* the user's installed app without requiring uninstall.

---

## 4. Phase-2 Master Roadmap (What to Build in This New Chat)

The user wants to expand Vellum into a complete cross-platform product (**Android Phone + Android TV + Windows 10/11 Laptop**) backed by **GitHub Releases, a GitHub Pages Download Website, and In-App Update Alerts**, plus **4 high-impact core features**:

### A. Four New Smart Engine Features (Selective Trap-IP Architecture — ~0% Extra Battery)
Instead of routing `0.0.0.0/0` (which drains 8–10% battery), route **only DNS + a tiny virtual private range (`198.18.0.0/15`)** so 99.9% of normal videos/downloads still bypass Vellum in hardware:

1. **ISP Site Unblocker (Local Anti-DPI / TLS Handshake Splitter + Clean Cloudflare Anycast IP Fallback)**:
   - Splits `TLS ClientHello` (`SNI` fragmentation / disorder) locally on-device **only** for ISP-blocked domains / Unblock List / Clean Open links, plus maps blocked Cloudflare edge IPs to clean Cloudflare Anycast IPs where applicable.
   - **Honest Benefit / Trade-off disclosure in UI**:
     - *Benefit:* Opens DNS- and DPI-blocked websites at **100% native internet speed** with `0 ms` extra ping while keeping your real local IP (so UPI, banking apps, Hotstar, and Google searches never break).
     - *Trade-off:* Your real IP remains visible to websites, it cannot spoof your country for Geo-blocks (e.g., US-only Netflix), and non-CDN raw server IP blocks still require an external relay VPN.

2. **"Who's Spying?" App Detector & Background Vampire Tracker**:
   - Return a mapped virtual Trap-IP (`198.18.x.y`) for blocked tracker/ad domains instead of `0.0.0.0`.
   - When the culprit app sends its single 60-byte `SYN` packet to `198.18.x.y`, call `ConnectivityManager.getConnectionOwnerUid()` (API 29+) to identify the **exact installed App Name & Icon** (and check `PowerManager.isInteractive` to flag **Screen-Off / Sleeping** background wakeups!), record it, and immediately reply with `TCP RST` (`0.0%` battery).
   - Surface per-app blocked counts in **Apps** & **Activity** + a compact **"Caught in Background"** summary on **Home**.

3. **Play-Games-Safe Smart "Don't Break My Apps" Context Mode**:
   - **CRITICAL REQUIREMENT**: Must **NEVER** break **Google Play Games**, **Sign in with Google**, **Continue with Facebook**, **Apple Sign-In**, cloud saves, achievements, or multiplayer accounts inside 3rd-party games/apps!
   - **Exact Rule**:
     - Put all login, OAuth, Play Games, and Cloud Save hosts (`games.googleapis.com`, `play.googleapis.com`, `accounts.google.com`, `oauth2.googleapis.com`, `googleapis.com` auth/play endpoints, Facebook OAuth login endpoints, Apple ID auth) on a permanent **Global Never-Block Auth Safe List** for *all* apps.
     - Smart Mode acts as **rescue-only** for official 1st-party apps (e.g., allowing Meta CDN/graph inside WhatsApp/Instagram while blocking dedicated ad/telemetry SDK hosts like `an.facebook.com`, `doubleclick.net`, `ads.unity3d.com`, `applovin.com` everywhere else). Never add surprise extra blocks that could break game sign-ins.

4. **"Clean Open in Vellum" Anti-Popup & Reader View**:
   - Register Android Share Sheet (`ACTION_SEND` `text/plain`) & Open-With (`ACTION_VIEW`) handler so users can share stubborn popup-ridden or anti-adblock-wall links from WhatsApp/Chrome/Google into Vellum for clean, popup-blocked, DPI-unblocked viewing + 1-tap Reader Mode + tracking-parameter stripping (`?igsh=`, `?utm_*`, `?si=`).

---

### B. Android TV / Google TV / Fire TV Dedicated Landscape UI
- Detect TV mode (`UiModeManager.CONFIGURATION_TELEVISION` or wide landscape viewport) and adapt the Figma React UI into a comfortable **10-foot TV layout**:
  - Left side-rail navigation + two-column cards instead of narrow portrait mobile column.
  - Crisp D-Pad remote focus rings (`:focus-visible` / arrow-key navigation + Enter/OK activation + Back button handling) so every control works effortlessly with a TV remote (no mouse/touchscreen needed).
  - Add `<category android:name="android.intent.category.LEANBACK_LAUNCHER" />`, `android:banner="@drawable/tv_banner"`, `<uses-feature android:name="android.software.leanback" android:required="false" />`, and `<uses-feature android:name="android.hardware.touchscreen" android:required="false" />` to `AndroidManifest.xml` so the **single `Vellum.apk`** installs natively on both Android Phones and Android TVs!

---

### C. Windows 10/11 Laptop App (`Vellum-Windows.exe`)
- Build a lightweight native Windows desktop app inside `desktop-windows/` (e.g., **Tauri v2 (Rust)** reusing `ui-web/` directly so it's ~6 MB instead of 150 MB Electron, with near-0% RAM/CPU):
  - System-wide Windows ad, tracker, & Windows-telemetry blocking via local `127.0.0.1:53` DNS sinkhole (`vellum_starter.vbin` 209k rules) + Encrypted Google DoH (`https://8.8.8.8/dns-query`).
  - Local Anti-DPI handshake splitter for ISP-blocked websites on Windows at 100% Wi-Fi speed.
  - Quiet Windows System Tray icon + **Crash-Safe Auto-Heal DNS Restore** (automatically restores Windows network adapter DNS settings cleanly on exit, shutdown, or crash so laptop Wi-Fi never gets stranded).
  - Automated `.github/workflows/release.yml` job running on `windows-latest` to compile and attach signed/ready `Vellum-Windows-Setup.exe` & portable `.exe` to GitHub Releases!

---

### D. GitHub Pages Download Website + Automated Releases + In-App Update Alerts
1. **GitHub Pages Website (`docs/` or `website/` workflow)**:
   - Publish a fast, responsive, Vellum-styled official download site on GitHub Pages matching the Figma aesthetic (Light & Dark mode, clear feature breakdown, honest FAQ, release changelog) with direct 1-click download buttons for:
     - **Download for Android & Android TV (`.apk`)**
     - **Download for Windows 10/11 (`.exe`)**
2. **`version.json` Live Update Endpoint** (served from GitHub Pages / GitHub Releases):
   ```json
   {
     "android": {
       "versionCode": 200,
       "versionName": "2.0.0",
       "apkUrl": "https://<owner>.github.io/<repo>/downloads/Vellum.apk",
       "notes": "Added ISP Unblocker, Spy Detector, TV UI & Clean Open"
     },
     "windows": {
       "version": "2.0.0",
       "exeUrl": "https://<owner>.github.io/<repo>/downloads/Vellum-Setup.exe",
       "notes": "Initial Windows 10/11 release"
     }
   }
   ```
3. **In-App Update Alert (Android + TV + Windows)**:
   - Lightweight check (once per day when opening the app — 0 background battery drain) against `version.json`.
   - When `remote.versionCode > local.versionCode`, display a clean non-intrusive **"Update available (vX.Y.Z)"** pill/banner on Vellum's Home screen with release notes and a 1-tap **Download Update** action!
4. **Full `README.md` & Repository Documentation Overhaul (Explicit User Requirement)**:
   - Completely update and polish `README.md` (plus `LICENSE`, release changelogs, and GitHub repo description/topics) to cover the full cross-platform Vellum ecosystem (**Android Phone**, **Android TV / Google TV / Fire TV**, and **Windows 10/11 `.exe`**), direct download links to the GitHub Pages site & GitHub Releases, all new Phase-2 capabilities (ISP Unblocker + honest limits, Spy Detector, Play-Games-Safe Smart Context Mode, Clean Open), battery/privacy architecture, and clean build docs.
