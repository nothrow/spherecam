package cz.nothrow.spherecam

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import cz.nothrow.spherecam.capture.session.Manifest
import cz.nothrow.spherecam.capture.session.SessionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun DetailScreen(sessionId: String, onBack: () -> Unit) {
  val ctx = LocalContext.current
  val scope = rememberCoroutineScope()
  var manifest by remember { mutableStateOf<Manifest?>(null) }
  var confirmDelete by remember { mutableStateOf(false) }
  LaunchedEffect(sessionId) {
    manifest = withContext(Dispatchers.IO) { runCatching { ctx.sessionStore().readManifest(sessionId) }.getOrNull() }
    if (manifest == null) onBack()
  }
  val m = manifest ?: return
  fun yn(b: Boolean) = ctx.getString(if (b) R.string.yes else R.string.no)

  Scaffold { pad ->
    Column(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(stringResource(R.string.detail_created, formatDate(m.createdAt)))
      Text(stringResource(R.string.detail_status, stringResource(m.status.label())))
      Text(stringResource(R.string.detail_frames, m.frames.size))
      Text(stringResource(R.string.detail_settings, yn(m.settings.nadir), (m.settings.targetOverlap * 100).toInt()))
      val c = m.camera
      Text(stringResource(R.string.detail_locks, yn(c.aeLocked), yn(c.awbLocked), yn(c.afLocked)))
      if (m.status == SessionStatus.CAPTURING) {
        Text(stringResource(R.string.mark_captured_hint))
        Button({
          scope.launch {
            withContext(Dispatchers.IO) { ctx.sessionStore().markCaptured(sessionId) }
            manifest = m.copy(status = SessionStatus.CAPTURED)
          }
        }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.mark_captured)) }
      }
      Button({
        scope.launch {
          val zip = withContext(Dispatchers.IO) {
            File(ctx.cacheDir, "exports").apply { mkdirs() }.resolve("$sessionId.zip")
              .also { ctx.sessionStore().exportZip(sessionId, it) }
          }
          val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", zip)
          val send = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
          ctx.startActivity(Intent.createChooser(send, ctx.getString(R.string.export_chooser)))
        }
      }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.export_zip)) }
      OutlinedButton({ confirmDelete = true }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.delete)) }
      TextButton(onBack, Modifier.fillMaxWidth()) { Text(stringResource(R.string.back)) }
    }
  }

  if (confirmDelete) {
    AlertDialog(
      onDismissRequest = { confirmDelete = false },
      title = { Text(stringResource(R.string.delete_title)) },
      text = { Text(stringResource(R.string.delete_text)) },
      confirmButton = {
        TextButton({
          scope.launch {
            withContext(Dispatchers.IO) { ctx.sessionStore().delete(sessionId) }
            onBack()
          }
        }) { Text(stringResource(R.string.delete)) }
      },
      dismissButton = { TextButton({ confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
    )
  }
}
