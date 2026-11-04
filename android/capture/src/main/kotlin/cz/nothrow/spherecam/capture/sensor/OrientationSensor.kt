package cz.nothrow.spherecam.capture.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import cz.nothrow.spherecam.capture.geo.Frames
import cz.nothrow.spherecam.capture.geo.Quat
import cz.nothrow.spherecam.capture.geo.RotationBuffer
import cz.nothrow.spherecam.capture.geo.Sample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

/** Gyro + accelerometer orientation (no magnetometer). Emits R_gw_from_camera. */
class OrientationSensor(context: Context) {
  private val sensorManager = context.getSystemService(SensorManager::class.java)
  private val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

  val available: Boolean get() = sensor != null

  private val lock = Any()
  private val buffer = RotationBuffer()
  private val _current = MutableStateFlow<Quat?>(null)
  val current: StateFlow<Quat?> = _current

  /**
   * sensorTimestamp - elapsedRealtimeNanos, estimated from the first event; null until then.
   * SensorEvent.timestamp is documented only as nanoseconds, and its clock base differs between devices
   * (usually elapsedRealtime, sometimes uptime), so it cannot be compared with camera timestamps blindly.
   */
  @Volatile var clockOffsetNs: Long? = null
    private set

  private var thread: HandlerThread? = null

  private val listener = object : SensorEventListener {
    override fun onSensorChanged(e: SensorEvent) {
      if (clockOffsetNs == null) {
        val d = e.timestamp - SystemClock.elapsedRealtimeNanos()
        clockOffsetNs = if (abs(d) < CLOCK_MATCH_NS) 0 else d
      }
      val v = e.values
      val q = Frames.gwFromCamera(
        Frames.gameRotationQuat(v[0].toDouble(), v[1].toDouble(), v[2].toDouble(), if (v.size > 3) v[3].toDouble() else null),
      )
      synchronized(lock) { buffer.add(e.timestamp, q) }
      _current.value = q
    }

    override fun onAccuracyChanged(s: Sensor, accuracy: Int) = Unit
  }

  fun start() {
    check(available) { "TYPE_GAME_ROTATION_VECTOR is not available on this device" }
    if (thread != null) return
    val t = HandlerThread("orientation-sensor").also { it.start() }
    thread = t
    sensorManager.registerListener(listener, sensor, SAMPLING_PERIOD_US, Handler(t.looper))
  }

  fun stop() {
    sensorManager.unregisterListener(listener)
    thread?.quitSafely()
    thread = null
    _current.value = null
  }

  /** [realtimeNs] is on the elapsedRealtimeNanos clock (what REALTIME camera timestamps use). Null if unknown or out of range. */
  fun rotationAt(realtimeNs: Long): Sample? {
    val off = clockOffsetNs ?: return null
    return synchronized(lock) { buffer.rotationAt(realtimeNs + off) }
  }

  fun angularVelocityDegPerS(): Double =
    synchronized(lock) { buffer.angularVelocityDegPerS(SystemClock.elapsedRealtimeNanos() + (clockOffsetNs ?: 0)) }

  private companion object {
    const val SAMPLING_PERIOD_US = 20_000
    const val CLOCK_MATCH_NS = 50_000_000L
  }
}
