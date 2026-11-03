package cz.nothrow.spherecam

import org.junit.Assert.assertEquals
import org.junit.Test

class EdgeArrowTest {
  @Test fun right() {
    val a = edgeArrow(1.0, 0.0, 400.0, 800.0, 40.0)
    assertEquals(360f, a.x, 1e-3f); assertEquals(400f, a.y, 1e-3f); assertEquals(90f, a.angleDeg, 1e-3f)
  }

  @Test fun up() {
    val a = edgeArrow(0.0, -1.0, 400.0, 800.0, 40.0)
    assertEquals(200f, a.x, 1e-3f); assertEquals(40f, a.y, 1e-3f); assertEquals(0f, a.angleDeg, 1e-3f)
  }

  @Test fun diagonalHitsNearestEdge() {
    val a = edgeArrow(-1.0, 1.0, 400.0, 800.0, 40.0)
    assertEquals(40f, a.x, 1e-3f); assertEquals(560f, a.y, 1e-3f); assertEquals(-135f, a.angleDeg, 1e-3f)
  }

  @Test fun directlyBehindDoesNotBlowUp() {
    val a = edgeArrow(0.0, 0.0, 400.0, 800.0, 40.0)
    assertEquals(760f, a.y, 1e-3f); assertEquals(180f, Math.abs(a.angleDeg), 1e-3f)
  }
}
