package com.orionlite.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface

/**
 * Gyro head tracking (3DoF). Produces a 3x3 row-major matrix that turns
 * camera-space directions into world directions (world: x right, y up, -z forward).
 */
class HeadTracker(ctx: Context, private val displayRotation: () -> Int) : SensorEventListener {
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val r = FloatArray(9)
    private val r2 = FloatArray(9)

    @Volatile var matrix = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    @Volatile var hasData = false
    val available get() = sensor != null

    fun start() { sensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) } }
    fun stop() = sm.unregisterListener(this)

    override fun onSensorChanged(e: SensorEvent) {
        val v = if (e.values.size > 4) e.values.copyOf(4) else e.values
        SensorManager.getRotationMatrixFromVector(r, v)
        when (displayRotation()) {
            Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(r, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, r2)
            Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(r, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, r2)
            Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(r, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, r2)
            else -> r.copyInto(r2)
        }
        // sensor world is East-North-Up; convert to y-up world (x=E, y=Up, z=-N)
        matrix = floatArrayOf(
            r2[0], r2[1], r2[2],
            r2[6], r2[7], r2[8],
            -r2[3], -r2[4], -r2[5]
        )
        hasData = true
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
}
