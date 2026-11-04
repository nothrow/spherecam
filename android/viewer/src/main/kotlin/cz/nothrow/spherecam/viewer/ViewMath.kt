package cz.nothrow.spherecam.viewer

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 3×3 matrices are row-major FloatArrays. World: Z up, yaw positive toward +Y (left), yaw 0 = equirect centre
 * column. Camera frame as in the manifest (OpenCV): x right, y down, z forward. The shader in [PanoRenderer]
 * repeats [ray] and [uvOf]; keep them in sync.
 */
object ViewMath {
  val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
  private val DEVICE_FROM_CAMERA = floatArrayOf(1f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, -1f)

  fun mul(a: FloatArray, b: FloatArray) = FloatArray(9) { i ->
    val r = i / 3
    val c = i % 3
    a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
  }

  fun mulVec(a: FloatArray, v: FloatArray) = FloatArray(3) { r -> a[r * 3] * v[0] + a[r * 3 + 1] * v[1] + a[r * 3 + 2] * v[2] }

  fun rotZ(deg: Float): FloatArray {
    val a = Math.toRadians(deg.toDouble())
    val c = cos(a).toFloat()
    val s = sin(a).toFloat()
    return floatArrayOf(c, -s, 0f, s, c, 0f, 0f, 0f, 1f)
  }

  /** Continuous texture coordinates; u wraps into [0, 1). */
  fun uvOf(d: FloatArray): FloatArray {
    val lon = atan2(d[1], d[0])
    val lat = atan2(d[2], hypot(d[0], d[1]))
    val u = ((PI - lon) / (2 * PI)).toFloat()
    return floatArrayOf(u - kotlin.math.floor(u), ((PI / 2 - lat) / PI).toFloat())
  }

  fun ray(worldFromCam: FloatArray, ndcX: Float, ndcY: Float, tanHalfFovY: Float, aspect: Float): FloatArray {
    val d = mulVec(worldFromCam, floatArrayOf(ndcX * tanHalfFovY * aspect, -ndcY * tanHalfFovY, 1f))
    val n = sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2])
    return floatArrayOf(d[0] / n, d[1] / n, d[2] / n)
  }

  fun worldFromCamYawPitch(yawDeg: Float, pitchDeg: Float): FloatArray {
    val y = Math.toRadians(yawDeg.toDouble())
    val p = Math.toRadians(pitchDeg.toDouble())
    val f = doubleArrayOf(cos(p) * cos(y), cos(p) * sin(y), sin(p))
    val r = doubleArrayOf(sin(y), -cos(y), 0.0)
    val d = doubleArrayOf(f[1] * r[2] - f[2] * r[1], f[2] * r[0] - f[0] * r[2], f[0] * r[1] - f[1] * r[0])
    return FloatArray(9) { i -> doubleArrayOf(r[i / 3], d[i / 3], f[i / 3])[i % 3].toFloat() }
  }

  /**
   * [gwFromDevice] from `SensorManager.getRotationMatrixFromVector`; [displayRotationDeg] is the screen rotation
   * (0/90/180/270). [yaw0Deg] is the heading captured when the viewer opened, so the first view looks at yaw 0.
   */
  fun worldFromCamGyro(gwFromDevice: FloatArray, displayRotationDeg: Int, yaw0Deg: Float, yawOffsetDeg: Float): FloatArray =
    mul(mul(rotZ(yawOffsetDeg - yaw0Deg), gwFromDevice), mul(rotZ(displayRotationDeg.toFloat()), DEVICE_FROM_CAMERA))

  /** Heading of the camera forward; looking straight up/down it falls back to the screen top. */
  fun headingDeg(worldFromCam: FloatArray): Float {
    val (x, y) = if (hypot(worldFromCam[2], worldFromCam[5]) > 0.1f) {
      worldFromCam[2] to worldFromCam[5]
    } else {
      -worldFromCam[1] to -worldFromCam[4]
    }
    return Math.toDegrees(atan2(y, x).toDouble()).toFloat()
  }
}
