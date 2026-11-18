# Contributing to AF Helper

Thanks for looking at the project. Small, focused changes are easiest to review and test.

## Build and test

```sh
./gradlew :bleapp:testDebugUnitTest :bleapp:assembleDebug
```

Use JDK 17+ and Android SDK platform 36. `local.properties` only needs `sdk.dir`; see `local.properties.example` for optional settings. Never commit a real `local.properties`, keystore, client certificate, or credentials.

## What to change

- Keep the app native Java and dependency-light. Avoid new runtime libraries unless they remove more code than they add.
- Keep the package `io.github.wpfyorg.afhelper` and the `:bleapp` module.
- Preserve the existing safety boundaries: address redaction in scans, the fixed PAN address policy, self-signed TLS scoped to the validated ODU host, in-memory credentials and cookies, and the fixed event allow-list in the local log.
- Do not add analytics, telemetry, remote logging, or background uploads. The app must not send data anywhere except the connected ODU.
- Do not log or display Bluetooth addresses, credentials, cookies, activation codes, ICCIDs, BLE payloads, or HTTP response bodies. The validated ODU host is shown only for the user to confirm before signing in.
- Treat BLE as discovery only. Do not claim that a returned host proves an HTTP route exists.

## Pull requests

- Explain the behavior change and the device/firmware it was tested against.
- Include the exact test command and result. Emulator-only verification is fine for UI and failure states, but say so.
- Do not include APKs, captured traffic, ODU logs, or screenshots containing addresses, SSIDs, EIDs, ICCIDs, or credentials.
- One logical change per commit is preferred.

## Reporting bugs

Open a GitHub issue with:

- app version and Android version,
- ODU model and firmware/software version,
- a short description of what happened,
- the in-app log if it is relevant (it contains only fixed event names).

Redact anything that identifies your unit or account. For security problems, follow [SECURITY.md](SECURITY.md) instead.
