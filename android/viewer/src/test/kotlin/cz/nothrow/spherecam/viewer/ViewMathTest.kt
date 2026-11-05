package cz.nothrow.spherecam.viewer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewMathTest {
  private val eps = 1e-5f

  private fun forward(r: FloatArray) = ViewMath.ray(r, 0f, 0f, 1f, 1f)

  @Test fun uvConvention() {
    assertArrayEquals(floatArrayOf(0.5f, 0.5f), ViewMath.uvOf(floatArrayOf(1f, 0f, 0f)), eps)
    assertArrayEquals(floatArrayOf(0.25f, 0.5f), ViewMath.uvOf(floatArrayOf(0f, 1f, 0f)), eps)
    assertArrayEquals(floatArrayOf(0.75f, 0.5f), ViewMath.uvOf(floatArrayOf(0f, -1f, 0f)), eps)
    assertEquals(0f, ViewMath.uvOf(floatArrayOf(0f, 0f, 1f))[1], eps)
    assertEquals(1f, ViewMath.uvOf(floatArrayOf(0f, 0f, -1f))[1], eps)
  }

  @Test fun uvWrapsBehind() {
    val u1 = ViewMath.uvOf(floatArrayOf(-1f, 1e-4f, 0f))[0]
    val u2 = ViewMath.uvOf(floatArrayOf(-1f, -1e-4f, 0f))[0]
    assertTrue(u1 < 0.001f && u2 > 0.999f)
  }

  @Test fun yawPitch() {
    assertArrayEquals(floatArrayOf(1f, 0f, 0f), forward(ViewMath.worldFromCamYawPitch(0f, 0f)), eps)
    assertArrayEquals(floatArrayOf(0f, 1f, 0f), forward(ViewMath.worldFromCamYawPitch(90f, 0f)), eps)
    assertArrayEquals(floatArrayOf(0f, 0f, 1f), forward(ViewMath.worldFromCamYawPitch(0f, 90f)), eps)
  }

  @Test fun screenRightIsNotMirrored() {
    val d = ViewMath.ray(ViewMath.worldFromCamYawPitch(0f, 0f), 0.5f, 0f, 1f, 1f)
    assertTrue(d[1] < 0)
    assertTrue(ViewMath.uvOf(d)[0] > 0.5f)
    val up = ViewMath.ray(ViewMath.worldFromCamYawPitch(0f, 0f), 0f, 0.5f, 1f, 1f)
    assertTrue(up[2] > 0)
  }

  @Test fun gyroFlatFaceUpLooksDown() {
    val r = ViewMath.worldFromCamGyro(ViewMath.IDENTITY, 0, 0f, 0f)
    assertArrayEquals(floatArrayOf(0f, 0f, -1f), forward(r), eps)
  }

  @Test fun gyroDisplayRotation90() {
    val r = ViewMath.worldFromCamGyro(ViewMath.IDENTITY, 90, 0f, 0f)
    assertArrayEquals(floatArrayOf(0f, 1f, 0f), floatArrayOf(r[0], r[3], r[6]), eps)
  }

  @Test fun gyroUprightPortraitAndYawReset() {
    // Device x -> -Y, y -> +Z, z -> -X (row-major R_gw_from_device), i.e. the back camera faces +X.
    val gwFromDevice = floatArrayOf(0f, 0f, -1f, -1f, 0f, 0f, 0f, 1f, 0f)
    val r = ViewMath.worldFromCamGyro(gwFromDevice, 0, 0f, 0f)
    assertArrayEquals(floatArrayOf(1f, 0f, 0f), forward(r), eps)
    assertEquals(0f, ViewMath.headingDeg(r), 1e-3f)

    val turned = ViewMath.mul(ViewMath.rotZ(30f), gwFromDevice)
    val h0 = ViewMath.headingDeg(ViewMath.worldFromCamGyro(turned, 0, 0f, 0f))
    assertEquals(30f, h0, 1e-3f)
    assertEquals(0f, ViewMath.headingDeg(ViewMath.worldFromCamGyro(turned, 0, h0, 0f)), 1e-3f)
    assertEquals(10f, ViewMath.headingDeg(ViewMath.worldFromCamGyro(turned, 0, h0, 10f)), 1e-3f)
  }

  @Test fun headingStraightDownUsesScreenTop() {
    // Flat face up, screen top along +Y: heading is that of the screen top.
    assertEquals(90f, ViewMath.headingDeg(ViewMath.worldFromCamGyro(ViewMath.IDENTITY, 0, 0f, 0f)), 1e-3f)
  }
}
