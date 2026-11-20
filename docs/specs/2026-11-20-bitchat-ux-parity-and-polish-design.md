# Experience Parity & Clarity Upgrade Design Spec

- **Date**: 2026-11-20
- **Reference**: [BitChat 产品体验与交互记录.md](../BitChat%20%E4%BA%A7%E5%93%81%E4%BD%93%E9%AA%8C%E4%B8%8E%E4%BA%A4%E4%BA%92%E8%AE%B0%E5%BD%95.md)
- **Wire Protocol Impact**: **None** (100% compatible with existing BLE & Nostr wire formats; leverages existing `BoardPost.geohash` TLV `0x03`, `sessionPeers`, and `Bridge` local send path)
- **Permissions Impact**: **None** (adds zero Android manifest permissions per `CONTRIBUTING.md`; fine-precision OpSec guidance is rendered inline and on channel share).

---

## 1. Design Goals

Address all 6 experience gaps and UX clarity opportunities identified in the field walkthrough while preserving OpenChat's **Minimalist Chrome + iOS 27 Liquid Glass** visual language:

1. **In-Context Message Long-Press Menu (`消息长按快捷菜单`)**: One-gesture access to Copy, `@Mention` draft insertion, Direct Message, and Profile/Block directly on any chat bubble.
2. **Truthful Handshake & Encryption Telemetry (`私聊真实握手与加密状态一致`)**: Eliminate the contradiction between "End-to-End Encrypted" labels and incomplete Noise handshakes across both `PrivateChatScreen` and `PersonSheet`.
3. **Explicit Send-Scope Pill for Mesh Bridge (`输入栏发送范围切换：附近+桥接 vs 仅蓝牙`)**: Replace mysterious icon-only toggles with a labeled micro-capsule when Mesh Bridge is enabled, clearly separated from the global Bridge setting.
4. **Channel Bookmarks & High-Precision Presence Clarity (`位置频道星标书签 + 高精度隐藏人数说明`)**: Separate persistent bookmarks from active relay subscriptions, and replace ambiguous `0` / `?` counts on block/neighborhood channels with plain-language privacy explanations.
5. **Regional Notice Board Tab (`公告栏支持附近蓝牙与区域公告切换`)**: Expose the existing `BoardPost.geohash` protocol field so users can filter and post to either the local Bluetooth mesh or their current region cell.
6. **Daily Passed-Devices Telemetry on Empty Radar (`雷达页“今日曾经过设备数”`)**: Surface anonymous, memory-only daily peer encounter counts on `NobodyNearby` without conflating historical encounters with live reachable peers.

---

## 2. Module Specifications

### 2.1 Module 1 — Message Bubble Long-Press Context Sheet (`ChatPane.kt`)

- **Trigger**: Add `combinedClickable(onClick = ..., onLongClick = ...)` on message bubble surfaces in `PublicPostRow` and `EncryptedBubbleRow` with `HapticFeedbackType.LongPress`.
  - Tapping an unrevealed photo still unblurs it (`onClick`); tapping a voice note play button still toggles playback; long-pressing any bubble opens the action sheet.
- **Presentation**: Clean `ModalBottomSheet` (`MessageActionSheet`) showing a compact 1-line preview of the sender + message snippet at the top, followed by crisp action rows:
  1. **复制消息 (`Copy text`)** — shown whenever `m.text.isNotBlank()`. Copies `m.text` to `ClipboardManager` and dismisses.
  2. **`@提及` (`Mention @name`)** — shown when `!m.mine` in multi-party chats (`mesh`, `geo:*`, `grp:*`). Appends `@<disambiguatedLabel> ` into the conversation's `Composer` draft (`drafts[conversation]`), requests input focus, and **does not auto-send**.
  3. **发私信 (`Direct message`)** — shown when `!m.mine && !m.bridged` outside `1-on-1` DMs (`mesh`/`grp:*` opens `Conversation.Private(m.senderKey)`; `geo:*` opens anonymous `Conversation.GeoPrivate(geohash, m.senderKey)`).
  4. **查看身份与安全验证 (`Verify / Profile`)** — shown when `onSenderClick != null && !m.mine && !m.bridged`. Opens `PersonSheet`.
  5. **屏蔽此人 (`Block`)** — destructive red action shown when `!m.mine && !m.bridged`. Blocks mesh fingerprint or geo pubkey immediately (matching `/block`).
- **Deliberate Omission (YAGNI)**: `/hug` and `/slap` remain available via `/` slash-command autocomplete rather than cluttering every long-press menu.

---

### 2.2 Module 2 — Truthful Private-Chat Encryption & Handshake State (`PrivateChats.kt`, `People.kt`)

