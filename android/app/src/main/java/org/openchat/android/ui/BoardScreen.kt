package org.openchat.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
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
    BoxWithConstraints(Modifier.fillMaxSize().openChatCanvas()) {
        val compactBar = maxWidth < 360.dp
        Scaffold(
            containerColor = Color.Transparent,
            contentColor = OpenChatPalette.Ink,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                TopAppBar(
                    colors = chromeTopBarColors(),
                    navigationIcon = { GlassBackButton(onBack) },
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
                    actions = { AudiencePill(encrypted = false, compact = compactBar, modifier = Modifier.padding(end = 12.dp)) },
                )
            },
            floatingActionButton = {
                if (posts.isNotEmpty()) {
                    ExtendedFloatingActionButton(
                        onClick = { composing = true },
                        containerColor = OpenChatPalette.Signal,
                        contentColor = Color.White,
                        icon = { Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        text = { Text(stringResource(R.string.board_post), fontWeight = FontWeight.SemiBold) },
                    )
                }
            },
        ) { padding ->
            val topClearance = padding.calculateTopPadding()
            Box(Modifier.fillMaxSize().openChatCanvas()) {
                if (posts.isEmpty()) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(start = 28.dp, end = 28.dp, top = 20.dp + topClearance, bottom = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = OpenChatPalette.SignalSoft,
                            modifier = Modifier.size(52.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.PushPin, contentDescription = null, tint = OpenChatPalette.Signal, modifier = Modifier.size(24.dp))
                            }
                        }
                        Text(
                            stringResource(R.string.board_empty),
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.5.sp,
                            lineHeight = 22.sp,
                        )
                        Button(onClick = { composing = true }, shape = RoundedCornerShape(12.dp)) {
                            Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.board_post))
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp + topClearance, bottom = 88.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(posts, key = { it.idHex }) { BoardCard(it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun BoardCard(p: BoardItem) {
    val peers by ChatRepository.peers.collectAsState()
    val inlineIcons = rememberLabelInlineIcons()
    val author = peers.firstOrNull { it.signingPublicKey.toHexString() == p.authorKeyHex }
    val label = if (author != null) PeerLabel.mesh(author.nickname, author.peerIdHex) else PeerLabel(p.nickname.ifBlank { "anon" }, p.authorKeyHex.take(4).let { if (it.isEmpty()) "" else "#$it" })
    val daysLeft = ((p.expiresAt - System.currentTimeMillis()) / 86_400_000L).toInt()
    val isLight = OpenChatPalette.isLight
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = when {
            p.urgent && isLight -> Color(0xFFFFEFE6)
            p.urgent -> Color(0xFF28140B)
            else -> OpenChatPalette.CardSurface
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                EmblemView(author?.peerIdHex ?: p.authorKeyHex.ifEmpty { p.nickname }, 28.dp)
                Text(labelText(label), inlineContent = inlineIcons, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
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
                    Text("#" + p.geohash, fontSize = 12.sp, style = TabularMonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(p.content, fontSize = 16.sp, lineHeight = 23.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stampText(p.createdAt, appLocale()) + " · " + if (daysLeft >= 1) plural(R.plurals.board_days_left, daysLeft) else stringResource(R.string.board_ends_today),
                    fontSize = 11.5.sp,
                    style = TabularMonoStyle,
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
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val submit = {
        if (ChatRepository.controller?.postToBoard(text, urgent, days) == true) {
            text = ""
            urgent = false
            onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = OpenChatPalette.Surface,
        contentColor = OpenChatPalette.Ink,
    ) {
        CompositionLocalProvider(LocalOnElevatedSurface provides true) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            val compactHeight = maxHeight < 340.dp
            val ultraShortHeight = maxHeight < 250.dp
            val narrowWidth = maxWidth < 380.dp
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = if (compactHeight) 4.dp else 8.dp),
                verticalArrangement = Arrangement.spacedBy(if (compactHeight) 8.dp else 12.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(R.string.board_post),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    AudiencePill(encrypted = false, compact = narrowWidth || ultraShortHeight)
                    if (ultraShortHeight) {
                        Button(
                            enabled = fits,
                            onClick = submit,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        ) {
                            Text(stringResource(R.string.board_post), fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                    }
                }

                Column(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(if (compactHeight) 8.dp else 12.dp),
                ) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it.take(600) },
                        placeholder = { Text(stringResource(R.string.board_placeholder)) },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = if (compactHeight) 72.dp else 96.dp, max = 136.dp),
                        minLines = if (compactHeight) 2 else 3,
                        maxLines = 5,
                    )
                    if (narrowWidth) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(
                                selected = urgent,
                                onClick = { urgent = !urgent },
                                label = { Text(stringResource(R.string.board_urgent_check), maxLines = 1) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = OpenChatPalette.Signal,
                                    selectedLabelColor = OpenChatPalette.OnSignal,
                                ),
                            )
                            listOf(1, 3, 7).forEach { d ->
                                FilterChip(
                                    selected = days == d,
                                    onClick = { days = d },
                                    label = { Text(plural(R.plurals.board_days, d), maxLines = 1) },
                                )
                            }
                        }
                    } else {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(
                                selected = urgent,
                                onClick = { urgent = !urgent },
                                label = { Text(stringResource(R.string.board_urgent_check), maxLines = 1) },
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
                                    label = { Text(plural(R.plurals.board_days, d), maxLines = 1) },
                                )
                            }
                        }
                    }
                }

                if (!ultraShortHeight) {
                    Button(
                        enabled = fits,
                        onClick = submit,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = if (compactHeight) 6.dp else 12.dp)
                            .height(if (compactHeight) 44.dp else 48.dp),
                    ) {
                        Text(stringResource(R.string.board_post), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        }
    }
}

private fun ByteArray.toHexString() = joinToString("") { "%02x".format(it) }
