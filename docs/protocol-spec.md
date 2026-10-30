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
| `0x21` | requestSync | send, receive; never relayed (§14) |
| `0x22` | fileTransfer | send, receive, relay (§18) |
| `0x23` | boardPost | send, receive, relay (§23) |
| `0x28` | nostrCarrier | send, receive, relay (§24.3) |
| `0x24`–`0x27`, `0x29`, `0x2C` | prekeyBundle, groupMessage, ping, pong, nostrCarrier, voiceFrame, announceV2 | relay only |

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
- Liveness (openchat): a handshake that has not completed within 10 s is
  dropped and, if anything is waiting to be sent, started again. The
  lower-ID side holds its initiator role only while its own attempt is that
  fresh. Handshake packets are unsigned, so a message 1 that arrives while a
  session with that peer already exists runs as a candidate beside it and
  replaces it only on completion; undecryptable ciphertext on a working
  session is dropped, and the session is given up only after a run of
  failures with nothing genuine in between. Half-open handshakes are capped
  (64) and expire with the same timeout.

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
| `0x06`, `0x07` | group invite, key update | §22.2 |
| `0x08` | voice frame | not in v0.1 |
| `0x10`, `0x11` | verify challenge, response | §16.2 |
| `0x12` | vouch | not in v0.1 |
| `0x20` (also `0x09`) | private file | §18.2 |
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
| Sync round | every 15 s per direct peer; first 5 s after link-up |
| Sync history | 1000 public messages, 900 s |
| Sync filter | ≤ 400 bytes, P = 7 (FPR ≈ 1 %) |
| Sync replies | ≤ 8 per peer per 30 s; accepted ≤ 30 s after our request |

## 12. Not yet implemented

- gossip sync of fragments, file transfers, group messages, prekey bundles
  and board posts (openchat syncs only announces and public messages, §14)
- courier envelope v2 (sealed to a one-time prekey, TLV `0x05`): openchat
  carries and hands over such envelopes but cannot open one addressed to it
- files other than images and audio, live push-to-talk voice, prekeys, ping/pong, source-route
  origination, peer ID rotation (`announceV2`, still a draft upstream)

## 13. Store-and-forward (v0.4)

Private messages for a peer who is not reachable are kept in a sender
outbox and, sealed, handed to nearby phones ("couriers") that may meet
the recipient later. The courier never learns sender, recipient or
content. Everything here matches upstream's courier path so bitchat
phones can carry openchat mail and the reverse.

### 13.1 Outbox (sender)

- Every private message stays in the outbox until a `delivered` or `read`
  payload for its message ID arrives, or 24 h after it was written.
- When the recipient is reachable (a verified announce was heard and a
  Noise session exists or can be started), the message is sent on the
  session as in §8. The outbox copy is kept: a session can be stale after
  the peer restarts, and receivers dedup by message ID.
- When a verified announce from the recipient arrives and the outbox has
  mail for them, every entry is resent on the session.
- When the recipient is not reachable, the message is also sealed into a
  courier envelope (§13.2) and deposited with up to 3 distinct connected
  verified peers, excluding the recipient. As new verified peers connect,
  deposits continue until 3 couriers hold the message or it expires.
- The outbox needs the recipient's Noise static key. Keys from verified
  announces are remembered after the peer leaves (bounded, wiped by §10).

### 13.2 Courier envelope (`0x04` payload)

TLV with a 1-byte type and a **2-byte big-endian** length (unlike §4.2):

| Type | Length | Value |
|---|---|---|
| `0x01` | 16 | recipient tag |
| `0x02` | 8 | expiry, milliseconds since the epoch, big-endian |
| `0x03` | 1–16384 | ciphertext |
| `0x04` | 1 | remaining copies, 1–8; omitted when 1 |
| `0x05` | 4 | prekey ID (envelope v2); never produced by openchat |

Lengths are exact, unknown types are skipped by length, and the three
required fields must all be present. `copies` is clamped to 1..8, never
rejected. An envelope is expired once `now_ms >= expiry`.

- **Recipient tag** = first 16 bytes of HMAC-SHA256(key = recipient Noise
  static key, message = `"bitchat-courier-tag-v1"` ‖ BE32(day)), where
  day = ⌊unix seconds / 86400⌋. A receiver matches day−1, day and day+1.
