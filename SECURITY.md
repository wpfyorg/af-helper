# Security policy

AF Helper talks to a local ODU over Bluetooth and a private Bluetooth PAN. It is not a hosted service, but reports about credential handling, TLS scope, address validation, or data exposure are welcome.

## Report privately

Use GitHub's **Security → Report a vulnerability** flow for this repository. Do not open a public issue for a suspected vulnerability.

Please include:

- the affected app version,
- the Android version and ODU model/firmware,
- a description of the issue and its impact,
- reproduction steps or a proof of concept that does not contain real credentials, addresses, or customer data.

You will get an acknowledgement when the report is reviewed. Please allow time for a fix before public disclosure.

## Scope

In scope:

- credential or cookie persistence or exposure,
- TLS verification broader than the validated ODU host,
- PAN/host validation bypasses,
- logging of identifiers, payloads, or user-entered data,
- unintended outbound network traffic.

Out of scope:

- vulnerabilities in the ODU firmware or the vendor WebUI itself,
- attacks that require a rooted phone or physical access to an unlocked device,
- reports that rely on the device being spoofed before the user confirms the address shown by the app (the app documents that the subnet check is not device authentication).
