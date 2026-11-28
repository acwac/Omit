export type ShieldState = "off" | "protected" | "paused" | "game";
export type AppMode = "Protected" | "Bypassed" | "Offline Lock";
export type LogStatus = "Blocked" | "Allowed";

export interface RecentBlockedItem {
  domain: string;
  category: string;
  time: string;
}

export interface DnsLogItem {
  id: number;
  domain: string;
  time: string;
  status: LogStatus;
  category: string;
  overridden?: boolean;
}

export interface InstalledAppItem {
  pkg: string;
  name: string;
  initials: string;
  color: string;
  isSystem: boolean;
  mode: AppMode;
  hasIcon?: boolean;
}

export interface OmitStateSnapshot {
  dark: boolean;
  shield: ShieldState;
  countdown: number;
  intensity: "Light" | "Balanced" | "Strict" | "Custom";
  categories: boolean[];
  engine: boolean[];
  dns: number;
  customDohUrl: string;
  gameMode: boolean;
  offlineGameCount: number;
  blockedCount: number;
  totalQueries: number;
  activeRules: number;
  privateDnsHost: string;
  allowDomains: string[];
  blockDomains: string[];
  customUrls: string[];
  logging: boolean;
  syncing: boolean;
  recentBlocked: RecentBlockedItem[];
  logs: DnsLogItem[];
}

export interface NativeBridge {
  getStateJson(): string;
  getInstalledAppsJson(): string;
  setDarkMode(dark: boolean): void;
  toggleShield(): void;
  pause60s(): void;
  resumeShield(): void;
  setOfflineGameMode(enabled: boolean): boolean;
  selectIntensity(index: number): void;
  toggleCategory(index: number): void;
  toggleEngine(index: number): void;
  setUpstreamDns(index: number, customUrl: string): void;
  addAllowDomain(domain: string): boolean;
  removeAllowDomain(domain: string): void;
  addBlockDomain(domain: string): boolean;
  removeBlockDomain(domain: string): void;
  addCustomListUrl(url: string): boolean;
  removeCustomListUrl(url: string): void;
  removeCustomRuleForDomain(domain: string): void;
  syncLists(): void;
  setAppMode(pkg: string, mode: string): void;
  setLogging(enabled: boolean): void;
  clearLogs(): void;
  openPrivateDnsSettings(): void;
  getSpyStatsJson?(): string;
  openExternalUrl?(url: string): void;
  copyToClipboard?(text: string): void;
  getBurnerEmailJson?(forceNew: boolean): string;
  fetchBurnerEmailAsync?(forceNew: boolean): void;
  fetchBurnerSmsNumbersAsync?(): void;
  fetchBurnerSmsMessagesAsync?(digits: string): void;
  getBurnerInboxJson?(): string;
  getBurnerSmsNumbersJson?(): string;
  getBurnerSmsMessagesJson?(digits: string): string;
  setHomeShieldEnabled?(enabled: boolean): boolean;
  getHomeShieldInfoJson?(): string;
  getTamperWarning?(): string;
  applyOtaPatch?(otaUrl: string, expectedSha256: string, patchCode: number): void;
  checkUpdateJson?(): string;
  checkUpdateAsync?(currentVersionCode: number): void;
  getVersionCode?(): number;
  downloadAndInstallUpdate?(apkUrl: string): void;
}

export interface UpdateInfo {
  version: string;
  versionCode: number;
  notes: string;
  apkUrl: string;
  /** Windows installer; the platform field is named differently in version.json. */
  windowsExeUrl: string;
  otaUrl: string;
  otaSha256: string;
  otaCode: number;
}

declare global {
  interface Window {
    /** Always injected by Tauri v2, even when withGlobalTauri is off. */
    __TAURI_INTERNALS__?: {
      invoke?: (cmd: string, args?: Record<string, unknown>, options?: unknown) => Promise<unknown>;
    };
    OmitAndroid?: NativeBridge;
    __omitOnNativeState?: (state: OmitStateSnapshot) => void;
    __omitOnNativeApps?: (apps: InstalledAppItem[]) => void;
    __omitOnToast?: (msg: string) => void;
    __omitOnUpdate?: (info: UpdateInfo) => void;
    __omitHandleBack?: () => boolean;
    __TAURI__?: {
      core?: { invoke?: <T = unknown>(cmd: string, args?: Record<string, unknown>) => Promise<T> };
      [key: string]: unknown;
    };
  }
}

/** The Android WebView bridge, or undefined when running in a browser. */
export function androidBridge(): NativeBridge | undefined {
  if (typeof window === "undefined") return undefined;
  return window.OmitAndroid;
}

