package org.openchat.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
    BackHandler(onBack = onBack)
    DisposableEffect(Unit) {
        ChatRepository.clearBoardUnseen()
        onDispose { ChatRepository.clearBoardUnseen() }
    }
    Scaffold(topBar = {
        TopAppBar(
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            title = {
                Column {
                    PanicTitle(stringResource(R.string.board_title), onPanic)
                    Text(stringResource(R.string.board_subtitle), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
        )
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (posts.isEmpty()) {
                    Text(stringResource(R.string.board_empty), Modifier.align(Alignment.Center).padding(32.dp), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 22.sp)
                } else {
                    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(posts, key = { it.idHex }) { BoardCard(it) }
                    }
                }
            }
            BoardComposer()
        }
    }
}

@Composable
private fun BoardCard(p: BoardItem) {
    val peers by ChatRepository.peers.collectAsState()
    val author = peers.firstOrNull { it.signingPublicKey.toHexString() == p.authorKeyHex }
    val label = if (author != null) PeerLabel.mesh(author.nickname, author.peerIdHex) else PeerLabel(p.nickname.ifBlank { "anon" }, "")
    val ctx = LocalContext.current
    val daysLeft = ((p.expiresAt - System.currentTimeMillis()) / 86_400_000L).toInt()
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = if (p.urgent) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (p.urgent) Text(stringResource(R.string.board_urgent) + "  ", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text(labelText(label), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (p.geohash.isNotEmpty()) Text("#" + p.geohash + "  ", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(p.content, fontSize = 16.sp, lineHeight = 22.sp, modifier = Modifier.padding(vertical = 6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stampText(p.createdAt) + " · " + if (daysLeft >= 1) ctx.resources.getQuantityString(R.plurals.board_days_left, daysLeft, daysLeft) else ctx.getString(R.string.board_ends_today),
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                )
                if (p.mine) TextButton(onClick = { ChatRepository.controller?.deleteBoardPost(p.idHex) }) { Text(stringResource(R.string.board_delete)) }
            }
        }
    }
}

@Composable
private fun BoardComposer() {
    var text by rememberSaveable { mutableStateOf("") }
    var urgent by rememberSaveable { mutableStateOf(false) }
    var days by rememberSaveable { mutableIntStateOf(3) }
    val fits = text.trim().toByteArray(Charsets.UTF_8).size in 1..Board.MAX_CONTENT
    HorizontalDivider()
    Column(Modifier.fillMaxWidth().padding(8.dp)) {
        OutlinedTextField(text, { text = it.take(600) }, placeholder = { Text(stringResource(R.string.board_placeholder)) }, modifier = Modifier.fillMaxWidth(), maxLines = 5)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Checkbox(checked = urgent, onCheckedChange = { urgent = it })
            Text(stringResource(R.string.board_urgent_check), fontSize = 13.sp, modifier = Modifier.weight(1f))
            listOf(1, 3, 7).forEach { d ->
                FilterChip(selected = days == d, onClick = { days = d }, label = { Text(LocalContext.current.resources.getQuantityString(R.plurals.board_days, d, d)) })
            }
        }
        Button(enabled = fits, onClick = { if (ChatRepository.controller?.postToBoard(text, urgent, days) == true) { text = ""; urgent = false } }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.board_post))
        }
    }
}

private fun ByteArray.toHexString() = joinToString("") { "%02x".format(it) }
