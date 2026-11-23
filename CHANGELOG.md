# Changelog

## Android v0.7.16 (preview) — 2026-11-23

Apple Music-inspired satin gray selected tab indicator and iMessage-inspired
2-piece floating Liquid Glass composer bar (detached circular `+` button +
refracting stadium input capsule) matched to the exact width of the bottom
navigation capsule across all chat screens. No wire-format change. 231 unit
tests. 9.7 MB (9,730,520 bytes, R8-shrunk release-signed preview).
APK SHA-256 `882449e9aeebbff43f7e6ee6a13df2a5c1d8b4a0d8d88f6548e37a8d777951aa`.

### Changed

- **Liquid Glass bottom tab bar (`FluidGlass`, `MainScreen`).**
  - Replaced the bright white active tab pill inside `CapsuleNavigationBar`
    with a satin translucent neutral-gray indicator (`#D1D1D1`-class over light
    glass, restrained graphite gray in OLED dark) with a concentric 4dp inner
    inset and crisp neutral charcoal idle tab icons/labels, inspired by Apple
    Music's iOS 26 Liquid Glass tab bar.
  - Refined light-mode Liquid Glass optical fill, lateral meniscus rim, and
    ambient drop shadow for cleaner daylight clarity over both blank canvas and
    scrolling content.
- **iMessage-style 2-piece Liquid Glass composer (`ChatPane`).**
  - Split the chat composer into an independent 46dp circular Liquid Glass `+`
    button and a refracting 46dp-minHeight Liquid Glass stadium input capsule
    with a subtle trailing mic/send control, aligned to the exact same
    `16dp` horizontal margin and `352dp` max width (`FLOATING_BAR_MAX_WIDTH`)
    as the floating bottom tab bar.
  - Upgraded voice recording, voice preview, and the `+` tactical command
    popover to matching full-width Liquid Glass surfaces, with auto-dismiss of
    the `+` popover when focusing the text field.

## Android v0.7.15 (preview) — 2026-11-22

Warm Titanium light & OLED Etched Obsidian dark V2 UI overhaul with preserved
AGSL Liquid Glass floating dock and Signal Orange active tabs, always-on
`FLAG_SECURE` screen capture protection across every screen and dialog, Tor
upstream SOCKS5/HTTP proxy configuration + privacy-safe diagnostic log export
+ clean unrestricted direct bootstrap, and full 18-language coverage for all
83 new strings. No wire-format change. 231 unit tests. 9.7 MB (9,717,548 bytes,
R8-shrunk debug-signed preview).
APK SHA-256 `a9fefdd584bb731c91f7cabccc8432c489f97c6386ce36a42eda89d696962f55`.

### Added

- **Always-on screen capture protection (`OpenChatApp`, `MainActivity`, `GlobePicker`).**
  - Registered `Application.ActivityLifecycleCallbacks` to apply
    `WindowManager.LayoutParams.FLAG_SECURE` (and `setRecentsScreenshotEnabled(false)`
    on Android 13+) across every activity window in the process (`MainActivity`
    and the ZXing QR `CaptureActivity`), with Compose `AlertDialog`,
    `ModalBottomSheet`, menus, and `GlobePickerDialog` inheriting or enforcing
    `SecureOn` by default with no bypass toggle.
- **Privacy-safe diagnostic log export & Tor upstream proxy (`DiagLog`, `Tor`, `ChatRepository`, `MeshService`, `Panic`, `MeScreen`, `LocationScreens`, `TorTest`).**
  - Added memory-only `DiagLog` ring buffer recording Tor control-socket events
    (`NOTICE`, `WARN`, `ERR`, `STATUS_CLIENT`, bootstrap `ORCONN`), network
    transport/proxy snapshots, and noise-filtered own-PID logcat; never logs
    chats, keys, or contacts and clears immediately on panic wipe.
  - Added one-tap **Export log** (via `FileProvider` share sheet) and **Copy**
    buttons in Me settings and the Tor stalled banner.
  - Added optional manual upstream proxy (`socks5://host:port`, `http://host:port`,
    or bare `host:port`) with live control-socket apply and backoff reset, plus
    8 new unit tests in `TorTest`.
- **18-language localization parity (`values*/strings.xml`).**
  - Translated all 83 new V2 UI, Tor proxy, and diagnostic strings across all
    18 supported locales with zero `lintDebug` missing-translation errors.

### Changed

- **V2 UI overhaul (`Theme`, `FluidGlass`, `MainScreen`, `MeshVisuals`, `WalkieTalkieDrawer`, `ChatPane`, `PrivateChats`, `NearbyPeersSheet`, `LocationScreens`, `MeScreen`, `BoardScreen`).**
  - Switched to Warm Titanium (`#F3F1EC`) light and Pure OLED (`#000000`) dark
    architectural palettes, converging status/online highlights into Signal
    Orange (`#FF551A`) and removing decorative blue/green clutter.
  - Upgraded private E2E dark bubbles to an etched obsidian vault (`#323846 →
    #242935` with 45° diagonal hatch and 1dp cold-silver hairline) and light
    bubbles to cold tungsten (`#43464D`), paired with a unified circular 3D
    knurled PTT disc.
  - Preserved the floating AGSL Liquid Glass bottom navigation capsule
    (`FluidGlassCapsule` + `fluidGlassDroplet`) with all 4 selected tabs
    (`Nearby`, `Private`, `Location`, `Me`) highlighted in Signal Orange.
  - Added **Clear local chat history** (keeping identity keys and verified
    contacts intact) on the Me tab.

### Fixed

- **Unrestricted Tor direct bootstrap & network state permission (`Tor`, `AndroidManifest.xml`).**
  - Added `android.permission.ACCESS_NETWORK_STATE` so `ConnectivityManager`
    can observe Wi-Fi/VPN switches and system HTTP proxy changes without
    silently failing with `SecurityException`.
  - Removed premature `FascistFirewall` / `ClientUseIPv6 0` throttling and
    initial-registration `DisableNetwork` flushes so direct Tor bootstraps keep
    full IPv4 + IPv6 multi-port mirror cycling; legacy managed `torrc` files
    are cleaned automatically on start and retry.

## Android v0.7.14 (preview) — 2026-11-20

Grain-free progressive top blur and iOS 27 Liquid Glass optical upgrade with
minimalist unified chrome, Tor `LocalBinder` readiness / local-proxy pass-through
and Nostr relay deduplication + failover for reliable location channels, and six
UX clarity & parity upgrades (message long-press actions, 6-state DM security
telemetry, Nearby send-scope toggle, encrypted location bookmarks + fine-cell
OpSec clarity, regional Notice Board filtering, and memory-only daily encounter
counts). No wire-format change. 223 unit tests. ~9.6 MB.

### Added

- **Message long-press action sheet (`ChatPane`, `MessageActionSheet`).**
  - Long-pressing any public or private message opens a borderless bottom sheet
    with **Copy**, **`@Mention`** (appends `@label ` to the composer draft and
    focuses the input via `appendMentionDraft`), **Direct Message** (`dm:<peer>`
    on mesh or anonymous `gdm:<geohash>:<pub>` in location rooms), **Profile /
    Verify**, and **Block**.
- **6-state DM encryption & link telemetry (`PrivateChats`, `People`).**
  - Unified `resolveDmSecurityState` and `DmSecurityBadgeRow` across both the
    `PrivateChatScreen` header and `PersonSheet` so direct vs multi-hop Noise XX
    sessions, pending BLE handshakes, mutual Nostr NIP-17 links, Noise X courier
    sealing, and missing-key states are stated plainly and identically.
- **Nearby send-scope micro-capsule (`ChatPane`, `ChatRepository`, `MeshService`).**
  - When global Mesh Bridge is enabled, the `Nearby` composer surfaces a
    one-tap pill toggling between `Nearby + Bridge` and `Bluetooth Direct Only`
    (`nearbyLocalOnlySend`) with a 3.5-second explanatory hint.
- **Encrypted location bookmarks, fine-cell presence clarity & OpSec warning (`LocationPresets`, `LocationScreens`, `MeshService`).**
  - Added `☆ / ★` header bookmarking in `GeoChatScreen`, long-press bookmarking
    on preset tiles, and a top **Bookmarks** chip row on the Location tab,
    persisted in `SealedFile` (`geo-bookmarks.bin`) and wiped on panic.
  - High-precision rooms (`>= 6` geohash chars) now show `Connected · count
    hidden at block level` instead of a misleading `0` when connected with no
    recent speakers, plus a fine-cell screenshot/location OpSec banner.