export const isAndroidRuntime = (): boolean => !!androidBridge();

type TauriInvoke = <T = unknown>(cmd: string, args?: Record<string, unknown>) => Promise<T>;

function tauriInvoke(): TauriInvoke | undefined {
  if (typeof window === "undefined") return undefined;
  // `__TAURI_INTERNALS__` is always present in a Tauri webview; the
  // `__TAURI__` convenience global only exists when withGlobalTauri is enabled.
  const internals = window.__TAURI_INTERNALS__;
  if (internals && typeof internals.invoke === "function") {
    const invoke = internals.invoke.bind(internals);
    return <T = unknown>(cmd: string, args?: Record<string, unknown>) => invoke(cmd, args) as Promise<T>;
  }
  const invoke = window.__TAURI__?.core?.invoke;
  return typeof invoke === "function" ? invoke : undefined;
}

export const isWindowsRuntime = (): boolean => !!tauriInvoke();

/** True inside any packaged app shell (Android WebView or Windows desktop). */
export const isNativeRuntime = (): boolean => isAndroidRuntime() || isWindowsRuntime();

export const isTauriRuntime = isWindowsRuntime;

const THEME_KEY = "omit-theme";

function readStoredDark(fallback: boolean): boolean {
  try {
    const saved = localStorage.getItem(THEME_KEY);
    if (saved === "light") return false;
    if (saved === "dark") return true;
  } catch {
    /* storage can be unavailable in private webviews */
  }
  return fallback;
}

function storeDark(dark: boolean): void {
  try {
    localStorage.setItem(THEME_KEY, dark ? "dark" : "light");
  } catch {
    /* ignore */
  }
}

const DEFAULT_FALLBACK_STATE: OmitStateSnapshot = {
  dark: readStoredDark(true),
  shield: "protected",
  countdown: 60,
  intensity: "Balanced",
  categories: [true, true, true, true, true, true, false, false],
  engine: [true, true, true, true, true],
  dns: 0,
  customDohUrl: "https://dns.google/dns-query",
  gameMode: false,
  offlineGameCount: 0,
  blockedCount: 0,
  totalQueries: 0,
  activeRules: 0,
  privateDnsHost: "",
  allowDomains: [],
  blockDomains: [],
  customUrls: [],
  logging: true,
  syncing: false,
  recentBlocked: [],
  logs: [],
};

/** In-memory mirror of the demo state so the browser preview stays interactive. */
let localState: OmitStateSnapshot | null = null;

export function readInitialState(): OmitStateSnapshot {
  const bridge = androidBridge();
  if (bridge) {
    try {
      return JSON.parse(bridge.getStateJson()) as OmitStateSnapshot;
    } catch {
      return { ...DEFAULT_FALLBACK_STATE };
    }
  }
  if (!localState) {
    localState = {
      ...DEFAULT_FALLBACK_STATE,
      dark: readStoredDark(true),
    };
  }
  return { ...localState };
}

export function persistLocalState(partial: Partial<OmitStateSnapshot>): void {
  const current = localState ?? readInitialState();
  localState = { ...current, ...partial };
}

export function readInitialApps(): InstalledAppItem[] {
  const bridge = androidBridge();
  if (bridge) {
    try {
      return JSON.parse(bridge.getInstalledAppsJson()) as InstalledAppItem[];
    } catch {
      return [];
    }
  }
  // Only the Android shell can enumerate packages; never invent an app list.
  return [];
}

export interface SpyStatItem {
  packageName: string;
  appName: string;
  spyCount: number;
  bgWakeups: number;
  lastDomain: string;
}

export function readSpyStats(): SpyStatItem[] {
  try {
    const raw = androidBridge()?.getSpyStatsJson?.();
    if (raw) return JSON.parse(raw) as SpyStatItem[];
  } catch {
    /* ignore */
  }
  return [];
}

export function openExternalUrl(url: string): void {
  const bridge = androidBridge();
  if (bridge?.openExternalUrl) {
    try {
      bridge.openExternalUrl(url);
      return;
    } catch {
      /* fall through */
    }
  }
  window.open(url, "_blank", "noopener,noreferrer");
}

export function copyToClipboard(text: string): void {
  try {
    androidBridge()?.copyToClipboard?.(text);
  } catch {
    /* ignore */
  }
  try {
    void navigator.clipboard?.writeText(text).catch(() => undefined);
  } catch {
    /* ignore */
  }
}

