package com.example

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.service.KeepAliveService
import com.example.ui.screen.CloudShellScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.CloudShellViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: CloudShellViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                BackHandler {
                    moveTaskToBack(true)
                }

                CloudShellScreen(
                    viewModel = viewModel,
                    onStartService = {
                        requestIgnoreBatteryOptimizations()
                        startKeepAliveService()
                    },
                    onStopService = { stopKeepAliveService() }
                )
            }
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            try {
                @SuppressLint("BatteryLife")
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (_: Exception) {
                try {
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    startActivity(intent)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun startKeepAliveService() {
        val intent = Intent(this, KeepAliveService::class.java).apply {
            action = KeepAliveService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopKeepAliveService() {
        val intent = Intent(this, KeepAliveService::class.java).apply {
            action = KeepAliveService.ACTION_STOP
        }
        startService(intent)
    }
}
