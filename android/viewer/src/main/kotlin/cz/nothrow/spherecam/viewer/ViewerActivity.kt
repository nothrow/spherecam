package cz.nothrow.spherecam.viewer

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.asin

class ViewerActivity : ComponentActivity(), SensorEventListener {
  private sealed interface Load {
    data object Loading : Load
    data class Ready(val bitmap: Bitmap, val crop: PanoCrop) : Load
    data class Error(val message: Int) : Load
  }

  private var load by mutableStateOf<Load>(Load.Loading)
  private var gyro by mutableStateOf(false)
  private var glView: GLSurfaceView? = null

  private val sensorManager by lazy { getSystemService(SensorManager::class.java) }
  private val rotationSensor by lazy { sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) }

  // Written on the main thread, read on the GL thread every frame.
  @Volatile private var gwFromDevice: FloatArray? = null
  @Volatile private var yaw0: Float? = null
  @Volatile private var yawDeg = 0f
  @Volatile private var pitchDeg = 0f
  @Volatile private var fovYDeg = 75f
  @Volatile private var displayRotationDeg = 0

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    WindowCompat.getInsetsController(window, window.decorView).apply {
      systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
      hide(WindowInsetsCompat.Type.systemBars())
    }
    gyro = rotationSensor != null
    val uri = intent.data
    setContent {
      MaterialTheme(colorScheme = darkColorScheme()) {
        LaunchedEffect(uri) { load = if (uri == null) Load.Error(R.string.viewer_open_failed) else decode(uri) }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
          when (val l = load) {
            Load.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            is Load.Error -> Text(stringResource(l.message), Modifier.align(Alignment.Center).padding(24.dp), color = Color.White)
            is Load.Ready -> Pano(l)
          }
          Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
          ) {
            FilledTonalButton(::finish) { Text(stringResource(R.string.viewer_close)) }
            if (rotationSensor != null && load is Load.Ready) {
              FilledTonalButton({ toggleGyro(!gyro) }) {
                Text(stringResource(if (gyro) R.string.viewer_gyro_off else R.string.viewer_gyro_on))
              }
            }
          }
        }
      }
    }
  }

  @Composable
  private fun Pano(ready: Load.Ready) {
    AndroidView(
      factory = { ctx ->
        GLSurfaceView(ctx).apply {
          setEGLContextClientVersion(3)
          preserveEGLContextOnPause = true
          setRenderer(PanoRenderer(ready.bitmap, ready.crop, ::currentRotation) { fovYDeg })
          glView = this
        }
      },
      modifier = Modifier.fillMaxSize(),
    )
    Box(
      Modifier.fillMaxSize().pointerInput(Unit) {
        detectTransformGestures { _, pan, zoom, _ ->
          val degPerPx = fovYDeg / size.height
          yawDeg += pan.x * degPerPx
          if (!gyro) pitchDeg = (pitchDeg + pan.y * degPerPx).coerceIn(-90f, 90f)
          fovYDeg = (fovYDeg / zoom).coerceIn(30f, 100f)
        }
      },
    )
  }

  private suspend fun decode(uri: Uri): Load = withContext(Dispatchers.IO) {
    try {
      var w = 0
      var h = 0
      val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { d, info, _ ->
        w = info.size.width
        h = info.size.height
        d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val k = maxOf(w, h).toFloat() / MAX_DECODE_PX
        if (k > 1f) d.setTargetSize((w / k).toInt(), (h / k).toInt())
      }
      val xmp = contentResolver.openInputStream(uri)?.use { GPano.readXmp(it) }
      val crop = GPano.crop(xmp, w, h)
      if (crop == null) {
        bitmap.recycle()
        Load.Error(R.string.viewer_not_360)
      } else {
        Load.Ready(bitmap, crop)
      }
    } catch (e: Exception) {
      Log.w(TAG, "Cannot open $uri", e)
      Load.Error(R.string.viewer_open_failed)
    }
  }

  private fun currentRotation(): FloatArray {
    val g = gwFromDevice
    if (!gyro || g == null) return ViewMath.worldFromCamYawPitch(yawDeg, pitchDeg)
    val y0 = yaw0 ?: ViewMath.headingDeg(ViewMath.worldFromCamGyro(g, displayRotationDeg, 0f, 0f)).also { yaw0 = it }
    return ViewMath.worldFromCamGyro(g, displayRotationDeg, y0, yawDeg)
  }

  /** Keeps the current view direction when switching, so the picture does not jump. */
  private fun toggleGyro(on: Boolean) {
    if (!on) {
      val r = currentRotation()
      yawDeg = ViewMath.headingDeg(r)
      pitchDeg = Math.toDegrees(asin(r[8].coerceIn(-1f, 1f).toDouble())).toFloat()
    }
    yaw0 = null
    gwFromDevice = null
    gyro = on
    if (on) startSensor() else sensorManager.unregisterListener(this)
  }

  private fun startSensor() {
    rotationSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
  }

  override fun onSensorChanged(e: SensorEvent) {
    displayRotationDeg = when (displayRotation()) {
      Surface.ROTATION_90 -> 90
      Surface.ROTATION_180 -> 180
      Surface.ROTATION_270 -> 270
      else -> 0
    }
    gwFromDevice = FloatArray(9).also { SensorManager.getRotationMatrixFromVector(it, e.values) }
  }

  override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

  @Suppress("DEPRECATION")
  private fun displayRotation() =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display!!.rotation else windowManager.defaultDisplay.rotation

  override fun onResume() {
    super.onResume()
    glView?.onResume()
    if (gyro) startSensor()
  }

  override fun onPause() {
    super.onPause()
    glView?.onPause()
    sensorManager.unregisterListener(this)
  }

  companion object {
    private const val TAG = "ViewerActivity"

    // ponytail: our panoramas are 6144 wide and that already matches a phone screen's pixel density at ~75° FOV;
    // larger third-party images are scaled down to keep RAM in check on the A16.
    private const val MAX_DECODE_PX = 6144

    fun intent(context: Context, uri: Uri): Intent =
      Intent(context, ViewerActivity::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
  }
}