- **Regional Notice Board filter & memory-only daily passed-devices count (`BoardScreen`, `MeshEngine`, `MeshService`, `MeshVisuals`).**
  - `BoardScreen` adds `All / Nearby Bluetooth / Regional` filter chips and a
    composer target selector (`Nearby Bluetooth` vs active `Region #xxxx` via
    `BoardPost.geohash`).
  - `NobodyNearby` displays a memory-only UTC-day unique peer counter
    (`DailyEncounterCounter`, cleared on process exit or panic wipe) when peers
    were heard earlier today.
  - Localized all 29 new strings across all 18 languages.

### Changed

- **Minimalist chrome, grain-free progressive top blur & iOS 27 Liquid Glass (`FluidGlass`, `Theme`, `MainScreen`, `ChatPane`, `PrivateChats`, `LocationScreens`, `BoardScreen`, `GuideScreen`, `WalkieTalkieDrawer`).**
  - Added a 3-octave hardware separable Gaussian `ProgressiveBlurTopBar`
    (`22dp / 13dp / 5.5dp` masked offscreen layers with zero grain) across main
    tabs and secondary screens so content scrolling behind the header and status
    capsule blurs smoothly while chat bubbles remain sharp.
  - Tuned `FluidGlassDefaults` and `FLUID_GLASS_AGSL` to iOS 27 optical crystal
    parameters (`ClarityBlur = 4.2.dp`, `Refraction = 12.5.dp`, `1.44x / 1.48x`
    chroma vibrancy, crease-free $C^1$ 3D pillow normal + Schlick Fresnel +
    `135°` Blinn-Phong specular glints, dual-pole rim, and raised convex
    `fluidGlassDroplet` active tab pills).
  - Grouped scattered top-right header pills into `GlassHeaderActionCluster`,
    merged the composer `+` / input / `Mic` / `Send` into a single `24dp`
    stadium bar, unified selected bottom tab icons + labels in `#FF551A` Signal
    orange, made the resting Walkie-Talkie PTT disc neutral monochrome, switched
    to pure `#F6F6F7` / `#000000` architectural canvases without neon radial
    blobs, and fixed `NobodyNearby` radar top clipping.

### Fixed

