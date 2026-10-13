package com.example.trackmate.services

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import com.example.trackmate.R
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

private const val VOICE_PREFS_NAME = "navigation_prefs"
private const val KEY_VOICE_MUTED = "voice_muted"
private const val SHUTDOWN_TIMEOUT_MS = 10_000L

private fun voicePrefs(context: Context) =
    context.getSharedPreferences(VOICE_PREFS_NAME, Context.MODE_PRIVATE)

fun isVoiceMuted(context: Context) = voicePrefs(context).getBoolean(KEY_VOICE_MUTED, false)

fun setVoiceMuted(context: Context, muted: Boolean) =
    voicePrefs(context).edit().putBoolean(KEY_VOICE_MUTED, muted).apply()

class VoiceGuidance(context: Context) {
    // app context so the arrival keeps playing after the service stops
    private val context = context.applicationContext
    private val audioManager = this.context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .build()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val utteranceIds = AtomicInteger()
    @Volatile
    private var currentUtterance: String? = null
    @Volatile
    private var ready = false
    @Volatile
    private var closing = false
    private var instructions: TrackInstructions? = null
    private var guide: VoiceGuide? = null
    private var locale: Locale? = null

    private val muteListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == KEY_VOICE_MUTED && prefs.getBoolean(key, false)) tts.stop()
    }

    private val tts: TextToSpeech = TextToSpeech(this.context) { status ->
        if (status == TextToSpeech.SUCCESS) {
            tts.setAudioAttributes(attributes)
            tts.setOnUtteranceProgressListener(progressListener)
            ready = true
        }
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}
        override fun onDone(utteranceId: String?) = finished(utteranceId)
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = finished(utteranceId)
        override fun onStop(utteranceId: String?, interrupted: Boolean) = finished(utteranceId)
    }

    init {
        voicePrefs(this.context).registerOnSharedPreferenceChangeListener(muteListener)
    }

    fun update(current: TrackInstructions?, along: Double, speedKmh: Float, offTrack: Boolean) {
        if (current !== instructions) {
            instructions = current
            guide = current?.let { VoiceGuide(it.maneuvers, it.vehicle) }
            locale = null
        }
        if (!ready || closing) return
        val guide = guide ?: return
        speak(guide.update(along, speedKmh, offTrack))
    }

    fun arrival() {
        if (ready && !closing) speak(guide?.arrival())
    }

    fun shutdown() {
        closing = true
        voicePrefs(context).unregisterOnSharedPreferenceChangeListener(muteListener)
        if (currentUtterance == null) release() else mainHandler.postDelayed(::release, SHUTDOWN_TIMEOUT_MS)
    }

    private fun speak(prompt: VoicePrompt?) {
        val maneuvers = instructions?.maneuvers ?: return
        if (prompt == null || isVoiceMuted(context)) return
        val locale = locale ?: applyLanguage() ?: return
        val text = when (prompt) {
            is VoicePrompt.Depart -> maneuvers[prompt.index].spokenText
            is VoicePrompt.Now -> maneuvers[prompt.index].spokenText
            is VoicePrompt.After -> maneuvers[prompt.index].verbalPost ?: return
            is VoicePrompt.Early -> context.getString(
                R.string.voice_in_meters,
                prompt.meters,
                maneuvers[prompt.index].spokenText.replaceFirstChar { it.lowercase(locale) }
            )
            VoicePrompt.OffTrack -> context.getString(R.string.voice_off_track)
        }
        val id = "nav-${utteranceIds.incrementAndGet()}"
        currentUtterance = id
        audioManager.requestAudioFocus(focusRequest)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    private fun applyLanguage(): Locale? {
        val tag = instructions?.language ?: return null
        val wanted = Locale.forLanguageTag(tag)
        val result = tts.setLanguage(wanted)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            // another language's voice mangles every word, better silent
            guide = null
            mainHandler.post {
                Toast.makeText(context, context.getString(R.string.voice_missing, wanted.displayName), Toast.LENGTH_LONG)
                    .show()
            }
            return null
        }
        locale = wanted
        return wanted
    }

    private fun finished(utteranceId: String?) {
        if (utteranceId != currentUtterance) return
        currentUtterance = null
        audioManager.abandonAudioFocusRequest(focusRequest)
        if (closing) mainHandler.post(::release)
    }

    private fun release() {
        mainHandler.removeCallbacksAndMessages(null)
        audioManager.abandonAudioFocusRequest(focusRequest)
        tts.shutdown()
    }
}
