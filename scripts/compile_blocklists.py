#!/usr/bin/env python3
"""
Vellum Offline Blocklist Compiler (vellum_starter.vbin)
Compiles open-source DNS filter lists into an ultra-compact, zero-allocation
64-bit sorted hash + 8-bit category bitmask binary table (VELLUM01 format).
"""

import os
import re
import struct
import sys
import urllib.request

# Exact bit positions matching App.tsx categoryData indices 0..7:
# 0: Game & In-App Ads         (0x01)
# 1: Web Banners & Popups      (0x02)
# 2: Cross-App Trackers        (0x04)
# 3: Malware & Scam Shield     (0x08)
# 4: Phone Brand Telemetry     (0x10)
# 5: Betting & Casino Popups   (0x20)
# 6: Shopping & Affiliate Links(0x40)
# 7: Social Media Trackers     (0x80)
CAT_GAME_ADS  = 1 << 0
CAT_WEB_ADS   = 1 << 1
CAT_TRACKERS  = 1 << 2
CAT_MALWARE   = 1 << 3
CAT_OEM       = 1 << 4
CAT_GAMBLING  = 1 << 5
CAT_AFFILIATE = 1 << 6
CAT_SOCIAL    = 1 << 7

ESSENTIAL_SAFE = {
    "localhost", "local", "broadcasthost", "ip6-localhost", "ip6-loopback",
    "google.com", "www.google.com", "google.co.in", "www.google.co.in",
    "accounts.google.com", "dns.google", "8888.google", "googleapis.com",
    "gstatic.com", "ggpht.com", "googleusercontent.com", "googlevideo.com",
    "youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be", "ytimg.com",
    "gvt1.com", "gvt2.com", "play.google.com", "android.com",
    "mtalk.google.com", "alt1-mtalk.google.com", "fcm.googleapis.com",
    "cloudflare.com", "cloudflare-dns.com", "one.one.one.one", "quad9.net",
    "whatsapp.com", "whatsapp.net", "wa.me",
    "instagram.com", "www.instagram.com", "cdninstagram.com",
    "facebook.com", "www.facebook.com", "m.facebook.com", "fbcdn.net", "fbsbx.com",
    "twitter.com", "x.com", "twimg.com", "t.co",
    "reddit.com", "www.reddit.com", "redd.it", "redditmedia.com", "redditstatic.com",
    "github.com", "raw.githubusercontent.com", "github.io", "gitlab.com",
    "wikipedia.org", "wikimedia.org",
    "netflix.com", "nflxvideo.net", "nflximg.net", "nflxext.com",
    "spotify.com", "api.spotify.com", "scdn.co", "spotifycdn.com", "images.spotifycdn.com",
    "amazon.com", "amazon.in", "www.amazon.in", "media-amazon.com", "ssl-images-amazon.com", "amazonaws.com",
    "flipkart.com", "www.flipkart.com", "flixcart.com",
    "paytm.com", "phonepe.com", "razorpay.com", "billdesk.com", "cashfree.com", "npci.org.in",
    "sbi.co.in", "hdfcbank.com", "icicibank.com", "axisbank.com", "kotak.com",
    "jio.com", "airtel.in", "irctc.co.in", "uidai.gov.in", "digilocker.gov.in",
    "apple.com", "icloud.com", "mzstatic.com",
    "microsoft.com", "live.com", "office.com", "outlook.com", "bing.com",
    "connectivitycheck.gstatic.com", "clients3.google.com", "captive.apple.com", "msftconnecttest.com"
}

PURE_SHOPPING_AFFILIATE = {
    "googleadservices.com", "www.googleadservices.com",
    "clickserve.dartsearch.net", "dartsearch.net",
    "anrdoezrs.net", "commission-junction.com", "cj.com",
    "dpbolvw.net", "jdoqocy.com", "kqzyfj.com", "tkqlhce.com", "qksrv.net",
    "linksynergy.com", "rakutenmarketing.com", "pntra.com", "pntrac.com", "pntrs.com",
    "ojrq.net", "avantlink.com", "awin1.com", "zenaps.com", "shareasale.com",
    "impactradius-event.com", "7eer.net", "evyy.net", "prf.hn",
    "partnerize.com", "skimresources.com", "redirectingat.com", "viglink.com",
    "geni.us", "amzn.to", "fkrt.it", "bitli.in", "earnkaro.com", "cashkaro.com",
    "track.admitad.com", "admitad.com", "vcommission.com", "inrdeals.com",
    "shopstyle.it", "rstyle.me", "narrativ.com", "howl.me"
}

