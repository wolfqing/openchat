package org.openchat.android.ui

import androidx.compose.ui.semantics.onClick
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(stringResource(R.string.me_name), fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(nick, { nick = it.take(32) }, singleLine = true, modifier = Modifier.weight(1f))
            Spacer(Modifier.size(8.dp))
            Button(enabled = nick.isNotBlank() && nick != mesh.myNickname, onClick = { ChatRepository.controller?.setNickname(nick) }) { Text(stringResource(R.string.save)) }
        }
        val seenAs = stringResource(R.string.me_seen_as)
        Text(
            buildAnnotatedString { append(seenAs); append(labelText(PeerLabel.mesh(mesh.myNickname, mesh.myPeerHex))) },
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(stringResource(R.string.me_suffix_note), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.me_language), fontWeight = FontWeight.SemiBold)
        LanguagePicker(onLanguage)
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.me_share_title), fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.me_share_body), fontSize = 14.sp, lineHeight = 21.sp)
        Spacer(Modifier.height(8.dp))
        Button(onClick = onShareApp, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.me_share_button)) }
        Spacer(Modifier.height(24.dp))
        SafetyNote()
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.me_fp_title), fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.me_fp_body), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(mesh.fingerprint.chunked(4).joinToString(" "), fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.me_device_id, mesh.myPeerHex), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        VerifySection(onScan)
        if (mesh.carriedCount > 0) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.me_carrying, mesh.carriedCount), fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
        }
        NotificationSection()
        LiveVoiceSection()
        BridgeSection()
        TorSection()
        RelaySection()
        BlockedList()
        Spacer(Modifier.height(24.dp))
        WipeSection(onPanic)
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.me_about), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The triple tap on the logo or title is out of reach for screen readers and unsteady hands.
 * This button does the same after a confirmation; the triple tap itself stays unconfirmed (§10).
 */
@Composable
private fun WipeSection(onPanic: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Text(stringResource(R.string.me_wipe_title), fontWeight = FontWeight.SemiBold)
    Text(stringResource(R.string.me_wipe_body), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = { confirm = true },
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
    ) { Text(stringResource(R.string.me_wipe_button)) }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(stringResource(R.string.me_wipe_title)) },
        text = { Text(stringResource(R.string.me_wipe_confirm)) },
        confirmButton = { TextButton(onClick = { confirm = false; onPanic() }) { Text(stringResource(R.string.me_wipe_do), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } },
    )
}
