package com.example.androidremote.api

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.androidremote.R
import com.example.androidremote.model.DeviceInfo
import com.example.androidremote.model.InputRequest
import com.example.androidremote.model.KeyRequest
import com.example.androidremote.model.LaunchRequest
import com.example.androidremote.model.NotificationRequest
import com.example.androidremote.model.SwipeRequest
import com.example.androidremote.model.TapRequest
import com.example.androidremote.service.RemoteAccessibilityService
import com.example.androidremote.service.ScreenCaptureManager
import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD

class RemoteHttpServer(
    private val context: Context,
    private val screenCaptureManager: ScreenCaptureManager,
    private val portValue: Int = 8080
) : NanoHTTPD(portValue) {

    private val gson = Gson()

    override fun serve(session: IHTTPSession): Response {
        return try {
            when (session.uri) {
                "/screen" -> handleScreen()
                "/ui" -> handleUi()
                "/tap" -> handleTap(session)
                "/swipe" -> handleSwipe(session)
                "/input" -> handleInput(session)
                "/key" -> handleKey(session)
                "/launch" -> handleLaunch(session)
                "/notification" -> handleNotification(session)
                "/info" -> handleInfo()
                else -> jsonResponse(Response.Status.NOT_FOUND, mapOf("error" to "not found"))
            }
        } catch (e: Exception) {
            jsonResponse(Response.Status.INTERNAL_ERROR, mapOf("error" to (e.message ?: "unknown")))
        }
    }

    private fun handleScreen(): Response {
        if (!screenCaptureManager.hasProjection()) {
            val reason = screenCaptureManager.projectionLostReason
            val msg = if (reason != null) "MediaProjection lost: $reason" else "MediaProjection not granted"
            return jsonResponse(Response.Status.BAD_REQUEST, mapOf("error" to msg))
        }
        val bytes = screenCaptureManager.capturePng()
            ?: return jsonResponse(Response.Status.INTERNAL_ERROR, mapOf("error" to "Screen capture failed (projection exists but capture returned null)"))
        return newFixedLengthResponse(Response.Status.OK, "image/png", bytes.inputStream(), bytes.size.toLong())
    }

    private fun handleUi(): Response {
        val svc = RemoteAccessibilityService.instance
            ?: return jsonResponse(Response.Status.BAD_REQUEST, mapOf("error" to "AccessibilityService not enabled"))
        val tree = svc.getUiTree()
        return jsonResponse(Response.Status.OK, tree ?: mapOf("error" to "no active window"))
    }

    private fun handleTap(session: IHTTPSession): Response {
        if (session.method != Method.POST) return methodNotAllowed()
        val req = parseBody(session, TapRequest::class.java)
        val svc = RemoteAccessibilityService.instance
            ?: return jsonResponse(Response.Status.BAD_REQUEST, mapOf("error" to "AccessibilityService not enabled"))

        val ok = when {
            req.nodeId != null -> svc.tapNode(req.nodeId)
            req.x != null && req.y != null -> svc.tap(req.x, req.y)
            else -> false
        }
        return jsonResponse(Response.Status.OK, mapOf("success" to ok))
    }

    private fun handleSwipe(session: IHTTPSession): Response {
        if (session.method != Method.POST) return methodNotAllowed()
        val req = parseBody(session, SwipeRequest::class.java)
        val svc = RemoteAccessibilityService.instance
            ?: return jsonResponse(Response.Status.BAD_REQUEST, mapOf("error" to "AccessibilityService not enabled"))
        val ok = svc.swipe(req.x1, req.y1, req.x2, req.y2, req.durationMs)
        return jsonResponse(Response.Status.OK, mapOf("success" to ok))
    }

    private fun handleInput(session: IHTTPSession): Response {
        if (session.method != Method.POST) return methodNotAllowed()
        val req = parseBody(session, InputRequest::class.java)
        val svc = RemoteAccessibilityService.instance
            ?: return jsonResponse(Response.Status.BAD_REQUEST, mapOf("error" to "AccessibilityService not enabled"))
        val ok = svc.inputText(req.text)
        return jsonResponse(Response.Status.OK, mapOf("success" to ok))
    }

    private fun handleKey(session: IHTTPSession): Response {
        if (session.method != Method.POST) return methodNotAllowed()
        val req = parseBody(session, KeyRequest::class.java)
        val keyCode = req.keyCode ?: req.key
            ?: return jsonResponse(Response.Status.BAD_REQUEST, mapOf("error" to "Missing 'keyCode' or 'key' parameter"))
        val svc = RemoteAccessibilityService.instance
            ?: return jsonResponse(Response.Status.BAD_REQUEST, mapOf("error" to "AccessibilityService not enabled"))
        val ok = svc.performGlobalKey(keyCode)
        return jsonResponse(Response.Status.OK, mapOf("success" to ok))
    }

    private fun handleLaunch(session: IHTTPSession): Response {
        if (session.method != Method.POST) return methodNotAllowed()
        val req = parseBody(session, LaunchRequest::class.java)
        val launchIntent = context.packageManager.getLaunchIntentForPackage(req.packageName)
        val ok = if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            true
        } else false
        return jsonResponse(Response.Status.OK, mapOf("success" to ok))
    }

    private fun handleNotification(session: IHTTPSession): Response {
        if (session.method != Method.POST) return methodNotAllowed()
        val req = parseBody(session, NotificationRequest::class.java)
        val notification = NotificationCompat.Builder(context, "remote_agent_channel")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(req.title)
            .setContentText(req.body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(context).notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
        return jsonResponse(Response.Status.OK, mapOf("success" to true))
    }

    private fun handleInfo(): Response {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val battery = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val info = DeviceInfo(
            model = Build.MODEL,
            manufacturer = Build.MANUFACTURER,
            androidVersion = Build.VERSION.RELEASE ?: "",
            sdkInt = Build.VERSION.SDK_INT,
            batteryPercent = battery,
            ipAddress = getWifiIp(context),
            port = portValue
        )
        return jsonResponse(Response.Status.OK, info)
    }

    private fun getWifiIp(ctx: Context): String {
        val wifiManager = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ip = wifiManager.connectionInfo.ipAddress
        return if (ip == 0) "0.0.0.0" else "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}.${ip shr 24 and 0xff}"
    }

    private fun methodNotAllowed(): Response =
        jsonResponse(Response.Status.METHOD_NOT_ALLOWED, mapOf("error" to "method not allowed"))

    private fun <T> parseBody(session: IHTTPSession, clazz: Class<T>): T {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val body = files["postData"] ?: "{}"
        return gson.fromJson(body, clazz)
    }

    private fun jsonResponse(status: Response.Status, body: Any): Response {
        val json = gson.toJson(body)
        return newFixedLengthResponse(status, "application/json", json)
    }
}
