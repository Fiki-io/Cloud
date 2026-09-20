package com.example.gecko

import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent
import com.example.service.KeepAliveService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import java.lang.ref.WeakReference
import kotlin.random.Random

class CloudShellManager(private val context: Context) {

    companion object {
        const val CLOUD_SHELL_URL = "https://shell.cloud.google.com"
        const val DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64; rv:125.0) Gecko/20100101 Firefox/125.0"
        const val MOBILE_UA = "Mozilla/5.0 (Android; Mobile; rv:125.0) Gecko/125.0 Firefox/125.0"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var heartbeatJob: Job? = null

    val runtime: GeckoRuntime by lazy {
        val runtimeSettings = GeckoRuntimeSettings.Builder()
            .webManifest(false)
            .extensionsProcessEnabled(false)
            .extensionsWebAPIEnabled(false)
            .consoleOutput(false)
            .debugLogging(false)
            .aboutConfigEnabled(false)
            .build()
        GeckoRuntime.create(context.applicationContext, runtimeSettings)
    }

    private val _isDesktopMode = MutableStateFlow(true)
    val isDesktopMode: StateFlow<Boolean> = _isDesktopMode.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _progress = MutableStateFlow(0)
    val progress: StateFlow<Int> = _progress.asStateFlow()

    private val _currentUrl = MutableStateFlow(CLOUD_SHELL_URL)
    val currentUrl: StateFlow<String> = _currentUrl.asStateFlow()

    private val _isPulseActive = MutableStateFlow(true)
    val isPulseActive: StateFlow<Boolean> = _isPulseActive.asStateFlow()

    private var geckoViewRef: WeakReference<GeckoView>? = null

    // Session popup penampung jika Google membuka otorisasi OAuth
    private var popupSession: GeckoSession? = null

    fun attachGeckoView(view: GeckoView) {
        geckoViewRef = WeakReference(view)
        view.setSession(session)
    }

