package cz.nothrow.spherecam

import androidx.compose.material3.FilledTonalButton
import androidx.compose.foundation.shape.RoundedCornerShape
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import cz.nothrow.spherecam.capture.CaptureController
import cz.nothrow.spherecam.capture.UiState
import cz.nothrow.spherecam.capture.camera.CaptureCamera
import cz.nothrow.spherecam.capture.sensor.OrientationSensor
import cz.nothrow.spherecam.capture.session.SessionStore
import kotlinx.coroutines.CancellationException

private fun hasCamera(ctx: Context) =
  ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

@Composable
fun CaptureScreen(nadir: Boolean, onExit: () -> Unit, onFinished: (sessionId: String) -> Unit) {
  val ctx = LocalContext.current
  var granted by remember { mutableStateOf(hasCamera(ctx)) }
  val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
  LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }
  // Returning from app settings.
  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = hasCamera(ctx) }

  if (granted) {
    CaptureSession(nadir, onExit, onFinished)
  } else {
    BackHandler(onBack = onExit)
    Column(Modifier.safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text(stringResource(R.string.permission_needed))
      Button({ launcher.launch(Manifest.permission.CAMERA) }) { Text(stringResource(R.string.grant_permission)) }
      OutlinedButton({
        ctx.startActivity(
          Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)),
        )
      }) { Text(stringResource(R.string.open_settings)) }
      TextButton(onExit) { Text(stringResource(R.string.back)) }
    }
  }
}

@Composable
private fun CaptureSession(nadir: Boolean, onExit: () -> Unit, onFinished: (String) -> Unit) {
  val ctx = LocalContext.current
  val owner = LocalLifecycleOwner.current
  val camera = remember { CaptureCamera(ctx) }
  val sensor = remember { OrientationSensor(ctx) }
  val previewView = remember {
    PreviewView(ctx).apply {
      scaleType = PreviewView.ScaleType.FILL_CENTER
      keepScreenOn = true
    }
  }
  var controller by remember { mutableStateOf<CaptureController?>(null) }
  var error by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(Unit) {
    try {
      camera.bind(owner, previewView.surfaceProvider)
      val c = CaptureController(ctx, ctx.sessionStore(), sensor, camera, nadir)
      controller = c
      // Activity-scoped so that cancel()'s deletion still runs after this composable is gone.
      c.start(owner.lifecycleScope)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      error = e.message ?: e.toString()
    }
  }
  // Background apps stop receiving sensor events, so the yaw reference would be lost: treat as interrupted.
  LifecycleEventEffect(Lifecycle.Event.ON_STOP) { onExit() }
  DisposableEffect(Unit) {
    onDispose {
      controller?.close()
      camera.shutdown()
    }
  }

  Box(Modifier.fillMaxSize().background(Color.Black)) {
    AndroidView({ previewView }, Modifier.fillMaxSize())
    val c = controller
    if (c != null) CaptureUi(c, onExit, onFinished) else Text(error ?: stringResource(R.string.starting), Modifier.align(Alignment.Center), color = Color.White)
    if (c != null && error != null) Text(error!!, Modifier.align(Alignment.Center), color = Color.Red)
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CaptureUi(controller: CaptureController, onExit: () -> Unit, onFinished: (String) -> Unit) {
  val ctx = LocalContext.current
  val state by controller.state.collectAsState()
  var confirmCancel by remember { mutableStateOf(false) }
  var showImages by remember { mutableStateOf(false) }
  LaunchedEffect(state.finished) { if (state.finished) onFinished(controller.sessionId) }
  BackHandler { confirmCancel = true }

  CaptureOverlay(state)
  Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
    val label = Modifier.background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp)
    Text(stringResource(R.string.counter, state.count, state.total), label, color = Color.White)
    Text(stringResource(R.string.hint_rotate), label, color = Color.White)
    // ponytail: always-on tuning readout for this phase; hide behind a debug flag before release.
    state.aim?.let {
      Text("%.1f°  roll %.1f°  %.1f°/s".format(it.angleDeg, it.twistDeg, state.angularVelDegPerS), label, color = Color.White)
    }
    state.lastError?.let { Text(it, color = Color.Red) }
    Box(Modifier.weight(1f))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      FilledTonalButton({ controller.undoLast() }, Modifier.weight(1f), enabled = state.count > 0 && !state.busy) {
        Text(stringResource(R.string.undo_last))
      }
      FilledTonalButton({ showImages = true }, Modifier.weight(1f), enabled = state.count > 0) {
        Text(stringResource(R.string.captured_images))
      }
      FilledTonalButton({ confirmCancel = true }, Modifier.weight(1f)) { Text(stringResource(R.string.cancel)) }
    }
  }

  if (confirmCancel) {
    AlertDialog(
      onDismissRequest = { confirmCancel = false },
      title = { Text(stringResource(R.string.cancel_title)) },
      text = { Text(stringResource(R.string.cancel_text)) },
      confirmButton = { TextButton({ controller.cancel(); onExit() }) { Text(stringResource(R.string.discard)) } },
      dismissButton = { TextButton({ confirmCancel = false }) { Text(stringResource(R.string.keep_capturing)) } },
    )
  }
  if (showImages) {
    ModalBottomSheet({ showImages = false }) {
      val dir = ctx.sessionDir(controller.sessionId)
      LazyVerticalGrid(GridCells.Fixed(3), Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items((0 until state.count).toList()) { i ->
          Thumb(java.io.File(dir, SessionStore.imageName(i)), Modifier.aspectRatio(0.75f))
        }
      }
    }
  }
}
