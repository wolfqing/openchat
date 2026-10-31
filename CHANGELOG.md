# Changelog

## Unreleased

- Live voice, compatible with bitchat ([spec §29](docs/protocol-spec.md)).
  Hold the mic to talk and release to send (a quick tap still starts and
  stops a recording). In Nearby and private chats people hear you while you
  are still talking: their bubble appears at once and plays live if they
  have that chat open, and the finished note then slots into the same
  bubble. Public bursts are signed and accepted for 30 seconds; floods are
  capped. A Me-tab switch, on by default, turns live voice off in both
  directions. Not yet tried on a phone.
- 6 new tests, 148 in all.

- `/pay` and Cashu cards, compatible with bitchat ([spec §28](docs/protocol-spec.md)).
  `/pay <token>` sends a Cashu ecash token after checking it is real money;
  outside a private chat it needs `/pay <token> public`, because whoever
  redeems a token first gets it. Tokens in messages show as a card with the
  amount, mint and memo, an "Open in wallet" button and Copy. The app never
  talks to a mint.
- 5 new tests, 142 in all.

- One-time prekeys, compatible with bitchat ([spec §27](docs/protocol-spec.md)).
  Each phone publishes eight single-use keys that spread like board posts.
  Mail for someone out of reach, whether carried by other phones or parked
  on relays, is now sealed to one of their single-use keys when one is
  known, and that key is deleted two days after the mail is opened, so
  stealing the phone later does not unlock mail captured on the way.
  Opening bitchat's forward-secret mail works too.
- 4 new tests.

- Vouching, compatible with bitchat ([spec §26](docs/protocol-spec.md)).
  People you verified in person also tell your phone, over an encrypted
  session and at most once a day, whom they verified; those people get a
  grey ✓ and "verified by …" on their card. Only vouches from people you
  verified count, and they lapse after 30 days or as soon as you un-verify
  the voucher.
- openchat now announces the capabilities it has (groups, board, vouching,
  diagnostics), so bitchat phones offer them.
- 5 new tests.

- Location-channel gateway, compatible with bitchat ([spec §24.5](docs/protocol-spec.md)).
  With the bridge on and internet working, your phone also serves people
  nearby who have none: what they post in a location channel goes out
  through you, and the channels you are in come in for them over the radio
  (30 a minute). Without internet, your own channel messages go out through
  such a phone automatically. Every carried event is signature-checked;
  bitchat's loop rules apply. The bridge explanation in the Me tab says so.
- Unread counts no longer grow when the same message arrives twice.

- `/ping @name` and `/trace @name` in Nearby and private chats, compatible
  with bitchat ([spec §25](docs/protocol-spec.md)). Ping shows the round
  trip and how many hops away someone is; trace shows an estimated path
  from the neighbour lists bitchat phones announce. Pongs are budgeted per
  radio link, so a forged sender cannot turn a phone into a pong cannon.
- 2 new tests.

- Mesh bridge, wire-compatible with bitchat ([spec §24](docs/protocol-spec.md)),
  off by default (Me tab). Bluetooth reaches only so far: with the bridge on,
  Nearby messages also go to the internet relays for your neighbourhood
  cell (about 1.2 km) under an unlinkable per-cell key, and what people in
  other islands of that area say appears in Nearby, marked "via bridge". A
  bridging phone with internet serves its island: it publishes messages
  that bridging phones without internet hand to it, and carries other
  islands' messages onto the radio (holdoff, 20 a minute, bitchat's loop
  rules). A copy of a radio message is skipped, and the radio copy replaces
  a bridged row that got there first.
- Courier drops: with the bridge on, mail for someone out of reach is also
  parked on relays as a sealed kind-1401 drop under a throwaway key, so it
  arrives as soon as either side is online; a bridging phone also fetches
  drops for verified neighbours and hands them over.
