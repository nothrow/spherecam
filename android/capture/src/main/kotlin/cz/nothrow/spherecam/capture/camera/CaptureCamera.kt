package cz.nothrow.spherecam.capture.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.SystemClock
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.ResolutionInfo
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.concurrent.futures.await
import androidx.lifecycle.LifecycleOwner
import android.graphics.Rect
import android.util.Size
import cz.nothrow.spherecam.capture.geo.Intrinsics
import cz.nothrow.spherecam.capture.geo.intrinsicsFromSensor
import cz.nothrow.spherecam.capture.geo.rotatedCw
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** [jpegBytes] is upright (pixels rotated, no EXIF written, i.e. orientation 1). */
class Shot(
  val jpegBytes: ByteArray,
  val exposureTimestampNs: Long,
  val captureTimestampNs: Long,
  val width: Int,
  val height: Int,
)

/** Static sensor/lens properties in sensor (landscape) orientation. */
class CameraSpecs(
  val cameraId: String,
  val focalLengthMm: Double,
  val physicalSizeMm: Pair<Double, Double>,
  val pixelArray: Size,
  val activeArray: Size,
  val sensorOrientation: Int,
  val timestampRealtime: Boolean,
  val lensIntrinsicCalibration: List<Double>?,
)

/** Lock flags as observed in capture results, not as requested. */
data class Locks(
  val ae: Boolean = false,
  val awb: Boolean = false,
  val af: Boolean = false,
  val exposureTimeNs: Long? = null,
  val iso: Int? = null,
  val focusDistance: Float? = null,
) {
  val all get() = ae && awb && af
}

class CaptureCamera(private val context: Context) {
  private var provider: ProcessCameraProvider? = null
  private var camera: Camera? = null
  private var preview: Preview? = null
  private var imageCapture: ImageCapture? = null
  private val executor = Executors.newSingleThreadExecutor()

  lateinit var specs: CameraSpecs
    private set

  /** Portrait intrinsics of the saved JPEG. */
  lateinit var savedIntrinsics: Intrinsics
    private set

  /** Portrait intrinsics of the preview buffer, before PreviewView's FILL_CENTER crop (see Intrinsics.fillCenter). */
  lateinit var previewIntrinsics: Intrinsics
    private set

  @Volatile private var locks = Locks()