- **Tor startup readiness, system-proxy pass-through & Nostr relay resilience (`Tor`, `NetRoute`, `Nostr`, `RelayPool`, `GeoChannels`, `RelayDirectoryUpdater`, `MainActivity`).**
  - Fixed `Tor` getting stuck on `"Starting…"` (`banner_starting`): cleaned
    stale `data/ControlSocket` files before `TorService` bind, captured
    `TorService.LocalBinder` to query `status/circuit-established` and live
    `0–100%` `status/bootstrap-phase` off-thread (bypassing `tor-android`
    `0.4.8.16`'s 10s `FileObserver` missed-`CIRC BUILT` race), auto-configured
    `HTTPSProxy` / `Socks5Proxy` when Android reports a local proxy, added a 20s
    `TorStalledBanner` with one-tap fallback to direct connection, and restarted
    `MeshService` in `MainActivity.onResume` if stopped while backgrounded.
  - Stripped default `:443` ports and deduplicated `georelays.csv` (`441` rows
    to `326` unique hosts) with deterministic `(distance, host)` tie-breaking
    matching upstream bitchat; added automatic backup-relay failover (`#6–#15`)
    on connection failure or 6s event starvation, raised Tor WebSocket connect
    timeout to `25s`, tracked per-channel `connectedChannels`, and added
    fail-closed 24h background refresh of `online_relays_gps.csv` into
    `cacheDir/georelays_cache_v1.csv` (wiped on panic).

## Android v0.7.13 (preview) — 2026-11-19

Interactive full-screen Bluetooth mesh topology view, zero-permission local
region + one-tap quick-join location channels with encrypted persistence, and
balanced neutral chrome over the restored luminous daylight gradient. No
wire-format change. Signed with the release key ([SECURITY.md](SECURITY.md));
installs over v0.7.12 and earlier release-signed builds. 214 unit tests. 9.5 MB.
APK SHA-256 `6307c9784ba8e141037a843685bec86dabb7aece6086f851d13f255fe5abe0de`.

### Added

- **Interactive mesh topology screen (`MeshTopologyScreen`, `NearbyPeersSheet`, `MeshEngine`, `MainScreen`).**
  - Added a `Topology` pill in the `NearbyPeersSheet` header (accessible from the
    top signal capsule on Nearby, Private, and Location tabs, including while
    scanning alone) opening a full-screen pan/zoom radial topology canvas
    centered on this device.
  - Exposed peer-announced `neighbors` and locally observed `heardViaPeerHex` on
    `MeshPeer` without changing the wire format or broadcasting local neighbour
    lists; edges honestly distinguish direct BLE links (solid green), announced
    peer links (solid neutral), and locally observed relay ingress (dashed
    signal orange), and each node draws its 5×5 cryptographic emblem inline.
  - Tapping any node highlights the shortest path (`shortestTopologyPath`) and
    opens a docked inspector card with path breadcrumbs, profile sheet shortcut,
    and one-tap encrypted DM.
- **Local region recommendation, quick-join regional rooms & persistence (`LocationPresets`, `LocationScreens`, `GeoChannels`, `MeshService`).**
  - Without requiring location permission, the Location tab now highlights a
    tap-to-join local 2-char region derived purely offline from the device time
    zone and locale, alongside a 2-column Quick Join grid of 12 curated regional
    channels (e.g. `#dr`, `#9q`, `#wx`, `#wt`, `#ws`, `#xn`, `#gc`, `#u0`, plus
    4 expandable regions).
  - Joined location channels now persist across restarts in an encrypted
    `SealedFile` (`geo-joined.bin`) and are destroyed automatically on panic
    wipe; `GeoChatScreen` displays localized region names alongside known
    `#geohash` titles, and manual entry strips leading `#`.
  - Localized all 24 new topology and location-preset strings across all 18
    languages.

### Changed

- **Restored luminous background gradient with neutral chrome (`Theme`, `FluidGlass`, `ChatPane`, `MainActivity`).**
  - Restored the airy `#FFFFFF → #F6F7FA → #EFF1F5` daylight canvas gradient
    with warm Signal-orange top-left and soft Lock-blue bottom-right radial
    glows (and matching subtle glows on `#000000` OLED dark), pairing it with
    achromatic light-neutral pills and buttons (`#F1F1F3` / `#F2F2F4`) and clear
    neutral frosted navigation glass — avoiding both cold light-blue and
    yellow-beige casts.
  - Removed the square background tile behind the top-left OpenChat wordmark
    (`PanicLogo`, `Onboarding`), leaving only the clean Signal-orange lettering.
  - Ordered location precision rows from broadest (`Region`) to finest (`Block`)
    when location is granted so the liveliest rooms sit at the top.

## Android v0.7.12 (preview) — 2026-11-19

Tappable nearby reachable peers sheet from the top signal capsule, locked
cross-tab status capsule vertical alignment, and Warm Stone daylight palette
replacing cool light-blue pills and buttons across every screen. No wire-format
change. Signed with the release key ([SECURITY.md](SECURITY.md)); installs over
v0.7.11 and earlier release-signed builds. 209 unit tests. 9.5 MB.
APK SHA-256 `2878e41e4a3ca7323996fd6a3c50dbabdd3c88d9f02546d2a6e613c4a6bf9b47`.

### Added

- **Nearby reachable peers sheet (`NearbyPeersSheet`, `MeshVisuals`, `MainScreen`).**
  - Tapping the top `Direct X · Reach Y` signal capsule on Nearby, Private, or
    Location tabs opens a live `ModalBottomSheet` listing everyone currently
    reachable over the Bluetooth mesh (when `reach > 0` and no live PTT speaker
    banner is active), with a subtle trailing chevron affordance.
  - Peers sort by direct neighbours first, then hop count, then most recently
    heard (`sortNearbyPeers`), showing emblem + direct/relayed dot, trust/star
    badges, hop distance and recency; tapping a row opens `PersonSheet` for
    fingerprint verification/actions, and the trailing 44dp chat button jumps
    straight into an encrypted private chat.
  - Localized across all 18 languages (`nearby_peers_*`).

### Changed

- **Warm Stone light palette & floating white chrome (`Theme`, `FluidGlass`, `MainActivity`, all UI screens).**
  - Replaced the cool light-blue canvas (`#F1F5FA` / `#F4F7FC` + bottom blue
    glow) and light-blue capsule/button fills (`#E5ECF4` / `#E7EDF5` /
    `#EDF2F8`) with a Warm Stone paper canvas (`#F4F2EC`), pure-white floating
    pills/buttons/cards (`#FFFFFF`), and warm-stone tonal fills (`#EFECE5` /
    `#F2F0EA`) automatically applied inside elevated cards, sheets, and drawers
    via `LocalOnElevatedSurface`.
  - Own public-channel bubbles now use crisp paper white instead of light blue,
    keeping Lock Blue (`#1A6BFF` / `#DCEBFF`) strictly exclusive to end-to-end
    encrypted private conversations.
  - Removed blue-gray tints from the bottom Liquid Glass navigation bar,
    shadows, hairlines, and dark-mode neutral charcoal surfaces (preserving pure
    `#000000` OLED dark ground).

### Fixed

- **Status capsule vertical alignment across tabs (`MeshVisuals`).**
  - Locked `NearbyStatusCapsuleRow`, the left status capsule, and the right
    `Talk` pill to a shared `32dp` minimum height with explicit `16sp` line
    height (preventing the `Talk` button from inheriting `bodyLarge`'s `24sp`
    line height and shifting the status capsule ~4dp lower on Nearby than on
    Private and Location tabs), and unified `SignalPulseDot` to `16dp` across
    both scanning and connected states.

## Android v0.7.11 (preview) — 2026-11-19

Borderless tonal surface hierarchy across every screen: removed over 40 `1dp`
wireframe strokes and `OutlinedButton`s in favour of clean tonal fills, ghost
navigation icons, and `12dp` rounded-rectangle card actions. No wire-format
change. Signed with the release key ([SECURITY.md](SECURITY.md)); installs over
v0.7.10 and earlier release-signed builds. 208 unit tests. 9.5 MB.
APK SHA-256 `f3c7bb462dc43988d69feef19566fab5ccdcb0f88517d060282123a28a0b44d6`.

### Changed

- **Borderless tonal buttons and navigation (`FluidGlass`, `MainScreen`).**
  - Removed the `1dp` outline from `FluidGlassCircleButton`,
    `FluidGlassPillButton`, `FlatCapsule` (unless focused), and `PanicLogo`,
    relying on clean tonal contrast in both Radiant Light and OLED Dark themes.
  - Turned `GlassBackButton` into a pure `48dp` borderless ghost back icon and
    softened the bottom frosted navigation bar's specular rim.
- **Replaced wireframe `OutlinedButton`s with `12dp` `TonalActionButton` (`MeScreen`, `People`, `PrivateChats`).**
  - Verification actions, update checks/retries, peer favorite/block actions,
    group leave/close buttons, and "Erase everything" now use borderless `12dp`
    tonal rectangles (with soft danger fills for destructive actions instead of
    red-outlined boxes inside red-outlined cards).
- **Borderless cards, bubbles and composer (`ChatPane`, `MeshVisuals`, `LocationScreens`, `BoardScreen`, `WalkieTalkieDrawer`, `GuideScreen`, `Onboarding`).**
  - Removed decorative borders from `SettingsGroupCard`, chat message bubbles
    (keeping the signal ring strictly for `@mention` alerts), the microphone and
    recording capsules, status bands, notice-board cards, walkie-talkie drawer,
    guide cards, and onboarding cards; the text composer shows a subtle border
    only while focused.

## Android v0.7.10 (preview) — 2026-11-17

Random callsigns by default instead of `"anon"`, across fresh installs, panic
wipes, and upgrades from older previews. No wire-format change. Signed with the
release key ([SECURITY.md](SECURITY.md)); installs over v0.7.9 and earlier
release-signed builds. 208 unit tests. 9.5 MB.
APK SHA-256 `60acaaaef7f5d60e64c1af1009b18820fb8eed612d6a4e302213c92cea07ab44`.

### Changed

- **Random default callsigns (`RandomNicknames`, `Identity`, `IdentityStore`, `MeshService`, `Panic`).**
  - Extracted the curated Simplified Chinese, Traditional Chinese, and Latin
    callsign pools (`ZH_HANS`, `ZH_HANT`, `LATIN`) into a shared
    `RandomNicknames` helper matching the in-app locale.
  - First launch generates a random callsign instead of `"anon"`.
  - Upgrading an existing install whose saved nickname is still the legacy
    default `"anon"` (or blank) automatically replaces it once with a random
    callsign while keeping all keys, `peerId`, fingerprint, and contacts intact.
  - Triple-tap / "Erase everything" panic wipes pick a fresh random callsign
    guaranteed to differ from the wiped identity's name and reset migration
    metadata.

## Android v0.7.9 (preview) — 2026-11-16

Dedicated half-duplex **Live Walkie-Talkie (`对讲 / 实时对讲`)** drawer for
`Nearby` public Bluetooth mesh (`0x29`) and `Private` 1-on-1 Noise XX chats
(`0x08`), modeled after real two-way handheld radios and WeChat's live
walkie-talkie: single-speaker floor lock, synthesized radio chirp/Roger/busy
tones, pure live playback with zero chat-bubble clutter, and a clean split from
ordinary `.m4a` voice notes. Localized across all 18 languages. No wire-format
change. Signed with the release key ([SECURITY.md](SECURITY.md)). 206 unit
tests. 9.5 MB.
APK SHA-256 `0be05769114d101de39ee1e3a00e62f71d0e76ec09bc4c120f16404d1d146084`.

### Added

- **Half-duplex Walkie-Talkie drawer & entry points (`WalkieTalkieDrawer`, `MeshVisuals`, `MainScreen`, `PrivateChats`, `ChatPane`).**
  - Added a persistent `[ Talk / 对讲 ]` pill on the right of `NearbyStatusCapsuleRow`
    and in `PrivateChatScreen`'s top bar, clickable active-speaker banners, and
    a `[ Photo ]` + `[ Live Walkie-Talkie ]` 2-pill `+` attachment tray in
    `Composer`.
  - Opening Walkie-Talkie swaps the single-line composer for a docked `216dp`
    flat panel keeping upper chat messages, photos, and hop badges visible,
    featuring a `116dp` hold-to-talk disc, concentric voice-level rings,
    active-speaker `EmblemView` + hop count, quick mute toggle, and a one-tap
    `[ Keyboard ]` switch.
- **Half-duplex channel floor coordinator (`PttFloorCoordinator`, `PttFloorCoordinatorTest`).**
  - Enforces single-speaker occupancy per channel (`Channel Busy` lock while a
    peer speaks, dropping concurrent 3rd-party bursts), deterministic lowercase
    `peerIdHex` tie-breaking on simultaneous BLE TX collisions, `300 ms`
    minimum hold (with tap hint), `60 s` TOT auto-release (with 5 s countdown),
    and `3 s` RX idle release.
- **Synthesized two-way radio cues (`PttTones`).**
  - Zero-asset `16 kHz` PCM sine-wave cues with `5 ms` cosine fade-in/out:
    rising start chirp (`880 Hz -> 1175 Hz`, played before opening `AudioRecord`
    so it never bleeds into transmitted audio), clean `1046 Hz` Roger beep on
    TX/RX completion, and `330 Hz` double-beep on busy/preempted floor.

### Changed

- **Pure-live walkie-talkie streaming & decoupled voice notes (`LiveVoice`, `MeshService`, `ChatRepository`, `MeshEngine`, `MainActivity`).**
  - Walkie-talkie bursts stream AAC-LC `16 kHz` frames directly over `§29`
    without creating chat voice bubbles; trailing `voice_<burst>.m4a` packets
    from stock bitchat peers are absorbed when already heard live and kept as
    missed voice notes when muted or backgrounded.
  - Decoupled the composer's right `Mic` button exclusively to `VoiceRecorder`
    for ordinary `.m4a` voice notes (hold to send, or tap to record & preview).
  - Auto-cancels active PTT transmission on transient audio-focus loss (phone
    calls/alarms), Bluetooth power-off, app backgrounding, or triple-tap panic.
- **18-language localization & Usage Guide (`values*/strings.xml`, `GuideScreen`).**
  - Added 17 `ptt_*` string resources across all 18 locales and updated the
    Usage Guide & `Me` Live Walkie-Talkie copy and vector icon.

## Android v0.7.8 (preview) — 2026-11-15

Focused real-device polish: bottom-only frosted Liquid Glass navigation bar
with flat chrome & composer everywhere else, system-wide Dark-theme text color
fix, top-left OpenChat vector logo, portrait QR scanner, voice recording stop &
preview, right-aligned own public messages, 3D globe city dots & smooth pinch
zoom, larger radar dial, one-tap random nicknames, and an in-app Usage Guide
across all 18 languages. No wire-format change. 201 unit tests. ~9.4 MB.

### Added

- **In-app Usage Guide (`GuideScreen`, `MainScreen`, `MeScreen`, `MeshVisuals`, all 18 `strings.xml`).**
  Added a structured, vector-illustrated **User Guide (`使用说明`)** reachable
  from the `Me` tab top-right header pill, a dedicated card in `Me`, and the
  `NobodyNearby` radar screen—explaining Offline Bluetooth Mesh, Noise XX
  encrypted chats & courier envelopes, Live PTT & voice notes, 3D Globe
  location channels over Tor, Nearby Notice Board & Place Notes, local
  identity, QR verification, triple-tap panic wipe, and slash commands.
- **One-tap random nickname generator (`MeScreen`, `RandomNicknamesTest`).**
  Editing your name in `Me` now includes a **Random (`随机`)** button and
  in-field shuffle button backed by 48+ curated historical & callsign pools per
  locale (`zh-Hans`, `zh-Hant`, Latin) guaranteed compatible with `#xxxx` peer
  suffixes.

### Fixed & Improved

- **Bottom-only frosted Liquid Glass (`FluidGlass`, `MainScreen`, `ChatPane`, `MeshVisuals`).**
  Reserved optical Liquid Glass exclusively for the bottom navigation bar
  (`CapsuleNavigationBar`) with richer `7.5dp` frosted clarity blur, while
  converting the chat composer (`+`, input capsule with crisp pale-white /
  ultra-light-grey backing, `Mic`/`Send`), header pills, status capsules, and
  action buttons to clean zero-shadow flat surfaces.
- **Top-left OpenChat vector logo (`PanicLogo`).**
  Restored the flat rounded `OPEN / CHAT` pixel-matrix vector logo in the
  top-left `48dp` triple-tap header target instead of a single dot.
- **Dark-theme black-text bug fixed system-wide (`Theme`, `Scaffold`s, `BoardScreen`, `FluidGlass`).**
  Provided `LocalContentColor = palette.ink` inside `OpenChatTheme` and every
  `Scaffold(containerColor = Color.Transparent)`, updated `DarkOpenChatColors`
  (`onSignal = Color.White`) and `OpenChatDarkScheme` (`primary = signal`,
  `onPrimary = Color.White`), eliminating unreadable black text on dark screens
  and buttons.
- **QR code scanner locked to portrait (`AndroidManifest.xml`, `MainActivity`).**
  Overrode `CaptureActivity`'s default `sensorLandscape` manifest orientation
  to `portrait` so scanning a verification QR no longer rotates sideways.
- **Voice recording stop, preview & discard (`ChatPane`, `ChatRepository`, `MeshService`).**
  Tapping `Mic` now shows a dedicated **Stop (`■`)** button alongside Cancel
  and Send; stopping enters a playback preview capsule (`▶` waveform + Delete +
  Send) instead of forcing immediate transmission.
- **Own messages right-aligned in public channels (`PublicPostRow`).**
  Local messages (`mine == true`) in `Nearby` and `Location` channels now align
  to the right with a flat matte bubble and the local user's `EmblemView`
  avatar on the right.
- **3D Globe city dots & smooth pinch zoom-out (`GlobePicker`).**
  Fixed Light-mode theme leakage that turned city dots and labels black-on-dark
  ocean, added glowing `dp`-scaled city markers with shadowed labels, and
  removed post-pinch snap-back (`settle()`) and single-finger lift pan jumps.
- **Larger radar dial (`MeshVisuals`).**
  Expanded the `NobodyNearby` radar disc up to `296dp` (`80%` width) with
  crisper range rings and center hub contrast.

### Checked, and not

- Checked on GitHub Actions CI (`ubuntu-latest`, Temurin JDK 17): 201 unit
  tests pass, `lintDebug` and `lintVitalRelease` 0 errors, debug and R8 release
  APKs build cleanly (`app-release.apk`).

## Android v0.7.7 (preview) — 2026-11-15

Anisotropic physical Liquid Glass rim for Radiant Light and full visual
alignment with the refined zero-fog Liquid Glass design prototype
([ui-prototype.html](docs/design/ui-prototype.html)). No wire-format change.
199 unit tests. ~9.4 MB.

### Improved

- **Anisotropic Light-theme Liquid Glass rim & daylight separation (`FluidGlass`).**
  Floating Liquid Glass capsules, circular action orbs, and active tab water
  droplets now render circular-arc cosine-projected **thin dark refractive
  left/right flanks** (`#14263C` at `50%` alpha on clear glass; darkened
  amber/cobalt on accented glass) paired with **thin pure-white `#FFFFFF`
  top/bottom specular catchlights**, a `0.60dp` `13%` cool-slate outer contact
  contour, inner vertical specular sheen, and crisp cool-slate elevation
  shadows—plus directional lateral absorption and `1.22x` chroma clarity in the
  AGSL shader (`FLUID_GLASS_AGSL`)—so light-mode glass never melts into bright
  daylight canvases while preserving the locked `15dp / 6dp / 2.0dp` optical
  parameters, zero milky surface fog, and zero artificial inner crescent arcs.
- **Full fidelity to the refined Radiant Light & OLED Dark design (`Theme`, `MainScreen`, `ChatPane`, `MeshVisuals`, `PrivateChats`, `LocationScreens`, `BoardScreen`, `MeScreen`, `People`, `Onboarding`).**
  - **Atmospheric canvas (`Modifier.openChatCanvas()`)**: replaces flat solid
    `#F4F7FC` fills across chat timelines and tab screens with a vertical
    `#FFFFFF -> #F4F7FC -> #EBF1F8` daylight gradient + top-left warm
    Signal-orange (`10.5%`) and bottom-right cool Lock-blue (`10.5%`) ambient
    radial glows (and `#000000` + subtle warm/cool radial glows in OLED Dark).
  - **Transparent floating header + full-screen live refraction**: `TopAppBar`
    and `NearbyStatusCapsuleRow` / `RouteBand` float transparently over a
    full-screen `.fluidGlassSource()` container (`0..H`) without opaque header
    boxes or full-width grey hairlines, featuring a `36dp` crystal `PanicLogo`
    orb with glowing Signal-orange core, `GlassBackButton` on secondary
    screens, Signal-orange `Board` pushpin, and compact intrinsic-width status
    capsules (`3 direct · 11 reachable · 4s ago`).
  - **Refined timeline & card details**: rounded inset `@mention` cards
    (`#FFF1EA` Light / `#22120B` Dark) and inline monospace `@mention` pill
    badges, top-right Signal-soft hop badges (`2 hops`) beside timestamps,
    text-indented `58dp` `7%` dividers, radial-gradient jewel `EmblemView`
    avatars, Signal-orange / Lock-blue circular voice-note play buttons, clean
    composer placeholders with translucent crystal `Mic` orbs, ultra-clear
    `fluidGlassDroplet` active tab indicators, and `76%` translucent settings
    and tip cards.

### Checked, and not

- Checked on GitHub Actions CI (`ubuntu-latest`, Temurin JDK 17): 199 unit
  tests pass, `lintDebug` and `lintVitalRelease` 0 errors, debug and R8 release
  APKs build cleanly (`app-release.apk`). Because `android/keystore.properties`
  stays offline ([SECURITY.md](SECURITY.md)), the CI-built APK attached to the
  GitHub pre-release (`openchat-0.7.7-ci-debug-signed.apk`, SHA-256 in the
  release notes) uses the CI debug key.
- Not checked on this build: real-device BLE mesh radio and stock bitchat
  interop.

## Android v0.7.6 (preview) — 2026-11-15

Hotfix for an immediate launch crash in v0.7.5 when entering `MainScreen`.
No wire-format change. 199 unit tests. ~9.4 MB.

### Fixed

- **Launch crash on `RenderThread` (`FluidGlass`, `MainActivity`, all tab panes).**
  In v0.7.5, empty-state and inline `FluidGlassSurface` pills (`AudiencePill`,
  the Nobody-Nearby share pill, the Private empty-state pill, Location geohash
  chips, and Me's language picker) were composed inside `.fluidGlassSource()`
  backdrop containers. Recording the container into `GraphicsLayer` while child
  glass surfaces called `drawLayer` on that same ancestor layer created a cyclic
  `RenderNode` display-list graph (`RenderNode A -> RenderNode B -> RenderNode A`),
  causing `android::uirenderer::RenderNode::prepareTreeImpl` in `libhwui.so` to
  overflow `RenderThread`'s stack (`SIGSEGV` / `SEGV_ACCERR`) on Frame 1 of
  `MainScreen`. `FluidGlassSource` now enforces scope isolation at compile time
  via `LocalInsideFluidGlassSource` so nested glass surfaces render standalone
  glass without sampling their ancestor backdrop, gives each tab pane its own
  `RecordedBackdropSource` graphics layer, skips backdrop shader layers for
  top-bar pills outside `sourceSize`, and moves `enableEdgeToEdge` updates from
  per-recomposition `SideEffect` to `LaunchedEffect(isDark)`.

### Checked, and not

- Checked on GitHub Actions CI (`ubuntu-latest`, Temurin JDK 17): 199 unit
  tests pass, `lintDebug` and `lintVitalRelease` 0 errors, debug and R8 release
  APKs build cleanly (`app-release.apk`). Verified the R8-shrunk release APK on
  an Android 14 (`API 34`) emulator across fresh onboarding, tapping Start into
  `MainScreen`, subsequent cold launches (both before and after runtime
  permissions + `MeshService` foreground startup), all four tabs (`Nearby`,
  `Private`, `Location`, `Me`), and `Board` with 0 crashes. Because
  `android/keystore.properties` stays offline ([SECURITY.md](SECURITY.md)), the
  CI-built APK attached to the GitHub pre-release
  (`openchat-0.7.6-ci-debug-signed.apk`, SHA-256 in the release notes) uses the
  CI debug key; uninstalling an earlier build signed with a different key may
  be required before installing.
- Not checked on this build: real-device BLE mesh radio and stock bitchat
  interop.

## Android v0.7.5 (preview) — 2026-11-15

In-tree AGSL Liquid Glass floating chrome, Radiant Light + OLED Dark dual
appearance, Live PTT walkie-talkie header feedback, flat matte chat bubbles,
and pure vector icons across the app and prototype
([ui-direction.md](docs/design/ui-direction.md),
[ui-prototype.html](docs/design/ui-prototype.html)). No wire-format change.
199 unit tests. 9.4 MB (`9,372,068` bytes).

### Added

- **Radiant Light and OLED Dark themes (`AppTheme`).** System-following default
  (`SYSTEM`) plus manual `Radiant Light` (`#F4F7FC` daylight canvas, `#FFFFFF`
  cards) and `OLED Dark` (`#000000` canvas) selector on the Me tab and quick
  switcher on Onboarding, backed by `values-night` window themes and dynamic
  status/navigation bar icon contrast. English (`en`) remains the default UI
  language when unset.
- **In-tree AGSL Liquid Glass chrome (`FluidGlass`).** Floating top status
  capsule, floating bottom `CapsuleNavigationBar` (preserving the v0.7.4
  semantic tab icons and labels), and floating three-piece `Composer`
  (`[ + ] [ Capsule Input ] [ Mic/Send ]`) capture the live scrolling canvas
  via Compose `GraphicsLayer`. On Android 13+ (`API 33+`), an in-tree AGSL
  rounded-capsule SDF shader renders Snell edge refraction (`15 dp`), RGB
  chromatic dispersion (`6 dp`) and clarity micro-blur (`2 dp`) with zero
  interior surface fog and crisp directional Fresnel rims (without decorative
  crescent arcs on `+` or `Mic`); Android 12 (`API 31–32`) uses hardware
  RenderEffect blur and Android 8–11 (`API 26–30`) falls back to translucent
  Fresnel-rimmed glass.
- **Live PTT walkie-talkie feedback (`LiveVoice`, `ChatRepository`, `MeshVisuals`).**
  Incoming live voice bursts morph the header signal/route capsule into an
  animated four-bar equalizer (`Live PTT · Name#xxxx`) paired with a one-tap
  Live Voice mute/unmute toggle; holding the mic button renders real-time PCM
  microphone amplitude bars (`pcmLevel()`).
- **18 languages.** Theme and Live PTT UI strings localised across all 18
  resource locales (281 translatable keys per locale).

### Changed

- **Flat matte encrypted bubbles and pure vector icons.** Private DM and group
  message bubbles render flat (`0 dp` tonal and shadow elevation, solid `Card`
  / `LockSoft` fills, `1 dp` hairline borders) so the four-dot delivery path
  stays primary. Remaining inline Unicode badge glyphs (star, check, zap) and
  prototype emojis are replaced with Compose `ImageVector` / `InlineTextContent`
  badges and inline SVG paths.
- **Updated design prototype and screenshots.** Refreshed
  [ui-prototype.html](docs/design/ui-prototype.html) and rendered screenshots
  (`prototype-three-screens.png`, `prototype-nearby.png`,
  `prototype-private.png`, `prototype-empty.png`) with Radiant Light + OLED
  Dark themes, SVG `<feDisplacementMap>` optical refraction (`15 / 6 / 2.0`),
  flat matte bubbles, and Live PTT preview.

### Checked, and not

- Checked on GitHub Actions CI (`ubuntu-latest`, Temurin JDK 17): 199 unit
  tests pass (including `AppThemeTest` and live-PTT `ChatRepositoryTest`
  cases), `lintDebug` and `lintVitalRelease` 0 errors, debug and R8 release
  APKs build cleanly with 0 R8 Kotlin-metadata complaints (`app-release.apk`,
  9.4 MB). Because `android/keystore.properties` stays offline
  ([SECURITY.md](SECURITY.md)), the CI-built APK attached to the GitHub
  pre-release (`openchat-0.7.5-ci-debug-signed.apk`, SHA-256 in the release
  notes) uses the CI debug key; installing over a release-signed v0.6.0–v0.7.4
  build requires re-signing from tag `v0.7.5` with the dedicated release key
  (or uninstalling first on a test device).
- Not checked on this build: Android emulator UI run and real-device BLE mesh
  radio / stock bitchat interop.

## Android v0.7.4 (preview) — 2026-11-13

In-app update discovery, Tor-routed download and dual SHA-256 + signing-certificate
verification. Adds `REQUEST_INSTALL_PACKAGES` for handing verified update APKs
to the system package installer. No wire-format change. Same release key as
v0.6.0–v0.7.3; installs over them. 195 unit tests. 9.3 MB.
APK SHA-256 `0d479681aaa1a888172f459f3e98c67ecbfa027b935cdf2933d906a70d9deb52`.

### Added

- **In-app updates on the Me tab (`AppUpdater`).** Manual "Check for updates"
  plus an opt-in "Check automatically on launch" toggle (off by default, with a
  signal-orange dot on the Me tab when a newer release is available).
- **Tor fail-closed routing.** Update checks and downloads go through
  `NetRoute.client()`: when Tor is enabled, nothing reaches GitHub until Tor's
  SOCKS port is up, and any route change mid-transfer aborts the active stream.
- **Dual verification before install.** Fetches `SHA256SUMS` from the same
  release, hashes the streamed APK incrementally, and verifies package name,
  non-downgrade `versionCode`, and exact signing-certificate match against the
  running app before opening the system package installer.
- **Panic wipe coverage.** Wiping cancels any active check or download, deletes
  cached update APKs, and resets the launch auto-check toggle to off.
- **18 languages.** Update UI strings localised across all 18 resource locales.

### Checked, and not

- Checked locally from this tree with the release key: 195 unit tests pass
  (including `AppUpdaterTest`), `lintDebug` and `lintVitalRelease` 0 errors,
  0 Kotlin compiler warnings, debug and R8 release APKs build cleanly, signing
  certificate matches [SECURITY.md](SECURITY.md).
- Not checked on this build: Android 15 emulator (`openchat35`, host disk 2.7 GB
  free) and real-device radio / bitchat interop.

## Android v0.7.3 (preview) — 2026-11-13

UI and UX overhaul across every screen to match the design prototype
([ui-direction.md](docs/design/ui-direction.md),
[ui-prototype.html](docs/design/ui-prototype.html)). No wire-format change.
Same release key as v0.6.0–v0.7.2; installs over them. 191 unit tests. 9.3 MB.
APK SHA-256 `c234e62b8c43d8abcebbc2715818685b6545021158bb8aa2ea2c0767cf348069`.

### Changed

- **Palette and semantic colour discipline.** Pure-black screen canvas
  (`#000000`) for OLED battery life, four cool-dark surface steps (`#101414` /
  `#151B1B` / `#1C2424` / `#222929`), signal orange (`#FF7840`) reserved for
  the mesh and radio layer, and lock blue (`#93BCE8` on `#13263A`) for
  encrypted chats. Window background and system bars are pinned dark so there
  is no light flash on launch or illegible status-bar icons under a light
  system theme.
- **Public feeds vs encrypted chats.** Nearby and location channels now render
  as full-width bulletin rows with emblems, hop badges and hairline rules
  instead of chat bubbles; private DMs and groups use asymmetric grouped
  bubbles with `LockSoft` outgoing fills. Consecutive messages from the same
  sender within two minutes collapse into a single visual burst.
- **Capsule composer and voice notes.** Replaced the tall outlined text field
  with a compact 22 dp capsule composer, floating `/command` and `@mention`
  autocomplete chips, an animated Mic / Send toggle, a dedicated voice
  recording bar with live timer and cancel button, and a 20-bar deterministic
  waveform with playback progress on voice notes.
- **Navigation and sheets.** Directional slide-and-fade transitions when
  opening and closing chats (respecting system reduced-motion settings),
  compact top-bar pill actions (`Board` on Nearby, `New group` on Private),
  and bottom sheets (`ModalBottomSheet`) replacing alert dialogs for person
  cards, QR codes, group members and notice-board composition.
- **Private list, Channels, Board, Me and Onboarding.** Smart private-chat
  sorting (unread, recent, nearby) with corner reachability dots and delivery
  prefixes; quick-switch pills and approximate geohash cell spans on Channels;
  FAB composer on the notice board; structured identity card (emblem, inline
  nickname edit, QR button, copyable 4×4 fingerprint grid) and grouped
  settings cards on Me; language switcher on the first-launch onboarding header.

### Checked, and not

- Checked locally from this tree with the release key: 191 unit tests pass,
  `lintDebug` 0 errors, debug and R8 release APKs build cleanly with no
  Kotlin-metadata warnings, signing certificate matches
  [SECURITY.md](SECURITY.md).
- Not checked on this build: the Android 15 emulator (`openchat35`) refused to
  boot on the build machine due to low free disk space (`2.7 GB` available),
  and nothing has been checked over a real radio yet.

## Android v0.7.2 (preview) — 2026-11-09

Safety, battery and language fixes from the 2026-11-09 review. No wire-format
change. Same release key as v0.6.0–v0.7.1; installs over them. 190 unit
tests. 9.2 MB.
APK SHA-256 `98c485b481def07351b5872da8ff27445b0b7d7f022e401fa499531fa75d1b2f`.

### Safety

- **The panic wipe no longer depends on the mesh service.** The triple tap
  and "Erase everything" only asked the running service to wipe. With no
  service (Bluetooth permission revoked or reclaimed by the system, saved
  identity locked, service stopped) nothing was erased: identity, contacts,
  outbox and groups stayed on disk, and the dialog still said "Erased".
  Now the app erases the sealed files, the Keystore key, the caches, Tor's
  directory and the settings a wipe resets by itself, starts the service
  again when it may, and the dialog says what happened: erased with a new
  identity, erased with a new identity to come, or not everything could be
  erased. The service's own wipe sweeps the whole sealed-file directory
  instead of the files it knows by name, so a file a later feature adds is
  covered.
- **A locked identity has a way out.** The banner now says that "Erase
  everything" on the Me tab starts over with a new identity, and in that
  state it works (see above). Before, the only exit was clearing the app's
  storage in the system settings.
- **Sealed files are synced to disk before they are renamed into place.**
  Without the sync a power cut could leave an empty file under the real
  name: an identity that never unlocks again.
- **A damaged groups file no longer crashes the app at every start.** It
  was the one sealed file read without a guard, on the mesh thread; the
  sticky restart ran into the same file again. It now counts as no groups.
- **"Not delivered" is no longer final.** A message is marked so when it
  leaves the outbox after 24 hours, but a courier can still hand it over,
  and the receipt can come back later. That receipt was dropped, so a
  delivered message stayed red with Retry, and Retry sent a duplicate. A
  delivered or read receipt now replaces "Not delivered"; giving up never
  replaces a receipt.

### Battery and speed

- **The mesh state is no longer sealed to disk on every announce.** Every
  verified announce refreshed a remembered peer's last-seen time, and that
  alone asked for a save: the whole state (up to 500 remembered peers, 1000
  history packets, the board, prekey bundles) serialised, encrypted inside
  the Android Keystore and written, on the mesh thread. With two or three
  phones that was every few seconds, around the clock. Now a last-seen time
  on its own waits for the next real change, or at most five minutes.
- **A busy mesh can no longer starve the save.** The 500 ms quiet-time
  debounce had no ceiling, so changes arriving faster than that put the
  write off indefinitely. It now runs at the latest five seconds after the
  first unsaved change.
- **Globe picker: the globe is redrawn when it moves, not 60 times a
  second.** The pulsing crosshair shared a canvas with the globe, so every
  frame re-projected all 60,412 coastline points with four trigonometric
  calls each, through boxing lambdas, whether or not a finger was down. The
  crosshair and the globe each have a layer of their own now, so only the
  crosshair's strokes are redrawn (on an emulator: 98 globe redraws in five
  idle seconds before, none after). Points are stored as unit vectors when
  the map is parsed (three dot products per point, no trigonometry), lines
  are cut into pieces of at most 128 points, and a piece that cannot be on
  screen is skipped whole: at region level 7% of the points are projected,
  at province level 1.5%, at block level 0.2%. The text measurer's cache
  holds 64 labels instead of 8.
- **The radar costs little while nobody is nearby.** Its sweep ran at the
  display's rate, redrawing rings and labels each frame, in the state the
  app is in most. Rings and labels are drawn once; the sweep is a layer
  that is only turned, 15 times a second; it stands still when system
  animations are off.
- **The dedup table holds 4096 packet keys instead of 1000.** Live voice
  bursts, prekey bundles, board posts and group messages now share it; a
  key pushed out early let its packet be processed and relayed again.
- **The UI no longer waits on the mesh thread without a limit.** Posting to
  the board, creating a group and `/trace` blocked the UI thread until the
  mesh thread answered, which is never once that thread has quit. They give
  up after two seconds, or at once when the thread is gone.

### Languages and checks

- **One language on screen.** The in-app language is applied by wrapping the
  activity's context; the system's default locale stays what the phone is
  set to. The texts v0.7.0 added were formatted with APIs that follow the
  system: "12 sec. ago" in the signal band, "Today" and "Yesterday" between
  messages, the scanning timer, voice-note lengths, and message timestamps
  since earlier. On a phone set to Chinese, the English app (the default)
  showed Chinese fragments from its first screen. They now use the app's
  language: relative times and day names come from ICU for that locale,
  timestamps and counters are formatted with it.
- **Plural forms declared for Russian, Ukrainian and Arabic** in the four
  plurals that had only "one" and "other" (group members, board days, days
  left, people across the bridge). Russian and Ukrainian put the number
  after a colon, so the wording was already right for every count and is
  unchanged. Arabic used one noun form for all counts, which was wrong for
  most; it now reads correctly for 1, 2, 3–10 and 11 and up.
- **Lint is clean of errors** (18 before: those twelve plurals, and six
  places that read strings through the context instead of through Compose,
  which does not follow a language change). Six compiler warnings are gone
  too.
- **CI now runs lint and builds the release APK**, and fails when R8 says it
  cannot read Kotlin metadata. v0.6.0 passed CI and crashed on launch for
  exactly that; until now only unit tests and the debug build were checked.

### Checked, and not

- The release APK was run on an Android 15 emulator before publishing: it
  starts; with the Bluetooth permissions revoked and no service running,
  "Erase everything" emptied the sealed-file, cache and Tor directories and
  said so, and a new identity appeared once the permissions were back; with
  the app in Chinese on an English system the day separator read 今天; the
  globe picker draws and is not redrawn while idle. That run caught two
  mistakes that unit tests could not: "today" in lower case, and the
  crosshair still sharing the globe's layer.
- Not checked: anything over a real radio (multi-hop, bitchat interop), the
  Keystore on real hardware, a power cut during a write.
- Left for later, on purpose: moving the sealing of state off the mesh
  thread and sealing bulk data with a key the Keystore only wraps (both
  touch the wipe and the identity file); persisting receipts that wait for
  their recipient; `android:localeConfig`, which needs a decision on whether
  the system's per-app language or the in-app default wins.

## Android v0.7.1 (preview) — 2026-11-09

Globe picker labels that stay readable on the world view. Same release key as
v0.6.0–v0.7.0; installs over them. 175 unit tests. 9.1 MB.
APK SHA-256 `b005188ed9174921724db68918a3fcd73ccace3d6390c7af4d77cabbe6c9f2aa`.

### Fixed

- **Globe picker, world view:** the selected cell's name was missing when the
  globe first opens, because the cell is too small to hold it under the
  crosshair. It now sits on a small plate just below the crosshair. At that
  size the neighbouring cells are left unlabelled instead of half of them
  squeezing in around the crosshair.
- **Globe picker, labels no longer pile up:** city names (Chicago and
  Toronto, Amsterdam, Berlin and Kyiv) and cell names (Dakar over `ef`)
  overlapped. Labels now take screen space in order of importance: the
  crosshair, the selected cell, its neighbours, then cities by size. A city
  name that would cover another moves to the left of its dot, or is left
  out while its dot stays.

## Android v0.7.0 (preview) — 2026-11-08

The mesh made visible, as the README screenshots show it, and a globe picker
that stays readable. Same release key as v0.6.0–v0.6.2; installs over them.
174 unit tests. 9.1 MB.
APK SHA-256 `8e4c75b7fe32b732eb59c8c3d3e16d12452555552b0490fbf4dbb4d1985e80df`.

### Added

- **The mesh is visible**, as the README screenshots always showed
  ([spec §30](docs/protocol-spec.md)):
  - A signal band under the top bar: `Direct 3 | Reach 11`, and how long
    ago anyone was last heard. With nobody in range it reads
    `Scanning · 0 people` and counts how long it has been looking.
  - Received messages in Nearby and private chats say how many links they
    crossed, "direct" or "2 hops", read off the packet's TTL.
  - Every sender in a room has an identity emblem, a small mirrored pixel
    pattern and colour drawn from their key, and their name in that colour.
  - Private messages you send show a four-dot path: sent, on its way
    (a courier holds a copy), delivered, read.
  - A private message that is still unconfirmed when it leaves the outbox
    (after 24 hours, or when 200 newer ones push it out) turns red as
    "Not delivered" with **Retry**. Before this the app had a failed state
    but nothing ever set it.
  - A private chat's header has the peer's emblem, "relayed by 1 phone" or
    "right next to you", an Encrypted pill, and a band with the hops to them.
  - Nobody nearby gets a real screen: a radar with 10 / 30 / 50 m rings and
    three cards (ask them to open openchat or bitchat, move near a window,
    pass the app on; the last opens the share sheet).
  - Day separators ("Today", "Yesterday"); in private chats the first one
    also says relaying phones cannot read the messages. The composer shows
    a lock or broadcast icon for who will read what you type.
- 2 new engine tests (hop counts, outbox expiry), 4 for the visual helpers;
  173 in all.

### Changed

- **The globe picker settles on each level**, like bitchat's stepped zoom.
  A level covers a 4-8x zoom range, and the picker switched to a finer level
  as soon as three of its cells fit, so right after a switch the 3x3 grid
  was a sixth of the screen and its nine labels piled into each other. Now
  a pinch is free while the fingers are down and then glides to the level's
  framing, the grid about 70% of the screen's width (a little less near the
  poles); -/+, double tap and the fly-in land on the same framing. While a
  cell is still too small mid-pinch its label is left out, and labels no
  longer carry a `#`. 1 new test, 174 in all.