- 5 new tests (including upstream's pinned message-ID vector), 125 in all.
  The bridge's own relay-side logic is not unit-tested yet.

- Notice board, wire-compatible with bitchat ([spec §23](docs/protocol-spec.md)).
  Board on the Nearby tab: pin a notice for 1, 3 or 7 days, mark it urgent,
  take it down later. Posts are signed by their author and pass from phone
  to phone, including by history sync, so people who arrive days later
  still read them; a takedown spreads the same way. Five posts per author,
  200 in all. A fresh urgent notice adds a line to the Nearby chat; others
  raise a badge. Kept with the sealed mesh state, wiped by the triple tap.
- 5 new tests, 120 in all.

- Private groups, wire-compatible with bitchat ([spec §22](docs/protocol-spec.md)).
  New group on the Private tab or `/group create`; the creator invites
  people nearby from Members (or `/group invite @name`), removes them, and
  anyone can leave. Up to 16 members. The creator signs the roster and a
  fresh key on every change and hands them out inside Noise sessions;
  messages are sealed with ChaCha20-Poly1305 under the group key, signed
  by their author, and flooded over the mesh like public ones, so only
  members read them. Members who were away pick up missed messages by
  history sync. Text only for now. Groups and keys are sealed on disk and
  go with the triple tap.
- Fix: a history-sync copy arriving while a relay waited cancelled the
  relay, so a message could stop one hop short. A sync reply goes to one
  phone only and no longer counts as someone else having relayed it.
- 6 new tests, 115 in all.

- Flood protection, as bitchat ([spec §21](docs/protocol-spec.md)): a
  sender may post five public messages at once and then one a second, and
  one text may appear three times at once and then once every two seconds,
  whoever sends it. Excess messages are hidden, still relayed. Synced
  history and relay backlogs are exempt.
- Location-channel messages carry a little proof of work (NIP-13, 8 bits,
  mined off the main thread), which lets a busy sender past the per-sender
  limit on bitchat and openchat.
- Notifications are quiet by default: "New message", no name, place or
  text, so a locked phone does not read chats out to whoever holds it. A
  switch on the Me tab shows previews; the lock screen stays quiet either
  way, and the triple tap turns previews off again.
- Received photos appear as coarse blocks until tapped.
- Location channels have a Share button: a text invite with a
  `bitchat://geohash/` link that bitchat and openchat both open (openchat
  now opens such links). Small areas ask before sharing.
- 5 new tests, 109 in all.

- The app mark now sits in the top-left corner of the main screen and is a
  48 dp target for the triple-tap wipe. The title still works too. Each
  counted tap gives a short haptic tick, and the three taps are now judged
  by the gap between them (500 ms each) instead of an 800 ms window from
  the first tap, which was too tight on a real phone. The first-run safety
  note points at the logo. Only the English and Chinese notes were
  updated; the other sixteen still say "title".

Fixes from the 2026-10-29 code review. None change the wire format.

- Private messages over Nostr: a favorite notice arriving over Nostr could
  bind any mesh peer's identity to the sender's own npub, because the sender
  field inside the envelope is not signed. Once bound, private messages to
  that peer out of Bluetooth range were encrypted to the attacker. Now only
  the npub already recorded for a person speaks for them over Nostr
  (`NostrSenders`, [spec §15.1](docs/protocol-spec.md)); the first binding
  happens over a Noise session or a verification QR, where the key is
  proven. 4 new tests.
- Sealed files (identity, mesh state, block list) are written to a sibling
  file and renamed over the old one, so a crash mid-write cannot leave half
  a file. An identity file that exists but will not open (Keystore not
  answering after a system update, corrupt bytes) is no longer replaced by
  fresh keys: the service stops, the banner says the saved identity cannot
  be unlocked, and a tap retries. Write failures no longer throw.
- Noise handshakes time out after 10 s and are retried while something is
  waiting to be sent; before, one lost handshake message left a private
  chat stuck until the other side restarted. Garbage ciphertext or a forged
  handshake message 1 under someone's peer ID no longer cuts an established
  session with that person: a re-handshake runs beside the old session and
  replaces it only when it completes. Half-open handshakes are capped at 64
  and expire; early ciphertexts are capped by bytes; sessions of peers gone
  for an hour are dropped and their keys zeroed.
- A phone's own public messages and announces go to every neighbour. The
  log₂(n) fanout subset of §7 applied to them too, so a hub with six
  neighbours reached about three of them with its own words.
- An announce whose signing key differs from the one remembered for that
  Noise key is rejected even after the peer has left the live table, so a
  copy of someone's Noise key under another signing key cannot speak as
  them or lock them out when they return. Favorites, verified people and
  anyone with mail in the outbox are never evicted from the remembered-key
  table by a flood of strangers.
- The service no longer crash-loops when Android 14+ restarts it after the
  Bluetooth permission was revoked: it checks first and stops quietly.
- Sending a photo or voice note no longer blocks the UI thread waiting on
  the mesh thread (a hang for good once the service was gone); a refusal
  shows as a local line in that chat.
- Delivered receipts over Nostr are sent once per message; a repeat is
  answered only within 10 minutes of its timestamp. Before, every reconnect
  replayed two days of envelopes and each got a fresh receipt, which also
  told the sender when this phone restarted.
- 104 unit tests in all (10 new).
- Release builds are signed with a dedicated release key when
  `android/keystore.properties` is present (git-ignored, see
  android/README.md). Previews up to v0.5.0 were signed with the build
  machine's debug key, which cannot be recovered if that machine is lost
  and would have forced every user to reinstall at some later, worse time.
  The first release-signed preview will not install over v0.5.0 or
  earlier: uninstall first. Identity, favorites and verifications are lost
  with the uninstall; this is the one-time cost of making the switch now.
- `/help` lists `/drop` in location channels.

Second round from the same review (link layer, engine, wipe, UI). Still
no wire-format change.

- Bluetooth link layer: a link's send queue used to shed the oldest chunks
  when it overflowed, which cut a frame in half and left the other phone's
  framer hunting for the next header inside our tail. A frame now goes out
  whole or is refused whole. Fragments are paced to what the link actually
  takes (the engine asks the link how much it still holds) instead of a
  fixed 25–30 ms clock, and a send that cannot drain in 30 s is abandoned.
  The receiving framer drops a partial frame that stalls for 250 ms ([spec
  §1](docs/protocol-spec.md)), applies the per-type payload caps (§2.7)
  rather than the global 1.2 MB one, resynchronises one byte at a time
  after lost traffic, and only hands up frames that decode. A link whose
  stack refuses 40 writes in a row is dropped instead of retried every
  50 ms forever. A central's prepared (long) write now gets its execute
  response. 3 new tests.
- A connection attempt that times out before the link is ready now clears
  every table it touched; before, the address could stay "connected" in our
  bookkeeping and was never tried again until the other phone rotated its
  Bluetooth address (up to 15 min).
- A device that starts speaking as a new identity on the same link (panic
  wipe, peer-ID rotation) no longer leaves its old identity marked direct
  forever: the old peer expires like any other. A reassembled packet takes
  the TTL its fragments travelled with, so an announce carried in
  fragments cannot pass for a neighbour's.
- An announce that arrives as a history-sync reply is remembered (for
  verifying synced messages) but no longer creates or refreshes presence.
  Before, two neighbours could keep a departed peer "here" between them for
  up to 15 min, and private messages to it went to a handshake that could
  not complete instead of to a courier.
- A new link gets our announce as soon as the 1 s announce rate limit
  allows; before, a link that opened within a second of the last announce
  waited 15–38 s for the next periodic one. 4 new engine tests.
- Panic wipe: Tor is stopped, its directory (guards, state, cached
  descriptors) deleted and Tor restarted on fresh guards after a 2–8 s
  pause, with relay sockets closed in between, so the new identity's first
  contact with the relays shares no circuit with the old one. Receipts still
  waiting their turn in the Nostr pacer are discarded rather than sent as
  the old persona. Media files in the cache are deleted at every service
  start, since the timeline they belong to does not survive one. Message
  notifications are marked secret, so their text stays off the lock screen
  and out of the notification history.
- Tor: the ON state is now taken from the service's status, not from a
  static port number that outlives a stop, so a Tor restart no longer shows
  ON while it is still bootstrapping. A relay socket that was opened just
  as the route changed is dropped on open rather than kept as a clearnet
  socket. An `.onion` relay is never handed to the system resolver while
  Tor is off.
- Chat view: the list follows new messages only while you are at the end of
  it or the message is yours, so a history sync inserting older lines (or a
  timeline at its 500-line cap) no longer yanks you to the bottom. Photos
  and voice-note lengths are decoded off the main thread and cached, so
  scrolling a media-heavy chat does not stutter. The APK copy behind "share
  the app" no longer runs on the UI thread.
- Edge-to-edge drawing is requested explicitly (Android 15 enforces it), and
  the first-run screen keeps its language menu and start button clear of
  the system bars.
- 156 unit tests in all (8 new). `fragmentsReassemble` failed once in a
  full run and passed on every rerun; it uses a random payload and is on
  the list to pin down.

## Android v0.5.0 (preview) — 2026-10-29

- History sync, wire-compatible with bitchat's `requestSync` gossip
  ([docs/protocol-spec.md §14](docs/protocol-spec.md)). Every phone keeps
  the last 15 minutes of public messages (up to 1000) and the latest
  announce of each peer. Five seconds after a neighbour is identified, and
  every 15 s after that, it sends the neighbour a Golomb-coded set of the
  packet IDs it holds; the neighbour replies with the signed packets that
  are missing, marked `IS_RSR` with TTL 0. So someone who arrives late, or
  walks from one group to another, sees what was said before. Replies are
  accepted only from a neighbour asked within the last 30 s, answered at
  most 8 times per peer per 30 s, and verified like any other message;
  a departed sender's remembered key still verifies its old messages.
- The history is stored with the sealed mesh state (format v2, reads v1)
  and wiped by the triple tap.
- Only announces and public messages are synced. Fragments, files, group
  messages, prekey bundles and board posts stay out of scope.
- 10 new unit tests: packet ID, GCS bitstream against the spec, false-
  positive rate, newest-first trimming, request TLV encode/decode and
  tolerance, type flags, a late joiner receiving history one hop from the
  author, an unsolicited reply being dropped, and history persistence.
  60 tests in all.
- Block. Tap a name in a chat for a person card: private message or
  block. A blocked mesh peer's public and private messages, including
  courier mail, are dropped without a receipt, so they are not told; the
  phone still relays their packets for others. In location channels the
  block applies to that sender's key in that area only. Blocked people are
  listed on the Me tab with Unblock. The list is sealed on disk and wiped by
  the triple tap. 1 new test.
- @mentions. "@name" and "@name#abcd" are set in orange; a message that
  mentions me gets an orange outline and a notification when that chat is
  not open (only the suffixed form counts when someone nearby shares my
  name). Typing "@" in any composer offers the names around. 3 new tests.
- In-person verification, compatible with bitchat's `bitchat://verify`
  QR ([spec §16](docs/protocol-spec.md)). The Me tab shows a signed code
  valid for five minutes and scans others (camera via
  zxing-android-embedded, Apache-2.0). A valid scan marks that identity
  verified, and if the person is in range a Noise challenge makes them
  prove they hold the key. Verified names show ✓. The person card shows the
  full fingerprint with "They match, mark as verified" for a manual check.
  5 new tests.
- IRC-style commands in every composer, with suggestions as you type "/":
  `/msg`, `/who`, `/clear`, `/hug`, `/slap` (same wording as bitchat, so
  bitchat users see the same line), `/block`, `/unblock`, `/fav`, `/unfav`
  and `/help`. Replies are local lines in that chat and are never sent.
  3 new tests.
- Favorites, and private messages over the internet between mutual
  favorites, wire-compatible with bitchat ([spec §15](docs/protocol-spec.md)).
  Favoriting sends a `[FAVORITED]:npub` notice; once both sides have
  favorited each other, a private message to someone out of Bluetooth range
  goes over Nostr relays in bitchat's private envelope (kind 1059/13/14,
  `v2:` XChaCha20-Poly1305). Receipts come back the same way. The envelope
  code opens both recorded bitchat envelopes from upstream's test fixtures.
  Favorites show ★ in the private list; the chat title says "over the
  internet". 7 new tests.
