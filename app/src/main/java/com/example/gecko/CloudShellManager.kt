package com.example.gecko

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.widget.Toast
import com.example.R
import com.example.data.AppConstants
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
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import java.lang.ref.WeakReference
import java.util.Collections
import kotlin.random.Random

class CloudShellManager(context: Context) {

    private val appContext: Context = context.applicationContext
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var heartbeatJob: Job? = null
    private var commandJob: Job? = null
    private var copyTimeoutJob: Job? = null
    private var lastKeyTime = 0L

    @Volatile
    private var waitingForCopyScan = false

    private val activePorts: MutableSet<WebExtension.Port> =
        Collections.synchronizedSet( LinkedHashSet() )

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _progress = MutableStateFlow(0)
    val progress: StateFlow<Int> = _progress.asStateFlow()

    private val _currentUrl = MutableStateFlow(AppConstants.CLOUD_SHELL_URL)
    val currentUrl: StateFlow<String> = _currentUrl.asStateFlow()

    private val _isPulseActive = MutableStateFlow(true)
    val isPulseActive: StateFlow<Boolean> = _isPulseActive.asStateFlow()

    private val _lastPinggyUrl = MutableStateFlow<String?>(null)
    val lastPinggyUrl: StateFlow<String?> = _lastPinggyUrl.asStateFlow()

    private var geckoViewRef: WeakReference<GeckoView>? = null
    private var popupSession: GeckoSession? = null

    val runtime: GeckoRuntime
        get() = getOrCreateRuntime(appContext)

    private val webExtensionMessageDelegate = object : WebExtension.MessageDelegate {
        override fun onMessage(
            nativeApp: String,
            message: Any,
            sender: WebExtension.MessageSender
        ): GeckoResult<Any>? {
            handleExtensionMessage(message)
            return null
        }

        override fun onConnect(port: WebExtension.Port) {
            activePorts.add(port)
            port.setDelegate(object : WebExtension.PortDelegate {
                override fun onPortMessage(message: Any, p: WebExtension.Port) {
                    handleExtensionMessage(message)
                }

                override fun onDisconnect(p: WebExtension.Port) {
                    activePorts.remove(p)
                }
            })
        }
    }

