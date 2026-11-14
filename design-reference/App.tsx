import { useEffect, useMemo, useState, type ReactNode } from "react";

type Tab = "home" | "blocking" | "apps" | "activity";
type ShieldState = "off" | "protected" | "paused" | "game";
type AppMode = "Protected" | "Bypassed" | "Offline Game";
type LogStatus = "Blocked" | "Allowed";

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
    "Keeps browser requests inside Vellum.",
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
    false,
    "Restores protection after restart.",
    "Vellum starts without manual confirmation.",
  ],
] as const;

const dnsData = [
  [
    "Google DNS",
    "Reliable default",
    "Broad, reliable encrypted DNS coverage.",
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
    "Leaves all filtering decisions to Vellum.",
    "May have higher latency in some regions.",
  ],
  [
    "Custom DoH URL",
    "Advanced",
    "Use your own trusted encrypted resolver.",
    "An invalid server can stop sites loading.",
  ],
] as const;

const installedApps = [
  ["Arcade Drift", "com.neon.arcadedrift", "AD", "#7768d9"],
  ["Chrome", "com.android.chrome", "C", "#3f9875"],
  ["HDFC Bank", "com.snapwork.hdfc", "H", "#477bc6"],
  ["Pocket Chess", "com.twofingers.chess", "PC", "#cb8a39"],
  ["Spotify", "com.spotify.music", "S", "#419b64"],
  ["YouTube", "com.google.android.youtube", "YT", "#db5a51"],
] as const;

const logSeed: { domain: string; time: string; status: LogStatus; category: string }[] = [
  { domain: "app-measurement.com", time: "Now", status: "Blocked", category: "Cross-App Tracker" },
  { domain: "api.spotify.com", time: "4s", status: "Allowed", category: "Google DNS" },
  { domain: "googleadservices.com", time: "12s", status: "Blocked", category: "Shopping Link" },
  { domain: "accounts.google.com", time: "21s", status: "Allowed", category: "Google DNS" },
  { domain: "ads.arcadedrift.io", time: "34s", status: "Blocked", category: "Game Ads" },
  { domain: "images.spotifycdn.com", time: "48s", status: "Allowed", category: "Google DNS" },
];

