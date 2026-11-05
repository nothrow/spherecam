package cz.nothrow.spherecam.viewer

import android.graphics.Bitmap
import android.opengl.GLES30.*
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.util.Log
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.tan

/** [rotation] is R_world_from_camera (row-major), [fovYDeg] the vertical field of view; both are read every frame. */
class PanoRenderer(
  private val bitmap: Bitmap,
  private val crop: PanoCrop,
  private val rotation: () -> FloatArray,
  private val fovYDeg: () -> Float,
) : GLSurfaceView.Renderer {
  private var program = 0
  private var aspect = 1f
  private var uRot = 0
  private var uTan = 0
  private var uAspect = 0
  private var uCrop = 0

  override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
    program = link(VERTEX, FRAGMENT)
    uRot = glGetUniformLocation(program, "uRot")
    uTan = glGetUniformLocation(program, "uTan")
    uAspect = glGetUniformLocation(program, "uAspect")
    uCrop = glGetUniformLocation(program, "uCrop")
    glBindVertexArray(IntArray(1).also { glGenVertexArrays(1, it, 0) }[0])

    val max = IntArray(1).also { glGetIntegerv(GL_MAX_TEXTURE_SIZE, it, 0) }[0]
    Log.i(TAG, "GL_MAX_TEXTURE_SIZE=$max, image ${bitmap.width}x${bitmap.height}")
    val k = minOf(1f, max.toFloat() / maxOf(bitmap.width, bitmap.height))
    val upload = if (k < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * k).toInt(), (bitmap.height * k).toInt(), true) else bitmap

    glBindTexture(GL_TEXTURE_2D, IntArray(1).also { glGenTextures(1, it, 0) }[0])
    GLUtils.texImage2D(GL_TEXTURE_2D, 0, upload, 0)
    if (upload !== bitmap) upload.recycle()
    glGenerateMipmap(GL_TEXTURE_2D)
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR)
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, if (crop.width >= FULL) GL_REPEAT else GL_CLAMP_TO_EDGE)
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
  }

  override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
    glViewport(0, 0, width, height)
    aspect = width.toFloat() / height
  }

  override fun onDrawFrame(gl: GL10?) {
    glUseProgram(program)
    // GLES 3 accepts transpose = true, so the row-major matrix goes in as is.
    glUniformMatrix3fv(uRot, 1, true, rotation(), 0)
    glUniform1f(uTan, tan(Math.toRadians(fovYDeg() / 2.0)).toFloat())
    glUniform1f(uAspect, aspect)
    glUniform4f(uCrop, crop.left, crop.top, crop.width, crop.height)
    glDrawArrays(GL_TRIANGLES, 0, 3)
  }

  private fun compile(type: Int, src: String): Int {
    val s = glCreateShader(type)
    glShaderSource(s, src)
    glCompileShader(s)
    val ok = IntArray(1).also { glGetShaderiv(s, GL_COMPILE_STATUS, it, 0) }[0]
    check(ok != 0) { "Shader compile failed: ${glGetShaderInfoLog(s)}" }
    return s
  }

  private fun link(vs: String, fs: String): Int {
    val p = glCreateProgram()
    glAttachShader(p, compile(GL_VERTEX_SHADER, vs))
    glAttachShader(p, compile(GL_FRAGMENT_SHADER, fs))
    glLinkProgram(p)
    val ok = IntArray(1).also { glGetProgramiv(p, GL_LINK_STATUS, it, 0) }[0]
    check(ok != 0) { "Program link failed: ${glGetProgramInfoLog(p)}" }
    return p
  }

  private companion object {
    const val TAG = "PanoRenderer"
    const val FULL = 0.999f

    const val VERTEX = """#version 300 es
out vec2 vNdc;
void main() {
  vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2)) * 2.0 - 1.0;
  vNdc = p;
  gl_Position = vec4(p, 0.0, 1.0);
}"""

    // Mirrors ViewMath.ray and ViewMath.uvOf. The u derivative jumps by ~1 where lon wraps; removing the integer
    // part keeps the mip level right there, otherwise a one-pixel seam of the smallest mip shows up.
    const val FRAGMENT = """#version 300 es
precision highp float;
in vec2 vNdc;
uniform mat3 uRot;
uniform float uTan;
uniform float uAspect;
uniform vec4 uCrop;
uniform sampler2D uTex;
out vec4 color;
const float PI = 3.14159265358979;
void main() {
  vec3 d = uRot * vec3(vNdc.x * uTan * uAspect, -vNdc.y * uTan, 1.0);
  float lon = atan(d.y, d.x);
  float lat = atan(d.z, length(d.xy));
  vec2 full = vec2((PI - lon) / (2.0 * PI), (0.5 * PI - lat) / PI);
  vec2 dx = dFdx(full);
  vec2 dy = dFdy(full);
  dx.x -= round(dx.x);
  dy.x -= round(dy.x);
  vec2 uv = (full - uCrop.xy) / uCrop.zw;
  if (uCrop.z >= 0.999) uv.x = fract(uv.x);
  if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) {
    color = vec4(0.12, 0.12, 0.12, 1.0);
    return;
  }
  color = textureGrad(uTex, uv, dx / uCrop.zw, dy / uCrop.zw);
}"""
  }
}
