package cz.nothrow.spherecam.capture

import kotlin.math.abs
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import cz.nothrow.spherecam.capture.camera.CaptureCamera
import cz.nothrow.spherecam.capture.camera.Locks
import cz.nothrow.spherecam.capture.geo.AimError
import cz.nothrow.spherecam.capture.geo.AutoCapture
import cz.nothrow.spherecam.capture.geo.Frames
import cz.nothrow.spherecam.capture.geo.Intrinsics
import cz.nothrow.spherecam.capture.geo.Quat
import cz.nothrow.spherecam.capture.geo.RotationSource
import cz.nothrow.spherecam.capture.geo.Sample
import cz.nothrow.spherecam.capture.geo.Target
import cz.nothrow.spherecam.capture.geo.aimError
import cz.nothrow.spherecam.capture.geo.fovDeg
import cz.nothrow.spherecam.capture.geo.layoutTargets
import cz.nothrow.spherecam.capture.geo.nearestUncaptured
import cz.nothrow.spherecam.capture.sensor.OrientationSensor
import cz.nothrow.spherecam.capture.session.AppInfo
import cz.nothrow.spherecam.capture.session.CameraInfo
import cz.nothrow.spherecam.capture.session.DeviceInfo
import cz.nothrow.spherecam.capture.session.Frame
import cz.nothrow.spherecam.capture.session.Manifest
import cz.nothrow.spherecam.capture.session.SessionStatus
import cz.nothrow.spherecam.capture.session.SessionStore
import cz.nothrow.spherecam.capture.session.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID

const val DEFAULT_OVERLAP = 0.30

/**
 * [cameraRotation] is R_world_from_camera of the live pose; before the first shot the world is provisional
 * (heading follows the phone), the first shot fixes it.
 */
data class UiState(
  val targets: List<Target> = emptyList(),
  val captured: Set<String> = emptySet(),
  val current: Target? = null,
  val cameraRotation: Quat? = null,
  val aim: AimError? = null,
  val angularVelDegPerS: Double = 0.0,
  /** All auto-capture conditions except the steady time are met. */
  val ready: Boolean = false,
  val aimOk: Boolean = false,
  val rollOk: Boolean = true,
  val count: Int = 0,
  val total: Int = 0,
  val previewIntrinsics: Intrinsics? = null,
  val busy: Boolean = false,
  val finished: Boolean = false,
  val lastError: String? = null,
)

/** Rotation for the exposure instant if the camera clock is REALTIME, else (or if unavailable) for the trigger instant. */
internal fun pickRotation(
  realtimeSource: Boolean,
  exposureNs: Long,
  triggerNs: Long,
  rotationAt: (Long) -> Sample?,
): Sample? {
  if (realtimeSource) rotationAt(exposureNs)?.let { return it }
  return rotationAt(triggerNs)?.copy(source = RotationSource.TRIGGER_TIME)
}

/**
 * Must be created after [CaptureCamera.bind]. [start] takes a main-confined scope (e.g. lifecycleScope); all
 * public methods must be called on the main thread.
 */
