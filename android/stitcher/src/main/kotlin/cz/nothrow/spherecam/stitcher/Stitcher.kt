package cz.nothrow.spherecam.stitcher

import java.io.File

/** Same order as spherestitch::Phase. */
enum class Phase { LOAD, FEATURES, MATCHING, BUNDLE_ADJUST, WARP, EXPOSURE, SEAMS, BLEND, ENCODE }

object Stitcher {
  init {
    System.loadLibrary("spherestitch_jni")
  }

  const val CANCELLED = "cancelled"

  /**
   * Blocking, CPU and memory heavy (~600 MB native for 36 frames); call off the main thread, one at a time.
   * [progress] runs on the calling thread; returning false cancels. Returns null on success, otherwise the error
   * ([CANCELLED] after a cancel). [report] gets the JSON report in both cases.
   */
  fun stitch(sessionDir: File, out: File, report: File, width: Int = 6144, progress: (Phase, Double) -> Boolean): String? =
    nativeStitch(sessionDir.path, out.path, report.path, width) { p, f -> progress(Phase.entries[p], f) }.ifEmpty { null }

  private fun interface Listener {
    fun onProgress(phase: Int, fraction: Double): Boolean
  }

  private external fun nativeStitch(sessionDir: String, out: String, report: String, width: Int, listener: Listener): String
}
