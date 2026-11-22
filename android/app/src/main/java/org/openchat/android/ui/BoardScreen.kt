package org.openchat.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
    val geo by ChatRepository.geo.collectAsState()
    var composing by rememberSaveable { mutableStateOf(false) }
    var filterMode by rememberSaveable { mutableIntStateOf(0) } // 0 = all, 1 = mesh, 2 = regional
    val activeRegionCell = geo.locationGeohash?.take(4) ?: geo.joined.sorted().firstOrNull()?.take(4)
    val hasRegionalPosts = posts.any { it.geohash.isNotEmpty() }
    val filteredPosts = when (filterMode) {
        1 -> posts.filter { it.geohash.isEmpty() }
        2 -> posts.filter { it.geohash.isNotEmpty() }
        else -> posts
    }
    BackHandler(onBack = onBack)
    DisposableEffect(Unit) {
        ChatRepository.clearBoardUnseen()
        onDispose { ChatRepository.clearBoardUnseen() }
    }
    if (composing) {
        BoardComposerSheet(activeRegionCell = activeRegionCell, onDismiss = { composing = false })
    }
    FluidGlassHost {
        Box(Modifier.fillMaxSize().openChatCanvas()) {
            Scaffold(
                containerColor = Color.Transparent,
                contentColor = OpenChatPalette.Ink,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = {
                    ProgressiveBlurTopBar {
                        Column {
                            TopAppBar(
                                colors = chromeTopBarColors(),
                                navigationIcon = { GlassBackButton(onBack) },
                                title = {
                                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                        PanicTitle(stringResource(R.string.board_title), onPanic)
                                        Text(
                                            stringResource(R.string.board_subtitle),
                                            fontSize = 11.5.sp,
                                            lineHeight = 15.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                },
                                actions = {
                                    Surface(
                                        shape = CircleShape,
                                        color = OpenChatPalette.PrimaryAction,
                                        tonalElevation = 0.dp,
                                        shadowElevation = 0.dp,
                                        modifier = Modifier
                                            .padding(end = 14.dp)
                                            .pressScale(0.95f)
                                            .clip(CircleShape)
                                            .clickable(role = Role.Button) { composing = true },
                                    ) {
                                        Row(
                                            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                                        ) {
                                            Icon(
                                                Icons.Outlined.Edit,
                                                contentDescription = null,
                                                tint = OpenChatPalette.OnPrimaryAction,
                                                modifier = Modifier.size(14.dp),
                                            )
                                            Text(
                                                stringResource(R.string.radar_board_cta),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = OpenChatPalette.OnPrimaryAction,
                                                maxLines = 1,
                                            )
                                        }
                                    }
                                },
                            )
                            if (hasRegionalPosts || activeRegionCell != null) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = OpenChatPalette.Well,
                                    tonalElevation = 0.dp,
                                    shadowElevation = 0.dp,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 6.dp),
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(3.dp),
                                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                                    ) {
                                        listOf(
                                            0 to stringResource(R.string.board_filter_all),
                                            1 to stringResource(R.string.board_filter_mesh),
                                            2 to (activeRegionCell?.let { "#$it" } ?: stringResource(R.string.board_filter_region)),
                                        ).forEach { (mode, label) ->
                                            val active = filterMode == mode
                                            Box(
                                                Modifier
                                                    .weight(1f)
                                                    .clip(RoundedCornerShape(9.dp))
                                                    .background(if (active) OpenChatPalette.Surface else Color.Transparent)
                                                    .clickable(role = Role.Tab) { filterMode = mode }
                                                    .padding(vertical = 6.dp, horizontal = 6.dp),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Text(
                                                    label,
                                                    fontSize = 12.sp,
                                                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                                                    color = if (active) OpenChatPalette.Ink else OpenChatPalette.Muted,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
            ) { padding ->
                val topClearance = padding.calculateTopPadding()
                FluidGlassSource {
                    Box(Modifier.fillMaxSize().openChatCanvas().fluidGlassSource()) {
                        if (filteredPosts.isEmpty()) {
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
                                    color = OpenChatPalette.Card,
                                    modifier = Modifier.size(52.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Outlined.PushPin, contentDescription = null, tint = OpenChatPalette.Ink, modifier = Modifier.size(22.dp))
                                    }
                                }
                                Text(
                                    stringResource(R.string.board_empty),
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 14.5.sp,
                                    lineHeight = 22.sp,
                                )
                                TonalActionButton(onClick = { composing = true }) {
                                    Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.board_post))
                                }
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp + topClearance, bottom = 48.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                items(filteredPosts, key = { it.idHex }) { BoardCard(it) }
                            }
                        }
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
private fun BoardComposerSheet(activeRegionCell: String?, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var urgent by rememberSaveable { mutableStateOf(false) }
    var days by rememberSaveable { mutableIntStateOf(3) }
    var useRegion by rememberSaveable { mutableStateOf(false) }
    val targetGeohash = if (useRegion && activeRegionCell != null) activeRegionCell else ""
    val byteCount = text.trim().toByteArray(Charsets.UTF_8).size
    val overLimit = byteCount > Board.MAX_CONTENT
    val fits = byteCount in 1..Board.MAX_CONTENT
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val submit = {
        if (ChatRepository.controller?.postToBoard(text, urgent, days, targetGeohash) == true) {
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
            val narrowWidth = maxWidth < 380.dp
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = if (compactHeight) 4.dp else 8.dp),
                verticalArrangement = Arrangement.spacedBy(if (compactHeight) 8.dp else 12.dp),
            ) {
                // Pinned header row: publish CTA stays permanently reachable above any soft keyboard
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        stringResource(R.string.board_title),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        enabled = fits,
                        onClick = submit,
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (urgent) OpenChatPalette.Signal else OpenChatPalette.PrimaryAction,
                            contentColor = if (urgent) OpenChatPalette.OnSignal else OpenChatPalette.OnPrimaryAction,
                        ),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(
                            stringResource(R.string.board_post),
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    }
                }

                Column(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = if (compactHeight) 6.dp else 14.dp),
                    verticalArrangement = Arrangement.spacedBy(if (compactHeight) 8.dp else 12.dp),
                ) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it.take(600) },
                        placeholder = { Text(stringResource(R.string.board_placeholder)) },
                        isError = overLimit,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = if (compactHeight) 74.dp else 100.dp, max = 148.dp),
                        minLines = if (compactHeight) 2 else 3,
                        maxLines = 5,
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        AudiencePill(encrypted = false, compact = narrowWidth)
                        Text(
                            "$byteCount / ${Board.MAX_CONTENT}B",
                            fontSize = 11.5.sp,
                            style = TabularMonoStyle,
                            fontWeight = if (overLimit) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (overLimit) MaterialTheme.colorScheme.error else OpenChatPalette.Muted,
                        )
                    }
                    if (activeRegionCell != null) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(
                                selected = !useRegion,
                                onClick = { useRegion = false },
                                label = { Text(stringResource(R.string.board_filter_mesh), maxLines = 1) },
                            )
                            FilterChip(
                                selected = useRegion,
                                onClick = { useRegion = true },
                                label = { Text(stringResource(R.string.board_scope_region, activeRegionCell), maxLines = 1) },
                            )
                        }
                    }
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
            }
        }
        }
    }
}

private fun ByteArray.toHexString() = joinToString("") { "%02x".format(it) }
