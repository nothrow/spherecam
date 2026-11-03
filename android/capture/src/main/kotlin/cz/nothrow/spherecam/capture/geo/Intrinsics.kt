package cz.nothrow.spherecam.capture.geo

import kotlin.math.atan
import kotlin.math.max

/** Pinhole intrinsics in pixels; origin at the image's top-left corner, u right, v down. */
data class Intrinsics(
  val fx: Double,
  val fy: Double,
  val cx: Double,
  val cy: Double,
  val width: Int,
  val height: Int,
)

/**
 * All inputs are in SENSOR (landscape) orientation.
 *
 * Assumptions (unverified on hardware): SENSOR_INFO_PHYSICAL_SIZE spans the whole pixel array, not the active
 * array, so pixel pitch = physW / pixelArrayW. The output is the largest centered crop of the active array
 * with the output aspect ratio, scaled to outW x outH, and the principal point is the output centre.
 */
fun intrinsicsFromSensor(
  focalMm: Double,
  physWmm: Double,
  physHmm: Double,
  pixelArrayW: Int,
  pixelArrayH: Int,
  activeW: Int,
  activeH: Int,
  outW: Int,
  outH: Int,
): Intrinsics {
  val fxActive = focalMm / (physWmm / pixelArrayW)
  val fyActive = focalMm / (physHmm / pixelArrayH)
  val outAspect = outW.toDouble() / outH
  val cropW: Double
  val cropH: Double
  if (activeW.toDouble() / activeH > outAspect) {
    cropH = activeH.toDouble(); cropW = cropH * outAspect
  } else {
    cropW = activeW.toDouble(); cropH = cropW / outAspect
  }
  return Intrinsics(fxActive * outW / cropW, fyActive * outH / cropH, outW / 2.0, outH / 2.0, outW, outH)
}

/** [deg] is the clockwise rotation applied to the image pixels (0/90/180/270). */
fun Intrinsics.rotatedCw(deg: Int): Intrinsics {
  require(deg % 90 == 0) { "deg must be a multiple of 90" }
  var r = this
  repeat(((deg % 360) + 360) % 360 / 90) {
    // Pixel (u, v) -> (height - v, u).
    r = Intrinsics(r.fy, r.fx, r.height - r.cy, r.cx, r.height, r.width)
  }
  return r
}

/** (horizontal, vertical) field of view in degrees. */
fun Intrinsics.fovDeg(): Pair<Double, Double> =
  Math.toDegrees(2 * atan(width / 2.0 / fx)) to Math.toDegrees(2 * atan(height / 2.0 / fy))

/** PreviewView FILL_CENTER: uniform scale to cover the view, centered, overflow cropped. */
fun Intrinsics.fillCenter(viewW: Int, viewH: Int): Intrinsics {
  val s = max(viewW.toDouble() / width, viewH.toDouble() / height)
  return Intrinsics(
    fx * s, fy * s,
    cx * s - (width * s - viewW) / 2,
    cy * s - (height * s - viewH) / 2,
    viewW, viewH,
  )
}

/** Pixel coordinates of a world direction, or null if it is behind the camera. */
fun project(intr: Intrinsics, rWorldFromCam: Quat, dirWorld: Vec3): Pair<Double, Double>? {
  val d = rWorldFromCam.conjugate().rotate(dirWorld)
  if (d.z <= 1e-9) return null
  return (intr.fx * d.x / d.z + intr.cx) to (intr.fy * d.y / d.z + intr.cy)
}
