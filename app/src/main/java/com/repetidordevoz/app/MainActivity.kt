package com.repetidordevoz.app

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var ttsService: TtsService? = null
    private var serviceBound = false

    // Conexión con el servicio
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val b = binder as TtsService.TtsBinder
            ttsService = b.getService()
            serviceBound = true

            // Cuando el TTS termina una utterance, avisar al JS para encadenar la siguiente
            ttsService?.onUtteranceDone = {
                runOnUiThread {
                    webView.evaluateJavascript("onNativeTtsDone()", null)
                }
            }
            ttsService?.onUtteranceError = {
                runOnUiThread {
                    webView.evaluateJavascript("onNativeTtsDone()", null)
                }
            }
            // Avisar al JS que el servicio está listo
            runOnUiThread {
                webView.evaluateJavascript("onNativeTtsReady()", null)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBound = false
            ttsService = null
        }
    }

    // Receptor para el botón "Detener" de la notificación
    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            webView.evaluateJavascript("stopLoop()", null)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        setupWebView()

        // Iniciar y bindear el servicio foreground
        val serviceIntent = Intent(this, TtsService::class.java)
        startService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        // Registrar receptor del botón Detener de la notificación
        registerReceiver(stopReceiver, IntentFilter("com.repetidordevoz.app.TTS_STOPPED"),
            RECEIVER_NOT_EXPORTED)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
            // Permitir acceso a fuentes y recursos externos
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = WebViewClient()

        // Bridge JavaScript → Kotlin
        webView.addJavascriptInterface(TtsBridge(), "AndroidTTS")

        // Cargar el HTML desde assets
        webView.loadUrl("file:///android_asset/index.html")
    }

    // ── BRIDGE JS → NATIVO ──
    inner class TtsBridge {

        @JavascriptInterface
        fun speak(text: String, rate: Float, pitch: Float, volume: Float, lang: String) {
            ttsService?.speak(text, rate, pitch, volume, lang)
        }

        @JavascriptInterface
        fun stop() {
            ttsService?.stopSpeaking()
        }

        @JavascriptInterface
        fun pause() {
            ttsService?.pauseSpeaking()
        }

        @JavascriptInterface
        fun isReady(): Boolean {
            return ttsService?.isReady() ?: false
        }

        @JavascriptInterface
        fun updateNotificationStatus(status: String) {
            ttsService?.updateNotification(status)
        }
    }

    override fun onDestroy() {
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        unregisterReceiver(stopReceiver)
        super.onDestroy()
    }

    // Al presionar Atrás, minimizar en lugar de cerrar
    // para que el TTS siga corriendo en background
    override fun onBackPressed() {
        moveTaskToBack(true)
    }
}
