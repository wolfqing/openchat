<div align="center">

<img src="docs/design/app-icon-preview.png" alt="openchat icon" width="96" height="96">

# openchat

**Offline-first mesh messenger for blackouts, disasters and crowded places.**
Bluetooth LE mesh when there is no network. Nostr when there is.
No accounts, no phone numbers, no servers.

[![License: MIT](https://img.shields.io/github/license/wolfqing/openchat?color=2b2b2b)](LICENSE)
[![Latest release](https://img.shields.io/github/v/release/wolfqing/openchat?include_prereleases&label=release&color=FF7840)](https://github.com/wolfqing/openchat/releases)
[![Android CI](https://github.com/wolfqing/openchat/actions/workflows/android.yml/badge.svg)](https://github.com/wolfqing/openchat/actions/workflows/android.yml)
[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](android/README.md)
[![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white)](android/)
[![Protocol: bitchat-compatible](https://img.shields.io/badge/protocol-bitchat%20compatible-blue)](docs/protocol-spec.md)
[![Status: preview](https://img.shields.io/badge/status-preview-yellow)](#status-honestly)

[**Download APK**](https://github.com/wolfqing/openchat/releases/latest) ·
[Protocol spec](docs/protocol-spec.md) ·
[Roadmap](docs/roadmap.md) ·
[Contributing](CONTRIBUTING.md) ·
[Security](SECURITY.md) ·
[中文说明](README.zh-CN.md)

<img src="docs/design/prototype-three-screens.png" alt="Three screens: the nearby public channel with hop counts, an encrypted private chat with a delivery path, and the 'nobody nearby yet' radar" width="100%">

<sub>Design prototype in dark mode. The app implements this direction.</sub>

</div>

---

## Why this exists

When the network goes down, the phones in a building, a stadium or a
neighbourhood can still talk to each other over Bluetooth. Every phone
relays for every other phone, so a message hops from person to person
until it reaches someone it was meant for. That is what the
[bitchat](https://github.com/permissionlesstech/bitchat) protocol does,
and its protocol, code and whitepaper are public domain.

openchat is an **independent, MIT-licensed client for that same
network**, built with three differences in mind:

- **For people who have never seen IRC.** The people who install a mesh
  messenger during an outage are not terminal people. Three taps from
  install to first message, with the phone offline, is the bar. The mesh
  itself is made visible: a signal band, a delivery path on every
  message, hop counts, identity emblems, and a designed "nobody nearby"
  screen instead of an empty list.
- **Positioned for infrastructure failure, not politics.** Earthquakes,
  typhoons, carrier outages, festival and stadium congestion, hiking and
  sailing. It is not a censorship-circumvention tool and is not marketed
  as one.
- **Protocol compatible, always.** An openchat phone must be a working
  relay for every bitchat phone and vice versa. Interop with upstream is
  a release gate, not a feature.

## Status, honestly

| | State |
|---|---|
| Android app | **v0.5.0 preview.** Builds, 94 unit tests pass, 9.5 MB APK, 18 languages |
| Interop with stock bitchat | **Not yet verified.** Implemented from the spec and upstream test vectors; verification against a stock bitchat client is the next milestone |
| Security audit | **None.** Everything on the wire is upstream's; upstream had one audit (Cure53, 2025-11) with findings still open |
| iOS | Not started |
| Release signing | Previews are signed with a debug key. Verify the SHA-256 in the release notes |

Do not rely on openchat where being identified has serious consequences.
The app says so on first launch. See [SECURITY.md](SECURITY.md).

## What it does

**On the mesh, with no network at all**

- Bluetooth LE mesh: every phone is both peripheral and central, with
  multi-hop relay, dedup, TTL and fragmentation per the
  [wire spec](docs/protocol-spec.md).
- A signed public **Nearby** channel that everyone in range can read.
- **Private messages** end-to-end encrypted with Noise XX, with delivered
  and read receipts. Relaying phones cannot read them.
- **Store-and-forward**: an outbox plus courier envelopes that nearby
  phones carry for you, so a message can reach someone who was not in
  range when you sent it.
- **History sync**: late joiners receive the last 15 minutes of public
  messages from a neighbour.
- **Photos and voice notes**, public or Noise-encrypted private.
- In-person **verification** with bitchat's `bitchat://verify` QR code
  plus a Noise challenge.
- Every nickname carries a `#xxxx` suffix derived from the peer ID, so
  two people with the same name cannot be confused.

**When there is internet**

- **Location channels** over Nostr, keyed by geohash, with one anonymous
  key per cell. Same relays as bitchat, so the rooms are shared.
- Private messages over Nostr between mutual favorites, in bitchat's
  envelope format.
- Relay traffic goes **through Tor by default, fail-closed**.

**Safety and everyday use**

- No account, no phone number, no email. Keys are generated on first
  launch.
- **Triple-tap the title** to wipe everything and start with a new
  identity. No confirmation dialog, by design.
- **Share the app offline**: send the APK to the phone next to you over
  Bluetooth or Quick Share. No app store needed, which matters when the
  app store is unreachable.
- Keeps relaying with the screen off. Block people. @mentions. IRC-style
  slash commands for those who want them.
- 18 languages, including right-to-left Arabic and Persian. Machine
  drafted; native speakers welcome.

Full feature-to-file map in [android/README.md](android/README.md).

## Screenshots

<table>
<tr>
<td align="center"><img src="docs/design/prototype-nearby.png" alt="Nearby public channel: a signal band with direct and reachable counts, messages with hop counts and identity emblems" width="240"><br><sub>Nearby: direct 3, reach 11, every message shows its hops</sub></td>
<td align="center"><img src="docs/design/prototype-private.png" alt="Encrypted private chat with a four-dot delivery path: sent, relayed, delivered, read" width="240"><br><sub>Private: a delivery path on every message, red with a retry when it fails</sub></td>
<td align="center"><img src="docs/design/prototype-empty.png" alt="Nobody nearby yet: a radar and three concrete things to do next" width="240"><br><sub>Nobody nearby: the most common state, so it gets a real screen</sub></td>
</tr>
</table>

<sub>From the clickable prototype in
[docs/design/ui-prototype.html](docs/design/ui-prototype.html); the
design rationale is in [docs/design/ui-direction.md](docs/design/ui-direction.md).
Sample nicknames are historical figures.</sub>

## Install

1. Download the APK from the
   [latest release](https://github.com/wolfqing/openchat/releases/latest).
   Android 8.0 or newer.
2. Check the hash against the release notes before installing, especially
   if the file reached you phone to phone:

   ```bash
   shasum -a 256 openchat-*.apk
   ```

3. Grant Bluetooth and nearby-devices permission. Android requires the
   location permission for BLE scanning; openchat does not read your
   location for the mesh.
4. Open the **Me** tab and tap **Share the app** to pass the installer to
   the phones around you. Do this *before* the outage.

## How it relates to bitchat

| | bitchat | openchat |
|---|---|---|
| Wire protocol | bitchat's | **Identical.** Same packets, same Noise handshake, same Nostr envelopes |
| Code | iOS: public domain. Android: GPL-3.0 | Written from scratch in Kotlin. **No bitchat source is used.** The GPL Android code was never read |
| Licence | Unlicense / GPL-3.0 | MIT |
| Who it is for | "IRC vibes", by design | People who have never seen IRC |
| Positioning | General purpose | Infrastructure failure |
| Relationship | — | Independent client. Not affiliated with or endorsed by the bitchat project |

The protocol was reverse-documented from the public-domain iOS source
into [docs/protocol-spec.md](docs/protocol-spec.md), a byte-level
specification that did not exist before. If you are building anything
that talks to bitchat, that document and
[docs/bitchat-protocol-notes.md](docs/bitchat-protocol-notes.md) may be
useful on their own.

## Documentation

| Document | What it is |
|---|---|
| [docs/protocol-spec.md](docs/protocol-spec.md) | Byte-level wire spec openchat implements: headers, TLVs, Noise XX, courier envelopes, sync, Nostr embedding |
| [docs/bitchat-protocol-notes.md](docs/bitchat-protocol-notes.md) | What the upstream protocol actually does, with numbers, audit history and open issues |
| [docs/phase0-findings.md](docs/phase0-findings.md) | 183 App Store reviews and 764 upstream issues classified; the go/no-go that started this |
| [docs/design/ui-direction.md](docs/design/ui-direction.md) | The UI direction: two layers, five elements, why the mesh is visible |
| [docs/roadmap.md](docs/roadmap.md) | Phases, exit criteria and the conditions under which this project stops |
| [CHANGELOG.md](CHANGELOG.md) | Every release, with commit and APK hash |

## Build from source

Requires JDK 17 and the Android SDK with platform 35.

```bash
cd android
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

The debug APK lands in `app/build/outputs/apk/debug/`. Release previews
are built with `assembleRelease` (R8, ARM only, about 9.5 MB).

## Contributing

Issues, interop reports, translations and field notes are all welcome.
Three rules are firm, and [CONTRIBUTING.md](CONTRIBUTING.md) explains
why:

1. **Clean room.** If you have read bitchat-android source code, you
   cannot contribute Kotlin here. The MIT licence depends on it.
2. **Interop is a release gate.** Nothing ships that breaks compatibility
   with a stock bitchat phone.
3. **Positioning is not negotiable.** No circumvention features or
   marketing, no security claims that have not been audited.

Commits are signed off under the
[Developer Certificate of Origin](https://developercertificate.org/).
There is no CLA.

## Roadmap in one line

Android first, from scratch, against the spec. Then interop verification
against stock bitchat, then iOS, then one real pilot in a
disaster-preparedness drill or a crowded venue. Each phase has an exit
criterion and a kill condition in [docs/roadmap.md](docs/roadmap.md).

## Name

"openchat" is a working name and collides with several unrelated LLM
projects on GitHub. A final name is pending; the repository will redirect
when it changes.

## License

MIT for everything in this repository, see [LICENSE](LICENSE). No
upstream code is included. Third-party components and their licences
are listed in [NOTICE](NOTICE).

## Acknowledgements

Built on the work of the bitchat contributors, whose decision to put the
protocol in the public domain made an independent client possible. Not
affiliated with or endorsed by them.

<div align="center">
<sub>If a mesh messenger that ordinary people can use during an outage sounds worth having, a ⭐ helps others find it.</sub>
</div>
