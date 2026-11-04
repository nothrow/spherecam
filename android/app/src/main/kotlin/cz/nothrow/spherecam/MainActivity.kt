package cz.nothrow.spherecam

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import cz.nothrow.spherecam.ui.theme.SpherecamTheme

sealed interface Screen {
  data object Home : Screen
  data class Capture(val nadir: Boolean) : Screen
  data class Detail(val sessionId: String) : Screen
  data class Stitch(val sessionId: String) : Screen
}

// A capture cannot be resumed (the gyro yaw reference is lost), so after process death Capture is restored as Home.
// A stitch is not restarted on its own after process death; it is restored as the session's Detail.
private val ScreenSaver = Saver<Screen, String>(
  save = {
    when (it) {
      is Screen.Detail -> "detail:${it.sessionId}"
      is Screen.Stitch -> "detail:${it.sessionId}"
      else -> "home"
    }
  },
  restore = { if (it.startsWith("detail:")) Screen.Detail(it.removePrefix("detail:")) else Screen.Home },
)

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      SpherecamTheme {
        var screen by rememberSaveable(stateSaver = ScreenSaver) { mutableStateOf<Screen>(Screen.Home) }
        val home = { screen = Screen.Home }
        BackHandler(screen is Screen.Detail, onBack = home)
        when (val s = screen) {
          Screen.Home -> HomeScreen(
            onNew = { screen = Screen.Capture(it) },
            onOpen = { screen = Screen.Detail(it) },
          )
          is Screen.Capture -> CaptureScreen(s.nadir, onExit = home, onFinished = { screen = Screen.Stitch(it) })
          is Screen.Detail -> DetailScreen(s.sessionId, onBack = home, onStitch = { screen = Screen.Stitch(s.sessionId) })
          is Screen.Stitch -> StitchScreen(s.sessionId, onDone = { screen = Screen.Detail(s.sessionId) })
        }
      }
    }
  }
}
