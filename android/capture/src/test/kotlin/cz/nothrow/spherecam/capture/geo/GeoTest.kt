package cz.nothrow.spherecam.capture.geo

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val EPS = 1e-9
private val Z = Vec3(0.0, 0.0, 1.0)
private val X = Vec3(1.0, 0.0, 0.0)

private fun assertVec(e: Vec3, a: Vec3, eps: Double = 1e-9) {
  assertTrue("expected $e got $a", (e - a).norm() < eps)
}

private fun randomQuat(r: Random) =
  Quat(r.nextDouble(-1.0, 1.0), r.nextDouble(-1.0, 1.0), r.nextDouble(-1.0, 1.0), r.nextDouble(-1.0, 1.0)).normalized()

/** Device pose: upright portrait, back camera looking at gw heading [a] radians on the horizon. */
private fun uprightDevice(a: Double): Quat {
  val heading = Vec3(cos(a), sin(a), 0.0)
  val devZ = -heading
  val devY = Z
  return Quat.fromMat3(Mat3.fromColumns(devY cross devZ, devY, devZ))
}

class RotationTest {
  @Test fun quatMatRoundtripAndOrthonormal() {
    val r = Random(1)
    repeat(200) {
      val q = randomQuat(r)
      val m = q.toMat3()
      assertEquals(1.0, m.det(), 1e-9)
      val id = m * m.transpose()
      for (i in 0..8) assertEquals(if (i % 4 == 0) 1.0 else 0.0, id.m[i], 1e-9)
      assertEquals(0.0, q.angleDegTo(Quat.fromMat3(m)), 1e-6)
    }
  }

  @Test fun fromMat3AllBranches() {
    for (axis in listOf(X, Vec3(0.0, 1.0, 0.0), Z)) {
      val q = Quat.fromAxisAngleDeg(axis, 170.0)
      assertEquals(0.0, q.angleDegTo(Quat.fromMat3(q.toMat3())), 1e-6)
    }
  }

  @Test fun slerp() {
    val a = Quat.fromAxisAngleDeg(Z, 10.0)
    val b = Quat.fromAxisAngleDeg(Z, 50.0)
    assertEquals(0.0, a.angleDegTo(Quat.slerp(a, b, 0.0)), 1e-6)
    assertEquals(0.0, b.angleDegTo(Quat.slerp(a, b, 1.0)), 1e-6)
    assertEquals(0.0, Quat.fromAxisAngleDeg(Z, 30.0).angleDegTo(Quat.slerp(a, b, 0.5)), 1e-6)
    val nb = Quat(-b.w, -b.x, -b.y, -b.z)
    assertEquals(0.0, Quat.fromAxisAngleDeg(Z, 30.0).angleDegTo(Quat.slerp(a, nb, 0.5)), 1e-6)
    assertEquals(40.0, a.angleDegTo(b), 1e-6)
  }
}

class FramesTest {
  @Test fun uprightPortraitCamera() {
    val gwCam = Frames.gwFromCamera(uprightDevice(0.7))
    val m = gwCam.toMat3()
    assertVec(Vec3(cos(0.7), sin(0.7), 0.0), gwCam.rotate(Z))
    assertVec(-Z, gwCam.rotate(Vec3(0.0, 1.0, 0.0)))
    val right = gwCam.rotate(X)
    assertEquals(0.0, right.z, EPS)
    assertVec(Vec3(sin(0.7), -cos(0.7), 0.0), right)
    assertEquals(1.0, m.det(), 1e-9)
  }

  @Test fun gameRotationMissingW() {
    val q = Quat.fromAxisAngleDeg(Z, 40.0)
    assertEquals(0.0, q.angleDegTo(Frames.gameRotationQuat(q.x, q.y, q.z, null)), 1e-6)
  }

  @Test fun rezeroGivesFirstTarget() {
    val gwCam = Frames.gwFromCamera(uprightDevice(2.1))
    val wFromGw = Frames.worldFromGw(gwCam)
    val world = wFromGw * gwCam
    val first = layoutTargets(50.0, 66.0, nadir = true).first()
    assertEquals("e_c0", first.id)
    assertEquals(0.0, aimError(world, first).angleDeg, 1e-6)
    assertEquals(0.0, aimError(world, first).twistDeg, 1e-6)
  }