- **Ciphertext** is one Noise `X` message, `Noise_X_25519_ChaChaPoly_SHA256`,
  prologue `"bitchat-courier-v1"`, pre-message `<- s` (the recipient's static
  key is mixed into the hash), tokens `-> e, es, s, ss`, then the payload
  encrypted with the handshake cipher. Wire: 32 bytes `e`, 48 bytes
  encrypted `s`, then the encrypted payload (16-byte tag). The handshake
  cipher uses plain 8-byte little-endian nonces, as in §8.1, not the
  4-byte prefix of §8.2.
- **Payload** is a §8.3 plaintext: `0x01` (privateMessage) then the
  message-ID/content TLV. Only that type is accepted on open.
- **Expiry** = seal time + 24 h. Initial copies = 4.

### 13.3 Packet

Type `0x04`, TTL 7, `recipientID` = the courier (on deposit) or the
recipient (on handover), signed by the sender per §2.6. Payload cap
64 KiB (§2.7). Relays treat it as a directed packet (§7).

### 13.4 Receiving an envelope addressed to us

1. Decode; drop if expired.
2. If the tag matches our static key for day±1: open (§13.5).
3. Otherwise it is a deposit. Accept only if the packet's sender is the
   peer bound to the ingress link (a relayed deposit is refused), the
   signature verifies against that peer's announced signing key, and
   expiry ≤ now + 25 h. Store it with the depositor's Noise key.
4. Identical ciphertext is the same envelope: keep the larger copy budget
   unless we have already sprayed it. Quotas: 40 envelopes, 2 per
   depositor (openchat has no favorites, so every verified peer gets
   upstream's "verified" tier). When full, evict the oldest.
5. Carried envelopes expire with their `expiry` and are wiped by §10.

### 13.5 Opening

Open the `X` message with our static key. The sender's static key comes
out of the handshake; their peer ID is derived from it (§4.1). Require
payload type `0x01`; dedup on the inner message ID (last 512 kept); then
deliver as a private message and queue a `delivered` payload to the
sender on the normal session path, which goes out when they are next
reachable.

### 13.6 Handover and spread (courier)

On a verified announce from peer P with Noise key K:

- **Direct handover.** If the announce came straight off a link (§4.2
  direct rule), send every carried envelope whose tag matches K for day±1
  as a `0x04` packet addressed to P on that link, and drop the carried
  copy.
- **Remote handover.** If the announce was relayed, broadcast those
  envelopes addressed to P instead, keep the copies, and do not repeat
  for the same envelope within 10 min.
- **Spray.** For every other carried envelope with copies > 1 that P did
  not deposit and that was not already sprayed to P, send P a copy with
  ⌊copies/2⌋ and keep the rest. Only for announces straight off a link.
- **Deposit.** Hand P any outbox mail for other recipients that P is not
  yet carrying (§13.1).

### 13.7 Persistence

The outbox, the carried envelopes and the remembered keys are stored in
app-private storage sealed like the identity, and restored on start.

## 14. History sync (v0.5)

A phone that arrives late, or walks between two groups, asks each direct
neighbour what public traffic it has missed. The request carries a compact
set of everything the asker already holds; the neighbour replies with what
is missing. Only announces and public messages are synced; the request is
link-local and never relayed.

### 14.1 Packet ID

Every synced packet is identified by the first 16 bytes of
SHA-256(`type` ‖ `senderID` ‖ BE64 `timestamp` ‖ `payload`), over the
decoded (uncompressed) payload. The ID is independent of TTL, flags,
recipient and signature, so a relayed or re-sent copy has the same ID.

### 14.2 Golomb-coded set

The asker maps each ID to a bucket: `h = BE64(SHA-256(id)[0:8]) & 0x7FFF…`,
`bucket = h mod M`, with bucket 0 remapped to 1. `M = N · 2^P` where `N` is
the number of IDs and `P` the Rice parameter (`P = ⌈log2(1/FPR)⌉`, 7 for a
1 % false-positive rate; a value above 32 is rejected on receipt). Buckets
are sorted ascending, duplicates dropped, and encoded as deltas `x ≥ 1`
(first delta from 0): `q = (x−1) >> P` as unary (`q` one-bits then a zero),
then the low `P` bits of `x−1`, MSB first, final byte zero-padded. The
decoder stops at the end of data or when the running sum reaches `M`.

The filter is sized to at most 400 bytes. IDs are offered newest first and
the tail is trimmed to fit, so the covered set is always a newest-prefix;
the timestamp of the oldest covered packet becomes the `since` cursor.

### 14.3 Request payload (`0x21`)

TLV, 1-byte type, 2-byte big-endian length:

| Type | Value |
|---|---|
| `0x01` | `P`, 1 byte |
| `0x02` | `M`, 4 bytes BE |
| `0x03` | filter bitstream (may be empty; a receiver rejects more than 1024 bytes) |
| `0x04` | type flags, 1–8 bytes little-endian: bit 0 announce, bit 1 message; higher bits name types openchat does not sync and ignores |
| `0x05` | `since`, 8 bytes BE ms, optional: the filter covers only packets at or after this time |
| `0x06` | fragment stream IDs for targeted resync, optional; openchat ignores it |

Unknown TLVs are skipped. A request without `0x04` means announces and
messages. The packet is signed (§2.6), TTL 0, addressed to the neighbour
(broadcast when no neighbour is identified yet), and sent only on the
direct link.

### 14.4 Replying

A receiver answers a request that is broadcast or addressed to it, from a
peer whose signing key it holds, at most 8 times per peer per 30 s. For
every stored packet of a requested type whose ID is not in the filter it
sends the stored packet unchanged except `TTL = 0` and `IS_RSR` set, on the
link the request came in on. Announces ignore the `since` cursor (they carry
the keys that verify everything else); messages older than `since` are
skipped. Nothing else is sent: a reply is the stored packets themselves,
including their original signatures.

### 14.5 Accepting replies

A packet with `IS_RSR` set is accepted only on a link whose peer we asked
within the last 30 s; otherwise it is dropped. Accepted replies go through
the normal pipeline: dedup, announce verification, message verification
against the sender's signing key (from the live peer table or the
remembered peers of §13.1), and the 900 s freshness window. `TTL = 0`
means they are never relayed. A message learned this way is stored and
offered onward, so history crosses partitions one hop per encounter.

