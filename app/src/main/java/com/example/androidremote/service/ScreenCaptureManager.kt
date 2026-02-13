package com.example.androidremote.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ScreenCaptureManager(private val context: Context) {
    private var mediaProjection: MediaProjection? = null

    fun setMediaProjection(resultCode: Int, data: android.content.Intent?) {
        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mgr.getMediaProjection(resultCode, data ?: return)
    }

    fun hasProjection(): Boolean = mediaProjection != null

    fun capturePng(): ByteArray? {
        val projection = mediaProjection ?: return null
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        var virtualDisplay: VirtualDisplay? = null
        val latch = CountDownLatch(1)
        var output: ByteArray? = null

        val handler = Handler(Looper.getMainLooper())
        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            image.use {
                val plane = image.planes[0]
                val buffer: ByteBuffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width

                val bitmap = Bitmap.createBitmap(
                    width + rowPadding / pixelStride,
                    height,
                    Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(buffer)
                val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                ByteArrayOutputStream().use { bos ->
                    cropped.compress(Bitmap.CompressFormat.PNG, 100, bos)
                    output = bos.toByteArray()
                }
                bitmap.recycle()
                cropped.recycle()
                latch.countDown()
            }
        }, handler)

        virtualDisplay = projection.createVirtualDisplay(
            "remote-capture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface,
            null,
            handler
        )

        latch.await(1200, TimeUnit.MILLISECONDS)
        virtualDisplay.release()
        imageReader.close()
        return output
    }
}