- Location notes, as bitchat leaves them ([spec §17](docs/protocol-spec.md)):
  the Location tab lists notes left in the last 24 hours at this building
  and the eight cells around it, and has a box to leave one. `/drop` does
  the same from a location channel. Notes are Nostr kind 1 events with a
  24-hour NIP-40 expiry. 3 new tests.
- Photos and voice notes in Nearby and private chats, in bitchat's file
  format ([spec §18](docs/protocol-spec.md)). + picks a photo (re-encoded
  to a small JPEG); 🎙 records up to 30 s, ■ sends. Public media is a signed
  broadcast; private media goes only inside the Noise session, and only to
  a peer that says in that session it can take it (openchat now sends that
  `0x21` peer state, so bitchat phones will send it encrypted photos too).
  Received files are type-checked by their first bytes and kept in the
  cache the triple tap wipes. Needs the microphone permission, asked on
  first use. 5 new tests.
- Tor ([spec §19](docs/protocol-spec.md)). Relay traffic goes through Tor
  (Guardian Project's tor-android 0.4.8.16, BSD-3-Clause, in-process) by
  default, fail-closed: nothing connects until Tor is up. A switch on the Me
  tab turns it off and says what that exposes. Relay sends now queue until
  the socket opens instead of being dropped after 3 s. The APK grows to
  about 36 MB (Tor for arm64, armv7 and x86_64). 1 new test.
- Announces no longer list neighbours, so a listener cannot read the local
  social graph off the air (upstream made the same change on its own).
- Peer ID rotation ([spec §20](docs/protocol-spec.md)): implemented and
  checked against upstream's draft vectors, but not switched on. Upstream
  has not shipped it in either client, and a rotating ID would make an
  openchat phone invisible to every bitchat phone. 4 new tests.
- 94 unit tests in all. New dependencies: zxing core and
  zxing-android-embedded (Apache-2.0), tor-android and jtorctl (BSD-3).
- Smaller download. Previews are now the release build: R8-shrunk
  (classes 11 MB → 1.4 MB), ARM only (the x86_64 Tor library was for the
  emulator), native libraries compressed in the APK (Tor 7.6 MB → 3.1 MB
  per ABI). 36 MB → 9.5 MB. Still signed with the local debug key, so it
  installs over earlier previews. The shrunk build has not been run on a
  device yet.
- Private messages in location channels, as bitchat sends them
  ([spec §15.5](docs/protocol-spec.md)). Tap a name in a location channel,
  then Private message: end-to-end encrypted over Nostr between the two
  anonymous per-area identities, with receipts. These chats are listed in
  the Private tab with their area. 1 new test.
- Your own relays for private messages (Me tab, up to 8, `.onion` allowed),
  used alongside the four built-in ones, so blocking those four host names
  does not end private messages over the internet. 1 new test. 94 tests.
- versionCode 7, versionName 0.5.0. Installs over v0.4.0 (same debug key).
  94 unit tests pass.
- Release: <https://github.com/wolfqing/openchat/releases/tag/v0.5.0>,
  APK SHA-256 `e1512de2fbe499921e2a523322416e2fb389f19dcf14dff7d5761e77ac083f1d`.
- Not yet checked on a device or against a bitchat phone. This is the first
  R8-shrunk build; it has not been launched anywhere yet.

## Android v0.4.0 (preview) — 2026-10-29

- Store-and-forward, wire-compatible with bitchat's courier path
  ([docs/protocol-spec.md §13](docs/protocol-spec.md)). A private message
  for someone out of reach waits in an outbox for 24 h and is also sealed
  (one-way Noise X to their static key) and handed to up to three nearby
  phones, which carry it and hand it over when they meet the recipient.
  Carried mail spreads by spray-and-wait and is opaque to the carrier.
  openchat also carries mail deposited by others (40 envelopes, 2 per
  depositor). Receipts clear the outbox; resends and courier copies are
  delivered once. Outbox, carried mail and remembered peer keys are
  sealed on disk like the identity and wiped by the triple tap.
- Message states "Waiting for them" and "Carried by others" in every
  language; the Me tab shows how many sealed envelopes the phone carries.
- 11 new unit tests: upstream's golden courier vectors (TLV, recipient
  tag, signing pre-image), the Noise X seal, a three-phone carry-and-
  handover run, and outbox persistence. 50 tests in all.
- README now states what a courier learns about the mail it carries.
- versionCode 6, versionName 0.4.0. Installs over v0.3.1.
- Release: <https://github.com/wolfqing/openchat/releases/tag/v0.4.0>,
  APK SHA-256 `0a2ae15e0b835a40e93252fc399cd8773205a52d6402c2e9d63979150f3209e8`.
- Not yet checked on a device or against a bitchat phone.

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