  @Test fun leftIncreasesYawUpIncreasesPitch() {
    val world = Frames.worldFromGw(Frames.gwFromCamera(uprightDevice(0.3))) * Frames.gwFromCamera(uprightDevice(0.3))
    val left = Quat.fromAxisAngleDeg(Z, 20.0) * world
    assertEquals(20.0, Frames.yawDeg(left.rotate(Z)), 1e-6)
    val up = world * Quat.fromAxisAngleDeg(X, 15.0)
    assertEquals(15.0, Frames.pitchDeg(up.rotate(Z)), 1e-6)
    assertEquals(0.0, Frames.yawDeg(up.rotate(Z)), 1e-6)
    assertEquals(1.0, left.toMat3().det(), 1e-9)
  }

  @Test fun firstShotStraightUpFails() {
    val up = idealRotation(0.0, 90.0)
    try { Frames.worldFromGw(up); throw AssertionError("no exception") } catch (_: IllegalArgumentException) {}
  }
}

class TargetsTest {
  @Test fun layout() {
    val t = layoutTargets(50.0, 66.0, nadir = true)
    val rows = t.groupBy { it.pitchDeg }
    println("rows: " + rows.entries.joinToString { "${it.key}deg=${it.value.size}" })
    assertEquals(listOf(0.0, 45.0, 90.0, -45.0, -90.0), rows.keys.toList())
    assertEquals(11, rows[0.0]!!.size)
    assertEquals(1, rows[90.0]!!.size)
    assertEquals(2, rows[-90.0]!!.size)
    val ids = t.map { it.id }
    assertEquals(listOf(0, 11, 22, 23, 34, 35), listOf("e_c0", "u1_c0", "zenith", "d1_c0", "nadir_0", "nadir_180").map { ids.indexOf(it) })
    assertEquals(t.last().id, "nadir_180")
    assertTrue(layoutTargets(50.0, 66.0, nadir = false).none { it.id.startsWith("nadir") })
    for ((p, row) in rows) {
      if (row.size < 3) continue
      for (i in row.indices) {
        val a = row[i].rotation.rotate(Z)
        val b = row[(i + 1) % row.size].rotation.rotate(Z)
        val sep = Math.toDegrees(Math.acos(a dot b))
        assertTrue("row $p sep $sep", sep < 50.0)
      }
    }
  }

  @Test fun idealRotationConventions() {
    val r = idealRotation(0.0, 0.0)
    assertVec(Vec3(0.0, -1.0, 0.0), r.rotate(X))
    assertVec(Vec3(0.0, 0.0, -1.0), r.rotate(Vec3(0.0, 1.0, 0.0)))
    assertVec(Vec3(1.0, 0.0, 0.0), r.rotate(Z))
  }

  @Test fun idealRotationContinuousAtPoles() {
    for (yaw in listOf(0.0, 90.0, 200.0)) {
      for (sign in listOf(1.0, -1.0)) {
        val pole = idealRotation(yaw, 90.0 * sign)
        val near = idealRotation(yaw, 89.99 * sign)
        assertTrue(pole.angleDegTo(near) < 0.02)
        assertEquals(1.0, pole.toMat3().det(), 1e-9)
      }
    }
  }

  @Test fun aimErrorAndTwist() {
    val t = layoutTargets(50.0, 66.0, nadir = true).first { it.id == "u1_c3" }
    assertEquals(0.0, aimError(t.rotation, t).angleDeg, 1e-6)
    assertEquals(0.0, aimError(t.rotation, t).twistDeg, 1e-6)
    val cur = t.rotation * Quat.fromAxisAngleDeg(Z, 10.0)
    val e = aimError(cur, t)
    assertEquals(0.0, e.angleDeg, 1e-6)
    assertEquals(10.0, e.twistDeg, 1e-6)
    assertEquals(-10.0, aimError(t.rotation * Quat.fromAxisAngleDeg(Z, -10.0), t).twistDeg, 1e-6)
    val swung = t.rotation * Quat.fromAxisAngleDeg(X, 5.0)
    assertEquals(5.0, aimError(swung, t).angleDeg, 1e-6)
    val zenith = layoutTargets(50.0, 66.0, nadir = true).first { it.id == "zenith" }
    assertEquals(0.0, aimError(zenith.rotation * Quat.fromAxisAngleDeg(Z, 90.0), zenith).twistDeg, EPS)
  }

