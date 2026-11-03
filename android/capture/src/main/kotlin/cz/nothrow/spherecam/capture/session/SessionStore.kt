package cz.nothrow.spherecam.capture.session

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Sessions live in `root/<sessionId>/`. Not thread-safe: confine all calls to one thread.
 */
class SessionStore(private val root: File) {
  private fun dir(id: String) = File(root, id)

  fun readManifest(id: String): Manifest =
    ManifestJson.decodeFromString(Manifest.serializer(), File(dir(id), MANIFEST).readText())

  fun create(manifest: Manifest): File {
    val d = dir(manifest.sessionId)
    check(d.mkdirs()) { "Session dir exists or cannot be created: $d" }
    writeManifest(manifest)
    return d
  }

  fun addFrame(sessionId: String, jpeg: ByteArray, frame: Frame) {
    val m = readManifest(sessionId)
    require(frame.index == m.frames.size) { "Frame index ${frame.index} != ${m.frames.size}" }
    require(frame.file == imageName(frame.index)) { "Unexpected file name ${frame.file}" }
    writeDurably(File(dir(sessionId), frame.file), jpeg)
    // The image must be on disk before a manifest references it; the reverse order could leave a
    // manifest pointing at a missing or truncated JPEG after a crash. An orphan image is harmless.
    writeManifest(m.copy(frames = m.frames + frame))
  }

  fun undoLast(sessionId: String) {
    val m = readManifest(sessionId)
    val last = m.frames.lastOrNull() ?: return
    // Manifest first: a crash in between leaves an orphan image, never a dangling reference.
    writeManifest(m.copy(frames = m.frames.dropLast(1)))
    File(dir(sessionId), last.file).delete()
  }

  fun markCaptured(sessionId: String) {
    writeManifest(readManifest(sessionId).copy(status = SessionStatus.CAPTURED))
  }

  /** Newest first. Folders with a missing or corrupt manifest are skipped but left in place. */
  fun list(): List<Manifest> =
    root.listFiles { f -> f.isDirectory }.orEmpty()
      .mapNotNull { runCatching { readManifest(it.name) }.getOrNull() }
      .sortedByDescending { it.createdAt }

  fun delete(sessionId: String) {
    dir(sessionId).deleteRecursively()
  }

  fun exportZip(sessionId: String, outFile: File) {
    val d = dir(sessionId)
    ZipOutputStream(outFile.outputStream().buffered()).use { zip ->
      val files = d.listFiles { f -> f.name == MANIFEST || f.name.matches(IMAGE_RE) }.orEmpty().sortedBy { it.name }
      for (f in files) {
        val e = ZipEntry("$sessionId/${f.name}")
        if (f.name == MANIFEST) {
          e.method = ZipEntry.DEFLATED
        } else {
          val bytes = f.readBytes()
          e.method = ZipEntry.STORED
          e.size = bytes.size.toLong()
          e.compressedSize = e.size
          e.crc = CRC32().apply { update(bytes) }.value
        }
        zip.putNextEntry(e)
        f.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
      }
    }
  }

  private fun writeManifest(m: Manifest) {
    val d = dir(m.sessionId)
    val tmp = File(d, "$MANIFEST.tmp")
    writeDurably(tmp, ManifestJson.encodeToString(Manifest.serializer(), m).toByteArray())
    Files.move(
      tmp.toPath(), File(d, MANIFEST).toPath(),
      StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
    )
  }

  private fun writeDurably(f: File, bytes: ByteArray) {
    FileOutputStream(f).use {
      it.write(bytes)
      it.fd.sync()
    }
  }

  companion object {
    const val MANIFEST = "manifest.json"
    private val IMAGE_RE = Regex("img_\\d{3}\\.jpg")
    fun imageName(index: Int) = "img_%03d.jpg".format(index)
  }
}
