package com.example.androidremote.model

data class UiNodeInfo(
    val nodeId: String,
    val text: String?,
    val contentDescription: String?,
    val className: String?,
    val bounds: RectInfo,
    val clickable: Boolean,
    val scrollable: Boolean,
    val children: List<UiNodeInfo>
)

data class RectInfo(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

data class TapRequest(val x: Float? = null, val y: Float? = null, val nodeId: String? = null)
data class SwipeRequest(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val durationMs: Long = 300
)
data class InputRequest(val text: String)
data class KeyRequest(val keyCode: String)
data class LaunchRequest(val packageName: String)
data class NotificationRequest(val title: String, val body: String)

data class DeviceInfo(
    val model: String,
    val manufacturer: String,
    val androidVersion: String,
    val sdkInt: Int,
    val batteryPercent: Int,
    val ipAddress: String,
    val port: Int
)
