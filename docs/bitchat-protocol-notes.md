# bitchat protocol notes

Working notes on what the upstream protocol does on the wire, taken from
`WHITEPAPER.md`, `docs/` and the v1.8.0 release notes of
[permissionlesstech/bitchat](https://github.com/permissionlesstech/bitchat)
as of 2026-10-27. Numbers are upstream's; verify against the current
whitepaper before relying on them. openchat must stay compatible with all of
this.

## 1. Transports and routing

Two transports sit behind one `Transport` interface and are coordinated by a
`MessageRouter`:

| Transport | When | Carries |
|---|---|---|
| BLE mesh | offline, peers in radio range | public broadcasts, Noise sessions, courier envelopes |
| Nostr | internet available | private messages to mutual favorites, geohash location channels; optionally over Tor |

Direct messages try, in order: live BLE link with an established Noise
session, then Nostr, then a local queue until a transport appears.

## 2. BLE packet format

- Header: version, type, TTL, timestamp, flags.
- Body: 8-byte sender ID, optional 8-byte recipient ID, payload, optional
  Ed25519 signature.
- Version 2 packets may carry an explicit source route.
- Signatures cover everything except the TTL byte so relays can decrement
  TTL without breaking them.
- Padding to 256/512/1024/2048-byte buckets applies only to
  `noiseEncrypted` and `noiseHandshake` packets. Frames needing more than
  255 pad bytes go out unpadded. Public messages, announces, fragments,
  files and voice go out at natural length.

## 3. Flooding, TTL and routing

- Initial TTL 7. Nodes with ≥ 6 links cap broadcast TTL at 5; nodes with
  ≤ 2 links relay at full incoming depth. Directed traffic relays with
  TTL − 1.
- Dedup: LRU seen-set of 1000 entries, 5-minute expiry, keyed by sender,
  timestamp, type and payload digest. A pending relay is cancelled when a
  duplicate arrives first.
- Relay jitter 10–220 ms, wider when dense.
- Broadcast fanout: deterministic, message-ID-seeded subset of about
  log₂(degree) links. Announces, fragments and sync packets use full
  fanout. The ingress link is always excluded.
- Announces carry up to 10 direct-neighbor IDs; each node keeps a topology
  map with 60 s freshness. Packets are source-routed along a bidirectionally
  confirmed path when one exists, falling back to flooding.

## 4. Fragmentation

Packets above the link MTU split into ~469-byte fragments with an 8-byte
fragment ID and index/total header. Fragments relay independently and
reassemble per node: 128 concurrent assemblies, 30 s timeout, 1 MiB cap.

## 5. Presence

- Signed announces every 4 s while isolated, backing off to ~15–30 s
  (jittered) when connected.
- A verified announce keeps a peer "reachable" for 60 s after last contact.
- Connection scheduling is RSSI-gated with duty-cycled scanning.

## 6. Identity

Two long-term keys in the Keychain:

| Key | Use | Derived identity |
|---|---|---|
| Curve25519 static | Noise handshakes | SHA-256 fingerprint is the stable identity |
| Ed25519 | packet signatures | published in announces |

- Peer ID = first 8 bytes of SHA-256(Noise static public key). Stable across
  sessions, reboots and keychain-preserving reinstalls; only a panic wipe
  changes it. This derivation is also what makes peer IDs unforgeable:
  announces whose claimed peer ID does not match the derived one are
  rejected, and handshakes check the same binding.
- Announces publish nickname, Noise static public key and Ed25519 public key
  in cleartext.
- Favorites pin the full Noise public key; mutual favorites also exchange
  Nostr public keys. Optional in-person QR verification binds a nickname to
  a fingerprint.

## 7. Encryption

| Path | Construction | Forward secrecy |
|---|---|---|
| Live BLE session | Noise `XX`, Curve25519 + ChaCha20-Poly1305 + SHA-256; sessions rekey on absolute age since v1.8.0 | yes |
| Offline seal (courier mail) | Noise `X` sealed to recipient static key, sender authenticated inside ciphertext | **no** (prekeys are future work) |
| Nostr private envelope | kind 14 rumor → kind 13 seal → kind 1059 envelope signed by a one-time key; `v2:` + base64url(24-byte nonce ‖ XChaCha20-Poly1305 ciphertext ‖ 16-byte tag); secp256k1 ECDH + HKDF-SHA256; seal and envelope timestamps randomized ±15 min | **no** |

The Nostr envelope is **not** NIP-17, NIP-44 or NIP-59 compatible; it
interoperates only with bitchat clients. The outer `p` tag exposes the
recipient's Nostr public key to relays.

Channel passwords on Android use Argon2id + AES-256-GCM.

## 8. Store-and-forward

**Sender outbox**: 100 messages per peer, 24 h TTL, resend on reconnect,
8-attempt cap, persisted sealed with a ChaChaPoly key in the Keychain.

**Couriers**:

- Envelopes are handed to up to 3 connected couriers. The recipient tag is a
  16-byte HMAC of the recipient's static key and the UTC day, so tags rotate
  daily.
- Quotas: favorites deposit 5 envelopes each; verified-announce peers 2.
  Verified-tier pool capped at 20 of 40 slots. Envelopes are limited to
  16 KiB and 24 h; overflow evicts the oldest verified-tier mail first.
- Spray-and-wait: initial copy budget 4, cap 8; a courier hands half its
  remaining budget to another eligible courier it meets.
- Handover: on a verified direct announce, envelopes deliver over the live
  link and are removed; on a verified relayed announce, a directed copy
  floods toward the recipient, one attempt per envelope per 10 minutes.
- Couriers carry text only; media is never couriered.

**Gossip sync (public history)**: 1000-packet cache reconciled about every
15 s with compact GCS filters, 6-hour window, persisted to disk. Fragments
and file transfers keep a 15-minute window.

**Nostr mailboxes**: clients re-subscribe with a 24-hour lookback on
reconnect.

## 9. Location channels

Geohash-scoped public rooms over Nostr relays (440+ relays), with a fresh
ephemeral identity per geohash area:

| Level | Geohash length |
|---|---|
| block | 7 |
| neighborhood | 6 |
| city | 5 |
| province | 4 |
| region | 2 |

## 10. Privacy boundaries stated by upstream

The whitepaper calls metadata "the weakest part of the design":

- The 8-byte sender ID derives from a key that never rotates.
- Announces publish static keys, nicknames and up to 10 neighbor IDs in
  cleartext, so a passive BLE listener can enumerate participants, follow a
  device across days and cities, and reconstruct local adjacency.
- Origin packets use default TTL, so hop distance can reveal the originator.
- No forward secrecy for courier seals or Nostr envelopes.
- Media is written to disk unsealed, protected only by platform data
  protection.
- "Unlinkable presence is not a property this protocol currently provides."

`docs/PEER-ID-ROTATION.md` (status: draft) implements epoch-based ID
derivation, recognition tags and an `announceV2 = 0x2C` wire format in
`BitFoundation`, with test vectors, but nothing is wired into the shipping
mesh: no client emits v2 announces and `BLEService` parses and ignores them.
It requires iOS and Android to switch together. The doc's own correction:
rotating the peer ID without encrypting the announce accomplishes nothing.

**Implication for openchat**: when upstream ships rotation, a client that
does not follow drops out of the network. Track that document.

## 11. Security history

- 2025-07: Alex Radocea demonstrated impersonation against the Favorites
  system shortly after the TestFlight release. Upstream added a "no
  external security review, do not rely on it" warning to the README. That
  warning is gone from the iOS README as of 2026-10-28.
- 2025-11: Cure53 audit "BCH-01". The report itself is not in either repo;
  the Android maintainer filed nine findings from it on 2026-01-14
  (bitchat-android #587–#595), three rated High. Still open on 2026-10-28:
  #589 nickname impersonation (High; the iOS side refuses ambiguous
  nicknames since v1.8.0, the Android fix is PR #911) and #593 announce
  replay within a ±1 h window (Medium). An earlier version of these notes
  said no third-party audit existed; that was wrong.
- v1.8.0 (2026-10-23) hardening: session rekey on absolute age, fail-closed
  on unverifiable handshake peer IDs, timestamp sanity windows for QR codes,
  Nostr DM rumors and sync replies, per-type payload caps, byte-bounded sync
  stores, private-group creator pinning, mesh peer ID kept out of geohash
  DMs, ambiguous nicknames refused, public-channel screenshot broadcast
  removed, privacy claims corrected.
- Every tagged release ships a `SOURCE-MANIFEST.txt`; see upstream
  `docs/VERIFYING-A-BUILD.md`. The upstream README warns that the repository
  has been the target of takedown demands and that unverifiable mirrors may
  appear.

## 12. Platform facts

| | iOS / macOS | Android |
|---|---|---|
| Repo | permissionlesstech/bitchat | permissionlesstech/bitchat-android |
| Language / UI | Swift, SwiftUI | Kotlin, Jetpack Compose (Material 3), MVVM |
| License | Unlicense (public domain) | GPL-3.0 (LICENSE.md is the full GPL-3.0 text; the README's public-domain claim and Wikipedia's MIT are wrong) |
| Transports | BLE | BLE + Wi-Fi Aware (`UnifiedMeshService`) |
| Tor | yes | built-in via Arti |
| Min version | — | API 26 (Android 8.0) |
| Localization | all 30 UI languages as of v1.8.0 | — |
| Build | `xcodebuild` / `just`; `swift test` | `./gradlew assembleDebug`; reproducible release builds in a pinned Linux container |
| Interop | binary-compatible with Android | binary-compatible with iOS/macOS |
| Offline distribution | — | APK sharing via hotspot / Nearby Share since 2026-07-28 |

Upstream iOS source layout worth keeping untouched in a fork: `Noise/`,
`Protocols/`, `Sync/`, `Nostr/`, `Identity/`, `Services/`, and the
`localPackages/BitFoundation` package. Test vectors:
`docs/courier-test-vectors.json`.

Other compatible clients that prove the protocol is implementable outside
Swift: bitchat-terminal and bitchat-tui (Rust), bitchat-python, and bitle, an
ESP32-C3 solar relay node.

## 13. Upstream changes since these notes were written

Checked 2026-10-28 against the GitHub API. Nothing below changes the wire
protocol; `docs/PEER-ID-ROTATION.md` is still "Draft for cross-platform
review", implemented in `BitFoundation` and not emitted by any client.

**iOS** (latest release still v1.8.0, 2026-10-23): one post-release commit,
"Port the Android globe geohash picker to iOS" (#1807, 2026-10-25). Source
tree confirmed: `bitchat/{Identity,Noise,Nostr,Protocols,Services,Sync}` and
`localPackages/{Arti,BitFoundation,BitLogger}` exist as the roadmap assumes.

**Android**: three releases in six days.

| Tag | Date | What changed |
|---|---|---|
| v2.0.3 | 2026-10-22 | (release notes not retrieved) |
| v2.1.0 | 2026-10-26 | Spam filtering on by default for public mesh and geohash chats; "improved onboarding layouts"; Basque; app data excluded from backup/device transfer; fragment and QR-timestamp validation; recipient-less Nostr envelopes no longer leak the mesh identity; updated bundled relays. Physical-device and cross-client tests were explicitly waived for this release. |
| v2.2.0 | 2026-10-28 | Redesigned globe for discovering geohash channels, participant counts, smoother map; Wear OS 0.1.7 screen refresh. |

**Reading for openchat**: upstream's current UX energy is going into
geohash discovery (the globe, on both platforms) and Wear OS, not into
first-run onboarding for the offline mesh case. "Improved onboarding
layouts" in v2.1.0 is a layout pass, not the rebuild that the roadmap's
kill condition describes. The spam filter being default-on in public
channels is new behaviour a compatible client should match or users will
see different timelines on different phones.

**Article correction**: the 深潮 TechFlow piece that circulated on
2026-10-27 says the Android licence is MIT. It is GPL-3.0 (see §12).
