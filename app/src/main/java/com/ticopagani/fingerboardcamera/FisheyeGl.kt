package com.ticopagani.fingerboardcamera

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.opengles.GL10

/**
 * Programa OpenGL do efeito: distorção olho de peixe (barril), corte pro formato
 * escolhido e vinheta circular de cantos pretos.
 */
class FisheyeGl {
    private var program = 0
    private var aPos = 0
    private var aUv = 0
    private var uTex = 0
    private var uTexM = 0
    private var uCrop = 0
    private var uAspect = 0
    private var uK = 0
    private var uVig = 0

    private val quad: FloatBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f
        ))
        position(0)
    }

    fun init() {
        program = link(VS, FS)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aUv = GLES20.glGetAttribLocation(program, "aUv")
        uTex = GLES20.glGetUniformLocation(program, "uTex")
        uTexM = GLES20.glGetUniformLocation(program, "uTexM")
        uCrop = GLES20.glGetUniformLocation(program, "uCrop")
        uAspect = GLES20.glGetUniformLocation(program, "uAspect")
        uK = GLES20.glGetUniformLocation(program, "uK")
        uVig = GLES20.glGetUniformLocation(program, "uVig")
    }

    fun createOesTexture(): Int {
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, t[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return t[0]
    }

    fun draw(tex: Int, texM: FloatArray, cropX: Float, cropY: Float, aspect: Float, k: Float, vig: Float) {
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glUniformMatrix4fv(uTexM, 1, false, texM, 0)
        GLES20.glUniform2f(uCrop, cropX, cropY)
        GLES20.glUniform1f(uAspect, aspect)
        GLES20.glUniform1f(uK, k)
        GLES20.glUniform1f(uVig, vig)
        quad.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPos)
        quad.position(2)
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aUv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aUv)
    }

    companion object {
        private const val VS = """
attribute vec4 aPos;
attribute vec2 aUv;
varying vec2 vQ;
void main() {
  gl_Position = aPos;
  vQ = aUv;
}
"""

        private const val FS = """
#extension GL_OES_EGL_image_external : require
precision highp float;
varying vec2 vQ;
uniform samplerExternalOES uTex;
uniform mat4 uTexM;
uniform vec2 uCrop;
uniform float uAspect;
uniform float uK;
uniform float uVig;
void main() {
  vec2 p = vQ - 0.5;
  vec2 pa = vec2(p.x * uAspect, p.y);
  float r2 = dot(pa, pa) / (0.25 * (uAspect * uAspect + 1.0));
  float f = (1.0 + uK * r2) / (1.0 + uK);
  vec2 src = p * f + 0.5;
  vec3 c = vec3(0.0);
  if (src.x >= 0.0 && src.x <= 1.0 && src.y >= 0.0 && src.y <= 1.0) {
    vec2 s = (src - 0.5) * uCrop + 0.5;
    vec2 t = (uTexM * vec4(s, 0.0, 1.0)).xy;
    c = texture2D(uTex, t).rgb;
  }
  float d = sqrt(r2);
  float st = mix(1.05, 0.62, uVig);
  float v = 1.0 - smoothstep(st, st + 0.28, d) * step(0.001, uVig);
  gl_FragColor = vec4(c * v, 1.0);
}
"""

        private fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(s)
                GLES20.glDeleteShader(s)
                throw RuntimeException("Shader: $log")
            }
            return s
        }

        private fun link(vs: String, fs: String): Int {
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
            GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
            GLES20.glLinkProgram(p)
            val ok = IntArray(1)
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
            if (ok[0] == 0) throw RuntimeException("Program: " + GLES20.glGetProgramInfoLog(p))
            return p
        }

        /**
         * Matriz que leva um ponto da imagem final (já em pé) até a textura da câmera/vídeo:
         * gira pelo ângulo do sensor e aplica a matriz do SurfaceTexture.
         */
        fun texMatrix(st: FloatArray, rotationDeg: Int, mirror: Boolean): FloatArray {
            val r = FloatArray(16)
            Matrix.setIdentityM(r, 0)
            Matrix.translateM(r, 0, 0.5f, 0.5f, 0f)
            Matrix.rotateM(r, 0, rotationDeg.toFloat(), 0f, 0f, 1f)
            if (mirror) Matrix.scaleM(r, 0, -1f, 1f, 1f)
            Matrix.translateM(r, 0, -0.5f, -0.5f, 0f)
            val out = FloatArray(16)
            Matrix.multiplyMM(out, 0, st, 0, r, 0)
            return out
        }

        /** Escala de corte pra preencher o formato de saída a partir da imagem de origem. */
        fun crop(srcAspect: Float, dstAspect: Float): Pair<Float, Float> =
            if (dstAspect < srcAspect) Pair(dstAspect / srcAspect, 1f) else Pair(1f, srcAspect / dstAspect)

        fun strengthFromPercent(pct: Int): Float = pct / 100f * 1.2f
    }
}

/** Renderer do preview: desenha a câmera (ou o vídeo do editor) com o efeito, encaixado no formato. */
class FisheyeRenderer(
    private val view: GLSurfaceView,
    private val onTexture: (SurfaceTexture) -> Unit
) : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    @Volatile var outAspect = 4f / 3f
    @Volatile var srcAspect = 16f / 9f
    @Volatile var rotation = 0
    @Volatile var mirror = false
    @Volatile var k = FishDefaults.K
    @Volatile var vig = FishDefaults.VIG

    private val gl = FisheyeGl()
    private var tex = 0
    private var st: SurfaceTexture? = null
    private val stM = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private var vw = 1
    private var vh = 1

    override fun onSurfaceCreated(unused: GL10?, config: javax.microedition.khronos.egl.EGLConfig?) {
        gl.init()
        tex = gl.createOesTexture()
        st?.release()
        val s = SurfaceTexture(tex)
        s.setOnFrameAvailableListener(this)
        st = s
        view.post { onTexture(s) }
    }

    override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
        vw = width
        vh = height
    }

    override fun onDrawFrame(unused: GL10?) {
        GLES20.glViewport(0, 0, vw, vh)
        GLES20.glClearColor(0.043f, 0.043f, 0.039f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val s = st ?: return
        try {
            s.updateTexImage()
            s.getTransformMatrix(stM)
        } catch (e: RuntimeException) {
            return
        }
        val a = outAspect
        val va = vw.toFloat() / vh
        var w = vw
        var h = vh
        if (va > a) w = (vh * a).toInt() else h = (vw / a).toInt()
        GLES20.glViewport((vw - w) / 2, (vh - h) / 2, w, h)
        val (cx, cy) = FisheyeGl.crop(srcAspect, a)
        gl.draw(tex, FisheyeGl.texMatrix(stM, rotation, mirror), cx, cy, a, k, vig)
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        view.requestRender()
    }

    fun redraw() = view.requestRender()
}

/** Contexto EGL fora da tela, desenhando direto na entrada do encoder de vídeo. */
class EglCore(surface: Surface) {
    private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val context: EGLContext
    private val eglSurface: EGLSurface

    init {
        val v = IntArray(2)
        check(EGL14.eglInitialize(display, v, 0, v, 1)) { "eglInitialize falhou" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) && num[0] > 0) { "Sem config EGL" }
        context = EGL14.eglCreateContext(
            display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent falhou" }
    }

    fun setPresentationTime(ns: Long) {
        EGLExt.eglPresentationTimeANDROID(display, eglSurface, ns)
    }

    fun swap() {
        EGL14.eglSwapBuffers(display, eglSurface)
    }

    fun release() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, eglSurface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
        EGL14.eglTerminate(display)
    }

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}
