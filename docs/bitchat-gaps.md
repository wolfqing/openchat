# What bitchat has that openchat does not

Survey of 2026-11-03, rechecked against `main` on 2026-11-11 (Android
v0.7.2). Sources: the public-domain bitchat iOS source and its pull
requests, and the bitchat-android **release notes and PR descriptions
only**. The GPL-3.0 Android source was not read (see
[CONTRIBUTING.md](../CONTRIBUTING.md)). Upstream PR numbers are given so a
later reader can find what was looked at; anything built from this list is
written from our own spec, not from that code.

## Open, ranked

| # | Gap | Upstream | Why it matters | Size |
|---|-----|----------|----------------|------|
| 1 | **Six-hour public history window**, kept on disk, with a "heard here earlier" marker | iOS #1372, #1409 | Interop: our `HISTORY_MAX_AGE_MS` is 900 s (`MeshEngine.kt`), so a sync reply from an iPhone carrying messages 15 min – 6 h old is dropped as stale. Check against a stock phone before anything else on this list. | M |
| 2 | **Content spam filter**: link-heavy, near-duplicate and copied messages hidden | Android v2.1.0 (#999) | We limit by rate only (spec §26). Without it the same room looks different on our phones and theirs. | M |
| 3 | **Local petnames**: a name you give someone, shown only to you | iOS #1507 | Small; helps tell apart people who all call themselves `anon`. | S |
| 4 | **One-tap location channel from the system region**, no location permission | iOS #1444 | Small; lowers the permission ask for the Location tab. | S |
| 5 | **Tor blocked banner** | iOS #1486, #1597 | Today a blocked Tor leaves location messages "sending" with no explanation. | S |
| 6 | **Originating source routing**: we pick the path for our own private messages | iOS #1378, Android #445 | We relay source-routed packets but never originate them. Do after the field interop test. | L |
| 7 | Private chat history kept across restarts (opt-in) | Android #806, #815 | iOS keeps it in memory only on purpose; if added, off by default. | M |
| 8 | Themes and chat styles (light theme first) | iOS #1337, Android #846 | Cosmetic. | M |
| 9 | Import from the system share sheet (text, photos) | iOS #1430 | Convenience. | S |
| 10 | Offline place names for geohashes | Android #611, iOS #1044 | Readability of the Location tab; needs a bundled gazetteer. | M |
| 11 | In-app update check and APK download | Android #812 | We ship through GitHub releases; needs a signature check before install. | M |
| 12 | Wi-Fi Aware transport | Android #711, #712 | Range and throughput; large, hardware-dependent. | L |
| 13 | Wear OS client | Android | Out of scope for now. | L |
| 14 | macOS client | iOS app | iOS client comes first (roadmap). | L |

## Closed since the survey

- **"Nobody nearby" screen with a radar** (iOS #1409): v0.7.0, radar with
  10 / 30 / 50 m rings and three cards; cheaper sweep in v0.7.2.
- **Mesh made visible** (part of the topology view, #1377): v0.7.0 signal
  band, hop counts, delivery path. A full graph of the mesh is not built.
- **3D globe picker for location channels**: v0.6.1, stepped zoom in
  v0.7.0, readable labels in v0.7.1.

## What openchat has that bitchat does not

- Triple-tap wipe with no confirmation (both bitchat apps now confirm).
- Tor guard rotation on wipe: a fresh identity shares no circuit with the old one.
- Notifications show nothing by default: no name, place or text.
- Incoming photos are pixelated until tapped.
- `/pay` outside a private chat needs an explicit `public`.
- Over Nostr, only an already-bound npub may speak for a mesh identity.
