# rawGram TODO

## Android developer verification (so Play Protect stops warning on installs)

Status 2026-10-06: identity verified, both packages registered with the rawGram key.

- [x] Wait for the identity verification email from the Android Developer Console
      (https://android.google.com/developerconsole/developers).
- [x] Packages → register `dev.rawgram.app.beta` and `dev.rawgram.app` with the signing key's SHA-256:
      `DD:66:0C:F4:86:4F:DD:DF:26:9C:79:26:7E:3B:D3:AD:48:31:5D:53:B6:A6:31:10:88:19:C3:2B:3A:A0:9A:A7`
      (key: `C:\Users\aleks\.rawgram\rawgram-release.jks`, see `local.properties`).
- [x] Ownership challenge: done with code-less dummy APKs (`..\rawgram-builds\adi\make-adi.ps1`: package name + token
      asset + the key). The token is not needed in normal builds; rerun the script for a new package or key.
- [x] Check on a clean install whether Play Protect still shows «hasn't seen an app from this developer before».
      (2026-10-07: after a couple of installs it no longer asks to scan and installs silently.)
- [ ] Plugins clone: add `RAWGRAM_SIGN_DEBUG=true` to `../telegram-plugs/local.properties` so its builds use the same key.