export interface BurnerEmailState {
  address: string;
  provider?: string;
  error?: string;
}

export interface BurnerEmailMsg {
  id: string;
  from: string;
  subject: string;
  intro: string;
  otp: string;
}

export interface BurnerSmsNum {
  digits: string;
  formatted: string;
}

export interface BurnerSmsMsg {
  from: string;
  text: string;
  time: string;
  otp: string;
}

export interface HomeShieldInfo {
  enabled: boolean;
  lanIp: string;
  port: number;
  blockedCount: number;
}

export function nativeGetBurnerEmail(forceNew: boolean): BurnerEmailState {
  try {
    const raw = androidBridge()?.getBurnerEmailJson?.(forceNew);
    if (raw) return JSON.parse(raw) as BurnerEmailState;
  } catch {
    /* ignore */
  }
  return { address: "" };
}

export function nativeGetBurnerInbox(): BurnerEmailMsg[] {
  try {
    const raw = androidBridge()?.getBurnerInboxJson?.();
    if (raw) return JSON.parse(raw) as BurnerEmailMsg[];
  } catch {
    /* ignore */
  }
  return [];
}

export function nativeGetBurnerSmsNumbers(): BurnerSmsNum[] {
  try {
    const raw = androidBridge()?.getBurnerSmsNumbersJson?.();
    if (raw) return JSON.parse(raw) as BurnerSmsNum[];
  } catch {
    /* ignore */
  }
  return [];
}

export function nativeGetBurnerSmsMessages(digits: string): BurnerSmsMsg[] {
  try {
    const raw = androidBridge()?.getBurnerSmsMessagesJson?.(digits);
    if (raw) return JSON.parse(raw) as BurnerSmsMsg[];
  } catch {
    /* ignore */
  }
  return [];
}

export function nativeSetHomeShield(enabled: boolean): boolean {
  const bridge = androidBridge();
  if (!bridge?.setHomeShieldEnabled) return false;
  try {
    return bridge.setHomeShieldEnabled(enabled) !== false;
  } catch {
    return false;
  }
}

export function nativeGetHomeShieldInfo(): HomeShieldInfo {
  try {
    const raw = androidBridge()?.getHomeShieldInfoJson?.();
    if (raw) {
      const parsed = JSON.parse(raw) as Partial<HomeShieldInfo> & { enabled?: boolean };
      return {
        enabled: Boolean(parsed.enabled),
        lanIp: parsed.lanIp || "",
        port: parsed.port || 0,
        blockedCount: parsed.blockedCount ?? 0,
      };
    }
  } catch {
    /* ignore */
  }
  return { enabled: false, lanIp: "", port: 0, blockedCount: 0 };
}

export function nativeGetTamperWarning(): string {
  try {
    return androidBridge()?.getTamperWarning?.() ?? "";
  } catch {
    return "";
  }
}

export function setDarkMode(dark: boolean): void {
  storeDark(dark);
  persistLocalState({ dark });
  try {
    androidBridge()?.setDarkMode(dark);
  } catch {
    /* ignore */
  }
  const invoke = tauriInvoke();
  if (invoke) void invoke("set_windows_theme", { dark }).catch(() => undefined);
}

export function nativeVersionCode(): number {
  try {
    return androidBridge()?.getVersionCode?.() ?? 0;
  } catch {
    return 0;
  }
}

const LOCAL_UPDATE_KEY = "omit-update-seen";

/** Returns true when the user has already dismissed this exact version. */
export function isUpdateDismissed(versionCode: number): boolean {
  try {
    return localStorage.getItem(LOCAL_UPDATE_KEY) === String(versionCode);
  } catch {
    return false;
  }
}

export function dismissUpdate(versionCode: number): void {
  try {
    localStorage.setItem(LOCAL_UPDATE_KEY, String(versionCode));
  } catch {
    /* ignore */
  }
}

export interface UpdateCheckResult {
  available: boolean;
  info: UpdateInfo | null;
  error?: string;
}