  @Test fun nearest() {
    val ts = layoutTargets(50.0, 66.0, nadir = true)
    assertEquals("e_c0", nearestUncaptured(idealRotation(3.0, 1.0), ts, emptySet())!!.id)
    assertEquals("e_c1", nearestUncaptured(idealRotation(3.0, 1.0), ts, setOf("e_c0"))!!.id)
    assertNull(nearestUncaptured(Quat.IDENTITY, ts, ts.map { it.id }.toSet()))
  }

  @Test fun autoCapture() {
    val t = layoutTargets(50.0, 66.0, nadir = false).first()
    val ms = 1_000_000L
    var ac = AutoCapture()
    assertTrue(!ac.update(0, t.rotation, 0.0, t))
    assertTrue(!ac.update(200 * ms, t.rotation, 0.0, t))
    assertTrue(ac.update(300 * ms, t.rotation, 0.0, t))
    assertTrue(!ac.update(400 * ms, t.rotation, 0.0, t))
    ac.reset()
    assertTrue(!ac.update(500 * ms, t.rotation, 0.0, t))
    assertTrue(ac.update(800 * ms, t.rotation, 0.0, t))

    ac = AutoCapture()
    assertTrue(!ac.update(0, t.rotation, 0.0, t))
    assertTrue(!ac.update(200 * ms, t.rotation, 10.0, t))
    assertTrue(!ac.update(300 * ms, t.rotation, 0.0, t))
    assertTrue(!ac.update(500 * ms, t.rotation, 0.0, t))
    assertTrue(ac.update(600 * ms, t.rotation, 0.0, t))

    ac = AutoCapture()
    val off = t.rotation * Quat.fromAxisAngleDeg(X, 3.0)
    assertTrue(!ac.update(0, off, 0.0, t))
    assertTrue(!ac.update(1000 * ms, off, 0.0, t))
  }
}

class IntrinsicsTest {
  @Test fun example() {
    val i = intrinsicsFromSensor(4.0, 6.4, 4.8, 4000, 3000, 4000, 3000, 2048, 1536)
    assertEquals(1280.0, i.fx, 1e-9)
    assertEquals(1280.0, i.fy, 1e-9)
    assertEquals(1024.0, i.cx, 0.0)
    assertEquals(768.0, i.cy, 0.0)
    val (h, v) = i.fovDeg()
    assertEquals(2 * Math.toDegrees(Math.atan(1024.0 / 1280)), h, 1e-9)
    assertTrue(h > v)
  }

  @Test fun cropWidescreen() {
    // 16:9 output from 4:3 active: full width kept, height cropped; scale = 1920/4000.
    val i = intrinsicsFromSensor(4.0, 6.4, 4.8, 4000, 3000, 4000, 3000, 1920, 1080)
    assertEquals(2500.0 * 1920 / 4000, i.fx, 1e-9)
    assertEquals(i.fx, i.fy, 1e-9)
  }

  @Test fun rotateCw() {
    val i = Intrinsics(1000.0, 1100.0, 510.0, 400.0, 1000, 800)
    val ray = Vec3(0.2, -0.1, 1.0)
    val (u, v) = project(i, Quat.IDENTITY, ray)!!
    val (u90, v90) = project(i.rotatedCw(90), Quat.IDENTITY, Vec3(-ray.y, ray.x, ray.z))!!
    assertEquals(800 - v, u90, 1e-9)
    assertEquals(u, v90, 1e-9)
    val (u180, v180) = project(i.rotatedCw(180), Quat.IDENTITY, Vec3(-ray.x, -ray.y, ray.z))!!
    assertEquals(1000 - u, u180, 1e-9)
    assertEquals(800 - v, v180, 1e-9)
    val (u270, v270) = project(i.rotatedCw(270), Quat.IDENTITY, Vec3(ray.y, -ray.x, ray.z))!!
    assertEquals(v, u270, 1e-9)
    assertEquals(1000 - u, v270, 1e-9)
    assertEquals(i, i.rotatedCw(90).rotatedCw(270))
    assertEquals(800, i.rotatedCw(90).width)
  }

