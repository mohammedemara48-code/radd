package app.radd

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import app.radd.ui.HomeScreen
import app.radd.ui.PermissionUi
import app.radd.ui.RaddTheme

/** Phase 1: the permission flow is real; the scan engine and the remaining screens plug in during phase 2. */
class MainActivity : ComponentActivity() {
    private var permission by mutableStateOf(PermissionUi(false, false))

    private val mediaRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            RaddTheme {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
                    HomeScreen(permission, onGrant = ::grant, onStart = { /* phase 2: ScanScreen */ })
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun mediaPermission(): String = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun refresh() {
        val allFiles = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else true
        val media = ContextCompat.checkSelfPermission(this, mediaPermission()) == PackageManager.PERMISSION_GRANTED
        permission = PermissionUi(allFiles, media)
    }

    private fun grant() {
        if (!permission.mediaGranted) {
            mediaRequest.launch(mediaPermission())
            return
        }
        if (!permission.allFilesGranted && Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
    }
}
