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
import androidx.compose.runtime.mutableLongStateOf
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
import kotlinx.coroutines.delay
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
  val weights = remember { ctx.phaseWeights() }
  var phase by remember { mutableStateOf(Phase.LOAD) }
  var fraction by remember { mutableDoubleStateOf(0.0) }
  var startNs by remember { mutableLongStateOf(0L) }
  var nowNs by remember { mutableLongStateOf(0L) }
  var message by remember { mutableStateOf<String?>(null) }

  DisposableEffect(view) {
    view.keepScreenOn = true
    onDispose { view.keepScreenOn = false }
  }
  // Bundle adjustment reports no progress, so the estimate is also refreshed by a clock.
  LaunchedEffect(Unit) {
    while (true) {
      nowNs = System.nanoTime()
      delay(1000)
    }
  }
  // Leaving the screen disposes the effect, which cancels the coroutine and through it the native stitch.
  BackHandler(onBack = onDone)
  LaunchedEffect(sessionId) {
    val msg = stitchLock.withLock { withContext(Dispatchers.Default) {
      startNs = System.nanoTime()
      val ctxJob = currentCoroutineContext()
      val dir = ctx.sessionDir(sessionId)
      val report = File(dir, SessionStore.PANO_REPORT)
      // imwrite picks the encoder from the extension; a temp name keeps the previous pano until this one succeeds.
      val tmp = File(dir, "pano.tmp.jpg")
      val e = Stitcher.stitch(dir, tmp, report) { p, f ->
        phase = p
        fraction = f
        ctxJob.isActive
      }
      withContext(NonCancellable + Dispatchers.IO) {
        when (e) {
          null -> {
            val pano = File(dir, SessionStore.PANO)
            check(tmp.renameTo(pano)) { "cannot rename $tmp" }
            val store = ctx.sessionStore()
            store.setStatus(sessionId, SessionStatus.STITCHED)
            ctx.rememberPhaseWeights(report, store.readManifest(sessionId).frames.size)
            runCatching { ctx.saveToPhotos(pano) }.exceptionOrNull()
              ?.let { ctx.getString(R.string.save_photos_failed, it.message ?: it.toString()) }
          }
          Stitcher.CANCELLED -> {
            tmp.delete()
            null
          }
          else -> {
            tmp.delete()
            ctx.sessionStore().setStatus(sessionId, SessionStatus.FAILED)
            ctx.getString(R.string.stitch_failed, e)
          }
        }
      }
    } }
    if (msg == null) onDone() else message = msg
  }

  Scaffold { pad ->
    Column(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      val m = message
      if (m == null) {
        val p = overallFraction(weights, phase.ordinal, fraction)
        val label = stringResource(R.string.stitch_phase, stringResource(phase.label()), phase.ordinal + 1, Phase.entries.size)
        val remaining = if (startNs == 0L) null else remainingSeconds(maxOf(0L, nowNs - startNs) / 1e9, p)
        Text(stringResource(R.string.stitching))
        LinearProgressIndicator({ p.toFloat() }, Modifier.fillMaxWidth())
        Text(remaining?.let { stringResource(R.string.stitch_remaining, label, formatRemaining(it)) } ?: label)
        OutlinedButton(onDone, Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel)) }
      } else {
        Text(m)
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
