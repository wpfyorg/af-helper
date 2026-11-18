# AF Helper

[![Android CI](https://github.com/wpfyorg/af-helper/actions/workflows/android.yml/badge.svg)](https://github.com/wpfyorg/af-helper/actions/workflows/android.yml)

AF Helper is a small, dependency-light Android app for finding a nearby Jio AirFiber ODU over Bluetooth LE, enabling its local WebUI, and managing the unit from the phone. It is an unofficial community tool. It is not affiliated with, endorsed by, or supported by Jio or the ODU vendor.

The app is native Java, has no analytics, and uploads nothing. All ODU traffic stays on the local Bluetooth PAN.

## Download

A signed release APK is attached to [Releases](https://github.com/wpfyorg/af-helper/releases/latest). Download the latest `af-helper-*.apk` and open it on the phone. Android will ask you to allow installs from the browser or file manager you used; the app itself requests only the permissions it needs at runtime.

Release APKs are signed with the project key. Verify the SHA-256 checksum published in the release notes before installing if you downloaded the file from anywhere else.

## What it does

- **Find the ODU.** Scan nearby BLE devices and show a sanitized name plus signal strength, or scan the sticker QR code / paste sticker text to jump straight to that unit. Bluetooth addresses are never displayed or logged.
- **Connect safely.** Confirm the vendor GATT service, run the discovery handshake, and read the ODU's Bluetooth PAN address. Only a valid private IPv4 host in `192.168.44.0/24` is accepted, and the address is shown before credentials are requested.
- **Enable the WebUI.** Sign in with the ODU credentials, check `gui_state`, enable the WebUI only when needed, verify the result, and open it in an in-app viewer. The viewer accepts the ODU's self-signed certificate only for that validated host.
- **Manage eSIM profiles.** List installed profiles, install the default Jio profile or one from an `LPA:1$...` activation code, enable, disable, or delete a profile, copy the EID, and reboot the ODU.
- **Read the radio.** Show the serving cell's RSRP, RSRQ, SINR, band, PCI, TAC, and a 0–100 strength summary.
- **Read the unit.** Show model, software version, EID, active profile, and local link-test latency.
- **Keep a local log.** A fixed, privacy-minimal event log is viewable in the app. It exists only on the device.

Both ODU WebUI families are supported: the JFE `/cgi-bin/app/*` API with a session cookie, and the CM `/cgi-bin/cgi/*` API with its rotating one-time token.

## Requirements

- Android 7.0 (API 24) or newer.
- A phone with Bluetooth LE.
- An AirFiber ODU whose BLE setup interface is available.
- Android 12+: Nearby Devices permission. Android 7–11: location permission and location services enabled for BLE scanning.
- Bluetooth tethering enabled on the phone after the ODU connects, so the phone can reach the ODU's PAN address. Android does not let an app turn this on silently; the app links to the relevant Settings screen.

## Build

```sh
./gradlew :bleapp:testDebugUnitTest :bleapp:assembleDebug
```

The debug APK is written to `bleapp/build/outputs/apk/debug/bleapp-debug.apk`.

Requirements: JDK 17+, Android SDK platform 36, and the Gradle wrapper (Gradle 9.6.0). The project uses Android Gradle Plugin 9.4.0. `compileSdk` is 36, `targetSdk` is 34, and `minSdk` is 24.

`local.properties` only needs `sdk.dir`. The debug build works with no other configuration.

### Optional local configuration

These values are read from `local.properties` at build time. None are required, and none are committed.

```properties
sdk.dir=/path/to/Android/sdk

# Optional: present a client certificate to ODU firmware that requires mTLS.
AF_BLE_CLIENT_KEYSTORE=/absolute/path/to/client.p12
AF_BLE_CLIENT_PASSWORD=change-me

# Optional: prefill the ODU sign-in form. The values are compiled into the APK,
# so only do this for a build you control.
AF_ODU_ADMIN_USER=Admin
AF_ODU_ADMIN_PASSWORD=change-me

# Optional: release signing. Put the keystore at ./release.keystore.
RELEASE_STORE_PASSWORD=change-me
RELEASE_KEY_ALIAS=af-helper
RELEASE_KEY_PASSWORD=change-me
```

A release build requires a signing configuration; without one, Gradle stops with a message listing the missing values.

## Protocol notes

The BLE contract is:

| Purpose | UUID |
| --- | --- |
| Vendor service | `0000ca77-0000-1000-a435-311b63657f1f` |
| Discovery trigger | `0000ca78-0000-1000-a435-311b63657f1f` |
| PAN address | `0000ca7a-0000-1000-a435-311b63657f1f` |
| CCCD | `00002902-0000-1000-8000-00805f9b34fb` |

The discovery characteristic is written once. The PAN characteristic returns the ODU's IPv4 host as a small UTF-8 payload. A one-byte payload of `0xff` means Bluetooth tethering is off; `0xfe` means the unit is busy. A busy result is reported to the user and never triggers a Bluetooth reset.

The app treats BLE as discovery only. A returned host does not prove that Android has an HTTP route over Bluetooth PAN. If the WebUI does not answer, check Bluetooth tethering in Settings and retry.

## Security and privacy

- ODU credentials and session cookies live in memory for the current session only and are cleared on disconnect or activity destruction.
- The self-signed TLS exception is scoped to the validated ODU host and its same-origin HTTPS redirects. It is not a global trust-all.
- The PAN subnet restriction narrows the destination range. It does not authenticate the ODU or prevent a spoofed device, so confirm the address shown by the app before entering credentials.
- The sticker scanner and paste box parse the Bluetooth address and model in memory. The payload is never displayed, stored, or logged.
- App log events are drawn from a fixed allow-list. No addresses, hostnames, credentials, cookies, activation codes, ICCIDs, or response bodies are recorded. The log stays on the device and is never uploaded.
- `android:allowBackup="false"` keeps app data out of Android backups.

## Known limits

- Firmware varies. Some units need a TLS client identity; some do not expose a WebUI disable switch; some reject the legacy eSIM CGI. The app reports these cases instead of hiding them.
- A reboot, profile delete, or profile switch can drop the ODU's Bluetooth link and data connection. The confirmation dialogs say so.
- Emulator testing can verify UI, permission, and failure states. It cannot verify BLE pairing, the ODU handshake, PAN routing, or WebUI behavior. Test hardware claims on a real, authorized unit.
- This is an unofficial tool that speaks a vendor protocol. Use it only on equipment you own or are authorized to manage, and check the terms that apply to your unit.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). For security issues, follow [SECURITY.md](SECURITY.md) instead of opening a public issue.

## License

Apache License 2.0. See [LICENSE](LICENSE).
