package com.orionlite.app

import android.content.Context
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlin.math.hypot

/** MediaPipe hand tracking. Exposes a cursor point (thumb/index midpoint) and pinch state. */
class HandTracker(private val ctx: Context) {
    @Volatile var visible = false
    @Volatile var x = 0.5f          // 0..1 in the upright camera image, left to right
    @Volatile var y = 0.5f          // 0..1 top to bottom
    @Volatile var pinch = false
    @Volatile var ready = false
    @Volatile var error: String? = null

    private var landmarker: HandLandmarker? = null
    @Volatile private var imgW = 640
    @Volatile private var imgH = 360
    private var lastTs = 0L

    fun init() {
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
            .setErrorListener { e -> error = e.message }
            .build()
        return HandLandmarker.createFromOptions(ctx, opts)
    }

    fun analyze(image: ImageProxy) {
        val lm = landmarker
        if (lm == null) { image.close(); return }
        try {
            val rot = image.imageInfo.rotationDegrees
            val bmp = image.toBitmap()
            if (rot % 180 == 0) { imgW = bmp.width; imgH = bmp.height } else { imgW = bmp.height; imgH = bmp.width }
            var ts = SystemClock.uptimeMillis()
            if (ts <= lastTs) ts = lastTs + 1
            lastTs = ts
            lm.detectAsync(
                BitmapImageBuilder(bmp).build(),
                ImageProcessingOptions.builder().setRotationDegrees(rot).build(),
                ts
            )
        } catch (e: Throwable) {
            error = e.message
        } finally {
            image.close()
        }
    }

    private fun onResult(r: HandLandmarkerResult) {
        val hands = r.landmarks()
        if (hands.isEmpty()) { visible = false; pinch = false; return }
        val p = hands[0]
        val w = imgW.toFloat(); val h = imgH.toFloat()
        fun d(a: Int, b: Int) = hypot((p[a].x() - p[b].x()) * w, (p[a].y() - p[b].y()) * h)
        val size = d(0, 9).coerceAtLeast(1f)
        val ratio = d(4, 8) / size
        if (!pinch && ratio < 0.28f) pinch = true
        else if (pinch && ratio > 0.42f) pinch = false
        x = (p[4].x() + p[8].x()) / 2f
        y = (p[4].y() + p[8].y()) / 2f
        visible = true
    }

    fun close() { landmarker?.close(); landmarker = null }
}