GAME_AND_INAPP_SEEDS = {
    "unityads.unity3d.com", "ads.unity3d.com", "adserver.unityads.unity3d.com",
    "auction.unityads.unity3d.com", "config.unityads.unity3d.com", "webview.unityads.unity3d.com",
    "applovin.com", "applvn.com", "a.applovin.com", "d.applovin.com", "ms.applovin.com", "rt.applovin.com",
    "ironsrc.com", "ironsource.mobi", "supersonicads.com", "outcome-ssp.supersonicads.com",
    "vungle.com", "ads.api.vungle.com", "config.ads.vungle.com",
    "chartboost.com", "live.chartboost.com",
    "inmobi.com", "w.inmobi.com", "config.inmobi.com",
    "mintegral.com", "rayjump.com", "net.rayjump.com",
    "adcolony.com", "events3alt.adcolony.com",
    "tapjoy.com", "ws.tapjoyads.com", "tapjoyads.com",
    "fyber.com", "inner-active.mobi",
    "startappservice.com", "startappexchange.com",
    "pangle.io", "pangleglobal.com",
    "admob.com", "media.admob.com", "googleads.g.doubleclick.net", "pubads.g.doubleclick.net",
    "liftoff.io", "bidmachine.io", "appodeal.com", "yandexadexchange.net",
    "smaato.net", "mopub.com", "ogury.io", "kidoz.net", "superawesome.tv", "hyprmx.com",
    "pokkt.com", "vserv.mobi", "greedygame.com"
}

REGIONAL_WEB_AD_SEEDS = {
    "clmbtech.com", "ade.clmbtech.com", "ase.clmbtech.com",
    "izooto.com", "cdn.izooto.com",
    "taboola.com", "trc.taboola.com", "cdn.taboola.com",
    "outbrain.com", "widgets.outbrain.com", "odb.outbrain.com",
    "mgid.com", "jsc.mgid.com", "servicer.mgid.com",
    "propellerads.com", "popads.net", "popcash.net",
    "highperformanceformat.com", "profitablecpmrate.com",
    "vertoz.com", "forkmedia.in", "adpushup.com"
}

OEM_SEEDS = {
    "ad.xiaomi.com", "ad1.xiaomi.com", "sdkconfig.ad.xiaomi.com", "tracking.miui.com",
    "data.mistat.xiaomi.com", "data.mistat.india.xiaomi.com", "storeconfig.mistat.intl.xiaomi.com",
    "api.ad.xiaomi.com", "zeus.ad.xiaomi.com", "test.ad.xiaomi.com", "cdn.ad.xiaomi.com",
    "abi.sec.miui.com", "tracking.intl.miui.com", "adv.sec.miui.com", "adv.sec.intl.miui.com",
    "samsungads.com", "config.samsungads.com", "events.samsungads.com", "smetrics.samsung.com",
    "nmetrics.samsung.com", "analytics-api.samsunghealthcn.com", "amsonpush.samsungosp.com",
    "adsfs.oppomobile.com", "adx.ads.oppomobile.com", "bdapi.ads.oppomobile.com", "data.ads.oppomobile.com",
    "ck.ads.oppomobile.com", "iotlog.oppo.com", "i.stat.nearme.com.cn", "stat.heytapmobi.com",
    "ads.heytapmobi.com", "adx-in.ads.heytapmobi.com", "bdapi-in-ads.realmemobile.com",
    "iot-eu-logser.realme.com", "iot-logser.realme.com",
    "adsdk.vivo.com.cn", "adreq.vivo.com.cn", "adlog.vivo.com.cn", "stsdk.vivo.com.cn",
    "monitor-stsdk.vivo.com.cn", "onrt-stsdk.vivo.com.cn", "ort-stsdk.vivo.com.cn",
    "open.oneplus.net", "click.oneplus.cn", "metrics.data.hicloud.com", "logservice.hicloud.com"
}

