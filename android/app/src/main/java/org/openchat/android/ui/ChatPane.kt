package org.openchat.android.ui

import android.media.MediaPlayer
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.openchat.android.R
import org.openchat.android.app.ChatMessage
import org.openchat.android.app.ChatRepository
import org.openchat.android.app.Commands
import org.openchat.android.app.Conversation
import org.openchat.android.app.DeliveryState
import org.openchat.android.app.Media
import org.openchat.android.app.PeerLabel
import org.openchat.android.app.Social
import org.openchat.android.app.VoiceRecorder
import org.openchat.android.core.Cashu
import org.openchat.android.core.Mentions
import org.openchat.android.core.sha256
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatPane(
    conversation: String,
    emptyText: String,
    placeholder: String,
    onSend: (String) -> Unit,
    onSenderClick: ((ChatMessage) -> Unit)? = null,
    onVisible: (List<ChatMessage>) -> Unit = {},
    /**
     * A screen that stands in for the timeline while [cover] holds, even over earlier messages
     * (the radar while nobody is nearby). It gets a way to the timeline when there is one.
     */
    empty: (@Composable (onShowHistory: (() -> Unit)?) -> Unit)? = null,
    cover: Boolean = false,
) {
    val all by ChatRepository.messages.collectAsState()
    val social by Social.state.collectAsState()
    val mesh by ChatRepository.mesh.collectAsState()
    val list = all[conversation].orEmpty().filter { m ->
        when {
            m.mine -> true
            conversation.startsWith("geo:") || conversation.startsWith("gdm:") -> m.senderKey !in social.geoBlocked
            else -> !social.isBlockedPeer(m.senderKey)
        }
    }
    val state = rememberLazyListState()
    val rows = remember(list) { withDayBreaks(list) }
    val isPublicFeed = conversation == Conversation.Mesh.key || conversation.startsWith("geo:")
    val private = conversation.startsWith("dm:") || conversation.startsWith("gdm:")
    val note = if (private) stringResource(R.string.dm_day_note) else null

    DisposableEffect(conversation) {
        ChatRepository.openConversation = conversation
        ChatRepository.clearUnread(conversation)
        onDispose { if (ChatRepository.openConversation == conversation) ChatRepository.openConversation = null }
    }
    val atBottom by remember {
        derivedStateOf {
            val info = state.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            last.index >= info.totalItemsCount - 2
        }
    }
    val newest = list.lastOrNull()
    var showHistory by remember(conversation) { mutableStateOf(false) }
    LaunchedEffect(cover) { if (!cover) showHistory = false }
    var seenNewest by remember(conversation) { mutableStateOf(newest?.id) }
    LaunchedEffect(newest?.id) {
        if (newest?.id != seenNewest && newest?.mine == true) showHistory = true
        seenNewest = newest?.id
    }
    val covered = empty != null && (list.isEmpty() || (cover && !showHistory))
    LaunchedEffect(covered) { if (!covered && rows.isNotEmpty()) state.scrollToItem(rows.size - 1) }
    LaunchedEffect(newest?.id, newest?.senderKey) {
        if (newest != null && (newest.mine || atBottom)) state.animateScrollToItem(rows.size - 1)
        ChatRepository.clearUnread(conversation)
        onVisible(list)
    }

    Column(Modifier.fillMaxSize().background(OpenChatPalette.Screen).imePadding()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (covered) {
                empty(if (list.isEmpty()) null else ({ showHistory = true }))
            } else if (list.isEmpty()) {
                Column(
                    Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    AudiencePill(encrypted = !isPublicFeed)
                    Text(
                        emptyText,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp,
                        lineHeight = 21.sp,
                    )
                }
            } else {
                LazyColumn(
                    state = state,
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(if (isPublicFeed) 0.dp else 2.dp),
                ) {
                    items(rows, key = { it.key }) { r ->
                        when (r) {
                            is TimelineRow.Day -> DayHeader(r.timestamp, note?.takeIf { r.first })
                            is TimelineRow.Message -> {
                                if (r.m.system) {
                                    SystemNoticeRow(r.m.text)
                                } else if (isPublicFeed) {
                                    PublicPostRow(r, myNickname = mesh.myNickname, myPeerHex = mesh.myPeerHex, onSenderClick = onSenderClick)
                                } else {
                                    EncryptedBubbleRow(r, onSenderClick = onSenderClick)
                                }
                            }
                        }
                    }
                }
            }
        }
        val peers by ChatRepository.peers.collectAsState()
        val names by ChatRepository.names.collectAsState()
        val persons = if (conversation.startsWith("geo:")) {
            list.filter { !it.mine && !it.system }.distinctBy { it.senderKey }.map { Commands.Person(PeerLabel.of(it), it.senderKey) }
        } else peers.filter { it.peerIdHex != mesh.myPeerHex }.map { Commands.Person(PeerLabel.mesh(it.nickname, it.peerIdHex), it.peerIdHex) }
        val dmPeer = conversation.takeIf { it.startsWith("dm:") }?.removePrefix("dm:")?.let { Commands.Person(PeerLabel.mesh(names[it], it), it) }
        val ctx = LocalContext.current
        Composer(conversation, placeholder, persons.map { it.label }) { text ->
            if (!Commands.isCommand(text)) onSend(text)
            else applyCommand(ctx, Commands.run(text, conversation, mesh.myNickname, persons, dmPeer), conversation, onSend)
        }
    }
}