/** Asks the native shell (Android) for version.json; falls back to a direct fetch in browsers. */
export async function checkForUpdate(currentVersionCode: number): Promise<UpdateCheckResult> {
  const bridge = androidBridge();
  const checkUpdateAsync = bridge?.checkUpdateAsync;
  if (bridge && checkUpdateAsync) {
    return new Promise((resolve) => {
      let settled = false;
      const finish = (result: UpdateCheckResult) => {
        if (settled) return;
        settled = true;
        window.__omitOnUpdate = previousOnUpdate;
        resolve(result);
      };
      const previousOnUpdate = window.__omitOnUpdate;
      window.__omitOnUpdate = (info) => {
        finish({
          available: Boolean(info && info.versionCode > currentVersionCode),
          info: info ?? null,
        });
      };
      try {
        checkUpdateAsync(currentVersionCode);
      } catch (error) {
        finish({ available: false, info: null, error: String(error) });
      }
      window.setTimeout(() => finish({ available: false, info: null, error: "timeout" }), 12000);
    });
  }
  // Desktop asks the shell first: version.json lives on the website, not in the
  // Windows bundle, so a relative fetch would 404 there.
  const invoke = tauriInvoke();
  if (invoke) {
    try {
      const raw = await invoke<Record<string, unknown> | null>("get_windows_update");
      if (raw) {
        const info = normalizeUpdateInfo(raw);
        if (info) {
          return { available: Boolean(info.versionCode > currentVersionCode), info };
        }
      }
      return { available: false, info: null, error: "no update manifest" };
    } catch {
      return { available: false, info: null, error: "offline" };
    }
  }
  try {
    const response = await fetch("./version.json", { cache: "no-store" });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const raw = (await response.json()) as Record<string, unknown>;
    const info = normalizeUpdateInfo(raw);
    return { available: Boolean(info && info.versionCode > currentVersionCode), info };
  } catch (error) {
    return { available: false, info: null, error: String(error) };
  }
}

export function normalizeUpdateInfo(raw: Record<string, unknown> | null | undefined): UpdateInfo | null {
  if (!raw) return null;
  const android = (raw.android ?? raw) as Record<string, unknown>;
  const version = String(android.versionName ?? raw.version ?? "");
  const versionCode = Number(android.versionCode ?? raw.versionCode ?? 0);
  if (!version || !Number.isFinite(versionCode) || versionCode <= 0) return null;
  return {
    version,
    versionCode,
    notes: String(android.notes ?? raw.notes ?? ""),
    apkUrl: String(android.apkUrl ?? raw.androidApkUrl ?? ""),
    windowsExeUrl: String(raw.windowsExeUrl ?? ""),
    otaUrl: String(android.otaUrl ?? raw.otaUrl ?? ""),
    otaSha256: String(android.otaSha256 ?? raw.otaSha256 ?? ""),
    otaCode: Number(android.otaCode ?? raw.otaCode ?? versionCode),
  };
}

export function applyOtaUpdate(info: UpdateInfo): void {
  const bridge = androidBridge();
  if (bridge?.applyOtaPatch) {
    bridge.applyOtaPatch(info.otaUrl, info.otaSha256, info.otaCode);
    return;
  }
  openExternalUrl(info.apkUrl);
}

export function downloadAndInstallUpdate(info: UpdateInfo): void {
  const bridge = androidBridge();
  if (bridge?.downloadAndInstallUpdate) {
    bridge.downloadAndInstallUpdate(info.apkUrl);
    return;
  }
  const invoke = tauriInvoke();
  if (invoke) {
    const installer = info.windowsExeUrl || info.apkUrl;
    void invoke("download_and_install_update", { exeUrl: installer }).catch(() => undefined);
    return;
  }

  openExternalUrl(info.apkUrl);
}

export interface WindowsStats {
  total: number;
  blocked: number;
  active: boolean;
  /** Rules present in the bundled blocklist (0 when the resource is missing). */
  rules: number;
  /** 8-bit category mask currently applied by the engine. */
  mask: number;
}

export interface WindowsShieldStatus {
  active: boolean;
  listening: boolean;
  admin: boolean;
  message: string;
}

export async function windowsStats(): Promise<WindowsStats | null> {
  const invoke = tauriInvoke();
  if (!invoke) return null;
  try {
    const stats = await invoke<Partial<WindowsStats>>("get_windows_stats");
    if (!stats) return null;
    return {
      total: Number(stats.total ?? 0),
      blocked: Number(stats.blocked ?? 0),
      active: Boolean(stats.active),
      rules: Number(stats.rules ?? 0),
      mask: Number(stats.mask ?? 0),
    };
  } catch {
    return null;
  }
}

export async function windowsShieldStatus(): Promise<WindowsShieldStatus | null> {
  const invoke = tauriInvoke();
  if (!invoke) return null;
  try {
    return await invoke<WindowsShieldStatus>("get_shield_status");
  } catch {
    return null;
  }
}

export async function setWindowsShield(enable: boolean): Promise<boolean | null> {
  const invoke = tauriInvoke();
  if (!invoke) return null;
  try {
    return await invoke<boolean>("toggle_windows_shield", { enable });
  } catch {
    return false;
  }
}