SOCIAL_TRACKER_SEEDS = {
    "pixel.facebook.com", "an.facebook.com",
    "connect.facebook.net", "analytics.tiktok.com", "business-api.tiktok.com",
    "ads-api.twitter.com", "analytics.twitter.com", "ads-twitter.com",
    "tr.snapchat.com", "sc-analytics.appspot.com",
    "ct.pinterest.com", "ads.pinterest.com", "trk.pinterest.com",
    "px.ads.linkedin.com", "dc.ads.linkedin.com",
    "events.redditmedia.com", "alb.reddit.com"
}

DOMAIN_RE = re.compile(r"^[a-z0-9]([a-z0-9\-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9\-]{0,61}[a-z0-9])?)+$")

def vellum_hash64_unsigned(domain: str) -> int:
    h = 0xcbf29ce484222325
    prime = 0x100000001b3
    mask64 = 0xFFFFFFFFFFFFFFFF
    for ch in domain.encode("ascii"):
        if 65 <= ch <= 90:
            ch += 32
        h = ((h ^ ch) * prime) & mask64
    h ^= (h >> 33)
    h = (h * 0xff51afd7ed558ccd) & mask64
    h ^= (h >> 33)
    h = (h * 0xc4ceb9fe1a85ec53) & mask64
    h ^= (h >> 33)
    return h

def to_signed64(u: int) -> int:
    return u - (1 << 64) if u >= (1 << 63) else u

def parse_line(line: str):
    line = line.strip().lower()
    if not line or line.startswith("#") or line.startswith("!") or line.startswith("["):
        return None
    if "#" in line:
        line = line.split("#", 1)[0].strip()
    if line.startswith("@@||") and "^" in line:
        d = line[4:].split("^", 1)[0].strip(".")
        if "/" not in d and "*" not in d and DOMAIN_RE.match(d):
            return ("allow", d)
        return None
    if line.startswith("||") and "^" in line:
        d = line[2:].split("^", 1)[0].strip(".")
        if "/" not in d and "*" not in d and DOMAIN_RE.match(d):
            return ("block", d)
        return None
    if line.startswith("*."):
        d = line[2:].strip(".")
        if DOMAIN_RE.match(d):
            return ("block", d)
        return None
    parts = line.split()
    if len(parts) >= 2 and parts[0] in ("0.0.0.0", "127.0.0.1", "::", "::1"):
        d = parts[1].strip(".")
    elif len(parts) == 1:
        d = parts[0].strip(".")
    else:
        return None
    if len(d) <= 253 and DOMAIN_RE.match(d):
        return ("block", d)
    return None

SOURCES = [
    ("AdGuard Official DNS Filter", "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt", CAT_GAME_ADS | CAT_WEB_ADS, 90000),
    ("OISD Small Curated Wildcards", "https://small.oisd.nl/domainswild2", CAT_GAME_ADS | CAT_WEB_ADS | CAT_TRACKERS, 70000),
    ("AdAway Mobile Ads", "https://adaway.org/hosts.txt", CAT_GAME_ADS, 25000),
    ("Peter Lowe AdServers", "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=nohtml&showintro=0", CAT_WEB_ADS, 10000),
    ("EasyList Web Banners", "https://v.firebog.net/hosts/Easylist.txt", CAT_WEB_ADS, 45000),
    ("EasyPrivacy Trackers", "https://v.firebog.net/hosts/Easyprivacy.txt", CAT_TRACKERS, 45000),
    ("Android OEM Telemetry", "https://raw.githubusercontent.com/Perflyst/PiHoleBlocklist/master/android-tracking.txt", CAT_OEM, 15000),
    ("Phishing & Scam Shield", "https://phishing.army/download/phishing_army_blocklist_extended.txt", CAT_MALWARE, 30000),
    ("Social Media Trackers", "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/social-only/hosts", CAT_SOCIAL, 12000),
    ("Betting & Casino Popups", "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/gambling-only/hosts", CAT_GAMBLING, 15000),
]

