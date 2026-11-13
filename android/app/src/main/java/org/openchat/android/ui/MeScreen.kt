package org.openchat.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openchat.android.R
import org.openchat.android.app.AppLanguage
import org.openchat.android.app.ChatRepository
import org.openchat.android.app.PeerLabel

@Composable
internal fun MePane(onShareApp: () -> Unit, onLanguage: (AppLanguage.Choice) -> Unit, onScan: () -> Unit, onPanic: () -> Unit) {
    val mesh by ChatRepository.mesh.collectAsState()
    var nick by rememberSaveable(mesh.myNickname) { mutableStateOf(mesh.myNickname) }
    var editingName by rememberSaveable { mutableStateOf(false) }
    var showQr by remember { mutableStateOf(false) }
    var showFingerprint by rememberSaveable { mutableStateOf(false) }

    if (showQr) MyQrDialog { showQr = false }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // 1. Identity Card (Emblem, Nickname#xxxx, QR shortcut, Fingerprint & Verification)
        SettingsGroupCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    EmblemView(mesh.myPeerHex.ifEmpty { "self" }, 54.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            labelText(PeerLabel.mesh(mesh.myNickname, mesh.myPeerHex)),
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            stringResource(R.string.me_suffix_note),
                            fontSize = 12.sp,
                            lineHeight = 16.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { nick = mesh.myNickname; editingName = !editingName }) {
                        Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.me_name), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { showQr = true }) {
                        Icon(Icons.Outlined.QrCode2, contentDescription = stringResource(R.string.verify_my_qr), tint = MaterialTheme.colorScheme.onSurface)
                    }
                }

                AnimatedVisibility(visible = editingName) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.me_name), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = nick,
                                onValueChange = { nick = it.take(32) },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f),
                            )
                            Button(
                                enabled = nick.isNotBlank(),
                                onClick = {
                                    if (nick != mesh.myNickname) ChatRepository.controller?.setNickname(nick)
                                    editingName = false
                                },
                            ) { Text(stringResource(R.string.save)) }
                        }
                    }
                }

                if (mesh.carriedCount > 0) {
                    Surface(
                        shape = CircleShape,
                        color = OpenChatPalette.SignalSoft,
                        border = BorderStroke(1.dp, OpenChatPalette.Signal.copy(alpha = 0.5f)),
                    ) {
                        Text(
                            stringResource(R.string.me_carrying, mesh.carriedCount),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = OpenChatPalette.Signal,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                        )
                    }
                }

                if (mesh.fingerprint.isNotEmpty()) {
                    val ctx = LocalContext.current
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { showFingerprint = !showFingerprint }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.me_fp_title),
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                stringResource(R.string.me_device_id, mesh.myPeerHex),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        AnimatedVisibility(visible = showFingerprint) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(stringResource(R.string.me_fp_body), fontSize = 12.5.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            Hairline()
            VerifySection(onShowQr = { showQr = true }, onScan = onScan)
        }

        // 2. Offline App Sharing Card
        SettingsGroupCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.me_share_title), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(stringResource(R.string.me_share_body), fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(2.dp))
                Button(onClick = onShareApp, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.me_share_button), fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // 3. Language, Notifications & Voice Card
        SettingsGroupCard {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.me_language), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                LanguagePicker(onLanguage)
            }
            Hairline()
            NotificationSection()
            Hairline()
            LiveVoiceSection()
        }

        // 4. Network & Privacy Card (Tor, Bridge, Relays)
        SettingsGroupCard {
            TorSection()
            Hairline()
            BridgeSection()
            Hairline()
            RelaySection()
        }

        // 5. Blocked List (when non-empty)
        BlockedList()

        // 6. Safety Note & Erase Everything
        SafetyNote()
        WipeSection(onPanic)

        // Footer
        Column(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(R.string.me_about), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Text(appVersion(), fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** "openchat 0.7.2 (13)": the installed versionName and versionCode, so a report names its build. */
@Composable
private fun appVersion(): String {
    val context = LocalContext.current
    return remember {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "openchat ${info.versionName} (${androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info)})"
    }
}

/**
 * The triple tap on the logo or title is out of reach for screen readers and unsteady hands.
 * This button does the same after a confirmation; the triple tap itself stays unconfirmed (§10).
 */
@Composable
private fun WipeSection(onPanic: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    SettingsGroupCard(borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.35f)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.me_wipe_title), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = MaterialTheme.colorScheme.error)
            Text(stringResource(R.string.me_wipe_body), fontSize = 13.sp, lineHeight = 18.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(2.dp))
            OutlinedButton(
                onClick = { confirm = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.55f)),
            ) { Text(stringResource(R.string.me_wipe_button)) }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        containerColor = OpenChatPalette.Surface,
        title = { Text(stringResource(R.string.me_wipe_title)) },
        text = { Text(stringResource(R.string.me_wipe_confirm)) },
        confirmButton = { TextButton(onClick = { confirm = false; onPanic() }) { Text(stringResource(R.string.me_wipe_do), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } },
    )
}