### 14.6 Store and schedule

Each phone keeps its own and every verified broadcast public message of the
last 900 s, newest 1000, and the latest announce of every current peer and
of itself. The store is persisted with the §13.7 state and restored on
start, minus what aged out. The first request to a neighbour goes 5 s after
its announce identifies the link; afterwards one request per direct
neighbour every 15 s. A panic wipe clears the store.

## 15. Favorites and private messages over Nostr (v0.5)

### 15.1 Favorite notice

Favoriting someone sends them a private message (§8.3, over the mesh
when reachable, else over Nostr if their npub is known, else by courier)
whose content is `[FAVORITED]:<my npub>` or `[UNFAVORITED]:<my npub>`. It
is not shown as a message. The receiver records the npub against the
sender's Noise key. openchat records it only from an authenticated path: a
Noise session on the mesh, or after a §16 verification. A notice arriving
over Nostr is honoured only when its npub is the one already recorded for
that Noise key, since the sender field inside a Nostr envelope (§15.2) is
not signed and would otherwise let anyone bind another person's identity to
their own npub. Two people who have both favorited each other and hold
each other's npub are mutual favorites. The npub is the account key: one
secp256k1 key per device, derived from the identity seed (openchat:
`HMAC-SHA256(seed, "openchat-nostr-account")` through the §9 derivation),
replaced by a panic wipe.

### 15.2 Embedded packet

Over Nostr a private message, receipt or notice is a §2 packet of type
`noiseEncrypted` (TTL 7, sender = my peer ID, recipient = theirs) whose
payload is the plaintext §8.3 Noise payload, not Noise-encrypted. The
event content is `bitchat1:` followed by the packet in unpadded base64url.

### 15.3 Private envelope

Three nested events, all with bitchat's own cipher rather than NIP-44:

- rumor: kind 14, unsigned (no `sig`), pubkey = sender account key,
  tags `[]` (a `[["p", recipient]]` tag is also accepted), content = §15.2.
- seal: kind 13, tags `[]`, signed by the sender account key,
  `created_at` = now ± up to 900 s, content = encrypt(rumor JSON).
