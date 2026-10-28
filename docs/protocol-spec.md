# openchat wire protocol spec (bitchat-compatible), v0.1

This is openchat's own description of the bitchat mesh wire format. It was
written by reading the public-domain (Unlicense) bitchat iOS source at tag
`v1.8.0` together with its `WHITEPAPER.md` and `docs/`. No upstream code is
copied into this repository, and the GPL-3.0 bitchat-android source was not
read. openchat implementations are written from this document.

Scope of v0.1: everything needed to join the BLE mesh as a full relay, see
peers, exchange signed public messages, run Noise XX sessions for private
messages and receipts, fragment large packets, and chat in geohash location
channels over Nostr. Courier mail, gossip sync, files, voice, groups, boards
and the Nostr private envelope are listed in §12 as later work.

All multi-byte integers are big-endian unless a section says otherwise.
"ms" timestamps are Unix epoch milliseconds.

## 1. BLE transport

| Item | Value |
|---|---|
| Service UUID (mainnet) | `F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C` |
| Service UUID (testnet, upstream debug builds) | `F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5A` |
| Characteristic UUID | `A1B2C3D4-E5F6-4A5B-8C9D-0E1F2A3B4C5D` |
| Characteristic properties | notify, write, write-without-response, read |
| Advertising | service UUID only, no local name |

Every device is both GATT peripheral (hosts the service) and central (scans
for the service UUID and connects). One link between two devices is enough;
a pair may hold two (one per direction) and should treat them as one peer.

**Byte stream.** Each direction of a link is a byte stream of encoded packets
(§2) laid end to end. A packet may span several notifications or writes, and
one notification may carry the tail of one packet and the head of the next.
Receivers keep a per-link buffer and cut frames using the header:

1. Read byte 0. If it is `1` or `2`, it is a packet version.
2. If it is anything else and the buffer starts with N copies of the byte N,
   that is PKCS#7 padding left over from the previous frame (§2.4): drop N
   bytes and continue.
3. Compute the frame length from the header (§2.1): header + 8 (sender)
   + 8 if HAS_RECIPIENT + (1 + 8 × routeCount) if HAS_ROUTE + payloadLength
   + 64 if HAS_SIGNATURE. Wait until that many bytes are buffered.
4. Drop a partial frame that stalls longer than about 250 ms, and cap the
   buffer (upstream: 8 MiB).

Writes from a central may arrive as ATT long writes with offsets; place each
chunk at its offset and try to decode once the buffer holds a full frame.

Senders should keep each packet within the link's write limit
(`MTU − 3`). Anything larger is fragmented (§6).

## 2. Packet

### 2.1 Fixed header

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | version: `1` or `2` |
| 1 | 1 | type (§3) |
| 2 | 1 | TTL |
| 3 | 8 | timestamp, ms |
| 11 | 1 | flags (§2.2) |
| 12 | 2 (v1) / 4 (v2) | payloadLength |

The header is 14 bytes for v1 and 16 for v2. `payloadLength` counts the
payload section only, including the 2- or 4-byte original-size field when
compressed. It never counts IDs, route or signature.

### 2.2 Flags

| Bit | Mask | Meaning |
|---|---|---|
| 0 | `0x01` | HAS_RECIPIENT: 8-byte recipient ID follows the sender ID |
| 1 | `0x02` | HAS_SIGNATURE: 64-byte Ed25519 signature ends the packet |
| 2 | `0x04` | IS_COMPRESSED: payload is compressed (§2.5) |
| 3 | `0x08` | HAS_ROUTE: source route present; valid only when version ≥ 2, ignored on v1 |
| 4 | `0x10` | IS_RSR: reply to a sync request; mutable, not signed |

### 2.3 Variable sections, in order

1. sender ID, 8 bytes (§4.1)
2. recipient ID, 8 bytes, if HAS_RECIPIENT. All `0xFF` means broadcast,
   same as absent.
3. route, if HAS_ROUTE: 1-byte hop count N, then N × 8-byte peer IDs of the
   intermediate hops
4. if IS_COMPRESSED: original (uncompressed) size, 2 bytes on v1, 4 on v2
5. payload bytes
6. signature, 64 bytes, if HAS_SIGNATURE

### 2.4 Padding

After encoding, `noiseHandshake` and `noiseEncrypted` packets are padded
PKCS#7-style: the target is the smallest of 256, 512, 1024, 2048 that is
≥ encoded length + 16, and every pad byte equals the pad count. If the pad
count would exceed 255, or the frame is above 2048 − 16, no padding is added.
All other types go out unpadded. Decoders first try to decode the raw bytes,
and only if that fails, strip PKCS#7 padding and try again.

