package cz.nothrow.spherecam

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import cz.nothrow.spherecam.capture.UiState
import cz.nothrow.spherecam.capture.geo.Vec3
import cz.nothrow.spherecam.capture.geo.fillCenter
import cz.nothrow.spherecam.capture.geo.project
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.tan

private val FORWARD = Vec3(0.0, 0.0, 1.0)
private val GREEN = Color(0xFF4CAF50)
private val AMBER = Color(0xFFFFC107)

class ArrowPose(val x: Float, val y: Float, val angleDeg: Float)

/**
 * [cx], [cy] are the camera-frame x/y of the target direction (R^T d); they map straight to screen right/down,
 * and stay meaningful for targets behind the camera. Angle is clockwise from "up".
 */
fun edgeArrow(cx: Double, cy: Double, w: Double, h: Double, margin: Double): ArrowPose {
  var dx = cx
  var dy = cy
  if (hypot(dx, dy) < 1e-9) { dx = 0.0; dy = 1.0 }
  val t = min(
    if (dx != 0.0) (w / 2 - margin) / abs(dx) else Double.MAX_VALUE,
    if (dy != 0.0) (h / 2 - margin) / abs(dy) else Double.MAX_VALUE,
  )
  return ArrowPose(
    (w / 2 + dx * t).toFloat(), (h / 2 + dy * t).toFloat(),
    Math.toDegrees(atan2(dx, -dy)).toFloat(),
  )
}

@Composable
fun CaptureOverlay(state: UiState, modifier: Modifier = Modifier) {
  Canvas(modifier.fillMaxSize()) {
    val w = size.width
    val h = size.height
    val center = Offset(w / 2, h / 2)
    val rot = state.cameraRotation
    val intr = state.previewIntrinsics?.fillCenter(w.toInt(), h.toInt())

    if (rot != null && intr != null) {
      val radius = (intr.fx * tan(Math.toRadians(state.aimToleranceDeg))).toFloat().coerceAtLeast(6.dp.toPx())
      for (t in state.targets) {
        val p = project(intr, rot, t.rotation.rotate(FORWARD)) ?: continue
        val o = Offset(p.first.toFloat(), p.second.toFloat())
        if (o.x < -radius || o.x > w + radius || o.y < -radius || o.y > h + radius) continue
        when {
          t.id in state.captured -> drawCircle(GREEN.copy(alpha = 0.8f), radius, o)
          t.id == state.current?.id -> drawCircle(AMBER, radius, o, style = Stroke(4.dp.toPx()))
          else -> drawCircle(Color.White.copy(alpha = 0.8f), radius, o, style = Stroke(2.dp.toPx()))
        }
      }

      state.current?.let { cur ->
        val p = project(intr, rot, cur.rotation.rotate(FORWARD))
        val onScreen = p != null && p.first in 0.0..w.toDouble() && p.second in 0.0..h.toDouble()
        if (!onScreen) {
          val c = rot.conjugate().rotate(cur.rotation.rotate(FORWARD))
          val a = edgeArrow(c.x, c.y, w.toDouble(), h.toDouble(), 36.dp.toPx().toDouble())
          val s = 20.dp.toPx()
          val tri = Path().apply {
            moveTo(a.x, a.y - s); lineTo(a.x + s * 0.8f, a.y + s * 0.8f); lineTo(a.x - s * 0.8f, a.y + s * 0.8f); close()
          }
          rotate(a.angleDeg, Offset(a.x, a.y)) { drawPath(tri, AMBER) }
        }
      }
    }

    val ringR = 28.dp.toPx()
    if (state.ready) drawCircle(GREEN.copy(alpha = 0.35f), ringR, center)
    drawCircle(if (state.aimOk) GREEN else Color.White, ringR, center, style = Stroke(3.dp.toPx()))
    val cur = state.current
    val aim = state.aim
    if (cur != null && !cur.twistFree && aim != null) {
      // Positive twist rotates the camera clockwise about its axis, so the scene (and the tick) turns counter-clockwise.
      val tick = { color: Color ->
        drawLine(color, Offset(center.x, center.y - ringR - 10.dp.toPx()), Offset(center.x, center.y - ringR + 10.dp.toPx()), 3.dp.toPx())
      }
      tick(Color.White.copy(alpha = 0.6f))
      rotate(-aim.twistDeg.toFloat(), center) { tick(if (state.rollOk) GREEN else Color.Red) }
    }
  }
}
