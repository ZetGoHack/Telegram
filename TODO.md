# rawGram TODO

## Android developer verification (so Play Protect stops warning on installs)

Status 2026-10-04: full distribution account paid, identity documents submitted — Google asked to wait a couple of days.

- [ ] Wait for the identity verification email from the Android Developer Console
      (https://android.google.com/developerconsole/developers).
- [ ] Packages → register `dev.rawgram.app.beta` and `dev.rawgram.app` with the signing key's SHA-256:
      `DD:66:0C:F4:86:4F:DD:DF:26:9C:79:26:7E:3B:D3:AD:48:31:5D:53:B6:A6:31:10:88:19:C3:2B:3A:A0:9A:A7`
      (key: `C:\Users\aleks\.rawgram\rawgram-release.jks`, see `local.properties`).
- [ ] Ownership challenge: put the snippet the console gives into `TMessagesProj/src/main/assets/`, build an APK signed
      with the same key, upload it; wait for «Registered».
- [ ] Check on a clean install whether Play Protect still shows «hasn't seen an app from this developer before».
      If it does, submit the APK through the Play Protect appeal form.
- [ ] Plugins clone: add `RAWGRAM_SIGN_DEBUG=true` to `../telegram-plugs/local.properties` so its builds use the same key.
