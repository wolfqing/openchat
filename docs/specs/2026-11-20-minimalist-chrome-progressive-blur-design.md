# Minimalist Chrome & Top Progressive Blur Design Spec (Option B)

- **Date**: 2026-11-20
- **Target Version**: Android `v0.7.14` (`versionCode = 21`)
- **Wire Protocol Impact**: None (100% UI / presentation layer)

---

## 1. Goals

1. **Top Progressive Blur Veil (`顶部渐变模糊`)**:
   - Blur begins at maximum strength at the very top edge of the screen (`y = 0`, status bar), stays softly blurred behind the top header & action capsule, and feathers smoothly to `0` blur / `0` opacity right below the header bar so **all chat bubbles and text in the conversation area remain 100% sharp**.
2. **Eliminate Scattered Pills (`收拢分散按钮 — 方案 B`)**:
   - Replace the 2-row scattered header pills (`TopAppBar` pill + `NearbyStatusCapsuleRow` / `RouteBand` + separate `Walkie-Talkie` / `Encrypted` pills) with a **single unified two-line `TopAppBar`** paired with a **compact monochrome right-hand icon cluster**.
   - Merge the disjointed 3-piece bottom composer (`[ + ]` + `[ input ]` + `[ peach Mic ]`) into **one cohesive stadium input bar**.
3. **Eliminate "AI-Generated" Visual Tells (`去 AI 感，高级简约工业质感`)**:
   - Remove the dual neon radial glow blobs (top-left orange + bottom-right blue) from `openChatCanvas()`.
   - Shift all resting chrome and icons to calm monochrome `Ink` / `Muted`; reserve `Signal` orange strictly for active live state (`6dp` live pulse dot, unread badge, active PTT TX/RX).
   - Tone down RGB chromatic dispersion on the bottom frosted navigation bar (`6.dp -> 1.5.dp`) for clean, rainbow-free optical glass.

---

## 2. Optical Progressive Top Blur (`FluidGlass.kt`)

### 2.1 Component: `TopProgressiveBlurBar`
- Placed as a non-interactive backdrop layer (`pointerInput` passthrough) behind every `Scaffold` top bar (`MainScreen`, `PrivateChatScreen`, `GroupChatScreen`, `GeoPrivateChatScreen`, `GeoChatScreen`, `BoardScreen`, `GuideScreen`).
- Height = `topBarMeasuredHeight + 14.dp` (the `14.dp` tail feathers smoothly into the top padding of the timeline, ending right above the active dialogue viewport).
- **Sampling Source**: Reads `FluidGlassHostState.activeSource` (`sourceLayer`) recorded by `FluidGlassSource` (`ChatPane`, `PrivateList`, `LocationPane`, `MePane`, `BoardScreen`, `GuideScreen`), guaranteeing zero cyclic `RenderNode` graphs (`LocalInsideFluidGlassSource == false`).

### 2.2 Platform Rendering Tiers
1. **Android 13+ (`API 33+`)**: Custom AGSL `RuntimeShader` (`PROGRESSIVE_BLUR_AGSL`):
   - Computes normalized vertical progress `v = clamp(fragCoord.y / uHeight, 0.0, 1.0)`.
   - Blur weight curve `blurFactor = 1.0 - smoothstep(0.52, 1.0, v)`:
     - `v = 0.0 .. 0.52` (status bar + upper title): full `18.dp` multi-tap golden-angle disc blur.
     - `v = 0.52 .. 0.80` (behind subtitle & right action capsule): `~9–14.dp` soft frosted blur so scrolling bubbles behind the capsule never clash with header glyphs.
     - `v = 0.80 .. 1.00` (14dp feather tail): smooth Hermite decay to **exact `0.0` blur** (`content.eval(fragCoord)` passthrough).
   - Composite translucent surface veil (`#F6F6F7` in Light / `#090A0C` in Dark) ramps from `0.82` alpha at `v = 0.0` -> `0.46` behind capsule -> `0.0` at `v = 1.0`.
