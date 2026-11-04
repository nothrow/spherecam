package cz.nothrow.spherecam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EtaTest {
  private val w = doubleArrayOf(1.0, 3.0, 0.0, 4.0)

  @Test fun overallFraction() {
    assertEquals(0.0, overallFraction(w, 0, 0.0), 1e-9)
    assertEquals(0.125, overallFraction(w, 1, 0.0), 1e-9)
    assertEquals(0.3125, overallFraction(w, 1, 0.5), 1e-9)
    assertEquals(0.5, overallFraction(w, 2, 0.7), 1e-9)
    assertEquals(1.0, overallFraction(w, 3, 1.0), 1e-9)
  }

  @Test fun remaining() {
    assertNull(remainingSeconds(4.9, 0.5))
    assertNull(remainingSeconds(60.0, 0.04))
    assertEquals(30.0, remainingSeconds(10.0, 0.25)!!, 1e-9)
  }

  @Test fun format() {
    assertEquals("10 s", formatRemaining(3.0))
    assertEquals("40 s", formatRemaining(36.0))
    assertEquals("120 s", formatRemaining(119.0))
    assertEquals("3 min", formatRemaining(150.0))
    assertEquals("10 min", formatRemaining(600.0))
  }
}
