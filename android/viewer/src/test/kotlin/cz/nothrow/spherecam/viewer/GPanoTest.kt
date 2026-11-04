package cz.nothrow.spherecam.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class GPanoTest {
  // Same shape as the stitcher's output (stitcher/core/src/stitch.cpp).
  private val ours = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
    <rdf:Description rdf:about="" xmlns:GPano="http://ns.google.com/photos/1.0/panorama/" GPano:ProjectionType="equirectangular" GPano:UsePanoramaViewer="True" GPano:FullPanoWidthPixels="6144" GPano:FullPanoHeightPixels="3072" GPano:CroppedAreaImageWidthPixels="6144" GPano:CroppedAreaImageHeightPixels="3072" GPano:CroppedAreaLeftPixels="0" GPano:CroppedAreaTopPixels="0"/>
    </rdf:RDF></x:xmpmeta>"""

  private val cropped = """<rdf:Description rdf:about="" xmlns:GPano="http://ns.google.com/photos/1.0/panorama/">
    <GPano:ProjectionType>equirectangular</GPano:ProjectionType>
    <GPano:FullPanoWidthPixels>8000</GPano:FullPanoWidthPixels>
    <GPano:FullPanoHeightPixels>4000</GPano:FullPanoHeightPixels>
    <GPano:CroppedAreaImageWidthPixels>8000</GPano:CroppedAreaImageWidthPixels>
    <GPano:CroppedAreaImageHeightPixels>2000</GPano:CroppedAreaImageHeightPixels>
    <GPano:CroppedAreaLeftPixels>0</GPano:CroppedAreaLeftPixels>
    <GPano:CroppedAreaTopPixels>1000</GPano:CroppedAreaTopPixels>
    </rdf:Description>"""

  private fun segment(marker: Int, payload: ByteArray) = ByteArrayOutputStream().apply {
    write(0xFF); write(marker)
    val len = payload.size + 2
    write(len shr 8); write(len and 0xFF)
    write(payload)
  }.toByteArray()

  private fun jpeg(vararg segments: ByteArray) = ByteArrayOutputStream().apply {
    write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
    segments.forEach { write(it) }
    write(byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 2, 1, 2, 3))
  }.toByteArray()

  @Test fun readsXmpAfterExif() {
    val exif = segment(0xE1, "Exif\u0000\u0000junk".toByteArray())
    val xmp = segment(0xE1, "http://ns.adobe.com/xap/1.0/\u0000".toByteArray() + ours.toByteArray())
    assertEquals(ours, GPano.readXmp(ByteArrayInputStream(jpeg(exif, xmp))))
  }

  @Test fun noXmp() {
    assertNull(GPano.readXmp(ByteArrayInputStream(jpeg(segment(0xE0, "JFIF\u0000".toByteArray())))))
  }

  @Test fun ourOutputIsFullSphere() {
    assertEquals(PanoCrop(0f, 0f, 1f, 1f), GPano.crop(ours, 6144, 3072))
  }

  @Test fun croppedElementForm() {
    assertEquals(PanoCrop(0f, 0.25f, 1f, 0.5f), GPano.crop(cropped, 4000, 1000))
  }

  @Test fun missingXmp() {
    assertEquals(PanoCrop(0f, 0f, 1f, 1f), GPano.crop(null, 4000, 2000))
    assertNull(GPano.crop(null, 4000, 3000))
  }

  @Test fun otherProjectionIsRejected() {
    assertNull(GPano.crop(ours.replace("equirectangular", "cylindrical"), 6144, 3072))
  }
}
