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

    // SupervisorJob menjamin jika 1 tugas coroutine error, timer heartbeat tidak ikut mati
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

    fun attachGeckoView(view: GeckoView) {
        geckoViewRef = WeakReference(view)
        // Hubungkan session ke view tampilan saat activity siap
        view.setSession(session)
    }

    /**
     * Mengirimkan Hardware KeyEvent langsung ke GeckoView.
     * Metode ini 100% menembus iframe sandbox Cloud Shell tanpa diblokir CSP.
     */
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

                override fun onNewSession(
                    session: GeckoSession,
                    uri: String
                ): GeckoResult<GeckoSession>? {
                    // Mencegah crash internal C++ GeckoView saat login Google membuka popup baru
                    session.loadUri(uri)
                    return null
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
                        injectAntiFreezeScript()
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
     * Menyuntikkan:
     * 1. CSS Anti-Zoom: Mengunci font input minimal 16px agar keyboard HP muncul tanpa layar membesar.
     * 2. Meta Viewport Locking: Mengunci skala layar rasio 1:1.
     * 3. Anti-Freeze: Memalsukan status tab agar selalu terbaca aktif di background.
     */
    fun injectAntiFreezeScript() {
        val script = "javascript:(function(){try{" +
                "var st=document.createElement('style');" +
                "st.innerHTML='input, textarea, select, .xterm-helper-textarea { font-size: 16px !important; touch-action: pan-x pan-y !important; }';" +
                "document.head.appendChild(st);" +
                "var mv=document.querySelector('meta[name=viewport]');" +
                "if(!mv){mv=document.createElement('meta');mv.name='viewport';document.head.appendChild(mv);}" +
                "mv.content='width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no';" +
                "Object.defineProperty(document,'hidden',{get:function(){return false;},configurable:true});" +
                "Object.defineProperty(document,'visibilityState',{get:function(){return'visible';},configurable:true});" +
                "Object.defineProperty(document,'webkitVisibilityState',{get:function(){return'visible';},configurable:true});" +
                "window.addEventListener('visibilitychange',function(e){e.stopImmediatePropagation();},true);" +
                "}catch(e){}})();void(0);"
        
        session.loadUri(script)
    }

    /**
     * Heartbeat pulse dengan random jitter (180s - 260s) agar pola tidak terbaca bot kaku.
     */
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
                        // Mencegah error jika service belum sempat terikat
                    }
                }
            }
        }
    }

    /**
     * Mengirimkan tombol Shift secara hardware dan fallback DOM event.
     */
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

    /**
     * Pengiriman tombol shortcut terminal (ESC, TAB, Arrow, Ctrl+C, dll).
     */
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
        session.close()
    }
}
