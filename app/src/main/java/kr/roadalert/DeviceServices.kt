package kr.roadalert

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** A foreground GNSS quality probe. Real point-to-road matching requires a separate road data source. */
class DevicePosition(private val context: Context, private val note: (String) -> Unit) : PositionInput, LocationListener {
    private val manager = context.getSystemService(LocationManager::class.java)
    override fun current(now: Long): RoadFix? = UnmappedPosition.current(now)
    fun start() {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            note("정밀 위치 권한 대기"); return
        }
        runCatching {
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 2f, this, Looper.getMainLooper())
        }.onFailure { note("위치 서비스 연결 불가") }
    }
    override fun onLocationChanged(location: Location) {
        val direction = if (location.hasBearing()) "방향 ${location.bearing.toInt()}°" else "방향 대기"
        val age = ((SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000_000).coerceAtLeast(0)
        val accuracy = if (location.hasAccuracy()) "±${location.accuracy.toInt()} m" else "정확도 대기"
        note("GNSS $accuracy · $direction · ${age}초 전 측정")
    }
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    fun stop() { runCatching { manager.removeUpdates(this) } }
}

class ShortVoice(private val context: Context, private val note: (String) -> Unit) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs).setOnAudioFocusChangeListener { if (it < 0) stop() }.build()
    private var engine: TextToSpeech? = null
    private var ready = false
    fun enable() {
        if (engine != null) return
        engine = TextToSpeech(context) { result ->
            val tts = engine ?: return@TextToSpeech
            val voice = if (result == TextToSpeech.SUCCESS) tts.voices?.firstOrNull {
                it.locale.language == Locale.KOREAN.language && !it.isNetworkConnectionRequired &&
                    !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
            } else null
            ready = voice != null && tts.setVoice(voice) == TextToSpeech.SUCCESS
            if (ready) tts.setAudioAttributes(attrs)
            note(if (ready) "한국어 음성 준비" else "기기에 설치된 한국어 음성 없음")
        }
        engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { audio.abandonAudioFocusRequest(focus) }
            @Deprecated("Android callback") override fun onError(utteranceId: String?) { audio.abandonAudioFocusRequest(focus) }
        })
    }
    fun speak(message: String, id: String) {
        if (!ready || audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        if (engine?.speak(message, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS)
            audio.abandonAudioFocusRequest(focus)
    }
    fun stop() { engine?.stop(); audio.abandonAudioFocusRequest(focus) }
    fun close() { stop(); ready = false; engine?.shutdown(); engine = null }
}