  @Test fun fillCenter() {
    val i = Intrinsics(1000.0, 1000.0, 500.0, 750.0, 1000, 1500)
    val f = i.fillCenter(500, 500)
    assertEquals(500.0, f.fx, 1e-9)
    assertEquals(250.0, f.cx, 1e-9)
    assertEquals(375.0 - 125.0, f.cy, 1e-9)
    assertEquals(250.0, f.cy, 1e-9)
    val g = i.fillCenter(1000, 3000)
    assertEquals(2000.0, g.fx, 1e-9)
    assertEquals(500.0, g.cx, 1e-9)
  }

  @Test fun projectBehindAndCentre() {
    val i = Intrinsics(1000.0, 1000.0, 500.0, 400.0, 1000, 800)
    assertNull(project(i, Quat.IDENTITY, Vec3(0.0, 0.0, -1.0)))
    val p = project(i, idealRotation(30.0, 10.0), Vec3(cos(Math.toRadians(30.0)) * cos(Math.toRadians(10.0)), sin(Math.toRadians(30.0)) * cos(Math.toRadians(10.0)), sin(Math.toRadians(10.0))))!!
    assertEquals(500.0, p.first, 1e-6)
    assertEquals(400.0, p.second, 1e-6)
  }
}

class RotationBufferTest {
  private val a = Quat.fromAxisAngleDeg(Z, 0.0)
  private val b = Quat.fromAxisAngleDeg(Z, 10.0)

  @Test fun interpolateNearestNull() {
    val buf = RotationBuffer()
    assertNull(buf.rotationAt(0))
    buf.add(1_000_000_000, a)
    buf.add(1_020_000_000, b)
    val mid = buf.rotationAt(1_010_000_000)!!
    assertEquals(RotationSource.INTERPOLATED, mid.source)
    assertEquals(5.0, a.angleDegTo(mid.quat), 1e-6)
    val after = buf.rotationAt(1_050_000_000)!!
    assertEquals(RotationSource.NEAREST, after.source)
    assertEquals(0.0, b.angleDegTo(after.quat), 1e-9)
    assertEquals(RotationSource.NEAREST, buf.rotationAt(950_000_000)!!.source)
    assertNull(buf.rotationAt(1_200_000_000))
    assertNull(buf.rotationAt(800_000_000))
    assertEquals(10.0 / 0.02, buf.angularVelocityDegPerS(), 1e-6)
    assertEquals(10.0 / 0.02, buf.angularVelocityDegPerS(nowNs = 1_100_000_000), 1e-6)
    assertEquals(0.0, buf.angularVelocityDegPerS(nowNs = 1_200_000_000), 0.0)
  }

  @Test fun largeGapIsNull() {
    val buf = RotationBuffer()
    buf.add(0, a)
    buf.add(300_000_000, b)
    assertNull(buf.rotationAt(150_000_000))
  }

  @Test fun ringOverflow() {
    val buf = RotationBuffer(4)
    assertEquals(0.0, buf.angularVelocityDegPerS(), 0.0)
    for (i in 0 until 10) buf.add(i * 10_000_000L, Quat.fromAxisAngleDeg(Z, i.toDouble()))
    assertNotNull(buf.rotationAt(70_000_000))
    val s = buf.rotationAt(75_000_000)!!
    assertEquals(7.5, Quat.IDENTITY.angleDegTo(s.quat), 1e-6)
    // Oldest retained is t=60ms; t=0 is >100ms away from it.
    assertNull(buf.rotationAt(0, maxGapNs = 50_000_000L))
    assertEquals(RotationSource.NEAREST, buf.rotationAt(30_000_000)!!.source)
  }
}