- wrap: kind 1059, signed by a one-time key, tags exactly
  `[["p", recipient hex]]`, `created_at` = now ± 900 s, content =
  encrypt(seal JSON).

encrypt: `v2:` + base64url(nonce24 ‖ ciphertext ‖ tag16), XChaCha20-Poly1305
(HChaCha20 over the first 16 nonce bytes, then ChaCha20-Poly1305 with
`00000000` ‖ the last 8). Key = HKDF-SHA256(IKM = the 33-byte compressed
ECDH point, salt empty, info `"nip44-v2"`, 32 bytes). Senders' x-only keys
are tried with even then odd Y. A receiver rejects any layer whose kind,
tags, signature or size (64 KiB) differ, and a rumor whose pubkey is not
the seal signer. Checked against two envelopes recorded from bitchat builds.

### 15.4 Relays and delivery

Account envelopes use `wss://relay.damus.io`, `wss://nos.lol`,
`wss://relay.primal.net` and `wss://offchain.pub`, subscribing to kind
1059 with `#p` = my key since two days ago. openchat sends over Nostr only
to a mutual favorite who is not reachable on the mesh while a relay is
connected; otherwise §13 applies. The user may add up to eight relays of
their own (`wss://`, or `ws://` for `.onion` only), used together with the
four for both subscribing and publishing. It accepts Nostr messages only from
favorites' npubs, and favorite notices only from the npub they announce
and from a peer ID it has met. A received message is answered with
`delivered` and, when shown, `readReceipt`, one envelope per 1.2 s.

### 15.5 Private messages in location channels

Tapping a name in a location channel opens a private chat carried the same
way (§15.3, same four relays) but between the two per-cell personas (§9):
the sender wraps under its key for that cell, to the other's cell pubkey,
and each side subscribes to kind 1059 with `#p` = its own cell key while
in the channel. The embedded packet (§15.2) has no recipient and a sender
ID that is random for every envelope, so the persona cannot be linked to
the device. `delivered` and `readReceipt` come back the same way.

## 16. In-person verification (v0.5)

### 16.1 QR link

`bitchat://verify?v=1&noise=<hex>&sign=<hex>&nick=<name>&ts=<unix s>&nonce=<b64url>&sig=<hex>[&npub=<npub>]`,
values percent-encoded (a space is `%20`; a literal `+` is not a space).
`noise` and `sign` are the Noise static and Ed25519 public keys, `nonce`
16 random bytes in unpadded base64url. `sig` is Ed25519, by the `sign`
key, over the canonical bytes: each of `"bitchat-verify-v1"`, `v`,
lowercase `noise`, lowercase `sign`, `npub` (empty if absent), `nick`, `ts`
and `nonce`, as UTF-8 prefixed by a 1-byte length (truncated at 255). A
scanner accepts the link if `|now − ts| ≤ 300 s` and the signature verifies,
then marks the fingerprint SHA-256(`noise`) as verified.

### 16.2 Challenge over Noise

After a scan, if the peer is reachable the scanner sends `verifyChallenge`
(`0x10`) over the Noise session: TLVs with 1-byte type and 1-byte length,
`0x01` the target's Noise key as lowercase hex ASCII, `0x02` a fresh
16-byte nonce. The target, if the key is its own, answers `verifyResponse`
(`0x11`): `0x01` key hex, `0x02` nonce, `0x03` Ed25519 signature over
`"bitchat-verify-resp-v1"` ‖ len(key hex) ‖ key hex ‖ nonce. The scanner
checks it against the signing key from the peer's announce.

Verification state is local and never sent. Blocking (also local) drops a
sender's public and private messages, including courier mail, without a
delivery receipt; the phone still relays their packets.

## 17. Location notes (v0.5)

A note is a Nostr kind 1 event signed by the per-cell key (§9) of a
building-level cell (geohash precision 8), with tags `["g", cell]`,
`["n", nickname]` (optional) and the NIP-40 `["expiration", created_at +
86400]`. A reader subscribes, on the cell's nearest relays, to kind 1 with
`#g` = the cell and its eight neighbours, limit 200, and drops expired
notes itself since relays need not. In a location channel `/drop <text>`
leaves a note at the device's own building cell.

## 18. Photos and voice notes (v0.5)

### 18.1 File TLV

