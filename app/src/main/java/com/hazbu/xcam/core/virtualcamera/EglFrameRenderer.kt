package com.hazbu.xcam.core.virtualcamera

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.media.Image.Plane
import android.os.Build
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl
import javax.microedition.khronos.opengles.GL10
import javax.microedition.khronos.opengles.GL20

/**
 * OpenGL ES / EGL frame renderer for the virtual camera.
 * Converts YUV_420_888 or NV21 frames to output surfaces (preview or JPEG).
 *
 * Pipeline:
 * - Input: YUV_420_888 frames from MediaCodecYuvDecoder or NV21 from YuvFrameProcessor
 * - Processing: EGL texture with YUV->RGB shader
 * - Output: NV21 bytes (for preview) or JPEG bytes (for still capture)
 */
class EglFrameRenderer(private val logAction: (String) -> Unit) {

    companion object {
        private const val TAG = "xEglFrame"
        private const val EGL_CONTEXT_CLIENT_VERSION = 0x3098
        private const val EGL_OPENGL_ES2_BIT = 4
        private const val EGL_DEFAULT_DISPLAY = 0x00000000
        private const val EGL_RED_SIZE = 0x3024
        private const val EGL_GREEN_SIZE = 0x3025
        private const val EGL_BLUE_SIZE = 0x3026
        private const val EGL_ALPHA_SIZE = 0x3027
        private const val EGL_RENDERABLE_TYPE = 0x3040
        private const val EGL_NONE = 0x3038
        private const val EGL_SUCCESS = 12297
        private const val EGL_FALSE = 0
        private const val EGL_TRUE = 1
        private const val EGL_NO_CONTEXT = 0
        private const val EGL_NO_DISPLAY = 0
        private const val EGL_NO_SURFACE = 0

        // Fragment shader for YUV_420_888 -> RGB conversion
        private const val YUV_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform samplerExternal2D yTexture;
            uniform samplerExternal2D uTexture;
            uniform samplerExternal2D vTexture;
            void main() {
                float r, g, b, y, u, v;
                y = texture2D(yTexture, vTextureCoord).r;
                u = texture2D(uTexture, vTextureCoord).r - 0.5;
                v = texture2D(vTexture, vTextureCoord).r - 0.5;
                r = y + 1.402 * v;
                g = y - 0.344136 * u - 0.714136 * v;
                b = y + 1.771 * u;
                gl_FragColor = vec4(r, g, b, 1.0);
            }
        """.trimIndent()

        // Vertex shader (standard quad)
        private const val YUV_VERTEX_SHADER = """
            attribute vec4 vPosition;
            attribute vec2 vTextureCoord;
            varying vec2 vTextureCoord;
            void main() {
                vTextureCoord = vTextureCoord;
                gl_Position = vPosition;
            }
        """.trimIndent()
    }

    private var glInitialized = false
    private var glContext: EGLContext? = null
    private var glDisplay: EGLDisplay? = null
    private var glSurface: EGLSurface? = null
    private var glProgram: Int = -1
    private var vPositionHandle: Int = -1
    private var vTextureCoordHandle: Int = -1
    private var yTextureHandle: Int = -1
    private var uTextureHandle: Int = -1
    private var vTextureHandle: Int = -1

    // Quad vertices (normalized device coordinates)
    private val quadVertices = floatArrayOf(
        -1f, -1f, 0f,
         1f, -1f, 0f,
        -1f,  1f, 0f,
         1f,  1f, 0f
    )

    // Texture coordinates (standard YUV mapping)
    private val quadTextures = floatArrayOf(
        0f, 1f,
        1f, 1f,
        0f, 0f,
        1f, 0f
    )

    // Frame data
    private var frameWidth = 0
    private var frameHeight = 0
    private var yPlane = ByteArray(0)
    private var uPlane = ByteArray(0)
    private var vPlane = ByteArray(0)

    // NV21 output buffer
    private var nv21Buffer: ByteArray? = null

    // EGL methods
    private external fun eglGetDisplay(nativeDisplay: Any): EGLDisplay
    private external fun eglInitialize(display: EGLDisplay?, versionMajor: IntArray?, versionMinor: IntArray?): Boolean
    private external fun eglChooseConfig(display: EGLDisplay?, attribList: IntArray?, configs: Array<EGLConfig?>?, configSize: Int, numConfigs: IntArray?): Int
    private external fun eglCreateContext(display: EGLDisplay?, config: EGLConfig?, shareContext: EGLContext?, attribList: IntArray?): EGLContext
    private external fun eglCreatePbufferSurface(display: EGLDisplay?, config: EGLConfig?, attribList: IntArray?): EGLSurface
    private external fun eglMakeCurrent(display: EGLDisplay?, surface: EGLSurface?, read: EGLSurface?, context: EGLContext?): Boolean
    private external fun eglSwapBuffers(display: EGLDisplay?, surface: EGLSurface?): Boolean
    private external fun eglDestroySurface(display: EGLDisplay?, surface: EGLSurface?): Boolean
    private external fun eglDestroyContext(display: EGLDisplay?, context: EGLContext?): Boolean
    private external fun eglTerminate(display: EGLDisplay?): Boolean
    private external fun eglGetError(): Int

    // OpenGL ES 2.0/3.0 methods
    private external fun glCreateShader(type: Int): Int
    private external fun glShaderSource(shader: Int, source: String?)
    private external fun glCompileShader(shader: Int)
    private external fun glCreateProgram(): Int
    private external fun glAttachShader(program: Int, shader: Int)
    private external fun glLinkProgram(program: Int)
    private external fun glUseProgram(program: Int)
    private external fun glGetAttribLocation(program: Int, name: String?): Int
    private external fun glGetUniformLocation(program: Int, name: String?): Int
    private external fun glEnableVertexAttribArray(index: Int)
    private external fun glVertexAttribPointer(
        index: Int, size: Int, type: Int, stride: Int, pointer: Long
    )
    private external fun glDrawArrays(mode: Int, first: Int, count: Int)
    private external fun glGenTextures(n: Int, textures: IntArray?)
    private external fun glDeleteTextures(n: Int, textures: IntArray?)
    private external fun glActiveTexture(texture: Int)
    private external fun glTexImage2D(target: Int, level: Int, internalFormat: Int, width: Int, height: Int, border: Int, format: Int, type: Int, pixels: ByteBuffer?)
    private external fun glTexImage2DWithLong(target: Int, level: Int, internalFormat: Int, width: Int, height: Int, border: Int, format: Int, type: Int, pixels: Long)
    private external fun glTexParameteri(target: Int, pname: Int, param: Int)
    private external fun glReadPixels(x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, pixels: ByteBuffer?)

    init {
        loadNativeLib()
        initializeEgl()
    }

    private fun loadNativeLib() {
        try {
            System.loadLibrary("xCamVirtualCamera")
        } catch (e: UnsatisfiedLinkError) {
            // JNI not available, use reflection-based EGL
            logAction("$TAG: JNI library not found, using reflection")
        }
    }

    /**
     * Initialize EGL context and shaders.
     */
    private fun initializeEgl() {
        try {
            // Create EGL display
            glDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY)
            if (glDisplay == EGL_NO_DISPLAY) {
                logAction("$TAG: Failed to get EGL display")
                return
            }

            // Initialize EGL
            val versionMajor = IntArray(1)
            val versionMinor = IntArray(1)
            if (!eglInitialize(glDisplay!!, versionMajor, versionMinor)) {
                logAction("$TAG: Failed to initialize EGL")
                return
            }

            // Set EGL attributes for RGB565 surface
            val eglAttribs = intArrayOf(
                EGL_RED_SIZE, 8,
                EGL_GREEN_SIZE, 8,
                EGL_BLUE_SIZE, 8,
                EGL_ALPHA_SIZE, 8,
                EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                EGL_NONE
            )

            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            eglChooseConfig(glDisplay!!, eglAttribs, configs, 1, numConfigs)

            if (numConfigs[0] == 0 || configs[0] == null) {
                logAction("$TAG: Failed to choose EGL config")
                return
            }

            // Create EGL context (OpenGL ES 2.0/3.0)
            val contextAttribs = intArrayOf(
                EGL_CONTEXT_CLIENT_VERSION, 3,
                EGL_NONE
            )

            glContext = eglCreateContext(
                glDisplay!!, configs[0], EGL_NO_CONTEXT, contextAttribs
            )

            if (glContext == EGL_NO_CONTEXT) {
                logAction("$TAG: Failed to create EGL context")
                return
            }

            // Create EGL surface (pbuffer for offscreen rendering)
            val surfaceAttribs = intArrayOf(
                EGL_WIDTH, 1280,
                EGL_HEIGHT, 720,
                EGL_NONE
            )

            glSurface = eglCreatePbufferSurface(glDisplay!!, configs[0]!!, surfaceAttribs)

            // Make current
            eglMakeCurrent(glDisplay!!, glSurface!!, glSurface!!, glContext!!)

            // Compile and link shaders
            compileAndLinkShaders()

            glInitialized = true
            logAction("$TAG: EGL initialized successfully")
        } catch (e: Exception) {
            logAction("$TAG: EGL init failed: ${e.message}")
        }
    }

    /**
     * Compile and link the YUV-to-RGB shaders.
     */
    private fun compileAndLinkShaders() {
        try {
            // Compile vertex shader
            val vertexShader = glCreateShader(GL20.GL_VERTEX_SHADER)
            glShaderSource(vertexShader, YUV_VERTEX_SHADER)
            glCompileShader(vertexShader)

            // Compile fragment shader
            val fragmentShader = glCreateShader(GL20.GL_FRAGMENT_SHADER)
            glShaderSource(fragmentShader, YUV_FRAGMENT_SHADER)
            glCompileShader(fragmentShader)

            // Link program
            glProgram = glCreateProgram()
            glAttachShader(glProgram, vertexShader)
            glAttachShader(glProgram, fragmentShader)
            glLinkProgram(glProgram)

            // Get handle locations
            vPositionHandle = glGetAttribLocation(glProgram, "vPosition")
            vTextureCoordHandle = glGetAttribLocation(glProgram, "vTextureCoord")
            yTextureHandle = glGetUniformLocation(glProgram, "yTexture")
            uTextureHandle = glGetUniformLocation(glProgram, "uTexture")
            vTextureHandle = glGetUniformLocation(glProgram, "vTexture")

            logAction("$TAG: Shaders compiled and linked")
        } catch (e: Exception) {
            logAction("$TAG: Shader compile/link failed: ${e.message}")
        }
    }

    /**
     * Render a YUV_420_888 frame to the output surface.
     *
     * @param yPlane Y plane data
     * @param uPlane U plane data
     * @param vPlane V plane data
     * @param width Frame width
     * @param height Frame height
     * @return NV21 byte array ready for output
     */
    fun renderYuvFrame(
        yPlane: ByteArray,
        uPlane: ByteArray,
        vPlane: ByteArray,
        width: Int,
        height: Int
    ): ByteArray? {
        if (!glInitialized || glProgram < 0) {
            // Fallback: direct NV21 copy
            return copyYuvToNv21(yPlane, uPlane, vPlane, width, height)
        }

        this.yPlane = yPlane
        this.uPlane = uPlane
        this.vPlane = vPlane
        this.frameWidth = width
        this.frameHeight = height

        return renderToNv21()
    }

    /**
     * Render an NV21 frame directly to output.
     *
     * @param nv21Data NV21 formatted YUV data
     * @param width Frame width
     * @param height Frame height
     * @return NV21 byte array (same as input if no transformation needed)
     */
    fun renderNv21Frame(nv21Data: ByteArray, width: Int, height: Int): ByteArray {
        this.frameWidth = width
        this.frameHeight = height
        val nv21Copy = ByteArray(nv21Data.size)
        System.arraycopy(nv21Data, 0, nv21Copy, 0, nv21Data.size)
        return nv21Copy
    }

    /**
     * Render to NV21 using EGL texture.
     */
    private fun renderToNv21(): ByteArray? {
        try {
            if (!glInitialized || glProgram < 0) return null

            val w = frameWidth
            val h = frameHeight
            val ySize = w * h
            val chromaSize = ySize / 4
            val totalSize = ySize + 2 * chromaSize

            // Allocate output buffer
            val outputBuffer = ByteBuffer.allocateDirect(totalSize).apply {
                order(ByteOrder.nativeOrder())
            }

            // Set up YUV textures
            val textures = IntArray(3)
            glGenTextures(3, textures)

            // Y plane (luminance)
            val yTexture = textures[0]
            glActiveTexture(0x0DE0) // GL_TEXTURE0
            glBindTexture(0x8063) // GL_TEXTURE_EXTERNAL_OES
            glTexParameteri(0x8063, 0x2801, 0x2000) // GL_TEXTURE_MAG_FILTER, GL_LINEAR
            glTexParameteri(0x8063, 0x2800, 0x2001) // GL_TEXTURE_MIN_FILTER, GL_LINEAR
            glTexParameteri(0x8063, 0x2100, 0x2000) // GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE
            glTexParameteri(0x8063, 0x2101, 0x2000) // GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE

            // Upload Y plane to texture
            glTexImage2D(
                0x8063, // GL_TEXTURE_EXTERNAL_OES
                0, // level
                0x1900, // GL_LUMINANCE
                w, h, 0,
                0x1900, // GL_LUMINANCE
                0x1401, // GL_UNSIGNED_BYTE
                yPlane.asByteBuffer()
            )

            // U plane (chrominance)
            val uTexture = textures[1]
            glActiveTexture(0x0DE1) // GL_TEXTURE1
            glTexImage2D(
                0x8063, 0, 0x1900, w / 2, h / 2, 0,
                0x1900, 0x1401, uPlane.asByteBuffer()
            )

            // V plane (chrominance)
            val vTexture = textures[2]
            glActiveTexture(0x0DE2) // GL_TEXTURE2
            glTexImage2D(
                0x8063, 0, 0x1900, w / 2, h / 2, 0,
                0x1900, 0x1401, vPlane.asByteBuffer()
            )

            // Set up vertex data
            val vertexBuffer = java.nio.FloatBuffer.allocate(12).apply {
                put(quadVertices)
                position(0)
            }

            val texCoordBuffer = java.nio.FloatBuffer.allocate(8).apply {
                put(quadTextures)
                position(0)
            }

            // Use shader program
            glUseProgram(glProgram)

            // Set vertex attributes
            glEnableVertexAttribArray(vPositionHandle)
            glVertexAttribPointer(vPositionHandle, 3, 0x1406, 0, 12, vertexBuffer)

            glEnableVertexAttribArray(vTextureCoordHandle)
            glVertexAttribPointer(vTextureCoordHandle, 2, 0x1406, 0, 8, texCoordBuffer)

            // Set texture uniforms
            glUseProgram(glProgram)
            glActiveTexture(0x0DE0)
            glUniform1i(yTextureHandle, 0)
            glBindTexture(0x8063, yTexture)

            glActiveTexture(0x0DE1)
            glUniform1i(uTextureHandle, 1)
            glBindTexture(0x8063, uTexture)

            glActiveTexture(0x0DE2)
            glUniform1i(vTextureHandle, 2)
            glBindTexture(0x8063, vTexture)

            // Draw the quad
            glDrawArrays(0x0003, 0, 4) // GL_TRIANGLE_STRIP

            // Read pixels
            glReadPixels(0, 0, w, h, 0x1907, 0x1401, outputBuffer)

            // Clean up textures
            glDeleteTextures(3, textures)

            val result = ByteArray(outputBuffer.remaining())
            outputBuffer.get(result)
            return result

        } catch (e: Exception) {
            logAction("$TAG: Render to NV21 failed: ${e.message}")
            return null
        }
    }

    /**
     * Convert YUV planes to NV21 format (direct copy, no shader).
     */
    private fun copyYuvToNv21(yPlane: ByteArray, uPlane: ByteArray, vPlane: ByteArray, width: Int, height: Int): ByteArray {
        val ySize = width * height
        val chromaSize = ySize / 4
        val totalSize = ySize + 2 * chromaSize
        val nv21 = ByteArray(totalSize)

        // Copy Y plane
        System.arraycopy(yPlane, 0, nv21, 0, minOf(yPlane.size, ySize))

        // Swap U and V planes for NV21 (NV21 = Y + VU interleaved)
        val nv21Offset = ySize
        val minUSize = minOf(uPlane.size, chromaSize)
        val minVSize = minOf(vPlane.size, chromaSize)
        for (i in 0 until minUSize) {
            nv21[nv21Offset + i * 2 + 1] = uPlane[i]
            nv21[nv21Offset + i * 2] = vPlane[i]
        }

        return nv21
    }

    /**
     * Convert YUV planes to JPEG bytes.
     */
    fun toJpeg(yPlane: ByteArray, uPlane: ByteArray, vPlane: ByteArray, width: Int, height: Int, quality: Int = 95): ByteArray? {
        val nv21 = copyYuvToNv21(yPlane, uPlane, vPlane, width, height)
        return nv21ToJpeg(nv21, width, height, quality)
    }

    /**
     * Convert NV21 to JPEG bytes.
     */
    private fun nv21ToJpeg(nv21: ByteArray, width: Int, height: Int, quality: Int): ByteArray? {
        try {
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            val out = java.io.ByteArrayOutputStream()
            return if (yuvImage.compressToJpeg(Rect(0, 0, width, height), quality, out)) {
                out.toByteArray()
            } else {
                null
            }
        } catch (e: Exception) {
            logAction("$TAG: NV21 to JPEG failed: ${e.message}")
            return null
        }
    }

    /**
     * Get NV21 size for given dimensions.
     */
    fun getNv21Size(width: Int, height: Int): Int {
        val ySize = width * height
        val chromaSize = ySize / 4
        return ySize + 2 * chromaSize
    }

    /**
     * Check if EGL is initialized.
     */
    fun isInitialized(): Boolean = glInitialized

    /**
     * Release EGL resources.
     */
    fun release() {
        try {
            if (glInitialized) {
                eglMakeCurrent(
                    glDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE,
                    EGL_NO_CONTEXT
                )
                glSurface?.let { eglDestroySurface(glDisplay!!, it) }
                glContext?.let { eglDestroyContext(glDisplay!!, it) }
                eglTerminate(glDisplay)
            }
            glInitialized = false
            logAction("$TAG: EGL released")
        } catch (e: Exception) {
            logAction("$TAG: EGL release failed: ${e.message}")
        }
    }

    // Extension: convert ByteArray to ByteBuffer for glTexImage2D
    private fun ByteArray.asByteBuffer(): ByteBuffer = ByteBuffer.wrap(this).apply {
        position(0)
        limit(size)
    }

    private fun minOf(a: Int, b: Int): Int = if (a < b) a else b
}
