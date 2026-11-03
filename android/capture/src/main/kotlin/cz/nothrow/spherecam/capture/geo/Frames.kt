package cz.nothrow.spherecam.capture.geo

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Coordinate conventions (all rotations are active, Hamilton quaternions, right-handed):
 *
 * - Device frame (Android): x right, y toward top of screen, z out of the screen. Natural orientation = portrait.
 * - Camera frame (OpenCV): x right, y down, z forward. The saved image is upright portrait (capture rotates
 *   the pixels), so R_device_from_camera = diag(1, -1, -1) regardless of SENSOR_ORIENTATION.
 * - Game world "gw" (TYPE_GAME_ROTATION_VECTOR): Z up against gravity, X/Y arbitrary horizontal.
 *   The sensor quaternion is R_gw_from_device.
 * - Final world: Z up, X = horizontal projection of the first shot's camera forward, Y = left of it.
 *   Output rotation is R_world_from_camera.
 * - Yaw = atan2(d.y, d.x) (counter-clockwise from above, positive toward +Y/left), pitch = asin(d.z).
 */
object Frames {
  /** 180 degrees about x equals diag(1, -1, -1). */
  val DEVICE_FROM_CAMERA = Quat(0.0, 1.0, 0.0, 0.0)

  /** w may be missing in the sensor values (SensorEvent may carry only x, y, z). */
  fun gameRotationQuat(x: Double, y: Double, z: Double, w: Double?): Quat =
    Quat(w ?: sqrt(maxOf(0.0, 1 - x * x - y * y - z * z)), x, y, z).normalized()

  fun gwFromCamera(gwFromDevice: Quat) = gwFromDevice * DEVICE_FROM_CAMERA

  /** Rz(-h) where h is the heading of the first shot; it makes that shot's forward point along +X. */
  fun worldFromGw(firstGwFromCam: Quat): Quat {
    val f = firstGwFromCam.rotate(Vec3(0.0, 0.0, 1.0))
    require(f.x * f.x + f.y * f.y > 1e-6) { "First shot points straight up/down; heading undefined" }
    return Quat.fromAxisAngleDeg(Vec3(0.0, 0.0, 1.0), -Math.toDegrees(atan2(f.y, f.x)))
  }

  fun yawDeg(d: Vec3) = Math.toDegrees(atan2(d.y, d.x))

  fun pitchDeg(d: Vec3) = Math.toDegrees(asin(d.z / d.norm()))
}
