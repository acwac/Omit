# Release signing

Android only installs an update whose signing certificate is **identical** to the
installed app's. If CI signs with a fresh key, every existing user has to uninstall
first — which is what used to happen, because the workflow passed no signing
material and `scripts/build_apk.sh` generated a throwaway keystore per build.

## What to configure

A permanent keypair was generated for this project and is **not** committed
(`signing/*.jks` is git-ignored). Store it in the repository's Actions secrets:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | base64 of `signing/omit-release.jks` (single line) |
| `ANDROID_KEYSTORE_PASSWORD` | `omit2026` |
| `ANDROID_KEY_PASSWORD` | `omit2026` |
| `ANDROID_KEY_ALIAS` | `omit` |
| `RELEASE_CERT_SHA256` | `261D03720114648D744A1392FD077D74F093C6165D145B0055EA4901B0EE9F9F` |

Certificate subject is `CN=Omit Shield, OU=Mobile, O=Omit, L=Jaipur, ST=RJ, C=IN`,
valid until 2054.

Without these secrets the build still succeeds (useful for pull requests) but logs
a warning, and the publish job prints `::warning title=No pinned certificate::`.
With `RELEASE_CERT_SHA256` set, the publish job **fails** if the APK certificate
differs, so a broken identity can never reach `downloads/Omit.apk`.

## Local builds

```bash
# throwaway key, not installable over a previous release
ALLOW_EPHEMERAL_KEY=1 ./scripts/build_apk.sh dist-release/Omit.apk

# real release key
SIGNING_KEYSTORE=signing/omit-release.jks \
SIGNING_STORE_PASS=omit2026 SIGNING_KEY_PASS=omit2026 SIGNING_KEY_ALIAS=omit \
./scripts/build_apk.sh dist-release/Omit.apk
```

The build log prints the keystore SHA-256 so any release can be traced back to a
specific identity.

## Already-broken installs

Every Omit APK published before this change was signed with a different throwaway
key, so those installs cannot be updated in place. One uninstall (which clears the
app's data) is required **once**; every release after that updates cleanly.
