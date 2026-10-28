package org.openchat.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openchat.android.app.ChatMessage
import org.openchat.android.app.ChatRepository
import org.openchat.android.app.Conversation
import org.openchat.android.app.DeliveryState
import org.openchat.android.nostr.Geohash
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Accent = Color(0xFF1F7A4D)

@Composable
fun OpenChatTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) darkColorScheme(primary = Color(0xFF6FD3A0), secondary = Color(0xFF6FD3A0))
    else lightColorScheme(primary = Accent, secondary = Accent)
    MaterialTheme(colorScheme = scheme, content = content)
}

// ---------------------------------------------------------------- onboarding

@Composable
fun OnboardingScreen(onStart: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(28.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("没网，也能聊。", fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            Text(
                "停电、地震、台风、基站挤爆、在山里或飞机上，手机没有信号时，openchat 用蓝牙把附近的手机连成一张网，消息一台传一台。",
                fontSize = 17.sp, lineHeight = 26.sp,
            )
            Spacer(Modifier.height(12.dp))
            Text("不用注册，不要手机号，没有服务器。", fontSize = 17.sp, lineHeight = 26.sp)
            Spacer(Modifier.height(24.dp))
            SafetyNote()
            Spacer(Modifier.height(32.dp))
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("开始", fontSize = 18.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text("下一步会请你允许使用蓝牙，用来发现附近的人。", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SafetyNote() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp)) {
            Text("请先知道", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "· 「附近」频道是公开的，范围内所有人都能看到。\n" +
                    "· 私信是加密的，但附近的人能看出你在用这个 App，也能长期认出同一台手机。\n" +
                    "· 本软件未经完整的独立安全审计。如果被识别出来会有严重后果，请不要依赖它。\n" +
                    "· 连点三下顶部标题，立刻清空所有数据。",
                fontSize = 14.sp, lineHeight = 21.sp,
            )
        }
    }
}

// ---------------------------------------------------------------- main shell

private enum class Tab(val label: String) { NEARBY("附近"), PRIVATE("私信"), LOCATION("位置频道"), ME("我") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    missingPermissions: Boolean,
    onGrantPermissions: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onRequestLocation: () -> Unit,
    onShareApp: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(Tab.NEARBY) }
    var openChat by rememberSaveable { mutableStateOf<String?>(null) } // conversation key
    var wiped by remember { mutableStateOf(false) }
    val unread by ChatRepository.unread.collectAsState()
    val mesh by ChatRepository.mesh.collectAsState()
    val peers by ChatRepository.peers.collectAsState()

    val onPanic = {
        ChatRepository.controller?.panic()
        openChat = null
        tab = Tab.NEARBY
        wiped = true
    }

    if (wiped) {
        AlertDialog(
            onDismissRequest = { wiped = false },
            confirmButton = { TextButton(onClick = { wiped = false }) { Text("好") } },
            text = { Text("已清空。你现在是一个新身份：${mesh.myNickname}") },
        )
    }

    val chat = openChat
    if (chat != null && chat.startsWith("dm:")) {
        PrivateChatScreen(chat.removePrefix("dm:"), onBack = { openChat = null }, onPanic = onPanic)
        return
    }
    if (chat != null && chat.startsWith("geo:")) {
        GeoChatScreen(chat.removePrefix("geo:"), onBack = { openChat = null }, onPanic = onPanic)
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { PanicTitle(tab.label, onPanic) })
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    val count = when (t) {
                        Tab.NEARBY -> unread[Conversation.Mesh.key] ?: 0
                        Tab.PRIVATE -> unread.filterKeys { it.startsWith("dm:") }.values.sum()
                        Tab.LOCATION -> unread.filterKeys { it.startsWith("geo:") }.values.sum()
                        Tab.ME -> 0
                    }
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        label = { Text(t.label) },
                        icon = {
                            BadgedBox(badge = { if (count > 0) Badge { Text("$count") } }) {
                                Icon(
                                    when (t) { Tab.NEARBY -> Icons.Filled.Home; Tab.PRIVATE -> Icons.Filled.Email; Tab.LOCATION -> Icons.Filled.LocationOn; Tab.ME -> Icons.Filled.Person },
                                    contentDescription = t.label,
                                )
                            }
                        },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            StatusBanner(missingPermissions, mesh.bluetoothOn, mesh.running, peers.size, onGrantPermissions, onEnableBluetooth)
            when (tab) {
                Tab.NEARBY -> ChatPane(
                    conversation = Conversation.Mesh.key,
                    emptyText = if (peers.isEmpty()) "附近还没有人。\n打开 openchat 的手机靠近到几十米内，就会自动连上。\n同一网络里的 bitchat 用户也能收到你的消息。" else "附近有 ${peers.size} 人。发一句话，大家都能看到。",
                    placeholder = "对附近所有人说…",
                    onSend = { ChatRepository.controller?.sendPublic(it) },
                    onSenderClick = { key -> if (key != mesh.myPeerHex) openChat = Conversation.Private(key).key },
                )
                Tab.PRIVATE -> PrivateList(onOpen = { openChat = Conversation.Private(it).key })
                Tab.LOCATION -> LocationPane(onRequestLocation, onOpen = { openChat = Conversation.Geo(it).key })
                Tab.ME -> MePane(onShareApp)
            }
        }
    }
}

