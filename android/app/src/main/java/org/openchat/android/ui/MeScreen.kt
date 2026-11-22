package org.openchat.android.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.Casino
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openchat.android.R
import org.openchat.android.app.AppLanguage
import org.openchat.android.app.AppTheme
import org.openchat.android.app.AppUpdater
import org.openchat.android.app.ChatRepository
import org.openchat.android.app.PeerLabel
import org.openchat.android.app.RandomNicknames

@Composable
internal fun MePane(
    onShareApp: () -> Unit,
    onLanguage: (AppLanguage.Choice) -> Unit,
    onScan: () -> Unit,
    onPanic: () -> Unit,
    onOpenGuide: (() -> Unit)? = null,
    topClearance: Dp = 0.dp,
) {
    val ctx = LocalContext.current
    val mesh by ChatRepository.mesh.collectAsState()
    val geo by ChatRepository.geo.collectAsState()
    val bridge by ChatRepository.bridge.collectAsState()
    val updateState by AppUpdater.state.collectAsState()
    var nick by rememberSaveable(mesh.myNickname) { mutableStateOf(mesh.myNickname) }
    var editingName by rememberSaveable { mutableStateOf(false) }
    var showQr by remember { mutableStateOf(false) }
    var showVerifyDrawer by rememberSaveable { mutableStateOf(false) }
    var showFingerprint by rememberSaveable { mutableStateOf(false) }
    var showMorePrefs by rememberSaveable { mutableStateOf(false) }
    var confirmClearChats by remember { mutableStateOf(false) }
    var confirmWipeKeys by remember { mutableStateOf(false) }
    val bottomClearance = LocalBottomBarClearance.current
    val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val haptic = LocalHapticFeedback.current
    val currentLocale = appLocale()
    val isLight = OpenChatPalette.isLight

    if (showQr) MyQrDialog { showQr = false }
    if (confirmClearChats) {
        AlertDialog(
            onDismissRequest = { confirmClearChats = false },
            containerColor = OpenChatPalette.Surface,
            title = { Text(stringResource(R.string.me_clear_chats_title)) },
            text = { Text(stringResource(R.string.me_clear_chats_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearChats = false
                    ChatRepository.clearAllMessages()
                    drafts.clear()
                    android.widget.Toast.makeText(ctx, R.string.me_clear_chats_done, android.widget.Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.me_clear_chats_cta), fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = { TextButton(onClick = { confirmClearChats = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (confirmWipeKeys) {
        AlertDialog(
            onDismissRequest = { confirmWipeKeys = false },
            containerColor = OpenChatPalette.Surface,
            title = { Text(stringResource(R.string.me_wipe_title)) },
            text = { Text(stringResource(R.string.me_wipe_confirm)) },
            confirmButton = {
                TextButton(onClick = { confirmWipeKeys = false; onPanic() }) {
                    Text(stringResource(R.string.me_wipe_do), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = { TextButton(onClick = { confirmWipeKeys = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    val shortFp = remember(mesh.fingerprint) {
        mesh.fingerprint.replace(" ", "").uppercase().take(16).chunked(4).joinToString(" ")
    }

    FluidGlassSource {
        Column(
            Modifier
                .fillMaxSize()
                .openChatCanvas()
                .fluidGlassSource()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp + topClearance, bottom = 18.dp + maxOf(bottomClearance, imeBottom)),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // CARD 1: Public-Key Identity Passport (Braun-instrument dense card)
            SettingsGroupCard {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        EmblemView(mesh.myPeerHex.ifEmpty { "self" }, 46.dp)
                        Column(
                            Modifier
                                .weight(1f)
                                .clickable { nick = mesh.myNickname; editingName = !editingName },
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Text(
                                    labelText(PeerLabel.mesh(mesh.myNickname, mesh.myPeerHex)),
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                Icon(
                                    Icons.Outlined.Edit,
                                    contentDescription = stringResource(R.string.me_name),
                                    tint = OpenChatPalette.Muted,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                            Text(
                                stringResource(R.string.me_suffix_note),
                                fontSize = 11.5.sp,
                                lineHeight = 16.sp,
                                color = OpenChatPalette.Muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Surface(
                            shape = CircleShape,
                            color = OpenChatPalette.Well,
                            tonalElevation = 0.dp,
                            shadowElevation = 0.dp,
                            modifier = Modifier
                                .pressScale(0.95f)
                                .clip(CircleShape)
                                .clickable(role = Role.Button) { showVerifyDrawer = !showVerifyDrawer },
                        ) {
                            Text(
                                stringResource(R.string.me_verify_pill),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = OpenChatPalette.Ink,
                                maxLines = 1,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    }

                    // Inline nickname editor
                    AnimatedVisibility(visible = editingName) {
                        val rollRandom = {
                            nick = RandomNicknames.next(nick, currentLocale)
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = nick,
                                onValueChange = { nick = it.take(32) },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                trailingIcon = {
                                    IconButton(onClick = rollRandom) {
                                        Icon(
                                            Icons.Outlined.Shuffle,
                                            contentDescription = stringResource(R.string.me_name_random),
                                            tint = OpenChatPalette.Signal,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            )
                            Button(
                                enabled = nick.isNotBlank(),
                                shape = RoundedCornerShape(12.dp),
                                onClick = {
                                    if (nick != mesh.myNickname) ChatRepository.controller?.setNickname(nick)
                                    editingName = false
                                },
                            ) { Text(stringResource(R.string.save)) }
                        }
                    }

                    // Inline QR verify drawer (show QR + scan QR)
                    AnimatedVisibility(visible = showVerifyDrawer) {
                        VerifySection(onShowQr = { showQr = true }, onScan = onScan)
                    }

                    // Compact inset security fingerprint strip (tap to expand/copy full grid)
                    if (shortFp.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = OpenChatPalette.Well,
                            tonalElevation = 0.dp,
                            shadowElevation = 0.dp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { showFingerprint = !showFingerprint },
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    stringResource(R.string.me_fp_title),
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = OpenChatPalette.Muted,
                                )
                                Text(
                                    shortFp,
                                    style = TabularMonoStyle,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = OpenChatPalette.Ink,
                                    maxLines = 1,
                                )
                            }
                        }
                        AnimatedVisibility(visible = showFingerprint) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(stringResource(R.string.me_fp_body), fontSize = 12.sp, lineHeight = 17.sp, color = OpenChatPalette.Muted)
                                FingerprintGrid(
                                    mesh.fingerprint,
                                    modifier = Modifier.clickable {
                                        val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                                        cm.setPrimaryClip(android.content.ClipData.newPlainText("fingerprint", mesh.fingerprint))
                                    },
                                )
                            }
                        }
                    }
                }
            }

            // CARD 2: Network Routing (Tor / Bridge) + Highlighted Inset Offline APK Share Well + Theme
            SettingsGroupCard {
                // 2a. Compact Tor row + stalled recovery & optional local upstream proxy
                val torOn = geo.tor != org.openchat.android.app.TorState.OFF
                val torStalled = geo.tor == org.openchat.android.app.TorState.STARTING && geo.torStalled
                var showProxyEditor by rememberSaveable { mutableStateOf(false) }
                var proxyDraft by rememberSaveable(geo.torProxy) { mutableStateOf(geo.torProxy) }
                var proxyBad by remember { mutableStateOf(false) }
                val torStatus = when {
                    geo.tor == org.openchat.android.app.TorState.ON -> stringResource(R.string.tor_on)
                    geo.tor == org.openchat.android.app.TorState.OFF -> stringResource(R.string.tor_off)
                    torStalled -> stringResource(R.string.tor_stalled_title)
                    geo.torProgress > 0 -> stringResource(R.string.tor_starting_pct, geo.torProgress)
                    else -> stringResource(R.string.tor_starting)
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(stringResource(R.string.tor_title), fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Box(
                                    Modifier
                                        .size(5.5.dp)
                                        .background(
                                            when {
                                                !torOn -> MaterialTheme.colorScheme.error
                                                else -> OpenChatPalette.Signal
                                            },
                                            CircleShape,
                                        ),
                                )
                                Text(
                                    torStatus,
                                    fontSize = 12.sp,
                                    fontWeight = if (torStalled) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (torStalled) OpenChatPalette.Signal else OpenChatPalette.Muted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Switch(checked = torOn, onCheckedChange = { ChatRepository.controller?.setTor(it) })
                    }

                    if (torOn && (torStalled || geo.torProxy.isNotEmpty() || showProxyEditor)) {
                        if (torStalled) {
                            Text(
                                stringResource(R.string.tor_stalled_body),
                                fontSize = 11.5.sp,
                                lineHeight = 16.5.sp,
                                color = OpenChatPalette.Muted,
                            )
                            if (geo.torDetail.isNotEmpty()) {
                                Text(
                                    geo.torDetail,
                                    style = TabularMonoStyle,
                                    fontSize = 11.sp,
                                    color = OpenChatPalette.Muted,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (torStalled) {
                                Surface(
                                    shape = CircleShape,
                                    color = OpenChatPalette.Well,
                                    modifier = Modifier
                                        .pressScale(0.95f)
                                        .clip(CircleShape)
                                        .clickable(role = Role.Button) { ChatRepository.controller?.retryTor() },
                                ) {
                                    Text(
                                        stringResource(R.string.retry),
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = OpenChatPalette.Ink,
                                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
                                    )
                                }
                            }
                            Surface(
                                shape = CircleShape,
                                color = if (showProxyEditor) OpenChatPalette.PrimaryAction else OpenChatPalette.Well,
                                modifier = Modifier
                                    .pressScale(0.95f)
                                    .clip(CircleShape)
                                    .clickable(role = Role.Button) {
                                        showProxyEditor = !showProxyEditor
                                        proxyBad = false
                                    },
                            ) {
                                Text(
                                    if (geo.torProxy.isNotEmpty() && !showProxyEditor) "${stringResource(R.string.tor_proxy_btn)} · ${geo.torProxy}"
                                    else stringResource(R.string.tor_proxy_btn),
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (showProxyEditor) OpenChatPalette.OnPrimaryAction else OpenChatPalette.Ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
                                )
                            }
                            Surface(
                                shape = CircleShape,
                                color = OpenChatPalette.Well,
                                modifier = Modifier
                                    .pressScale(0.95f)
                                    .clip(CircleShape)
                                    .clickable(role = Role.Button) { org.openchat.android.app.DiagLog.shareReport(ctx) },
                            ) {
                                Text(
                                    stringResource(R.string.diag_share_btn),
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = OpenChatPalette.Ink,
                                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
                                )
                            }
                            if (torStalled) {
                                Surface(
                                    shape = CircleShape,
                                    color = OpenChatPalette.PrimaryAction,
                                    modifier = Modifier
                                        .pressScale(0.95f)
                                        .clip(CircleShape)
                                        .clickable(role = Role.Button) { ChatRepository.controller?.setTor(false) },
                                ) {
                                    Text(
                                        stringResource(R.string.tor_use_direct),
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = OpenChatPalette.OnPrimaryAction,
                                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
                                    )
                                }
                            }
                        }
                    } else if (torOn && geo.tor == org.openchat.android.app.TorState.STARTING) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = OpenChatPalette.Well,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .clickable(role = Role.Button) { showProxyEditor = true },
                            ) {
                                Text(
                                    stringResource(R.string.tor_proxy_btn),
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = OpenChatPalette.Muted,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                )
                            }
                            Surface(
                                shape = CircleShape,
                                color = OpenChatPalette.Well,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .clickable(role = Role.Button) { org.openchat.android.app.DiagLog.shareReport(ctx) },
                            ) {
                                Text(
                                    stringResource(R.string.diag_share_btn),
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = OpenChatPalette.Muted,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }

                    AnimatedVisibility(visible = torOn && showProxyEditor) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedTextField(
                                    value = proxyDraft,
                                    onValueChange = { proxyDraft = it.take(120); proxyBad = false },
                                    placeholder = { Text(stringResource(R.string.tor_proxy_hint), fontSize = 12.sp) },
                                    singleLine = true,
                                    isError = proxyBad,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.weight(1f),
                                )
                                Button(
                                    shape = RoundedCornerShape(12.dp),
                                    onClick = {
                                        val cleaned = proxyDraft.trim()
                                        if (cleaned.isNotEmpty() && org.openchat.android.app.parseManualProxySpec(cleaned) == null) {
                                            proxyBad = true
                                        } else {
                                            ChatRepository.controller?.setTorProxy(cleaned)
                                            showProxyEditor = false
                                        }
                                    },
                                ) {
                                    Text(
                                        stringResource(if (proxyDraft.isBlank()) R.string.tor_proxy_clear else R.string.save),
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                            if (proxyBad) {
                                Text(
                                    stringResource(R.string.tor_proxy_invalid),
                                    fontSize = 11.5.sp,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }

                Hairline(Modifier.padding(horizontal = 14.dp))

                // 2b. Compact Mesh Bridge row
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(R.string.bridge_title), fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp)
                        val bridgeSub = if (bridge.enabled) {
                            val cell = bridge.cell
                            if (cell != null && bridge.online) stringResource(R.string.bridge_online, cell)
                            else if (cell != null) stringResource(R.string.bridge_offline, cell)
                            else stringResource(R.string.bridge_need_cell)
                        } else {
                            stringResource(R.string.bridge_body)
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            if (bridge.enabled) {
                                Box(Modifier.size(5.5.dp).background(OpenChatPalette.Signal, CircleShape))
                            }
                            Text(
                                bridgeSub,
                                fontSize = 12.sp,
                                color = OpenChatPalette.Muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Switch(checked = bridge.enabled, onCheckedChange = { ChatRepository.controller?.setBridge(it) })
                }

                // 2c. Gently highlighted inset Offline APK Share well (matches Braun monochrome spec)
                Box(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 12.dp)) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (isLight) Color(0xFFEBE7DE) else Color(0xFF1A1F2A),
                        border = BorderStroke(
                            0.5.dp,
                            if (isLight) Color(0x1E18140E) else Color(0x24FFFFFF),
                        ),
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .pressScale(0.98f)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(role = Role.Button, onClick = onShareApp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = OpenChatPalette.Surface,
                                tonalElevation = 0.dp,
                                shadowElevation = 0.dp,
                                modifier = Modifier.size(34.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Outlined.Share, contentDescription = null, tint = OpenChatPalette.Ink, modifier = Modifier.size(17.dp))
                                }
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Text(
                                    stringResource(R.string.tip_share_title),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.5.sp,
                                    color = OpenChatPalette.Ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    stringResource(R.string.tip_share_body),
                                    fontSize = 11.5.sp,
                                    color = OpenChatPalette.Muted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Surface(
                                shape = CircleShape,
                                color = OpenChatPalette.PrimaryAction,
                                tonalElevation = 0.dp,
                                shadowElevation = 0.dp,
                            ) {
                                Text(
                                    stringResource(R.string.me_share_pill),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = OpenChatPalette.OnPrimaryAction,
                                    maxLines = 1,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }
                }

                Hairline(Modifier.padding(horizontal = 14.dp))
                ThemeSection()
            }

            // CARD 3: Two-Tier Safety Isolation (Clear Local History vs Destroy Keys)
            SettingsGroupCard {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { confirmClearChats = true }
                        .padding(horizontal = 16.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            stringResource(R.string.me_clear_chats_title),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.5.sp,
                            color = OpenChatPalette.Ink,
                        )
                        Text(
                            stringResource(R.string.me_clear_chats_body),
                            fontSize = 12.sp,
                            color = OpenChatPalette.Muted,
                        )
                    }
                    Surface(
                        shape = CircleShape,
                        color = OpenChatPalette.Well,
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp,
                    ) {
                        Text(
                            stringResource(R.string.me_clear_chats_cta),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = OpenChatPalette.Ink,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
                Hairline(Modifier.padding(horizontal = 14.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { confirmWipeKeys = true }
                        .padding(horizontal = 16.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            stringResource(R.string.me_wipe_title),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.5.sp,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            stringResource(R.string.me_wipe_body),
                            fontSize = 12.sp,
                            color = OpenChatPalette.Muted,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.errorContainer,
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp,
                    ) {
                        Text(
                            stringResource(R.string.me_wipe_cta),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }

            // CARD 4: Collapsible Preferences, App Updates, Relays & Blocked List (keeps main screen razor clean)
            val hasUpdate = updateState is AppUpdater.UpdateState.Available || updateState is AppUpdater.UpdateState.ReadyToInstall
            SettingsGroupCard {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { showMorePrefs = !showMorePrefs }
                        .padding(horizontal = 16.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(R.string.me_more_settings),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = OpenChatPalette.Ink,
                        modifier = Modifier.weight(1f),
                    )
                    if (hasUpdate) {
                        Box(Modifier.size(7.dp).background(OpenChatPalette.Signal, CircleShape))
                    }
                    Text(
                        if (showMorePrefs) "▴" else "▾",
                        fontSize = 13.sp,
                        color = OpenChatPalette.Muted,
                    )
                }
                AnimatedVisibility(visible = showMorePrefs || hasUpdate) {
                    Column(Modifier.fillMaxWidth()) {
                        Hairline()
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(stringResource(R.string.me_language), fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp, modifier = Modifier.weight(1f))
                            LanguagePicker(onLanguage)
                        }
                        Hairline()
                        NotificationSection()
                        Hairline()
                        LiveVoiceSection()
                        Hairline()
                        RelaySection()
                        Hairline()
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(stringResource(R.string.diag_export_title), fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp)
                                Text(stringResource(R.string.diag_export_body), fontSize = 11.5.sp, lineHeight = 16.sp, color = OpenChatPalette.Muted)
                            }
                            Surface(
                                shape = CircleShape,
                                color = OpenChatPalette.Well,
                                modifier = Modifier
                                    .pressScale(0.95f)
                                    .clip(CircleShape)
                                    .clickable(role = Role.Button) { org.openchat.android.app.DiagLog.copyReport(ctx) },
                            ) {
                                Text(
                                    stringResource(R.string.diag_copy_btn),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = OpenChatPalette.Ink,
                                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                                )
                            }
                            Surface(
                                shape = CircleShape,
                                color = OpenChatPalette.PrimaryAction,
                                modifier = Modifier
                                    .pressScale(0.95f)
                                    .clip(CircleShape)
                                    .clickable(role = Role.Button) { org.openchat.android.app.DiagLog.shareReport(ctx) },
                            ) {
                                Text(
                                    stringResource(R.string.diag_share_btn),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = OpenChatPalette.OnPrimaryAction,
                                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                                )
                            }
                        }
                        if (onOpenGuide != null) {
                            Hairline()
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable(role = Role.Button, onClick = onOpenGuide)
                                    .padding(horizontal = 16.dp, vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(stringResource(R.string.guide_title), fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp, modifier = Modifier.weight(1f))
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = OpenChatPalette.Muted)
                            }
                        }
                    }
                }
            }

            if (showMorePrefs || hasUpdate) {
                UpdateSection()
                BlockedList()
            }

            // Minimalist footer
            Column(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(stringResource(R.string.me_about), fontSize = 11.5.sp, color = OpenChatPalette.Muted, textAlign = TextAlign.Center)
                Text(appVersion(), fontSize = 11.sp, style = TabularMonoStyle, color = OpenChatPalette.Muted)
            }
        }
    }
}

@Composable
private fun ThemeSection() {
    val context = LocalContext.current
    val current by AppTheme.choiceFlow.collectAsState()
    val isLight = OpenChatPalette.isLight
    Column(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.theme_title), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = OpenChatPalette.Well,
            modifier = Modifier.fillMaxWidth(),
        ) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val hideIcon = maxWidth < 280.dp
                Row(
                    Modifier.fillMaxWidth().padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val options = listOf(
                        Triple(AppTheme.Choice.SYSTEM, Icons.Outlined.BrightnessAuto, R.string.theme_system),
                        Triple(AppTheme.Choice.LIGHT, Icons.Outlined.LightMode, R.string.theme_light),
                        Triple(AppTheme.Choice.DARK, Icons.Outlined.DarkMode, R.string.theme_dark),
                    )
                    options.forEach { (choice, icon, labelRes) ->
                        val selected = current == choice
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(9.dp))
                                .let {
                                    if (selected) {
                                        it.background(
                                            if (isLight) OpenChatPalette.Surface else OpenChatPalette.CardRaised,
                                            RoundedCornerShape(9.dp),
                                        )
                                    } else {
                                        it
                                    }
                                }
                                .clickable(role = Role.RadioButton) { AppTheme.set(context, choice) }
                                .padding(horizontal = if (hideIcon) 6.dp else 8.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (!hideIcon) {
                                Icon(
                                    icon,
                                    contentDescription = null,
                                    tint = if (selected) OpenChatPalette.Ink else OpenChatPalette.Muted,
                                    modifier = Modifier.size(15.dp),
                                )
                                Spacer(Modifier.width(5.dp))
                            }
                            Text(
                                stringResource(labelRes),
                                fontSize = 12.5.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) OpenChatPalette.Ink else OpenChatPalette.Muted,
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

/** "openchat 0.7.8 (19)": the installed versionName and versionCode, so a report names its build. */
@Composable
private fun appVersion(): String {
    val context = LocalContext.current
    return remember {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "openchat ${info.versionName} (${androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info)})"
    }
}

@Composable
private fun UpdateSection() {
    val context = LocalContext.current
    val state by AppUpdater.state.collectAsState()
    var autoCheck by remember(state) { mutableStateOf(AppUpdater.autoCheck(context)) }
    val installedVer = remember {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "v${info.versionName}"
    }

    SettingsGroupCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.update_title),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    installedVer,
                    fontSize = 12.sp,
                    style = TabularMonoStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                stringResource(R.string.update_body),
                fontSize = 13.sp,
                lineHeight = 18.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when (val s = state) {
                is AppUpdater.UpdateState.Idle -> {
                    TonalActionButton(
                        onClick = { AppUpdater.checkNow(context) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.SystemUpdateAlt, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.update_check), fontWeight = FontWeight.Medium)
                    }
                }
                is AppUpdater.UpdateState.Checking -> {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        Text(stringResource(R.string.update_checking), fontSize = 13.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { AppUpdater.cancel() }) { Text(stringResource(R.string.cancel)) }
                    }
                }
                is AppUpdater.UpdateState.UpToDate -> {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(OpenChatPalette.Online))
                        Text(
                            stringResource(R.string.update_up_to_date, "v${s.currentVersion}"),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    TonalActionButton(
                        onClick = { AppUpdater.checkNow(context) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.update_check), fontWeight = FontWeight.Medium)
                    }
                }
                is AppUpdater.UpdateState.Available -> {
                    val rel = s.release
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = OpenChatPalette.Well,
                    ) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                stringResource(
                                    R.string.update_available_title,
                                    rel.tagName,
                                    Formatter.formatShortFileSize(context, rel.apkSizeBytes),
                                ),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                                color = OpenChatPalette.Signal,
                            )
                            if (rel.summary.isNotEmpty()) {
                                Text(
                                    rel.summary,
                                    fontSize = 12.5.sp,
                                    lineHeight = 18.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Button(
                                onClick = { AppUpdater.download(context, rel) },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 44.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = OpenChatPalette.Signal,
                                    contentColor = OpenChatPalette.OnSignal,
                                ),
                            ) {
                                Icon(Icons.Outlined.SystemUpdateAlt, contentDescription = null, modifier = Modifier.size(17.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.update_download), fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
                is AppUpdater.UpdateState.Downloading -> {
                    val total = s.totalBytes.coerceAtLeast(1L)
                    val fraction = (s.bytesRead.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                    val pct = (fraction * 100f).toInt()
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = OpenChatPalette.Well,
                    ) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                stringResource(
                                    R.string.update_downloading,
                                    Formatter.formatShortFileSize(context, s.bytesRead),
                                    Formatter.formatShortFileSize(context, total),
                                    pct,
                                ),
                                fontSize = 12.5.sp,
                                style = TabularMonoStyle,
                            )
                            LinearProgressIndicator(
                                progress = { fraction },
                                modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
                                color = OpenChatPalette.Signal,
                                trackColor = OpenChatPalette.Line,
                            )
                            TonalActionButton(
                                onClick = { AppUpdater.cancel() },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.cancel), fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
                is AppUpdater.UpdateState.Verifying -> {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        Text(stringResource(R.string.update_verifying), fontSize = 13.sp)
                    }
                }
                is AppUpdater.UpdateState.ReadyToInstall -> {
                    val rel = s.release
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = OpenChatPalette.Well,
                    ) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(Modifier.size(8.dp).clip(CircleShape).background(OpenChatPalette.Online))
                                Text(
                                    stringResource(R.string.update_verified),
                                    fontSize = 12.5.sp,
                                    color = OpenChatPalette.Online,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                            Button(
                                onClick = { context.findActivity()?.let { AppUpdater.install(it, s.apkFile) } },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 44.dp),
                            ) {
                                Text(stringResource(R.string.update_install, rel.tagName), fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
                is AppUpdater.UpdateState.Error -> {
                    val msg = when (s.error) {
                        AppUpdater.UpdateError.TOR_NOT_READY -> stringResource(R.string.update_err_tor)
                        AppUpdater.UpdateError.NETWORK -> stringResource(R.string.update_err_network)
                        AppUpdater.UpdateError.CHECKSUM_MISSING,
                        AppUpdater.UpdateError.HASH_MISMATCH,
                        -> stringResource(R.string.update_err_hash)
                        AppUpdater.UpdateError.SIGNATURE_MISMATCH,
                        AppUpdater.UpdateError.DOWNGRADE_BLOCKED,
                        -> stringResource(R.string.update_err_sig)
                        AppUpdater.UpdateError.INSTALL_FAILED -> stringResource(R.string.update_err_install)
                    }
                    Text(msg, fontSize = 12.5.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.error)
                    TonalActionButton(
                        onClick = {
                            if (s.release != null) AppUpdater.download(context, s.release)
                            else AppUpdater.checkNow(context)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.retry), fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
        Hairline()
        Row(
            Modifier
                .fillMaxWidth()
                .clickable {
                    val next = !autoCheck
                    autoCheck = next
                    AppUpdater.setAutoCheck(context, next)
                }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.update_auto_title),
                fontSize = 14.sp,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = autoCheck,
                onCheckedChange = { next ->
                    autoCheck = next
                    AppUpdater.setAutoCheck(context, next)
                },
            )
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
