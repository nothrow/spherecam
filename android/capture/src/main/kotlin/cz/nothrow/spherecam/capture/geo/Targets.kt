package cz.nothrow.spherecam.capture.geo

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** [rotation] is R_world_from_camera of the ideal shot. */
data class Target(
  val id: String,
  val yawDeg: Double,
  val pitchDeg: Double,
  val rotation: Quat,
  val twistFree: Boolean,
)

data class AimError(val angleDeg: Double, val twistDeg: Double)

private val FORWARD = Vec3(0.0, 0.0, 1.0)

/**
 * Camera x is (sin yaw, -cos yaw, 0) for every pitch, not d x Z normalised: this equals the limit at the poles,
 * so the rotation is continuous there. Looking up, image top points away from the yaw direction (as when tilting
 * up from the horizon); looking down, toward it.
 */
fun idealRotation(yawDeg: Double, pitchDeg: Double): Quat {
  val yaw = Math.toRadians(yawDeg)
  val pitch = Math.toRadians(pitchDeg)
  val z = Vec3(cos(pitch) * cos(yaw), cos(pitch) * sin(yaw), sin(pitch))
  val x = Vec3(sin(yaw), -cos(yaw), 0.0)
  return Quat.fromMat3(Mat3.fromColumns(x, z cross x, z))
}

private fun target(id: String, yaw: Double, pitch: Double, twistFree: Boolean = false) =
  Target(id, yaw, pitch, idealRotation(yaw, pitch), twistFree)

/** hfov/vfov are of the saved portrait image. Returns targets in capture order. */
fun layoutTargets(hfovDeg: Double, vfovDeg: Double, overlap: Double = 0.30, nadir: Boolean): List<Target> {
  val gaps = ceil(90 / (vfovDeg * (1 - overlap))).toInt()

  // Neighbours are farthest apart at the image edge nearest the equator, so size the row for that latitude.
  fun row(prefix: String, pitch: Double): List<Target> {
    val edge = max(0.0, abs(pitch) - vfovDeg / 2)
    val count = max(3, ceil(360 * cos(Math.toRadians(edge)) / (hfovDeg * (1 - overlap))).toInt())
    return List(count) { i -> target("${prefix}_c$i", 360.0 * i / count, pitch) }
  }

  val pitches = (1 until gaps).map { 90.0 * it / gaps }
  return row("e", 0.0) +
    pitches.flatMapIndexed { i, p -> row("u${i + 1}", p) } +
    target("zenith", 0.0, 90.0, twistFree = true) +
    pitches.flatMapIndexed { i, p -> row("d${i + 1}", -p) } +
    if (nadir) listOf(target("nadir_0", 0.0, -90.0), target("nadir_180", 180.0, -90.0)) else emptyList()
}

fun aimError(current: Quat, target: Target): AimError {
  val f1 = current.rotate(FORWARD)
  val f2 = target.rotation.rotate(FORWARD)
  val angle = Math.toDegrees(atan2((f1 cross f2).norm(), f1 dot f2))
  if (target.twistFree) return AimError(angle, 0.0)
  // Swing-twist about local z of the target: for rel = (w, v), twist angle = 2*atan2(v.z, w).
  val rel = target.rotation.conjugate() * current
  var twist = Math.toDegrees(2 * atan2(rel.z, rel.w))
  if (twist > 180) twist -= 360
  if (twist <= -180) twist += 360
  return AimError(angle, twist)
}

/**
 * Fires once when the aim has stayed within thresholds for [steadyNs]; call [reset] after a shot or target change.
 * Once the countdown runs, limits widen by [exitFactor] so tremor at the edge does not restart it.
 */
class AutoCapture(
  val maxAngleDeg: Double = 2.0,
  val maxTwistDeg: Double = 8.0,
  private val maxAngularVelDegPerS: Double = 8.0,
  private val steadyNs: Long = 300_000_000L,
  private val exitFactor: Double = 1.25,
) {
  private var steadySinceNs = -1L
  private var fired = false

  fun update(tNs: Long, current: Quat, angularVelDegPerS: Double, target: Target): Boolean {
    val k = if (steadySinceNs >= 0) exitFactor else 1.0
    if (!withinLimits(aimError(current, target), angularVelDegPerS, k)) {
      steadySinceNs = -1
      return false
    }
    if (fired) return false
    if (steadySinceNs < 0) steadySinceNs = tNs
    if (tNs - steadySinceNs < steadyNs) return false
    fired = true
    return true
  }

  fun withinLimits(e: AimError, angularVelDegPerS: Double, k: Double = 1.0) =
    e.angleDeg <= k * maxAngleDeg && abs(e.twistDeg) <= k * maxTwistDeg && angularVelDegPerS <= k * maxAngularVelDegPerS

  fun reset() {
    steadySinceNs = -1
    fired = false
  }
}

fun nearestUncaptured(current: Quat, targets: List<Target>, captured: Set<String>): Target? {
  val f = current.rotate(FORWARD)
  return targets.filter { it.id !in captured }.minByOrNull {
    atan2((f cross it.rotation.rotate(FORWARD)).norm(), f dot it.rotation.rotate(FORWARD))
  }
}
