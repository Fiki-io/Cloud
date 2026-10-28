package com.example.gecko

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
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
import java.lang.ref.WeakReference
import kotlin.random.Random

class CloudShellManager(context: Context) {

    private val appContext: Context = context.applicationContext
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var heartbeatJob: Job? = null
    private var commandJob: Job? = null
    private var injectJob: Job? = null
    private var lastKeyTime = 0L

    @Volatile
    private var pendingCommandText: String? = null

    @Volatile
    private var pendingCommandAppendEnter: Boolean = false

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

    val session: GeckoSession by lazy {
        val settings = GeckoSessionSettings.Builder()
            .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP)
            .userAgentOverride(AppConstants.DESKTOP_UA)
            .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
            .suspendMediaWhenInactive(true)
            .build()

        GeckoSession(settings).apply {
            setPriorityHint(GeckoSession.PRIORITY_HIGH)
            navigationDelegate = createNavigationDelegate(settings)
            permissionDelegate = createPermissionDelegate()
            promptDelegate = createPromptDelegate()
            progressDelegate = createProgressDelegate()
            open(runtime)
            loadUri(AppConstants.CLOUD_SHELL_URL)
        }
    }

    init {
        startHeartbeat()
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
            view.requestFocus()
            view.dispatchKeyEvent(downEvent)
            view.dispatchKeyEvent(upEvent)
        }
    }

    fun sendTextInstant(text: String, appendEnter: Boolean = false) {
        if (text.isEmpty()) return
        val payload = if (appendEnter) "$text\r" else text
        pendingCommandText = text
        pendingCommandAppendEnter = appendEnter

        val escapedJson = JSONObject.quote(payload)
        val script = "javascript:(function(){" +
            "try{" +
            "var txt=$escapedJson;" +
            "function findTa(w){" +
            "try{" +
            "var d=w.document;" +
            "var el=d.querySelector('textarea.xterm-helper-textarea')||d.querySelector('.xterm textarea')||d.querySelector('textarea');" +
            "if(el)return {d:d,el:el};" +
            "for(var i=0;i<w.frames.length;i++){var r=findTa(w.frames[i]);if(r)return r;}" +
            "}catch(e){}" +
            "return null;" +
            "}" +
            "var t=findTa(window);" +
            "if(t&&t.el){" +
            "t.el.focus({preventScroll:true});" +
            "var ok=false;" +
            "try{" +
            "var dt=new DataTransfer();" +
            "dt.setData('text/plain',txt);" +
            "var pe=new ClipboardEvent('paste',{clipboardData:dt,bubbles:true,cancelable:true});" +
            "t.el.dispatchEvent(pe);" +
            "ok=true;" +
            "}catch(e1){ok=false;}" +
            "if(!ok){ok=t.d.execCommand('insertText',false,txt);}" +
            "window.prompt('${AppConstants.BRIDGE_CMD_STATUS}',ok?'${AppConstants.BRIDGE_STATUS_OK}':'${AppConstants.BRIDGE_STATUS_FALLBACK}');" +
            "return;" +
            "}" +
            "}catch(e){}" +
            "window.prompt('${AppConstants.BRIDGE_CMD_STATUS}','${AppConstants.BRIDGE_STATUS_FALLBACK}');" +
            "})();void(0);"

        session.loadUri(script)
    }

    private fun executeNativePasteFallback(text: String, appendEnter: Boolean) {
        val view = geckoViewRef?.get() ?: return
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val previousClip: ClipData? = try {
            clipboard.primaryClip
        } catch (_: Exception) {
            null
        }

        scope.launch {
            try {
                clipboard.setPrimaryClip(ClipData.newPlainText(AppConstants.CLIPBOARD_LABEL, text))
                view.requestFocus()
                sendNativeKeyEvent(KeyEvent.KEYCODE_PASTE, bypassDebounce = true)

                if (appendEnter) {
                    delay(AppConstants.NATIVE_PASTE_ENTER_DELAY_MS)
                    sendNativeKeyEvent(KeyEvent.KEYCODE_ENTER, bypassDebounce = true)
                }

                delay(AppConstants.CLIPBOARD_RESTORE_DELAY_MS)
                if (previousClip != null && previousClip.itemCount > 0) {
                    val prevText = previousClip.getItemAt(0)?.text?.toString()
                    if (!prevText.isNullOrEmpty() && prevText != text) {
                        clipboard.setPrimaryClip(previousClip)
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    fun pasteText(text: String) {
        sendTextInstant(text, appendEnter = false)
    }

    fun runTerminalCommand(command: String) {
        sendTextInstant(command, appendEnter = true)
    }

    fun reconnectPinggyInstant() {
        commandJob?.cancel()
        commandJob = scope.launch {
            sendNativeKeyEvent(KeyEvent.KEYCODE_C, ctrl = true, bypassDebounce = true)
            delay(AppConstants.CTRL_C_SETTLE_DELAY_MS)
            sendTextInstant(AppConstants.CMD_PINGGY_SSH, appendEnter = true)
        }
    }

    fun copyPinggyOrSelectedText() {
        val script = "javascript:(function(){" +
            "try{" +
            "var found=window.__csLastPinggy||'';" +
            "function collectText(w){" +
            "var acc='';" +
            "try{" +
            "var d=w.document;" +
            "if(w.__csLastPinggy&&!found){found=w.__csLastPinggy;}" +
            "if(w.__csStreamTail){acc+='\\n'+w.__csStreamTail;}" +
            "var rows=d.querySelectorAll('.xterm-rows, .xterm-accessibility, .xterm-screen');" +
            "for(var r=0;r<rows.length;r++){acc+='\\n'+(rows[r].innerText||rows[r].textContent||'');}" +
            "if(d.body){acc+='\\n'+(d.body.innerText||'');}" +
            "for(var i=0;i<w.frames.length;i++){acc+='\\n'+collectText(w.frames[i]);}" +
            "}catch(e){}" +
            "return acc;" +
            "}" +
            "function collectSelection(w){" +
            "try{" +
            "var s=w.getSelection?w.getSelection().toString().trim():'';" +
            "if(s)return s;" +
            "for(var i=0;i<w.frames.length;i++){var fs=collectSelection(w.frames[i]);if(fs)return fs;}" +
            "}catch(e){}" +
            "return '';" +
            "}" +
            "var sel=collectSelection(window);" +
            "var raw=collectText(window);" +
            "var payload=JSON.stringify({sel:sel,cached:found,raw:raw.slice(-12000)});" +
            "window.prompt('${AppConstants.BRIDGE_COPY_RESULT}',payload);" +
            "}catch(e){" +
            "window.prompt('${AppConstants.BRIDGE_COPY_RESULT}','');" +
            "}" +
            "})();void(0);"

        session.loadUri(script)
    }

    private fun handleCopyResultPayload(payload: String) {
        var selectedText = ""
        var cachedPinggy = ""
        var rawTerminalText = ""

        if (payload.isNotBlank()) {
            try {
                val json = JSONObject(payload)
                selectedText = json.optString("sel", "").trim()
                cachedPinggy = json.optString("cached", "").trim()
                rawTerminalText = json.optString("raw", "")
            } catch (_: Exception) {
                rawTerminalText = payload
            }
        }

        val pinggyFromSelection = PinggyUrlExtractor.extractLatestPinggyUrl(selectedText)
        val pinggyFromRaw = PinggyUrlExtractor.extractLatestPinggyUrl(rawTerminalText)
        val pinggyFromCache = PinggyUrlExtractor.extractLatestPinggyUrl(cachedPinggy) ?: _lastPinggyUrl.value

        val resolvedPinggy = pinggyFromSelection ?: pinggyFromRaw ?: pinggyFromCache
        if (!resolvedPinggy.isNullOrBlank()) {
            _lastPinggyUrl.value = resolvedPinggy
            val verified = copyToSystemClipboardVerified(resolvedPinggy)
            if (verified) {
                Toast.makeText(
                    appContext,
                    appContext.getString(R.string.toast_pinggy_copied, resolvedPinggy),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(
                    appContext,
                    appContext.getString(R.string.toast_clipboard_verify_failed),
                    Toast.LENGTH_SHORT
                ).show()
            }
            return
        }

        if (selectedText.isNotEmpty()) {
            val verified = copyToSystemClipboardVerified(selectedText)
            if (verified) {
                Toast.makeText(
                    appContext,
                    appContext.getString(R.string.toast_text_copied),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(
                    appContext,
                    appContext.getString(R.string.toast_clipboard_verify_failed),
                    Toast.LENGTH_SHORT
                ).show()
            }
            return
        }

        Toast.makeText(
            appContext,
            appContext.getString(R.string.toast_copy_empty),
            Toast.LENGTH_SHORT
        ).show()
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

    fun injectTerminalOptimizationsAndStreamHook() {
        val script = "javascript:(function(){" +
            "try{" +
            "function installInWindow(w){" +
            "try{" +
            "var d=w.document;" +
            "if(d&&d.head){" +
            "var mvs=d.querySelectorAll('meta[name=viewport]');" +
            "var mv=mvs.length>0?mvs[0]:null;" +
            "for(var k=1;k<mvs.length;k++){mvs[k].remove();}" +
            "if(!mv){mv=d.createElement('meta');mv.name='viewport';d.head.appendChild(mv);}" +
            "var targetVp='width=device-width, initial-scale=1.0, minimum-scale=1.0, maximum-scale=1.0, user-scalable=no';" +
            "if(mv.content!==targetVp){mv.content=targetVp;}" +
            "if(!d.getElementById('__cs_style')){" +
            "var st=d.createElement('style');st.id='__cs_style';" +
            "st.textContent='html, body { max-width: 100vw !important; overflow-x: hidden !important; -webkit-text-size-adjust: 100% !important; text-size-adjust: 100% !important; touch-action: pan-x pan-y !important; } *, .xterm, .xterm-rows, .xterm-screen { -webkit-user-select: text !important; user-select: text !important; } input, textarea, select, .xterm-helper-textarea { font-size: 16px !important; }';" +
            "d.head.appendChild(st);" +
            "}" +
            "}" +
            "if(!w.__csHookInstalled){" +
            "w.__csHookInstalled=true;" +
            "w.__csStreamTail='';" +
            "w.__csLastPinggy='';" +
            "function inspectChunk(str){" +
            "if(typeof str!=='string'||str.length===0)return;" +
            "w.__csStreamTail=(w.__csStreamTail+str).slice(-3072);" +
            "if(w.__csStreamTail.indexOf('pinggy.')!==-1){" +
            "var clean=w.__csStreamTail.replace(/\\x1b\\[[0-9;?]*[a-zA-Z]/g,'');" +
            "var m=clean.match(/tcp:\\/\\/[a-zA-Z0-9-]+(?:\\.[a-zA-Z0-9-]+)*\\.pinggy\\.(?:link|io):\\d+/gi)" +
            "||clean.match(/(?:tcp|https?):\\/\\/[a-zA-Z0-9-]+(?:\\.[a-zA-Z0-9-]+)*\\.pinggy\\.(?:link|io)(?::\\d+)?/gi);" +
            "if(m&&m.length>0){" +
            "var candidate=m[m.length-1];" +
            "if(candidate.indexOf('@')===-1&&candidate!==w.__csLastPinggy){" +
            "w.__csLastPinggy=candidate;" +
            "window.__csLastPinggy=candidate;" +
            "window.prompt('${AppConstants.BRIDGE_PINGGY_AUTO}',candidate);" +
            "}" +
            "}" +
            "}" +
            "}" +
            "if(w.TextDecoder&&w.TextDecoder.prototype){" +
            "var origDecode=w.TextDecoder.prototype.decode;" +
            "w.TextDecoder.prototype.decode=function(){" +
            "var res=origDecode.apply(this,arguments);" +
            "try{if(res&&(res.indexOf('pinggy')!==-1||w.__csStreamTail.indexOf('pinggy')!==-1)){inspectChunk(res);}" +
            "else if(res&&res.length<512){w.__csStreamTail=(w.__csStreamTail+res).slice(-3072);}" +
            "}catch(e){}" +
            "return res;" +
            "};" +
            "}" +
            "if(w.MessageEvent&&w.MessageEvent.prototype){" +
            "var desc=Object.getOwnPropertyDescriptor(w.MessageEvent.prototype,'data');" +
            "if(desc&&desc.get){" +
            "Object.defineProperty(w.MessageEvent.prototype,'data',{" +
            "get:function(){" +
            "var val=desc.get.call(this);" +
            "try{if(typeof val==='string'&&(val.indexOf('pinggy')!==-1||val.length<512)){inspectChunk(val);}}catch(e){}" +
            "return val;" +
            "}," +
            "configurable:true" +
            "});" +
            "}" +
            "}" +
            "Object.defineProperty(d,'hidden',{get:function(){return false;},configurable:true});" +
            "Object.defineProperty(d,'visibilityState',{get:function(){return 'visible';},configurable:true});" +
            "w.addEventListener('visibilitychange',function(e){e.stopImmediatePropagation();},true);" +
            "}" +
            "for(var i=0;i<w.frames.length;i++){installInWindow(w.frames[i]);}" +
            "}catch(e){}" +
            "}" +
            "installInWindow(window);" +
            "}catch(e){}" +
            "})();void(0);"

        session.loadUri(script)
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

        override fun onTextPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.TextPrompt
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
            val message = prompt.message ?: ""
            val defaultValue = prompt.defaultValue ?: ""

            when (message) {
                AppConstants.BRIDGE_CMD_STATUS -> {
                    val cmd = pendingCommandText
                    val appendEnter = pendingCommandAppendEnter
                    pendingCommandText = null
                    if (defaultValue != AppConstants.BRIDGE_STATUS_OK && !cmd.isNullOrEmpty()) {
                        executeNativePasteFallback(cmd, appendEnter)
                    }
                }
                AppConstants.BRIDGE_COPY_RESULT -> {
                    handleCopyResultPayload(defaultValue)
                }
                AppConstants.BRIDGE_PINGGY_AUTO -> {
                    val extracted = PinggyUrlExtractor.extractLatestPinggyUrl(defaultValue)
                    if (!extracted.isNullOrBlank()) {
                        _lastPinggyUrl.value = extracted
                    }
                }
            }
            return GeckoResult.fromValue(prompt.dismiss())
        }
    }

    private fun createProgressDelegate() = object : GeckoSession.ProgressDelegate {
        override fun onPageStart(session: GeckoSession, url: String) {
            injectJob?.cancel()
            _isLoading.value = true
            _progress.value = 10
        }

        override fun onPageStop(session: GeckoSession, success: Boolean) {
            _isLoading.value = false
            _progress.value = 100
            if (success) {
                // Tunggu jeda singkat setelah halaman selesai dimuat agar tidak pernah memotong
                // rantai redirect OAuth Google (shell.cloud.google.com -> accounts.google.com)
                injectJob?.cancel()
                injectJob = scope.launch {
                    delay(AppConstants.PAGE_STOP_INJECT_DELAY_MS)
                    injectTerminalOptimizationsAndStreamHook()
                }
            }
        }

        override fun onProgressChange(session: GeckoSession, newProgress: Int) {
            _progress.value = newProgress
        }
    }

    fun destroy() {
        injectJob?.cancel()
        commandJob?.cancel()
        heartbeatJob?.cancel()
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
                        .extensionsWebAPIEnabled(false)
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
