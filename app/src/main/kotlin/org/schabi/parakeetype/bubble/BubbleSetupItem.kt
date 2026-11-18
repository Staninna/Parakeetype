package org.schabi.parakeetype.bubble

import android.content.Intent
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import org.schabi.parakeetype.R
import org.schabi.parakeetype.settings.screens.SettingsItem

/**
 * The home screen's setup row for the dictation bubble: shows whether its accessibility service
 * is on (re-checked whenever the screen resumes) and, while it is off, opens the accessibility
 * settings to turn it on.
 */
@Composable
internal fun BubbleSetupItem(shape: Shape) {
    val context = LocalContext.current
    val preview = LocalInspectionMode.current
    var enabled by remember { mutableStateOf(!preview && BubbleAccessibilityService.isEnabled(context)) }
    LifecycleResumeEffect(context) {
        if (!preview) enabled = BubbleAccessibilityService.isEnabled(context)
        onPauseOrDispose {}
    }
    SettingsItem(
        icon = if (enabled) Icons.Rounded.Mic else Icons.Rounded.MicOff,
        title = stringResource(if (enabled) R.string.home_bubble_enabled else R.string.home_bubble_disabled),
        subtitle = stringResource(
            if (enabled) R.string.home_bubble_enabled_subtitle else R.string.home_bubble_disabled_subtitle,
        ),
        onClickLabel = stringResource(R.string.action_open_settings),
        onClick = if (enabled) null else {
            { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        },
        shape = shape,
    )
}
