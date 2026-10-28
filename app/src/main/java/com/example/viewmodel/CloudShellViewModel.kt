package com.example.viewmodel

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import com.example.R
import com.example.data.AppConstants
import com.example.gecko.CloudShellManager
import kotlinx.coroutines.flow.StateFlow
import org.mozilla.geckoview.GeckoView

class CloudShellViewModel(application: Application) : AndroidViewModel(application) {

    val manager = CloudShellManager(application.applicationContext)

    val isLoading: StateFlow<Boolean> = manager.isLoading
    val progress: StateFlow<Int> = manager.progress
    val isPulseActive: StateFlow<Boolean> = manager.isPulseActive
    val lastPinggyUrl: StateFlow<String?> = manager.lastPinggyUrl

    fun attachGeckoView(view: GeckoView) {
        manager.attachGeckoView(view)
    }

    fun runStartScript(context: Context) {
        manager.runTerminalCommand(AppConstants.CMD_START_SCRIPT)
        Toast.makeText(
            context.applicationContext,
            context.getString(R.string.toast_running_start_script),
            Toast.LENGTH_SHORT
        ).show()
    }

    fun reconnectPinggy(context: Context) {
        manager.reconnectPinggyInstant()
        Toast.makeText(
            context.applicationContext,
            context.getString(R.string.toast_reconnecting_pinggy),
            Toast.LENGTH_SHORT
        ).show()
    }

    fun copyPinggyLink() {
        manager.copyPinggyOrSelectedText()
    }

    fun pasteFromSystemClipboard(context: Context) {
        val appCtx = context.applicationContext
        val clipboard = appCtx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        if (text.isNotEmpty()) {
            manager.pasteText(text)
            Toast.makeText(
                appCtx,
                appCtx.getString(R.string.toast_pasted_to_terminal),
                Toast.LENGTH_SHORT
            ).show()
        } else {
            Toast.makeText(
                appCtx,
                appCtx.getString(R.string.toast_clipboard_empty),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun togglePulse() {
        manager.togglePulse(!manager.isPulseActive.value)
    }

    fun reload() {
        manager.reload()
    }

    fun loadHome() {
        manager.loadDefaultUrl()
    }

    fun sendKey(keyCode: Int, ctrl: Boolean = false) {
        manager.sendTerminalKey(keyCode, ctrl = ctrl)
    }

    override fun onCleared() {
        manager.destroy()
        super.onCleared()
    }
}
