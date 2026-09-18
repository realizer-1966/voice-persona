package com.voicepersona.app

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Thin TextToSpeech wrapper so replies can be spoken out loud. */
class Speaker(context: Context) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: (() -> Unit)? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.language = Locale.KOREAN
                pending?.invoke()
            }
            pending = null
        }
    }

    fun speak(text: String) {
        if (text.isBlank()) return
        val engine = tts ?: return
        val doSpeak: () -> Unit = {
            engine.language = Locale.KOREAN
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "vp-${System.currentTimeMillis()}")
            Unit
        }
        if (ready) doSpeak() else pending = doSpeak
    }

    fun stop() {
        runCatching { tts?.stop() }
    }

    fun shutdown() {
        runCatching { tts?.shutdown() }
        tts = null
    }
}