`0x01` file name (UTF-8), `0x02` size (4 bytes BE), `0x03` MIME type,
`0x04` content. Type 1 byte; length 2 bytes BE except content, whose length
is 4 bytes BE (a receiver falls back to 2 bytes when 4 overrun the data).
Content is capped at 1 MiB. openchat accepts only images (JPEG, PNG, GIF,
WebP) and audio (MP4/M4A, AAC, MP3, WAV, Ogg) whose first bytes match the
declared type, and sends JPEG photos re-encoded to ≤ 720 px and voice notes
as AAC in MP4 (`audio/mp4`, ≤ 30 s), both ≤ 96 KiB. File names follow
upstream: `img_<UUID>.jpg`, `voice_<UUID>.m4a`.

### 18.2 Public and private

Public: a signed broadcast `fileTransfer` (`0x22`) packet whose payload is
the TLV, verified, relayed and fragmented like a public message, but not
history-synced. Private: Noise payload `privateFile` (`0x20`; `0x09` is
accepted from prerelease iOS and never sent) carrying the TLV. openchat
sends it only to a peer that, inside the current session, sent
`authenticatedPeerState` (`0x21`) with capability bit 8 (private media)
and the Ed25519 key of its announce. openchat sends its own `0x21`
(capabilities = bit 8) on every completed session and sets bit 8 in its
announce capabilities. It never sends media unencrypted to one person; it
does accept the signed directed `fileTransfer` older clients send.

## 19. Tor (v0.5)

All relay WebSockets (location channels, notes, private messages over
Nostr) go through Tor by default, using Guardian Project's tor-android
(tor 0.4.8) in-process and its SOCKS port; host names are resolved by Tor.
It is fail-closed: while Tor is wanted but not ready no relay socket is
opened and outgoing events wait. Turning Tor off in the Me tab connects
directly, and the tab says relay operators then see the device's address.
The Bluetooth mesh never uses the internet. No bridges or pluggable
transports yet, so Tor cannot start where it is blocked.

## 20. Metadata and peer ID rotation (v0.5)

openchat no longer sends the neighbour list (announce TLV `0x04`): it gives
any listener the local adjacency graph, and routing falls back to flooding.
It still accepts the TLV from others.

Peer ID rotation follows upstream's draft and, like upstream, is not wired
in: a rotating ID breaks the `peerID = SHA-256(noise key)[0..8]` check every
current client uses, so a phone that rotated would be invisible to every
bitchat phone. openchat implements and tests the derivations so it can join
once both bitchat clients ship them:

- `K_rot = HKDF-SHA256(noise static private, salt "", "bitchat-peer-rotation-v1")`,
  `peerID_e = HMAC-SHA256(K_rot, "bitchat-peer-id-v2" ‖ BE32(floor(t / 3600)))[0..8]`.
- Recognition tag A→B = HMAC-SHA256(HKDF(X25519(A, B), "bitchat-recognition-v1"),
  BE32(epoch) ‖ A pub ‖ B pub ‖ peerID_e)[0..8], directional; eight slots,
  random-padded, shuffled.
- Binding signed in-session: `"bitchat-peerid-binding-v1"` ‖ BE32(epoch) ‖
  peerID_e ‖ noise static public (69 bytes).
- `announceV2` (`0x2C`) TLVs, 1-byte type and length (assumed, as v1):
  `0x01` epoch (4), `0x02` tags (64), `0x03` capabilities, `0x04` bridge
  geohash. Received v2 announces are relayed and otherwise ignored.

## 21. Privacy and flood protection (v0.6)

### 21.1 Rate limit on incoming public messages

Two token buckets, as bitchat: one per sender (capacity 5, refill 1/s)
and one per normalised text (capacity 3, refill 0.5/s; lower case, URL
query and fragment dropped, whitespace collapsed, first 256 characters).
At most 2000 buckets each, idle ones (10 min) evicted first. A message over
either limit is not shown; the mesh still relays it. History that arrives by
sync (§14) or as a relay's stored backlog (events older than 60 s) is not
limited, since it comes in a burst by design.

### 21.2 Proof of work in location channels

