package cz.nothrow.spherecam.viewer

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream

/** Area covered by the image, as fractions of the full equirect. */
data class PanoCrop(val left: Float, val top: Float, val width: Float, val height: Float)

object GPano {
  private const val XMP_NS = "http://ns.adobe.com/xap/1.0/\u0000"
  private const val SOS = 0xDA
  private const val EOI = 0xD9
  private const val APP1 = 0xE1

  /** Main XMP packet of a JPEG, or null. Extended XMP is not read; GPano lives in the main packet. */
  fun readXmp(input: InputStream): String? {
    val s = DataInputStream(input.buffered())
    try {
      if (s.readUnsignedShort() != 0xFFD8) return null
      while (true) {
        if (s.readUnsignedByte() != 0xFF) return null
        var marker = s.readUnsignedByte()
        while (marker == 0xFF) marker = s.readUnsignedByte()
        if (marker == SOS || marker == EOI) return null
        val payload = ByteArray(s.readUnsignedShort() - 2).also { s.readFully(it) }
        if (marker == APP1 && payload.size > XMP_NS.length &&
          String(payload, 0, XMP_NS.length, Charsets.ISO_8859_1) == XMP_NS
        ) {
          return String(payload, XMP_NS.length, payload.size - XMP_NS.length, Charsets.UTF_8)
        }
      }
    } catch (_: EOFException) {
      return null
    }
  }

  /** Null if the image is not a 360° equirect. Without GPano, a 2:1 image is taken as a full sphere. */
  fun crop(xmp: String?, imgW: Int, imgH: Int): PanoCrop? {
    fun value(name: String) = xmp?.let {
      (Regex("GPano:$name\\s*=\\s*\"([^\"]*)\"").find(it) ?: Regex("<GPano:$name>([^<]*)</GPano:$name>").find(it))
        ?.groupValues?.get(1)?.trim()
    }
    fun num(name: String) = value(name)?.toFloatOrNull()

    value("ProjectionType")?.let { if (!it.equals("equirectangular", ignoreCase = true)) return null }
    val fullW = num("FullPanoWidthPixels")
    if (fullW == null || fullW <= 0) {
      return if (kotlin.math.abs(imgW - 2 * imgH) <= 2) PanoCrop(0f, 0f, 1f, 1f) else null
    }
    val fullH = num("FullPanoHeightPixels") ?: (fullW / 2)
    val cw = num("CroppedAreaImageWidthPixels") ?: fullW
    val ch = num("CroppedAreaImageHeightPixels") ?: (cw * imgH / imgW)
    return PanoCrop(
      (num("CroppedAreaLeftPixels") ?: 0f) / fullW,
      (num("CroppedAreaTopPixels") ?: 0f) / fullH,
      cw / fullW,
      ch / fullH,
    )
  }
}
