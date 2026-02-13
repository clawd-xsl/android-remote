package com.example.androidremote

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.androidremote.service.RemoteAccessibilityService
import com.example.androidremote.service.RemoteAgentForegroundService
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(this)
                }
            }
        }
    }
}

@Composable
private fun MainScreen(context: Context) {
    val accessibilityEnabled = remember { mutableStateOf(false) }
    val projectionGranted = remember { mutableStateOf(false) }
    val hasSavedProjection = remember { mutableStateOf(false) }
    val ipAddress = remember { mutableStateOf(getLocalIpAddress()) }

    val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

    val notificationPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }

    val projectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            projectionGranted.value = true
            hasSavedProjection.value = true
            val startIntent = Intent(context, RemoteAgentForegroundService::class.java).apply {
                putExtra(RemoteAgentForegroundService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(RemoteAgentForegroundService.EXTRA_RESULT_DATA, result.data)
            }
            ContextCompat.startForegroundService(context, startIntent)
        }
    }

    LaunchedEffect(Unit) {
        accessibilityEnabled.value = isAccessibilityServiceEnabled(context)
        hasSavedProjection.value = RemoteAgentForegroundService.hasSavedProjection(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Top
    ) {
        Text("Android Remote Agent", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        Text("服务状态：${if (accessibilityEnabled.value) "Accessibility 已启用" else "Accessibility 未启用"}")
        Text("截屏权限：${if (projectionGranted.value) "已授权" else "未授权"}")
        if (hasSavedProjection.value && !projectionGranted.value) {
            Text(
                "已保存截屏权限（重启时自动恢复）",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                Text(
                    "⚠️ Android 14+ 不支持权限复用，需重新授权",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        Text("IP 地址：${ipAddress.value}")
        Text("端口：8080")

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("开启 Accessibility Service")
        }

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("授予截屏权限并启动服务")
        }

        if (hasSavedProjection.value) {
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    // Start service without new projection data — it will restore from saved
                    val startIntent = Intent(context, RemoteAgentForegroundService::class.java)
                    ContextCompat.startForegroundService(context, startIntent)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("使用已保存权限启动服务")
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    RemoteAgentForegroundService.clearSavedProjectionData(context)
                    hasSavedProjection.value = false
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("清除已保存的截屏权限")
            }
        }

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                accessibilityEnabled.value = isAccessibilityServiceEnabled(context)
                hasSavedProjection.value = RemoteAgentForegroundService.hasSavedProjection(context)
                ipAddress.value = getLocalIpAddress()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("刷新状态")
        }
    }
}

private fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    val services = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
    return services.any { it.resolveInfo.serviceInfo.name == RemoteAccessibilityService::class.java.name }
}

private fun getLocalIpAddress(): String {
    return try {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
            ?.hostAddress ?: "0.0.0.0"
    } catch (_: Exception) {
        "0.0.0.0"
    }
}
