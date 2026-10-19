# AdShield Android

AdShield is a small Android ad/tracker blocker built around Android's `VpnService`.

## MVP features

- No root required.
- One large on/off button.
- Local DNS-only VPN route: normal app traffic is **not** sent through a remote VPN.
- Built-in domain blocklist with parent-domain matching.
- Blocked/processed DNS counters.
- Persistent foreground notification while protection is active.
- GitHub Actions builds a debug APK on every push to `main`.

## How it works

AdShield configures a virtual DNS address (`10.111.222.2`) through `VpnService` and routes only that address into the local TUN interface.

1. Android sends normal DNS queries to the virtual DNS address.
2. AdShield reads those DNS packets locally.
3. A blocked domain receives an NXDOMAIN response.
4. Other queries are forwarded through a protected UDP socket to Cloudflare `1.1.1.1`.
5. The DNS response is written back to the local TUN interface.

This means AdShield does **not** proxy regular web/app traffic.

## Current limitations

- DNS-over-HTTPS, DNS-over-QUIC, or hard-coded encrypted DNS inside an app may bypass this MVP.
- YouTube in-app ads generally cannot be blocked reliably using domain filtering alone.
- The starter blocklist is intentionally small; remote list updates and per-app allowlists are planned.
- The first release handles standard IPv4 UDP DNS requests. TCP DNS and encrypted DNS are not intercepted.

## Build

The repository uses:

- Android Gradle Plugin 9.4.0
- Gradle 9.6.0
- JDK 17
- compile/target SDK 36
- min SDK 26

### GitHub Actions

Open **Actions → Android APK**, run the workflow manually, or push to `main`.
After a successful run, download the artifact named **adshield-debug-apk**.

### Android Studio

Open the repository as an Android project and build the `app` module.

## Privacy

The app has no analytics SDK and no account system. Non-blocked DNS requests are currently forwarded to Cloudflare `1.1.1.1`; a future version can add selectable upstream resolvers and local/remote blocklist management.

## License

Apache-2.0.


## Aggressive filter mode (v0.3)

AdShield can download and combine a large DNS filtering profile designed for strong blocking on Android:

- HaGeZi Multi ULTIMATE (aggressive)
- HaGeZi TIF Mini for malware/phishing/security coverage
- HaGeZi encrypted DNS (DoH) bypass domains
- hostsVN for Vietnamese ad/tracker coverage
- ABPVN ad domains
- HaGeZi Pop-Up Ads as a separate detection list for popup/redirect warnings

The downloaded lists are cached locally and refreshed after 24 hours when the app is opened. A failed update does not replace the previously working files. Users can always add an allow rule for false positives.

This remains DNS-level filtering. It cannot remove same-origin HTML/CSS ad containers or reliably block JavaScript popups served from the same domain as the website.


## HTTPS filtering (v1.0)

AdShield no longer includes an embedded protected WebView/browser.

Optional HTTPS filtering now uses:

- the existing local `VpnService`
- Android's VPN HTTP proxy hint (`VpnService.Builder.setHttpProxy`) on Android 10+
- a local proxy bound to `127.0.0.1:8877`
- a per-install **AdShield Personal CA**
- on-device TLS certificates generated for requested hosts
- request host blocking plus HTML cosmetic/script injection for compatible HTTP/1.1 pages

HTTPS filtering is **off by default**.

### CA installation

On Android 11+ an ordinary app cannot silently install a CA certificate. AdShield therefore generates the CA privately, exports only the public `.crt` file through Android's Storage Access Framework, and asks the user to install that certificate manually in Android Settings.

The CA private key is never exported from the app's private data directory.

Typical setup:

1. Open **Lọc HTTPS toàn hệ thống** in AdShield.
2. Tap **Tạo và xuất chứng chỉ CA (.crt)**.
3. Save `adshield-ca.crt`.
4. Open Android Settings and install it under the **CA certificate** installation flow.
5. Return to AdShield and enable **HTTPS filtering**.
6. Restart protection if needed.

### Important limitations and safety

- `setHttpProxy` is a proxy recommendation exposed by the VPN network; apps are free to ignore it.
- Apps that do not trust user-installed CAs, use certificate pinning, use a private network stack, or avoid the system proxy may not be filterable.
- Banking/payment/security-sensitive apps should normally be placed in **Ứng dụng bỏ qua AdShield**.
- HTTPS filtering decrypts compatible traffic **locally on the device** so filtering can be applied. AdShield does not send decrypted content to a remote server.
- HTTP/2, HTTP/3/QUIC, uncommon transfer encodings, and highly customized app network stacks are not fully covered by this first implementation.
- DNS filtering remains active independently of HTTPS filtering.

## Local-network compatibility (v0.9+)

AdShield can keep TV remotes, Chromecast, printers, and smart-home apps working by:

- forwarding `.local`, `.lan`, `home.arpa`, and related local names to the current Wi-Fi/router DNS when possible
- allowing selected applications to bypass the VPN completely via `addDisallowedApplication()`
