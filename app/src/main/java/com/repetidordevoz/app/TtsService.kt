package com.repetidordevoz.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import java.util.Locale

class TtsService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val CHANNEL_ID = "repetidor_tts"
        const val NOTIF_ID = 1
        const val ACTION_STOP = "com.repetidordevoz.app.STOP"
    }

    private lateinit var tts: TextToSpeech
    private var ttsReady = false

    // Callback que se llama cuando el TTS termina de hablar
    // Lo usa MainActivity para encadenar la siguiente repetición
    var onUtteranceDone: (() -> Unit)? = null
    var onUtteranceError: (() -> Unit)? = null

    inner class TtsBinder : Binder() {
        fun getService(): TtsService = this@TtsService
    }

    private val binder = TtsBinder()

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this)
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Listo para reproducir"))
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale("es", "AR")
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    onUtteranceDone?.invoke()
                }
                override fun onError(utteranceId: String?) {
                    onUtteranceError?.invoke()
                }
            })
            ttsReady = true
        }
    }

    fun speak(text: String, rate: Float, pitch: Float, volume: Float, langCode: String) {
        if (!ttsReady) return
        // Configurar idioma dinámicamente desde la UI
        val parts = langCode.split("-")
        val locale = if (parts.size >= 2) Locale(parts[0], parts[1]) else Locale(parts[0])
        val result = tts.isLanguageAvailable(locale)
        if (result >= TextToSpeech.LANG_AVAILABLE) {
            tts.language = locale
        }
        tts.setSpeechRate(rate)
        tts.setPitch(pitch)
        // Android TTS no tiene control de volumen nativo por utterance;
        // el volumen se maneja desde la UI con el stream de audio del sistema
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "rep_utterance")
        updateNotification("Hablando...")
    }

    fun stopSpeaking() {
        if (ttsReady) tts.stop()
        updateNotification("Detenido")
    }

    fun pauseSpeaking() {
        if (ttsReady) tts.stop() // Android TTS no tiene pause real; stop y luego reanudar
        updateNotification("Pausado")
    }

    fun isReady() = ttsReady

    // ── NOTIFICACIÓN FOREGROUND ──
    // La notificación mantiene el servicio vivo aunque el usuario minimice la app.
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Repetidor de Voz",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "TTS en reproducción"
            setSound(null, null)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(status: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, TtsService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Repetidor de Voz")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_delete, "Detener", stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    fun updateNotification(status: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(status))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSpeaking()
            // Notificar a la Activity para actualizar la UI
            sendBroadcast(Intent("com.repetidordevoz.app.TTS_STOPPED"))
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        tts.shutdown()
        super.onDestroy()
    }
}