@Composable
private fun SystemNoticeRow(text: String) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = OpenChatPalette.Surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Text(
                text,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Public channel row (Nearby & Location channels): full-width bulletin style without chat bubbles
 * (docs/design/ui-direction.md §4, docs/design/ui-prototype.html `.post`).
 */
@Composable
private fun PublicPostRow(
    row: TimelineRow.Message,
    myNickname: String,
    myPeerHex: String,
    onSenderClick: ((ChatMessage) -> Unit)?,
) {
    val m = row.m
    val locale = appLocale()
    val time = remember(m.timestamp, locale) { stampText(m.timestamp, locale) }
    val social by Social.state.collectAsState()
    val senderKey = if (m.mine && m.senderKey.isEmpty()) myPeerHex else m.senderKey

    Column(
        Modifier
            .fillMaxWidth()
            .let {
                if (m.mentionsMe) it.background(OpenChatPalette.SignalSoft.copy(alpha = 0.4f))
                else it
            },
    ) {
        if (row.groupStart) {
            Hairline(Modifier.padding(horizontal = 16.dp))
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    top = if (row.groupStart) 10.dp else 2.dp,
                    bottom = if (row.groupEnd) 10.dp else 3.dp,
                ),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (row.groupStart) {
                Box(
                    Modifier
                        .padding(top = 2.dp)
                        .let { if (!m.mine && onSenderClick != null && !m.bridged) it.clickable { onSenderClick(m) } else it }
                        .clearAndSetSemantics { },
                ) {
                    EmblemView(senderKey.ifEmpty { "self" }, 30.dp)
                }
            } else {
                Spacer(Modifier.width(30.dp))
            }

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (row.groupStart) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val isMesh = !m.bridged && !m.conversation.startsWith("geo:")
                        val label = if (m.mine) {
                            if (m.conversation.startsWith("geo:") && m.senderKey.isNotEmpty()) PeerLabel.of(m)
                            else PeerLabel.mesh(myNickname, myPeerHex)
                        } else {
                            PeerLabel.of(m)
                        }
                        Text(
                            buildAnnotatedString {
                                val formatted = labelText(
                                    label,
                                    teleported = m.teleported,
                                    verified = !m.mine && isMesh && social.isVerifiedPeer(m.senderKey),
                                    vouched = !m.mine && isMesh && social.isVouchedPeer(m.senderKey),
                                )
                                if (m.mine) {
                                    withStyle(SpanStyle(color = OpenChatPalette.Ink)) { append(formatted) }
                                } else {
                                    withStyle(SpanStyle(color = Emblem.color(senderKey))) { append(formatted) }
                                }
                                if (m.bridged) {
                                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Normal)) {
                                        append(" · " + stringResource(R.string.bridge_via))
                                    }
                                }
                            },
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .weight(1f)
                                .let {
                                    if (!m.mine && onSenderClick != null && !m.bridged) it.clickable(role = Role.Button) { onSenderClick(m) }
                                    else it
                                },
                        )
                        Text(
                            time,
                            fontSize = 11.5.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                MessageContent(m, inBubble = false)

                val hops = m.hops?.takeIf { !m.mine && !m.bridged && m.conversation == Conversation.Mesh.key }
                val myStatus = if (m.mine && (row.groupEnd || m.state == DeliveryState.FAILED)) statusLabel(m.state) else null
                if (hops != null || myStatus != null || (!row.groupStart && row.groupEnd)) {
                    val parts = buildList {
                        if (!row.groupStart) add(time)
                        myStatus?.let { add(it) }
                        hops?.let { add(hopsText(it)) }
                    }
                    if (parts.isNotEmpty()) {
                        Text(
                            parts.joinToString(" · "),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = if (m.state == DeliveryState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Private & group chat row: asymmetric grouped bubbles with lock-blue tint for outgoing messages
 * (docs/design/ui-direction.md §4, docs/design/ui-prototype.html `.msg`).
 */
@Composable
private fun EncryptedBubbleRow(
    row: TimelineRow.Message,
    onSenderClick: ((ChatMessage) -> Unit)?,
) {
    val m = row.m
    val locale = appLocale()
    val time = remember(m.timestamp, locale) { stampText(m.timestamp, locale) }
    val isGroup = m.conversation.startsWith("grp:")

    val shape = if (m.mine) {
        RoundedCornerShape(
            topStart = 18.dp,
            bottomStart = 18.dp,
            topEnd = if (row.groupStart) 18.dp else 6.dp,
            bottomEnd = if (row.groupEnd) 5.dp else 6.dp,
        )
    } else {
        RoundedCornerShape(
            topStart = if (row.groupStart) 18.dp else 6.dp,
            bottomStart = if (row.groupEnd) 5.dp else 6.dp,
            topEnd = 18.dp,
            bottomEnd = 18.dp,
        )
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(
                start = 14.dp,
                end = 14.dp,
                top = if (row.groupStart) 6.dp else 1.dp,
                bottom = if (row.groupEnd) 4.dp else 1.dp,
            ),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (isGroup && !m.mine) {
            if (row.groupStart) {
                Box(
                    Modifier
                        .padding(top = 2.dp)
                        .let { if (onSenderClick != null) it.clickable { onSenderClick(m) } else it }
                        .clearAndSetSemantics { },
                ) {
                    EmblemView(m.senderKey, 28.dp)
                }
            } else {
                Spacer(Modifier.width(28.dp))
            }
        }

        Column(
            Modifier.weight(1f),
            horizontalAlignment = if (m.mine) Alignment.End else Alignment.Start,
        ) {
            if (isGroup && !m.mine && row.groupStart) {
                val social by Social.state.collectAsState()
                Text(
                    buildAnnotatedString {
                        val label = labelText(
                            PeerLabel.of(m),
                            verified = social.isVerifiedPeer(m.senderKey),
                            vouched = social.isVouchedPeer(m.senderKey),
                        )
                        withStyle(SpanStyle(color = Emblem.color(m.senderKey))) { append(label) }
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                )
            }

            Surface(
                shape = shape,
                color = if (m.mine) OpenChatPalette.LockSoft else OpenChatPalette.Card,
                border = when {
                    m.mentionsMe -> BorderStroke(1.dp, OpenChatPalette.Signal)
                    m.mine -> BorderStroke(1.dp, OpenChatPalette.Lock.copy(alpha = 0.22f))
                    else -> BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                },
                modifier = Modifier.widthIn(max = 304.dp),
            ) {
                MessageContent(m, inBubble = true)
            }

            val failed = m.mine && m.state == DeliveryState.FAILED
            if (row.groupEnd || failed) {
                val meta = FontFamily.Monospace
                if (m.mine && m.conversation.startsWith("dm:")) {
                    val status = statusLabel(m.state)
                    val tone = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    Row(
                        Modifier.padding(top = 3.dp, end = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Text(time, fontSize = 11.sp, fontFamily = meta, color = tone)
                        Box(Modifier.clearAndSetSemantics { }) { DeliveryPathDots(m.state) }
                        Text(status, fontSize = 11.sp, fontFamily = meta, color = if (failed) tone else MaterialTheme.colorScheme.onSurface)
                        if (failed && m.attachment == null) {
                            Text(
                                stringResource(R.string.retry),
                                fontSize = 11.sp,
                                fontFamily = meta,
                                color = OpenChatPalette.Signal,
                                textDecoration = TextDecoration.Underline,
                                modifier = Modifier
                                    .clickable(role = Role.Button) { ChatRepository.controller?.retry(m.conversation, m.id) }
                                    .defaultMinSize(minHeight = 28.dp)
                                    .wrapContentHeight(Alignment.CenterVertically)
                                    .padding(horizontal = 4.dp),
                            )
                        }
                    }
                } else {
                    val hops = m.hops?.takeIf { !m.mine && !m.bridged && m.conversation.startsWith("dm:") }
                    val status = if (m.mine) statusLabel(m.state) else null
                    val parts = listOfNotNull(time, status, hops?.let { hopsText(it) })
                    Text(
                        parts.joinToString(" · "),
                        fontSize = 11.sp,
                        fontFamily = meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 3.dp, start = 2.dp, end = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun statusLabel(state: DeliveryState): String = stringResource(
    when (state) {
        DeliveryState.SENDING -> R.string.state_sending
        DeliveryState.SENT -> R.string.state_sent
        DeliveryState.DELIVERED -> R.string.state_delivered
        DeliveryState.QUEUED -> R.string.state_queued
        DeliveryState.CARRIED -> R.string.state_carried
        DeliveryState.READ -> R.string.state_read
        DeliveryState.FAILED -> R.string.state_undelivered
    },
)

@Composable
private fun MessageContent(m: ChatMessage, inBubble: Boolean) {
    when {
        m.attachment != null && m.audio -> {
            if (inBubble) VoiceNote(m.attachment)
            else Surface(
                shape = RoundedCornerShape(14.dp),
                color = OpenChatPalette.Card,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.padding(top = 2.dp),
            ) { VoiceNote(m.attachment) }
        }
        m.attachment != null -> PhotoView(m.attachment, covered = !m.mine, inBubble = inBubble)
        else -> Column {
            Text(
                withMentions(m.text),
                modifier = if (inBubble) Modifier.padding(horizontal = 13.dp, vertical = 9.dp) else Modifier,
                fontSize = 16.sp,
                lineHeight = 23.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Cashu.tokensIn(m.text).take(3).forEach { CashuCard(it, inBubble = inBubble) }
        }
    }
}

/** A Cashu token in a message: what it is worth, and a hand-off to a wallet app (§28). */
@Composable
private fun CashuCard(token: String, inBubble: Boolean) {
    val ctx = LocalContext.current
    val info = remember(token) { Cashu.decode(token) } ?: return
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = OpenChatPalette.Surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.padding(
            start = if (inBubble) 10.dp else 0.dp,
            end = if (inBubble) 10.dp else 0.dp,
            top = 6.dp,
            bottom = if (inBubble) 10.dp else 2.dp,
        ),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                (info.display ?: stringResource(R.string.pay_token)) + (info.mintHost?.let { " · $it" } ?: ""),
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
            )
            info.memo?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("cashu:$token"))
                    try { ctx.startActivity(intent) } catch (e: android.content.ActivityNotFoundException) {
                        android.widget.Toast.makeText(ctx, R.string.pay_no_wallet, android.widget.Toast.LENGTH_LONG).show()
                    }
                }) { Text(stringResource(R.string.pay_open_wallet)) }
                TextButton(onClick = {
                    val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("cashu", token))
                    android.widget.Toast.makeText(ctx, R.string.pay_copied, android.widget.Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.pay_copy)) }
            }
        }
    }
}

/**
 * A received photo starts as coarse blocks and opens on a tap (§21.4), so nobody
 * standing nearby gets an unwanted picture shown to them on the screen.
 */
@Composable
private fun PhotoView(path: String, covered: Boolean, inBubble: Boolean) {
    var open by remember(path) { mutableStateOf(!covered) }
    val decoded by produceState(thumbnails.get(path), path) {
        if (value == null) value = withContext(Dispatchers.IO) { Media.thumbnail(path)?.also { thumbnails.put(path, it) } }
    }
    val bmp = decoded ?: run {
        Icon(Icons.Outlined.Image, contentDescription = stringResource(R.string.media_photo), modifier = Modifier.padding(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val shown = remember(path, open, bmp) { (if (open) bmp else Media.pixelate(bmp)).asImageBitmap() }
    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(if (inBubble) 4.dp else 2.dp)) {
        Image(
            shown,
            contentDescription = stringResource(R.string.media_photo),
            filterQuality = if (open) FilterQuality.Medium else FilterQuality.None,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .widthIn(max = 260.dp)
                .heightIn(max = 320.dp)
                .aspectRatio(bmp.width.toFloat() / bmp.height)
                .clip(RoundedCornerShape(14.dp))
                .let { if (!open) it.clickable { open = true } else it },
        )
        if (!open) {
            Text(
                stringResource(R.string.media_tap_to_show),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                modifier = Modifier
                    .background(Color(0xB3000000), CircleShape)
                    .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
    }
}

private val thumbnails = LruCache<String, android.graphics.Bitmap>(24)
private val voiceDurations = LruCache<String, Int>(64)

/** Voice note bubble with deterministic waveform bars and live playback progress. */
@Composable
private fun VoiceNote(path: String) {
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    val duration by produceState(voiceDurations.get(path) ?: 0, path) {
        if (voiceDurations.get(path) == null) value = withContext(Dispatchers.IO) {
            try { MediaPlayer().run { setDataSource(path); prepare(); duration.also { release() } } } catch (e: Exception) { 0 }
        }.also { voiceDurations.put(path, it) }
    }
    val bars = remember(path) {
        val h = sha256(path.toByteArray(Charsets.UTF_8))
        FloatArray(20) { i -> 0.25f + ((h[i % h.size].toInt() and 0xFF) / 255f) * 0.75f }
    }
    LaunchedEffect(player) {
        val p = player ?: run { progress = 0f; return@LaunchedEffect }
        while (player === p) {
            progress = runCatching {
                val d = p.duration.coerceAtLeast(1)
                (p.currentPosition.toFloat() / d).coerceIn(0f, 1f)
            }.getOrDefault(0f)
            delay(60)
        }
    }
    DisposableEffect(path) { onDispose { player?.release() } }

    Row(
        Modifier.padding(start = 8.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val label = stringResource(if (player != null) R.string.media_stop else R.string.media_play)
        Surface(
            shape = CircleShape,
            color = OpenChatPalette.Ink,
            contentColor = OpenChatPalette.Screen,
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .clickable {
                    val p = player
                    if (p != null) {
                        p.release()
                        player = null
                    } else {
                        player = try {
                            MediaPlayer().apply {
                                setDataSource(path)
                                setOnCompletionListener { it.release(); player = null; progress = 0f }
                                prepare()
                                start()
                            }
                        } catch (e: Exception) { null }
                    }
                }
                .semantics { contentDescription = label; role = Role.Button },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    if (player != null) Icons.Outlined.Stop else Icons.Outlined.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        val activeBarColor = OpenChatPalette.Signal
        val idleBarColor = MaterialTheme.colorScheme.outline
        Canvas(Modifier.width(96.dp).height(22.dp).clearAndSetSemantics { }) {
            val step = size.width / bars.size
            val barW = (step * 0.56f).coerceAtLeast(2f)
            bars.forEachIndexed { i, amp ->
                val h = (size.height * amp).coerceAtLeast(4f)
                val filled = player != null && (i.toFloat() / bars.size) <= progress
                drawRoundRect(
                    color = if (filled) activeBarColor else idleBarColor,
                    topLeft = Offset(i * step + (step - barW) / 2f, (size.height - h) / 2f),
                    size = Size(barW, h),
                    cornerRadius = CornerRadius(barW / 2f),
                )
            }
        }
        val shownMs = if (player != null && duration > 0) (duration * progress).toInt() else duration
        Text(
            String.format(appLocale(), "%d:%02d", shownMs / 60_000, (shownMs / 1000) % 60),
            fontSize = 12.5.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Carries out a command outcome; local lines go into this chat only. */
private fun applyCommand(ctx: android.content.Context, o: Commands.Outcome, conversation: String, onSend: (String) -> Unit) {
    fun local(text: String) = ChatRepository.addLocal(conversation, text)
    when (o) {
        is Commands.Outcome.Send -> onSend(o.text)
        is Commands.Outcome.Clear -> ChatRepository.clear(conversation)
        is Commands.Outcome.OpenPrivate -> {
            o.text?.let { ChatRepository.controller?.sendPrivate(o.peerHex, it) }
            ChatRepository.requestOpen(Conversation.Private(o.peerHex).key)
        }
        is Commands.Outcome.Block -> {
            if (conversation.startsWith("geo:")) Social.blockGeo(o.person.key, o.person.label.name)
            else meshFingerprint(o.person.key)?.let { Social.block(it, o.person.label.name) }
            local(ctx.getString(R.string.cmd_blocked, o.person.label.toString()))
        }
        is Commands.Outcome.Unblock -> {
            val s = Social.state.value
            val n = o.name.lowercase()
            val mesh = s.blocked.entries.firstOrNull { (fp, name) -> name.lowercase() == n || PeerLabel.mesh(name, fp.take(16)).toString().lowercase() == n }
            val geo = s.geoBlocked.entries.firstOrNull { (pk, name) -> name.lowercase() == n || PeerLabel.nostr(name, pk).toString().lowercase() == n }
            when {
                mesh != null -> { Social.unblock(mesh.key); local(ctx.getString(R.string.cmd_unblocked, o.name)) }
                geo != null -> { Social.unblockGeo(geo.key); local(ctx.getString(R.string.cmd_unblocked, o.name)) }
                else -> local(ctx.getString(R.string.cmd_not_blocked, o.name))
            }
        }
        is Commands.Outcome.Favorite -> {
            ChatRepository.controller?.setFavorite(o.person.key, o.person.label.name, o.on)
            local(ctx.getString(if (o.on) R.string.cmd_favorited else R.string.cmd_unfavorited, o.person.label.toString()))
        }
        is Commands.Outcome.GroupCreate -> ChatRepository.controller?.createGroup(o.name)?.let { ChatRepository.requestOpen(it) }
        is Commands.Outcome.GroupList -> local(
            ChatRepository.groups.value.values.joinToString("\n") { "#${it.name} · ${it.members.size}/${org.openchat.android.core.Group.MAX_MEMBERS}" + if (it.iAmCreator) " \u2605\uFE0E" else "" }
                .ifEmpty { ctx.getString(R.string.group_none) },
        )
        is Commands.Outcome.GroupInvite, is Commands.Outcome.GroupRemove, Commands.Outcome.GroupLeave -> {
            val id = conversation.removePrefix("grp:").takeIf { conversation.startsWith("grp:") } ?: return local(ctx.getString(R.string.group_open_first))
            val c = ChatRepository.controller ?: return
            when (o) {
                is Commands.Outcome.GroupInvite -> c.groupInvite(id, o.person.key)
                is Commands.Outcome.GroupRemove -> {
                    val n = o.name.lowercase()
                    ChatRepository.groups.value[id]?.members?.firstOrNull { m ->
                        m.nickname.lowercase() == n || PeerLabel.mesh(m.nickname, m.fingerprint.take(16)).toString().lowercase() == n
                    }?.let { c.groupRemove(id, it.fingerprint) } ?: local(ctx.getString(R.string.cmd_not_found, o.name))
                }
                else -> c.groupLeave(id)
            }
        }
        is Commands.Outcome.Pay -> {
            onSend(o.token)
            local(ctx.getString(if (o.private) R.string.pay_sent_private else R.string.pay_sent_public, o.summary))
        }
        is Commands.Outcome.Ping -> {
            local(ctx.getString(R.string.cmd_ping_started, o.person.label.toString()))
            ChatRepository.controller?.ping(o.person.key, o.person.label.toString(), conversation)
        }
        is Commands.Outcome.Trace -> {
            val path = ChatRepository.controller?.estimatePath(o.person.key)
            local(
                if (path == null) ctx.getString(R.string.cmd_trace_none, o.person.label.toString())
                else ctx.getString(R.string.cmd_trace_path, (listOf(ctx.getString(R.string.cmd_trace_you)) + path + o.person.label.toString()).joinToString(" → "), path.size + 1),
            )
        }
        is Commands.Outcome.Drop -> local(ctx.getString(if (ChatRepository.controller?.dropNote(o.text) == true) R.string.note_dropped else R.string.note_no_location))
        is Commands.Outcome.Local -> local(
            when (o.note) {
                Commands.Note.HELP -> ctx.getString(R.string.cmd_help) + "\n" + ctx.getString(R.string.cmd_help_group) + "\n" + ctx.getString(R.string.cmd_help_diag) + "\n" + ctx.getString(R.string.cmd_help_pay)
                Commands.Note.WHO -> ctx.getString(R.string.cmd_who, o.arg)
                Commands.Note.WHO_NONE -> ctx.getString(R.string.cmd_who_none)
                Commands.Note.NOT_FOUND -> ctx.getString(R.string.cmd_not_found, o.arg)
                Commands.Note.USAGE -> ctx.getString(R.string.cmd_usage, o.arg)
                Commands.Note.UNKNOWN -> ctx.getString(R.string.cmd_unknown, o.arg)
                Commands.Note.MESH_ONLY -> ctx.getString(R.string.cmd_mesh_only)
                Commands.Note.NO_LOCATION -> ctx.getString(R.string.cmd_geo_only)
                Commands.Note.PAY_NOT_TOKEN -> ctx.getString(R.string.pay_not_token)
                Commands.Note.PAY_INVALID -> ctx.getString(R.string.pay_invalid)
                Commands.Note.PAY_CONFIRM_PUBLIC -> ctx.getString(R.string.pay_confirm_public)
                else -> o.arg
            },
        )
    }
}

/** Message text with every @mention set in signal orange. */
@Composable
internal fun withMentions(text: String): AnnotatedString {
    val ranges = Mentions.ranges(text)
    if (ranges.isEmpty()) return AnnotatedString(text)
    val accent = OpenChatPalette.Signal
    return buildAnnotatedString {
        var at = 0
        for (r in ranges) {
            append(text.substring(at, r.first))
            withStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)) { append(text.substring(r.first, r.last + 1)) }
            at = r.last + 1
        }
        append(text.substring(at))
    }
}

/** "HH:mm" today, otherwise the short date as well, in the app's language ([locale] from [appLocale]). */
internal fun stampText(ts: Long, locale: Locale): String {
    val clock = SimpleDateFormat("HH:mm", locale).format(Date(ts))
    val day = SimpleDateFormat("yyyyDDD", locale)
    return if (day.format(Date(ts)) == day.format(Date())) clock
    else DateFormat.getDateInstance(DateFormat.SHORT, locale).format(Date(ts)) + " " + clock
}

/** Unsent text per conversation, kept while the user switches tabs. Cleared by the panic wipe. */
internal val drafts = HashMap<String, String>()

@Composable
private fun Composer(conversation: String, placeholder: String, people: List<PeerLabel>, onSend: (String) -> Unit) {
    val ctx = LocalContext.current
    val resources = LocalResources.current
    var text by remember(conversation) { mutableStateOf(drafts[conversation].orEmpty()) }
    var focused by remember { mutableStateOf(false) }
    fun set(t: String) { text = t; if (t.isEmpty()) drafts.remove(conversation) else drafts[conversation] = t }
    val send = { val t = text.trim(); if (t.isNotEmpty()) { onSend(t); set("") } }

    val supportsMedia = !conversation.startsWith("geo:") && !conversation.startsWith("gdm:") && !conversation.startsWith("grp:")
    val recorder = remember { VoiceRecorder(ctx) }
    var recording by remember { mutableStateOf(false) }
    var recordStartedAt by remember { mutableLongStateOf(0L) }
    var viaRecorder by remember { mutableStateOf(false) }

    fun sendMedia(bytes: ByteArray?, mime: String) {
        val reason = if (bytes == null) R.string.media_too_big else ChatRepository.controller?.sendMedia(conversation, bytes, mime) ?: 0
        if (reason != 0) ChatRepository.addLocal(conversation, resources.getString(reason))
    }
    fun beginVoice(): Boolean {
        viaRecorder = ChatRepository.controller?.startVoice(conversation) != true
        val ok = if (viaRecorder) recorder.start() else true
        if (ok) recordStartedAt = System.currentTimeMillis()
        return ok
    }
    fun stopAndSendVoice() {
        recording = false
        if (viaRecorder) recorder.stop()?.let { f -> sendMedia(f.readBytes(), "audio/mp4"); f.delete() }
        else Thread { ChatRepository.controller?.finishVoice() }.start()
    }
    fun cancelVoice() {
        recording = false
        if (viaRecorder) recorder.cancel() else ChatRepository.controller?.cancelVoice()
    }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { sendMedia(Media.jpegFrom(ctx, it), "image/jpeg") }
    }
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) recording = beginVoice()
    }
    DisposableEffect(conversation) { onDispose { if (recording) cancelVoice() } }
    LaunchedEffect(recording) {
        if (recording) {
            delay(Media.MAX_VOICE_MS.toLong())
            if (recording) stopAndSendVoice()
        }
    }

    Column(Modifier.fillMaxWidth().background(OpenChatPalette.Surface)) {
        Hairline()
        // Slash-command autocomplete pills
        if (text.startsWith("/") && !text.contains(' ')) {
            val cmds = Commands.available(conversation).filter { it.startsWith(text.lowercase()) }
            if (cmds.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    cmds.forEach { c ->
                        Surface(
                            shape = CircleShape,
                            color = OpenChatPalette.Card,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.clickable { set("$c ") },
                        ) {
                            Text(c, fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                        }
                    }
                }
                Hairline()
            }
        }
        // @mention autocomplete pills
        val typing = Mentions.typing(text)
        if (typing != null) {
            val matches = people.filter { it.toString().lowercase().startsWith(typing.lowercase()) }.take(6)
            if (matches.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    matches.forEach { p ->
                        val token = if (people.count { it.name.equals(p.name, ignoreCase = true) } > 1) p.toString() else p.name
                        Surface(
                            shape = CircleShape,
                            color = OpenChatPalette.Card,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.clickable { set(text.substring(0, text.lastIndexOf('@')) + "@" + token + " ") },
                        ) {
                            Text(labelText(p), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                        }
                    }
                }
                Hairline()
            }
        }

        if (recording) {
            val now = rememberNow()
            val secs = ((now - recordStartedAt) / 1000).coerceAtLeast(0)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                IconButton(
                    onClick = { cancelVoice() },
                    modifier = Modifier.size(40.dp).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                ) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cancel), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
                Row(
                    Modifier
                        .weight(1f)
                        .background(OpenChatPalette.Screen, RoundedCornerShape(22.dp))
                        .border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f), RoundedCornerShape(22.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.size(9.dp).background(MaterialTheme.colorScheme.error, CircleShape))
                    Text(
                        String.format(appLocale(), "%d:%02d", secs / 60, secs % 60),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        stringResource(R.string.media_recording),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Surface(
                    shape = CircleShape,
                    color = OpenChatPalette.Ink,
                    contentColor = OpenChatPalette.Screen,
                    modifier = Modifier.size(40.dp).clip(CircleShape).clickable { stopAndSendVoice() },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.send), modifier = Modifier.size(18.dp))
                    }
                }
            }
            return
        }

        val encrypted = !conversation.startsWith("mesh") && !conversation.startsWith("geo:")
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (supportsMedia && text.isEmpty()) {
                IconButton(
                    onClick = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    modifier = Modifier.size(40.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.media_photo), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
            }

            Surface(
                shape = RoundedCornerShape(22.dp),
                color = OpenChatPalette.Screen,
                border = BorderStroke(
                    1.dp,
                    when {
                        focused && encrypted -> OpenChatPalette.Lock.copy(alpha = 0.7f)
                        focused -> MaterialTheme.colorScheme.outline
                        else -> MaterialTheme.colorScheme.outlineVariant
                    },
                ),
                modifier = Modifier.weight(1f).defaultMinSize(minHeight = 40.dp),
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        if (encrypted) Icons.Outlined.Lock else Icons.Outlined.Sensors,
                        contentDescription = stringResource(if (encrypted) R.string.chip_encrypted else R.string.chip_public),
                        tint = if (encrypted) OpenChatPalette.Lock else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    BasicTextField(
                        value = text,
                        onValueChange = { if (it.length <= 1000) set(it) },
                        modifier = Modifier.weight(1f).onFocusChanged { focused = it.isFocused },
                        textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, lineHeight = 22.sp),
                        cursorBrush = SolidColor(OpenChatPalette.Ink),
                        maxLines = 5,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { send() }),
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (text.isEmpty()) {
                                    Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp, maxLines = 1)
                                }
                                inner()
                            }
                        },
                    )
                }
            }

            val hasText = text.isNotBlank()
            AnimatedContent(
                targetState = hasText || !supportsMedia,
                transitionSpec = { (fadeIn(tween(120)) + scaleIn(tween(120), initialScale = 0.85f)) togetherWith (fadeOut(tween(90)) + scaleOut(tween(90), targetScale = 0.85f)) },
                label = "composer-action",
            ) { showSend ->
                if (showSend) {
                    val active = text.isNotBlank()
                    Surface(
                        shape = CircleShape,
                        color = if (active) OpenChatPalette.Ink else OpenChatPalette.Card,
                        contentColor = if (active) OpenChatPalette.Screen else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .let { if (active) it.clickable(onClick = send) else it }
                            .semantics {
                                contentDescription = resources.getString(R.string.send)
                                role = Role.Button
                            },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    }
                } else {
                    val hint = stringResource(R.string.media_mic_hint)
                    fun toggleVoice() {
                        val granted = androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        if (granted) recording = beginVoice() else micPerm.launch(android.Manifest.permission.RECORD_AUDIO)
                    }
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(OpenChatPalette.Card)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                            .clearAndSetSemantics {
                                contentDescription = hint
                                role = Role.Button
                                onClick { toggleVoice(); true }
                            }
                            .pointerInput(conversation) {
                                detectTapGestures(onPress = {
                                    val granted = androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                    if (!granted) { micPerm.launch(android.Manifest.permission.RECORD_AUDIO); return@detectTapGestures }
                                    recording = beginVoice()
                                    if (!recording) return@detectTapGestures
                                    val pressedAt = System.currentTimeMillis()
                                    if (!tryAwaitRelease()) cancelVoice()
                                    else if (System.currentTimeMillis() - pressedAt >= 400) stopAndSendVoice()
                                })
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}
