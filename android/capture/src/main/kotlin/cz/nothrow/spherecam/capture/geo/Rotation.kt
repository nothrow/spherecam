package cz.nothrow.spherecam.capture.geo

import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

data class Vec3(val x: Double, val y: Double, val z: Double) {
  operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
  operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
  operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
  operator fun unaryMinus() = Vec3(-x, -y, -z)
  infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
  infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
  fun norm() = sqrt(this dot this)
  fun normalized(): Vec3 = norm().let { Vec3(x / it, y / it, z / it) }
}

/** 3x3 matrix, row-major. */
class Mat3(val m: DoubleArray) {
  init { require(m.size == 9) }

  operator fun get(r: Int, c: Int) = m[r * 3 + c]

  operator fun times(v: Vec3) = Vec3(
    m[0] * v.x + m[1] * v.y + m[2] * v.z,
    m[3] * v.x + m[4] * v.y + m[5] * v.z,
    m[6] * v.x + m[7] * v.y + m[8] * v.z,
  )

  operator fun times(o: Mat3) =
    Mat3(DoubleArray(9) { i -> (0..2).sumOf { k -> this[i / 3, k] * o[k, i % 3] } })

  fun transpose() = Mat3(DoubleArray(9) { i -> this[i % 3, i / 3] })

  fun det() = m[0] * (m[4] * m[8] - m[5] * m[7]) -
    m[1] * (m[3] * m[8] - m[5] * m[6]) +
    m[2] * (m[3] * m[7] - m[4] * m[6])

  companion object {
    fun fromColumns(x: Vec3, y: Vec3, z: Vec3) =
      Mat3(doubleArrayOf(x.x, y.x, z.x, x.y, y.y, z.y, x.z, y.z, z.z))
  }
}

/** Hamilton quaternion (w, x, y, z); a unit quaternion represents a rotation. */
data class Quat(val w: Double, val x: Double, val y: Double, val z: Double) {
  operator fun times(o: Quat) = Quat(
    w * o.w - x * o.x - y * o.y - z * o.z,
    w * o.x + x * o.w + y * o.z - z * o.y,
    w * o.y - x * o.z + y * o.w + z * o.x,
    w * o.z + x * o.y - y * o.x + z * o.w,
  )

  fun conjugate() = Quat(w, -x, -y, -z)

  fun normalized(): Quat = sqrt(w * w + x * x + y * y + z * z).let { Quat(w / it, x / it, y / it, z / it) }

  /** Same rotation with w >= 0. */
  fun canonical() = if (w < 0) Quat(-w, -x, -y, -z) else this

  fun toMat3() = Mat3(
    doubleArrayOf(
      1 - 2 * (y * y + z * z), 2 * (x * y - w * z), 2 * (x * z + w * y),
      2 * (x * y + w * z), 1 - 2 * (x * x + z * z), 2 * (y * z - w * x),
      2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y),
    ),
  )

  fun rotate(v: Vec3) = toMat3() * v

  /** Rotation angle of the relative rotation, in degrees, in [0, 180]. */
  fun angleDegTo(o: Quat): Double {
    val r = conjugate() * o
    return Math.toDegrees(2 * atan2(sqrt(r.x * r.x + r.y * r.y + r.z * r.z), kotlin.math.abs(r.w)))
  }

  companion object {
    val IDENTITY = Quat(1.0, 0.0, 0.0, 0.0)

    fun fromAxisAngleDeg(axis: Vec3, deg: Double): Quat {
      val a = axis.normalized()
      val h = Math.toRadians(deg) / 2
      return Quat(cos(h), a.x * sin(h), a.y * sin(h), a.z * sin(h))
    }

    /** Shepperd's method: branches on the largest of trace/diagonal to stay numerically stable. */
    fun fromMat3(r: Mat3): Quat {
      val tr = r[0, 0] + r[1, 1] + r[2, 2]
      val q = when {
        tr >= max(r[0, 0], max(r[1, 1], r[2, 2])) -> {
          val w = sqrt(1 + tr) / 2
          Quat(w, (r[2, 1] - r[1, 2]) / (4 * w), (r[0, 2] - r[2, 0]) / (4 * w), (r[1, 0] - r[0, 1]) / (4 * w))
        }
        r[0, 0] >= r[1, 1] && r[0, 0] >= r[2, 2] -> {
          val s = 2 * sqrt(1 + r[0, 0] - r[1, 1] - r[2, 2])
          Quat((r[2, 1] - r[1, 2]) / s, s / 4, (r[0, 1] + r[1, 0]) / s, (r[0, 2] + r[2, 0]) / s)
        }
        r[1, 1] >= r[2, 2] -> {
          val s = 2 * sqrt(1 + r[1, 1] - r[0, 0] - r[2, 2])
          Quat((r[0, 2] - r[2, 0]) / s, (r[0, 1] + r[1, 0]) / s, s / 4, (r[1, 2] + r[2, 1]) / s)
        }
        else -> {
          val s = 2 * sqrt(1 + r[2, 2] - r[0, 0] - r[1, 1])
          Quat((r[1, 0] - r[0, 1]) / s, (r[0, 2] + r[2, 0]) / s, (r[1, 2] + r[2, 1]) / s, s / 4)
        }
      }
      return q.normalized().canonical()
    }

    /** Shortest-path slerp; falls back to nlerp when nearly parallel (sin(theta) ~ 0). */
    fun slerp(a: Quat, b0: Quat, t: Double): Quat {
      var dot = a.w * b0.w + a.x * b0.x + a.y * b0.y + a.z * b0.z
      val b = if (dot < 0) { dot = -dot; Quat(-b0.w, -b0.x, -b0.y, -b0.z) } else b0
      if (dot > 0.9995) {
        return Quat(
          a.w + t * (b.w - a.w), a.x + t * (b.x - a.x), a.y + t * (b.y - a.y), a.z + t * (b.z - a.z),
        ).normalized()
      }
      val theta = acos(dot)
      val s = sin(theta)
      val ka = sin((1 - t) * theta) / s
      val kb = sin(t * theta) / s
      return Quat(ka * a.w + kb * b.w, ka * a.x + kb * b.x, ka * a.y + kb * b.y, ka * a.z + kb * b.z)
    }
  }
}