2. **Android 12 (`API 31–32`)**: Hardware `RenderEffect.createBlurEffect(16.dp, 16.dp, CLAMP)` drawn into an offscreen `CompositingStrategy.Offscreen` layer masked with `BlendMode.DstIn` vertical alpha gradient (`1f` at `0f`, `0.85f` at `0.68f`, `0f` at `1f`), plus matching surface veil gradient.
3. **Android 8–11 (`API 26–30`)**: Smooth 4-stop vertical surface veil gradient (`0.94 -> 0.78 -> 0.35 -> 0.0`).

---

## 3. Unified Single-Row Header Architecture — Option B

### 3.1 `MainScreen` (`Nearby`, `Private`, `Location`, `Me`)
- **Remove second-row `NearbyStatusCapsuleRow` from `MainScreen.topBar`** (saving ~38dp of vertical chrome height).
- **Left Navigation Mark (`PanicLogo`)**:
  - Clean `44.dp` touch target (preserving 3-tap instant panic wipe) rendering a refined `22.dp` monochrome geometric radio-node mark (central `Signal` dot + crisp `Ink` concentric wave arcs) instead of the oversized `1.42x`-scaled orange launcher bitmap/vector.
- **Two-Line Title Block**:
  - **Line 1**: `PanicTitle` (`17.5.sp SemiBold`, `Ink`, preserving 3-tap panic wipe).
  - **Line 2** (shown on `Nearby`, `Private`, `Location` when `!hasProblem`):
    - **Active Live PTT on Nearby**: `LiveEqualizerBars` + `live_ptt_speaking` in `Signal` orange; tapping opens `WalkieTalkieDrawer`.
    - **Scanning (`otherPeers.isEmpty()`)**: muted dot + `正在扫描 · 已 0:12 ›` (`11.5.sp` tabular mono); tapping opens `NearbyPeersSheet`.
    - **Peers Reachable**: `6.dp` live `Signal` dot + `2 直连 · 5 可达 · 3s ›` (`11.5.sp` tabular mono, `Ink` / `Muted`); tapping opens `NearbyPeersSheet`.
- **Right Action Capsule (`HeaderActionCluster` — Liquid Glass matching Bottom Tab Bar)**:
  - Built directly on **`FluidGlassCapsule`** (`FluidGlassSurface` with `isCapsule = true`, sharing the exact same AGSL curved-meniscus Snell refraction, `7.5.dp` frosted clarity blur, subtle `1.5.dp` dispersion, and anisotropic glass rim as `CapsuleNavigationBar`).
  - **`Tab.NEARBY`**: Single `FluidGlassCapsule` (`height = 36.dp`, `shadowElevation = 6.dp`) housing two `38x32.dp` icon slots separated by a `12.dp` subtle glass divider:
    1. `WalkieTalkieIcon` (`contentDescription = stringResource(R.string.ptt_button)`): monochrome `Ink` at rest; highlighted with a `fluidGlassDroplet` / `Signal` droplet pill inside the glass capsule when `nearbyPttOpen` is true (or soft `Signal` pulse when `nearbySpeaker != null`).
    2. `Icons.Outlined.PushPin` (`contentDescription = stringResource(R.string.board_title)`): monochrome `Ink` + compact `6.dp` `Signal` unread dot (`boardUnseen > 0`).
  - **`Tab.PRIVATE`**: Single `36.dp` circular `FluidGlassCapsule` button (`Icons.Filled.Add`, `contentDescription = stringResource(R.string.group_new)`).
  - **`Tab.ME`**: Single `36.dp` circular `FluidGlassCapsule` button (`Icons.AutoMirrored.Outlined.HelpOutline`, `contentDescription = stringResource(R.string.guide_button)`).

### 3.2 `PrivateChatScreen` (`1-on-1` Encrypted Chat)
- **Remove second-row `RouteBand` and separate `AudiencePill`**.
- **Left**: `GlassBackButton` + clickable `EmblemView(30.dp)` + Two-line title:
  - **Line 1**: `PanicTitle` with peer callsign + `#xxxx` + verification badge.
  - **Line 2**:
    - When `dmSpeaker != null`: `LiveEqualizerBars` + `live_ptt_speaking` in `Signal` orange (clickable to open private `WalkieTalkieDrawer`).
    - Otherwise: `11.dp` inline `Icons.Outlined.Lock` (`Lock` / `Muted`) + concise status & hop summary (`附近 · 直连` / `经 2 台转发` / `经互联网`).
