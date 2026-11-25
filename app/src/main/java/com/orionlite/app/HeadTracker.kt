package com.orionlite.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface

/**
 * Gyro head rotation, used as a backup whenever ARCore tracking pauses.
 * Produces a 3x3 row-major matrix: camera -> world (x right, y up, -z forward),
 * the same camera frame ARCore's displayOrientedPose uses.
 */
class HeadTracker(ctx: Context, private val displayRotation: () -> Int) : SensorEventListener {
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val r = FloatArray(9)
    private val r2 = FloatArray(9)

    @Volatile var matrix = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    @Volatile var hasData = false

    fun start() { sensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) } }
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
        // only relative rotation is used, so the world frame here doesn't need to match ARCore's
        matrix = r2.copyOf()
        hasData = true
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
}
