package dev.n2k02.localadb

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.n2k02.adb.core.AdbConnectionSettingsPage

/** Exactly the same ADB Settings cards and toggles as every shared-core app. */
@Composable
internal fun AdbConnectionPage(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val adb = remember(context.applicationContext) {
        SharedAdbHostAdapter.core(context)
    }
    val lifecycle = LocalLifecycleOwner.current
    var resumeTick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    // Reuse the already configured LocalAdb accessibility reader, not a second
    // service declared by a dependency.
    val accessibilityEnabled = remember(resumeTick) {
        dev.n2k02.adb.core.SharedAdbPairingAccessibilityService.isEnabled(
            context, AdbPairingAccessibilityService::class.java
        )
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        AdbConnectionSettingsPage(
            adb = adb,
            accessibilityEnabled = accessibilityEnabled,
            onOpenAccessibility = {
                runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            },
        )
    }
}
