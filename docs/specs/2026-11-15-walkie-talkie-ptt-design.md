# OpenChat 半双工仿真实时对讲机（Walkie-Talkie / Live PTT）设计文档

- **日期**：2026-11-15
- **适用范围**：「附近（Nearby）」蓝牙多跳公开频道（`mesh`）与「1v1 私信（Private DM）」端到端加密频道（`dm:<peerHex>`）
- **协议兼容性**：零新增封包类型、零加密算法改动，100% 复用 bitchat 规范 `§29 Live voice`（公开广播 `0x29`、私信 `NoisePayload 0x08`）

---

## 1. 背景与设计目标

此前版本虽然在底层实现了 `§29` 实时语音流（`VoiceBurst` + `LiveVoice`），但存在两个核心体验缺陷：
1. **无独立入口、无对讲机形态**：实时流隐藏在普通输入框右下角的麦克风按钮长按手势中，与普通录语音条混杂，用户无法感知「对讲机」功能的存在。
2. **非半双工互斥**：原实现允许最多 8 路并发发声，两人同时按住说话会互相覆盖或混音，且默认往文字聊天列表里不断插入语音气泡。

本次设计目标是打造类似微信「共享实时位置 · 实时对讲」与真实硬件手台的**半双工仿真对讲机体验**：
- **双入口直达**，展开底部半屏仿真对讲抽屉，上半屏保留聊天消息、照片与跳数可见；
- **严格半双工互斥**（同一频道同一时刻仅允许一人讲话，他人讲话时本机按键锁定占线，离线双发碰撞确定性自动让行）；
- **纯实时对讲、听过即走**（对讲语音不在聊天列表生成语音气泡；原右下角麦克风彻底解耦，专注「普通录语音条 + 试听发送」）；
- **拟真声光触反馈**（状态指示灯、开麦音、松手/讲完释放音、占线忙音与触觉震动）。

---

## 2. 界面摆放与交互设计（方案 A）

### 2.1 入口布局
1. **顶部栏常驻胶囊（主入口）**
   - **「附近（Nearby）」页**（`MainScreen.kt` / `MeshVisuals.kt`）：`NearbyStatusCapsuleRow` 右侧升级为扁平胶囊按钮 **`[ 对讲 ]`**（展开抽屉时高亮为信号橙填充态 `对讲中`）。收起抽屉期间若有他人呼叫，左侧信号胶囊切换为四柱动态声波 + `昵称#xxxx 讲话中`，点击该胶囊亦可直接展开底部对讲抽屉。
   - **「1v1 私信（PrivateChatScreen）」页**（`PrivateChats.kt`）：顶部栏右侧动作区新增 **`[ 对讲 ]`** 按钮（展开抽屉时高亮为加密锁蓝填充态）。
2. **输入框 `+` 号展开栏（副入口）**
   - `ChatPane.kt` 左下角 `+` 按钮改为展开/收起上方快捷操作条，包含两个扁平胶囊：**`[ 照片 ]`**（调起系统选图）与 **`[ 实时对讲 ]`**（切换到半屏对讲抽屉）。
3. **原右下角麦克风按钮解耦**
   - `Composer` 右下角麦克风移除 `LiveCapture` 实时流逻辑，仅调用 `VoiceRecorder` 进行本地 `.m4a` 录音 → 停止预览试听 → 发送 `FilePacket (0x22)` 语音气泡。

### 2.2 底部半屏「仿真对讲机抽屉」（`WalkieTalkieDrawer`）
抽屉展开时在 `ChatPane` 底部平滑替换单行文字输入栏（高度 `216dp`，位于底部悬浮玻璃导航栏上方，上半屏消息列表自动上推保持底部对齐）：

- **色彩语义**（遵循 `docs/design/ui-direction.md`）：
  - `Nearby` 公开频道主强调色使用**信号橙**（`OpenChatPalette.Signal`）；
  - `Private` 1v1 加密私信主强调色使用**加密锁蓝**（`OpenChatPalette.Lock`）；
  - 空闲指示灯使用在线绿（`OpenChatPalette.Online`），占线锁定指示使用琥珀色（`#F59E0B`）。
