package app.radd

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.view.WindowCompat
import app.radd.android.Graph
import app.radd.ui.AppRoot
import app.radd.ui.RaddTheme
import app.radd.vm.AndroidPermissions

class MainActivity : ComponentActivity() {
    private val mediaRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // "don't ask again": the system dialog no longer appears, so send the user to the app's settings page instead
        if (!granted && !shouldShowRequestPermissionRationale(mediaPermission())) openAppSettings()
    }
    private val notificationRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            // the app is Arabic-only: always right-to-left, whatever the phone's language
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                RaddTheme {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
                        AppRoot(onGrant = ::grant, onNeedNotifications = ::askNotifications)
                    }
                }
            }
        }
    }

    private fun openAppSettings() {
        try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (_: Exception) { }
    }

    private fun mediaPermission(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            try { notificationRequest.launch(Manifest.permission.POST_NOTIFICATIONS) } catch (_: Exception) { }
        }
    }

    private fun grant() {
        val state = AndroidPermissions(this).state()
        if (!state.mediaGranted) {
            mediaRequest.launch(mediaPermission())
            return
        }
        if (!state.allFilesGranted && Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                try { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } catch (_: Exception) { }
            }
        }
    }
}