    val session: GeckoSession by lazy {
        // VIEWPORT_MODE_DESKTOP + USER_AGENT_MODE_DESKTOP memastikan tampilan Google Cloud Shell
        // adalah tampilan Desktop penuh (bukan tampilan mobile)
        val settings = GeckoSessionSettings.Builder()
            .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP)
            .userAgentOverride(AppConstants.DESKTOP_UA)
            .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_DESKTOP)
            .suspendMediaWhenInactive(true)
            .build()

        GeckoSession(settings).apply {
            setPriorityHint(GeckoSession.PRIORITY_HIGH)
            navigationDelegate = createNavigationDelegate(settings)
            permissionDelegate = createPermissionDelegate()
            promptDelegate = createPromptDelegate()
            progressDelegate = createProgressDelegate()
            open(runtime)
            registerWebExtensionBridge(this)
            loadUri(AppConstants.CLOUD_SHELL_URL)
        }
    }

    init {
        startHeartbeat()
    }

    private fun registerWebExtensionBridge(targetSession: GeckoSession) {
        runtime.webExtensionController
            .ensureBuiltIn(AppConstants.WEB_EXT_URI, AppConstants.WEB_EXT_ID)
            .accept(
                { ext ->
                    if (ext != null) {
                        ext.setMessageDelegate(
                            webExtensionMessageDelegate,
                            AppConstants.WEB_EXT_NATIVE_APP
                        )
                        targetSession.webExtensionController.setMessageDelegate(
                            ext,
                            webExtensionMessageDelegate,
                            AppConstants.WEB_EXT_NATIVE_APP
                        )
                    }
                },
                { _ -> }
            )
    }

    private fun handleExtensionMessage(message: Any) {
        val json = message as? JSONObject ?: return
        val type = json.optString("type", "")
        when (type) {
            "pinggy" -> {
                val url = json.optString("url", "")
                val extracted = PinggyUrlExtractor.extractLatestPinggyUrl(url)
                if (!extracted.isNullOrBlank()) {
                    _lastPinggyUrl.value = extracted
                    if (waitingForCopyScan) {
                        waitingForCopyScan = false
                        copyTimeoutJob?.cancel()
                        scope.launch {
                            deliverCopyToClipboard(extracted, isPinggy = true)
                        }
                    }
                }
            }
            "scan_result" -> {
                val pinggy = json.optString("pinggy", "")
                val selection = json.optString("selection", "")
                val raw = json.optString("raw", "")

                val resolvedPinggy = PinggyUrlExtractor.extractLatestPinggyUrl(pinggy)
                    ?: PinggyUrlExtractor.extractLatestPinggyUrl(selection)
                    ?: PinggyUrlExtractor.extractLatestPinggyUrl(raw)

                if (!resolvedPinggy.isNullOrBlank()) {
                    _lastPinggyUrl.value = resolvedPinggy
                }

                if (waitingForCopyScan) {
                    val targetPinggy = resolvedPinggy ?: _lastPinggyUrl.value
                    if (!targetPinggy.isNullOrBlank()) {
                        waitingForCopyScan = false
                        copyTimeoutJob?.cancel()
                        scope.launch {
                            deliverCopyToClipboard(targetPinggy, isPinggy = true)
                        }
                    } else if (selection.isNotBlank()) {
                        waitingForCopyScan = false
                        copyTimeoutJob?.cancel()
                        scope.launch {
                            deliverCopyToClipboard(selection, isPinggy = false)
                        }
                    }
                }
            }
        }
    }

    fun attachGeckoView(view: GeckoView) {
        geckoViewRef = WeakReference(view)
        val activeSession = popupSession ?: session
        if (view.session !== activeSession) {
            view.setSession(activeSession)
        }
    }

    fun sendNativeKeyEvent(
        keyCode: Int,
        ctrl: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false,
        bypassDebounce: Boolean = false
    ) {
        val now = SystemClock.uptimeMillis()
        if (!bypassDebounce && now - lastKeyTime < AppConstants.KEY_DEBOUNCE_MS) return
        lastKeyTime = now

        val view = geckoViewRef?.get() ?: return
        var metaState = 0
        if (ctrl) metaState = metaState or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (alt) metaState = metaState or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        if (shift) metaState = metaState or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON

        val downEvent = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, metaState)
        val upEvent = KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, metaState)

        view.post {
            view.dispatchKeyEvent(downEvent)
            view.dispatchKeyEvent(upEvent)
        }
    }

    private fun focusTerminalInFrames() {
        val view = geckoViewRef?.get()
        view?.post {
            view.requestFocus()
        }
        val portsSnapshot = synchronized(activePorts) { activePorts.toList() }
        if (portsSnapshot.isEmpty()) return
        val req = JSONObject().put("action", "focus_terminal")
        for (port in portsSnapshot) {
            try {
                port.postMessage(req)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Mengirimkan teks langsung melalui Hardware KeyCharacterMap ke GeckoView.
     * Metode ini 100% tembus ke terminal xterm.js di dalam iframe Cloud Shell tanpa diblokir CSP.
     */
    fun sendText(text: String) {
        if (text.isEmpty()) return
        val view = geckoViewRef?.get() ?: return
        val charMap = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        val events = charMap.getEvents(text.toCharArray())

        if (events != null && events.isNotEmpty()) {
            view.post {
                view.requestFocus()
                for (event in events) {
                    view.dispatchKeyEvent(event)
                }
            }
        }
    }

    fun pasteText(text: String) {
        sendText(text)
    }

    private suspend fun typeCommandToTerminal(command: String) {
        val view = geckoViewRef?.get() ?: return
        focusTerminalInFrames()
        delay(20L)

        val charMap = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        val chunkSize = 8
        var i = 0
        while (i < command.length) {
            val end = (i + chunkSize).coerceAtMost(command.length)
            val sub = command.substring(i, end).toCharArray()
            val events = charMap.getEvents(sub)
            if (events != null && events.isNotEmpty()) {
                view.post {
                    for (ev in events) {
                        view.dispatchKeyEvent(ev)
                    }
                }
            }
            i = end
            delay(12L)
        }
        delay(25L)
        sendNativeKeyEvent(KeyEvent.KEYCODE_ENTER, bypassDebounce = true)
    }

    fun runTerminalCommand(command: String) {
        commandJob?.cancel()
        commandJob = scope.launch {
            typeCommandToTerminal(command)
        }
    }

    fun reconnectPinggyInstant() {
        commandJob?.cancel()
        commandJob = scope.launch {
            focusTerminalInFrames()
            sendNativeKeyEvent(KeyEvent.KEYCODE_C, ctrl = true, bypassDebounce = true)
            delay(AppConstants.CTRL_C_SETTLE_DELAY_MS)
            typeCommandToTerminal(AppConstants.CMD_PINGGY_SSH)
        }
    }

    fun copyPinggyOrSelectedText() {
        val cached = _lastPinggyUrl.value
        if (!cached.isNullOrBlank()) {
            // Jika link Pinggy sudah tertangkap otomatis dari stream terminal, langsung salin detik itu juga!
            deliverCopyToClipboard(cached, isPinggy = true)
            // Tetap minta scan di background untuk berjaga-jaga ada link yang lebih baru
            requestFramesScan(silent = true)
            return
        }

        requestFramesScan(silent = false)
    }

    private fun requestFramesScan(silent: Boolean) {
        val portsSnapshot = synchronized(activePorts) { activePorts.toList() }
        if (portsSnapshot.isEmpty()) {
            if (!silent) {
                Toast.makeText(
                    appContext,
                    appContext.getString(R.string.toast_copy_empty),
                    Toast.LENGTH_SHORT
                ).show()
            }
            return
        }

        if (!silent) {
            waitingForCopyScan = true
            copyTimeoutJob?.cancel()
            copyTimeoutJob = scope.launch {
                delay(AppConstants.COPY_SCAN_TIMEOUT_MS)
                if (waitingForCopyScan) {
                    waitingForCopyScan = false
                    val fallbackUrl = _lastPinggyUrl.value
                    if (!fallbackUrl.isNullOrBlank()) {
                        deliverCopyToClipboard(fallbackUrl, isPinggy = true)
                    } else {
                        Toast.makeText(
                            appContext,
                            appContext.getString(R.string.toast_copy_empty),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }

        val req = JSONObject().put("action", "scan_copy")
        for (port in portsSnapshot) {
            try {
                port.postMessage(req)
            } catch (_: Exception) {
            }
        }
    }

    private fun deliverCopyToClipboard(text: String, isPinggy: Boolean) {
        val verified = copyToSystemClipboardVerified(text)
        if (verified) {
            val msg = if (isPinggy) {
                appContext.getString(R.string.toast_pinggy_copied, text)
            } else {
                appContext.getString(R.string.toast_text_copied)
            }
            Toast.makeText(appContext, msg, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(
                appContext,
                appContext.getString(R.string.toast_clipboard_verify_failed),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun copyToSystemClipboardVerified(text: String): Boolean {
        if (text.isEmpty()) return false
        return try {
            val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(AppConstants.CLIPBOARD_LABEL, text)
            clipboard.setPrimaryClip(clip)
            val currentClip = clipboard.primaryClip
            val readBack = if (currentClip != null && currentClip.itemCount > 0) {
                currentClip.getItemAt(0)?.text?.toString()
            } else {
                null
            }
            readBack == text
        } catch (_: Exception) {
            false
        }
    }

    fun reload() {
        session.reload()
    }

    fun loadDefaultUrl() {
        session.loadUri(AppConstants.CLOUD_SHELL_URL)
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

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (true) {
                val jitterSeconds = Random.nextLong(
                    AppConstants.HEARTBEAT_MIN_SECONDS,
                    AppConstants.HEARTBEAT_MAX_SECONDS
                )
                delay(jitterSeconds * 1000L)

                if (_isPulseActive.value) {
                    injectPulse()
                    KeepAliveService.recordHeartbeat()
                }
            }
        }
    }

    fun injectPulse() {
        sendNativeKeyEvent(KeyEvent.KEYCODE_SHIFT_LEFT, bypassDebounce = true)
    }

    fun sendTerminalKey(
        androidKeyCode: Int,
        ctrl: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false
    ) {
        if (androidKeyCode != KeyEvent.KEYCODE_UNKNOWN) {
            sendNativeKeyEvent(androidKeyCode, ctrl = ctrl, alt = alt, shift = shift)
        }
    }

    private fun createNavigationDelegate(settings: GeckoSessionSettings) =
        object : GeckoSession.NavigationDelegate {
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
            ): GeckoResult<GeckoSession> {
                popupSession?.close()
                val newSession = GeckoSession(settings)
                popupSession = newSession

                newSession.contentDelegate = object : GeckoSession.ContentDelegate {
                    override fun onCloseRequest(s: GeckoSession) {
                        closePopupAndRestoreMainSession(s)
                    }
                }

                newSession.navigationDelegate = object : GeckoSession.NavigationDelegate {
                    override fun onLocationChange(
                        s: GeckoSession,
                        url: String?,
                        p: MutableList<GeckoSession.PermissionDelegate.ContentPermission>
                    ) {
                        if (url != null && (url.contains("shell.cloud.google.com") || url.contains("close"))) {
                            closePopupAndRestoreMainSession(s)
                        }
                    }
                }

                newSession.open(runtime)

                val gv = geckoViewRef?.get()
                gv?.post {
                    try {
                        gv.releaseSession()
                        gv.setSession(newSession)
                    } catch (_: Exception) {
                    }
                }

                return GeckoResult.fromValue(newSession)
            }
        }

    private fun closePopupAndRestoreMainSession(targetPopup: GeckoSession) {
        val gv = geckoViewRef?.get()
        gv?.post {
            try {
                gv.releaseSession()
                targetPopup.close()
                if (popupSession === targetPopup) {
                    popupSession = null
                }
                gv.setSession(session)
            } catch (_: Exception) {
            }
        }
    }

    private fun createPermissionDelegate() = object : GeckoSession.PermissionDelegate {
        override fun onContentPermissionRequest(
            session: GeckoSession,
            perm: GeckoSession.PermissionDelegate.ContentPermission
        ): GeckoResult<Int> {
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

    private fun createPromptDelegate() = object : GeckoSession.PromptDelegate {
        override fun onAlertPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.AlertPrompt
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
            return GeckoResult.fromValue(prompt.dismiss())
        }

        override fun onButtonPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.ButtonPrompt
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
            return GeckoResult.fromValue(
                prompt.confirm(GeckoSession.PromptDelegate.ButtonPrompt.Type.POSITIVE)
            )
        }
    }

    private fun createProgressDelegate() = object : GeckoSession.ProgressDelegate {
        override fun onPageStart(session: GeckoSession, url: String) {
            _isLoading.value = true
            _progress.value = 10
        }

        override fun onPageStop(session: GeckoSession, success: Boolean) {
            _isLoading.value = false
            _progress.value = 100
        }

        override fun onProgressChange(session: GeckoSession, newProgress: Int) {
            _progress.value = newProgress
        }
    }

    fun destroy() {
        copyTimeoutJob?.cancel()
        commandJob?.cancel()
        heartbeatJob?.cancel()
        activePorts.clear()
        popupSession?.close()
        popupSession = null
        session.close()
    }

    companion object {
        @Volatile
        private var sharedRuntime: GeckoRuntime? = null

        fun getOrCreateRuntime(context: Context): GeckoRuntime {
            return sharedRuntime ?: synchronized(this) {
                sharedRuntime ?: run {
                    val runtimeSettings = GeckoRuntimeSettings.Builder()
                        .webManifest(false)
                        .extensionsProcessEnabled(false)
                        // Aktifkan WebAPI khusus untuk built-in WebExtension internal kita
                        .extensionsWebAPIEnabled(true)
                        .consoleOutput(false)
                        .debugLogging(false)
                        .aboutConfigEnabled(false)
                        .loginAutofillEnabled(false)
                        .inputAutoZoomEnabled(false)
                        .doubleTapZoomingEnabled(false)
                        .build()
                    GeckoRuntime.create(context.applicationContext, runtimeSettings).also {
                        sharedRuntime = it
                    }
                }
            }
        }
    }
}
