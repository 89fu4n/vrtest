package com.orionlite.app

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.os.SystemClock
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.util.concurrent.Executors
import kotlin.math.hypot

/**
 * MediaPipe hand tracking on ARCore's CPU camera image.
 * Gives a cursor point (between thumb tip and index tip) and the pinch state.
 */
class HandTracker(private val ctx: Context) {
    @Volatile var visible = false
    @Volatile var x = 0.5f          // 0..1 in the camera image (ARCore IMAGE_NORMALIZED)
    @Volatile var y = 0.5f
    @Volatile var pinch = false
    @Volatile var size = 0.1f        // hand size in the image (wrist to middle knuckle), for push/pull
    @Volatile var fps = 0f
    @Volatile var ready = false
    @Volatile var busy = false
    @Volatile var error: String? = null

    private val exec = Executors.newSingleThreadExecutor()
    private var landmarker: HandLandmarker? = null
    private var lastTs = 0L
    private var lastSeen = 0L

    // reusable buffers
    private var yBuf = ByteArray(0); private var uBuf = ByteArray(0); private var vBuf = ByteArray(0)
    private var pixels = IntArray(0)
    private var bmp: Bitmap? = null

    fun init() = exec.execute {
        landmarker = try { create(Delegate.GPU) } catch (e: Throwable) {
            try { create(Delegate.CPU) } catch (e2: Throwable) { error = e2.message; null }
        }
        ready = landmarker != null
    }

    private fun create(d: Delegate): HandLandmarker {
        val base = BaseOptions.builder().setModelAssetPath("hand_landmarker.task").setDelegate(d).build()
        val opts = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.5f)
            .setMinHandPresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { result: HandLandmarkerResult, _ -> onResult(result) }
            .setErrorListener { e -> error = e.message; busy = false }
            .build()
        return HandLandmarker.createFromOptions(ctx, opts)
    }

    /** Copies the YUV image (fast, on the GL thread). The caller closes the image. */
    fun submit(img: Image) {
        if (!ready || busy) return
        val w = img.width; val h = img.height
        val py = img.planes[0]; val pu = img.planes[1]; val pv = img.planes[2]
        val yRow = py.rowStride; val uvRow = pu.rowStride; val uvPix = pu.pixelStride
        fun copy(src: java.nio.ByteBuffer, dst: ByteArray): ByteArray {
            val n = src.remaining()
            val out = if (dst.size >= n) dst else ByteArray(n)
            src.get(out, 0, n)
            return out
        }
        yBuf = copy(py.buffer, yBuf); uBuf = copy(pu.buffer, uBuf); vBuf = copy(pv.buffer, vBuf)
        busy = true
        exec.execute { process(w, h, yRow, uvRow, uvPix) }
    }

    private fun process(w: Int, h: Int, yRow: Int, uvRow: Int, uvPix: Int) {
        val lm = landmarker ?: run { busy = false; return }
        try {
            // half-resolution RGB is plenty for hand tracking and much faster
            val ow = w / 2; val oh = h / 2
            if (pixels.size != ow * oh) pixels = IntArray(ow * oh)
            var k = 0
            for (j in 0 until oh) {
                val sy = j * 2
                for (i in 0 until ow) {
                    val sx = i * 2
                    val yy = (yBuf[sy * yRow + sx].toInt() and 0xff)
                    val uvIdx = (sy / 2) * uvRow + (sx / 2) * uvPix
                    val u = (uBuf[uvIdx].toInt() and 0xff) - 128
                    val v = (vBuf[uvIdx].toInt() and 0xff) - 128
                    val r = (yy + 1.402f * v).toInt().coerceIn(0, 255)
                    val g = (yy - 0.344f * u - 0.714f * v).toInt().coerceIn(0, 255)
                    val b = (yy + 1.772f * u).toInt().coerceIn(0, 255)
                    pixels[k++] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            val bm = bmp?.takeIf { it.width == ow && it.height == oh }
                ?: Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888).also { bmp = it }
            bm.setPixels(pixels, 0, ow, 0, 0, ow, oh)
            var ts = SystemClock.uptimeMillis()
            if (ts <= lastTs) ts = lastTs + 1
            lastTs = ts
            lm.detectAsync(BitmapImageBuilder(bm).build(), ts)
        } catch (e: Throwable) {
            error = e.message; busy = false
        }
    }

    private fun onResult(r: HandLandmarkerResult) {
        busy = false
        val hands = r.landmarks()
        if (hands.isEmpty()) {
            // keep the last position for a moment so a brief miss doesn't drop a pinch
            if (SystemClock.uptimeMillis() - lastSeen > 250) { visible = false; pinch = false }
            return
        }
        val now = SystemClock.uptimeMillis()
        if (lastSeen > 0) fps = fps * 0.9f + 0.1f * (1000f / (now - lastSeen).coerceAtLeast(1))
        lastSeen = now
        val p = hands[0]
        val w = (bmp?.width ?: 320).toFloat(); val h = (bmp?.height ?: 240).toFloat()
        fun d(a: Int, b: Int) = hypot((p[a].x() - p[b].x()) * w, (p[a].y() - p[b].y()) * h)
        val handPx = d(0, 9).coerceAtLeast(1f)
        val ratio = d(4, 8) / handPx
        if (!pinch && ratio < 0.28f) pinch = true
        else if (pinch && ratio > 0.42f) pinch = false
        size = size * 0.7f + 0.3f * (handPx / w)
        x = (p[4].x() + p[8].x()) / 2f
        y = (p[4].y() + p[8].y()) / 2f
        visible = true
    }

    fun close() = exec.execute { landmarker?.close(); landmarker = null; ready = false }
}
