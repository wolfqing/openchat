# Changelog

## Android v0.3.1 (preview) — 2026-10-28

- Bottom bar icons: Nearby is now a three-node mesh, Private a lock. They
  were a house and an envelope.
- Messages from another day show the short local date before the time.
- An unsent draft survives switching between tabs. The panic wipe clears
  drafts too.
- Sender names in chat are plain ink, semibold. Orange is kept for the
  network layer, as the design direction says.
- versionCode 5, versionName 0.3.1. Installs over v0.3.0. 39 unit tests
  pass.
- Release: <https://github.com/wolfqing/openchat/releases/tag/v0.3.1>,
  APK SHA-256 `bccc1cee71f54f28add9d5c55ac03896abd3130e09936a0d20cb40c99e505c40`.
- Not yet checked on a device.

## Android v0.3.0 (preview) — 2026-10-28

- The app now starts in English and in the dark theme. Chinese is still
  one tap away in the language picker, which keeps a "System default"
  option. The dark theme no longer follows the system setting.
- The main colour is now signal orange (`#FF7840`) on near-black, the
  dark palette of the prototype. It replaces the green from v0.1.
- New launcher icon: "OPEN" over "CHAT" in a 5x7 pixel face, orange on
  black, drawn as a vector so it stays sharp. The letters are as large as
  a round mask allows without clipping. It also serves as the Android 13
  themed icon. Preview, as a round launcher shows it:
  [docs/design/app-icon-preview.png](docs/design/app-icon-preview.png).
- Sixteen more languages: Traditional Chinese, Spanish, French, German,
  Portuguese (Brazil), Russian, Ukrainian, Turkish, Arabic and Persian
  (both right to left), Hindi, Indonesian, Vietnamese, Burmese, Japanese
  and Korean, alongside English and Simplified Chinese. The picker lists
  each language in its own script and wraps onto several rows. Phones set
  to Taiwan or Hong Kong Chinese get Traditional. The new translations are
  machine-drafted and need review by native speakers.
- UI review fixes: the system Back button now returns from a private or
  location chat instead of leaving the app; own message bubbles use light
  text (they were orange on brown); text-field borders are visible; the
  language picker is a dropdown rather than nineteen chips above the
  welcome headline; the status band is quiet grey, with the connected
  count in monospaced signal orange, and keeps the red warning colours
  only for missing permission or Bluetooth off.
- versionCode 4, versionName 0.3.0. Installs over v0.2.0. 39 unit tests
  pass.
- Release: <https://github.com/wolfqing/openchat/releases/tag/v0.3.0>,
  APK SHA-256
  `1913787eb285fc41cb60f00713e83ba9db25353d26a4a614aa2ef634cdc74c21`.
- Not yet checked on a device.

## Android v0.2.0 (preview) — 2026-10-28

- Every nickname now carries a `#xxxx` suffix: the first four hex digits
  of the peer ID on the mesh, or the last four of the Nostr pubkey in
  location channels. The suffix is monospaced and muted. A `#xxxx` typed
  at the end of a nickname is stripped, so nobody can pass for another
  peer's suffix.
- English version. English is the fallback language, and devices set to
  Chinese get Chinese. A language picker (System default, 中文, English)
  is on the first screen and in the Me tab. All UI text moved to string
  resources. Notification text follows the choice from the next start of
  the background service.
- versionCode 3, versionName 0.2.0. Installs over v0.1.x. 39 unit tests
  pass.
- Release: <https://github.com/wolfqing/openchat/releases/tag/v0.2.0>,
  APK SHA-256
  `24c489a08487955770ffcfc603e66325a5c5646f9ac5298c118ed86a864827c2`.
- Not yet checked on a device.

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