### 2.5 Compression

- Compress when the payload is ≥ 100 bytes and its distinct byte values
  divided by min(length, 256) is below 0.9.
- Algorithm: raw DEFLATE (RFC 1951) with no zlib or gzip header. Apple's
  `COMPRESSION_ZLIB` produces this at zlib level 5. On the JVM use
  `Deflater(5, nowrap = true)` / `Inflater(nowrap = true)`.
- Keep the compressed form only if it is strictly shorter than the input.
- Reject on decode: declared original size above the type's cap (§2.7), or
  above 1032 × compressed size.

**Interop risk:** signatures cover the compressed encoding (§2.6), and the
verifier re-encodes the decoded packet with its own compressor. A signed
packet whose payload gets compressed only verifies if both sides' DEFLATE
output is byte-identical. Test this against a real iOS device early.

### 2.6 Signing

The signed bytes are the packet encoded exactly as in §2.1–2.5 with TTL set
to 0, IS_RSR cleared, HAS_SIGNATURE cleared and no signature section, and
**with the §2.4 padding step applied as if the packet were a padded type**
(upstream's signing encoder pads every type). The signature is Ed25519 over
those bytes, using the signing key published in the sender's announce.

Signed types in v0.1: `announce`, `message`, `leave`. `noiseHandshake`,
`noiseEncrypted` and `fragment` are unsigned.

### 2.7 Payload caps (decoded size)

| Types | Cap |
|---|---|
| fragment | 1 280 bytes |
| announce, leave, noiseHandshake, ping, pong, boardPost, prekeyBundle, voiceFrame | 4 KiB |
| requestSync | 8 KiB |
| courierEnvelope, nostrCarrier | 64 KiB |
| message, groupMessage, unknown types | 128 KiB |
| noiseEncrypted, fileTransfer | 1 MiB + framing overhead |

## 3. Packet types

| Value | Name | v0.1 |
|---|---|---|
| `0x01` | announce | send, receive, relay |
| `0x02` | message (public) | send, receive, relay |
| `0x03` | leave | send, receive, relay |
| `0x04` | courierEnvelope | relay only |
| `0x10` | noiseHandshake | send, receive, relay |
| `0x11` | noiseEncrypted | send, receive, relay |
| `0x20` | fragment | send, receive, relay |
| `0x21` | requestSync | ignore (link-local) |
| `0x22` | fileTransfer | relay only |
| `0x23`–`0x29`, `0x2C` | boardPost, prekeyBundle, groupMessage, ping, pong, nostrCarrier, voiceFrame, announceV2 | relay only |

A relay forwards unknown types it can parse under the normal rules (§7).

## 4. Identity

### 4.1 Keys and peer ID

- Noise static key: X25519. Its SHA-256 over the 32-byte public key, in
  lowercase hex, is the **fingerprint**.
- Signing key: Ed25519, independent of the Noise key.
- **Peer ID** = first 8 bytes of SHA-256(Noise static public key). Shown as
  16 lowercase hex characters.
- Both keys persist until a panic wipe (§10), which generates new ones and
  therefore a new peer ID.

### 4.2 Announce payload (`0x01`)

A sequence of TLVs: 1-byte type, 1-byte length, value. Unknown types are
skipped.

| T | Field | Value |
|---|---|---|
| `0x01` | nickname | UTF-8, ≤ 255 bytes, required |
| `0x02` | Noise static public key | 32 bytes, required |
| `0x03` | Ed25519 public key | 32 bytes, required |
| `0x04` | direct neighbours | N × 8-byte peer IDs, N ≤ 10, optional |
| `0x05` | capabilities | little-endian unsigned, minimal bytes (1–8), optional |
| `0x06` | bridge geohash | UTF-8, ≤ 12 bytes, optional |

Announce packets: version 1, no recipient, TTL 7, signed (§2.6).

**Accepting an announce.** Drop it unless all of these hold:

1. The three required TLVs are present.
2. First 8 bytes of SHA-256(TLV `0x02`) equal the packet's sender ID.
3. The signature verifies with TLV `0x03`.
4. If this peer ID already has a pinned signing key, it is unchanged.

Capability bits, for reference: prekeys 0, wifiBulk 1, gateway 2, groups 3,
board 4, vouch 5, meshDiagnostics 6, bridge 7, privateMedia 8,
privateMediaReceipts 9, nonDestructiveNoiseReplacement 10. openchat v0.1
advertises none (omits TLV `0x05`).

**Cadence.** Send on start and on every new link. While isolated, every
~4 s. While connected, every 15 s ± 4 s (sparse) or 30 s ± 8 s (≥ 6 links).
Never more than once per second. A verified peer stays "reachable" for 60 s
after its last announce (45 s if unverified).

### 4.3 Leave (`0x03`)

Empty payload, version 1, no recipient, TTL 7, signed. Sent on clean exit.

## 5. Public message (`0x02`)

- Payload: the message text as UTF-8, nothing else. Mentions are inline.
- Version 1, no recipient, TTL 7, signed (§2.6), timestamp = send time.
- Accept only if the sender has a verified announce and the signature
  verifies with that announce's signing key.
- Drop broadcasts older than 900 s (stale) and the device's own echoes.
- Upstream derives a display message ID from sender, timestamp and content;
  openchat may use its own local ID.

## 6. Fragmentation (`0x20`)

To send a packet larger than the link allows:

1. Encode the original packet in full (§2), including padding if its type
   pads.
2. Cut the bytes into chunks of 469 bytes (upstream default; at least 64).
3. Pick a random 8-byte fragment ID.
4. For each chunk i of N, send a `fragment` packet with this payload:

| Size | Field |
|---|---|
| 8 | fragment ID |
| 2 | index i, from 0 |
| 2 | total N |
| 1 | original packet type |
| rest | chunk bytes |

Fragment packets copy the original's sender ID, timestamp, TTL, route and
IS_RSR. Recipient = the original's recipient, if any. Version 1, or 2 when
a route is present. Unsigned. Space them ~30 ms apart (25 ms when directed).

Reassembly: key by (sender ID, fragment ID). Keep at most 128 assemblies,
each for at most 30 s and at most the original type's frame cap. When all N
chunks are present, concatenate in index order and decode as a packet.
Relays forward fragments under §7 whether or not they reassemble them.

## 7. Relaying

On every received packet that decodes:

1. **Dedup.** Key on sender ID, timestamp, type and the first 4 bytes of
   SHA-256(payload) (upstream formats it as
   `<senderHex>-<timestamp>-<type>-<digestHex>`; the format is local). Keep
   1000 keys for 5 minutes. Drop duplicates, and cancel a pending relay of
   the same key.
2. **Deliver locally** if the packet is broadcast or addressed to us.
3. **Decide.** Let `t = min(incoming TTL, 7)` and `d` = our link count.
   Never relay `requestSync`. Do not relay if `t ≤ 1`, if we sent it, or if
   it is addressed to us. Otherwise:

| Packet | New TTL | Delay |
|---|---|---|
| handshake | t − 1 | 10–35 ms |
| directed `noiseEncrypted` or directed fragment | t − 1 | 20–60 ms |
| other fragment (and voice frame) | min(t, 5 if d ≥ 6 else 7) − 1; skip if that cap ≤ 1 | 8–25 ms |
| other broadcast, d ≥ 6 | max(2, min(t, 5)) − 1 | 80–180 ms (d 6–9), 100–220 ms (d ≥ 10) |
| other broadcast, d ≤ 2 | t − 1 | 10–40 ms |
| other broadcast, d 3–5 | max(2, min(t, 7 for announce, else 6)) − 1 | 60–150 ms |

4. **Send** after the delay unless a duplicate cancelled it.
5. **Fanout.** Never send back on the ingress link. Announces, fragments and
   directed packets go to all other links. Other broadcasts may go to a
   subset of about log₂(link count) links.
6. **Source route.** If HAS_ROUTE and the next hop is a direct link, send
   only there; otherwise fall back to flooding.

openchat v0.1 originates packets without routes but relays routed packets.

## 8. Noise sessions

### 8.1 Handshake

- Protocol name `Noise_XX_25519_ChaChaPoly_SHA256` (exactly 32 bytes, so
  h₀ = the name bytes). Empty prologue. Empty payload in all three
  messages. Otherwise standard Noise rev. 34 for XX.
- Each handshake message travels as a `noiseHandshake` packet: version 1,
  recipient = the peer, TTL 7, unsigned, payload = the raw Noise message,
  padded (§2.4).
- The initiator sends message 1. The responder replies with message 2. The
  initiator sends message 3. Both then split into a send and a receive
  cipher state.
- After the handshake, the remote static key learned from Noise must hash to
  the peer ID that sent it; drop the session otherwise.
- Simultaneous initiation: upstream breaks the tie by comparing peer IDs.
  openchat: the side with the lexicographically lower peer ID (hex) stays
  initiator; the other abandons its own attempt and responds.

### 8.2 Transport messages

- ChaCha20-Poly1305 with the standard Noise 12-byte nonce: 4 zero bytes then
  the 64-bit counter **little-endian**. Associated data is empty.
- On the wire each ciphertext is prefixed with the counter's **low 4 bytes,
  big-endian**: `nonce(4) ‖ ciphertext ‖ tag(16)`. A sender must stop (and
  re-handshake) before the counter reaches 2³² − 1.
- Receivers take the nonce from that prefix, not from a local counter, and
  reject replays with a 1024-entry sliding window. Out-of-order delivery is
  therefore allowed.
- Carried in a `noiseEncrypted` packet: version 1 (2 for payloads that need
  it), recipient = the peer, TTL 7, unsigned, padded (§2.4).
- Upstream rekeys on session age (v1.8.0). openchat re-handshakes after
  1 hour or 10 000 messages, whichever comes first.

### 8.3 Plaintext inside a transport message

First byte is the payload type, the rest is its body.

| Type | Name | Body |
|---|---|---|
| `0x01` | privateMessage | TLVs: `0x00` message ID (UTF-8 ≤ 255), `0x01` content (UTF-8 ≤ 255). 1-byte type, 1-byte length. Both required |
| `0x02` | readReceipt | original message ID, UTF-8 |
| `0x03` | delivered | message ID, UTF-8 |
| `0x06`, `0x07` | group invite, key update | not in v0.1 |
| `0x08` | voice frame | not in v0.1 |
| `0x10`, `0x11` | verify challenge, response | not in v0.1 |
| `0x12` | vouch | not in v0.1 |
| `0x20` (also `0x09`) | private file | not in v0.1 |
| `0x21` | authenticated peer state | version `0x01`, then TLVs `0x01` capabilities (1–8 bytes), `0x02` Ed25519 key (32) |

Message IDs are UUID strings. A receiver sends `delivered` on receipt and
`readReceipt` when the message is shown. Private message content is capped
at 255 UTF-8 bytes by this encoding; longer text must be split.

## 9. Geohash location channels (Nostr)

- Event kind `20000` (ephemeral). Content = message text. Tags:
  `["g", <geohash>]`, optional `["n", <nickname>]`, optional
  `["t", "teleport"]` when the sender is not physically in that cell.
- Presence heartbeat: kind `20001`, empty content, only the `g` tag, never a
  nickname.
- Signature: standard Nostr (BIP-340 Schnorr over secp256k1).
- Identity: one keypair per geohash, so a user is not linkable across
  cells. Private key = HMAC-SHA256(key = 32-byte device seed, message =
  UTF-8 geohash ‖ 4-byte big-endian counter), with the counter starting at
  0 and incremented (up to 9) while the result is not a valid secp256k1
  scalar. The seed is random, stored on device, and wiped by
  panic (§10). Interop does not depend on the exact derivation.
- Subscribe with filter `{"kinds":[20000,20001], "#g":[<geohash>]}`,
  1-hour lookback, limit 200.
- Relays: the 5 relays geographically closest to the cell's centre, from a
  public relay directory. Two clients only see each other if their relay
  sets overlap, so openchat should use the same public directory data.
- Precision levels: block 7, neighborhood 6, city 5, province 4, region 2
  geohash characters.
- Upstream also supports NIP-13 proof of work on these events as spam
  control. Not in v0.1.

## 10. Panic wipe

A triple tap on the app title wipes, immediately and without a dialog:
both identity keypairs, the Nostr device seed, nickname, favorites and
pinned keys, all Noise sessions, all timelines and queued messages, received
media and any app caches. The app then generates a fresh identity (new peer
ID) and keeps running. This is local behaviour and needs no wire support.

## 11. Constants

| Name | Value |
|---|---|
| Default TTL | 7 |
| Dense-graph broadcast TTL cap | 5 (at ≥ 6 links) |
| Dedup set | 1000 entries, 300 s |
| Relay delay | see §7 table |
| Fragment chunk | 469 bytes |
| Fragment assemblies | 128, 30 s each |
| Stale broadcast | 900 s |
| Max central links (upstream) | 6 |
| Scan duty cycle | 5 s on / 10 s off; dense 3 s / 15 s |

## 12. Not in v0.1

- `requestSync` gossip sync with GCS filters (public history for late joiners)
- courier envelopes and the sender outbox (store-and-forward)
- the Nostr private envelope for mutual favorites (kind 14/13/1059,
  `v2:` XChaCha20-Poly1305), not NIP-17/44/59 compatible
- files, images, voice, groups, boards, prekeys, ping/pong, source-route
  origination, peer ID rotation (`announceV2`, still a draft upstream)
