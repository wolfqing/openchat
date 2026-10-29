# openchat for Android

Status: **v0.1.1 preview.** Builds and passes its unit tests. Not yet tested
on physical devices, and not yet tested against a stock bitchat phone.

Written from scratch in Kotlin. No bitchat source code is used. The wire
format comes from [docs/protocol-spec.md](../docs/protocol-spec.md), which
was written by reading the public-domain bitchat iOS source; the GPL-3.0
bitchat-android source was never read.

## Features

| Feature | Where |
|---|---|
| BLE mesh, every phone is both peripheral and central, multi-hop relay | `mesh/BleTransport.kt`, `mesh/MeshEngine.kt`, `core/Relay.kt` |
| No account, no phone number: keys are generated on first launch | `mesh/Identity.kt`, `app/IdentityStore.kt` |
| Signed public "nearby" channel, long messages fragmented | `core/Codec.kt`, `core/Fragments.kt` |
| Noise XX encrypted private messages with delivered and read receipts | `crypto/Noise.kt`, `mesh/MeshEngine.kt` |
| Geohash location channels over Nostr, one anonymous key per cell | `nostr/`, `app/GeoChannels.kt` |
| Triple-tap the title: instant wipe, new identity, no confirmation | `ui/Screens.kt` `PanicTitle`, `app/MeshService.kt` `panic()` |
| Share the APK offline by Bluetooth or Quick Share, no app store | `app/ApkShare.kt` |
| Mesh keeps running with the screen off (foreground service) | `app/MeshService.kt` |
| Store-and-forward: outbox plus courier envelopes carried by nearby phones (spec §13) | `core/Courier.kt`, `crypto/Noise.kt` `NoiseX`, `mesh/StoreAndForward.kt`, `mesh/MeshEngine.kt` |
| History sync: late joiners get the last 15 min of public messages from a neighbour via GCS-filtered `requestSync` (spec §14) | `core/Sync.kt`, `mesh/MeshEngine.kt` |
| Block people on the mesh and per location channel; tap a name for the person card | `app/Social.kt`, `ui/People.kt` |
| @mentions: highlighted, notified, autocompleted | `core/Mentions.kt` |
| In-person verification with bitchat's `bitchat://verify` QR plus a Noise challenge (spec §16) | `core/Verification.kt`, `ui/People.kt` |
| IRC-style commands: `/msg /who /clear /hug /slap /block /unblock /fav /unfav /drop /help` | `app/Commands.kt` |
| Favorites, and private messages over Nostr between mutual favorites in bitchat's envelope (spec §15) | `nostr/PrivateEnvelope.kt`, `core/NostrEmbedded.kt`, `app/NostrDirect.kt` |
| Location notes that fade after 24 h (spec §17) | `app/GeoChannels.kt` |
| Private messages in location channels, between per-area personas (spec §15.5) | `app/NostrDirect.kt`, `app/MeshService.kt` |
| Up to 8 extra relays of your own for private messages | `app/NostrDirect.kt`, `ui/People.kt` |
| Photos and voice notes, public or Noise-encrypted private (spec §18) | `core/FilePacket.kt`, `app/Media.kt` |
| Relay traffic through Tor by default, fail-closed (spec §19) | `nostr/NetRoute.kt`, `app/Tor.kt` |

Not yet: groups, boards, live push-to-talk, files other than images and
audio, private media over Nostr, Tor bridges. Peer ID rotation exists only
as tested code, as upstream's draft does (spec §20). See spec §12.

## Build

Requires JDK 17 and the Android SDK (platform 35).

```bash
cd android
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`. Previews are
published from `./gradlew :app:assembleRelease` (R8-shrunk, ARM only, about
9.5 MB, signed with the local debug key). minSdk 26
(Android 8.0), targetSdk 35.

## Tests

| Suite | What it checks |
|---|---|
| `CodecTest` | header layout, flags, v2 routes, raw-DEFLATE compression, Noise-only padding, signing bytes, TLVs, stream framing, fragments, relay policy |
| `NoiseTest` | Noise_XX_25519_ChaChaPoly_SHA256 against the cacophony test vector, bitchat's 4-byte nonce prefix, replay window |
| `NostrTest` | all BIP-340 official vectors, NIP-01 event IDs, geohash, per-cell keys, relay choice |
| `MeshEngineTest` | three simulated phones in a line: announces over two hops, relayed public and fragmented messages, private messages with receipts across a relay, crossed handshakes, panic reset, peer expiry, courier carry and handover, outbox persistence, a late joiner synced from a neighbour, unsolicited sync replies dropped, history persistence |
| `CourierTest` | upstream's golden courier vectors (envelope TLV, recipient tag, signing pre-image), Noise X seal and open |
| `PrivateEnvelopeTest` | opens two envelopes recorded from bitchat builds, HChaCha20 draft vector, bech32 npubs |
| `VerificationTest` | verify QR link, canonical signed bytes, expiry, challenge and response |
| `FilePacketTest`, `NostrEmbeddedTest`, `MentionsTest`, `CommandsTest`, `GeohashTest`, `NetRouteTest` | media TLV and type checks, `bitchat1:` embedding, mentions, commands, geohash neighbours and notes, fail-closed Tor routing |
| `PeerIdRotationTest` | upstream's draft vectors for rotating IDs and recognition tags |
| `SyncTest` | packet ID, GCS bitstream layout and false-positive rate, newest-first trimming, `requestSync` TLV and type flags |

## Known risks

- **Interop with bitchat is unverified.** Generating byte-level vectors by
  running upstream code was not possible in the build environment. First
  field test: two Android phones plus one iPhone with stock bitchat, in
  airplane mode.
- **Compressed signed messages.** Public messages over 100 bytes are
  compressed before signing, and the verifier re-compresses. Android
  (zlib level 5, raw DEFLATE) and iOS output must match byte for byte.
- **BLE layer only compiles.** Connection limits, MTU handling and
  background behaviour need real devices, especially across vendors.
- **Courier metadata.** A private message to someone out of reach is
  sealed and handed to up to three nearby phones. They cannot read it or
  see who sent it, but each of them learns that a message exists for a
  recipient tag that rotates daily, and the sender's own packet names
  the sender. The same trade-off bitchat makes for delivery rate. Nothing
  about carried mail for others is shown to the carrier beyond a count.
- **Android lint**: no errors; warnings remain (dependency versions, obsolete SDK checks, monochrome icon).
- The debug APK is signed with a local debug key, so a later release build
  will not install over it.
