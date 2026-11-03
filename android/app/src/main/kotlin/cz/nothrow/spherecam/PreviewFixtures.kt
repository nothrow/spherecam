package cz.nothrow.spherecam

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import cz.nothrow.spherecam.capture.UiState
import cz.nothrow.spherecam.capture.geo.Intrinsics
import cz.nothrow.spherecam.capture.geo.aimError
import cz.nothrow.spherecam.capture.geo.idealRotation
import cz.nothrow.spherecam.capture.geo.layoutTargets
import cz.nothrow.spherecam.capture.session.AppInfo
import cz.nothrow.spherecam.capture.session.CameraInfo
import cz.nothrow.spherecam.capture.session.DeviceInfo
import cz.nothrow.spherecam.capture.session.Frame
import cz.nothrow.spherecam.capture.session.Manifest
import cz.nothrow.spherecam.capture.session.SessionStatus
import cz.nothrow.spherecam.capture.session.Settings
import cz.nothrow.spherecam.capture.geo.RotationSource

fun fakeManifest() = Manifest(
  sessionId = "a", createdAt = "2026-11-04T10:15:00Z", status = SessionStatus.CAPTURED,
  app = AppInfo("1.0", 1), device = DeviceInfo("Samsung", "A16", 34), settings = Settings(true),
  camera = CameraInfo(
    "0", 1536, 2048, 1500.0, 1500.0, 768.0, 1024.0, 90, 4.0, listOf(5.0, 3.7), listOf(4000, 3000),
    listOf(4000, 3000), null, timestampSource = "REALTIME", aeLocked = true, awbLocked = true, afLocked = true,
  ),
  frames = List(31) {
    Frame(it, "img_%03d.jpg".format(it), "t", 0.0, 0.0, emptyList(), emptyList(), 0, 0, RotationSource.INTERPOLATED, 0.5)
  },
)

@Preview(widthDp = 360, heightDp = 640)
@Composable
private fun OverlayPreview() {
  val targets = layoutTargets(55.0, 70.0, 0.30, true)
  val cam = idealRotation(20.0, 10.0)
  val cur = targets[3]
  Box(Modifier.fillMaxSize().background(Color.DarkGray)) {
    CaptureOverlay(
      UiState(
        targets = targets, captured = setOf(targets[0].id, targets[1].id), current = cur, cameraRotation = cam,
        aim = aimError(cam, cur), count = 2, total = targets.size,
        previewIntrinsics = Intrinsics(1100.0, 1100.0, 540.0, 720.0, 1080, 1440),
      ),
    )
  }
}
