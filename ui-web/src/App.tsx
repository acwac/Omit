import { Component, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import {
  androidBridge,
  applyOtaUpdate,
  checkForUpdate,
  copyToClipboard,
  dismissUpdate,
  downloadAndInstallUpdate,
  formatCompactCount,
  isAndroidRuntime,
  isNativeRuntime,
  isUpdateDismissed,
  isWindowsRuntime,
  nativeGetBurnerEmail,
  nativeGetBurnerInbox,
  nativeGetBurnerSmsMessages,
  nativeGetBurnerSmsNumbers,
  nativeGetHomeShieldInfo,
  nativeGetTamperWarning,
  nativeSetHomeShield,
  nativeVersionCode,
  openExternalUrl,
  readInitialApps,
  readInitialState,
  readSpyStats,
  setDarkMode,
  clearWindowsLogs,
  getWindowsLogs,
  getWindowsRules,
  setWindowsCategoryMask,
  setWindowsDns,
  setWindowsEngine,
  setWindowsRule,
  clearWindowsRule,
  setWindowsLogging,
  windowsDnsLock,
  windowsInstalledCode,
  windowsState,
  setWindowsShield,
  windowsShieldStatus,
  windowsStats,
  type AppMode,
  type BurnerEmailMsg,
  type BurnerSmsMsg,
  type BurnerSmsNum,
  type DnsLogItem,
  type HomeShieldInfo,
  type InstalledAppItem,
  type OmitStateSnapshot,
  type RecentBlockedItem,
  type ShieldState,
  type SpyStatItem,
  type UpdateInfo,
} from "./bridge";

type Tab = "home" | "burner" | "blocking" | "apps" | "activity";

const iconPaths: Record<string, ReactNode> = {
  shield: (
    <g transform="scale(0.2222)">
      <path fill="currentColor" stroke="none" d="M 64.14,17.88 A 37.43,37.43 0 1,0 91.16,60.38 A 25.80,25.80 0 0,1 64.14,17.88 Z" />
      <circle fill="currentColor" stroke="none" cx="75.17" cy="34.93" r="5.63" />
    </g>
  ),
  sliders: <path d="M4 7h9m4 0h3M4 17h3m4 0h9M15 5v4M9 15v4" />,
  mail: <path d="M4 6h16v12H4V6Zm0 0 8 6 8-6" />,
  apps: <path d="M4 4h6v6H4V4Zm10 0h6v6h-6V4ZM4 14h6v6H4v-6Zm10 0h6v6h-6v-6Z" />,
  pulse: <path d="M3 12h4l2.2-6 4.1 12 2.3-6H21" />,
  moon: <path d="M20 15.6A8.4 8.4 0 0 1 8.4 4 8.5 8.5 0 1 0 20 15.6Z" />,
  sun: (
    <>
      <circle cx="12" cy="12" r="3.5" />
      <path d="M12 2v2m0 16v2M4.9 4.9l1.4 1.4m11.4 11.4 1.4 1.4M2 12h2m16 0h2M4.9 19.1l1.4-1.4m11.4-11.4 1.4-1.4" />
    </>
  ),
  pause: <path d="M8 5v14m8-14v14" />,
  play: <path d="m8 5 11 7-11 7V5Z" />,
  game: <path d="M7 11V7a5 5 0 0 1 10 0v4M5 11h14v9H5v-9Z" />,
  warning: (
    <>
      <path d="m12 3 9 17H3L12 3Z" />
      <path d="M12 9v4m0 3h.01" />
    </>
  ),
  arrow: <path d="m9 6 6 6-6 6" />,
  down: <path d="m6 9 6 6 6-6" />,
  up: <path d="m6 15 6-6 6 6" />,
  check: <path d="m5 12 4 4L19 6" />,
  search: (
    <>
      <circle cx="10.5" cy="10.5" r="6.5" />
      <path d="m16 16 4.5 4.5" />
    </>
  ),
  lock: (
    <>
      <rect x="5" y="10" width="14" height="11" rx="2" />
      <path d="M8 10V7a4 4 0 0 1 8 0v3" />
    </>
  ),
  globe: (
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="M3 12h18M12 3a14 14 0 0 1 0 18m0-18a14 14 0 0 0 0 18" />
    </>
  ),
  plus: <path d="M12 5v14M5 12h14" />,
  close: <path d="m6 6 12 12M18 6 6 18" />,
  trash: <path d="M4 7h16M9 7V4h6v3m3 0-1 14H7L6 7m4 4v6m4-6v6" />,
  wifi: <path d="M3.5 9a13 13 0 0 1 17 0M6.5 12.5a8.5 8.5 0 0 1 11 0M9.5 16a4 4 0 0 1 5 0M12 19h.01" />,
  download: (
    <>
      <path d="M12 4v10m0 0 4-4m-4 4-4-4" />
      <path d="M5 18h14" />
    </>
  ),
};

function Icon({ name, size = 20 }: { name: string; size?: number }) {
  return (
    <svg
      viewBox="0 0 24 24"
      width={size}
      height={size}
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      {iconPaths[name]}
    </svg>
  );
}

function Toggle({ on, onChange, label }: { on: boolean; onChange: () => void; label: string }) {
  return (
    <button
      type="button"
      className={`toggle ${on ? "on" : ""}`}
      onClick={(event) => {
        event.stopPropagation();
        onChange();
      }}
      role="switch"
      aria-checked={on}
      aria-label={label}
    >
      <span />
    </button>
  );
}

function Header({ title, subtitle }: { title: string; subtitle?: string }) {
  return (
    <div className="page-head">
      <div>
        <h1>{title}</h1>
        {subtitle && <p>{subtitle}</p>}
      </div>
    </div>
  );
}

function Detail({ benefit, consequence }: { benefit: string; consequence: string }) {
  return (
    <div className="details">
      <div>
        <span className="good">
          <Icon name="check" size={12} />
        </span>
        <p>
          <b>Benefit</b>
          {benefit}
        </p>
      </div>
      <div>
        <span className="trade">!</span>
        <p>
          <b>Trade-off</b>
          {consequence}
        </p>
      </div>
    </div>
  );
}

function ExpandRow({
  title,
  note,
  on,
  onChange,
  benefit,
  consequence,
}: {
  title: string;
  note?: string;
  on: boolean;
  onChange: () => void;
  benefit: string;
  consequence: string;
}) {
  const [open, setOpen] = useState(false);
  return (
    <div className={`expand-row ${open ? "open" : ""}`}>
      <div className="row-main">
        <button className="row-copy" onClick={() => setOpen(!open)} aria-expanded={open}>
          <span>
            <b>{title}</b>
            {note && <small>{note}</small>}
          </span>
          <Icon name={open ? "up" : "down"} size={17} />
        </button>
        <Toggle on={on} onChange={onChange} label={title} />
      </div>
      {open && <Detail benefit={benefit} consequence={consequence} />}
    </div>
  );
}

function Modal({ children, onClose }: { children: ReactNode; onClose: () => void }) {
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  return (
    <div className="modal-wrap" onMouseDown={onClose}>
      <div
        className="modal"
        role="dialog"
        aria-modal="true"
        onMouseDown={(event) => event.stopPropagation()}
      >
        <button className="icon-btn modal-close" onClick={onClose} aria-label="Close">
          <Icon name="close" />
        </button>
        {children}
      </div>
    </div>
  );
}

function AppBadge({ app }: { app: InstalledAppItem }) {
  const [imgFailed, setImgFailed] = useState(false);
  const showNativeIcon = isAndroidRuntime() && !imgFailed;
  return (
    <span className="app-icon" style={{ background: showNativeIcon ? "transparent" : app.color }}>
      {showNativeIcon ? (
        <img
          src={`https://app.omit.local/app-icon/${encodeURIComponent(app.pkg)}`}
          alt=""
          loading="lazy"
          onError={() => setImgFailed(true)}
        />
      ) : (
        app.initials
      )}
    </span>
  );
}

const intensityData = [
  ["Light", "Game ads & scams only", "Zero broken sites.", "Web trackers still pass."],
  ["Balanced", "Everyday protection", "Blocks 95% of annoyances.", "Reward ads need Pause; shopping links work."],
  ["Strict", "Block everything", "Maximum privacy.", "May break shopping, cashback, and social login links."],
  ["Custom", "Choose categories", "Protection shaped by you.", "Results depend on the switches below."],
] as const;

const categoryData = [
  ["Game & In-App Ads", "Removes banners, popups, and video ad preloads.", "Rewarded ads will need Pause 60s."],
  ["Web Banners & Popups", "Stops banners, fake buttons, and redirects.", "Some ad-wall sites may show blank spaces."],
  ["Cross-App Trackers", "Stops analytics SDKs profiling app use.", "Some tracked email and referral links may fail."],
  ["Malware & Scam Shield", "Blocks known phishing, scam, and malware hosts.", "Rare new file mirrors may be blocked."],
  ["Phone Brand Telemetry", "Silences phone maker analytics and ad-ID pings.", "Built-in diagnostics may not sync."],
  ["Betting & Casino Popups", "Stops aggressive betting and casino networks.", "Betting and fantasy sports sites are blocked."],
  ["Shopping & Affiliate Links", "Stops affiliate purchase tracking.", "Sponsored results and cashback links can break."],
  ["Social Media Trackers", "Stops social pixels on websites you visit.", "Social login and embedded posts may fail."],
] as const;

/** Windows has no phone-brand telemetry; relabel the same bit for desktop. */
const WINDOWS_CATEGORY_LABELS: Record<string, [string, string, string]> = {
  "Phone Brand Telemetry": [
    "Windows Telemetry",
    "Silences Microsoft diagnostic and ad-ID pings.",
    "Windows tips and diagnostics may not sync.",
  ],
  "Game & In-App Ads": [
    "Game & Store Ads",
    "Stops game storefront and overlay ad networks.",
    "Rewarded ads need Pause 60s.",
  ],
};

const engineData = [
  ["CNAME Uncloaking", "Finds trackers hidden behind first-party domains.", "Rare CDN aliases may need an allow rule."],
  ["Anti-Retry-Storm Cache", "Stops blocked apps repeatedly waking your radio.", "Blocks remain cached for five minutes."],
  ["Prevent Browser DNS Bypass", "Keeps browser requests inside Omit.", "Secure DNS may need disabling in some browsers."],
  ["Captive Portal Helper", "Helps hotel and cafe Wi-Fi login pages open.", "The portal lookup may briefly be unencrypted."],
  ["Start on Boot", "Restores protection automatically after restart.", "Omit starts without manual confirmation."],
] as const;

/** On Windows the phone-specific engine rows are replaced, not just hidden. */
const windowsEngineData = [
  ["Tracker CNAME Guard", "Inspects the CNAME chain so cloaked trackers are still caught.", "Rare CDN aliases may need an allow rule."],
  ["TTL Retry Guard", "Caches blocked answers so apps stop re-querying.", "Blocks stay cached for five minutes."],
  ["Encrypted DNS Guard", "Stops browsers negotiating a resolver that bypasses Omit.", "Some browsers may fall back to system DNS."],
  ["Captive Portal Helper", "Falls back to plain DNS so hotel and airport logins still load.", "Each fallback lookup is unencrypted."],
  ["Start with Windows", "Restores protection automatically after restart.", "Omit starts without manual confirmation."],
] as const;

const dnsData = [
  ["Google DNS", "Reliable default", "Unlocks ISP-blocked sites with reliable encrypted DNS coverage.", "Uses Google Public DNS policy."],
  ["Cloudflare", "Fastest nearby", "Low latency and privacy-oriented policy.", "May route some CDN content differently."],
  ["Quad9", "Malware-aware", "Adds an independent malware protection layer.", "Can be slightly slower in some locations."],
  ["AdGuard Unfiltered", "Independent", "Leaves all filtering decisions to Omit.", "May have higher latency in some regions."],
  ["Custom DoH URL", "Advanced", "Use your own trusted encrypted resolver.", "An invalid server can stop sites loading."],
] as const;

function computeIntensityName(cats: boolean[]): OmitStateSnapshot["intensity"] {
  const light = [true, false, false, true, false, false, false, false];
  const balanced = [true, true, true, true, true, true, false, false];
  const strict = [true, true, true, true, true, true, true, true];
  if (cats.length === 8 && cats.every((v, i) => v === light[i])) return "Light";
  if (cats.length === 8 && cats.every((v, i) => v === balanced[i])) return "Balanced";
  if (cats.length === 8 && cats.every((v, i) => v === strict[i])) return "Strict";
  return "Custom";
}

function normalizeDomain(raw: string): string {
  let s = raw.trim().toLowerCase();
  if (s.startsWith("https://")) s = s.slice(8);
  if (s.startsWith("http://")) s = s.slice(7);
  const slash = s.indexOf("/");
  if (slash >= 0) s = s.slice(0, slash);
  return s.replace(/^\.+|\.+$/g, "").trim();
}

export default function App() {
  const [tab, setTab] = useState<Tab>("home");
  const [state, setState] = useState<OmitStateSnapshot>(() => readInitialState());
  const [apps, setApps] = useState<InstalledAppItem[]>(() => readInitialApps());
  const [spyStats, setSpyStats] = useState<SpyStatItem[]>(() => readSpyStats());

  const [dnsOpen, setDnsOpen] = useState(false);
  const [customDnsOpen, setCustomDnsOpen] = useState(false);
  const [customDnsDraft, setCustomDnsDraft] = useState(state.customDohUrl);

  const [rulesOpen, setRulesOpen] = useState(false);
  const [domainInput, setDomainInput] = useState("");

  const [urlsOpen, setUrlsOpen] = useState(false);
  const [urlInput, setUrlInput] = useState("");

  const [gameDetails, setGameDetails] = useState(false);
  const [spyOpen, setSpyOpen] = useState(false);
  const [selectedAppPkg, setSelectedAppPkg] = useState<string | null>(null);
  const [appSearch, setAppSearch] = useState("");
  const [appFilter, setAppFilter] = useState("User Apps");

  const [frozen, setFrozen] = useState(false);
  const frozenRef = useRef(false);
  frozenRef.current = frozen;
  const [frozenLogs, setFrozenLogs] = useState<DnsLogItem[]>([]);
  const [logSearch, setLogSearch] = useState("");
  const [logFilter, setLogFilter] = useState("All");
  const [selectedLog, setSelectedLog] = useState<DnsLogItem | null>(null);
  const [toast, setToast] = useState("");

  const [updateInfo, setUpdateInfo] = useState<UpdateInfo | null>(null);
  const [updateOpen, setUpdateOpen] = useState(false);

  const categoriesRef = useRef<HTMLDivElement | null>(null);

  const windows = isWindowsRuntime();
  const native = isNativeRuntime();

  // Apply the stored theme immediately (useLayoutEffect order: first render commit).
  if (typeof document !== "undefined") {
    document.documentElement.dataset.theme = state.dark ? "dark" : "light";
  }

  // Apply the theme to <html> before the first paint, then keep it in sync.
  useEffect(() => {
    document.documentElement.dataset.theme = state.dark ? "dark" : "light";
  }, [state.dark]);

  // Register live callbacks from the native engine (new names, legacy aliases kept)
  useEffect(() => {
    const onState = (nextState: OmitStateSnapshot) => setState(nextState);
    const onApps = (nextApps: InstalledAppItem[]) => setApps(nextApps);
    const onToast = (msg: string) => setToast(msg);
    window.__omitOnNativeState = onState;
    window.__omitOnNativeApps = onApps;
    window.__omitOnToast = onToast;
    return () => {
      delete window.__omitOnNativeState;
      delete window.__omitOnNativeApps;
      delete window.__omitOnToast;
    };
  }, []);

  // Back button / Escape navigation for phones, TVs and desktop windows
  useEffect(() => {
    const goBack = () => {
      if (selectedLog) {
        setSelectedLog(null);
        return true;
      }
      if (updateOpen) {
        setUpdateOpen(false);
        return true;
      }
      if (selectedAppPkg !== null) {
        setSelectedAppPkg(null);
        return true;
      }
      if (customDnsOpen) {
        setCustomDnsOpen(false);
        return true;
      }
      if (dnsOpen) {
        setDnsOpen(false);
        return true;
      }
      if (rulesOpen) {
        setRulesOpen(false);
        return true;
      }
      if (urlsOpen) {
        setUrlsOpen(false);
        return true;
      }
      if (spyOpen) {
        setSpyOpen(false);
        return true;
      }
      if (tab !== "home") {
        setTab("home");
        return true;
      }
      return false;
    };
    window.__omitHandleBack = goBack;
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape" && goBack()) {
        event.preventDefault();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => {
      delete window.__omitHandleBack;
      window.removeEventListener("keydown", onKey);
    };
  }, [selectedLog, updateOpen, selectedAppPkg, customDnsOpen, dnsOpen, rulesOpen, urlsOpen, spyOpen, tab]);

  // Standalone countdown fallback (native shells drive their own countdown)
  useEffect(() => {
    if (native || state.shield !== "paused") return;
    const timer = window.setInterval(() => {
      setState((cur) => {
        if (cur.countdown <= 1) {
          return { ...cur, shield: "protected", countdown: 60 };
        }
        return { ...cur, countdown: cur.countdown - 1 };
      });
    }, 1000);
    return () => clearInterval(timer);
  }, [native, state.shield]);

  useEffect(() => {
    if (!toast) return;
    const timer = window.setTimeout(() => setToast(""), 2300);
    return () => clearTimeout(timer);
  }, [toast]);

  // Load installed apps whenever the user switches to the Apps tab on Android
  useEffect(() => {
    if (tab !== "apps") return;
    const bridge = androidBridge();
    if (!bridge) return;
    try {
      const fresh = JSON.parse(bridge.getInstalledAppsJson()) as InstalledAppItem[];
      if (fresh.length > 0) setApps(fresh);
    } catch {
      /* ignore */
    }
  }, [tab]);

  // Refresh spy-detector stats when the Apps / Home tabs come into view
  useEffect(() => {
    if (!isAndroidRuntime()) return;
    if (tab !== "apps" && tab !== "activity") return;
    setSpyStats(readSpyStats());
  }, [tab]);

  // Once-per-open update check (Android + browser). Zero background wakeups.
  useEffect(() => {
    let cancelled = false;
    const check = async () => {
      // On desktop the shell reports its own version through the manifest call.
      const localCode = isWindowsRuntime()
        ? await windowsInstalledCode()
        : nativeVersionCode();
      const result = await checkForUpdate(localCode);
      if (cancelled || !result.available || !result.info) return;
      if (isUpdateDismissed(result.info.versionCode)) return;
      setUpdateInfo(result.info);
    };
    const timer = window.setTimeout(() => void check(), 1200);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, []);

  // Windows shell: pull the persisted engine state once so every screen starts
  // from real values instead of the generic fallback.
  useEffect(() => {
    if (!windows) return;
    let mounted = true;
    void windowsState().then((snapshot) => {
      if (!mounted || !snapshot) return;
      setState((cur) => ({
        ...cur,
        ...snapshot,
        // Theme is owned by the renderer (localStorage), not the Rust side.
        dark: cur.dark,
        // Logs and recent blocks are streamed by their own pollers, which run
        // more often; never overwrite their newer snapshot with this one.
        logs: cur.logs,
        recentBlocked: cur.recentBlocked,
      }));
    });
    return () => {
      mounted = false;
    };
  }, [windows]);

  // Windows shell: poll live engine stats so Home tiles stay truthful
  const [windowsReady, setWindowsReady] = useState(!windows);
  useEffect(() => {
    if (!windows) return;
    let mounted = true;
    const tick = async () => {
      const stats = await windowsStats();
      if (!mounted || !stats) return;
      // Mirror the engine's real category mask so both stay in lockstep
      // (e.g. after a restart, the UI shows exactly what the DNS filter uses).
      setState((cur) => {
        const categories = cur.categories.map((_, i) => (stats.mask & (1 << i)) !== 0);
        const changed = categories.some((on, i) => on !== cur.categories[i]);
        return {
          ...cur,
          shield: stats.active ? "protected" : "off",
          blockedCount: stats.blocked,
          totalQueries: stats.total,
          activeRules: stats.rules,
          intensity: changed ? computeIntensityName(categories) : cur.intensity,
          categories: changed ? categories : cur.categories,
        };
      });
      setWindowsReady(true);
    };
    void tick();
    const timer = window.setInterval(() => void tick(), 4000);
    return () => {
      mounted = false;
      window.clearInterval(timer);
    };
  }, [windows]);

  // Windows: mirror the engine's "Recently blocked" list on the Home screen.
  useEffect(() => {
    if (!windows) return;
    let mounted = true;
    const pull = async () => {
      const invoke = window.__TAURI__?.core?.invoke;
      if (!invoke) return;
      try {
        const rows = await invoke<RecentBlockedItem[]>("get_windows_recent_blocked");
        if (mounted && Array.isArray(rows)) {
          setState((cur) => ({ ...cur, recentBlocked: rows }));
        }
      } catch {
        /* engine not ready yet */
      }
    };
    void pull();
    const timer = window.setInterval(() => void pull(), 5000);
    return () => {
      mounted = false;
      window.clearInterval(timer);
    };
  }, [windows]);

  // Windows engine publishes its own recent DNS decisions for the Activity tab.
  useEffect(() => {
    if (!windows) return;
    let mounted = true;
    const pull = async () => {
      const rows = await getWindowsLogs();
      if (!mounted || !rows) return;
      // Frozen logs stay put until the user unfreezes.
      setState((cur) => (frozenRef.current ? cur : { ...cur, logs: rows }));
    };
    void pull();
    const timer = window.setInterval(() => void pull(), 3000);
    return () => {
      mounted = false;
      window.clearInterval(timer);
    };
  }, [windows]);

  // Restore custom allow/block rules from the engine on launch (Windows).
  useEffect(() => {
    if (!windows) return;
    let mounted = true;
    void getWindowsRules().then((rules) => {
      if (!mounted || !rules) return;
      setState((cur) => ({ ...cur, allowDomains: rules.allow, blockDomains: rules.block }));
    });
    return () => {
      mounted = false;
    };
  }, [windows]);

  const toggleDark = () => {
    const next = !state.dark;
    setState((cur) => ({ ...cur, dark: next }));
    setDarkMode(next);
  };

  const handleShieldAction = (target: ShieldState) => {
    if (windows) {
      const enable = target !== "off";
      void setWindowsShield(enable).then(async (ok) => {
        const stats = await windowsStats();
        if (stats) setState((cur) => ({ ...cur, shield: stats.active ? "protected" : "off" }));
        if (enable && ok) {
          setToast("Shield on");
        } else if (enable && !ok) {
          const status = await windowsShieldStatus();
          setToast(
            status && !status.admin
              ? "Run Omit as administrator to enable"
              : "Windows shield could not start",
          );
        } else {
          setToast("Shield off");
        }
      });
      return;
    }
    const bridge = androidBridge();
    if (bridge) {
      if (target === "paused") {
        bridge.pause60s();
      } else if (target === "protected" && state.shield === "paused") {
        bridge.resumeShield();
      } else if (target === "protected" && state.shield === "game") {
        bridge.setOfflineGameMode(false);
      } else {
        bridge.toggleShield();
      }
      return;
    }
    setState((cur) => ({
      ...cur,
      shield: target,
      gameMode: target === "game" ? true : target === "protected" ? false : cur.gameMode,
      countdown: target === "paused" ? 60 : cur.countdown,
    }));
  };

  const chooseIntensity = (name: string, index: number) => {
    if (index === 3) {
      categoriesRef.current?.scrollIntoView({ behavior: "smooth", block: "start" });
      return;
    }
    androidBridge()?.selectIntensity(index);
    const nextCats =
      index === 0
        ? [true, false, false, true, false, false, false, false]
        : index === 1
          ? [true, true, true, true, true, true, false, false]
          : Array<boolean>(8).fill(true);
    if (windows) {
      const mask = nextCats.reduce((acc, on, i) => (on ? acc | (1 << i) : acc), 0);
      void setWindowsCategoryMask(mask);
    }
    setState((cur) => ({
      ...cur,
      intensity: name as OmitStateSnapshot["intensity"],
      categories: nextCats,
    }));
  };

  const handleToggleCategory = (index: number) => {
    androidBridge()?.toggleCategory(index);
    setState((cur) => {
      const nextCats = cur.categories.map((v, i) => (i === index ? !v : v));
      return { ...cur, categories: nextCats, intensity: computeIntensityName(nextCats) };
    });
    if (windows) {
      void setWindowsCategoryMask(undefined, index).then((mask) => {
        if (mask === null) {
          // The engine rejected it: put the switch back where it was.
          setState((cur) => {
            const reverted = cur.categories.map((v, i) => (i === index ? !v : v));
            return { ...cur, categories: reverted, intensity: computeIntensityName(reverted) };
          });
          setToast("That category could not be changed");
        }
      });
    }
  };

  /** Engine rows are index-aligned with Windows' five switches. */
  const WINDOWS_ENGINE_KEYS = [
    "cname_uncloak",
    "anti_storm_ttl",
    "block_doh_canary",
    "captive_portal_fallback",
    "start_on_boot",
  ];

  const handleToggleEngine = (index: number) => {
    androidBridge()?.toggleEngine(index);
    const nextEngine = state.engine.map((v, i) => (i === index ? !v : v));
    if (windows) {
      const key = WINDOWS_ENGINE_KEYS[index];
      if (key) {
        void setWindowsEngine(key, nextEngine[index]).then((ok) => {
          if (!ok) setToast("That switch could not be changed");
        });
      }
    }
    setState((cur) => ({ ...cur, engine: nextEngine }));
  };

  const handleSelectDns = (index: number) => {
    if (index === 4) {
      setCustomDnsDraft(state.customDohUrl || "https://");
      setCustomDnsOpen(true);
      return;
    }
    androidBridge()?.setUpstreamDns(index, "");
    if (windows) void setWindowsDns(index, "");
    setState((cur) => ({ ...cur, dns: index }));
    setDnsOpen(false);
    setToast(`${dnsData[index][0]} active`);
  };

  const handleSaveCustomDoh = () => {
    const trimmed = customDnsDraft.trim();
    if (!trimmed.startsWith("https://") || trimmed.length < 12) {
      setToast("Enter a valid https:// DoH URL");
      return;
    }
    androidBridge()?.setUpstreamDns(4, trimmed);
    if (windows) {
      void setWindowsDns(4, trimmed).then((ok) => {
        if (!ok) setToast("That DoH endpoint could not be saved");
      });
    }
    setState((cur) => ({ ...cur, dns: 4, customDohUrl: trimmed }));
    setCustomDnsOpen(false);
    setDnsOpen(false);
    setToast("Custom DNS saved");
  };

  const handleAddAllow = (rawDomain: string) => {
    const clean = normalizeDomain(rawDomain);
    if (!clean || !clean.includes(".")) {
      setToast("Enter a valid domain (e.g. example.com)");
      return;
    }
    androidBridge()?.addAllowDomain(clean);
    setState((cur) => ({
      ...cur,
      allowDomains: Array.from(new Set([...cur.allowDomains, clean])),
      blockDomains: cur.blockDomains.filter((d) => d !== clean),
      recentBlocked: cur.recentBlocked.filter((r) => r.domain !== clean),
    }));
    setDomainInput("");
    if (windows) {
      void setWindowsRule(clean, "allow").then((ok) => {
        setToast(ok ? `${clean} allowed` : `Could not allow ${clean}`);
      });
      return;
    }
    setToast(`${clean} allowed`);
  };

  const handleAddBlock = (rawDomain: string) => {
    const clean = normalizeDomain(rawDomain);
    if (!clean || !clean.includes(".")) {
      setToast("Enter a valid domain (e.g. ads.example.com)");
      return;
    }
    androidBridge()?.addBlockDomain(clean);
    if (windows) {
      void setWindowsRule(clean, "block").then((ok) => {
        if (ok) setToast(`${clean} blocked`);
        else setToast(`Could not block ${clean}`);
      });
      setDomainInput("");
      setState((cur) => ({
        ...cur,
        blockDomains: Array.from(new Set([...cur.blockDomains, clean])),
        allowDomains: cur.allowDomains.filter((d) => d !== clean),
      }));
      return;
    }
    setState((cur) => ({
      ...cur,
      blockDomains: Array.from(new Set([...cur.blockDomains, clean])),
      allowDomains: cur.allowDomains.filter((d) => d !== clean),
    }));
    setDomainInput("");
    setToast(`${clean} blocked`);
  };

  const handleRemoveAllow = (domain: string) => {
    androidBridge()?.removeAllowDomain(domain);
    if (windows) void setWindowsRule(domain, "clear");
    setState((cur) => ({ ...cur, allowDomains: cur.allowDomains.filter((d) => d !== domain) }));
  };

  const handleRemoveBlock = (domain: string) => {
    androidBridge()?.removeBlockDomain(domain);
    if (windows) void setWindowsRule(domain, "clear");
    setState((cur) => ({ ...cur, blockDomains: cur.blockDomains.filter((d) => d !== domain) }));
  };

  const handleAddUrl = () => {
    const trimmed = urlInput.trim();
    if (!trimmed.startsWith("https://") && !trimmed.startsWith("http://")) {
      setToast("Enter a valid https:// blocklist URL");
      return;
    }
    androidBridge()?.addCustomListUrl(trimmed);
    setState((cur) => ({ ...cur, customUrls: Array.from(new Set([...cur.customUrls, trimmed])) }));
    setUrlInput("");
    setToast("Blocklist URL added");
  };

  const handleRemoveUrl = (url: string) => {
    androidBridge()?.removeCustomListUrl(url);
    setState((cur) => ({ ...cur, customUrls: cur.customUrls.filter((u) => u !== url) }));
  };

  const handleSyncLists = () => {
    if (state.syncing) return;
    const bridge = androidBridge();
    if (bridge) {
      bridge.syncLists();
      return;
    }
    setToast("Lists are up to date");
  };

  const handleToggleGameMode = () => {
    const next = !state.gameMode;
    if (next && state.offlineGameCount === 0) {
      setToast("Mark at least one app below as 'Offline Lock' first");
      return;
    }
    const bridge = androidBridge();
    if (bridge) {
      const ok = bridge.setOfflineGameMode(next);
      if (!ok && next) {
        setToast("Mark at least one app below as 'Offline Lock' first");
      }
      return;
    }
    setState((cur) => ({ ...cur, gameMode: next, shield: next ? "game" : "protected" }));
  };

  const handleSetAppMode = (app: InstalledAppItem, mode: AppMode) => {
    androidBridge()?.setAppMode(app.pkg, mode);
    setApps((cur) => cur.map((a) => (a.pkg === app.pkg ? { ...a, mode } : a)));
    setState((cur) => {
      const offline = cur.offlineGameCount + (mode === "Offline Lock" ? 1 : 0) - (app.mode === "Offline Lock" ? 1 : 0);
      return { ...cur, offlineGameCount: Math.max(0, offline) };
    });
    setToast(`${app.name} set to ${mode}`);
    setSelectedAppPkg(null);
  };

  const handleToggleFreeze = () => {
    if (!frozen) {
      setFrozenLogs(state.logs);
      setFrozen(true);
    } else {
      setFrozen(false);
    }
  };

  const handleToggleLogging = () => {
    const next = !state.logging;
    androidBridge()?.setLogging(next);
    if (windows) {
      void setWindowsLogging(next).then((ok) => {
        if (!ok) {
          setToast("Live log could not be changed");
          return;
        }
        setState((cur) => ({ ...cur, logging: next, logs: next ? cur.logs : [] }));
      });
      return;
    }
    setState((cur) => ({ ...cur, logging: next, logs: next ? cur.logs : [] }));
  };

  const handleClearLogs = () => {
    setFrozenLogs([]);
    androidBridge()?.clearLogs();
    if (windows) void clearWindowsLogs();
    setState((cur) => ({ ...cur, logs: [] }));
  };

  const handleUpdateDismiss = () => {
    if (updateInfo) dismissUpdate(updateInfo.versionCode);
    setUpdateInfo(null);
    setUpdateOpen(false);
  };

  const filteredApps = useMemo(() => {
    const q = appSearch.trim().toLowerCase();
    return apps.filter((app) => {
      const match = !q || `${app.name} ${app.pkg}`.toLowerCase().includes(q);
      if (!match) return false;
      if (appFilter === "Bypassed") return app.mode === "Bypassed";
      if (appFilter === "Offline Locks") return app.mode === "Offline Lock";
      if (appFilter === "System Apps") return app.isSystem;
      return !app.isSystem || app.mode !== "Protected";
    });
  }, [apps, appSearch, appFilter]);

  const selectedApp = useMemo(
    () => (selectedAppPkg ? apps.find((a) => a.pkg === selectedAppPkg) ?? null : null),
    [apps, selectedAppPkg],
  );

  const activeLogSource = frozen ? frozenLogs : state.logs;
  const filteredLogs = useMemo(() => {
    const q = logSearch.trim().toLowerCase();
    return activeLogSource.filter((log) => {
      const searchMatch = !q || log.domain.toLowerCase().includes(q) || log.category.toLowerCase().includes(q);
      if (logFilter === "Blocked") return searchMatch && log.status === "Blocked";
      if (logFilter === "Allowed") return searchMatch && log.status === "Allowed";
      return searchMatch;
    });
  }, [activeLogSource, logSearch, logFilter]);

  const nav = useMemo(
    () =>
      [
        ["home", "Home", "shield"],
        ["blocking", "Blocking", "sliders"],
        ["apps", "Apps", "apps"],
        ["burner", "Burner", "mail"],
        ["activity", "Activity", "pulse"],
      ] as const,
    [],
  );

  const isSelectedLogCustomRuled =
    selectedLog !== null &&
    (state.allowDomains.includes(selectedLog.domain) || state.blockDomains.includes(selectedLog.domain));

  return (
    <div className="app">
      <header className="topbar">
        <button className="brand" onClick={() => setTab("home")}>
          <span>
            <Icon name="shield" size={20} />
          </span>
          <b>Omit</b>
        </button>
        <div className="topbar-actions">
          {updateInfo && (
            <button
              className="update-pill"
              onClick={() => setUpdateOpen(true)}
              aria-label={`Update available v${updateInfo.version}`}
            >
              <span className="update-dot" />
              v{updateInfo.version}
            </button>
          )}
          <button className="icon-btn theme-toggle" onClick={toggleDark} aria-label="Toggle appearance">
            <Icon name={state.dark ? "sun" : "moon"} />
          </button>
        </div>
      </header>

      <main>
        <TamperBanner />
        {tab === "home" && (
          <>
            <Home
              ready={windowsReady}
              shield={state.shield}
              countdown={state.countdown}
              intensity={state.intensity}
              offlineGameCount={state.offlineGameCount}
              blockedCount={state.blockedCount}
              totalQueries={state.totalQueries}
              activeRules={state.activeRules}
              privateDnsHost={state.privateDnsHost}
              recentBlocked={state.recentBlocked}
              spyStats={spyStats}
              onSpy={() => setSpyOpen(true)}
              onShield={handleShieldAction}
              onIntensity={() => setTab("blocking")}
              onAllowDomain={handleAddAllow}
              onOpenPrivateDns={() => {
                const bridge = androidBridge();
                if (bridge) {
                  bridge.openPrivateDnsSettings();
                } else {
                  setToast("Opening network settings");
                }
              }}
            />
            {!windows && <HomeShieldCard onToast={setToast} />}
            {windows && <WindowsStatusCard onToast={setToast} />}
          </>
        )}

        {tab === "burner" && (
          <BurnerErrorBoundary>
            <BurnerScreen onToast={setToast} />
          </BurnerErrorBoundary>
        )}

        {tab === "blocking" && (
          <div className="page">
            <Header title="Blocking" subtitle="Choose how much Omit should block." />

            <section className="section">
              <div className="section-title">
                <h2>Intensity</h2>
                <span>{state.intensity}</span>
              </div>
              <div className="intensity-list">
                {intensityData.map((item, index) => (
                  <IntensityOption
                    key={item[0]}
                    item={item}
                    selected={state.intensity === item[0]}
                    onSelect={() => chooseIntensity(item[0], index)}
                  />
                ))}
              </div>
            </section>

            <section className="section" ref={categoriesRef}>
              <div className="section-title">
                <div>
                  <h2>Categories</h2>
                  <p>Tap a row for details</p>
                </div>
                <span>{state.categories.filter(Boolean).length} on</span>
              </div>
              <div className="row-list">
                {categoryData.map((item, index) => {
                  const override = windows ? WINDOWS_CATEGORY_LABELS[item[0]] : undefined;
                  return (
                    <ExpandRow
                      key={item[0]}
                      title={override?.[0] ?? item[0]}
                      on={Boolean(state.categories[index])}
                      onChange={() => handleToggleCategory(index)}
                      benefit={override?.[1] ?? item[1]}
                      consequence={override?.[2] ?? item[2]}
                    />
                  );
                })}
              </div>
            </section>

            <section className="section">
              <div className="section-title">
                <div>
                  <h2>DNS &amp; engine</h2>
                  <p>Encrypted and on-device</p>
                </div>
              </div>
              <button className="select-row" onClick={() => setDnsOpen(true)}>
                <span className="row-icon">
                  <Icon name="globe" />
                </span>
                <span>
                  <small>Upstream DNS</small>
                  <b>{dnsData[state.dns]?.[0] ?? "Google DNS"}</b>
                </span>
                <Icon name="arrow" />
              </button>
              <div className="row-list engine-rows">
                {(windows ? windowsEngineData : engineData).map((item, index) => (
                  <ExpandRow
                    key={item[0]}
                    title={item[0]}
                    on={Boolean(state.engine[index])}
                    onChange={() => handleToggleEngine(index)}
                    benefit={item[1]}
                    consequence={item[2]}
                  />
                ))}
              </div>
            </section>

            <section className="section custom-section">
              <div className="section-title">
                <div>
                  <h2>Custom rules</h2>
                  <p>
                    {state.allowDomains.length} allowed · {state.blockDomains.length} blocked ·{" "}
                    {state.customUrls.length} lists
                  </p>
                </div>
              </div>
              <div className="stack-actions">
                <button className="select-row" onClick={() => setRulesOpen(true)}>
                  <span className="row-icon">
                    <Icon name="globe" />
                  </span>
                  <b>Allow or block domains</b>
                  <Icon name="arrow" />
                </button>
                {/* Remote list sync compiles into the APK's on-device table, so it is Android-only. */}
                {!windows && (
                  <button className="select-row" onClick={() => setUrlsOpen(true)}>
                    <span className="row-icon">
                      <Icon name="plus" />
                    </span>
                    <b>Custom blocklist URLs</b>
                    <Icon name="arrow" />
                  </button>
                )}
                {!windows && (
                  <button className="primary-btn" disabled={state.syncing} onClick={handleSyncLists}>
                    {state.syncing ? "Syncing lists..." : "Sync lists"}
                  </button>
                )}
                {windows && (
                  <p className="section-hint">
                    Omit for Windows ships a fixed rule table; add domains above to allow or block them.
                  </p>
                )}
              </div>
            </section>
          </div>
        )}

        {tab === "apps" && windows && (
          <div className="page">
            <Header title="Apps" subtitle="Every app on this PC is covered." />
            <section className="section">
              <div className="panel">
                <div className="row-item home-shield-row">
                  <div className="row-copy">
                    <strong>System-wide filtering</strong>
                    <span>
                      Omit filters DNS for the whole PC, so browsers, games and store apps are
                      covered without per-app rules.
                    </span>
                  </div>
                  <span className="status-chip on">All apps</span>
                </div>
                <div className="home-shield-body">
                  <div className="home-shield-note">
                    Need an exception? Add the domain under Blocking → Allow or block domains.
                  </div>
                </div>
              </div>
            </section>

            {/* Per-app connect modes need the Android VPN; Windows bypasses by domain. */}
            <section className="section">
              <div className="section-title">
                <div>
                  <h2>Bypassed domains</h2>
                  <p>Always allowed, even when the shield is on</p>
                </div>
                <span>{state.allowDomains.length}</span>
              </div>
              <div className="row-list">
                {state.allowDomains.length ? (
                  state.allowDomains.map((domain) => (
                    <div key={domain} className="row-item">
                      <span className="domain-icon">{domain[0]?.toUpperCase() ?? "D"}</span>
                      <div className="row-copy">
                        <strong>{domain}</strong>
                        <span>Allowed by a custom rule</span>
                      </div>
                      <button
                        type="button"
                        className="pill-btn"
                        onClick={() => handleRemoveAllow(domain)}
                      >
                        Remove
                      </button>
                    </div>
                  ))
                ) : (
                  <div className="empty">
                    <Icon name="wifi" />
                    <p>
                      Windows has no per-app VPN. Allow a domain instead and it stays reachable from
                      every app.
                    </p>
                  </div>
                )}
              </div>
            </section>
          </div>
        )}

        {tab === "apps" && !windows && (
          <div className="page">
            <Header title="Apps" subtitle="Choose how each app connects." />

            <section className={`game-card ${state.gameMode ? "active" : ""}`}>
              <div className="game-head">
                <span className="game-icon">
                  <Icon name="game" />
                </span>
                <span>
                  <h2>Offline Lock</h2>
                  <p>
                    {state.gameMode
                      ? `${state.offlineGameCount} ${state.offlineGameCount === 1 ? "app is" : "apps are"} offline`
                      : state.offlineGameCount > 0
                        ? `Play with zero ads · ${state.offlineGameCount} selected`
                        : "Play with zero ads"}
                  </p>
                </span>
                <Toggle on={state.gameMode} onChange={handleToggleGameMode} label="Offline Lock" />
              </div>
              <button className="learn-row" onClick={() => setGameDetails(!gameDetails)} aria-expanded={gameDetails}>
                How it works <Icon name={gameDetails ? "up" : "down"} size={16} />
              </button>
              {gameDetails && (
                <Detail
                  benefit="Selected apps get no internet, ads, or tracking."
                  consequence="Multiplayer, cloud saves, and rewarded ads will not work. Global DNS filtering pauses until Offline Lock is turned off."
                />
              )}
            </section>

            {spyStats.length > 0 && (
              <section className="section">
                <button className="select-row spy-row" onClick={() => setSpyOpen(true)}>
                  <span className="row-icon">
                    <Icon name="pulse" />
                  </span>
                  <span>
                    <small>Who&apos;s spying?</small>
                    <b>{spyStats.reduce((sum, s) => sum + s.spyCount, 0)} tracker pings caught</b>
                  </span>
                  <Icon name="arrow" />
                </button>
              </section>
            )}

            <section className="section apps-section">
              <label className="search">
                <Icon name="search" />
                <input
                  value={appSearch}
                  onChange={(event) => setAppSearch(event.target.value)}
                  placeholder="Search apps"
                />
              </label>
              <div className="chips">
                {["User Apps", "Bypassed", "Offline Locks", "System Apps"].map((filter) => (
                  <button
                    className={appFilter === filter ? "active" : ""}
                    key={filter}
                    onClick={() => setAppFilter(filter)}
                  >
                    {filter}
                  </button>
                ))}
              </div>
              <div className="app-list">
                {filteredApps.length ? (
                  filteredApps.map((app) => (
                    <button key={app.pkg} className="app-row" onClick={() => setSelectedAppPkg(app.pkg)}>
                      <AppBadge app={app} />
                      <span className="app-copy">
                        <b>{app.name}</b>
                        <small>{app.pkg}</small>
                      </span>
                      <span className={`mode ${app.mode.toLowerCase().replace(" ", "-")}`}>{app.mode}</span>
                      <Icon name="arrow" size={17} />
                    </button>
                  ))
                ) : (
                  <div className="empty">
                    <Icon name="apps" />
                    <p>{windows ? "Per-app rules are available on Android" : "No matching apps"}</p>
                  </div>
                )}
              </div>
            </section>
          </div>
        
        )}

        {tab === "activity" && (
          <div className="page">
            <Header title="Activity" subtitle="Live DNS requests. Nothing is saved." />

            <section className="section activity-section">
              <div className="activity-actions">
                <span className="live-control">
                  <Toggle on={state.logging} onChange={handleToggleLogging} label="Live log" />
                  <b>Live log</b>
                </span>
                <button className={`small-btn ${frozen ? "active" : ""}`} onClick={handleToggleFreeze}>
                  <Icon name={frozen ? "play" : "pause"} size={16} />
                  {frozen ? "Resume" : "Freeze"}
                </button>
                <button className="small-btn danger" onClick={handleClearLogs}>
                  Clear
                </button>
              </div>
              <label className="search">
                <Icon name="search" />
                <input
                  value={logSearch}
                  onChange={(event) => setLogSearch(event.target.value)}
                  placeholder="Search domains"
                />
              </label>
              <div className="chips">
                {["All", "Blocked", "Allowed"].map((filter) => (
                  <button
                    className={logFilter === filter ? "active" : ""}
                    key={filter}
                    onClick={() => setLogFilter(filter)}
                  >
                    {filter}
                  </button>
                ))}
              </div>
              <div className="log-list">
                {filteredLogs.length ? (
                  filteredLogs.map((log, index) => (
                    <button
                      className="log-row"
                      key={`${log.id}-${log.domain}-${index}`}
                      onClick={() => setSelectedLog(log)}
                    >
                      <span className={`log-dot ${log.status.toLowerCase()}`} />
                      <span>
                        <b>{log.domain}</b>
                        <small>
                          {log.time} · {log.category}
                        </small>
                      </span>
                      <em className={log.status.toLowerCase()}>{log.status}</em>
                      <Icon name="arrow" size={16} />
                    </button>
                  ))
                ) : (
                  <div className="empty">
                    <Icon name="pulse" />
                    <p>
                      {state.logging
                        ? "Open any app or website to see live DNS requests"
                        : "Live logging is turned off"}
                    </p>
                  </div>
                )}
              </div>
            </section>
          </div>
        )}
      </main>

      <nav className="bottom-nav" aria-label="Sections">
        {nav.map(([id, label, icon]) => (
          <button className={tab === id ? "active" : ""} key={id} onClick={() => setTab(id)}>
            <span>
              <Icon name={icon} />
            </span>
            {label}
          </button>
        ))}
      </nav>

      {dnsOpen && (
        <Modal onClose={() => setDnsOpen(false)}>
          <div className="modal-head">
            <span className="modal-icon">
              <Icon name="globe" />
            </span>
            <h2>Upstream DNS</h2>
            <p>Choose your encrypted resolver.</p>
          </div>
          <div className="choice-list">
            {dnsData.map((item, index) => (
              <button
                className={state.dns === index ? "selected" : ""}
                key={item[0]}
                onClick={() => handleSelectDns(index)}
              >
                <span className="radio">{state.dns === index && <i />}</span>
                <span>
                  <b>{item[0]}</b>
                  <small>{index === 4 && state.dns === 4 ? state.customDohUrl : item[1]}</small>
                </span>
              </button>
            ))}
          </div>
          <Detail
            benefit={dnsData[state.dns]?.[2] ?? dnsData[0][2]}
            consequence={dnsData[state.dns]?.[3] ?? dnsData[0][3]}
          />
        </Modal>
      )}

      {customDnsOpen && (
        <Modal
          onClose={() => {
            setCustomDnsOpen(false);
            setDnsOpen(false);
          }}
        >
          <div className="modal-head">
            <span className="modal-icon">
              <Icon name="lock" />
            </span>
            <h2>Custom DoH</h2>
            <p>Enter an encrypted DNS endpoint.</p>
          </div>
          <label className="field">
            <span>Server URL</span>
            <input
              autoFocus
              value={customDnsDraft}
              onChange={(e) => setCustomDnsDraft(e.target.value)}
              placeholder="https://dns.google/dns-query"
            />
          </label>
          <button className="primary-btn" onClick={handleSaveCustomDoh}>
            Save
          </button>
        </Modal>
      )}

      {rulesOpen && (
        <Modal onClose={() => setRulesOpen(false)}>
          <div className="modal-head">
            <span className="modal-icon">
              <Icon name="globe" />
            </span>
            <h2>Custom rules</h2>
            <p>Manual rules override your intensity.</p>
          </div>
          <label className="field">
            <span>Domain (matches subdomains too)</span>
            <input
              value={domainInput}
              onChange={(e) => setDomainInput(e.target.value)}
              placeholder="example.com"
            />
          </label>
          <div className="two-actions">
            <button className="allow-btn" onClick={() => handleAddAllow(domainInput)}>
              Always allow
            </button>
            <button className="block-btn" onClick={() => handleAddBlock(domainInput)}>
              Always block
            </button>
          </div>
          <div className="rule-tags">
            {state.allowDomains.map((d) => (
              <span key={`allow-${d}`} className="tag-allow">
                <b>Allow · {d}</b>
                <button onClick={() => handleRemoveAllow(d)} aria-label={`Remove ${d}`}>
                  <Icon name="close" size={13} />
                </button>
              </span>
            ))}
            {state.blockDomains.map((d) => (
              <span key={`block-${d}`} className="tag-block">
                <b>Block · {d}</b>
                <button onClick={() => handleRemoveBlock(d)} aria-label={`Remove ${d}`}>
                  <Icon name="close" size={13} />
                </button>
              </span>
            ))}
          </div>
        </Modal>
      )}

      {urlsOpen && (
        <Modal onClose={() => setUrlsOpen(false)}>
          <div className="modal-head">
            <span className="modal-icon">
              <Icon name="plus" />
            </span>
            <h2>Custom blocklist URLs</h2>
            <p>Included whenever you tap Sync lists.</p>
          </div>
          <label className="field">
            <span>Hosts or domain-list URL</span>
            <input
              value={urlInput}
              onChange={(e) => setUrlInput(e.target.value)}
              placeholder="https://raw.githubusercontent.com/..."
            />
          </label>
          <button className="primary-btn" onClick={handleAddUrl}>
            Add custom list URL
          </button>
          <div className="rule-tags">
            {state.customUrls.map((u) => (
              <span key={u}>
                <b>{u}</b>
                <button onClick={() => handleRemoveUrl(u)} aria-label="Remove list URL">
                  <Icon name="close" size={13} />
                </button>
              </span>
            ))}
          </div>
        </Modal>
      )}

      {selectedApp && (
        <Modal onClose={() => setSelectedAppPkg(null)}>
          <div className="app-modal-head">
            <AppBadge app={selectedApp} />
            <span>
              <h2>{selectedApp.name}</h2>
              <p>{selectedApp.pkg}</p>
            </span>
          </div>
          <div className="mode-list">
            {(
              [
                ["Protected", "shield", "Normal filtering", "Ads and trackers are blocked."],
                ["Bypassed", "wifi", "Direct internet", "Omit skips & hides VPN from this app."],
                [
                  "Offline Lock",
                  "game",
                  "No internet or ads",
                  "Online features will not work while Offline Lock is on.",
                ],
              ] as const
            ).map(([mode, icon, benefit, consequence]) => (
              <button
                className={selectedApp.mode === mode ? "selected" : ""}
                key={mode}
                onClick={() => handleSetAppMode(selectedApp, mode)}
              >
                <span className="mode-icon">
                  <Icon name={icon} />
                </span>
                <span>
                  <b>{mode}</b>
                  <small>
                    <strong>Benefit:</strong> {benefit}
                  </small>
                  <small>
                    <strong>Trade-off:</strong> {consequence}
                  </small>
                </span>
                <span className="radio">{selectedApp.mode === mode && <i />}</span>
              </button>
            ))}
          </div>
        </Modal>
      )}

      {selectedLog && (
        <Modal onClose={() => setSelectedLog(null)}>
          <div className="log-modal-head">
            <span className={`log-status-icon ${selectedLog.status.toLowerCase()}`}>
              <Icon name={selectedLog.status === "Blocked" ? "shield" : "check"} />
            </span>
            <span>
              <small>{selectedLog.status}</small>
              <h2>{selectedLog.domain}</h2>
              <p>
                {selectedLog.time} · {selectedLog.category}
              </p>
            </span>
          </div>
          <button
            className="primary-btn"
            onClick={() => {
              if (selectedLog.status === "Blocked") {
                handleAddAllow(selectedLog.domain);
              } else {
                handleAddBlock(selectedLog.domain);
              }
              setSelectedLog(null);
            }}
          >
            {selectedLog.status === "Blocked" ? "Always allow" : "Always block"}
          </button>
          {isSelectedLogCustomRuled && (
            <button
              className="secondary-modal-btn"
              onClick={() => {
                const bridge = androidBridge();
                if (bridge) {
                  bridge.removeCustomRuleForDomain(selectedLog.domain);
                }
                if (windows) void clearWindowsRule(selectedLog.domain);
                handleRemoveAllow(selectedLog.domain);
                handleRemoveBlock(selectedLog.domain);
                setToast(`Custom rule removed for ${selectedLog.domain}`);
                setSelectedLog(null);
              }}
            >
              Remove custom rule
            </button>
          )}
        </Modal>
      )}

      {spyOpen && (
        <Modal onClose={() => setSpyOpen(false)}>
          <div className="modal-head">
            <span className="modal-icon">
              <Icon name="pulse" />
            </span>
            <h2>Who&apos;s spying?</h2>
            <p>Apps that pinged blocked trackers this session.</p>
          </div>
          {spyStats.length === 0 ? (
            <div className="empty">
              <Icon name="shield" />
              <p>No background tracker pings caught yet.</p>
            </div>
          ) : (
            <div className="spy-list">
              {spyStats.map((stat) => (
                <div className="spy-item" key={stat.packageName}>
                  <span className="spy-dot" />
                  <span>
                    <b>{stat.appName}</b>
                    <small>
                      {stat.spyCount} pings{stat.bgWakeups > 0 ? ` · ${stat.bgWakeups} while sleeping` : ""}
                    </small>
                    {stat.lastDomain && <small className="spy-domain">{stat.lastDomain}</small>}
                  </span>
                </div>
              ))}
            </div>
          )}
          <Detail
            benefit="See exactly which installed apps ping trackers in the background."
            consequence="Counts are session-only and reset when Omit restarts."
          />
        </Modal>
      )}

      {updateInfo && updateOpen && (
        <Modal onClose={() => setUpdateOpen(false)}>
          <div className="modal-head">
            <span className="modal-icon">
              <Icon name="download" />
            </span>
            <h2>Omit v{updateInfo.version} is ready</h2>
            <p>{updateInfo.notes || "Faster blocking, fixes, and polish."}</p>
          </div>
          <div className="stack-actions">
            {isAndroidRuntime() && updateInfo.otaUrl && updateInfo.otaSha256 && (
              <button
                className="primary-btn"
                onClick={() => {
                  applyOtaUpdate(updateInfo);
                  setUpdateOpen(false);
                }}
              >
                Instant UI-only update
              </button>
            )}
            <button
              className="secondary-modal-btn"
              onClick={() => {
                downloadAndInstallUpdate(updateInfo);
                setUpdateOpen(false);
              }}
            >
              {windows ? "Download Windows update" : "Download full app"}
            </button>
            <button className="secondary-modal-btn" onClick={handleUpdateDismiss}>
              Not now
            </button>
          </div>
        </Modal>
      )}

      {toast && (
        <div className="toast" role="status">
          <Icon name="check" size={17} />
          {toast}
        </div>
      )}
    </div>
  );
}

function IntensityOption({
  item,
  selected,
  onSelect,
}: {
  item: (typeof intensityData)[number];
  selected: boolean;
  onSelect: () => void;
}) {
  const [open, setOpen] = useState(false);
  return (
    <div className={`intensity-option ${selected ? "selected" : ""}`}>
      <button className="intensity-main" onClick={onSelect}>
        <span className="radio">{selected && <i />}</span>
        <span>
          <b>{item[0]}</b>
          <small>{item[1]}</small>
        </span>
      </button>
      <button className="info-toggle" onClick={() => setOpen(!open)} aria-label={`About ${item[0]}`}>
        <Icon name={open ? "up" : "down"} size={16} />
      </button>
      {open && <Detail benefit={item[2]} consequence={item[3]} />}
    </div>
  );
}

function Home({
  shield,
  countdown,
  intensity,
  offlineGameCount,
  blockedCount,
  totalQueries,
  activeRules,
  privateDnsHost,
  recentBlocked,
  spyStats,
  onSpy,
  onShield,
  onIntensity,
  onAllowDomain,
  onOpenPrivateDns,
  ready,
}: {
  shield: ShieldState;
  ready: boolean;
  countdown: number;
  intensity: string;
  offlineGameCount: number;
  blockedCount: number;
  totalQueries: number;
  activeRules: number;
  privateDnsHost: string;
  recentBlocked: RecentBlockedItem[];
  spyStats: SpyStatItem[];
  onSpy: () => void;
  onShield: (state: ShieldState) => void;
  onIntensity: () => void;
  onAllowDomain: (domain: string) => void;
  onOpenPrivateDns: () => void;
}) {
  const copy = {
    off: ["Protection is off", "Tap the shield to turn on"],
    protected: ["You’re protected", "Ads and trackers are blocked"],
    paused: ["Protection paused", `${countdown}s until Omit resumes`],
    game: [
      "Offline Lock",
      `${offlineGameCount} ${offlineGameCount === 1 ? "game has" : "games have"} no internet`,
    ],
  }[shield];

  const backgroundCaught = spyStats.reduce((sum, s) => sum + s.bgWakeups, 0);

  const toggleShield = () => {
    if (shield === "off") onShield("protected");
    else onShield("off");
  };

  return (
    <div className="page home-page">
      <Header title="Home" />

      <section className={`shield-card ${shield}`}>
        <button className="shield-button" onClick={toggleShield} aria-label="Toggle Omit">
          <span className="shield-ring">
            <span>
              <Icon name={shield === "game" ? "game" : shield === "paused" ? "pause" : "shield"} size={47} />
            </span>
          </span>
        </button>
        <h2>{ready ? copy[0] : "Checking shield..."}</h2>
        <p>{ready ? copy[1] : "Reading the local DNS engine"}</p>
        <div className="shield-actions">
          {shield === "protected" && (
            <button className="secondary-btn" onClick={() => onShield("paused")}>
              <Icon name="pause" size={16} /> Pause 60s
            </button>
          )}
          {shield === "paused" && (
            <button className="primary-btn light" onClick={() => onShield("protected")}>
              <Icon name="play" size={16} /> Resume
            </button>
          )}
          {shield === "game" && (
            <button className="secondary-btn" onClick={() => onShield("protected")}>
              Turn off Offline Lock
            </button>
          )}
        </div>
      </section>

      <button className="intensity-pill" onClick={onIntensity}>
        <span>Blocking intensity</span>
        <b>{intensity}</b>
        <Icon name="arrow" size={16} />
      </button>

      <section className="stat-row">
        <div>
          <span>Blocked</span>
          <b>{formatCompactCount(blockedCount)}</b>
        </div>
        <div>
          <span>Queries</span>
          <b>{formatCompactCount(totalQueries)}</b>
        </div>
        <div>
          <span>Active rules</span>
          <b>{formatCompactCount(activeRules)}</b>
        </div>
      </section>

      {privateDnsHost !== "" && (
        <section className="warning-card">
          <span>
            <Icon name="warning" />
          </span>
          <div>
            <b>Private DNS conflict</b>
            <p>Android Private DNS ({privateDnsHost}) may bypass Omit.</p>
          </div>
          <button onClick={onOpenPrivateDns}>Open Settings</button>
        </section>
      )}

      {spyStats.length > 0 && (
        <button className="intensity-pill spy-pill" onClick={onSpy}>
          <span>Caught in background</span>
          <b>{backgroundCaught} pings</b>
          <Icon name="arrow" size={16} />
        </button>
      )}

      <section className="recent-section">
        <div className="section-title">
          <h2>Recently blocked</h2>
        </div>
        <div className="recent-list">
          {recentBlocked.length > 0 ? (
            recentBlocked.map((item) => (
              <div key={`${item.domain}-${item.time}`}>
                <span className="domain-icon">{item.domain[0]?.toUpperCase() ?? "D"}</span>
                <span>
                  <b>{item.domain}</b>
                  <small>
                    {item.category} · {item.time}
                  </small>
                </span>
                <button onClick={() => onAllowDomain(item.domain)}>Allow</button>
              </div>
            ))
          ) : (
            <div className="recent-empty">Blocked domains appear here for instant 1-tap unblocking</div>
          )}
        </div>
      </section>
    </div>
  );
}

function HomeShieldCard({ onToast }: { onToast: (msg: string) => void }) {
  const initial = nativeGetHomeShieldInfo();
  const [homeOn, setHomeOn] = useState<boolean>(initial.enabled);
  const [homeInfo, setHomeInfo] = useState<HomeShieldInfo>(initial);

  const copyText = (txt: string, label: string) => {
    if (!txt) return;
    copyToClipboard(txt);
    onToast(`${label} copied`);
  };

  const toggleHomeShield = () => {
    const next = !homeOn;
    const ok = nativeSetHomeShield(next);
    const info = nativeGetHomeShieldInfo();
    if (next && !ok) {
      // Ports 8080 and 8088 were both taken; keep the switch honest.
      setHomeOn(false);
      setHomeInfo(info);
      onToast("Could not start the Wi-Fi proxy");
      return;
    }
    setHomeOn(next);
    setHomeInfo(info);
    onToast(next ? "Wi-Fi proxy on" : "Wi-Fi proxy off");
  };

  const autoUrl = homeInfo.port ? `http://${homeInfo.lanIp}:${homeInfo.port}/omit.pac` : "";
  const manualProxy = homeInfo.port ? `${homeInfo.lanIp}:${homeInfo.port}` : "";

  return (
    <section className="section">
      <div className="panel">
        <div className="row-item home-shield-row">
          <div className="row-copy">
            <strong>Share Ad-Block to Wi-Fi</strong>
            <span>Route other devices on this Wi-Fi through Omit</span>
          </div>
          <button
            type="button"
            className={`switch ${homeOn ? "on" : ""}`}
            aria-pressed={homeOn}
            aria-label="Share Ad-Block to Wi-Fi"
            onClick={toggleHomeShield}
          >
            <span className="switch-knob" />
          </button>
        </div>
        {homeOn && (
          <div className="home-shield-body">
            <div className="home-shield-line">
              <span>
                Auto config URL: <strong>{autoUrl}</strong>
              </span>
              <button
                type="button"
                className="pill-btn"
                onClick={() => copyText(autoUrl, "Auto config URL")}
              >
                Copy
              </button>
            </div>
            <div className="home-shield-line">
              <span>
                HTTP proxy: <strong>{manualProxy}</strong>
              </span>
              <button
                type="button"
                className="pill-btn"
                onClick={() => copyText(manualProxy, "Proxy address")}
              >
                Copy
              </button>
            </div>
            <div className="home-shield-note">
              {homeInfo.blockedCount} requests blocked from other devices
            </div>
          </div>
        )}
      </div>
    </section>
  );
}

function WindowsStatusCard({ onToast }: { onToast: (msg: string) => void }) {
  const [status, setStatus] = useState<Awaited<ReturnType<typeof windowsShieldStatus>>>(null);
  const [rules, setRules] = useState<number | null>(null);
  const [dnsLocked, setDnsLocked] = useState<boolean | null>(null);

  useEffect(() => {
    let mounted = true;
    const refresh = async () => {
      const [next, stats, locked] = await Promise.all([
        windowsShieldStatus(),
        windowsStats(),
        windowsDnsLock(),
      ]);
      if (!mounted) return;
      if (next) setStatus(next);
      if (stats) setRules(stats.rules);
      setDnsLocked(locked);
    };
    void refresh();
    const timer = window.setInterval(() => void refresh(), 4000);
    return () => {
      mounted = false;
      window.clearInterval(timer);
    };
  }, []);

  const rulesMissing = rules === 0;

  return (
    <section className="section">
      <div className="panel">
        <div className="row-item home-shield-row">
          <div className="row-copy">
            <strong>System DNS shield</strong>
            <span>
              {status?.active
                ? status.listening
                  ? "Filtering every app on this PC on 127.0.0.1:53"
                  : "Shield is on, waiting for port 53"
                : "Starts a local DNS sinkhole for every app on this PC"}
            </span>
          </div>
          <span className={`status-chip ${status?.active ? "on" : ""}`}>
            {status?.active ? "Active" : "Off"}
          </span>
        </div>
        <div className="home-shield-body">
          {rulesMissing && (
            <div className="home-shield-note warn">
              No blocklist was found next to Omit. Reinstall so the rule table ships alongside the app.
            </div>
          )}
          {status && !status.admin && (
            <div className="home-shield-note warn">
              Run Omit as administrator so Windows DNS can be redirected.
            </div>
          )}
          {status?.message && status.admin && <div className="home-shield-note">{status.message}</div>}
          <div className="home-shield-line">
            <span>
              {rules ? `${rules.toLocaleString("en-US")} rules loaded` : "Loading rule table"} ·{" "}
              {status?.active
                ? dnsLocked
                  ? "adapter DNS pinned to Omit"
                  : "adapter DNS not pinned — re-check elevation"
                : "adapter DNS is restored on exit"}
            </span>
            <button
              type="button"
              className="pill-btn"
              onClick={() =>
                onToast(
                  status?.active
                    ? dnsLocked
                      ? "Every adapter resolves through Omit"
                      : "Turn the shield off and on to re-pin adapter DNS"
                    : "Closing Omit restores your normal DNS",
                )
              }
            >
              Info
            </button>
          </div>
        </div>
      </div>
    </section>
  );
}

class BurnerErrorBoundary extends Component<{ children: ReactNode }, { err: string | null }> {
  state = { err: null as string | null };
  static getDerivedStateFromError(error: unknown) {
    return { err: error instanceof Error ? error.message : String(error) };
  }
  render() {
    if (this.state.err) {
      return (
        <div className="page">
          <div className="panel" style={{ padding: 16 }}>
            <strong>Burner Error</strong>
            <p style={{ fontSize: 13, opacity: 0.8, marginTop: 6 }}>{this.state.err}</p>
            <button
              type="button"
              className="pill-btn"
              style={{ marginTop: 10 }}
              onClick={() => this.setState({ err: null })}
            >
              Retry
            </button>
          </div>
        </div>
      );
    }
    return this.props.children;
  }
}

interface BurnerPayload {
  type?: string;
  email?: { address?: string; error?: string };
  inbox?: BurnerEmailMsg[];
  numbers?: BurnerSmsNum[];
  messages?: BurnerSmsMsg[];
}

function BurnerScreen({ onToast }: { onToast: (msg: string) => void }) {
  const [burnerTab, setBurnerTab] = useState<"email" | "sms">("email");
  const [emailAddr, setEmailAddr] = useState<string>("");
  const [emailMsgs, setEmailMsgs] = useState<BurnerEmailMsg[]>([]);
  const [smsNums, setSmsNums] = useState<BurnerSmsNum[]>([]);
  const [smsIdx, setSmsIdx] = useState<number>(0);
  const [smsMsgs, setSmsMsgs] = useState<BurnerSmsMsg[]>([]);
  const [loading, setLoading] = useState<boolean>(false);

  const onToastRef = useRef(onToast);
  onToastRef.current = onToast;

  const copyText = (txt: string, label: string) => {
    if (!txt) return;
    copyToClipboard(txt);
    onToastRef.current(`${label} copied`);
  };

  useEffect(() => {
    if (!isAndroidRuntime()) return;
    const handler = (raw: string) => {
      try {
        const data = JSON.parse(raw) as BurnerPayload;
        if (data.type === "email") {
          if (data.email?.address) setEmailAddr(data.email.address);
          else if (data.email?.error) onToastRef.current(data.email.error);
          if (Array.isArray(data.inbox)) setEmailMsgs(data.inbox);
          setLoading(false);
        } else if (data.type === "smsNumbers") {
          if (Array.isArray(data.numbers) && data.numbers.length) {
            setSmsNums(data.numbers);
            const current = data.numbers[smsIdx % data.numbers.length];
            androidBridge()?.fetchBurnerSmsMessagesAsync?.(current.digits);
          } else {
            setLoading(false);
          }
        } else if (data.type === "smsMessages") {
          if (Array.isArray(data.messages)) setSmsMsgs(data.messages);
          setLoading(false);
        }
      } catch {
        setLoading(false);
      }
    };
    (window as unknown as { __onBurnerData?: (raw: string) => void }).__onBurnerData = handler;
    return () => {
      delete (window as unknown as { __onBurnerData?: (raw: string) => void }).__onBurnerData;
    };
  }, [smsIdx]);

  // Windows talks to the same providers through Rust commands (no CORS, and the
  // API keys stay out of the webview). Android uses its JavascriptInterface.
  const invoke = isWindowsRuntime() ? window.__TAURI__?.core?.invoke : undefined;

  const loadEmail = (forceNew = false) => {
    const bridge = androidBridge();
    setLoading(true);
    if (bridge?.fetchBurnerEmailAsync) {
      bridge.fetchBurnerEmailAsync(forceNew);
      return;
    }
    if (invoke) {
      void (async () => {
        try {
          const res = await invoke<{ address?: string; error?: string }>("get_burner_email", {
            forceNew,
          });
          if (res?.address) setEmailAddr(res.address);
          else if (res?.error) onToastRef.current(res.error);
          const inbox = await invoke<BurnerEmailMsg[]>("get_burner_inbox");
          if (Array.isArray(inbox)) setEmailMsgs(inbox);
        } catch {
          onToastRef.current("Could not reach the burner email provider");
        } finally {
          setLoading(false);
        }
      })();
      return;
    }
    window.setTimeout(() => {
      const res = nativeGetBurnerEmail(forceNew);
      if (res.address) setEmailAddr(res.address);
      else if (res.error) onToast(res.error);
      setEmailMsgs(nativeGetBurnerInbox());
      setLoading(false);
    }, 20);
  };

  const loadSms = (idx = smsIdx) => {
    const bridge = androidBridge();
    setLoading(true);
    if (invoke) {
      void (async () => {
        try {
          let numbers = smsNums;
          if (!numbers.length) {
            const fetched = await invoke<BurnerSmsNum[]>("get_burner_sms_numbers");
            if (Array.isArray(fetched)) numbers = fetched;
            if (numbers.length) setSmsNums(numbers);
          }
          if (numbers.length) {
            const current = numbers[idx % numbers.length];
            const messages = await invoke<BurnerSmsMsg[]>("get_burner_sms_messages", {
              digits: current.digits,
            });
            if (Array.isArray(messages)) setSmsMsgs(messages);
          } else {
            onToastRef.current("Could not load SMS numbers");
          }
        } catch {
          onToastRef.current("Could not reach the SMS provider");
        } finally {
          setLoading(false);
        }
      })();
      return;
    }
    if (smsNums.length && bridge?.fetchBurnerSmsMessagesAsync) {
      const current = smsNums[idx % smsNums.length];
      bridge.fetchBurnerSmsMessagesAsync(current.digits);
      return;
    }
    if (bridge?.fetchBurnerSmsNumbersAsync) {
      bridge.fetchBurnerSmsNumbersAsync();
      return;
    }
    window.setTimeout(() => {
      const nums = smsNums.length ? smsNums : nativeGetBurnerSmsNumbers();
      if (nums.length) {
        setSmsNums(nums);
        const current = nums[idx % nums.length];
        setSmsMsgs(nativeGetBurnerSmsMessages(current.digits));
      } else {
        onToast("Could not load SMS numbers");
      }
      setLoading(false);
    }, 20);
  };

  const burnerTabRef = useRef(burnerTab);
  burnerTabRef.current = burnerTab;
  const smsIdxRef = useRef(smsIdx);
  smsIdxRef.current = smsIdx;

  useEffect(() => {
    // Windows reads the same providers directly over HTTPS (CSP allows them).
    if (!isAndroidRuntime() && !isWindowsRuntime()) return;
    if (burnerTab === "email" && !emailAddr) loadEmail(false);
    if (burnerTab === "sms" && smsNums.length === 0) loadSms(0);
    const timer = window.setInterval(() => {
      if (burnerTabRef.current === "email") loadEmail(false);
      else loadSms(smsIdxRef.current);
    }, 6000);
    return () => window.clearInterval(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [burnerTab]);

  const activeNumber = smsNums.length ? smsNums[smsIdx % smsNums.length] : null;

  return (
    <div className="page">
      <Header title="Burner" subtitle="Disposable email & SMS OTPs." />

      <div className="seg-tabs">
        <button
          type="button"
          className={`seg-tab ${burnerTab === "email" ? "active" : ""}`}
          onClick={() => setBurnerTab("email")}
        >
          Burner Email
        </button>
        <button
          type="button"
          className={`seg-tab ${burnerTab === "sms" ? "active" : ""}`}
          onClick={() => setBurnerTab("sms")}
        >
          Burner Number
        </button>
      </div>

      {burnerTab === "email" ? (
        <section className="panel burner-panel">
          <div className="burner-head">
            <div className="burner-address">
              {loading && !emailAddr ? "Generating inbox..." : emailAddr || "Tap New Address to generate"}
            </div>
            <div className="burner-actions">
              {emailAddr && (
                <button type="button" className="pill-btn" onClick={() => copyText(emailAddr, "Email")}>
                  Copy Email
                </button>
              )}
              <button type="button" className="pill-btn" onClick={() => loadEmail(false)}>
                {loading ? <span className="omit-spinner" /> : "Refresh"}
              </button>
              <button type="button" className="pill-btn" onClick={() => loadEmail(true)}>
                New Address
              </button>
            </div>
          </div>
          {emailMsgs.length === 0 && <div className="burner-empty">No emails yet. Tap Refresh to check inbox.</div>}
          {emailMsgs.map((m) => (
            <div key={m.id || `${m.subject}-${m.from}`} className="burner-item">
              <strong>{m.subject || m.from}</strong>
              <span>{m.intro}</span>
              {m.otp && (
                <button type="button" className="pill-btn" onClick={() => copyText(m.otp, "OTP")}>
                  Copy OTP: {m.otp}
                </button>
              )}
            </div>
          ))}
        </section>
      ) : (
        <section className="panel burner-panel">
          <div className="burner-head">
            <div className="burner-address">
              {loading && smsNums.length === 0
                ? "Loading live number..."
                : activeNumber?.formatted || "Tap Refresh to load"}
            </div>
            <div className="burner-actions">
              {activeNumber && (
                <>
                  <button
                    type="button"
                    className="pill-btn"
                    onClick={() => copyText(activeNumber.formatted, "Number")}
                  >
                    Copy Number
                  </button>
                  <button
                    type="button"
                    className="pill-btn"
                    onClick={() => {
                      const next = smsIdx + 1;
                      setSmsIdx(next);
                      loadSms(next);
                    }}
                  >
                    Switch Number
                  </button>
                </>
              )}
              <button type="button" className="pill-btn" onClick={() => loadSms(smsIdx)}>
                {loading ? <span className="omit-spinner" /> : "Refresh"}
              </button>
            </div>
          </div>
          <div className="burner-note">Public inbox — use only for throwaway sign-ups, never banking.</div>
          {smsMsgs.length === 0 && <div className="burner-empty">No SMS yet. Tap Refresh to check.</div>}
          {smsMsgs.map((m, i) => (
            <div key={`${m.from}-${m.time}-${i}`} className="burner-item">
              <strong>
                {m.from} · {m.time}
              </strong>
              <span>{m.text}</span>
              {m.otp && (
                <button type="button" className="pill-btn" onClick={() => copyText(m.otp, "Code")}>
                  Copy Code: {m.otp}
                </button>
              )}
            </div>
          ))}
        </section>
      )}
    </div>
  );
}

function TamperBanner() {
  const [warn, setWarn] = useState<string>(() => nativeGetTamperWarning());
  if (!warn) return null;
  return (
    <div className="tamper-banner" role="alert">
      <span>{warn}</span>
      <button type="button" onClick={() => setWarn("")} aria-label="Dismiss warning">
        ✕
      </button>
    </div>
  );
}

export { openExternalUrl };
