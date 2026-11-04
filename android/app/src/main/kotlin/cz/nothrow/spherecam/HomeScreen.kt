package cz.nothrow.spherecam

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cz.nothrow.spherecam.capture.session.Manifest
import cz.nothrow.spherecam.capture.session.SessionStatus
import cz.nothrow.spherecam.capture.session.SessionStore
import cz.nothrow.spherecam.ui.theme.SpherecamTheme
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val PREFS = "settings"
private const val PREF_NADIR = "nadir"

@Composable
fun HomeScreen(onNew: (nadir: Boolean) -> Unit, onOpen: (String) -> Unit) {
  val ctx = LocalContext.current
  val prefs = remember { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
  var nadir by remember { mutableStateOf(prefs.getBoolean(PREF_NADIR, false)) }
  var sessions by remember { mutableStateOf<List<Manifest>>(emptyList()) }
  LaunchedEffect(Unit) { sessions = withContext(Dispatchers.IO) { ctx.sessionStore().list() } }
  HomeContent(
    sessions, nadir,
    { m -> File(ctx.sessionDir(m.sessionId), SessionStore.PANO).takeIf { it.exists() } ?: File(ctx.sessionDir(m.sessionId), SessionStore.imageName(0)) },
    onNadir = { nadir = it; prefs.edit().putBoolean(PREF_NADIR, it).apply() },
    onNew = { onNew(nadir) },
    onOpen = onOpen,
  )
}

@Composable
private fun HomeContent(
  sessions: List<Manifest>,
  nadir: Boolean,
  firstImage: (Manifest) -> File,
  onNadir: (Boolean) -> Unit,
  onNew: () -> Unit,
  onOpen: (String) -> Unit,
) {
  Scaffold { pad ->
    Column(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Button(onNew, Modifier.fillMaxWidth()) { Text(stringResource(R.string.new_panorama)) }
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.capture_ground), Modifier.weight(1f))
        Switch(nadir, onNadir)
      }
      if (sessions.isEmpty()) Text(stringResource(R.string.no_sessions))
      LazyColumn {
        items(sessions, key = { it.sessionId }) { m ->
          ListItem(
            headlineContent = { Text(formatDate(m.createdAt)) },
            supportingContent = {
              Text(stringResource(R.string.session_row, stringResource(m.status.label()), m.frames.size))
            },
            leadingContent = { Thumb(firstImage(m), Modifier.size(56.dp)) },
            modifier = Modifier.clickable { onOpen(m.sessionId) },
          )
        }
      }
    }
  }
}

fun SessionStatus.label() = when (this) {
  SessionStatus.CAPTURING -> R.string.status_capturing
  SessionStatus.CAPTURED -> R.string.status_captured
  SessionStatus.STITCHED -> R.string.status_stitched
  SessionStatus.FAILED -> R.string.status_failed
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun HomePreview() {
  val base = fakeManifest()
  SpherecamTheme {
    HomeContent(
      listOf(base, base.copy(sessionId = "b", status = SessionStatus.CAPTURING, frames = base.frames.take(1))),
      true, { File("none") }, {}, {}, {},
    )
  }
}