- **State Inputs**:
  - `hasLiveSession = peerHex in ChatRepository.sessionPeers.value` (completed `Noise_XX` handshake on active mesh)
  - `peer = peers.firstOrNull { it.peerIdHex == peerHex }` (currently reachable on BLE mesh)
  - `knownNoiseKey = meshFingerprint(peerHex) != null` (static Curve25519 key verified via announce or QR)
  - `isMutualNostr = social.favoriteOf(peerHex)?.let { it.mine && it.theyFavorited && it.theirNpub != null } == true`
- **Unified 6-State Matrix** (rendered identically in `PrivateChatScreen` header subtitle, empty-chat `AudiencePill`, and `PersonSheet`):

| Priority | Condition | Icon | Label (ZH / EN) |
| :-: | :--- | :--- | :--- |
| 1 | `social.isBlockedPeer(peerHex)` | `Block` (Muted) | `已屏蔽` / `Blocked` |
| 2 | `peer != null && hasLiveSession` | Solid `Lock` (`OpenChatPalette.Lock`) | `实时加密已就绪 · 蓝牙直连` (or `· 经 X 跳中继`) |
| 3 | `peer != null && !hasLiveSession` | Outlined `LockOpen` (`Signal` / `Muted`) | `在附近 · 发消息自动建立加密握手` |
| 4 | `peer == null && isMutualNostr` | Solid `Lock` (`OpenChatPalette.Lock`) | `端到端加密 · 经互联网投递` |
| 5 | `peer == null && knownNoiseKey` | Outlined `Lock` (`Muted`) | `离线加密封存 · 信使代转` |
| 6 | `peer == null && !knownNoiseKey` | Outlined `Schedule` (`Muted`) | `离线 · 等待对方上线交换密钥` |

---

### 2.3 Module 3 — Nearby Send-Scope Micro-Capsule (`ChatPane.kt`, `Bridge.kt`, `MeshService.kt`)

- **Visibility Rule**: Shown **only** in `Conversation.Mesh` (`附近`) **when the global Mesh Bridge toggle (`BridgeStatus.enabled`) is ON**. When global Bridge is OFF, zero extra UI appears.
- **UI Placement & Copy**:
  - Compact pill sitting cleanly above the left edge of the stadium `Composer` bar:
    - **Mode A — Bridge Allowed (default when Bridge is ON)**: `🌐 附近 + 桥接` (`OpenChatPalette.Lock` accent dot)
    - **Mode B — Radio Only (local override)**: `📡 仅蓝牙直达` (`OpenChatPalette.Online` green dot)
  - Tapping flips between Mode A and Mode B, displaying a brief inline helper caption (`仅影响当前发送范围，全局桥接仍开启` / `发送仅限附近蓝牙，不上互联网桥接`) for 3.5 seconds so its relationship to the global setting is unmistakable.
- **Data Flow**:
  - `ChatController.sendPublic(text: String, allowBridge: Boolean = true)` in `ChatRepository.kt` / `MeshService.kt`:
    - Always broadcasts over BLE (`engine.sendPublic(text)`).
    - Calls `bridge.publishLocalPublic(...)` **only when `allowBridge == true`**.

---

### 2.4 Module 4 — Location Bookmarks & High-Precision Presence Clarity (`LocationScreens.kt`, `LocationPresets.kt`, `MeshService.kt`)

- **4.1 Persistent Starred Bookmarks (`常用书签`)**:
  - Add `bookmarks: Set<String>` to `GeoStatus`, persisted in `SealedFile(context, "geo-bookmarks.bin")` (automatically wiped by `Panic.wipeStorage`).
  - Add a `☆ / ★` bookmark action inside `GeoChatScreen`'s top-right `GlassHeaderActionCluster` (`[ ☆ | 分享 | 退出 ]`) and a long-press / star toggle on `QuickJoinTile` & joined chips.
  - Leaving a room (`退出`) closes its live Nostr subscription (`leaveGeo`) to save battery/traffic while keeping starred channels pinned in a dedicated `已收藏书签` chip row on `LocationPane` for 1-tap re-entry.
- **4.2 High-Precision Presence Explanation (`>= 6` chars: Neighborhood & Block)**:
  - Protocol §9 / BitChat suppresses presence heartbeats (`kind 20001`) at fine precision (`length >= 6`) to protect physical privacy.
  - In `LocationPane` (precision rows & bookmarked chips) and `GeoChatScreen` subtitle:
    - When `geohash.length >= 6` and `participants == 0` while `channelOnline == true`: display **`已连接 · 街区级隐藏人数`** (`Connected · count hidden for privacy`) instead of `最近 0 人` or cryptic `?`.
    - When `participants > 0` (active chatters seen via `kind 20000` messages in the last 5 minutes): display `最近 N 人发言`.
