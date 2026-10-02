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
