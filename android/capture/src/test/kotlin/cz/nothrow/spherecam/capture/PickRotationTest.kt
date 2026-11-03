package cz.nothrow.spherecam.capture

import cz.nothrow.spherecam.capture.geo.Quat
import cz.nothrow.spherecam.capture.geo.RotationSource
import cz.nothrow.spherecam.capture.geo.Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PickRotationTest {
  private val atExposure = Sample(Quat.IDENTITY, RotationSource.INTERPOLATED)
  private val atTrigger = Sample(Quat(0.0, 1.0, 0.0, 0.0), RotationSource.NEAREST)
  private val lookup = { t: Long -> mapOf(100L to atExposure, 200L to atTrigger)[t] }

  @Test fun realtimeUsesExposure() {
    assertEquals(atExposure, pickRotation(true, 100, 200, lookup))
  }

  @Test fun unknownSourceUsesTriggerLabelled() {
    assertEquals(atTrigger.copy(source = RotationSource.TRIGGER_TIME), pickRotation(false, 100, 200, lookup))
  }

  @Test fun missingExposureFallsBackToTrigger() {
    assertEquals(RotationSource.TRIGGER_TIME, pickRotation(true, 999, 200, lookup)?.source)
  }

  @Test fun nothingAvailable() {
    assertNull(pickRotation(true, 1, 2, lookup))
  }
}