Outgoing kind 20000 messages carry a NIP-13 `["nonce", <16 hex>, <target>]`
tag mined to 8 leading zero bits of the event ID (2 s budget, then half
the difficulty with 150 ms, down to 0). An incoming event's work counts
only if its ID really has the committed number of zero bits; 8 or more
bits skip the per-sender bucket, never the per-text one. Work is never
required.

### 21.3 Notifications

By default a notification says only "New message" or "Someone mentioned
you": no sender, place or text. The user may turn previews on; the lock
screen still gets the quiet form (`VISIBILITY_PRIVATE` with that public
version). A panic wipe turns previews off again.

### 21.4 Received photos

A received photo is shown as coarse blocks (about 12 across) until tapped.

### 21.5 Sharing a location channel

A plain-text invite with a `bitchat://geohash/<cell>` link, which both
apps open. Cells of 6 characters or more (a neighbourhood or smaller) ask
for confirmation first, since whoever carries the text learns the place.

## 22. Private groups (v0.6)

Creator-managed groups of at most 16, as bitchat's v1: the creator signs
the roster and the key; messages are sealed with the group key and flooded
like public messages. Group TLVs below use a 1-byte type and a **2-byte**
big-endian length; unknown types are skipped.

### 22.1 Group state

| Type | Value |
|---|---|
| `0x01` | group ID, 16 random bytes |
| `0x02` | name, UTF-8 (≤ 40 characters) |
| `0x03` | key, 32 bytes, for this epoch |
| `0x04` | epoch, BE32, starts at 1 and rises on every roster change |
| `0x05` | roster: count byte, then per member the 32-byte fingerprint (SHA-256 of the Noise key), the 32-byte Ed25519 key, a length byte and the nickname (≤ 64 bytes, cut on a character boundary) |
| `0x06` | creator fingerprint, 32 bytes |
| `0x07` | Ed25519 signature by the creator over `"bitchat-group-v1"` ‖ group ID ‖ BE32 epoch ‖ SHA-256(key) ‖ SHA-256(roster bytes) ‖ SHA-256(name) |

### 22.2 Invites and key updates

State travels only inside a Noise session (§8.3): `groupInvite` (`0x06`) to
a new member, `groupKeyUpdate` (`0x07`) to the others. A receiver accepts it
only if the session peer's fingerprint is the creator fingerprint, the
signature verifies with the creator's key in the roster, it is in the
roster itself, and, for a group it already holds, the creator (fingerprint
and key) is unchanged and the epoch does not go down. A valid state without
the receiver in the roster removes the group. Every invite and removal
draws a new random key and raises the epoch; a removed member is sent that
state with an all-zero key so it learns it is out. Leaving is local. Members
who are away when the key changes miss it (v1 limitation, as upstream).

### 22.3 Group messages (`0x25`)

Broadcast, unsigned outer packet, TTL 7, relayed like a public message.
Payload TLVs: `0x01` group ID, `0x02` BE32 epoch, `0x03` 12-byte nonce,
`0x04` ciphertext ‖ 16-byte tag. ChaCha20-Poly1305 with the epoch key and
additional data group ID ‖ BE32 epoch. Inside: `0x01` message ID, `0x02`
sender Ed25519 key, `0x03` nickname, `0x04` BE64 timestamp ms, `0x05`
content, `0x06` Ed25519 signature over `"bitchat-group-msg-v1"` ‖ group ID ‖
BE32 epoch ‖ message ID ‖ BE64 timestamp ‖ content. A member shows a message
only for the current epoch, with a valid signature from a key in the roster,
not its own, not blocked. Group messages are kept for history sync (§14,
type bit 10, newest 200, 900 s) so members who were away catch up.

## 23. Notice board (v0.6)

### 23.1 Wire format (`0x23` payload)

TLVs with a 1-byte type and 2-byte big-endian length; unknown types are
skipped. `0x01` kind (`0x01` post, `0x02` tombstone), `0x02` post ID (16
random bytes), `0x03` geohash (UTF-8, ≤ 12 base32 characters; empty = the
mesh board), `0x04` content (1–512 bytes), `0x05` author Ed25519 key,
`0x06` nickname (≤ 64 bytes), `0x07` created ms BE64, `0x08` expires ms
BE64 (after created, at most 7 days), `0x09` flags (bit 0 urgent), `0x0A`
signature, `0x0B` deleted ms BE64 (tombstone). A post signs: length byte +
`"bitchat-board-v1"` ‖ post ID ‖ (BE16 length ‖ geohash) ‖ (BE16 length ‖
content) ‖ author key ‖ (BE16 length ‖ nickname) ‖ created ‖ expires ‖
flags. A tombstone signs: length byte + `"bitchat-board-del-v1"` ‖ post ID ‖
deleted. The outer packet is a signed broadcast, but posts are verified by
their own signature, so they stay valid after the author has left.