    fun sendNativeKeyEvent(keyCode: Int, ctrl: Boolean = false, alt: Boolean = false, shift: Boolean = false) {
        val view = geckoViewRef?.get() ?: return
        var metaState = 0
        if (ctrl) metaState = metaState or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (alt) metaState = metaState or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        if (shift) metaState = metaState or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON

        val eventTime = SystemClock.uptimeMillis()
        val downEvent = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0, metaState)
        val upEvent = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0, metaState)

        view.post {
            view.dispatchKeyEvent(downEvent)
            view.dispatchKeyEvent(upEvent)
        }
    }

    val session: GeckoSession by lazy {
        val settings = GeckoSessionSettings.Builder()
            .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP)
            .userAgentOverride(DESKTOP_UA)
            .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_DESKTOP) // Kunci Viewport Mode Desktop Asli
            .build()

        GeckoSession(settings).apply {
            navigationDelegate = object : GeckoSession.NavigationDelegate {
                override fun onLocationChange(
                    session: GeckoSession,
                    url: String?,
                    permissions: MutableList<GeckoSession.PermissionDelegate.ContentPermission>
                ) {
                    if (url != null) {
                        _currentUrl.value = url
                    }
                }

                // SOLUSI POPUP OAUTH IZINKAN CLOUD SHELL
                override fun onNewSession(
                    session: GeckoSession,
                    uri: String
                ): GeckoResult<GeckoSession>? {
                    // Buat session terpisah untuk otorisasi tanpa menimpa session terminal utama
                    val newSession = GeckoSession(settings)
                    popupSession = newSession
                    
                    newSession.navigationDelegate = object : GeckoSession.NavigationDelegate {
                        override fun onLocationChange(s: GeckoSession, url: String?, p: MutableList<GeckoSession.PermissionDelegate.ContentPermission>) {
                            // Jika popup selesai otentikasi (kembali ke Cloud Shell atau selesai), tutup popup
                            if (url != null && (url.contains("shell.cloud.google.com") || url.contains("close"))) {
                                newSession.close()
                                popupSession = null
                                // Kembalikan view ke session utama
                                geckoViewRef?.get()?.setSession(this@apply)
                            }
                        }
                    }

                    newSession.open(runtime)
                    // Tampilkan popup sementara di view agar pengguna bisa klik izinkan
                    geckoViewRef?.get()?.setSession(newSession)
                    newSession.loadUri(uri)

                    return GeckoResult.fromValue(newSession)
                }
            }

            permissionDelegate = object : GeckoSession.PermissionDelegate {
                override fun onContentPermissionRequest(
                    session: GeckoSession,
                    perm: GeckoSession.PermissionDelegate.ContentPermission
                ): GeckoResult<Int>? {
                    return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW)
                }

                override fun onMediaPermissionRequest(
                    session: GeckoSession,
                    uri: String,
                    video: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
                    audio: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
                    callback: GeckoSession.PermissionDelegate.MediaCallback
                ) {
                    callback.reject()
                }
            }

            promptDelegate = object : GeckoSession.PromptDelegate {
                override fun onAlertPrompt(
                    session: GeckoSession,
                    prompt: GeckoSession.PromptDelegate.AlertPrompt
                ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                    return GeckoResult.fromValue(prompt.dismiss())
                }

                override fun onButtonPrompt(
                    session: GeckoSession,
                    prompt: GeckoSession.PromptDelegate.ButtonPrompt
                ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                    return GeckoResult.fromValue(prompt.confirm(GeckoSession.PromptDelegate.ButtonPrompt.Type.POSITIVE))
                }
            }

            progressDelegate = object : GeckoSession.ProgressDelegate {
                override fun onPageStart(session: GeckoSession, url: String) {
                    _isLoading.value = true
                    _progress.value = 10
                }

                override fun onPageStop(session: GeckoSession, success: Boolean) {
                    _isLoading.value = false
                    _progress.value = 100
                    if (success) {
                        injectDesktopScaleAndAntiFreeze()
                    }
                }

                override fun onProgressChange(session: GeckoSession, newProgress: Int) {
                    _progress.value = newProgress
                }
            }

            open(runtime)
            loadUri(CLOUD_SHELL_URL)
        }
    }

    init {
        startHeartbeat()
    }

    fun reload() {
        session.reload()
    }

    fun loadDefaultUrl() {
        session.loadUri(CLOUD_SHELL_URL)
    }

    fun toggleDesktopMode() {
        val newMode = !_isDesktopMode.value
        _isDesktopMode.value = newMode
        val ua = if (newMode) DESKTOP_UA else MOBILE_UA
        val mode = if (newMode) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
        session.settings.userAgentMode = mode
        session.settings.userAgentOverride = ua
        session.reload()
    }

    fun togglePulse(active: Boolean) {
        _isPulseActive.value = active
        if (active) {
            startHeartbeat()
        } else {
            heartbeatJob?.cancel()
            heartbeatJob = null
        }
    }

    /**
     * Mengatur ukuran layar desktop presisi (1280px),
     * mematikan auto-zoom pada input, dan menjaga status tab tetap aktif.
     */
    fun injectDesktopScaleAndAntiFreeze() {
        val script = "javascript:(function(){try{" +
                // 1. Kunci Viewport ke ukuran Desktop 1280px permanen (tidak membesar seperti HP)
                "var mv=document.querySelector('meta[name=viewport]');" +
                "if(!mv){mv=document.createElement('meta');mv.name='viewport';document.head.appendChild(mv);}" +
                "mv.content='width=1280, initial-scale=0.75, maximum-scale=2.0, user-scalable=yes';" +
                // 2. Cegah auto-zoom saat klik teks / input terminal
                "var st=document.createElement('style');" +
                "st.innerHTML='input, textarea, select, .xterm-helper-textarea { font-size: 16px !important; }';" +
                "document.head.appendChild(st);" +
                // 3. Spoofing visibility agar tidak freeze di background
                "Object.defineProperty(document,'hidden',{get:function(){return false;},configurable:true});" +
                "Object.defineProperty(document,'visibilityState',{get:function(){return'visible';},configurable:true});" +
                "Object.defineProperty(document,'webkitVisibilityState',{get:function(){return'visible';},configurable:true});" +
                "window.addEventListener('visibilitychange',function(e){e.stopImmediatePropagation();},true);" +
                "}catch(e){}})();void(0);"

        session.loadUri(script)
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (true) {
                val jitterSeconds = Random.nextLong(180L, 260L)
                delay(jitterSeconds * 1000L)

                if (_isPulseActive.value) {
                    injectPulse()
                    try {
                        KeepAliveService.recordHeartbeat()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    fun injectPulse() {
        sendNativeKeyEvent(KeyEvent.KEYCODE_SHIFT_LEFT)

        val script = "javascript:(function(){try{" +
                "var t=document.querySelector('.xterm-helper-textarea')||document.querySelector('textarea')||document.activeElement||document.body;" +
                "if(t){" +
                "t.dispatchEvent(new KeyboardEvent('keydown',{key:'Shift',code:'ShiftLeft',keyCode:16,which:16,bubbles:true}));" +
                "t.dispatchEvent(new KeyboardEvent('keyup',{key:'Shift',code:'ShiftLeft',keyCode:16,which:16,bubbles:true}));" +
                "}}catch(e){}})();void(0);"
        session.loadUri(script)
    }

    fun sendTerminalKey(
        androidKeyCode: Int,
        key: String,
        code: String,
        jsKeyCode: Int,
        ctrl: Boolean = false,
        alt: Boolean = false
    ) {
        if (androidKeyCode != KeyEvent.KEYCODE_UNKNOWN) {
            sendNativeKeyEvent(androidKeyCode, ctrl = ctrl, alt = alt)
        }

        val script = "javascript:(function(){try{" +
                "var t=document.querySelector('.xterm-helper-textarea')||document.activeElement||document.querySelector('textarea')||document.body;" +
                "if(t){" +
                "t.dispatchEvent(new KeyboardEvent('keydown',{key:'$key',code:'$code',keyCode:$jsKeyCode,which:$jsKeyCode,ctrlKey:$ctrl,altKey:$alt,bubbles:true,cancelable:true}));" +
                "t.dispatchEvent(new KeyboardEvent('keyup',{key:'$key',code:'$code',keyCode:$jsKeyCode,which:$jsKeyCode,ctrlKey:$ctrl,altKey:$alt,bubbles:true,cancelable:true}));" +
                "}}catch(e){}})();void(0);"
        session.loadUri(script)
    }

    fun destroy() {
        heartbeatJob?.cancel()
        popupSession?.close()
        session.close()
    }
}