export default function App() {
  const [tab, setTab] = useState<Tab>("home");
  const [dark, setDark] = useState(false);
  const [shield, setShield] = useState<ShieldState>("protected");
  const [countdown, setCountdown] = useState(60);
  const [intensity, setIntensity] = useState("Balanced");
  const [categories, setCategories] = useState(categoryData.map((item) => item[1]));
  const [engine, setEngine] = useState(engineData.map((item) => item[1]));
  const [dns, setDns] = useState(0);
  const [dnsOpen, setDnsOpen] = useState(false);
  const [rulesOpen, setRulesOpen] = useState(false);
  const [customDnsOpen, setCustomDnsOpen] = useState(false);
  const [gameMode, setGameMode] = useState(false);
  const [gameDetails, setGameDetails] = useState(false);
  const [appModes, setAppModes] = useState<AppMode[]>([
    "Offline Game",
    "Protected",
    "Bypassed",
    "Offline Game",
    "Protected",
    "Protected",
  ]);
  const [selectedApp, setSelectedApp] = useState<number | null>(null);
  const [appSearch, setAppSearch] = useState("");
  const [appFilter, setAppFilter] = useState("User Apps");
  const [logging, setLogging] = useState(true);
  const [frozen, setFrozen] = useState(false);
  const [logs, setLogs] = useState(logSeed);
  const [logSearch, setLogSearch] = useState("");
  const [logFilter, setLogFilter] = useState("All");
  const [selectedLog, setSelectedLog] = useState<(typeof logSeed)[number] | null>(null);
  const [toast, setToast] = useState("");

  useEffect(() => {
    document.documentElement.dataset.theme = dark ? "dark" : "light";
  }, [dark]);

  useEffect(() => {
    if (shield !== "paused") return;
    const timer = window.setInterval(() => {
      setCountdown((current) => {
        if (current <= 1) {
          setShield("protected");
          return 60;
        }
        return current - 1;
      });
    }, 1000);
    return () => clearInterval(timer);
  }, [shield]);

  useEffect(() => {
    if (!toast) return;
    const timer = window.setTimeout(() => setToast(""), 2200);
    return () => clearTimeout(timer);
  }, [toast]);

  const chooseIntensity = (name: string, index: number) => {
    setIntensity(name);
    if (index === 0) setCategories([true, false, false, true, false, false, false, false]);
    if (index === 1) setCategories([true, true, true, true, true, true, false, false]);
    if (index === 2) setCategories(Array(8).fill(true));
  };

  const filteredApps = useMemo(
    () =>
      installedApps
        .map((app, index) => ({ app, index }))
        .filter(({ app, index }) => {
          const match = `${app[0]} ${app[1]}`.toLowerCase().includes(appSearch.toLowerCase());
          if (appFilter === "Bypassed") return match && appModes[index] === "Bypassed";
          if (appFilter === "Offline Games") return match && appModes[index] === "Offline Game";
          return match;
        }),
    [appSearch, appFilter, appModes],
  );

  const filteredLogs = logs.filter((log) => {
    const searchMatch = log.domain.toLowerCase().includes(logSearch.toLowerCase());
    if (logFilter === "Blocked") return searchMatch && log.status === "Blocked";
    if (logFilter === "Allowed") return searchMatch && log.status === "Allowed";
    return searchMatch;
  });

  const nav = [
    ["home", "Home", "shield"],
    ["blocking", "Blocking", "sliders"],
    ["apps", "Apps", "apps"],
    ["activity", "Activity", "pulse"],
  ] as const;

  return (
    <div className="app">
      <header className="topbar">
        <button className="brand" onClick={() => setTab("home")}>
          <span>
            <Icon name="shield" size={20} />
          </span>
          <b>Vellum</b>
        </button>
        <button
          className="icon-btn theme-toggle"
          onClick={() => setDark(!dark)}
          aria-label="Toggle appearance"
        >
          <Icon name={dark ? "sun" : "moon"} />
        </button>
      </header>

      <main>
        {tab === "home" && (
          <Home
            shield={shield}
            countdown={countdown}
            intensity={intensity}
            onShield={setShield}
            onIntensity={() => setTab("blocking")}
            onToast={setToast}
          />
        )}

        {tab === "blocking" && (
          <div className="page">
            <Header title="Blocking" subtitle="Choose how much Vellum should block." />

            <section className="section">
              <div className="section-title">
                <h2>Intensity</h2>
                <span>{intensity}</span>
              </div>
              <div className="intensity-list">
                {intensityData.map((item, index) => (
                  <IntensityOption
                    key={item[0]}
                    item={item}
                    selected={intensity === item[0]}
                    onSelect={() => chooseIntensity(item[0], index)}
                  />
                ))}
              </div>
            </section>

            <section className="section">
              <div className="section-title">
                <div>
                  <h2>Categories</h2>
                  <p>Tap a row for details</p>
                </div>
                <span>{categories.filter(Boolean).length} on</span>
              </div>
              <div className="row-list">
                {categoryData.map((item, index) => (
                  <ExpandRow
                    key={item[0]}
                    title={item[0]}
                    on={categories[index]}
                    onChange={() => {
                      setCategories((current) =>
                        current.map((value, itemIndex) => (itemIndex === index ? !value : value)),
                      );
                      setIntensity("Custom");
                    }}
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
                  <b>{dnsData[dns][0]}</b>
                </span>
                <Icon name="arrow" />
              </button>
              <div className="row-list engine-rows">
                {engineData.map((item, index) => (
                  <ExpandRow
                    key={item[0]}
                    title={item[0]}
                    on={engine[index]}
                    onChange={() =>
                      setEngine((current) =>
                        current.map((value, itemIndex) => (itemIndex === index ? !value : value)),
                      )
                    }
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
                  <p>2 allowed · 1 blocked · 2 lists</p>
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
                <button className="select-row" onClick={() => setRulesOpen(true)}>
                  <span className="row-icon">
                    <Icon name="plus" />
                  </span>
                  <b>Custom blocklist URLs</b>
                  <Icon name="arrow" />
                </button>
                <button className="primary-btn" onClick={() => setToast("Lists are up to date")}>
                  Sync lists
                </button>
              </div>
            </section>
          </div>
        )}

        {tab === "apps" && (
          <div className="page">
            <Header title="Apps" subtitle="Choose how each app connects." />

            <section className={`game-card ${gameMode ? "active" : ""}`}>
              <div className="game-head">
                <span className="game-icon">
                  <Icon name="game" />
                </span>
                <span>
                  <h2>Offline Game Mode</h2>
                  <p>{gameMode ? "2 games are offline" : "Play with zero ads"}</p>
                </span>
                <Toggle
                  on={gameMode}
                  onChange={() => {
                    setGameMode(!gameMode);
                    setShield(!gameMode ? "game" : "protected");
                  }}
                  label="Offline Game Mode"
                />
              </div>
              <button className="learn-row" onClick={() => setGameDetails(!gameDetails)}>
                How it works <Icon name="down" size={16} />
              </button>
              {gameDetails && (
                <Detail
                  benefit="Selected games get no internet, ads, or tracking."
                  consequence="Multiplayer, cloud saves, and rewarded ads will not work."
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
                {["User Apps", "Bypassed", "Offline Games", "System Apps"].map((filter) => (
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
                {filteredApps.map(({ app, index }) => (
                  <button key={app[1]} className="app-row" onClick={() => setSelectedApp(index)}>
                    <span className="app-icon" style={{ background: app[3] }}>
                      {app[2]}
                    </span>
                    <span className="app-copy">
                      <b>{app[0]}</b>
                      <small>{app[1]}</small>
                    </span>
                    <span className={`mode ${appModes[index].toLowerCase().replace(" ", "-")}`}>
                      {appModes[index]}
                    </span>
                    <Icon name="arrow" size={17} />
                  </button>
                ))}
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
                  <Toggle on={logging} onChange={() => setLogging(!logging)} label="Live log" />
                  <b>Live log</b>
                </span>
                <button
                  className={`small-btn ${frozen ? "active" : ""}`}
                  onClick={() => setFrozen(!frozen)}
                >
                  <Icon name={frozen ? "play" : "pause"} size={16} />
                  {frozen ? "Resume" : "Freeze"}
                </button>
                <button className="small-btn danger" onClick={() => setLogs([])}>
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
                      key={`${log.domain}-${index}`}
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
                    <p>No activity to show</p>
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
                className={dns === index ? "selected" : ""}
                key={item[0]}
                onClick={() => {
                  setDns(index);
                  if (index === 4) setCustomDnsOpen(true);
                  else setDnsOpen(false);
                }}
              >
                <span className="radio">{dns === index && <i />}</span>
                <span>
                  <b>{item[0]}</b>
                  <small>{item[1]}</small>
                </span>
              </button>
            ))}
          </div>
          <Detail benefit={dnsData[dns][2]} consequence={dnsData[dns][3]} />
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
            <input autoFocus defaultValue="https://" placeholder="https://example.com/dns-query" />
          </label>
          <button
            className="primary-btn"
            onClick={() => {
              setCustomDnsOpen(false);
              setDnsOpen(false);
              setToast("Custom DNS saved");
            }}
          >
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
            <span>Domain or blocklist URL</span>
            <input placeholder="example.com" />
          </label>
          <div className="two-actions">
            <button onClick={() => setToast("Domain allowed")}>Always allow</button>
            <button onClick={() => setToast("Domain blocked")}>Always block</button>
          </div>
          <div className="rule-tags">
            <span>
              links.shop.app{" "}
              <button aria-label="Remove">
                <Icon name="close" size={13} />
              </button>
            </span>
            <span>
              ads.example.net{" "}
              <button aria-label="Remove">
                <Icon name="close" size={13} />
              </button>
            </span>
          </div>
          <button className="primary-btn">Add custom list URL</button>
        </Modal>
      )}

      {selectedApp !== null && (
        <Modal onClose={() => setSelectedApp(null)}>
          <div className="app-modal-head">
            <span className="app-icon" style={{ background: installedApps[selectedApp][3] }}>
              {installedApps[selectedApp][2]}
            </span>
            <span>
              <h2>{installedApps[selectedApp][0]}</h2>
              <p>{installedApps[selectedApp][1]}</p>
            </span>
          </div>
          <div className="mode-list">
            {(
              [
                ["Protected", "shield", "Normal filtering", "Ads and trackers may be blocked."],
                ["Bypassed", "wifi", "Direct internet", "Vellum cannot protect this app."],
                ["Offline Game", "game", "No internet or ads", "Online features will not work."],
              ] as const
            ).map(([mode, icon, benefit, consequence]) => (
              <button
                className={appModes[selectedApp] === mode ? "selected" : ""}
                key={mode}
                onClick={() => {
                  setAppModes((current) =>
                    current.map((value, index) => (index === selectedApp ? mode : value)),
                  );
                  setToast(`${installedApps[selectedApp][0]} set to ${mode}`);
                  setSelectedApp(null);
                }}
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
                <span className="radio">{appModes[selectedApp] === mode && <i />}</span>
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
              setToast(
                selectedLog.status === "Blocked"
                  ? "Domain always allowed"
                  : "Domain always blocked",
              );
              setSelectedLog(null);
            }}
          >
            {selectedLog.status === "Blocked" ? "Always allow" : "Always block"}
          </button>
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
  onShield,
  onIntensity,
  onToast,
}: {
  shield: ShieldState;
  countdown: number;
  intensity: string;
  onShield: (state: ShieldState) => void;
  onIntensity: () => void;
  onToast: (message: string) => void;
}) {
  const copy = {
    off: ["Protection is off", "Tap the shield to turn on"],
    protected: ["You’re protected", "Ads and trackers are blocked"],
    paused: ["Protection paused", `${countdown}s until Vellum resumes`],
    game: ["Offline Game Mode", "2 games have no internet"],
  }[shield];

  const recent = [
    ["googleadservices.com", "Shopping link", "12s"],
    ["app-measurement.com", "Tracker", "38s"],
    ["ads.arcadedrift.io", "Game ad", "1m"],
  ];

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
            <button
              className="secondary-btn"
              onClick={() => {
                onShield("paused");
              }}
            >
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
          <b>1,284</b>
        </div>
        <div>
          <span>Queries</span>
          <b>7,132</b>
        </div>
        <div>
          <span>Active rules</span>
          <b>134.8k</b>
        </div>
      </section>

      <section className="warning-card">
        <span>
          <Icon name="warning" />
        </span>
        <div>
          <b>Private DNS conflict</b>
          <p>Android Private DNS may bypass Vellum.</p>
        </div>
        <button onClick={() => onToast("Opening network settings")}>Open Settings</button>
      </section>

      <section className="recent-section">
        <div className="section-title">
          <h2>Recently blocked</h2>
        </div>
        <div className="recent-list">
          {recent.map((item) => (
            <div key={item[0]}>
              <span className="domain-icon">{item[0][0].toUpperCase()}</span>
              <span>
                <b>{item[0]}</b>
                <small>
                  {item[1]} · {item[2]}
                </small>
              </span>
              <button onClick={() => onToast(`${item[0]} allowed`)}>Allow</button>
            </div>
          ))}
        </div>
      </section>
    </div>
  );
}