- **4.3 Zero-Permission Location OpSec Note**:
  - Keep `CONTRIBUTING.md` strict zero-new-permission discipline (no `DETECT_SCREEN_CAPTURE` manifest addition). Instead, for high-precision rooms (`geohash.length >= 6`), include a concise 1-line OpSec reminder inside the empty-room header (`AudiencePill` subtitle): `公开区域频道 · 截屏或分享 #编码可能暴露所在街区`.

---

### 2.5 Module 5 — Mesh & Regional Notice Board Filter + Composer Scope (`BoardScreen.kt`, `MeshService.kt`)

- **Filter Segmented Bar (`BoardScreen.kt`)**:
  - Add a compact 3-chip filter row below the top bar when any regional post or active region cell exists:
    - `全部` (`All`) · `附近蓝牙` (`Mesh`, `post.geohash.isEmpty()`) · `区域` (`Regional`, `post.geohash.isNotEmpty()`).
- **Scoped Posting (`BoardComposerSheet`)**:
  - Resolve `activeRegionCell = geo.locationGeohash?.take(4) ?: geo.joined.firstOrNull()?.take(4)`.
  - When `activeRegionCell != null`, show a scope selector in `BoardComposerSheet`:
    - `📡 附近蓝牙` (`geohash = ""`)
    - `📍 当前区域 #$activeRegionCell` (`geohash = activeRegionCell`)
  - Passes `geohash` straight into `ChatController.postBoard(text, days, urgent, geohash = selectedGeohash)` -> `Board.newPost(text, geohash, ...)` (already natively supported by `Board.kt` TLV `0x03`!).

---

### 2.6 Module 6 — Daily Passed-Devices Counter on Empty Radar (`MeshVisuals.kt`, `ChatRepository.kt`, `MeshService.kt`)

- **Privacy-Safe In-Memory Accounting**:
  - In `MeshService`, track `seenUtcDay: Long` (`System.currentTimeMillis() / 86_400_000L`) and `seenTodayPeerIds = HashSet<String>()` on the mesh handler thread.
  - Whenever `onPeersChanged` observes verified peers (`peerIdHex != myPeerHex`), roll over the set if `utcDay != seenUtcDay`, add `peerIdHex`, and publish `passedTodayCount = seenTodayPeerIds.size` into `MeshStatus`.
  - **Never written to disk**; cleared immediately on `panic()` and midnight rollover.
- **UI Presentation (`NobodyNearby` in `MeshVisuals.kt`)**:
  - Only when `otherPeers.isEmpty()` (`附近当前 0 人`) **and** `mesh.passedTodayCount > 0`:
    - Render a subtle muted pill below the radar rings: `今日曾经过 N 台设备 · 当前均已离开范围` (`Passed N devices today · none in range right now`), clearly distinguishing cumulative encounters from live online peers.

---

## 3. Files Touched & Verification Plan

| File | Responsibility |
| :--- | :--- |
| `app/ChatRepository.kt` | Add `passedTodayCount` to `MeshStatus`, `bookmarks` to `GeoStatus`, `allowBridge` param on `sendPublic`, `geohash` param on `postBoard`, bookmark actions |
| `app/MeshService.kt` | Daily in-memory encounter counter, `allowBridge` routing, `geo-bookmarks.bin` persistence + panic wipe, regional `postBoard` |
| `ui/ChatPane.kt` | Long-press `MessageActionSheet` (Copy, `@Mention`, DM, Profile, Block) + `SendScopePill` (`附近+桥接` vs `仅蓝牙`) + reactive draft injection |
| `ui/PrivateChats.kt` & `ui/People.kt` | Unified 6-state encryption & live `Noise_XX` handshake status across header, empty pill, and `PersonSheet` |
| `ui/LocationScreens.kt` & `app/LocationPresets.kt` | Starred bookmarks row & header toggle, high-precision `隐藏人数` copy, fine-cell OpSec reminder |
| `ui/BoardScreen.kt` | `全部 / 附近蓝牙 / 区域` filter chips + regional scope selector in `BoardComposerSheet` |
| `ui/MeshVisuals.kt` | `今日曾经过 N 台设备` muted telemetry pill on `NobodyNearby` |
| `res/values*/strings.xml` | Localized strings across English source + Simplified/Traditional Chinese + all 15 supported locales |
| `src/test/...` | Unit tests for handshake state resolution, bookmark serialization, daily encounter UTC rollover, and `@mention` draft insertion |
