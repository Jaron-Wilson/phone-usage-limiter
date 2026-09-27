package dev.jaronwilson.modes.remote

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Reads and drives whatever app is on screen, through the accessibility service.
 *
 * This is what lets a remote caller do more than launch apps: see the current
 * screen as a list of labelled elements (or a screenshot) and then tap, type,
 * swipe and scroll on it. It is deliberately app-agnostic. Instagram is just the
 * first thing it is pointed at, and nothing here knows or cares that it is
 * Instagram.
 *
 * Every gesture is dispatched and waited on with a short latch so the HTTP reply
 * can honestly report whether it landed. All of it needs the Modes guard
 * (an [AccessibilityService]) to be on; with it off there is nothing to act.
 */
object UiControl {

    private const val GESTURE_TIMEOUT_S = 4L
    private const val SHOT_TIMEOUT_S = 4L
    private const val MAX_NODES = 200
    private const val WALK_GUARD = 4000

    /** Package of the app currently in front, or null if it cannot be read. */
    fun foreground(service: AccessibilityService): String? =
        service.rootInActiveWindow?.packageName?.toString()

    /**
     * The text and interactive elements on screen. Each entry carries a center
     * [cx,cy] the caller can pass straight back to [tap], plus text, desc, id
     * and whether it is clickable or editable.
     */
    fun dump(service: AccessibilityService): JSONArray {
        val arr = JSONArray()
        val root = service.rootInActiveWindow ?: return arr
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var guard = 0
        while (queue.isNotEmpty() && arr.length() < MAX_NODES && guard < WALK_GUARD) {
            val node = queue.removeFirst()
            guard++
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val clickable = node.isClickable
            if (text.isNotEmpty() || desc.isNotEmpty() || clickable) {
                val r = Rect()
                node.getBoundsInScreen(r)
                val o = JSONObject()
                o.put("cx", r.centerX())
                o.put("cy", r.centerY())
                if (text.isNotEmpty()) o.put("text", text.take(120))
                if (desc.isNotEmpty()) o.put("desc", desc.take(120))
                node.viewIdResourceName?.let { o.put("id", it.substringAfterLast('/')) }
                if (clickable) o.put("clickable", true)
                if (node.isEditable) o.put("editable", true)
                arr.put(o)
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return arr
    }

    /** Tap a point. */
    fun tap(service: AccessibilityService, x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return dispatch(service, path, 0, 60)
    }

    /**
     * Find the first element whose text or description contains [text]
     * (case-insensitive) and click it, walking up to the nearest clickable
     * parent so a label inside a button still works.
     */
    fun tapText(service: AccessibilityService, text: String): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val match = findNode(root) { node ->
            val hay = (node.text?.toString().orEmpty() + " " +
                node.contentDescription?.toString().orEmpty())
            hay.contains(text, ignoreCase = true)
        } ?: return false
        var target: AccessibilityNodeInfo? = match
        while (target != null && !target.isClickable) target = target.parent
        return (target ?: match).performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /** Set text into the focused field, or the first editable one found. */
    fun typeText(service: AccessibilityService, text: String): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val field = findNode(root) { it.isEditable && it.isFocused }
            ?: findNode(root) { it.isEditable }
            ?: return false
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text
            )
        }
        return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** Swipe from one point to another over [durationMs]. */
    fun swipe(
        service: AccessibilityService,
        x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 300
    ): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        return dispatch(service, path, 0, durationMs.toLong().coerceIn(50, 3000))
    }

    /** Scroll the screen up, down, left or right by a fraction of its size. */
    fun scroll(service: AccessibilityService, direction: String, amount: Float): Boolean {
        val dm = service.resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        val cx = w / 2
        val cy = h / 2
        val a = amount.coerceIn(0.1f, 0.9f)
        val dx = (w * a / 2).toInt()
        val dy = (h * a / 2).toInt()
        // To move the content one way you drag your finger the other way.
        val coords = when (direction.lowercase()) {
            "down" -> intArrayOf(cx, cy + dy, cx, cy - dy)
            "up" -> intArrayOf(cx, cy - dy, cx, cy + dy)
            "right" -> intArrayOf(cx + dx, cy, cx - dx, cy)
            "left" -> intArrayOf(cx - dx, cy, cx + dx, cy)
            else -> return false
        }
        return swipe(service, coords[0], coords[1], coords[2], coords[3], 300)
    }

    /** Press a navigation key: back, home or recents. */
    fun key(service: AccessibilityService, name: String): Boolean = when (name.lowercase()) {
        "back" -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        "home" -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        "recents" -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
        else -> false
    }

    /** A PNG screenshot as base64, or null if it could not be taken. */
    fun screenshotBase64(service: AccessibilityService): String? {
        val latch = CountDownLatch(1)
        var encoded: String? = null
        service.takeScreenshot(
            Display.DEFAULT_DISPLAY,
            service.mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    try {
                        val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        val soft = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        hw?.recycle()
                        if (soft != null) {
                            val out = ByteArrayOutputStream()
                            soft.compress(Bitmap.CompressFormat.PNG, 100, out)
                            soft.recycle()
                            encoded = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                        }
                    } finally {
                        result.hardwareBuffer.close()
                        latch.countDown()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    latch.countDown()
                }
            }
        )
        latch.await(SHOT_TIMEOUT_S, TimeUnit.SECONDS)
        return encoded
    }

    // --- internals ---

    private fun dispatch(
        service: AccessibilityService, path: Path, startMs: Long, durationMs: Long
    ): Boolean {
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, startMs, durationMs))
            .build()
        val latch = CountDownLatch(1)
        var completed = false
        val posted = service.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) {
                    completed = true
                    latch.countDown()
                }

                override fun onCancelled(d: GestureDescription?) {
                    latch.countDown()
                }
            },
            Handler(Looper.getMainLooper())
        )
        if (!posted) return false
        latch.await(GESTURE_TIMEOUT_S, TimeUnit.SECONDS)
        return completed
    }

    /** Breadth-first search for the first node matching [pred]. */
    private fun findNode(
        root: AccessibilityNodeInfo,
        pred: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var guard = 0
        while (queue.isNotEmpty() && guard < WALK_GUARD) {
            val node = queue.removeFirst()
            guard++
            if (pred(node)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return null
    }
}
