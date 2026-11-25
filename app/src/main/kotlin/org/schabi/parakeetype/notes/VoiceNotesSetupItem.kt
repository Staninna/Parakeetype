package org.schabi.parakeetype.notes

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import org.schabi.parakeetype.R
import org.schabi.parakeetype.settings.screens.SettingsItem

/** Setup state of voice note transcription ([VoiceNoteListenerService]), one step at a time. */
private enum class VoiceNotesState { Off, NeedsFileAccess, NeedsNotifications, On }

/**
 * The home screen's setup row for voice note transcription: off until Parakeetype has
 * notification access, then asks for all files access to read the notes and for permission to
 * post the transcriptions. Re-checked whenever the screen resumes; a tap opens the missing step.
 */
@Composable
internal fun VoiceNotesSetupItem(shape: Shape) {
    val context = LocalContext.current
    val preview = LocalInspectionMode.current
    var state by remember { mutableStateOf(if (preview) VoiceNotesState.Off else voiceNotesState(context)) }
    LifecycleResumeEffect(context) {
        if (!preview) state = voiceNotesState(context)
        onPauseOrDispose {}
    }
    // Once the user has denied the permission, Android stops showing the dialog: fall back to
    // Parakeetype's notification settings, which also covers notifications blocked there.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        state = voiceNotesState(context)
        if (state == VoiceNotesState.NeedsNotifications) openNotificationSettings(context)
    }
    SettingsItem(
        icon = when (state) {
            VoiceNotesState.On -> Icons.Rounded.Notifications
            VoiceNotesState.NeedsFileAccess, VoiceNotesState.NeedsNotifications -> Icons.Rounded.Warning
            VoiceNotesState.Off -> Icons.Rounded.NotificationsOff
        },
        title = stringResource(
            when (state) {
                VoiceNotesState.On -> R.string.home_voice_notes_enabled
                VoiceNotesState.NeedsFileAccess -> R.string.home_voice_notes_needs_access
                VoiceNotesState.NeedsNotifications -> R.string.home_voice_notes_needs_notifications
                VoiceNotesState.Off -> R.string.home_voice_notes_disabled
            },
        ),
        subtitle = stringResource(
            when (state) {
                VoiceNotesState.On -> R.string.home_voice_notes_enabled_subtitle
                VoiceNotesState.NeedsFileAccess -> R.string.home_voice_notes_needs_access_subtitle
                VoiceNotesState.NeedsNotifications -> R.string.home_voice_notes_needs_notifications_subtitle
                VoiceNotesState.Off -> R.string.home_voice_notes_disabled_subtitle
            },
        ),
        onClickLabel = stringResource(R.string.action_open_settings),
        onClick = when (state) {
            VoiceNotesState.On -> null
            VoiceNotesState.NeedsFileAccess -> { { openFileAccessSettings(context) } }
            VoiceNotesState.NeedsNotifications -> {
                {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        openNotificationSettings(context)
                    }
                }
            }
            VoiceNotesState.Off -> { { openNotificationAccessSettings(context) } }
        },
        shape = shape,
    )
}

private fun voiceNotesState(context: Context): VoiceNotesState = when {
    context.packageName !in NotificationManagerCompat.getEnabledListenerPackages(context) -> VoiceNotesState.Off
    !Environment.isExternalStorageManager() -> VoiceNotesState.NeedsFileAccess
    !NotificationManagerCompat.from(context).areNotificationsEnabled() -> VoiceNotesState.NeedsNotifications
    else -> VoiceNotesState.On
}

private fun openNotificationAccessSettings(context: Context) {
    val component = ComponentName(context, VoiceNoteListenerService::class.java)
    val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
        .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
    try {
        context.startActivity(detail)
    } catch (e: ActivityNotFoundException) {
        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }
}

private fun openFileAccessSettings(context: Context) {
    val app = Intent(
        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        Uri.fromParts("package", context.packageName, null),
    )
    try {
        context.startActivity(app)
    } catch (e: ActivityNotFoundException) {
        context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
    }
}

private fun openNotificationSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
    )
}
