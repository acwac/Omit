# Omit — Architecture & Handoff Context

> Accurate as of Omit 2.5.0 (versionCode 250). For downloads see the
> [website](https://acwac.github.io/Omit/) or [README](README.md).

## Identity

- **Name:** Omit (`app.omit.shield`, desktop identifier `app.omit.shield`)
- **Downloads:** `Omit.apk` (phone + Android TV, one APK), `Omit-Windows.exe` (NSIS installer)
- **Site:** https://acwac.github.io/Omit/ — download cards, `version.json` update feed
- **Wire format:** `VELLUM01` vbin table (kept for on-disk compatibility with the
  Kotlin engine, Rust loader and `scripts/compile_blocklists.py`; it is a format
  id, not a product name)

## Source layout

- `app/src/main/java/app/omit/shield/`
  - `dns/BlockEngine.kt` — sorted 64-bit FNV-1a + fmix64 hash table, 8-bit category
    masks, allow/block suffix sets, DoH canary guard, essential safe list
  - `dns/DohClient.kt` — DoH chain (custom → primary → secondary → cross-provider →
    captive UDP) with a 20 s per-provider failure cooldown
  - `service/OmitVpnService.kt` — split-route TUN, 60 s pause, DNS cache, CNAME
    uncloaking, per-app rules
  - `service/OmitTileService.kt`, `service/BootReceiver.kt` — Quick Settings tile
    and start-on-boot
  - `net/HomeShieldServer.kt` — LAN HTTP proxy + PAC (`/omit.pac`) for TVs,
    consoles and phones on the same Wi-Fi
  - `ui/MainActivity.kt`, `ui/OmitJsBridge.kt` — offline WebView on
    `https://app.omit.local/` (also serves `/app-icon/<pkg>`), typed
    `@JavascriptInterface` exposed as `window.OmitAndroid`
  - `data/OmitPrefs.kt` — lock-free config; one-time migration from the legacy
    `vellum_shield_prefs_v2` preference file
- `ui-web/` — React + TypeScript UI, built into `app/src/main/assets/ui/`
- `desktop-windows/` — Tauri 2 shell: local DNS sinkhole on `127.0.0.1:53`,
  PowerShell DNS redirect with restore, tray, in-app updater
- `scripts/` — `compile_blocklists.py` (vbin compiler), `build_apk.sh` (aapt2 +
  kotlinc + apksigner)
- `.github/workflows/build-apk.yml` — builds APK + EXE, packages `ota-ui.zip`,
  publishes to `downloads/`

## How updates work

1. `version.json` at the site root is the single source of truth.
2. Android reads it through `OmitJsBridge.checkUpdateAsync` and shows an update
   pill; `applyOtaPatch` verifies https + SHA-256 + version code before swapping
   the UI bundle atomically.
3. Windows downloads the installer over https after restoring DNS.

## 1. User's Standing Rules & Non-Negotiable Constraints

1. **App Name & Identity**: **Omit** (`app.omit.shield`).
2. **Ultra-Low Battery & CPU Footprint**:
   - Must feel featherlight and consume almost zero processing power or battery (~1% or less per day on mobile; near-0% CPU/RAM on Windows & TV).
   - **Never** route normal internet/video/download traffic (`0.0.0.0/0`) through a full userspace packet stack (`tun2socks`). Always use **split-route DNS + selective virtual subnet (`198.18.0.0/15`)** so 99.9% of traffic goes straight through the OS hardware kernel.
3. **Zero-to-No Setup Out of the Box + Full Customization Inside**:
   - Must work immediately on first turn-on with **Balanced** intensity + **Google Encrypted DNS (`https://8.8.8.8/dns-query`)** + pre-bundled **209,332** rules (`omit_starter.vbin`), while allowing deep customization inside the app.
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

## 2. What Is Already Built & Working (v2.5.0 / versionCode 250)

### Directory map
- `app/src/main/assets/omit_starter.vbin` — compiled `VELLUM01` table: sorted 64-bit
  hashes (`FNV-1a + MurmurHash3 fmix64`) plus an 8-bit category mask per entry.
  The repo copy is a 146-rule starter; `scripts/compile_blocklists.py` downloads the
  live sources and writes the full ~209k-rule table during CI.
- `app/src/main/assets/ui/` — production Vite build of `ui-web/` (`index.html`,
  hashed JS/CSS, self-hosted `dm-sans.woff2` + `manrope.woff2`, `favicon.svg`).
- `app/src/main/java/app/omit/shield/`:
  - `data/FilterMetadata.kt` — category bits `0..7` (`0x01` Game Ads, `0x02` Web Ads,
    `0x04` Cross-App Trackers, `0x08` Malware/Scams, `0x10` Brand Telemetry,
    `0x20` Betting/Casino, `0x40` Shopping/Affiliate, `0x80` Social Trackers),
    intensity masks (`Light = 0x09`, `Balanced = 0x3F`, `Strict = 0xFF`) and DoH
    upstreams (Google, Cloudflare, Quad9, AdGuard Unfiltered, Custom).
  - `data/OmitPrefs.kt` — lock-free config, RAM-only log ring, per-app modes, custom
    allow/block domains, one-time legacy-pref migration.
  - `dns/BlockEngine.kt` — suffix-walking binary search, DoH canary `NXDOMAIN` guard,
    essential safe list, background online list sync (Android only).
  - `dns/DnsPacket.kt` — RFC 1035 parser, synthetic `0.0.0.0`/`::` and `NXDOMAIN`
    answers, CNAME uncloaking inspector.
  - `dns/IpUdpPacket.kt` — IPv4/IPv6 UDP/53 framing + TCP `RST+ACK` on port 853.
  - `dns/DohClient.kt` — RFC 8484 POST client with provider health cooldown and
    Google ↔ Cloudflare failover.
  - `service/OmitVpnService.kt` — split-route TUN, Offline Game sandbox, per-app
    bypass, 60 s pause with auto-resume, RAM DNS cache, private-DNS conflict detector.
  - `service/OmitTileService.kt`, `service/BootReceiver.kt` — Quick Settings tile and
    start-on-boot.
  - `net/HomeShieldServer.kt` — LAN HTTP proxy + PAC (`/omit.pac`) so TVs, consoles
    and phones on the same Wi-Fi are filtered without installing anything.
  - `ui/MainActivity.kt`, `ui/OmitJsBridge.kt` — offline WebView on
    `https://app.omit.local/`, app launcher icons at `/app-icon/<pkg>`, typed
    `@JavascriptInterface` exposed as `window.OmitAndroid`.
- `ui-web/` — React 19 + TypeScript + Vite UI (`src/App.tsx`, `src/index.css`,
  `src/bridge.ts`); `npm run build` writes straight into `app/src/main/assets/ui/`.
- `desktop-windows/` — Tauri 2 shell (`src-tauri/src/main.rs`): vbin loader, masked
  suffix matcher, `127.0.0.1:53` sinkhole on 4 worker threads, PowerShell DNS
  redirect + crash-safe restore, NSIS bundle, tray, in-app updater.
- `scripts/compile_blocklists.py` & `scripts/build_apk.sh` — blocklist compiler and
  CLI APK builder (`aapt2` + `kotlinc` + `d8` + `zipalign` + `apksigner`).

## 3. Critical Android Crash Traps Already Solved (DO NOT REGRESS!)

1. **System Bar `NullPointerException` on Android 15/16 & OEM ROMs (Realme/ColorOS/OneUI/HyperOS)**:
   - **NEVER** call `window.insetsController` or `applySystemBarAppearance(...)` before `setContentView(...)` in `MainActivity.onCreate()`.
   - Always call `setContentView(...)` **first**, and inside `applySystemBarAppearance` use `window?.decorView?.post { decor.windowInsetsController?.setSystemBarsAppearance(...) }` wrapped in `try/catch`.
2. **Android 8–12 `NoClassDefFoundError: Landroid/window/OnBackInvokedCallback;`**:
   - **NEVER** declare `OnBackInvokedCallback` as a field or direct lambda on `MainActivity`. Keep `android:enableOnBackInvokedCallback="false"` in `AndroidManifest.xml` and handle back navigation via `onBackPressed()`.
3. **WebView Virtual Origin (`https://app.omit.local`)**:
   - Keep `WebSettings.blockNetworkLoads = false` so Chromium WebView loads ES module `<script type="module">` bundles cleanly, while strictly returning `403 Blocked` inside `shouldInterceptRequest` for any host other than `app.omit.local`. Pass `encoding = null` (not `"UTF-8"`) for binary `.woff2` fonts and `.png` app icons.
4. **Low-RAM Build Environment Tip**:
   - In 2 GB RAM Linux containers, avoid heavy Gradle daemons; compile Android APKs directly with `scripts/build_apk.sh` (`aapt2`, `kotlinc`, `d8`, `zipalign`, `apksigner`).
5. **Persistent Signing Key for In-App Updates**:
   - Generate **one permanent release keystore** (store its base64 + passwords in GitHub Actions Secrets when setting up the GitHub repo) so every future `Omit.apk` update installs cleanly *on top of* the user's installed app without requiring uninstall.

---

## 4. Phase-2 Roadmap — Status

Shipped: Android phone + TV UI, Windows Tauri app, GitHub Pages site, `version.json`
update feed, in-app update pill (Android) and installer download (Windows),
ISP unblocker, Spy Detector, Play-Games-safe smart mode, Burner email/number,
whole-home Wi-Fi proxy.

Still open: Clean Open share-sheet viewer, Windows anti-DPI handshake splitter,
signed Windows installer.

### A. Four Smart Engine Features (Selective Trap-IP Architecture — ~0% extra battery)

### A. Four New Smart Engine Features (Selective Trap-IP Architecture — ~0% Extra Battery)
Instead of routing `0.0.0.0/0` (which drains 8–10% battery), route **only DNS + a tiny virtual private range (`198.18.0.0/15`)** so 99.9% of normal videos/downloads still bypass Omit in hardware:

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

4. **"Clean Open in Omit" Anti-Popup & Reader View**:
   - Register Android Share Sheet (`ACTION_SEND` `text/plain`) & Open-With (`ACTION_VIEW`) handler so users can share stubborn popup-ridden or anti-adblock-wall links from WhatsApp/Chrome/Google into Omit for clean, popup-blocked, DPI-unblocked viewing + 1-tap Reader Mode + tracking-parameter stripping (`?igsh=`, `?utm_*`, `?si=`).

---

### B. Android TV / Google TV / Fire TV Landscape UI (shipped: side rail, D-pad
focus rings, 10-foot type scale; leanback launcher entry is in the manifest)
- Detect TV mode (`UiModeManager.CONFIGURATION_TELEVISION` or wide landscape viewport) and adapt the Figma React UI into a comfortable **10-foot TV layout**:
  - Left side-rail navigation + two-column cards instead of narrow portrait mobile column.
  - Crisp D-Pad remote focus rings (`:focus-visible` / arrow-key navigation + Enter/OK activation + Back button handling) so every control works effortlessly with a TV remote (no mouse/touchscreen needed).
  - Add `<category android:name="android.intent.category.LEANBACK_LAUNCHER" />`, `android:banner="@drawable/tv_banner"`, `<uses-feature android:name="android.software.leanback" android:required="false" />`, and `<uses-feature android:name="android.hardware.touchscreen" android:required="false" />` to `AndroidManifest.xml` so the **single `Omit.apk`** installs natively on both Android Phones and Android TVs!

