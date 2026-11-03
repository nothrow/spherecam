package cz.nothrow.spherecam.capture.session

import cz.nothrow.spherecam.capture.geo.RotationSource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class SessionStatus {
  @SerialName("capturing") CAPTURING,
  @SerialName("captured") CAPTURED,
  @SerialName("stitched") STITCHED,
  @SerialName("failed") FAILED,
}

@Serializable
data class Manifest(
  val schemaVersion: Int = 1,
  val sessionId: String,
  val createdAt: String,
  val status: SessionStatus,
  val app: AppInfo,
  val device: DeviceInfo,
  val settings: Settings,
  val conventions: Conventions = Conventions(),
  val camera: CameraInfo,
  val frames: List<Frame> = emptyList(),
)

@Serializable
data class AppInfo(val versionName: String, val versionCode: Int)

@Serializable
data class DeviceInfo(val manufacturer: String, val model: String, val sdkInt: Int)

@Serializable
data class Settings(val nadir: Boolean, val targetOverlap: Double = 0.30)

@Serializable
data class Conventions(
  val camera: String = "opencv",
  val world: String = "z-up, x=first-shot-heading",
  val rotation: String = "R_world_from_camera, row-major",
)

@Serializable
data class CameraInfo(
  val cameraId: String,
  val imageWidth: Int,
  val imageHeight: Int,
  val fx: Double,
  val fy: Double,
  val cx: Double,
  val cy: Double,
  val sensorOrientation: Int,
  val focalLengthMm: Double,
  val sensorPhysicalSizeMm: List<Double>,
  val pixelArraySize: List<Int>,
  val activeArraySize: List<Int>,
  val lensIntrinsicCalibration: List<Double>?,
  val intrinsicsSource: String = "sensor-physical-size",
  val timestampSource: String,
  val aeLocked: Boolean,
  val awbLocked: Boolean,
  val afLocked: Boolean,
)

@Serializable
data class Frame(
  val index: Int,
  val file: String,
  val targetId: String,
  val targetYawDeg: Double,
  val targetPitchDeg: Double,
  val rotation: List<Double>,
  val quaternion: List<Double>,
  val exposureTimestampNs: Long,
  val captureTimestampNs: Long,
  val rotationSource: RotationSource,
  val angularErrorDeg: Double,
)

val ManifestJson = Json {
  prettyPrint = true
  encodeDefaults = true
  explicitNulls = true
  ignoreUnknownKeys = true
}