- **抽屉顶栏**：
  - 左侧：状态圆点 + 实时信道文案（如 `频道空闲 · 可达 5 人`、`加密专线 · 苏轼#e887`、`正在发射 0:04` 或 `张衡#e887 正在讲话 · 经 2 跳`）。
  - 右侧：**`[ 外放开关 ]`**（绑定 `LiveVoicePrefs`，静音后暂停接收外放与提示音）+ **`[ 切回键盘 ]`**（收起对讲抽屉回到文字输入栏）。
- **中央 `112dp` 按住说话圆盘（四态表现）**：
  1. **空闲待命（`IDLE`）**：绿点提示，圆盘显示麦克风/对讲矢量图标与 `按住 说话`。按下即发起讲话，松手即结束；若轻点不足 `300ms` 视为误触，取消发射并短暂提示 `请按住说话`。
  2. **本机发射（`TRANSMITTING`）**：圆盘呈高对比主色填充，外围绘制随本机实时分贝（`liveMicLevel`）缩放的脉冲呼吸环，中心显示动态声波与秒表计时（上限 `60s`，最后 `5s` 倒数提醒 `剩余 5s`）。
  3. **他人讲话 / 信道占线（`RECEIVING`）**：圆盘锁定不可触发发射，中心渲染当前发言者的 `EmblemView` 对称徽记 + 实时输出电平声波，下方显示 `信道占用中`。此时若用户按压圆盘，触发短促拒绝震动、播放占线忙音并在顶栏短暂提示 `对方正在讲话，请稍候`。
  4. **不可用态（`UNAVAILABLE`）**：
     - 附近频道 `otherPeers.isEmpty()`：圆盘置灰，文案 `附近暂无节点 · 等待设备进入范围`；
     - 私信对方不在蓝牙网格可达列表（`!isReachable`）：圆盘置灰，文案 `对方不在蓝牙范围内 · 暂不可实时对讲`；
     - 私信对方可达但 Noise XX 握手进行中（`!hasSession`）：显示 `正在建立加密信道…`，握手完成后自动切入 `IDLE`；
     - 本机已关闭实时对讲外放开关（静音态）：圆盘提示 `已静音实时对讲 · 点右上角开启`。

---

## 3. 核心架构与半双工状态机

### 3.1 模块划分与职责

| 模块 / 文件 | 核心职责 |
|---|---|
| `core/VoiceBurst.kt` | 保持现有 `§29` 封包编解码（`Start` / `Frames` / `End` / `Canceled`）与 `BurstAssembly` 乱序重组不变 |
| `app/PttFloorCoordinator.kt`（新增） | 纯 Kotlin 单信道半双工裁决器（无 Android UI 依赖，便于纯 JVM 单元测试）：维护每个会话的 `IDLE / TRANSMITTING / RECEIVING` 状态、单路占线锁、双发碰撞 `peerIdHex` 裁决、超时释放与已播 `burstHex` 去重表 |
| `app/PttTones.kt`（新增） | 基于 `AudioTrack` 内存合成极短 PCM 正弦波提示音（开麦音 `Chirp`、释放音 `Roger`、占线音 `Busy`），零音频资源文件 |
| `app/LiveVoice.kt` | 重构为纯实时对讲流引擎：`LivePttCapture` 仅采集编码并发送 `VoiceBurst`（不写 `.m4a`）；`LiveVoice` 结合 `PttFloorCoordinator` 接收单路语音并交由 `LivePlayer` 实时外放与计算播放电平，不再创建 `ChatMessage` 气泡 |
| `mesh/MeshEngine.kt` | 新增轻量级 `ensureSession(peerHex)` 与 `onVoiceFrame(..., hops: Int?)` 跳数透传，使私信打开对讲抽屉即预热握手，公开频道对讲可展示 `经 N 跳` |
| `app/ChatRepository.kt` & `MeshService.kt` | 暴露 `pttStates: StateFlow<Map<String, PttUiState>>`、`liveRxLevel: StateFlow<Float>`，并在 `ChatController` 提供 `openPtt(conversation)`、`startPtt(conversation): PttStartResult`、`stopPtt()`、`cancelPtt()` |
| `ui/WalkieTalkieDrawer.kt`（新增） | 半屏对讲抽屉 Composable 组件（四态大圆盘、脉冲动画、手势防抖与无障碍语义） |
| `ui/ChatPane.kt` / `MeshVisuals.kt` / `PrivateChats.kt` | 接入顶部 `[ 对讲 ]` 胶囊、`+` 双格展开栏、底部抽屉切换，并简化原 `Mic` 按钮为纯本地语音条录制 |

