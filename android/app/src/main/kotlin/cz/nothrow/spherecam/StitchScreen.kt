package cz.nothrow.spherecam

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cz.nothrow.spherecam.capture.session.SessionStatus
import cz.nothrow.spherecam.capture.session.SessionStore
import cz.nothrow.spherecam.stitcher.Phase
import cz.nothrow.spherecam.stitcher.Stitcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

// Cancel only takes effect at the next progress callback (bundle adjustment reports none), so a stitch
// started right after a cancel must wait instead of running two at once on a phone with limited RAM.
private val stitchLock = Mutex()

@Composable
fun StitchScreen(sessionId: String, onDone: () -> Unit) {
  val ctx = LocalContext.current
  val view = LocalView.current
  var phase by remember { mutableStateOf(Phase.LOAD) }
  var fraction by remember { mutableDoubleStateOf(0.0) }
  var error by remember { mutableStateOf<String?>(null) }

  DisposableEffect(view) {
    view.keepScreenOn = true
    onDispose { view.keepScreenOn = false }
  }
  // Leaving the screen disposes the effect, which cancels the coroutine and through it the native stitch.
  BackHandler(onBack = onDone)
  LaunchedEffect(sessionId) {
    val err = stitchLock.withLock { withContext(Dispatchers.Default) {
      val ctxJob = currentCoroutineContext()
      val dir = ctx.sessionDir(sessionId)
      // imwrite picks the encoder from the extension; a temp name keeps the previous pano until this one succeeds.
      val tmp = File(dir, "pano.tmp.jpg")
      val e = Stitcher.stitch(dir, tmp, File(dir, SessionStore.PANO_REPORT)) { p, f ->
        phase = p
        fraction = f
        ctxJob.isActive
      }
      withContext(NonCancellable + Dispatchers.IO) {
        when (e) {
          null -> {
            check(tmp.renameTo(File(dir, SessionStore.PANO))) { "cannot rename $tmp" }
            ctx.sessionStore().setStatus(sessionId, SessionStatus.STITCHED)
          }
          Stitcher.CANCELLED -> tmp.delete()
          else -> {
            tmp.delete()
            ctx.sessionStore().setStatus(sessionId, SessionStatus.FAILED)
          }
        }
      }
      e
    } }
    if (err == null) onDone() else error = err
  }

  Scaffold { pad ->
    Column(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      val e = error
      if (e == null) {
        Text(stringResource(R.string.stitching))
        Text(stringResource(R.string.stitch_phase, stringResource(phase.label()), phase.ordinal + 1, Phase.entries.size))
        LinearProgressIndicator({ fraction.toFloat() }, Modifier.fillMaxWidth())
        OutlinedButton(onDone, Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel)) }
      } else {
        Text(stringResource(R.string.stitch_failed, e))
        Button(onDone, Modifier.fillMaxWidth()) { Text(stringResource(R.string.back)) }
      }
    }
  }
}

private fun Phase.label() = when (this) {
  Phase.LOAD -> R.string.phase_load
  Phase.FEATURES -> R.string.phase_features
  Phase.MATCHING -> R.string.phase_matching
  Phase.BUNDLE_ADJUST -> R.string.phase_ba
  Phase.WARP -> R.string.phase_warp
  Phase.EXPOSURE -> R.string.phase_exposure
  Phase.SEAMS -> R.string.phase_seams
  Phase.BLEND -> R.string.phase_blend
  Phase.ENCODE -> R.string.phase_encode
}
