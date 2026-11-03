package cz.nothrow.spherecam.capture.geo

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class RotationSource {
  @SerialName("interpolated") INTERPOLATED,
  @SerialName("nearest") NEAREST,
  @SerialName("trigger-time") TRIGGER_TIME,
}

data class Sample(val quat: Quat, val source: RotationSource)

/**
 * Ring buffer of timestamped rotations; timestamps must be added in non-decreasing order.
 * Not thread-safe: confine all calls to one thread (e.g. post sensor and capture callbacks to the same handler).
 */
class RotationBuffer(private val capacity: Int = 256) {
  private val times = LongArray(capacity)
  private val quats = arrayOfNulls<Quat>(capacity)
  private var count = 0
  private var next = 0

  fun add(tNs: Long, q: Quat) {
    times[next] = tNs
    quats[next] = q
    next = (next + 1) % capacity
    if (count < capacity) count++
  }

  private fun idx(i: Int) = (next - count + i + capacity) % capacity

  fun rotationAt(tNs: Long, maxGapNs: Long = 100_000_000L): Sample? {
    if (count == 0) return null
    val first = idx(0)
    val last = idx(count - 1)
    if (count == 1 || tNs < times[first] || tNs > times[last]) {
      val i = if (count == 1 || tNs < times[first]) first else last
      return if (kotlin.math.abs(tNs - times[i]) <= maxGapNs) Sample(quats[i]!!, RotationSource.NEAREST) else null
    }
    for (k in count - 2 downTo 0) {
      val a = idx(k)
      if (times[a] > tNs) continue
      val b = idx(k + 1)
      if (times[b] - times[a] > maxGapNs) return null
      val span = times[b] - times[a]
      val t = if (span == 0L) 0.0 else (tNs - times[a]).toDouble() / span
      return Sample(Quat.slerp(quats[a]!!, quats[b]!!, t), RotationSource.INTERPOLATED)
    }
    return null
  }

  fun angularVelocityDegPerS(): Double {
    if (count < 2) return 0.0
    val a = idx(count - 2)
    val b = idx(count - 1)
    val dt = (times[b] - times[a]) / 1e9
    return if (dt <= 0) 0.0 else quats[a]!!.angleDegTo(quats[b]!!) / dt
  }
}