### 3.2 半双工状态机与碰撞裁决规则（`PttFloorCoordinator`）

每个 `conversation`（`"mesh"` 或 `"dm:<peerHex>"`）独立维护状态：

1. **本地请求开麦 `tryAcquireTx(conversation, myPeerHex, now)`**：
   - 若当前为 `RECEIVING` 且未超时：拒绝并返回 `PttStartResult.BUSY`，不启动麦克风；
   - 若当前为 `IDLE`：转入 `TRANSMITTING(startedAt = now)`，允许启动 `LivePttCapture`。
2. **收到远端语音帧 `onRemotePacket(conversation, senderHex, senderName, hops, packet, now)`**：
   - 若 `senderHex` 与本机 `myPeerHex` 相同（回环回声），直接忽略。
   - 实时外放条件：只要 App 处于前台、当前正打开该 `conversation`（无论底部对讲抽屉处于展开还是收起状态）且 `LiveVoicePrefs` 未静音，即启动 `LivePlayer` 实时外放。
   - 若 `packet.kind == Canceled`：若 `senderHex` 是当前 `RECEIVING` 持有者，立即释放回 `IDLE`（不播 Roger Beep）。
   - 若当前为 `IDLE`：
     - 忽略孤立的 `End` / `Canceled` 或已在 `finishedBursts` 中的旧包；
     - 收到有效 `Start` 或 `Frames` 时转入 `RECEIVING(senderHex, senderName, burstHex, hops, startedAt = now)`。
   - 若当前为 `RECEIVING(active)`：
     - **同一发言者同一 `burstHex`**：正常喂入 `BurstAssembly` 解码外放；当 `BurstAssembly` 进入 `FINISHED` 时转回 `IDLE` 并触发一次 Roger Beep；
     - **同一发言者开启新 `burstHex`**：无缝替换新 `burstHex`；
     - **其他并发第三人（`senderHex != active.senderHex`）**：直接丢弃，不混音、不抢占当前发言者，确保单频段一次只有一人出声。
   - 若当前为 **`TRANSMITTING`（离线双发碰撞窗口）**：
     - 比较 `senderHex.lowercase()` 与 `myPeerHex.lowercase()`：
       - 若远端优先级更高（`senderHex < myPeerHex`）：判定**远端胜出**，触发回调通知本机立即 `cancelPtt()`（发出 `VoiceBurst.Canceled` 停止录音），状态原子切换为 `RECEIVING(senderHex, ...)` 并播放短促让行提示音 + 轻震；
       - 若本机优先级更高（`myPeerHex < senderHex`）：判定**本机胜出**，保持 `TRANSMITTING` 并忽略远端碰撞包（远端用对称规则同样会算出本机胜出并主动收麦切换为收听）。
3. **超时防死锁保护**：
   - **发端 TOT 限时**：本机连续按住达 `60_000 ms` 自动触发 `stopPtt()` 发送 `End`，转回 `IDLE` 并响释放音；按压不足 `300 ms` 松手自动走 `cancelPtt()` 发送 `Canceled`。
   - **收端断流超时**：`BurstAssembly.IDLE_MS = 3_000 ms` 内未收到后续帧时，定时器自动结束该 `Burst` 并使信道回到 `IDLE`，防止发送者走出蓝牙范围导致接收端永久卡在占线态。