- **Right**: Unified 2-slot **`FluidGlassCapsule`** `HeaderActionCluster` `[ 📻 | ⋯ ]` (`WalkieTalkieIcon` + `Icons.Filled.MoreHoriz`), matching the bottom tab bar's exact Liquid Glass material and `MainScreen`'s `36.dp` capsule geometry.

### 3.3 `GroupChatScreen`, `GeoChatScreen` & `GeoPrivateChatScreen`
- Remove redundant header `AudiencePill` where subtitle already states encrypted status; style top-right header action pills/buttons with the same **`FluidGlassCapsule`** Liquid Glass material so top-right controls and the bottom navigation capsule match across every screen; wrap scrollable body + top bar with `TopProgressiveBlurBar` for uniform progressive blur.

---

## 4. Unified 1-Piece Minimalist Composer (`ChatPane.kt`)

- Replace the 3 detached bottom shapes (`44.dp` `+` circle + `44.dp` input pill + `44.dp` peach/blue `Mic` circle) with **one cohesive stadium bar**:
  - Outer container: `Surface` (`CircleShape`, `minHeight = 46.dp`, `#FFFFFF` in Light / `#15161A` in Dark, `0.5.dp` subtle hairline border `Color.Black.copy(alpha = 0.07f)` / `Color.White.copy(alpha = 0.08f)`).
  - **Leading slot** (`if (supportsMedia && text.isEmpty())`): `34.dp` internal circular hit target with monochrome `Icons.Filled.Add` (or `Icons.Filled.Close` when the `+` attachment tray is open).
  - **Center slot**: `BasicTextField` (`15.sp`, `Ink` text, `Muted` placeholder).
  - **Trailing slot**:
    - When `text.isNotBlank() || !supportsMedia`: `34.dp` crisp solid high-contrast send circle (`#111214` with white `Icons.Rounded.ArrowUpward` in Light; `#F1F2F4` with `#111214` arrow in Dark when active).
    - When `text.isEmpty() && supportsMedia`: `34.dp` internal circular hit target with calm monochrome `Icons.Outlined.Mic` (supporting both tap-to-record-and-preview and hold-to-send).
- Attachment `+` tray (`[ 照片 ]` + `[ 实时对讲 ]`) uses monochrome `Ink` icons without colored borders.

---

## 5. De-AI-ified Palette, Canvas & Chat Bubbles (`Theme.kt`, `ChatPane.kt`, `FluidGlass.kt`)

1. **Pure Architectural Canvas (`Theme.kt`)**:
   - Light mode: flat `#F6F6F7` architectural paper ground (`Surface = #FFFFFF`, `Card = #EEEEF0`, `Control = #EAEAEF`). Zero orange/blue radial glow blobs.
   - Dark mode: deep `#090A0C` / `#000000` obsidian ground (`Surface = #121316`, `Card = #17181C`, `CardRaised = #202227`). Zero radial glow blobs.
2. **Clean Public & Encrypted Chat Bubbles (`ChatPane.kt`)**:
   - Peer messages in `PublicPostRow` wrap their content inside a clean borderless `#FFFFFF` (Light) / `#15161A` (Dark) bubble (`RoundedCornerShape(topStart = 5.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp)`), removing inter-message full-width divider hairlines so conversations read as crisp modern bubbles.
   - Own public messages (`mine == true`) use a calm tonal slate bubble (`#E6E7EC` in Light / `#22242A` in Dark) that preserves 100% contrast for text, `@mentions`, `VoiceNote`, and `CashuCard`.
3. **Refined Frosted Bottom Navigation (`FluidGlass.kt`)**:
   - Reduce `FluidGlassDefaults.Dispersion` from `6.dp` to `1.5.dp` and `Refraction` from `15.dp` to `9.dp` to eliminate rainbow fringes while keeping genuine optical frosted glass.

---

## 6. Verification Plan

1. Update unit tests in `MeshVisualsTest` if any helper signatures change; add unit test coverage for progressive blur curve / status formatting if applicable.
2. Run `./gradlew :app:testDebugUnitTest :app:lintDebug :app:lintVitalRelease :app:assembleDebug :app:assembleRelease` locally with JDK 17 + Android SDK 36 — require **0 test failures, 0 lint errors, and clean R8 release APK**.
