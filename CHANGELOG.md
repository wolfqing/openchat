# Changelog

## UI direction and prototype — 2026-10-28

- Set the UI direction in [docs/design/ui-direction.md](docs/design/ui-direction.md).
  The distinctive part is that the mesh is visible: a signal band, a
  delivery path on each message, hop counts, identity emblems and a
  designed "nobody nearby" screen. Message text stays plain and readable.
- Added a clickable three-screen prototype,
  [docs/design/ui-prototype.html](docs/design/ui-prototype.html), with
  light and dark themes. It is published privately at
  <https://claude.ai/artifact/QBFVpgvNTgxqF1RhRwDG18>.
- The prototype has a Chinese/English switch. Sample nicknames are
  historical figures (张衡, 李白, 苏轼; Lovelace, Darwin, Curie), so none
  of them reads as a real user. Every name carries a `#xxxx` suffix
  (the first four hex digits of the peer ID), which tells duplicate
  names apart.
- No app code changed. Only the signal band, delivery path and empty
  state are planned before device testing.

## Android v0.1.1 (preview) — 2026-10-28

- Fix the API 33+ checks on BLE write and notify results. They compared
  against `BluetoothGatt.GATT_SUCCESS` instead of
  `BluetoothStatusCodes.SUCCESS`. Both are 0, so behaviour is unchanged.
  Lint now reports no errors.
- versionCode 2, versionName 0.1.1. Installs over v0.1.0.
- Release: <https://github.com/wolfqing/openchat/releases/tag/v0.1.1>,
  commit `d758e2f`, APK SHA-256
  `46a38e68c5486342b08fdd2708ee7e9acdbd3817b7d4a3bfc8dcb798d52048ad`.
- Merged to `main` in PR #1 (merge commit `727a275`).

## Android v0.1.0 (preview) — 2026-10-28

First Android build, written from scratch in Kotlin with no bitchat code.

- BLE mesh: every phone is peripheral and central, with multi-hop relay,
  dedup, TTL and fragmentation per [docs/protocol-spec.md](docs/protocol-spec.md).
- Keys are generated on first launch. No account and no phone number.
- Signed public "nearby" channel.
- Noise XX private messages with delivered and read receipts.
- Geohash location channels over Nostr, with one anonymous key per cell.
- A triple tap on the title wipes everything at once and creates a new
  identity. There is no confirmation.
- The APK can be shared offline by Bluetooth or Quick Share.
- 32 unit tests pass. They include the cacophony Noise XX vector, every
  BIP-340 vector and a three-phone mesh simulation.
- Release: <https://github.com/wolfqing/openchat/releases/tag/v0.1.0>,
  commit `62ab166`.

Not yet verified on devices or against stock bitchat. See
[android/README.md](android/README.md) for known risks.

## Phase 0 — 2026-10-27

- Classified 183 App Store reviews and 764 upstream issues. UX is about 19%
  of complaints, which met the roadmap's first kill condition. The owner
  decided to continue. See [docs/phase0-findings.md](docs/phase0-findings.md).
- Corrected the earlier claim that bitchat was never audited. Cure53
  audited it in 2025-11.