### 3.3 与原版 bitchat 尾随语音条的兼容去重
- 本机通过新对讲圆盘发射时，仅发送 `VoiceBurst` 序列，不生成、不发送 `voice_<burst>.m4a` 文件包，聊天流保持干净。
- 当收到来自原版 bitchat 设备的对讲语音时：
  - 若本机开启了实时对讲且已通过 `LivePlayer` 实时播放了该 `burstHex`（至少解码出有效音频帧），`PttFloorCoordinator` 将 `(conversation, senderHex, burstHex)` 记入 `heardLiveBursts`（容量上限 64 条，TTL 10 分钟）；
  - 随后原版 bitchat 补发的 `voice_<burstHex>.m4a` 到达 `MeshService.onFile` 时，`liveVoice.absorb(...)` 命中 `heardLiveBursts` 后直接删除临时文件并返回 `true`（不重复生成气泡）；
  - 若刚才本机处于后台、未打开实时外放或完全未收到实时帧，则 `absorb` 返回 `false`，该 `.m4a` 照常作为普通语音气泡展示在聊天列表中，确保紧急消息不丢失。

---

## 4. 拟真提示音、生命周期与多语言规范

### 4.1 合成提示音参数（`PttTones`）
通过 `16 kHz` 单声道 16-bit PCM 内存缓冲合成（带 `5ms` 首尾渐入渐出包络消除爆音），遵循 `LiveVoicePrefs.enabled` 静音状态：
- **开麦音（`playStart`）**：`880 Hz (30ms) -> 1175 Hz (35ms)` 双音升调，在 `AudioRecord.startRecording()` 之前完成播放，避免录进发射流。
- **释放音（`playEnd` / Roger Beep）**：`1046 Hz (55ms)` 清脆单音，在本地正常松手结束发射（`>= 300ms`）或远端语音流正常播放完毕释放信道时触发。
- **占线/让行音（`playBusy`）**：`330 Hz (45ms) + 静音 (30ms) + 330 Hz (45ms)` 低频双短音，在占线拒按或双发碰撞让行时触发。

### 4.2 异常与安全边界
- **生命周期中断**：手指滑出圆盘手势取消、切换 Tab 或返回上一页、`MainActivity` 退至后台（`ChatRepository.foreground == false`）、蓝牙关闭、系统电话/闹钟抢占音频焦点（`AudioFocusRequest` 丢失）时，若处于 `TRANSMITTING`，立即执行 `cancelPtt()` 释放麦克风与信道。
- **紧急擦除（Panic Wipe）**：连点三下左上角 Logo 时，`MeshService.panic()` 立即中断正在进行的 `LivePttCapture` 与 `LivePlayer`，清空 `PttFloorCoordinator` 所有状态与缓存。
- **无障碍与减少动态效果**：遵循 `rememberReducedMotion()`，开启系统“移除动画”时关闭呼吸脉冲环，仅保留实心色块与静态电平条；圆盘提供明确的 `contentDescription` 与状态播报。
- **多语言与使用说明**：在 `values/strings.xml` 及全部 17 个本地化目录（含 `values-zh`、`values-b+zh+Hant` 及 RTL 语言 `ar`/`fa`）补齐所有新增文案，确保 `lintDebug` 与 `lintVitalRelease` 零 `MissingTranslation` 报错；同步更新 `GuideScreen.kt` 中的对讲机说明。

---

## 5. 测试验证清单

1. **纯 JVM 单元测试（`PttFloorCoordinatorTest.kt`）**：
   - 空闲开麦 → 正常松手释放（`>= 300ms`）与极短误触取消（`< 300ms`）；
   - `RECEIVING` 占线期间本地 `tryAcquireTx` 返回 `BUSY`；
   - `RECEIVING` 期间第三人并发 `VoiceBurst` 被隔离丢弃、不覆盖当前发言者；
   - `TRANSMITTING` 期间收到远端包时，按 `peerIdHex` 字典序确定性裁决胜负（低字典序胜出继续发射，高字典序自动触发 `cancel` 切入 `RECEIVING`）；
   - 远端断流满 `3_000 ms` 后 `tick()` 自动释放信道回 `IDLE`；
   - `heardLiveBursts` 对原版 bitchat 尾随 `.m4a` 的已播吸收与未播放行（及 10 分钟过期淘汰）。
2. **协议编解码回归测试**：保留并通过现有 `VoiceBurst` 等全部 201 项单元测试。
3. **构建与静态检查门槛**：
   - `./gradlew :app:testDebugUnitTest` 全部通过；
   - `./gradlew :app:lintDebug :app:assembleDebug` 零错误构建通过。