- **The radar shows whenever nobody is nearby**, not only while Nearby has
  never had a message. Before, one message sent or received earlier (or a
  bridged one) hid it until the app restarted, so on a real phone it was
  rarely seen. Earlier messages stay one tap away ("Show earlier messages");
  sending something, or a command's answer, opens the timeline too, and it
  stays open until someone comes into range.
  Not yet tried on a phone.

## Android v0.6.2 (preview) — 2026-11-06

Same release key as v0.6.0 and v0.6.1; installs over them. 167 unit tests.
APK SHA-256 `c31a8a6a184e20b68fe1efe81b4c231e7c44f7612410bf0bc3c341e03c512292`.

### Changed

- The Me tab ends with the installed version, `openchat 0.6.2 (10)`, read
  from the package, so a report can name its build.
- "Pick on the globe" is the first control on the Location tab, as a tonal
  button. It used to sit under the levels and the notes, often off screen.

## Android v0.6.1 (preview) — 2026-11-04

Fixes the v0.6.0 launch crash and adds the globe picker. Signed with the
same release key as v0.6.0, so it installs over it. 167 unit tests.
APK SHA-256 `1f5d692571859de58e4373b7780bdbec230204f27743a5423ec2a693abbdcb1f`.

### Fixed

- **v0.6.0 crashed on launch.** Kotlin 2.4 needs R8 9.1.29 or later
  ([kotlin-support](https://developer.android.com/build/kotlin-support)),
  but AGP 8.13 bundles R8 8.13, which reads Kotlin metadata only up to 2.3.
  It shrank the v0.6.0 release without understanding it (the build log is
  full of "error occurred when parsing kotlin metadata"). R8 9.5.29 is now
  pinned on the buildscript classpath; the warnings are gone and a rebuilt
  APK starts on the owner's phone. Debug builds were never affected.

### New

- Globe picker for location channels: "Pick on the globe" on the Location
  tab opens a full-screen orthographic globe, as bitchat has on Android and
  iOS. Drag to spin (a flick keeps it turning), pinch to zoom, tap to
  centre, double tap to go one level finer. The cell under the crosshair is
  the selection, drawn with its eight neighbours and their geohashes; zoom
  sets the precision from region to block, and −/+ step it. It flies in to
  the typed geohash, else a joined channel, else the region around you;
  with animations turned off it jumps. "Enter this channel" joins, as
  teleported unless the cell contains your own location. Coastlines,
  borders and 243 cities come from Natural Earth (public domain), packed
  into a 275 KB asset by `android/tools/pack_globe.py`. Lines only, no land
  fill, so no polygon has to be clipped at the horizon. No wire change. 6
  new strings in 18 languages.

### Changed

- The logo at the top left is drawn at two thirds of its former size; the
  48 dp tap target for the triple-tap wipe is unchanged.

## Android v0.6.0 (preview) — 2026-10-30

Everything merged to `main` since v0.5.0 (PRs #7, #8, #11, #13, #14, #15,
#16). The new features are the ones bitchat has, wire-compatible with it;
none of the fixes changes a format v0.5.0 already spoke.

**First release-signed build.** It does not install over v0.5.0 or earlier,
which carried the build machine's debug key: uninstall first. Identity,
favorites and verifications are lost with the uninstall; this is the
one-time cost of making the switch now. The certificate fingerprint is in
[SECURITY.md](SECURITY.md).

### New, compatible with bitchat

- Private groups ([spec §22](docs/protocol-spec.md)). New group on the
  Private tab or `/group create`; the creator invites people nearby from
  Members (or `/group invite @name`), removes them, and anyone can leave.
  Up to 16 members. The creator signs the roster and a fresh key on every
  change and hands them out inside Noise sessions; messages are sealed with
  ChaCha20-Poly1305 under the group key, signed by their author, and
  flooded over the mesh like public ones, so only members read them.
  Members who were away pick up missed messages by history sync. Text only
  for now. Groups and keys are sealed on disk and go with the triple tap.
- Notice board ([spec §23](docs/protocol-spec.md)). Board on the Nearby
  tab: pin a notice for 1, 3 or 7 days, mark it urgent, take it down later.
  Posts are signed by their author and pass from phone to phone, including
  by history sync, so people who arrive days later still read them; a
  takedown spreads the same way. Five posts per author, 200 in all. A fresh
  urgent notice adds a line to the Nearby chat; others raise a badge. Kept
  with the sealed mesh state, wiped by the triple tap.
- Mesh bridge ([spec §24](docs/protocol-spec.md)), off by default (Me tab).
  Bluetooth reaches only so far: with the bridge on, Nearby messages also
  go to the internet relays for your neighbourhood cell (about 1.2 km)
  under an unlinkable per-cell key, and what people in other islands of
  that area say appears in Nearby, marked "via bridge". A bridging phone
  with internet serves its island: it publishes messages that bridging
  phones without internet hand to it, and carries other islands' messages
  onto the radio (holdoff, 20 a minute, bitchat's loop rules). A copy of a
  radio message is skipped, and the radio copy replaces a bridged row that
  got there first. The bridge's own relay-side logic is not unit-tested yet.
- Courier drops: with the bridge on, mail for someone out of reach is also
  parked on relays as a sealed kind-1401 drop under a throwaway key, so it
  arrives as soon as either side is online; a bridging phone also fetches
  drops for verified neighbours and hands them over.
- Location-channel gateway ([spec §24.5](docs/protocol-spec.md)). With the
  bridge on and internet working, your phone also serves people nearby who
  have none: what they post in a location channel goes out through you, and
  the channels you are in come in for them over the radio (30 a minute).
  Without internet, your own channel messages go out through such a phone
  automatically. Every carried event is signature-checked; bitchat's loop
  rules apply. The bridge explanation in the Me tab says so.
- `/ping @name` and `/trace @name` in Nearby and private chats
  ([spec §25](docs/protocol-spec.md)). Ping shows the round trip and how
  many hops away someone is; trace shows an estimated path from the
  neighbour lists bitchat phones announce. Pongs are budgeted per radio
  link, so a forged sender cannot turn a phone into a pong cannon.
- Vouching ([spec §26](docs/protocol-spec.md)). People you verified in
  person also tell your phone, over an encrypted session and at most once a
  day, whom they verified; those people get a grey ✓ and "verified by …" on
  their card. Only vouches from people you verified count, and they lapse
  after 30 days or as soon as you un-verify the voucher. openchat now
  announces the capabilities it has (groups, board, vouching, diagnostics),
  so bitchat phones offer them.
- One-time prekeys ([spec §27](docs/protocol-spec.md)). Each phone
  publishes eight single-use keys that spread like board posts. Mail for
  someone out of reach, whether carried by other phones or parked on
  relays, is now sealed to one of their single-use keys when one is known,
  and that key is deleted two days after the mail is opened, so stealing
  the phone later does not unlock mail captured on the way. Opening
  bitchat's forward-secret mail works too.
- `/pay` and Cashu cards ([spec §28](docs/protocol-spec.md)).
  `/pay <token>` sends a Cashu ecash token after checking it is real money;
  outside a private chat it needs `/pay <token> public`, because whoever
  redeems a token first gets it. Tokens in messages show as a card with the
  amount, mint and memo, an "Open in wallet" button and Copy. The app never
  talks to a mint.
- Live voice ([spec §29](docs/protocol-spec.md)). Hold the mic to talk and
  release to send (a quick tap still starts and stops a recording). In
  Nearby and private chats people hear you while you are still talking:
  their bubble appears at once and plays live if they have that chat open,
  and the finished note then slots into the same bubble. Public bursts are
  signed and accepted for 30 seconds; floods are capped. A Me-tab switch,
  on by default, turns live voice off in both directions.
- Location channels have a Share button: a text invite with a
  `bitchat://geohash/` link that bitchat and openchat both open (openchat
  now opens such links). Small areas ask before sharing.

### Privacy and spam

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
  way, and the triple tap turns previews off again. Message notifications
  are marked secret, so their text stays off the lock screen and out of the
  notification history.
- Received photos appear as coarse blocks until tapped.
- Panic wipe: Tor is stopped, its directory (guards, state, cached
  descriptors) deleted and Tor restarted on fresh guards after a 2–8 s
  pause, with relay sockets closed in between, so the new identity's first
  contact with the relays shares no circuit with the old one. Receipts
  still waiting their turn in the Nostr pacer are discarded rather than
  sent as the old persona. Media files in the cache are deleted at every
  service start, since the timeline they belong to does not survive one.
- Key material is zeroed when it is done with: a handshake that is given up
  (timeout, bad message, eviction, key mismatch, panic) wipes its ephemeral
  key and symmetric state, and the handshake state is destroyed as soon as
  the transport keys are derived; a panic wipe zeroes the old identity's
  private keys and Nostr seed; the sealed identity file's plaintext buffers
  are zeroed after use.

### Interface

- The app mark sits in the top-left corner of the main screen and is a
  48 dp target for the triple-tap wipe. The title still works too. Each
  counted tap gives a short haptic tick, and the three taps are judged by
  the gap between them (500 ms each) instead of an 800 ms window from the
  first tap, which was too tight on a real phone. The first-run safety note
  points at the logo in all 18 languages.
- One icon style: the 🎙/🖼 emoji and ■/▶ glyphs are outlined Material
  icons (Mic, Stop, PlayArrow, Image); the name badges ✓ ✈ ★ render as
  monochrome text; pictographic emoji are gone from UI strings in all 18
  languages. Each bottom tab has an outlined icon that fills when selected.
- The default nickname is `anon`. Labels drop digits directly before the
  suffix, so bitchat's `anon1234` shows as `anon#xxxx`, and peers that
  never announced a name no longer show their hex ID as the name.
- Screen readers: tab icons no longer repeat the tab name; the record button
  is a button (one activation starts, the next sends; the hold-to-talk
  gesture is unchanged for everyone else); the play button says play or
  stop; a sender's name is a 32 dp button; the verification QR has a
  description. The Me tab has an "Erase everything" button with a
  confirmation, for people who cannot tap three times in half-second steps;
  the triple tap itself stays unconfirmed. The logo's description is
  translated in all 18 languages.
- Chat view: the list follows new messages only while you are at the end of
  it or the message is yours, so a history sync inserting older lines (or a
  timeline at its 500-line cap) no longer yanks you to the bottom. Photos
  and voice-note lengths are decoded off the main thread and cached, so
  scrolling a media-heavy chat does not stutter. The APK copy behind "share
  the app" no longer runs on the UI thread.
- Edge-to-edge drawing is requested explicitly (Android 15 enforces it), and
  the first-run screen keeps its language menu and start button clear of
  the system bars.
- Unread counts no longer grow when the same message arrives twice.
- `/help` lists `/drop` in location channels.

### Fixes from the 2026-10-29 code review

- Private messages over Nostr: a favorite notice arriving over Nostr could
  bind any mesh peer's identity to the sender's own npub, because the sender
  field inside the envelope is not signed. Once bound, private messages to
  that peer out of Bluetooth range were encrypted to the attacker. Now only
  the npub already recorded for a person speaks for them over Nostr
  (`NostrSenders`, [spec §15.1](docs/protocol-spec.md)); the first binding
  happens over a Noise session or a verification QR, where the key is
  proven.
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
  response.
- Bluetooth scanning follows the duty cycle of [spec §11](docs/protocol-spec.md):
  5 s on, 10 s off (3 s / 15 s with six or more links) instead of scanning
  without pause; no scan is started while the central table is full, and a
  scan that fails to start is retried after 10, 20, 40, then 60 s. Discovery
  of a new neighbour may now take up to 10 s longer; the radio sleeps two
  thirds of the time.
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
- A history-sync copy arriving while a relay waited cancelled the relay, so
  a message could stop one hop short. A sync reply goes to one phone only
  and no longer counts as someone else having relayed it.
- A new link gets our announce as soon as the 1 s announce rate limit
  allows; before, a link that opened within a second of the last announce
  waited 15–38 s for the next periodic one.
- Tor: the ON state is now taken from the service's status, not from a
  static port number that outlives a stop, so a Tor restart no longer shows
  ON while it is still bootstrapping. A relay socket that was opened just
  as the route changed is dropped on open rather than kept as a clearnet
  socket. An `.onion` relay is never handed to the system resolver while
  Tor is off.

### Build and project

- Release builds are signed with a dedicated release key when
  `android/keystore.properties` is present (git-ignored, see
  android/README.md). Previews up to v0.5.0 were signed with the build
  machine's debug key, which cannot be recovered if that machine is lost
  and would have forced every user to reinstall at some later, worse time.
- `Screens.kt` (1374 lines) is split into Theme, Onboarding, MainScreen,
  ChatPane, PrivateChats, LocationScreens and MeScreen. Code moved, not
  changed.
- Toolchain and libraries brought up to date: Gradle 8.14.6, Android Gradle
  plugin 8.13.2, Kotlin 2.4.21, Compose BOM 2026.06.00, compileSdk 36
  (targetSdk stays 35), OkHttp 5.4.0, Bouncy Castle 1.86, coroutines 1.11.0,
  ZXing 3.5.4, activity-compose 1.13.0, core-ktx 1.18.0, plus
  `material-icons-extended` (R8 removes what is unused; the debug APK grows
  to about 44 MB). The two lifecycle libraries that nothing referenced are
  gone. Compose 1.12, core 1.19 and OkHttp 5.5 need Android Gradle plugin
  9.1 and compileSdk 37; that step (built-in Kotlin, new DSL) is left for
  its own change.
- 161 unit tests (67 new since v0.5.0), including upstream's pinned
  message-ID vector. A CI-only failure of the synced-announce test was the
  sync filter's 1-in-128 false positive; the test now injects the packet
  itself. `fragmentsReassemble` failed once during the second review round
  and passed on every rerun; it uses a random payload and is still on the
  list to pin down.
- versionCode 8, versionName 0.6.0. Does not install over v0.5.0 or earlier
  (release key, see above): uninstall first.
- Release: <https://github.com/wolfqing/openchat/releases/tag/v0.6.0>,
  APK SHA-256 `659b40d79cfadad5fd26c58ba1db5ce752425fcc76f9bd6058f9f9ad059886ca`.
- Not yet checked on a device or against a bitchat phone. Nothing since
  v0.5.0 has run on a phone; the scan duty cycle, live voice, the bridge
  and TalkBack behaviour in particular need a real device.

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
