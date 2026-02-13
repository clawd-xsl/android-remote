package com.example.androidremote.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.androidremote.model.RectInfo
import com.example.androidremote.model.UiNodeInfo
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RemoteAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: RemoteAccessibilityService? = null
            private set
    }

    private val nodeBoundsCache = ConcurrentHashMap<String, Rect>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
    }

    fun getUiTree(): UiNodeInfo? {
        val root = rootInActiveWindow ?: return null
        nodeBoundsCache.clear()
        return buildNode(root, "0")
    }

    private fun buildNode(node: AccessibilityNodeInfo, nodeId: String): UiNodeInfo {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        nodeBoundsCache[nodeId] = Rect(rect)

        val children = mutableListOf<UiNodeInfo>()
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            children += buildNode(child, "$nodeId.$i")
        }

        return UiNodeInfo(
            nodeId = nodeId,
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            className = node.className?.toString(),
            bounds = RectInfo(rect.left, rect.top, rect.right, rect.bottom),
            clickable = node.isClickable,
            scrollable = node.isScrollable,
            children = children
        )
    }

    fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        return dispatchGestureBlocking(gesture)
    }

    fun tapNode(nodeId: String): Boolean {
        val rect = nodeBoundsCache[nodeId] ?: return false
        return tap(rect.exactCenterX(), rect.exactCenterY())
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(50)))
            .build()
        return dispatchGestureBlocking(gesture)
    }

    fun inputText(text: String): Boolean {
        val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun performGlobalKey(keyCode: String): Boolean {
        return when (keyCode.uppercase()) {
            "HOME" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "BACK" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "RECENTS" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "NOTIFICATIONS" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            "QUICK_SETTINGS" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            else -> false
        }
    }

    private fun dispatchGestureBlocking(gesture: GestureDescription): Boolean {
        val latch = CountDownLatch(1)
        var success = false
        val handler = Handler(Looper.getMainLooper())
        val result = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                success = true
                latch.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                success = false
                latch.countDown()
            }
        }, handler)
        if (!result) return false
        latch.await(1500, TimeUnit.MILLISECONDS)
        return success
    }
}
