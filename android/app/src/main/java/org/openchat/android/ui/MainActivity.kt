package org.openchat.android.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.openchat.android.app.ApkShare
import org.openchat.android.app.AppLanguage
import org.openchat.android.app.MeshService

class MainActivity : ComponentActivity() {
    private val onboarded = mutableStateOf(false)
    private val missingPermissions = mutableStateOf(false)
    private val scanResult = mutableStateOf<String?>(null) // "" = rejected, else the verified nickname

    private val scanLauncher = registerForActivityResult(ScanContract()) { r ->
        val text = r.contents ?: return@registerForActivityResult
        scanResult.value = org.openchat.android.app.ChatRepository.controller?.verifyScanned(text) ?: ""
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshPermissionState()
        if (!missingPermissions.value) MeshService.start(this)
    }

    private val locationLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        org.openchat.android.app.ChatRepository.controller?.refreshLocation()
    }

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLanguage.wrap(newBase))

    private fun setLanguage(choice: AppLanguage.Choice) {
        AppLanguage.set(this, choice)
        recreate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15 draws edge to edge whether asked or not; asking makes every version lay out the same way.
        enableEdgeToEdge()
        val prefs = getSharedPreferences("ui", Context.MODE_PRIVATE)
        onboarded.value = prefs.getBoolean("onboarded", false)
        refreshPermissionState()
        if (onboarded.value && !missingPermissions.value) MeshService.start(this)
        if (onboarded.value) openLink(intent)

        setContent {
            OpenChatTheme {
                if (!onboarded.value) {
                    OnboardingScreen(onStart = {
                        prefs.edit().putBoolean("onboarded", true).apply()
                        onboarded.value = true
                        requestMeshPermissions()
                    }, onLanguage = ::setLanguage)
                } else {
                    MainScreen(
                        missingPermissions = missingPermissions.value,
                        onGrantPermissions = ::requestMeshPermissions,
                        onEnableBluetooth = ::enableBluetooth,
                        onRequestLocation = ::requestLocation,
                        onShareApp = { ApkShare.share(this) },
                        onLanguage = ::setLanguage,
                        onScan = {
                            scanLauncher.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setBeepEnabled(false).setOrientationLocked(false).setPrompt(""))
                        },
                        scanResult = scanResult.value,
                        onScanResultSeen = { scanResult.value = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openLink(intent)
    }

    /** `bitchat://geohash/<cell>` from a shared invite (§21.5) opens that location channel. */
    private fun openLink(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "bitchat" || uri.host != "geohash") return
        val cell = uri.pathSegments.firstOrNull()?.lowercase() ?: return
        if (!org.openchat.android.nostr.Geohash.isValid(cell) || cell.length < 2) return
        val here = org.openchat.android.app.ChatRepository.geo.value.locationGeohash
        org.openchat.android.app.ChatRepository.controller?.joinGeo(cell, teleported = here?.startsWith(cell) != true)
        org.openchat.android.app.ChatRepository.requestOpen(org.openchat.android.app.Conversation.Geo(cell).key)
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionState()
    }

    private fun meshPermissions(): Array<String> {
        val list = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            list += Manifest.permission.BLUETOOTH_SCAN
            list += Manifest.permission.BLUETOOTH_CONNECT
            list += Manifest.permission.BLUETOOTH_ADVERTISE
        } else {
            list += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
        return list.toTypedArray()
    }

    private fun required() = meshPermissions().filter { it != Manifest.permission.POST_NOTIFICATIONS }

    private fun refreshPermissionState() {
        missingPermissions.value = required().any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
    }

    private fun requestMeshPermissions() {
        val needed = meshPermissions().filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) MeshService.start(this) else permissionLauncher.launch(needed.toTypedArray())
    }

    private fun requestLocation() {
        val p = Manifest.permission.ACCESS_COARSE_LOCATION
        if (ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED) {
            org.openchat.android.app.ChatRepository.controller?.refreshLocation()
        } else {
            locationLauncher.launch(arrayOf(p, Manifest.permission.ACCESS_FINE_LOCATION))
        }
    }

    @Suppress("MissingPermission")
    private fun enableBluetooth() {
        try { startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) } catch (e: Exception) { }
    }
}
