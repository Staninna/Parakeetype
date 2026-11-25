package org.schabi.parakeetype.bubble

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * Pauses music and other media while the bubble dictates, so the microphone does not pick up
 * the song's lyrics. It takes exclusive transient audio focus: players pause on that loss and
 * resume by themselves once [resume] hands the focus back.
 */
class MediaPause(context: Context) {

    private val audioManager = context.getSystemService(AudioManager::class.java)

    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .setOnAudioFocusChangeListener { }
        .build()

    private var held = false

    fun pause() {
        if (held) return
        held = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    fun resume() {
        if (!held) return
        held = false
        audioManager.abandonAudioFocusRequest(request)
    }
}