---

### C. Windows 10/11 Laptop App (`Omit-Windows.exe`) — shipped, see `desktop-windows/`
- Build a lightweight native Windows desktop app inside `desktop-windows/` (e.g., **Tauri v2 (Rust)** reusing `ui-web/` directly so it's ~6 MB instead of 150 MB Electron, with near-0% RAM/CPU):
  - System-wide Windows ad, tracker, & Windows-telemetry blocking via local `127.0.0.1:53` DNS sinkhole (`omit_starter.vbin` 209k rules) + Encrypted Google DoH (`https://8.8.8.8/dns-query`).
  - Local Anti-DPI handshake splitter for ISP-blocked websites on Windows at 100% Wi-Fi speed.
  - Quiet Windows System Tray icon + **Crash-Safe Auto-Heal DNS Restore** (automatically restores Windows network adapter DNS settings cleanly on exit, shutdown, or crash so laptop Wi-Fi never gets stranded).
  - Automated `.github/workflows/release.yml` job running on `windows-latest` to compile and attach signed/ready `Omit-Windows-Setup.exe` & portable `.exe` to GitHub Releases!

---

### D. GitHub Pages site, automated builds and update feed — shipped
(`index.html`, `version.json`, `.github/workflows/build-apk.yml`)
1. **GitHub Pages Website (`docs/` or `website/` workflow)**:
   - Publish a fast, responsive, Omit-styled official download site on GitHub Pages matching the Figma aesthetic (Light & Dark mode, clear feature breakdown, honest FAQ, release changelog) with direct 1-click download buttons for:
     - **Download for Android & Android TV (`.apk`)**
     - **Download for Windows 10/11 (`.exe`)**
2. **`version.json` Live Update Endpoint** (served from GitHub Pages / GitHub Releases):
   ```json
   {
     "android": {
       "versionCode": 200,
       "versionName": "2.0.0",
       "apkUrl": "https://<owner>.github.io/<repo>/downloads/Omit.apk",
       "notes": "Added ISP Unblocker, Spy Detector, TV UI & Clean Open"
     },
     "windows": {
       "version": "2.0.0",
       "exeUrl": "https://<owner>.github.io/<repo>/downloads/Omit-Setup.exe",
       "notes": "Initial Windows 10/11 release"
     }
   }
   ```
3. **In-App Update Alert (Android + TV + Windows)**:
   - Lightweight check (once per day when opening the app — 0 background battery drain) against `version.json`.
   - When `remote.versionCode > local.versionCode`, display a clean non-intrusive **"Update available (vX.Y.Z)"** pill/banner on Omit's Home screen with release notes and a 1-tap **Download Update** action!
4. **Full `README.md` & Repository Documentation Overhaul (Explicit User Requirement)**:
   - Completely update and polish `README.md` (plus `LICENSE`, release changelogs, and GitHub repo description/topics) to cover the full cross-platform Omit ecosystem (**Android Phone**, **Android TV / Google TV / Fire TV**, and **Windows 10/11 `.exe`**), direct download links to the GitHub Pages site & GitHub Releases, all new Phase-2 capabilities (ISP Unblocker + honest limits, Spy Detector, Play-Games-Safe Smart Context Mode, Clean Open), battery/privacy architecture, and clean build docs.

---

## Platform parity audit (2.5.0)

Every user-visible control was traced from the shared React shell to a real
implementation on each platform. The rule for this codebase: **if a control is
rendered, it must change real engine state on that platform.** Platform-specific
UI is hidden rather than stubbed.

### Where the platforms differ, and why

| Surface | Android | Windows |
| --- | --- | --- |
| Filtering model | `VpnService` TUN redirect | loopback DNS sinkhole + adapter DNS |
| Per-app modes (Protected / Bypassed / Offline Lock) | yes (per-package VPN) | not applicable — replaced by "Bypassed domains" (custom allow rules) |
| Custom blocklist URLs / Sync lists | yes (compiled into the on-device table) | hidden — the rule table ships fixed with the installer |
| Wi-Fi proxy ("Share Ad-Block to Wi-Fi") | yes | hidden |
| Burner email | `BurnerManager` (mail.tm → Guerrilla Mail) | same providers via Rust commands, state in `burner.json` |
| Upstream DNS | DoH chain in `DohClient` | DoH (RFC 8484) with plain-DNS fallback |
| Update payload | UI-only OTA zip + full APK | NSIS installer from `version.json` |

### Engine switches, end to end

The five rows on Blocking → DNS & engine map to
`FilterMetadata.ENGINE_KEYS` / `WINDOWS_ENGINE_KEYS` and must stay index-aligned:

| Index | Key | Android | Windows |
| --- | --- | --- | --- |
| 0 | `cname_uncloak` | CNAME chain inspection in `OmitVpnService` | checks the CNAME chain before allowing |
| 1 | `anti_storm_ttl` | 300 s vs 15 s blocked TTL | same TTL choice in `build_blocked_response` |
| 2 | `block_doh_canary` | blocks DoH canary names + strips SVCB type 65 | strips type 65 |
| 3 | `captive_portal_fallback` | plain-DNS fallback in `DohClient` | plain-DNS fallback in `resolve_upstream` |
| 4 | `start_on_boot` | `BootReceiver` honours it | writes/removes `HKCU\...\Run\Omit` |

### What is persisted on Windows

`settings.json` in the Tauri app-config dir: shield state, category mask, DNS
index + custom URL, all five engine switches, logging, and the allow/block rule
sets. Burner state lives in `burner.json` beside it. Restarting resumes the
previous shield state when the process is elevated.

### Guard rails for future changes

- The Windows shell is detected through `window.__TAURI_INTERNALS__` (always
  injected); `withGlobalTauri` stays on so `window.__TAURI__` also exists.
  Without this the UI silently falls back to the Android layout.
- `version.json` is fetched through the shell on desktop (bundled copy as the
  offline fallback); a relative `fetch` 404s inside the app.
- Never add a control that only flips local React state — route it through
  `bridge.ts` to a Kotlin method and a Rust command, or hide it per platform.
- Actions logs/artifacts are often unreadable from automation. To inspect a
  Windows build failure, publish the outcome as a **check-run annotation**
  (`::error title=...::message`) — that channel is readable.

## Wi-Fi sharing (2.5.1)

Both platforms can share the filter with the LAN, by different mechanisms
because the operating systems allow different things.

**Android** (`net/HomeShieldServer.kt`) runs an HTTP proxy on 8080 (fallback
8088) plus a PAC file at `/omit.pac`. Root causes of the 2.5.0 breakage, both
fixed:

- `getLanIp()` accepted only `192.168.*` and `10.*`, so a phone on
  `172.16.0.0/12`, `100.64.0.0/10` (CGNAT, Tailscale) or a hotspot interface
  advertised `127.0.0.1` and clients proxied through themselves. It now accepts
  RFC1918, link-local and CGNAT, prefers `wlan`/`eth`, and skips
  `tun`/`ppp`/`rmnet`. Binds the LAN address, falling back to all interfaces.
- The non-CONNECT path forwarded `headerBytes` verbatim: the absolute-URI
  request line (`GET http://host/ HTTP/1.1`) plus `Proxy-Connection` went
  straight to origin servers, which answer 400. Requests are now rewritten to
  origin-form and hop-by-hop headers are stripped. CONNECT is unchanged.

DNS sharing is deliberately **not** offered on Android: an unprivileged app
cannot bind port 53 (`ip_unprivileged_port_start` is 1024, and Android does not
grant `CAP_NET_BIND_SERVICE` to apps), and Private DNS needs DoT, so it could
not work. `Asms.ai`-style public SMS was removed at the user's request.

Android has no way to intercept a *client* device's traffic — it is not the
gateway — so the client must be told where the proxy is exactly once. Everything
else was automated:

- Turning sharing on also turns the shield on (previously a second step).
- The proxy re-binds on every network change, so new Wi-Fi and reboots recover.
- `BootReceiver` restarts the shield when sharing is on even if "start with
  boot" is off, so a reboot no longer silently ends sharing.
- Back leaves the activity alive (TaskStackBuilder-style `moveTaskToBack`)
  while sharing is on, instead of finishing and killing the proxy.
- The service stops the proxy in `onDestroy`, so the UI can never claim
  sharing while nothing listens.

**Windows** (`set_windows_lan_sharing` in `main.rs`) binds a second DNS
listener on the LAN address only — `0.0.0.0:53` would collide with the loopback
sinkhole — and a proxy on 8080 with CONNECT tunnelling, and opens firewall
rules `Omit LAN DNS` (UDP 53) and `Omit LAN Proxy` (TCP 8080), private profile
only. Requires elevation, same as the shield. Disabling the shield disables
sharing. The proxy does not proxy this PC to itself; the sinkhole already
filters this PC, and a self-proxy risks a loop.

Verified offline: Kotlin end-to-end through the real socket server (PAC body,
origin receives `GET /hello?q=1` with no `Proxy-Connection`, 400 no longer
possible), plus browser screenshots at 420px with zero overflow.

## Burner legality (2.5.1)

Answer given plainly, and the UI now states it:

- Disposable **email is legal**. No jurisdiction bans it; the worst case is the
  destination site terminating the account under its terms. Never use for
  banking, government, healthcare or anything needing recovery — this is now
  the in-app note on the email tab.
- The **SMS number feature was removed** on request. Free numbers are shared and
  their inboxes are public — anyone can read the codes — and numbers were
  recycled, so a code could belong to a stranger's account, and logging into an
  account you do not own is unlawful. `asms.ai` scraping, the two bridge
  methods, the two Kotlin fetchers, the two Rust commands, the tab, and every
  reference in README, the website and this file are gone. Burner is email only.
- Nothing in the remaining feature automates a provider or evades a check.

## In-app updates (2.5.2)

**Why every install needed a manual reinstall.** Each CI build was signed with a
freshly generated throwaway key because `ANDROID_KEYSTORE_BASE64` was never set.
Android refuses to replace an installed app whose certificate differs, so the
installer failed with "App not installed" — the update code itself was fine.
Confirmed by hashing the signer of every published APK: six releases, six
different certificates. CI said so in its own annotation.

**The fix requires the release key, which only the repo owner can add.** The
keystore must be the same one every release uses, and the download page must be
re-published with it once:

1. Base64 the keystore and add these repository secrets (Settings → Secrets and
   variables → Actions):

       ANDROID_KEYSTORE_BASE64   base64 -w0 signing/omit-release.jks
       ANDROID_KEYSTORE_PASSWORD store password
       ANDROID_KEY_PASSWORD      key password (usually the same)
       ANDROID_KEY_ALIAS         omit
       RELEASE_CERT_SHA256       26:1D:03:72:01:14:64:8D:74:4A:13:92:FD:07:7D
                                 :74:F0:93:C6:16:5D:14:5B:00:55:EA:49:01:B0:
                                 EE:9F:9F

2. Set `REQUIRE_STABLE_SIGNING=1` in the `Build Omit APK` step so a missing
   secret fails the build instead of silently publishing an unupdatable APK.

Nothing is uninstalled: one final manual APK install is needed because the
installed copy is signed with a per-build key that no longer exists. Every
update after that is in place.

Until the secret exists, the app detects the mismatch before the installer opens
and says so in plain words instead of failing as "App not installed".

## Wi-Fi sharing: what it actually does (answers given, keep copy honest)

- Traffic is **not** tunnelled. The phone is an HTTP proxy: a device sends its
  web requests to the phone, which returns the replies. Nothing of the client's
  is stored on the phone.
- The phone must stay awake, so the Wi-Fi radio stays busy; battery depends on
  how much the other devices browse. Values are never polled in the background,
  because every UI poller is gated on `document.visibilityState`.
- Only **web traffic** is filtered. Other protocols (DNS from the device itself,
  games, most apps) are not covered, and a device that ignores proxy settings is
  not filtered at all.
- That is why the README and site scope "~1% battery" to normal use and name
  sharing as the exception it is.

