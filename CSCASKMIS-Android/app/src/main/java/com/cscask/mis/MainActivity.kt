package com.cscask.mis

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.webkit.*
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

class MainActivity : AppCompatActivity() {

    companion object {
        const val SITE_URL = "https://cscasktn.page.gd/"
        const val HOST = "cscasktn.page.gd"
    }

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var offlineView: View
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            fileUploadCallback?.onReceiveValue(uris)
            fileUploadCallback = null
        }

    // --- Native voice recognition (Google speech) for Voice Search / Buddy ---
    private val speechLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val alternatives = result.data
                ?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
            if (result.resultCode == RESULT_OK && !alternatives.isNullOrEmpty()) {
                val json = org.json.JSONArray(alternatives.toList()).toString()
                webView.evaluateJavascript(
                    "window.__androidVoiceResult && window.__androidVoiceResult(" +
                        org.json.JSONObject.quote(json) + ");",
                    null
                )
            } else {
                webView.evaluateJavascript(
                    "window.__androidVoiceError && window.__androidVoiceError();",
                    null
                )
            }
        }

    // Runtime mic permission (Android 6+). Must be granted before launching the
    // speech recognizer intent, or the system dialog silently fails.
    private var pendingVoiceLang: String? = null
    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val lang = pendingVoiceLang
            pendingVoiceLang = null
            if (granted && lang != null) {
                launchSpeechIntent(lang)
            } else if (lang != null) {
                // Only show the "voice search needs mic" feedback if the user
                // was actually mid-voice-search when they denied permission.
                webView.evaluateJavascript(
                    "window.__androidVoiceError && window.__androidVoiceError();",
                    null
                )
                Toast.makeText(this, "Microphone permission needed for voice search", Toast.LENGTH_SHORT).show()
            }
            // else: this was just the upfront app-launch permission prompt; stay silent either way
        }

    private fun launchSpeechIntent(lang: String) {
        try {
            val intent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, lang)
                putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(
                    android.speech.RecognizerIntent.EXTRA_PROMPT,
                    if (lang.startsWith("ta")) "பேசுங்கள்..." else "Speak now..."
                )
            }
            speechLauncher.launch(intent)
        } catch (_: Exception) {
            // Google app / speech service not available on this device
            webView.evaluateJavascript(
                "window.__androidVoiceError && window.__androidVoiceError();",
                null
            )
            Toast.makeText(this, "Voice search needs the Google app installed", Toast.LENGTH_SHORT).show()
        }
    }

    inner class VoiceBridge {
        @JavascriptInterface
        fun startListening(lang: String) {
            runOnUiThread {
                val hasMicPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                    this@MainActivity, android.Manifest.permission.RECORD_AUDIO
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED

                if (hasMicPermission) {
                    launchSpeechIntent(lang)
                } else {
                    pendingVoiceLang = lang
                    micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        offlineView = findViewById(R.id.offlineLayout)

        // --- WebView settings ---
        webView.settings.apply {
            javaScriptEnabled = true          // Required: site JS + InfinityFree browser check
            domStorageEnabled = true          // localStorage (dark mode toggle, etc.)
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            allowFileAccess = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = true
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        // Expose native voice recognition to the site's voice_recognition.js
        webView.addJavascriptInterface(VoiceBridge(), "AndroidVoice")

        // --- Navigation control ---
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url
                return when {
                    // Keep our own site inside the app
                    url.host == HOST -> false
                    // tel:, mailto:, whatsapp etc -> open external app
                    url.scheme != "http" && url.scheme != "https" -> {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, url))
                        } catch (_: Exception) {
                            Toast.makeText(this@MainActivity, "No app found to open this link", Toast.LENGTH_SHORT).show()
                        }
                        true
                    }
                    // External http links (Google OAuth for Drive backup, etc.) -> browser
                    else -> {
                        startActivity(Intent(Intent.ACTION_VIEW, url))
                        true
                    }
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                swipeRefresh.isRefreshing = false
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) showOffline()
            }
        }

        // --- File upload support (CSV import, Excel import pages) ---
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback
                return try {
                    fileChooserLauncher.launch(fileChooserParams.createIntent())
                    true
                } catch (_: Exception) {
                    fileUploadCallback = null
                    false
                }
            }

            // Grant mic access to the WebView itself, in case the page ever uses
            // the browser's own Web Speech API instead of the AndroidVoice bridge.
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val wantsAudio = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                    val originOk = request.origin.toString().contains(HOST)
                    if (wantsAudio && originOk &&
                        androidx.core.content.ContextCompat.checkSelfPermission(
                            this@MainActivity, android.Manifest.permission.RECORD_AUDIO
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {
                        request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                    } else {
                        request.deny()
                    }
                }
            }
        }

        // --- File download support (Excel/PDF/HTML exports) ---
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            try {
                val request = DownloadManager.Request(Uri.parse(url)).apply {
                    val cookies = CookieManager.getInstance().getCookie(url)
                    addRequestHeader("Cookie", cookies)
                    addRequestHeader("User-Agent", userAgent)
                    val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                    setTitle(fileName)
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                }
                val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(request)
                Toast.makeText(this, "Downloading...", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Toast.makeText(this, "Download failed", Toast.LENGTH_SHORT).show()
            }
        }

        // --- Pull to refresh ---
        swipeRefresh.setOnRefreshListener {
            if (isOnline()) {
                offlineView.visibility = View.GONE
                webView.visibility = View.VISIBLE
                webView.reload()
            } else {
                swipeRefresh.isRefreshing = false
                showOffline()
            }
        }

        // --- Retry button on offline screen ---
        findViewById<View>(R.id.btnRetry).setOnClickListener {
            if (isOnline()) {
                offlineView.visibility = View.GONE
                webView.visibility = View.VISIBLE
                webView.reload()
            } else {
                Toast.makeText(this, "Still no internet connection", Toast.LENGTH_SHORT).show()
            }
        }

        // --- Hardware back = go back inside site ---
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })

        // --- Initial load ---
        if (savedInstanceState == null) {
            if (isOnline()) webView.loadUrl(SITE_URL) else showOffline()
        } else {
            webView.restoreState(savedInstanceState)
        }

        // Ask for mic permission upfront so Voice Search works the first time it's tapped
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.RECORD_AUDIO
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun showOffline() {
        webView.visibility = View.GONE
        offlineView.visibility = View.VISIBLE
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }
}
