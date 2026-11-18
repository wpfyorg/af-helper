# Releasing AF Helper

Release APKs are signed with a dedicated RSA 4096 key. The keystore and its passwords are local-only and must never be committed.

## Key

- Keystore: `~/.af-helper-signing/release.keystore`
- Passwords: `~/.af-helper-signing/keystore.properties`
- Alias: `af-helper`
- Certificate SHA-256: `A1:21:0F:1F:DD:17:19:E1:67:42:A3:CE:C1:BB:8D:E5:8A:5B:71:32:DF:0D:34:53:12:95:9F:4C:DA:E2:19:16`

Back up both files somewhere safe. Losing the key means future builds cannot update an installed copy of the app.

For a local release build:

1. Copy `release.keystore` to the repository root (it is gitignored), or keep a copy there.
2. Add the signing values to `local.properties`:

   ```properties
   RELEASE_STORE_PASSWORD=...
   RELEASE_KEY_ALIAS=af-helper
   RELEASE_KEY_PASSWORD=...
   ```

3. Build:

   ```sh
   ./gradlew :bleapp:assembleRelease
   ```

The APK is written to `bleapp/build/outputs/apk/release/bleapp-release.apk`.

## Verify

```sh
apksigner verify --print-certs bleapp/build/outputs/apk/release/bleapp-release.apk
aapt2 dump badging bleapp/build/outputs/apk/release/bleapp-release.apk | head -1
shasum -a 256 bleapp/build/outputs/apk/release/bleapp-release.apk
```

## Publish

1. Bump `versionCode` and `versionName` in `bleapp/build.gradle.kts`, then commit.
2. Build and verify the release APK.
3. Copy it to a versioned filename, for example `af-helper-1.0.10.apk`.
4. Create a GitHub release with the version tag, attach the APK, and include the SHA-256 checksum in the notes.

```sh
gh release create v1.0.10 af-helper-1.0.10.apk \
  --repo wpfyorg/af-helper \
  --title "AF Helper 1.0.10" \
  --notes-file release-notes.md \
  --latest
```
