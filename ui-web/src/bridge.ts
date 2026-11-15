export type ShieldState = "off" | "protected" | "paused" | "game";
export type AppMode = "Protected" | "Bypassed" | "Offline Game";
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

export interface VellumStateSnapshot {
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

interface NativeBridge {
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
}

declare global {
  interface Window {
    VellumAndroid?: NativeBridge;
    __vellumOnNativeState?: (state: VellumStateSnapshot) => void;
    __vellumOnNativeApps?: (apps: InstalledAppItem[]) => void;
    __vellumOnToast?: (msg: string) => void;
    __vellumHandleBack?: () => boolean;
  }
}

export const isAndroidRuntime = (): boolean => typeof window !== "undefined" && !!window.VellumAndroid;

const DEFAULT_FALLBACK_STATE: VellumStateSnapshot = {
  dark: true,
  shield: "protected",
  countdown: 60,
  intensity: "Balanced",
  categories: [true, true, true, true, true, true, false, false],
  engine: [true, true, true, true, true],
  dns: 0,
  customDohUrl: "https://dns.google/dns-query",
  gameMode: false,
  offlineGameCount: 2,
  blockedCount: 1284,
  totalQueries: 7132,
  activeRules: 209332,
  privateDnsHost: "",
  allowDomains: [],
  blockDomains: [],
  customUrls: [],
  logging: true,
  syncing: false,
  recentBlocked: [
    { domain: "app-measurement.com", category: "Cross-App Trackers", time: "12s" },
    { domain: "ads.unity3d.com", category: "Game & In-App Ads", time: "38s" },
  ],
  logs: [
    { id: 1, domain: "app-measurement.com", time: "Now", status: "Blocked", category: "Cross-App Trackers" },
    { id: 2, domain: "api.spotify.com", time: "4s", status: "Allowed", category: "Google DNS" },
    { id: 3, domain: "ads.unity3d.com", time: "18s", status: "Blocked", category: "Game & In-App Ads" },
    { id: 4, domain: "accounts.google.com", time: "25s", status: "Allowed", category: "Google DNS" },
  ],
};

const DEFAULT_FALLBACK_APPS: InstalledAppItem[] = [
  { pkg: "com.neon.arcadedrift", name: "Arcade Drift", initials: "AD", color: "#7768d9", isSystem: false, mode: "Offline Game" },
  { pkg: "com.android.chrome", name: "Chrome", initials: "C", color: "#3f9875", isSystem: false, mode: "Protected" },
  { pkg: "com.snapwork.hdfc", name: "HDFC Bank", initials: "H", color: "#477bc6", isSystem: false, mode: "Bypassed" },
  { pkg: "com.twofingers.chess", name: "Pocket Chess", initials: "PC", color: "#cb8a39", isSystem: false, mode: "Offline Game" },
  { pkg: "com.spotify.music", name: "Spotify", initials: "S", color: "#419b64", isSystem: false, mode: "Protected" },
  { pkg: "com.google.android.youtube", name: "YouTube", initials: "YT", color: "#db5a51", isSystem: false, mode: "Protected" },
];

export function readInitialState(): VellumStateSnapshot {
  if (window.VellumAndroid) {
    try {
      return JSON.parse(window.VellumAndroid.getStateJson()) as VellumStateSnapshot;
    } catch {
      return DEFAULT_FALLBACK_STATE;
    }
  }
  return DEFAULT_FALLBACK_STATE;
}

export function readInitialApps(): InstalledAppItem[] {
  if (window.VellumAndroid) {
    try {
      return JSON.parse(window.VellumAndroid.getInstalledAppsJson()) as InstalledAppItem[];
    } catch {
      return [];
    }
  }
  return DEFAULT_FALLBACK_APPS;
}

export interface SpyStatItem {
  packageName: string;
  appName: string;
  spyCount: number;
  bgWakeups: number;
  lastDomain: string;
}

export function isTvMode(): boolean {
  try {
    if ((window.VellumAndroid as unknown as { isTvMode?: () => boolean })?.isTvMode) {
      return (window.VellumAndroid as unknown as { isTvMode: () => boolean }).isTvMode();
    }
  } catch { /* ignore */ }
  return typeof window !== "undefined" && window.innerWidth >= 960 && window.innerHeight <= 800;
}

export function readSpyStats(): SpyStatItem[] {
  try {
    const fn = (window.VellumAndroid as unknown as { getSpyStatsJson?: () => string })?.getSpyStatsJson;
    if (fn) return JSON.parse(fn()) as SpyStatItem[];
  } catch { /* ignore */ }
  return [];
}

export function openExternalUrl(url: string): void {
  try {
    const fn = (window.VellumAndroid as unknown as { openExternalUrl?: (u: string) => void })?.openExternalUrl;
    if (fn) { fn(url); return; }
  } catch { /* ignore */ }
  window.open(url, "_blank", "noopener,noreferrer");
}

export function formatCompactCount(n: number): string {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`;
  if (n >= 100_000) return `${(n / 1_000).toFixed(1)}k`;
  return n.toLocaleString("en-US");
}