### 23.2 Store and spread

A post is refused if expired, created more than 1 h in the future, expiring
beyond 7 days + 1 h from now, or already deleted. At most 5 posts per author
and 200 in all, oldest evicted. A tombstone is accepted only from the post's
author key, removes the post and is kept until the post would have expired;
one that arrives before its post is kept 7 days (at most 5 per author, 100
in all) and suppresses the post. Accepted and duplicate packets are
relayed; refused ones are not. Board packets are offered in history sync
(§14, type bit 8) for as long as they live, not just 900 s, and persisted
with the §13.7 state, re-verified on load. A panic wipe clears them.

### 23.3 In openchat

The board lives on the Nearby tab. Posting goes to the mesh board (empty
geohash) for 1, 3 or 7 days, optionally urgent; posts tagged with a
geohash by bitchat are shown with their area. A new urgent post that is
live and under 10 minutes old adds one line to the Nearby chat; other new
posts only raise the board's badge. Authors can take their posts down.

## 24. Mesh bridge (v0.6)

Opt-in, off by default. Bluetooth islands that share a neighbourhood cell
(geohash precision 6, about 1.2 km) meet on that cell's Nostr relays (the
five nearest, §9).

### 24.1 Rendezvous events

A bridged public message is a kind 20000 event signed by the per-cell key
(§9) with tags `["r", cell]`, `["n", nickname]` and `["m", id, sender peer
ID hex, timestamp ms]`, where `id` = the first 32 hex digits of SHA-256(
lowercase sender ID ‖ `|` ‖ timestamp ‖ `|` ‖ trimmed text) of the radio
message (upstream's pinned vector checks it). `r`, not `g`, keeps it out of
location channels. Presence is kind 20001 with only `["r", cell]`, every
4 min. A bridging phone subscribes to kinds 20000 and 20001 with `#r` = its
cell and the eight around it, since 15 min ago. An event counts only if its
own `r` cell is in that ring, it is at most 15 min from now, its text is
1–16 000 bytes, and its pubkey is not ours for that cell. The `m` tag is a
hint only (its fields are public): it lets a phone skip a copy of a radio
message it already has, and when the radio copy arrives after a bridged
row, the radio copy replaces it. Bridged rows are keyed by event ID.

### 24.2 Advertising

While bridging and online, a phone sets capability bit 7 (bridge) and the
announce `bridgeGeohash` TLV (`0x06`) to its cell. A phone without its own
location uses a bridging neighbour's advertised cell.

### 24.3 Carrier (`0x28`)

Payload TLVs, 1-byte type and 2-byte length: `0x01` direction, `0x02`
cell (1–12 bytes), `0x03` the complete signed event as JSON (≤ 16 KiB).
`0x03` toBridge: a mesh-only bridging phone sends its signed event,
directed and signed, to a bridging neighbour, which verifies the Schnorr
signature and publishes it (10 per depositor per minute; up to 20 queued
while offline, 5 per depositor). `0x04` fromBridge: a bridging phone with
internet broadcasts events from other islands onto the radio, after a 0.2–
1.5 s holdoff in which another phone's copy cancels its own, at most 20 a
minute. Receiving fromBridge needs no toggle. Loop rules: an event that
came over the radio is never published or rebroadcast; an event this
phone published is never rebroadcast; each event is rebroadcast at most
once, and never when the radio copy is already present. Directions
`0x01`/`0x02` are the location-channel gateway (§24.5); other values are
refused.

### 24.4 Courier drops

With the bridge on, mail sealed for an absent peer (§13.2, a single-copy
envelope) is also published as kind 1401 to the private-message relays
(§15.4): content = standard base64 of the envelope, tags `["x", recipient
tag hex]` and NIP-40 `["expiration", envelope expiry s]`, signed by a fresh
throwaway key. Drops are queued (20) while offline. A bridging phone
subscribes to kind 1401 with `#x` = its own candidate tags (§13.3) and
those of up to 16 verified direct neighbours, since 24 h ago; it opens its
own and hands the others to their recipients as directed courier packets.
Message-ID dedup absorbs a message arriving by drop, courier and direct link.

### 24.5 Location-channel gateway

Capability bit 2 in the announce. A phone advertises it while the bridge
toggle is on and it has a working relay connection; it then shares that
connection with the mesh for location channels (§9):

- Deposit (`0x01`, directed to the gateway): a phone that just sent a
  kind-20000 channel event and has no relay connection hands the finished,
  signed event to one gateway peer. No toggle is needed for this. The
  gateway checks kind, a `g` tag equal to the carrier geohash, age ≤ 15 min,
  dedup, 10 deposits per depositor per minute, and only then the Schnorr
  signature; it publishes to that cell's relays, or queues (20, 5 per
  depositor) until it is online. If it is in that channel itself, the event
  also shows on its own timeline.
