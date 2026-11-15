import { useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import {
  formatCompactCount,
  isAndroidRuntime,
  readInitialApps,
  readInitialState,
  type AppMode,
  type DnsLogItem,
  type InstalledAppItem,
  type RecentBlockedItem,
  type ShieldState,
  type VellumStateSnapshot,
} from "./bridge";

type Tab = "home" | "blocking" | "apps" | "activity";

const iconPaths: Record<string, ReactNode> = {
  shield: (
    <path d="M12 3 5.5 5.7v5.5c0 4.3 2.7 7.8 6.5 9.3 3.8-1.5 6.5-5 6.5-9.3V5.7L12 3Zm0 3.1 3.7 1.5v3.6c0 2.8-1.5 5.2-3.7 6.4V6.1Z" />
  ),
  sliders: <path d="M4 7h9m4 0h3M4 17h3m4 0h9M15 5v4M9 15v4" />,
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
  game: (
    <path d="M8 9h8a5 5 0 0 1 4.6 7l-.6 1.4a2.5 2.5 0 0 1-4.1.8L14.7 17H9.3l-1.2 1.2a2.5 2.5 0 0 1-4.1-.8L3.4 16A5 5 0 0 1 8 9Zm-1 3v3m-1.5-1.5h3M16 13h.01M18 15h.01" />
  ),
  warning: (
    <>
      <path d="m12 3 9 17H3L12 3Z" />
      <path d="M12 9v4m0 3h.01" />
    </>
  ),
  arrow: <path d="m9 6 6 6-6 6" />,
  down: <path d="m6 9 6 6 6-6" />,
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
  wifi: (
    <path d="M3.5 9a13 13 0 0 1 17 0M6.5 12.5a8.5 8.5 0 0 1 11 0M9.5 16a4 4 0 0 1 5 0M12 19h.01" />
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
      className={`toggle ${on ? "on" : ""}`}
      onClick={onChange}
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
        <button className="row-copy" onClick={() => setOpen(!open)}>
          <span>
            <b>{title}</b>
            {note && <small>{note}</small>}
          </span>
          <Icon name="down" size={17} />
        </button>
        <Toggle on={on} onChange={onChange} label={title} />
      </div>
      {open && <Detail benefit={benefit} consequence={consequence} />}
    </div>
  );
}

function Modal({ children, onClose }: { children: ReactNode; onClose: () => void }) {
  return (
    <div className="modal-wrap" onMouseDown={onClose}>
      <div className="modal" onMouseDown={(event) => event.stopPropagation()}>
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
          src={`https://app.vellum.local/app-icon/${encodeURIComponent(app.pkg)}`}
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
  [
    "Balanced",
    "Everyday protection",
    "Blocks 95% of annoyances.",
    "Reward ads need Pause; shopping links work.",
  ],
  [
    "Strict",
    "Block everything",
    "Maximum privacy.",
    "May break shopping, cashback, and social login links.",
  ],
  [
    "Custom",
    "Choose categories",
    "Protection shaped by you.",
    "Results depend on the switches below.",
  ],
] as const;

const categoryData = [
  [
    "Game & In-App Ads",
    true,
    "Removes banners, popups, and video ad preloads.",
    "Rewarded ads will need Pause 60s.",
  ],
  [
    "Web Banners & Popups",
    true,
    "Stops banners, fake buttons, and redirects.",
    "Some ad-wall sites may show blank spaces.",
  ],
  [
    "Cross-App Trackers",
    true,
    "Stops analytics SDKs profiling app use.",
    "Some tracked email and referral links may fail.",
  ],
  [
    "Malware & Scam Shield",
    true,
    "Blocks known phishing, scam, and malware hosts.",
    "Rare new file mirrors may be blocked.",
  ],
  [
    "Phone Brand Telemetry",
    true,
    "Silences phone maker analytics and ad-ID pings.",
    "Built-in diagnostics may not sync.",
  ],
  [
    "Betting & Casino Popups",
    true,
    "Stops aggressive betting and casino networks.",
    "Betting and fantasy sports sites are blocked.",
  ],
  [
    "Shopping & Affiliate Links",
    false,
    "Stops affiliate purchase tracking.",
    "Sponsored results and cashback links can break.",
  ],
  [
    "Social Media Trackers",
    false,
    "Stops social pixels on websites you visit.",
    "Social login and embedded posts may fail.",
  ],
] as const;

const engineData = [
  [
    "CNAME Uncloaking",
    true,
    "Finds trackers hidden behind first-party domains.",
    "Rare CDN aliases may need an allow rule.",
  ],
  [
    "Anti-Retry-Storm Cache",
    true,
    "Stops blocked apps repeatedly waking your radio.",
    "Blocks remain cached for five minutes.",
  ],
  [
    "Prevent Browser DNS Bypass",
    true,
    "Keeps browser requests inside Omit.",
    "Secure DNS may need disabling in some browsers.",
  ],
  [
    "Hotel/Cafe Wi-Fi Helper",
    true,
    "Helps captive portal login pages open.",
    "The portal lookup may briefly be unencrypted.",
  ],
  [
    "Start on Boot",
    true,
    "Restores protection automatically after restart.",
    "Omit starts without manual confirmation.",
  ],
] as const;

const dnsData = [
  [
    "Google DNS",
    "Reliable default",
    "Unlocks ISP-blocked sites with reliable encrypted DNS coverage.",
    "Uses Google Public DNS policy.",
  ],
  [
    "Cloudflare",
    "Fastest nearby",
    "Low latency and privacy-oriented policy.",
    "May route some CDN content differently.",
  ],
  [
    "Quad9",
    "Malware-aware",
    "Adds an independent malware protection layer.",
    "Can be slightly slower in some locations.",
  ],
  [
    "AdGuard Unfiltered",
    "Independent",
    "Leaves all filtering decisions to Omit.",
    "May have higher latency in some regions.",
  ],
  [
    "Custom DoH URL",
    "Advanced",
    "Use your own trusted encrypted resolver.",
    "An invalid server can stop sites loading.",
  ],
] as const;

function computeIntensityName(cats: boolean[]): VellumStateSnapshot["intensity"] {
  const light = [true, false, false, true, false, false, false, false];
  const balanced = [true, true, true, true, true, true, false, false];
  const strict = [true, true, true, true, true, true, true, true];
  if (cats.every((v, i) => v === light[i])) return "Light";
  if (cats.every((v, i) => v === balanced[i])) return "Balanced";
  if (cats.every((v, i) => v === strict[i])) return "Strict";
  return "Custom";
}

export default function App() {
  const [tab, setTab] = useState<Tab>("home");
  const [state, setState] = useState<VellumStateSnapshot>(() => readInitialState());
  const [apps, setApps] = useState<InstalledAppItem[]>(() => readInitialApps());

  const [dnsOpen, setDnsOpen] = useState(false);
  const [customDnsOpen, setCustomDnsOpen] = useState(false);
  const [customDnsDraft, setCustomDnsDraft] = useState(state.customDohUrl);

  const [rulesOpen, setRulesOpen] = useState(false);
  const [domainInput, setDomainInput] = useState("");

  const [urlsOpen, setUrlsOpen] = useState(false);
  const [urlInput, setUrlInput] = useState("");

  const [gameDetails, setGameDetails] = useState(false);
  const [selectedAppPkg, setSelectedAppPkg] = useState<string | null>(null);
  const [appSearch, setAppSearch] = useState("");
  const [appFilter, setAppFilter] = useState("User Apps");

  const [frozen, setFrozen] = useState(false);
  const [frozenLogs, setFrozenLogs] = useState<DnsLogItem[]>([]);
  const [logSearch, setLogSearch] = useState("");
  const [logFilter, setLogFilter] = useState("All");
  const [selectedLog, setSelectedLog] = useState<DnsLogItem | null>(null);
  const [toast, setToast] = useState("");

  const categoriesRef = useRef<HTMLElement | null>(null);

  // Sync theme attribute on <html>
  useEffect(() => {
    document.documentElement.dataset.theme = state.dark ? "dark" : "light";
  }, [state.dark]);

  // Register live callbacks from Android native engine + Back button handler
  useEffect(() => {
    window.__vellumOnNativeState = (nextState: VellumStateSnapshot) => {
      setState(nextState);
    };
    window.__vellumOnNativeApps = (nextApps: InstalledAppItem[]) => {
      setApps(nextApps);
    };
    window.__vellumOnToast = (msg: string) => {
      setToast(msg);
    };
    return () => {
      delete window.__vellumOnNativeState;
      delete window.__vellumOnNativeApps;
      delete window.__vellumOnToast;
    };
  }, []);

  useEffect(() => {
    window.__vellumHandleBack = () => {
      if (selectedLog) {
        setSelectedLog(null);
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
      if (tab !== "home") {
        setTab("home");
        return true;
      }
      return false;
    };
    return () => {
      delete window.__vellumHandleBack;
    };
  }, [selectedLog, selectedAppPkg, customDnsOpen, dnsOpen, rulesOpen, urlsOpen, tab]);

  // Standalone browser countdown fallback (on Android, native service drives countdown)
  useEffect(() => {
    if (isAndroidRuntime() || state.shield !== "paused") return;
    const timer = window.setInterval(() => {
      setState((cur) => {
        if (cur.countdown <= 1) {
          return { ...cur, shield: "protected", countdown: 60 };
        }
        return { ...cur, countdown: cur.countdown - 1 };
      });
    }, 1000);
    return () => clearInterval(timer);
  }, [state.shield]);

  useEffect(() => {
    if (!toast) return;
    const timer = window.setTimeout(() => setToast(""), 2300);
    return () => clearTimeout(timer);
  }, [toast]);

  // Load installed apps whenever user switches to Apps tab on Android
  useEffect(() => {
    if (tab === "apps" && window.VellumAndroid) {
      try {
        const fresh = JSON.parse(window.VellumAndroid.getInstalledAppsJson()) as InstalledAppItem[];
        if (fresh.length > 0) setApps(fresh);
      } catch {
        // ignore
      }
    }
  }, [tab]);

  const toggleDark = () => {
    const next = !state.dark;
    setState((cur) => ({ ...cur, dark: next }));
    window.VellumAndroid?.setDarkMode(next);
  };

  const handleShieldAction = (target: ShieldState) => {
    if (window.VellumAndroid) {
      if (target === "paused") {
        window.VellumAndroid.pause60s();
      } else if (target === "protected" && state.shield === "paused") {
        window.VellumAndroid.resumeShield();
      } else if (target === "protected" && state.shield === "game") {
        window.VellumAndroid.setOfflineGameMode(false);
      } else {
        window.VellumAndroid.toggleShield();
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
    if (window.VellumAndroid) {
      window.VellumAndroid.selectIntensity(index);
      return;
    }
    const nextCats =
      index === 0
        ? [true, false, false, true, false, false, false, false]
        : index === 1
          ? [true, true, true, true, true, true, false, false]
          : Array<boolean>(8).fill(true);
    setState((cur) => ({
      ...cur,
      intensity: name as VellumStateSnapshot["intensity"],
      categories: nextCats,
    }));
  };

  const handleToggleCategory = (index: number) => {
    if (window.VellumAndroid) {
      window.VellumAndroid.toggleCategory(index);
      return;
    }
    setState((cur) => {
      const nextCats = cur.categories.map((v, i) => (i === index ? !v : v));
      return {
        ...cur,
        categories: nextCats,
        intensity: computeIntensityName(nextCats),
      };
    });
  };

  const handleToggleEngine = (index: number) => {
    if (window.VellumAndroid) {
      window.VellumAndroid.toggleEngine(index);
      return;
    }
    setState((cur) => ({
      ...cur,
      engine: cur.engine.map((v, i) => (i === index ? !v : v)),
    }));
  };

  const handleSelectDns = (index: number) => {
    if (index === 4) {
      setCustomDnsDraft(state.customDohUrl || "https://");
      setCustomDnsOpen(true);
      return;
    }
    if (window.VellumAndroid) {
      window.VellumAndroid.setUpstreamDns(index, "");
    } else {
      setState((cur) => ({ ...cur, dns: index }));
    }
    setDnsOpen(false);
    setToast(`${dnsData[index][0]} active`);
  };

  const handleSaveCustomDoh = () => {
    const trimmed = customDnsDraft.trim();
    if (!trimmed.startsWith("https://") || trimmed.length < 12) {
      setToast("Enter a valid https:// DoH URL");
      return;
    }
    if (window.VellumAndroid) {
      window.VellumAndroid.setUpstreamDns(4, trimmed);
    } else {
      setState((cur) => ({ ...cur, dns: 4, customDohUrl: trimmed }));
    }
    setCustomDnsOpen(false);
    setDnsOpen(false);
    setToast("Custom DNS saved");
  };

  const normalizeDomain = (raw: string): string => {
    let s = raw.trim().toLowerCase();
    if (s.startsWith("https://")) s = s.slice(8);
    if (s.startsWith("http://")) s = s.slice(7);
    const slash = s.indexOf("/");
    if (slash >= 0) s = s.slice(0, slash);
    return s.replace(/^\.+|\.+$/g, "").trim();
  };

  const handleAddAllow = (rawDomain: string) => {
    const clean = normalizeDomain(rawDomain);
    if (!clean || !clean.includes(".")) {
      setToast("Enter a valid domain (e.g. example.com)");
      return;
    }
    if (window.VellumAndroid) {
      window.VellumAndroid.addAllowDomain(clean);
    } else {
      setState((cur) => ({
        ...cur,
        allowDomains: Array.from(new Set([...cur.allowDomains, clean])),
        blockDomains: cur.blockDomains.filter((d) => d !== clean),
        recentBlocked: cur.recentBlocked.filter((r) => r.domain !== clean),
      }));
    }
    setDomainInput("");
    setToast(`${clean} allowed`);
  };

  const handleAddBlock = (rawDomain: string) => {
    const clean = normalizeDomain(rawDomain);
    if (!clean || !clean.includes(".")) {
      setToast("Enter a valid domain (e.g. ads.example.com)");
      return;
    }
    if (window.VellumAndroid) {
      window.VellumAndroid.addBlockDomain(clean);
    } else {
      setState((cur) => ({
        ...cur,
        blockDomains: Array.from(new Set([...cur.blockDomains, clean])),
        allowDomains: cur.allowDomains.filter((d) => d !== clean),
      }));
    }
    setDomainInput("");
    setToast(`${clean} blocked`);
  };

  const handleRemoveAllow = (domain: string) => {
    if (window.VellumAndroid) {
      window.VellumAndroid.removeAllowDomain(domain);
    } else {
      setState((cur) => ({
        ...cur,
        allowDomains: cur.allowDomains.filter((d) => d !== domain),
      }));
    }
  };

  const handleRemoveBlock = (domain: string) => {
    if (window.VellumAndroid) {
      window.VellumAndroid.removeBlockDomain(domain);
    } else {
      setState((cur) => ({
        ...cur,
        blockDomains: cur.blockDomains.filter((d) => d !== domain),
      }));
    }
  };

  const handleAddUrl = () => {
    const trimmed = urlInput.trim();
    if (!trimmed.startsWith("https://") && !trimmed.startsWith("http://")) {
      setToast("Enter a valid https:// blocklist URL");
      return;
    }
    if (window.VellumAndroid) {
      window.VellumAndroid.addCustomListUrl(trimmed);
    } else {
      setState((cur) => ({
        ...cur,
        customUrls: Array.from(new Set([...cur.customUrls, trimmed])),
      }));
    }
    setUrlInput("");
    setToast("Blocklist URL added");
  };

  const handleRemoveUrl = (url: string) => {
    if (window.VellumAndroid) {
      window.VellumAndroid.removeCustomListUrl(url);
    } else {
      setState((cur) => ({
        ...cur,
        customUrls: cur.customUrls.filter((u) => u !== url),
      }));
    }
  };

  const handleSyncLists = () => {
    if (state.syncing) return;
    if (window.VellumAndroid) {
      window.VellumAndroid.syncLists();
      return;
    }
    setToast("Lists are up to date");
  };

  const handleToggleGameMode = () => {
    const next = !state.gameMode;
    if (window.VellumAndroid) {
      const ok = window.VellumAndroid.setOfflineGameMode(next);
      if (!ok && next) {
        setToast("Mark at least one app below as 'Offline Lock' first");
      }
      return;
    }
    const gameCnt = apps.filter((a) => a.mode === "Offline Lock").length;
    if (next && gameCnt === 0) {
      setToast("Mark at least one app below as 'Offline Lock' first");
      return;
    }
    setState((cur) => ({
      ...cur,
      gameMode: next,
      shield: next ? "game" : "protected",
    }));
  };

  const handleSetAppMode = (app: InstalledAppItem, mode: AppMode) => {
    if (window.VellumAndroid) {
      window.VellumAndroid.setAppMode(app.pkg, mode);
    }
    setApps((cur) => cur.map((a) => (a.pkg === app.pkg ? { ...a, mode } : a)));
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
    if (window.VellumAndroid) {
      window.VellumAndroid.setLogging(next);
    } else {
      setState((cur) => ({ ...cur, logging: next, logs: next ? cur.logs : [] }));
    }
  };

  const handleClearLogs = () => {
    setFrozenLogs([]);
    if (window.VellumAndroid) {
      window.VellumAndroid.clearLogs();
    } else {
      setState((cur) => ({ ...cur, logs: [] }));
    }
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
      const searchMatch =
        !q || log.domain.toLowerCase().includes(q) || log.category.toLowerCase().includes(q);
      if (logFilter === "Blocked") return searchMatch && log.status === "Blocked";
      if (logFilter === "Allowed") return searchMatch && log.status === "Allowed";
      return searchMatch;
    });
  }, [activeLogSource, logSearch, logFilter]);

  const nav = [
    ["home", "Home", "shield"],
    ["blocking", "Blocking", "sliders"],
    ["apps", "Apps", "apps"],
    ["activity", "Activity", "pulse"],
  ] as const;

  const isSelectedLogCustomRuled =
    selectedLog !== null &&
    (state.allowDomains.includes(selectedLog.domain) ||
      state.blockDomains.includes(selectedLog.domain));

  return (
    <div className="app">
      <header className="topbar">
        <button className="brand" onClick={() => setTab("home")}>
          <span>
            <Icon name="shield" size={20} />
          </span>
          <b>Omit</b>
        </button>
        <button
          className="icon-btn theme-toggle"
          onClick={toggleDark}
          aria-label="Toggle appearance"
        >
          <Icon name={state.dark ? "sun" : "moon"} />
        </button>
      </header>

      <main>
        {tab === "home" && (
          <Home
            shield={state.shield}
            countdown={state.countdown}
            intensity={state.intensity}
            offlineGameCount={state.offlineGameCount}
            blockedCount={state.blockedCount}
            totalQueries={state.totalQueries}
            activeRules={state.activeRules}
            privateDnsHost={state.privateDnsHost}
            recentBlocked={state.recentBlocked}
            onShield={handleShieldAction}
            onIntensity={() => setTab("blocking")}
            onAllowDomain={handleAddAllow}
            onOpenPrivateDns={() => {
              if (window.VellumAndroid) {
                window.VellumAndroid.openPrivateDnsSettings();
              } else {
                setToast("Opening network settings");
              }
            }}
          />
        )}

        {tab === "blocking" && (
          <div className="page">
            <Header title="Blocking" subtitle="Choose how much Vellum should block." />

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
                {categoryData.map((item, index) => (
                  <ExpandRow
                    key={item[0]}
                    title={item[0]}
                    on={Boolean(state.categories[index])}
                    onChange={() => handleToggleCategory(index)}
                    benefit={item[2]}
                    consequence={item[3]}
                  />
                ))}
              </div>
            </section>

            <section className="section">
              <div className="section-title">
                <div>
                  <h2>DNS & engine</h2>
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
                {engineData.map((item, index) => (
                  <ExpandRow
                    key={item[0]}
                    title={item[0]}
                    on={Boolean(state.engine[index])}
                    onChange={() => handleToggleEngine(index)}
                    benefit={item[2]}
                    consequence={item[3]}
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
                <button className="select-row" onClick={() => setUrlsOpen(true)}>
                  <span className="row-icon">
                    <Icon name="plus" />
                  </span>
                  <b>Custom blocklist URLs</b>
                  <Icon name="arrow" />
                </button>
                <button
                  className="primary-btn"
                  disabled={state.syncing}
                  onClick={handleSyncLists}
                >
                  {state.syncing ? "Syncing lists..." : "Sync lists"}
                </button>
              </div>
            </section>
          </div>
        )}

        {tab === "apps" && (
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
                      ? `${state.offlineGameCount} ${state.offlineGameCount === 1 ? "game is" : "games are"} offline`
                      : state.offlineGameCount > 0
                        ? `Play with zero ads · ${state.offlineGameCount} selected`
                        : "Play with zero ads"}
                  </p>
                </span>
                <Toggle
                  on={state.gameMode}
                  onChange={handleToggleGameMode}
                  label="Offline Lock"
                />
              </div>
              <button className="learn-row" onClick={() => setGameDetails(!gameDetails)}>
                How it works <Icon name="down" size={16} />
              </button>
              {gameDetails && (
                <Detail
                  benefit="Selected games get no internet, ads, or tracking."
                  consequence="Multiplayer, cloud saves, and rewarded ads will not work. Global DNS filtering pauses until Game Mode is turned off."
                />
              )}
            </section>

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
                    <button
                      key={app.pkg}
                      className="app-row"
                      onClick={() => setSelectedAppPkg(app.pkg)}
                    >
                      <AppBadge app={app} />
                      <span className="app-copy">
                        <b>{app.name}</b>
                        <small>{app.pkg}</small>
                      </span>
                      <span className={`mode ${app.mode.toLowerCase().replace(" ", "-")}`}>
                        {app.mode}
                      </span>
                      <Icon name="arrow" size={17} />
                    </button>
                  ))
                ) : (
                  <div className="empty">
                    <Icon name="apps" />
                    <p>No matching apps</p>
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
                <button
                  className={`small-btn ${frozen ? "active" : ""}`}
                  onClick={handleToggleFreeze}
                >
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

      <nav className="bottom-nav">
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
                  <small>
                    {index === 4 && state.dns === 4 ? state.customDohUrl : item[1]}
                  </small>
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
                ["Bypassed", "wifi", "Direct internet", "Vellum skips & hides VPN from this app."],
                ["Offline Lock", "game", "No internet or ads", "Online features will not work while Game Mode is on."],
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
                if (window.VellumAndroid) {
                  window.VellumAndroid.removeCustomRuleForDomain(selectedLog.domain);
                } else {
                  handleRemoveAllow(selectedLog.domain);
                  handleRemoveBlock(selectedLog.domain);
                }
                setToast(`Custom rule removed for ${selectedLog.domain}`);
                setSelectedLog(null);
              }}
            >
              Remove custom rule
            </button>
          )}
        </Modal>
      )}

      {toast && (
        <div className="toast">
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
      <button
        className="info-toggle"
        onClick={() => setOpen(!open)}
        aria-label={`About ${item[0]}`}
      >
        <Icon name="down" size={16} />
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
  onShield,
  onIntensity,
  onAllowDomain,
  onOpenPrivateDns,
}: {
  shield: ShieldState;
  countdown: number;
  intensity: string;
  offlineGameCount: number;
  blockedCount: number;
  totalQueries: number;
  activeRules: number;
  privateDnsHost: string;
  recentBlocked: RecentBlockedItem[];
  onShield: (state: ShieldState) => void;
  onIntensity: () => void;
  onAllowDomain: (domain: string) => void;
  onOpenPrivateDns: () => void;
}) {
  const copy = {
    off: ["Protection is off", "Tap the shield to turn on"],
    protected: ["You’re protected", "Ads and trackers are blocked"],
    paused: ["Protection paused", `${countdown}s until Vellum resumes`],
    game: [
      "Offline Lock",
      `${offlineGameCount} ${offlineGameCount === 1 ? "game has" : "games have"} no internet`,
    ],
  }[shield];

  const toggleShield = () => {
    if (shield === "off") onShield("protected");
    else onShield("off");
  };

  return (
    <div className="page home-page">
      <Header title="Home" />

      <section className={`shield-card ${shield}`}>
        <button className="shield-button" onClick={toggleShield} aria-label="Toggle Vellum">
          <span className="shield-ring">
            <span>
              <Icon
                name={shield === "game" ? "game" : shield === "paused" ? "pause" : "shield"}
                size={47}
              />
            </span>
          </span>
        </button>
        <h2>{copy[0]}</h2>
        <p>{copy[1]}</p>
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
              Turn off Game Mode
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
            <p>Android Private DNS ({privateDnsHost}) may bypass Vellum.</p>
          </div>
          <button onClick={onOpenPrivateDns}>Open Settings</button>
        </section>
      )}

      <section className="recent-section">
        <div className="section-title">
          <h2>Recently blocked</h2>
        </div>
        <div className="recent-list">
          {recentBlocked.length > 0 ? (
            recentBlocked.map((item) => (
              <div key={item.domain}>
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
            <div className="recent-empty">
              Blocked domains appear here for instant 1-tap unblocking
            </div>
          )}
        </div>
      </section>
    </div>
  );
}
