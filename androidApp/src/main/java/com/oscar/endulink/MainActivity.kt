package com.oscar.endulink

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.IntentCompat
import com.oscar.endulink.bike.AndroidBikeKeysStorage
import com.oscar.endulink.platform.AndroidAppContext
import com.oscar.endulink.trail.importGPXFromUri
import com.oscar.endulink.ui.App

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val fineGranted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fineGranted || coarseGranted) {
            com.oscar.endulink.ui.EnduController.shared.restartLocation()
        }
    }

    override fun onResume() {
        super.onResume()
        com.oscar.endulink.ui.EnduController.shared.restartLocation()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingGpxIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidBikeKeysStorage.appContext = applicationContext
        AndroidAppContext.appContext = applicationContext

        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())

        setContent {
            App()
        }

        handleIncomingGpxIntent(intent)
    }

    private fun handleIncomingGpxIntent(incoming: Intent?) {
        if (incoming == null) return
        val uri: Uri? = when (incoming.action) {
            Intent.ACTION_VIEW -> incoming.data
                ?: incoming.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(incoming, Intent.EXTRA_STREAM, Uri::class.java)
                ?: incoming.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            else -> null
        }
        if (uri != null) {
            // Consumir el Intent para evitar reimportaciones repetidas tras recreación
            incoming.action = null
            incoming.data = null
            incoming.removeExtra(Intent.EXTRA_STREAM)
            incoming.clipData = null
            importGPXFromUri(applicationContext, uri)
        }
    }
}

