package cz.nothrow.spherecam.capture.session

import cz.nothrow.spherecam.capture.geo.RotationSource
import java.io.File
import java.util.zip.ZipFile
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionStoreTest {
  @get:Rule val tmp = TemporaryFolder()

  private fun manifest(id: String, createdAt: String = "2026-01-01T00:00:00Z") = Manifest(
    sessionId = id, createdAt = createdAt, status = SessionStatus.CAPTURING,
    app = AppInfo("1.0", 1), device = DeviceInfo("samsung", "SM-A166B", 34), settings = Settings(true),
    camera = CameraInfo(
      "0", 1536, 2048, 1500.0, 1500.0, 768.0, 1024.0, 90, 4.0, listOf(6.4, 4.8), listOf(4608, 3456),
      listOf(4608, 3456), null, timestampSource = "REALTIME", aeLocked = true, awbLocked = true, afLocked = true,
    ),
  )

  private fun frame(i: Int) = Frame(
    i, SessionStore.imageName(i), "e_c$i", 0.0, 0.0, listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0),
    listOf(1.0, 0.0, 0.0, 0.0), 100L + i, 90L + i, RotationSource.TRIGGER_TIME, 0.8,
  )

  @Test fun jsonFieldNames() {
    val m = manifest("s").copy(frames = listOf(frame(0)))
    val text = ManifestJson.encodeToString(Manifest.serializer(), m)
    val o = Json.parseToJsonElement(text).jsonObject
    assertEquals(
      setOf("schemaVersion", "sessionId", "createdAt", "status", "app", "device", "settings", "conventions", "camera", "frames"),
      o.keys,
    )
    assertEquals(
      setOf(
        "cameraId", "imageWidth", "imageHeight", "fx", "fy", "cx", "cy", "sensorOrientation", "focalLengthMm",
        "sensorPhysicalSizeMm", "pixelArraySize", "activeArraySize", "lensIntrinsicCalibration", "intrinsicsSource",
        "timestampSource", "aeLocked", "awbLocked", "afLocked",
      ),
      (o["camera"] as JsonObject).keys,
    )
    val f = (o["frames"] as kotlinx.serialization.json.JsonArray)[0].jsonObject
    assertEquals(
      setOf(
        "index", "file", "targetId", "targetYawDeg", "targetPitchDeg", "rotation", "quaternion",
        "exposureTimestampNs", "captureTimestampNs", "rotationSource", "angularErrorDeg",
      ),
      f.keys,
    )
    assertTrue(text.contains("\"status\": \"capturing\""))
    assertTrue(text.contains("\"rotationSource\": \"trigger-time\""))
    assertTrue(text.contains("\"lensIntrinsicCalibration\": null"))
    assertEquals(m, ManifestJson.decodeFromString(Manifest.serializer(), text))
  }

  @Test fun lifecycle() {
    val store = SessionStore(tmp.newFolder("sessions"))
    val dir = store.create(manifest("a"))
    store.addFrame("a", byteArrayOf(1, 2), frame(0))
    store.addFrame("a", byteArrayOf(3), frame(1))
    assertTrue(File(dir, "img_001.jpg").exists())
    assertEquals(2, store.readManifest("a").frames.size)

    store.undoLast("a")
    assertFalse(File(dir, "img_001.jpg").exists())
    assertEquals(1, store.readManifest("a").frames.size)

    store.markCaptured("a")
    assertEquals(SessionStatus.CAPTURED, store.list().single().status)

    store.delete("a")
    assertFalse(dir.exists())
    assertTrue(store.list().isEmpty())
  }

  @Test fun listSortedAndSkipsCorrupt() {
    val root = tmp.newFolder("sessions")
    val store = SessionStore(root)
    store.create(manifest("old", "2026-01-01T00:00:00Z"))
    store.create(manifest("new", "2026-02-01T00:00:00Z"))
    File(root, "broken").apply { mkdirs(); File(this, "manifest.json").writeText("{ nope") }
    File(root, "empty").mkdirs()
    assertEquals(listOf("new", "old"), store.list().map { it.sessionId })
    assertTrue(File(root, "broken").exists())
  }

  @Test fun leftoverTmpIsIgnored() {
    val root = tmp.newFolder("sessions")
    val store = SessionStore(root)
    val dir = store.create(manifest("a"))
    File(dir, "manifest.json.tmp").writeText("{ half written")
    assertEquals(listOf("a"), store.list().map { it.sessionId })
    store.addFrame("a", byteArrayOf(1), frame(0))
    assertEquals(1, store.readManifest("a").frames.size)
  }

  @Test fun zipEntries() {
    val store = SessionStore(tmp.newFolder("sessions"))
    store.create(manifest("a"))
    store.addFrame("a", ByteArray(100) { it.toByte() }, frame(0))
    val out = File(tmp.root, "a.zip")
    store.exportZip("a", out)
    ZipFile(out).use { z ->
      assertEquals(setOf("a/manifest.json", "a/img_000.jpg"), z.entries().toList().map { it.name }.toSet())
      assertEquals(java.util.zip.ZipEntry.STORED, z.getEntry("a/img_000.jpg").method)
      assertEquals(java.util.zip.ZipEntry.DEFLATED, z.getEntry("a/manifest.json").method)
      assertEquals(100, z.getInputStream(z.getEntry("a/img_000.jpg")).readBytes().size)
    }
  }
}