  private val resultCallback = object : CameraCaptureSession.CaptureCallback() {
    override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
      val afState = result.get(CaptureResult.CONTROL_AF_STATE)
      locks = Locks(
        ae = result.get(CaptureResult.CONTROL_AE_STATE) == CaptureResult.CONTROL_AE_STATE_LOCKED,
        awb = result.get(CaptureResult.CONTROL_AWB_STATE) == CaptureResult.CONTROL_AWB_STATE_LOCKED,
        af = result.get(CaptureResult.CONTROL_AF_MODE) == CaptureResult.CONTROL_AF_MODE_OFF ||
          afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
          afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED,
        exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
        iso = result.get(CaptureResult.SENSOR_SENSITIVITY),
        focusDistance = result.get(CaptureResult.LENS_FOCUS_DISTANCE),
      )
    }
  }

  fun observedLocks(): Locks = locks

  /** Call on the main thread. */
  suspend fun bind(owner: LifecycleOwner, surfaceProvider: Preview.SurfaceProvider) {
    val p = ProcessCameraProvider.getInstance(context).await()
    withContext(Dispatchers.Main.immediate) {
      unbind()
      provider = p
      val selector = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
        .setResolutionStrategy(
          ResolutionStrategy(Size(2048, 1536), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
        )
        .build()
      val previewBuilder = Preview.Builder()
        .setResolutionSelector(
          ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build(),
        )
        .setTargetRotation(Surface.ROTATION_0)
      Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(resultCallback)
      val pv = previewBuilder.build().also { it.setSurfaceProvider(surfaceProvider) }
      val ic = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        .setResolutionSelector(selector)
        .setTargetRotation(Surface.ROTATION_0)
        .build()
      val cam = p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, pv, ic)
      preview = pv
      imageCapture = ic
      camera = cam
      specs = readSpecs(Camera2CameraInfo.from(cam.cameraInfo))
      savedIntrinsics = intrinsics(checkNotNull(ic.resolutionInfo) { "ImageCapture has no resolutionInfo after bind" })
      previewIntrinsics = intrinsics(checkNotNull(pv.resolutionInfo) { "Preview has no resolutionInfo after bind" })
    }
  }

  /** Call on the main thread. */
  fun unbind() {
    val ic = imageCapture
    val pv = preview
    if (ic != null && pv != null) provider?.unbind(pv, ic)
    imageCapture = null
    preview = null
    camera = null
  }

  fun shutdown() {
    unbind()
    executor.shutdown()
  }

  private fun readSpecs(info: Camera2CameraInfo): CameraSpecs {
    fun <T> get(key: CameraCharacteristics.Key<T>): T? = info.getCameraCharacteristic(key)
    val phys = checkNotNull(get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE))
    val active = checkNotNull(get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE))
    return CameraSpecs(
      cameraId = info.cameraId,
      focalLengthMm = checkNotNull(get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)).min().toDouble(),
      physicalSizeMm = phys.width.toDouble() to phys.height.toDouble(),
      pixelArray = checkNotNull(get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)),
      activeArray = Size(active.width(), active.height()),
      sensorOrientation = checkNotNull(get(CameraCharacteristics.SENSOR_ORIENTATION)),
      timestampRealtime = get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
        CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME,
      lensIntrinsicCalibration = get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)?.map { it.toDouble() },
    )
  }

  private fun intrinsics(info: ResolutionInfo): Intrinsics {
    val s = specs
    val c = info.cropRect
    return intrinsicsFromSensor(
      s.focalLengthMm, s.physicalSizeMm.first, s.physicalSizeMm.second,
      s.pixelArray.width, s.pixelArray.height, s.activeArray.width, s.activeArray.height,
      c.width(), c.height(),
    ).rotatedCw(info.rotationDegrees)
  }

  private class Raw(val bytes: ByteArray, val exposureNs: Long, val rotationDegrees: Int, val crop: Rect)

  suspend fun capture(): Shot {
    val ic = checkNotNull(imageCapture) { "Camera is not bound" }
    val triggerNs = SystemClock.elapsedRealtimeNanos()
    val raw = suspendCancellableCoroutine { cont ->
      ic.takePicture(
        executor,
        object : ImageCapture.OnImageCapturedCallback() {
          override fun onCaptureSuccess(image: ImageProxy) {
            try {
              val buf = image.planes[0].buffer
              val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
              // Capture result's SENSOR_TIMESTAMP (start of exposure), in the SENSOR_INFO_TIMESTAMP_SOURCE clock.
              cont.resume(Raw(bytes, image.imageInfo.timestamp, image.imageInfo.rotationDegrees, image.cropRect))
            } catch (e: Exception) {
              cont.resumeWithException(e)
            } finally {
              image.close()
            }
          }

          override fun onError(exception: ImageCaptureException) = cont.resumeWithException(exception)
        },
      )
    }
    return withContext(Dispatchers.Default) { upright(raw, triggerNs) }
  }

  // BitmapFactory ignores EXIF orientation, so raw pixels are rotated by ImageInfo.rotationDegrees only.
  // Bitmap.compress writes no EXIF, which makes the output orientation 1.
  private fun upright(raw: Raw, triggerNs: Long): Shot {
    var bmp = BitmapFactory.decodeByteArray(raw.bytes, 0, raw.bytes.size) ?: throw IOException("JPEG decode failed")
    fun replace(next: Bitmap) {
      if (next !== bmp) bmp.recycle()
      bmp = next
    }
    val c = raw.crop
    val full = c.width() >= bmp.width && c.height() >= bmp.height
    if (!full && c.left >= 0 && c.top >= 0 && c.right <= bmp.width && c.bottom <= bmp.height) {
      replace(Bitmap.createBitmap(bmp, c.left, c.top, c.width(), c.height()))
    }
    if (raw.rotationDegrees != 0) {
      val m = Matrix().apply { postRotate(raw.rotationDegrees.toFloat()) }
      replace(Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true))
    }
    val out = ByteArrayOutputStream(raw.bytes.size)
    bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
    val shot = Shot(out.toByteArray(), raw.exposureNs, triggerNs, bmp.width, bmp.height)
    bmp.recycle()
    return shot
  }

  /**
   * Locks AE and AWB via CONTROL_AE_LOCK / CONTROL_AWB_LOCK and fixes focus with AF_MODE_OFF at the last observed
   * LENS_FOCUS_DISTANCE. Camera2CameraControl options override CameraX's own (e.g. continuous-picture AF mode).
   * Returns what the capture results report after up to ~2 s.
   */
  suspend fun lock3A(): Locks {
    val b = CaptureRequestOptions.Builder()
      .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
      .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
      .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
    locks.focusDistance?.let { b.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, it) }
    Camera2CameraControl.from(checkNotNull(camera) { "Camera is not bound" }.cameraControl)
      .addCaptureRequestOptions(b.build()).await()
    repeat(40) {
      if (locks.all) return locks
      delay(50)
    }
    return locks
  }

  suspend fun unlock3A() {
    Camera2CameraControl.from(checkNotNull(camera) { "Camera is not bound" }.cameraControl)
      .clearCaptureRequestOptions().await()
  }

  private companion object {
    const val JPEG_QUALITY = 92
  }
}
