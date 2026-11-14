# Vellum — Ultra-Light Android DNS Ad & Tracker Shield

Vellum is a zero-setup, ultra-low-battery system-wide ad, tracker, scam, and telemetry blocker for Android (`minSdk 26`, `targetSdk 34`, no root required).

Unlike traditional VPN ad blockers that route gigabytes of video/app traffic through heavy userspace TCP/IP stacks (`tun2socks`), Vellum uses a **split-route `/32` + `/128` local DNS sinkhole** combined with **IP-bootstrapped DNS-over-HTTPS (Google DoH by default)**. Video streams, downloads, and games flow directly through the Android kernel at native wire speed with **~0% idle CPU drain**.

---

## Key Highlights

- **Zero Setup Out of the Box**: Pre-loaded with **209,332** suffix-pruned domains (`vellum_starter.vbin`) compiled from 10 curated upstream lists (OISD Small, AdGuard SDNS, HaGeZi Light, StevenBlack, AdAway, Peter Lowe, Phishing Army, Xiaomi/Samsung/Realme/OnePlus OEM telemetry, gambling, affiliate, and social tracker lists). Launches ready in **Balanced** mode with **Google Encrypted DoH** (`https://8.8.8.8/dns-query`) so ISP-blocked websites keep working seamlessly.
- **Near-Zero Battery & RAM Footprint**:
  - Split-route TUN captures *only* DNS packets (`10.215.173.2/32` & `fd00:7665:6c6c::2/128`), never app payload traffic.
  - Allocation-free 64-bit `FNV-1a + fmix64` binary search table (~1.84 MB total, ~3 microseconds per lookup).
  - **300s Anti-Retry-Storm Synthetic TTL** puts blocked ad SDKs to sleep instead of letting them hammer the radio every second.
  - Immediate TCP `RST+ACK` on port `853` eliminates Android's 3-second opportunistic DoT probe stall.
- **Transparent Customization (Benefits & Trade-offs)**:
  - **Intensity Presets**: `Light`, `Balanced`, `Strict`, and automatic `Custom` mode.
  - **8 Granular Categories**: Game & In-App Ads, Web Banners & Popups, Cross-App Trackers, Malware & Scam Shield, Phone Brand Telemetry, Betting & Casino Popups, Shopping & Affiliate Links, and Social Media Trackers — each with expandable **Benefit** and **Trade-off** guidance.
  - **Engine Controls**: Deep CNAME Uncloaking, Anti-Retry-Storm TTL, Browser DoH Guard (`NXDOMAIN` on canary hosts + Type 65 HTTPS record stripping), Hotel/Cafe Captive Portal Helper, and Start on Boot.
  - **Encrypted DNS Resolver**: Google DNS (default), Cloudflare, Quad9, AdGuard Unfiltered, or Custom `https://` DoH URL.
- **Per-App Controls & Offline Game Mode**:
  - Set any installed app to **Protected**, **Bypassed** (invisible to banking/UPI/Android Auto apps via `addDisallowedApplication`), or **Offline Game** (cuts internet only for selected offline games while keeping WhatsApp/calls/UPI online).
- **Light & Dark Mode + 100% Offline UI**:
  - Built with React + TypeScript (`ui-web/`) and served locally inside a network-isolated Android `WebView` (`blockNetworkLoads = true`) with bundled `DM Sans` & `Manrope` `.woff2` variable fonts.

---

## Repository Structure

```text
vellum/
├── .github/workflows/build-apk.yml   # Automated GitHub Actions CI APK build
├── app/
│   ├── build.gradle.kts              # Android Gradle module config
│   └── src/main/
│       ├── AndroidManifest.xml       # VpnService, Quick Settings Tile, BootReceiver
│       ├── assets/
│       │   ├── vellum_starter.vbin   # Compiled 209,332-rule VELLUM01 binary table
│       │   └── ui/                   # Production offline UI bundle + variable fonts
│       ├── java/app/vellum/shield/
│       │   ├── data/
│       │   │   ├── FilterMetadata.kt # Bitmasks, presets, and DoH upstream specs
│       │   │   └── VellumPrefs.kt    # Atomic zero-I/O packet config & RAM log ring
│       │   ├── dns/
│       │   │   ├── BlockEngine.kt    # 64-bit hash suffix-walk & online list sync
│       │   │   ├── DnsPacket.kt      # RFC 1035 parser, CNAME inspector & synthesizer
│       │   │   ├── DohClient.kt      # VPN-protected RFC 8484 DoH resolver + failover
│       │   │   └── IpUdpPacket.kt    # IPv4/IPv6 UDP framing + TCP 853 RST fast-reject
│       │   ├── service/
│       │   │   ├── BootReceiver.kt   # Auto-resume on device reboot
│       │   │   ├── VellumTileService.kt # Android Quick Settings toggle tile
│       │   │   └── VellumVpnService.kt  # Split-route & Offline Game sandbox TUN loop
│       │   └── ui/
│       │       ├── MainActivity.kt   # Offline WebView host & app icon PNG server
│       │       └── VellumJsBridge.kt # Native <-> TypeScript state & action bridge
│       └── res/                      # Adaptive launcher icons, QS vector, Light/Dark themes
├── scripts/
│   ├── build_apk.sh                  # Fast standalone Android SDK CLI build script
│   └── compile_blocklists.py         # Fetches & compiles upstream lists into .vbin
├── ui-web/                           # React + TypeScript + Vite Figma UI source
│   ├── public/fonts/                 # Bundled offline DM Sans & Manrope woff2 fonts
│   └── src/
│       ├── App.tsx                   # Home, Blocking, Apps, Activity & modal sheets
│       ├── bridge.ts                 # Typed VellumAndroid bridge & browser preview state
│       └── index.css                 # Light & Dark mode design tokens & layout styles
├── build.gradle.kts
├── gradle.properties
├── settings.gradle.kts
└── LICENSE
```

---

## Building from Source

### 1. Update & Recompile UI (Optional — pre-built bundle is already in `app/src/main/assets/ui/`)

```bash
cd ui-web
npm install
npm run build
```

### 2. Refresh Starter Blocklists (Optional — `vellum_starter.vbin` is already compiled)

```bash
python3 scripts/compile_blocklists.py
```

### 3. Build Signed APK

Using the standalone Android SDK CLI script (`aapt2` + `kotlinc` + `d8` + `zipalign` + `apksigner`):

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_SDK_ROOT=/path/to/android-sdk
export KOTLIN_HOME=/path/to/kotlin-1.9.24
./scripts/build_apk.sh ./Vellum.apk
```

Or open the root `vellum/` directory directly in **Android Studio** (Ladybug / Koala or newer) and run `:app`.

---

## License

Released under the [MIT License](./LICENSE).