/** Title that wipes everything on a triple tap, with no confirmation (docs/protocol-spec.md §10). */
@Composable
fun PanicTitle(text: String, onPanic: () -> Unit) {
    var taps by remember { mutableStateOf(0) }
    var firstTap by remember { mutableLongStateOf(0L) }
    Text(
        text,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
            val now = System.currentTimeMillis()
            if (now - firstTap > 800) { firstTap = now; taps = 0 }
            taps++
            if (taps >= 3) { taps = 0; firstTap = 0; onPanic() }
        },
    )
}

@Composable
private fun StatusBanner(missing: Boolean, btOn: Boolean, running: Boolean, peers: Int, onGrant: () -> Unit, onEnable: () -> Unit) {
    val (text, action) = when {
        missing -> "需要蓝牙权限才能发现附近的人" to onGrant
        !btOn -> "蓝牙已关闭，点这里打开" to onEnable
        !running -> "正在启动…" to null
        peers == 0 -> "正在寻找附近的人…" to null
        else -> "已连上附近 $peers 人" to null
    }
    val warn = missing || !btOn
    Box(
        Modifier.fillMaxWidth()
            .background(if (warn) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer)
            .let { if (action != null) it.clickable { action() } else it }
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(text, fontSize = 14.sp, color = if (warn) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

// ---------------------------------------------------------------- chat

@Composable
fun ChatPane(
    conversation: String,
    emptyText: String,
    placeholder: String,
    onSend: (String) -> Unit,
    onSenderClick: ((String) -> Unit)? = null,
    onVisible: (List<ChatMessage>) -> Unit = {},
) {
    val all by ChatRepository.messages.collectAsState()
    val list = all[conversation].orEmpty()
    val state = rememberLazyListState()

    DisposableEffect(conversation) {
        ChatRepository.openConversation = conversation
        ChatRepository.clearUnread(conversation)
        onDispose { if (ChatRepository.openConversation == conversation) ChatRepository.openConversation = null }
    }
    LaunchedEffect(list.size) {
        if (list.isNotEmpty()) state.animateScrollToItem(list.size - 1)
        ChatRepository.clearUnread(conversation)
        onVisible(list)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (list.isEmpty()) {
                Text(emptyText, Modifier.align(Alignment.Center).padding(32.dp), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 22.sp)
            } else {
                LazyColumn(state = state, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(list, key = { it.senderKey + it.id }) { m -> Bubble(m, onSenderClick) }
                }
            }
        }
        Composer(placeholder, onSend)
    }
}

@Composable
private fun Bubble(m: ChatMessage, onSenderClick: ((String) -> Unit)?) {
    val time = remember(m.timestamp) { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(m.timestamp)) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (m.mine) Alignment.End else Alignment.Start) {
        if (!m.mine) {
            Text(
                m.senderName, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.let { if (onSenderClick != null) it.clickable { onSenderClick(m.senderKey) } else it },
            )
        }
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (m.mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Text(m.text, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), fontSize = 16.sp)
        }
        val status = if (m.mine) when (m.state) {
            DeliveryState.SENDING -> " · 发送中"; DeliveryState.SENT -> " · 已发出"; DeliveryState.DELIVERED -> " · 已送达"
            DeliveryState.READ -> " · 已读"; DeliveryState.FAILED -> " · 发送失败"
        } else ""
        Text(time + status, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Composer(placeholder: String, onSend: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val send = { val t = text.trim(); if (t.isNotEmpty()) { onSend(t); text = "" } }
    HorizontalDivider()
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text, onValueChange = { if (it.length <= 1000) text = it },
            placeholder = { Text(placeholder) },
            modifier = Modifier.weight(1f),
            maxLines = 4,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
        )
        IconButton(onClick = send) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送", tint = MaterialTheme.colorScheme.primary) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivateChatScreen(peerHex: String, onBack: () -> Unit, onPanic: () -> Unit) {
    val names by ChatRepository.names.collectAsState()
    val peers by ChatRepository.peers.collectAsState()
    val peer = peers.firstOrNull { it.peerIdHex == peerHex }
    val name = peer?.nickname ?: names[peerHex] ?: peerHex.take(8)
    val readSent = remember { HashSet<String>() }
    Scaffold(topBar = {
        TopAppBar(
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            title = {
                Column {
                    PanicTitle(name, onPanic)
                    Text(if (peer != null) "在附近 · 加密私信" else "不在附近 · 消息会在对方回来后送达", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
        )
    }) { padding ->
        Box(Modifier.padding(padding)) {
            ChatPane(
                conversation = Conversation.Private(peerHex).key,
                emptyText = "和 $name 的私信是端到端加密的，中间转发的手机看不到内容。",
                placeholder = "写给 $name…",
                onSend = { ChatRepository.controller?.sendPrivate(peerHex, it) },
                onVisible = { list ->
                    val unreadIds = list.filter { !it.mine && readSent.add(it.id) }.map { it.id }
                    if (unreadIds.isNotEmpty()) ChatRepository.controller?.markRead(peerHex, unreadIds)
                },
            )
        }
    }
}

@Composable
private fun PrivateList(onOpen: (String) -> Unit) {
    val peers by ChatRepository.peers.collectAsState()
    val all by ChatRepository.messages.collectAsState()
    val names by ChatRepository.names.collectAsState()
    val unread by ChatRepository.unread.collectAsState()
    val me by ChatRepository.mesh.collectAsState()
    val withHistory = all.keys.filter { it.startsWith("dm:") }.map { it.removePrefix("dm:") }
    val ids = (peers.map { it.peerIdHex } + withHistory).distinct().filter { it != me.myPeerHex }
    if (ids.isEmpty()) {
        Box(Modifier.fillMaxSize()) {
            Text("附近的人会出现在这里。\n点一下名字就能发加密私信。", Modifier.align(Alignment.Center).padding(32.dp), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn {
        items(ids, key = { it }) { hex ->
            val peer = peers.firstOrNull { it.peerIdHex == hex }
            val last = all["dm:$hex"]?.lastOrNull()
            Row(Modifier.fillMaxWidth().clickable { onOpen(hex) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(if (peer != null) Color(0xFF2EB872) else Color.Gray, CircleShape))
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(peer?.nickname ?: names[hex] ?: hex.take(8), fontWeight = FontWeight.SemiBold)
                    Text(
                        last?.text ?: if (peer?.direct == true) "就在身边" else if (peer != null) "经其他手机转发可达" else "不在附近",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                    )
                }
                val n = unread["dm:$hex"] ?: 0
                if (n > 0) Badge { Text("$n") }
            }
            HorizontalDivider()
        }
    }
}

// ---------------------------------------------------------------- location channels

@Composable
private fun LocationPane(onRequestLocation: () -> Unit, onOpen: (String) -> Unit) {
    val geo by ChatRepository.geo.collectAsState()
    var manual by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("有网络时，可以和同一地区的人聊天，距离不限于蓝牙范围。每个地区用一个独立的匿名身份。", lineHeight = 22.sp)
        Spacer(Modifier.height(16.dp))
        val here = geo.locationGeohash
        if (here == null) {
            FilledTonalButton(onClick = onRequestLocation) { Text("使用我的大致位置") }
        } else {
            Geohash.Level.entries.forEach { level ->
                val cell = here.take(level.precision)
                val n = geo.participants[cell] ?: 0
                OutlinedButton(
                    onClick = { ChatRepository.controller?.joinGeo(cell, teleported = false); onOpen(cell) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) {
                    Text("${level.label} · #$cell" + if (n > 0) " · $n 人在线" else "", Modifier.weight(1f))
                }
            }
            TextButton(onClick = onRequestLocation) { Text("刷新位置") }
        }
        Spacer(Modifier.height(16.dp))
        Text("去别的地区看看", fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(manual, { manual = it.lowercase().filter { c -> c.isLetterOrDigit() }.take(8) }, placeholder = { Text("geohash，如 wtw3s") }, modifier = Modifier.weight(1f), singleLine = true)
            Spacer(Modifier.size(8.dp))
            Button(enabled = Geohash.isValid(manual) && manual.length >= 2, onClick = {
                ChatRepository.controller?.joinGeo(manual, teleported = true); onOpen(manual)
            }) { Text("进入") }
        }
        Text("你不在那里，所以别人会看到你的名字旁有 ✈ 标记。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (geo.joined.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("已加入", fontWeight = FontWeight.SemiBold)
            geo.joined.sorted().forEach { g -> TextButton(onClick = { onOpen(g) }) { Text("#$g") } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeoChatScreen(geohash: String, onBack: () -> Unit, onPanic: () -> Unit) {
    val geo by ChatRepository.geo.collectAsState()
    Scaffold(topBar = {
        TopAppBar(
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            title = {
                Column {
                    PanicTitle("#$geohash", onPanic)
                    val n = geo.participants[geohash] ?: 0
                    Text(if (geo.relaysConnected == 0) "正在连接网络…" else "公开频道 · 近 5 分钟 $n 人", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            actions = { TextButton(onClick = { ChatRepository.controller?.leaveGeo(geohash); onBack() }) { Text("退出") } },
        )
    }) { padding ->
        Box(Modifier.padding(padding)) {
            ChatPane(
                conversation = Conversation.Geo(geohash).key,
                emptyText = "这是 #$geohash 的公开频道，需要联网。附近用 bitchat 的人也在这里。",
                placeholder = "对 #$geohash 说…",
                onSend = { ChatRepository.controller?.sendGeo(geohash, it) },
            )
        }
    }
}

// ---------------------------------------------------------------- me

@Composable
private fun MePane(onShareApp: () -> Unit) {
    val mesh by ChatRepository.mesh.collectAsState()
    var nick by rememberSaveable(mesh.myNickname) { mutableStateOf(mesh.myNickname) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("我的名字", fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(nick, { nick = it.take(32) }, singleLine = true, modifier = Modifier.weight(1f))
            Spacer(Modifier.size(8.dp))
            Button(enabled = nick.isNotBlank() && nick != mesh.myNickname, onClick = { ChatRepository.controller?.setNickname(nick) }) { Text("保存") }
        }
        Spacer(Modifier.height(24.dp))
        Text("把 openchat 传给身边的人", fontWeight = FontWeight.SemiBold)
        Text("不需要应用商店，也不需要网络。可以用蓝牙、快速分享（附近分享）或其他方式直接发送安装包。", fontSize = 14.sp, lineHeight = 21.sp)
        Spacer(Modifier.height(8.dp))
        Button(onClick = onShareApp, modifier = Modifier.fillMaxWidth()) { Text("分享安装包") }
        Spacer(Modifier.height(24.dp))
        SafetyNote()
        Spacer(Modifier.height(24.dp))
        Text("身份指纹", fontWeight = FontWeight.SemiBold)
        Text("当面核对这串字符，可以确认对方真的是你认识的人。", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(mesh.fingerprint.chunked(4).joinToString(" "), fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp)
        Spacer(Modifier.height(8.dp))
        Text("设备 ID：${mesh.myPeerHex}", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Text("openchat 兼容 bitchat 网络。开源，MIT 许可证。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