def main():
    repo_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    out_path = sys.argv[1] if len(sys.argv) > 1 else os.path.join(repo_root, "app", "src", "main", "assets", "omit_starter.vbin")
    domain_masks = {}
    upstream_exceptions = set(ESSENTIAL_SAFE)

    def add_domain(d: str, cat: int):
        d = d.strip().lower()
        if not d or d in upstream_exceptions or not DOMAIN_RE.match(d):
            return
        if cat == CAT_SOCIAL and d in {
            "facebook.com", "www.facebook.com", "instagram.com", "www.instagram.com",
            "twitter.com", "x.com", "reddit.com", "www.reddit.com", "linkedin.com",
            "www.linkedin.com", "pinterest.com", "www.pinterest.com", "snapchat.com",
            "tiktok.com", "www.tiktok.com", "discord.com", "telegram.org", "t.me"
        }:
            return
        for aff in PURE_SHOPPING_AFFILIATE:
            if d == aff or d.endswith("." + aff):
                domain_masks[d] = CAT_AFFILIATE
                return
        domain_masks[d] = domain_masks.get(d, 0) | cat

    for name, url, cat, cap in SOURCES:
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 VellumCompiler/1.0"})
            with urllib.request.urlopen(req, timeout=20) as resp:
                lines = resp.read().decode("utf-8", errors="ignore").splitlines()
            blocks, allows = 0, 0
            for raw in lines:
                parsed = parse_line(raw)
                if not parsed:
                    continue
                kind, dom = parsed
                if kind == "allow":
                    if dom not in GAME_AND_INAPP_SEEDS and dom not in PURE_SHOPPING_AFFILIATE:
                        upstream_exceptions.add(dom)
                        domain_masks.pop(dom, None)
                        allows += 1
                else:
                    actual_cat = cat
                    if any(k in dom for k in ("applovin", "unity3d", "unityads", "vungle", "ironsrc", "supersonicads", "chartboost", "inmobi", "admob", "adcolony", "tapjoy", "mintegral", "rayjump", "startapp", "appodeal", "mopub", "pokkt", "greedygame", "vserv", "pangle")):
                        actual_cat |= CAT_GAME_ADS
                    elif any(k in dom for k in ("miui", "xiaomi", "mistat", "oppomobile", "heytapmobi", "realmemobile", "samsungads", "smetrics.samsung", "vivo.com.cn", "hicloud")):
                        actual_cat |= CAT_OEM
                    add_domain(dom, actual_cat)
                    blocks += 1
                    if blocks >= cap:
                        break
            print(f"[OK] {name}: +{blocks:,} rules ({allows:,} unbreak exceptions)")
        except Exception as e:
            print(f"[WARN] {name} failed: {e}")

    for d in PURE_SHOPPING_AFFILIATE:
        domain_masks[d] = CAT_AFFILIATE
    for d in GAME_AND_INAPP_SEEDS:
        domain_masks[d] = domain_masks.get(d, 0) | CAT_GAME_ADS
    for d in REGIONAL_WEB_AD_SEEDS:
        domain_masks[d] = domain_masks.get(d, 0) | CAT_WEB_ADS | CAT_GAME_ADS
    for d in OEM_SEEDS:
        domain_masks[d] = domain_masks.get(d, 0) | CAT_OEM
    for d in SOCIAL_TRACKER_SEEDS:
        domain_masks[d] = domain_masks.get(d, 0) | CAT_SOCIAL
    for safe in ESSENTIAL_SAFE:
        domain_masks.pop(safe, None)

    print(f"Total unique domains before suffix pruning: {len(domain_masks):,}")

    pruned = {}
    for d, mask in domain_masks.items():
        parts = d.split(".")
        redundant = False
        for i in range(1, len(parts) - 1):
            parent = ".".join(parts[i:])
            pmask = domain_masks.get(parent, 0)
            if pmask != 0 and (pmask & mask) == mask:
                redundant = True
                break
        if not redundant:
            pruned[d] = mask

    print(f"Optimized suffix-pruned root rules: {len(pruned):,}")

    entries = {}
    for d, mask in pruned.items():
        sh = to_signed64(vellum_hash64_unsigned(d))
        entries[sh] = entries.get(sh, 0) | mask

    sorted_items = sorted(entries.items(), key=lambda x: x[0])
    count = len(sorted_items)

    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(out_path, "wb") as f:
        f.write(b"VELLUM01")
        f.write(struct.pack(">ii", count, 1))
        for sh, _ in sorted_items:
            f.write(struct.pack(">q", sh))
        f.write(bytes(m & 0xFF for _, m in sorted_items))

    print(f"Saved {out_path}: {count:,} root rules ({os.path.getsize(out_path) / 1024:.1f} KB)")

if __name__ == "__main__":
    main()
