package cz.nothrow.spherecam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import cz.nothrow.spherecam.capture.session.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

fun Context.sessionStore() = SessionStore(File(filesDir, "sessions"))

fun Context.sessionDir(id: String) = File(filesDir, "sessions/$id")

fun formatDate(iso: String): String =
  runCatching {
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
      .withZone(ZoneId.systemDefault()).format(Instant.parse(iso))
  }.getOrDefault(iso)

private fun decodeSampled(file: File, targetPx: Int): Bitmap? {
  val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
  BitmapFactory.decodeFile(file.path, o)
  o.inJustDecodeBounds = false
  o.inSampleSize = maxOf(1, minOf(o.outWidth, o.outHeight) / targetPx)
  return BitmapFactory.decodeFile(file.path, o)
}

@Composable
fun Thumb(file: File, modifier: Modifier, targetPx: Int = 256) {
  val bmp by produceState<ImageBitmap?>(null, file, file.lastModified()) {
    value = withContext(Dispatchers.IO) { decodeSampled(file, targetPx)?.asImageBitmap() }
  }
  bmp?.let { Image(it, null, modifier, contentScale = ContentScale.Crop) }
}