class CaptureController(
  private val context: Context,
  private val store: SessionStore,
  private val sensor: OrientationSensor,
  private val camera: CaptureCamera,
  private val nadir: Boolean,
  private val overlap: Double = DEFAULT_OVERLAP,
) {
  val sessionId: String = UUID.randomUUID().toString()

  private val saved = camera.savedIntrinsics
  private val targets = saved.fovDeg().let { (h, v) -> layoutTargets(h, v, overlap, nadir) }
  private val autoCapture = AutoCapture()
  private val storeDispatcher = Dispatchers.IO.limitedParallelism(1)
  private val shotTargets = ArrayList<Target>()
  private var worldFromGw: Quat? = null
  private var provisionalWorld = Quat.IDENTITY
  private var scope: CoroutineScope? = null
  private var loop: Job? = null
  private var shooting: Job? = null

  private val _state = MutableStateFlow(
    UiState(targets = targets, total = targets.size, previewIntrinsics = camera.previewIntrinsics),
  )
  val state: StateFlow<UiState> = _state.asStateFlow()

  init {
    check(targets.first().id == "e_c0") { "First target must be e_c0" }
  }

  suspend fun start(scope: CoroutineScope) {
    this.scope = scope
    sensor.start()
    withContext(storeDispatcher) { store.create(newManifest()) }
    loop = scope.launch {
      while (isActive) {
        tick()
        delay(TICK_MS)
      }
    }
  }

  private fun tick() {
    val q = sensor.current.value ?: return
    val world = worldFromGw ?: runCatching { Frames.worldFromGw(q) }.getOrDefault(provisionalWorld).also { provisionalWorld = it }
    val cam = world * q
    val captured = shotTargets.mapTo(HashSet()) { it.id }
    val target = if (shotTargets.isEmpty()) targets.first() else nearestUncaptured(cam, targets, captured)
    val aim = target?.let { aimError(cam, it) }
    val vel = sensor.angularVelocityDegPerS()
    val ready = aim != null && autoCapture.withinLimits(aim, vel)
    _state.update { it.copy(cameraRotation = cam, current = target, aim = aim, angularVelDegPerS = vel, ready = ready,
      aimOk = aim != null && aim.angleDeg <= autoCapture.maxAngleDeg,
      rollOk = aim == null || abs(aim.twistDeg) <= autoCapture.maxTwistDeg) }
    if (target != null && !_state.value.busy && !_state.value.finished &&
      autoCapture.update(SystemClock.elapsedRealtimeNanos(), cam, vel, target)
    ) {
      shooting = scope!!.launch { shoot(target) }
    }
  }

  private suspend fun shoot(target: Target) {
    _state.update { it.copy(busy = true) }
    try {
      val shot = camera.capture()
      check(shot.width == saved.width && shot.height == saved.height) {
        "Saved image ${shot.width}x${shot.height} differs from intrinsics ${saved.width}x${saved.height}"
      }
      val sample = pickRotation(camera.specs.timestampRealtime, shot.exposureTimestampNs, shot.captureTimestampNs, sensor::rotationAt)
        ?: Sample(checkNotNull(sensor.current.value) { "No orientation available" }, RotationSource.TRIGGER_TIME)
      val first = shotTargets.isEmpty()
      val world = if (first) Frames.worldFromGw(sample.quat) else worldFromGw!!
      val rWorld = (world * sample.quat).normalized().canonical()
      val index = shotTargets.size
      val frame = Frame(
        index = index,
        file = SessionStore.imageName(index),
        targetId = target.id,
        targetYawDeg = target.yawDeg,
        targetPitchDeg = target.pitchDeg,
        rotation = rWorld.toMat3().m.toList(),
        quaternion = listOf(rWorld.w, rWorld.x, rWorld.y, rWorld.z),
        exposureTimestampNs = shot.exposureTimestampNs,
        captureTimestampNs = shot.captureTimestampNs,
        rotationSource = sample.source,
        angularErrorDeg = aimError(rWorld, target).angleDeg,
      )
      withContext(storeDispatcher) { store.addFrame(sessionId, shot.jpegBytes, frame) }
      worldFromGw = world
      shotTargets += target
      vibrate()
      publishProgress()
      if (first) writeLocks(camera.lock3A())
      if (shotTargets.size == targets.size) finishNow()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      _state.update { it.copy(lastError = e.message ?: e.toString()) }
    } finally {
      autoCapture.reset()
      _state.update { it.copy(busy = false) }
    }
  }

  private fun publishProgress() = _state.update {
    it.copy(captured = shotTargets.mapTo(HashSet()) { t -> t.id }, count = shotTargets.size, lastError = null)
  }

  /** No-op while a shot is in flight or after finish. */
  fun undoLast() {
    val s = _state.value
    if (s.busy || s.finished || shotTargets.isEmpty()) return
    _state.update { it.copy(busy = true) }
    shooting = scope!!.launch {
      try {
        withContext(storeDispatcher) { store.undoLast(sessionId) }
        shotTargets.removeAt(shotTargets.lastIndex)
        if (shotTargets.isEmpty()) {
          worldFromGw = null
          camera.unlock3A()
          writeLocks(Locks())
        }
        publishProgress()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        _state.update { it.copy(lastError = e.message ?: e.toString()) }
      } finally {
        autoCapture.reset()
        _state.update { it.copy(busy = false) }
      }
    }
  }

  /** Deletes the session. */
  fun cancel() {
    stopWork()
    scope!!.launch { withContext(storeDispatcher) { store.delete(sessionId) } }
  }

  /** Stops the loop and sensor; the stored session is left as is (status `capturing`). */
  fun close() = stopWork()

  /** Marks the session captured; also called automatically when the last target is shot. */
  fun finish() {
    scope!!.launch { finishNow() }
  }

  private suspend fun finishNow() {
    if (_state.value.finished) return
    loop?.cancel()
    sensor.stop()
    // The flags at the end show whether the locks held for the whole session.
    writeLocks(camera.observedLocks())
    withContext(storeDispatcher) { store.markCaptured(sessionId) }
    _state.update { it.copy(finished = true) }
  }

  private fun stopWork() {
    loop?.cancel()
    shooting?.cancel()
    sensor.stop()
  }

  private suspend fun writeLocks(l: Locks) = withContext(storeDispatcher) {
    store.updateCamera(sessionId) { it.copy(aeLocked = l.ae, awbLocked = l.awb, afLocked = l.af) }
  }

  private fun vibrate() {
    val effect = VibrationEffect.createOneShot(VIBRATION_MS, VibrationEffect.DEFAULT_AMPLITUDE)
    if (Build.VERSION.SDK_INT >= 31) {
      context.getSystemService(VibratorManager::class.java).defaultVibrator.vibrate(effect)
    } else {
      context.getSystemService(Vibrator::class.java).vibrate(effect)
    }
  }

  private fun newManifest(): Manifest {
    val s = camera.specs
    val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
    return Manifest(
      sessionId = sessionId,
      createdAt = Instant.now().toString(),
      status = SessionStatus.CAPTURING,
      app = AppInfo(pkg.versionName ?: "", pkg.longVersionCode.toInt()),
      device = DeviceInfo(Build.MANUFACTURER, Build.MODEL, Build.VERSION.SDK_INT),
      settings = Settings(nadir = nadir, targetOverlap = overlap),
      camera = CameraInfo(
        cameraId = s.cameraId,
        imageWidth = saved.width,
        imageHeight = saved.height,
        fx = saved.fx, fy = saved.fy, cx = saved.cx, cy = saved.cy,
        sensorOrientation = s.sensorOrientation,
        focalLengthMm = s.focalLengthMm,
        sensorPhysicalSizeMm = listOf(s.physicalSizeMm.first, s.physicalSizeMm.second),
        pixelArraySize = listOf(s.pixelArray.width, s.pixelArray.height),
        activeArraySize = listOf(s.activeArray.width, s.activeArray.height),
        lensIntrinsicCalibration = s.lensIntrinsicCalibration,
        timestampSource = if (s.timestampRealtime) "REALTIME" else "UNKNOWN",
        aeLocked = false, awbLocked = false, afLocked = false,
      ),
    )
  }

  private companion object {
    const val TICK_MS = 33L
    const val VIBRATION_MS = 40L
  }
}
