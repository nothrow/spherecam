package cz.nothrow.spherecam

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import cz.nothrow.spherecam.stitcher.Phase
import cz.nothrow.spherecam.viewer.ViewerActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

fun Context.saveToPhotos(pano: File) {
  val name = "PANO_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".jpg"
  val values = ContentValues().apply {
    put(MediaStore.Images.Media.DISPLAY_NAME, name)
    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/SphereCam")
    put(MediaStore.Images.Media.IS_PENDING, 1)
  }
  val uri = contentResolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
    ?: error("MediaStore insert failed")
  try {
    checkNotNull(contentResolver.openOutputStream(uri)) { "cannot open $uri" }.use { out ->
      pano.inputStream().use { it.copyTo(out) }
    }
    contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
  } catch (e: Exception) {
    contentResolver.delete(uri, null, null)
    throw e
  }
}

fun Context.viewPano(pano: File) =
  startActivity(ViewerActivity.intent(this, FileProvider.getUriForFile(this, "$packageName.files", pano)))

fun Context.sharePano(pano: File) {
  val uri = FileProvider.getUriForFile(this, "$packageName.files", pano)
  val send = Intent(Intent.ACTION_SEND)
    .setType("image/jpeg")
    .putExtra(Intent.EXTRA_STREAM, uri)
    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
  startActivity(Intent.createChooser(send, getString(R.string.share_chooser)))
}

private const val PREF_WEIGHTS = "phase_weights"
// Keys of phaseSeconds in the stitcher report, in Phase order.
private val REPORT_PHASES = listOf("load", "features", "matching", "ba", "warp", "exposure", "seams", "blend", "encode")

private fun Context.prefs() = getSharedPreferences("stitch", Context.MODE_PRIVATE)

fun Context.phaseWeights(): DoubleArray =
  runCatching {
    val a = JSONArray(prefs().getString(PREF_WEIGHTS, null)!!)
    DoubleArray(a.length()) { a.getDouble(it) }.takeIf { it.size == Phase.entries.size && it.sum() > 0 }
  }.getOrNull() ?: DEFAULT_PHASE_WEIGHTS

fun Context.rememberPhaseWeights(report: File, frames: Int) {
  val s = runCatching { JSONObject(report.readText()).getJSONObject("phaseSeconds") }.getOrNull() ?: return
  val w = REPORT_PHASES.map { s.optDouble(it, 0.0) / frames }
  if (w.sum() > 0) prefs().edit().putString(PREF_WEIGHTS, JSONArray(w).toString()).apply()
}