- Downlink (`0x02`, broadcast): a gateway carries each fresh, verified
  kind-20000 event its own channel subscriptions deliver onto the radio,
  at most 30 a minute (30 waiting, oldest dropped), each at most once,
  marked only after it is sent. Never one it learned from the radio or
  published itself.
- Receiving `0x02` needs no toggle: after the same checks and the
  signature, the event is marked as radio-carried (never published or
  carried again) and shown if we are in that channel.

## 25. Mesh diagnostics (v0.6)

### 25.1 Ping (`0x26`) and pong (`0x27`)

Payload, 9 bytes (longer is accepted, the rest ignored): an 8-byte random
nonce, then the TTL the packet was sent with. Unsigned and unencrypted;
TTL 7, addressed to one peer and relayed like any directed packet. The
addressed peer answers with a pong addressed to the ping's sender, echoing
the nonce, with its own launch TTL. A pong counts only if its nonce is one
we sent to that very peer, within 10 s. Hops = launch TTL − received TTL +
1 (1 = neighbour). Because the sender ID of an unsigned packet can be
forged, the pong budget is per ingress link, not per claimed sender: at
most 5 pongs per link per 10 s.

### 25.2 Estimated path

`/trace` is computed locally from the direct-neighbour lists other phones
put in their announces (§4, TLV `0x04`); nothing is sent. A link counts if
either end claims it, because openchat sends no list of its own (§20);
the first hop is one of our own live links. Breadth-first, at most 6 hops.
The result is an estimate, shown as such.

## 26. Vouching (v0.6)

A vouch says "I verified this identity in person" (§16), signed by the
voucher and sent only inside a Noise session (payload type `0x12`), so the
voucher is the session peer and is not named in it.

Attestation, TLVs with 1-byte type and 1-byte length, unknown types skipped:
`0x01` vouchee fingerprint (32, SHA-256 of its Noise static key), `0x02`
vouchee Ed25519 key (32), `0x03` time (8, BE ms), `0x04` signature (64) by
the voucher's signing key over `"bitchat-vouch-v1"` ‖ fingerprint ‖ key ‖
time. Batch: a count byte, then each attestation behind a 2-byte BE length;
at most 16, malformed entries skipped, a larger declared count ignored.

Sending: when a session is up with a peer we verified, at most once per 24 h
per peer, we send attestations for up to 16 other people we verified, most
recently verified first, skipping any whose signing key we do not hold.
Also re-tried when the peer list changes or we verify someone. A peer whose
announce lists capabilities without bit 5 gets none; one that lists none
may still get them.

Accepting: only from a session peer we verified, each signature checked
against that peer's announced signing key; time within 30 days back and one
hour ahead. Stored per vouchee, the eight newest vouchers; never for
ourselves as voucher of ourselves or for someone we verified directly.
Whether a vouch counts is decided when read: its voucher must still be
verified by us and it must be under 30 days old. Shown as a grey ✓ and as
"verified by …" on the person. Kept in the sealed social state and wiped
by the triple tap.

openchat now announces capability bits 3 (groups), 4 (board), 5 (vouch),
6 (diagnostics) and 8 (private media), plus 7 and 2 while bridging or
serving as a gateway, both in the announce and in the in-session peer state.

