package org.openchat.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openchat.android.R
import org.openchat.android.app.BoardItem
import org.openchat.android.app.ChatRepository
import org.openchat.android.app.PeerLabel
import org.openchat.android.core.Board

/** Pseudo conversation key for the notice board screen. */
const val BOARD = "board"

/**
 * The mesh notice board (docs/protocol-spec.md §23): signed posts that stay up to a week
 * and travel phone to phone, so people arriving later still read them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardScreen(onBack: () -> Unit, onPanic: () -> Unit) {
    val posts by ChatRepository.board.collectAsState()
    var composing by rememberSaveable { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    DisposableEffect(Unit) {
        ChatRepository.clearBoardUnseen()
        onDispose { ChatRepository.clearBoardUnseen() }
    }
    if (composing) {
        BoardComposerSheet(onDismiss = { composing = false })
    }
    Scaffold(
        containerColor = OpenChatPalette.Screen,
        topBar = {
            Column {
                TopAppBar(
                    colors = chromeTopBarColors(),
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                    title = {
                        Column {
                            PanicTitle(stringResource(R.string.board_title), onPanic)
                            Text(
                                stringResource(R.string.board_subtitle),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    actions = { AudiencePill(encrypted = false, modifier = Modifier.padding(end = 12.dp)) },
                )
                Hairline()
            }
        },
        floatingActionButton = {
            if (posts.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { composing = true },
                    containerColor = OpenChatPalette.Ink,
                    contentColor = OpenChatPalette.Screen,
                    icon = { Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    text = { Text(stringResource(R.string.board_post), fontWeight = FontWeight.SemiBold) },
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (posts.isEmpty()) {
                Column(
                    Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = OpenChatPalette.Surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.size(52.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.PushPin, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                        }
                    }
                    Text(
                        stringResource(R.string.board_empty),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.5.sp,
                        lineHeight = 22.sp,
                    )
                    Button(onClick = { composing = true }) {
                        Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.board_post))
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(posts, key = { it.idHex }) { BoardCard(it) }
                }
            }
        }
    }
}

@Composable
private fun BoardCard(p: BoardItem) {
    val peers by ChatRepository.peers.collectAsState()
    val author = peers.firstOrNull { it.signingPublicKey.toHexString() == p.authorKeyHex }
    val label = if (author != null) PeerLabel.mesh(author.nickname, author.peerIdHex) else PeerLabel(p.nickname.ifBlank { "anon" }, p.authorKeyHex.take(4).let { if (it.isEmpty()) "" else "#$it" })
    val daysLeft = ((p.expiresAt - System.currentTimeMillis()) / 86_400_000L).toInt()
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (p.urgent) OpenChatPalette.SignalSoft.copy(alpha = 0.55f) else OpenChatPalette.Card,
        border = BorderStroke(1.dp, if (p.urgent) OpenChatPalette.Signal else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                EmblemView(author?.peerIdHex ?: p.authorKeyHex.ifEmpty { p.nickname }, 28.dp)
                Text(labelText(label), fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (p.urgent) {
                    Surface(
                        shape = CircleShape,
                        color = OpenChatPalette.Signal,
                        contentColor = OpenChatPalette.OnSignal,
                    ) {
                        Text(
                            stringResource(R.string.board_urgent),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
                if (p.geohash.isNotEmpty()) {
                    Text("#" + p.geohash, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(p.content, fontSize = 16.sp, lineHeight = 23.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stampText(p.createdAt, appLocale()) + " · " + if (daysLeft >= 1) plural(R.plurals.board_days_left, daysLeft) else stringResource(R.string.board_ends_today),
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (p.mine) {
                    TextButton(
                        onClick = { ChatRepository.controller?.deleteBoardPost(p.idHex) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(stringResource(R.string.board_delete), fontSize = 12.5.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BoardComposerSheet(onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var urgent by rememberSaveable { mutableStateOf(false) }
    var days by rememberSaveable { mutableIntStateOf(3) }
    val fits = text.trim().toByteArray(Charsets.UTF_8).size in 1..Board.MAX_CONTENT

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = OpenChatPalette.Surface,
        contentColor = OpenChatPalette.Ink,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.board_post), fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                AudiencePill(encrypted = false)
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(600) },
                placeholder = { Text(stringResource(R.string.board_placeholder)) },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(132.dp),
                maxLines = 6,
            )
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = urgent,
                    onClick = { urgent = !urgent },
                    label = { Text(stringResource(R.string.board_urgent_check)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = OpenChatPalette.Signal,
                        selectedLabelColor = OpenChatPalette.OnSignal,
                    ),
                )
                Spacer(Modifier.weight(1f))
                listOf(1, 3, 7).forEach { d ->
                    FilterChip(
                        selected = days == d,
                        onClick = { days = d },
                        label = { Text(plural(R.plurals.board_days, d)) },
                    )
                }
            }
            Button(
                enabled = fits,
                onClick = {
                    if (ChatRepository.controller?.postToBoard(text, urgent, days) == true) {
                        text = ""
                        urgent = false
                        onDismiss()
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).height(48.dp),
            ) {
                Text(stringResource(R.string.board_post), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private fun ByteArray.toHexString() = joinToString("") { "%02x".format(it) }
