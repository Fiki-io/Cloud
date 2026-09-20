package com.example.gecko

import android.content.Context
import android.view.KeyEvent
import com.example.service.KeepAliveService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    private val scope = CoroutineScope(Dispatchers.Main + Job())
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
    }

    /**
     * Sends a real hardware-level Android KeyEvent directly to GeckoView.
     * GeckoView translates this directly into internal engine keyboard events,
     * which reliably targets the focused terminal (xterm.js inside any iframe)
     * without being blocked by CSP or iframe boundaries.
     */
    fun sendNativeKeyEvent(keyCode: Int, ctrl: Boolean = false, alt: Boolean = false, shift: Boolean = false) {
        val view = geckoViewRef?.get() ?: return
        var metaState = 0
        if (ctrl) metaState = metaState or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (alt) metaState = metaState or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        if (shift) metaState = metaState or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON

        val eventTime = android.os.SystemClock.uptimeMillis()
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
                    // Critical fix: Loading the popup URI inside the main session and returning null.
                    // Returning GeckoResult.fromValue(session) with the same active session causes a fatal C++ native crash in GeckoView!
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
                    // Auto-confirm/dismiss button prompts without crashing
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
     * Spoofs Page Visibility API so that Cloud Shell thinks the tab is always visible,
     * even when minimized or when the phone screen is off.
     */
    fun injectAntiFreezeScript() {
        val script = """
            javascript:(function() {
                try {
                    Object.defineProperty(document, 'hidden', { get: function() { return false; }, configurable: true });
                    Object.defineProperty(document, 'visibilityState', { get: function() { return 'visible'; }, configurable: true });
                    Object.defineProperty(document, 'webkitVisibilityState', { get: function() { return 'visible'; }, configurable: true });
                    window.addEventListener('visibilitychange', function(e) { e.stopImmediatePropagation(); }, true);
                } catch(e) {}
            })();void(0);
        """.trimIndent()
        session.loadUri(script)
    }

    /**
     * Heartbeat pulse with randomized jitter (every 180s - 260s) to prevent inactivity timeout
     * without triggering rigid bot detection patterns.
     */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (true) {
                // Random jitter between 180 seconds and 260 seconds (approx 3 to 4.3 minutes)
                val jitterSeconds = Random.nextLong(180L, 260L)
                delay(jitterSeconds * 1000L)

                if (_isPulseActive.value) {
                    injectPulse()
                    KeepAliveService.recordHeartbeat()
                }
            }
        }
    }

    /**
     * Injects a harmless keystroke (Shift) into xterm.js terminal to refresh websocket activity.
     */
    fun injectPulse() {
        // Native key event Shift dispatch
        sendNativeKeyEvent(KeyEvent.KEYCODE_SHIFT_LEFT)

        // Also run JS fallback traversing all frames/iframes
        val script = """
            javascript:(function() {
                function triggerInDoc(doc) {
                    try {
                        var target = doc.querySelector('.xterm-helper-textarea') || 
                                     doc.querySelector('textarea') || 
                                     doc.querySelector('.xterm') || 
                                     doc.activeElement || 
                                     doc.body;
                        if (target) {
                            var evDown = new KeyboardEvent('keydown', { key: 'Shift', code: 'ShiftLeft', keyCode: 16, which: 16, bubbles: true });
                            target.dispatchEvent(evDown);
                            var evUp = new KeyboardEvent('keyup', { key: 'Shift', code: 'ShiftLeft', keyCode: 16, which: 16, bubbles: true });
                            target.dispatchEvent(evUp);
                        }
                    } catch(e) {}
                    try {
                        var iframes = doc.querySelectorAll('iframe');
                        for (var i = 0; i < iframes.length; i++) {
                            try {
                                if (iframes[i].contentDocument) {
                                    triggerInDoc(iframes[i].contentDocument);
                                }
                            } catch(e) {}
                        }
                    } catch(e) {}
                }
                triggerInDoc(document);
            })();void(0);
        """.trimIndent()
        session.loadUri(script)
    }

    /**
     * Terminal quick button dispatcher (ESC, TAB, Arrow keys, Ctrl+C, etc.)
     * Combines Native Hardware KeyEvent and recursive DOM dispatch.
     */
    fun sendTerminalKey(
        androidKeyCode: Int,
        key: String,
        code: String,
        jsKeyCode: Int,
        ctrl: Boolean = false,
        alt: Boolean = false
    ) {
        // 1. Send native hardware KeyEvent directly to GeckoView
        if (androidKeyCode != KeyEvent.KEYCODE_UNKNOWN) {
            sendNativeKeyEvent(androidKeyCode, ctrl = ctrl, alt = alt)
        }

        // 2. Also dispatch JS keyboard event, penetrating through all iframes to find xterm.js
        val script = """
            javascript:(function() {
                function dispatchKey(doc) {
                    try {
                        var target = doc.querySelector('.xterm-helper-textarea') || 
                                     doc.activeElement || 
                                     doc.querySelector('textarea') || 
                                     doc.body;
                        if (target) {
                            var evDown = new KeyboardEvent('keydown', {
                                key: '$key',
                                code: '$code',
                                keyCode: $jsKeyCode,
                                which: $jsKeyCode,
                                ctrlKey: $ctrl,
                                altKey: $alt,
                                bubbles: true,
                                cancelable: true
                            });
                            target.dispatchEvent(evDown);
                            var evUp = new KeyboardEvent('keyup', {
                                key: '$key',
                                code: '$code',
                                keyCode: $jsKeyCode,
                                which: $jsKeyCode,
                                ctrlKey: $ctrl,
                                altKey: $alt,
                                bubbles: true,
                                cancelable: true
                            });
                            target.dispatchEvent(evUp);
                        }
                    } catch(e) {}
                    try {
                        var frames = doc.querySelectorAll('iframe');
                        for (var i = 0; i < frames.length; i++) {
                            try {
                                if (frames[i].contentDocument) {
                                    dispatchKey(frames[i].contentDocument);
                                }
                            } catch(e) {}
                        }
                    } catch(e) {}
                }
                dispatchKey(document);
            })();void(0);
        """.trimIndent()
        session.loadUri(script)
    }

    fun destroy() {
        heartbeatJob?.cancel()
        session.close()
    }
}