/** Pushes the full category mask (or one flipped bit) to the Windows engine. */
export async function setWindowsCategoryMask(mask?: number, toggleIndex?: number): Promise<number | null> {
  const invoke = tauriInvoke();
  if (!invoke) return null;
  try {
    const args: Record<string, unknown> = {};
    if (typeof mask === "number") args.mask = mask;
    if (typeof toggleIndex === "number") args.toggleIndex = toggleIndex;
    return await invoke<number>("set_windows_categories", args);
  } catch {
    return null;
  }
}

/** Adds an allow/block rule to the Windows engine. */
export async function setWindowsRule(domain: string, mode: "allow" | "block" | "clear"): Promise<boolean> {
  const invoke = tauriInvoke();
  if (!invoke) return false;
  try {
    return await invoke<boolean>("set_windows_rules", { domain, mode });
  } catch {
    return false;
  }
}

/** Recent DNS decisions recorded by the Windows engine (newest first). */
export async function getWindowsLogs(): Promise<DnsLogItem[] | null> {
  const invoke = tauriInvoke();
  if (!invoke) return null;
  try {
    const rows = await invoke<DnsLogItem[]>("get_windows_logs");
    return Array.isArray(rows) ? rows : [];
  } catch {
    return null;
  }
}

export async function clearWindowsLogs(): Promise<void> {
  const invoke = tauriInvoke();
  if (!invoke) return;
  try {
    await invoke("clear_windows_logs");
  } catch {
    /* ignore */
  }
}

export async function getWindowsRules(): Promise<{ allow: string[]; block: string[] } | null> {
  const invoke = tauriInvoke();
  if (!invoke) return null;
  try {
    const [allow, block] = await invoke<[string[], string[]]>("get_windows_rules");
    return { allow: allow ?? [], block: block ?? [] };
  } catch {
    return null;
  }
}

/** Full Windows engine state, shaped exactly like Android's getStateJson. */
export async function windowsState(): Promise<Partial<OmitStateSnapshot> | null> {
  const invoke = tauriInvoke();
  if (!invoke) return null;
  try {
    return await invoke<Partial<OmitStateSnapshot>>("get_windows_state");
  } catch {
    return null;
  }
}

/** Selects an encrypted resolver on Windows (index 4 = custom DoH). */
export async function setWindowsDns(index: number, customUrl: string): Promise<boolean> {
  const invoke = tauriInvoke();
  if (!invoke) return false;
  try {
    return await invoke<boolean>("set_windows_dns", { index, customUrl });
  } catch {
    return false;
  }
}

/** Flips one of the five engine switches and persists it. */
export async function setWindowsEngine(key: string, enabled: boolean): Promise<boolean> {
  const invoke = tauriInvoke();
  if (!invoke) return false;
  try {
    return await invoke<boolean>("set_windows_engine", { key, enabled });
  } catch {
    return false;
  }
}

/** Removes a domain from both the allow and block sets. */
export async function clearWindowsRule(domain: string): Promise<boolean> {
  const invoke = tauriInvoke();
  if (!invoke) return false;
  try {
    return await invoke<boolean>("clear_windows_rule", { domain });
  } catch {
    return false;
  }
}

/** The desktop build's own version code (2.5.0 -> 250). */
export async function windowsInstalledCode(): Promise<number> {
  const invoke = tauriInvoke();
  if (!invoke) return 0;
  try {
    const raw = await invoke<Record<string, unknown> | null>("get_windows_update");
    const code = Number(raw?.installedVersionCode ?? 0);
    return Number.isFinite(code) ? code : 0;
  } catch {
    return 0;
  }
}

/** Turns DNS logging on or off (the engine stops recording when false). */
export async function setWindowsLogging(enabled: boolean): Promise<boolean> {
  const invoke = tauriInvoke();
  if (!invoke) return false;
  try {
    return await invoke<boolean>("set_windows_logging", { enabled });
  } catch {
    return false;
  }
}

/** True while every adapter still points at Omit's loopback resolver. */
export async function windowsDnsLock(): Promise<boolean | null> {
  const invoke = tauriInvoke();
  if (!invoke) return null;
  try {
    return await invoke<boolean>("get_windows_dns_lock");
  } catch {
    return null;
  }
}

export function formatCompactCount(n: number): string {
  if (!Number.isFinite(n) || n < 0) return "0";
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`;
  if (n >= 100_000) return `${(n / 1_000).toFixed(1)}k`;
  return n.toLocaleString("en-US");
}
