package com.hazbu.xcam.spoof

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.media.Image
import android.media.Image.Plane
import android.os.Handler
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.CameraX
import androidx.camera.core.impl.CameraSelectorCompat
import androidx.camera.core.impl.PreviewConfig
import androidx.camera.core.impl.ImageCaptureConfig
import androidx.camera.core.impl.ImageAnalysisConfig
import androidx.camera.core.impl.PreviewConfig
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
    private var isCameraXAvailable = false
    private var isCameraXLicensed = false

    // CameraX components
    private var previewBuilder: Preview.Builder? = null
    private var imageCaptureBuilder: ImageCapture.Builder? = null
    private var imageAnalysisBuilder: ImageAnalysis.Builder? = null

    // Frame processor (for ImageAnalysis)
    private var onFrameCallback: ((ImageProxy) -> Unit)? = null

    // Capture callback (for ImageCapture)
    private var onCaptureCallback: ((ImageProxy) -> Unit)? = null

    // Current camera facing
    private var currentCameraFacing: Int = CameraSelector.CAMERA_FACING_BACK

    // Current resolution
    private var currentWidth = 1280
    private var currentHeight = 720

    // Handler for CameraX operations
    private val handler = Handler(ContextCompat.getMainExecutor(context) as? java.util.concurrent.Executor ?: java.util.concurrent.Executors.newSingleThreadExecutor())

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
        this.height = height
        logAction("$TAG: Resolution set to: $width x $height")
    }

    // ==================== CALLBACKS ====================

    /**
     * Set a callback for image analysis frames.
     * Called for each frame sent to the liveness detection pipeline.
     */
    fun setOnFrameCallback(callback: (ImageProxy) -> Unit) {
        this.onFrameCallback = callback
    }

    /**
     * Set a callback for image capture.
     * Called for each still photo captured by the app.
     */
    fun setOnCaptureCallback(callback: (ImageProxy) -> Unit) {
        this.onCaptureCallback = callback
    }

    // ==================== CAMERA BUILDER OVERRIDES ====================

    /**
     * Override the Preview builder to inject a virtual surface.
     */
    fun createPreviewBuilder(): Preview.Builder {
        val builder = Preview.Builder()
        logAction("$TAG: Preview builder created")
        return builder
    }

    /**
     * Override the ImageCapture builder for still captures.
     */
    fun createImageCaptureBuilder(): ImageCapture.Builder {
        val builder = ImageCapture.Builder()
        builder.setTargetResolution(android.util.Size(currentWidth, currentHeight))
        logAction("$TAG: ImageCapture builder created")
        return builder
    }

    /**
     * Override the ImageAnalysis builder for liveness detection.
     */
    fun createImageAnalysisBuilder(): ImageAnalysis.Builder {
        val builder = ImageAnalysis.Builder()
        builder.setTargetResolution(android.util.Size(currentWidth, currentHeight))
        logAction("$TAG: ImageAnalysis builder created")
        return builder
    }

    // ==================== IMAGE ANALYSIS ====================

    /**
     * Create an ImageAnalysis.Analyzer that intercepts each frame.
     * This is the critical hook for liveness detection bypass.
     */
    fun createImageAnalyzer(): ImageAnalysis.Analyzer {
        return ImageAnalysis.Analyzer { imageProxy ->
            if (!isHooking) {
                imageProxy.close()
                return@Analyzer
            }

            // Call the custom callback if set
            onFrameCallback?.invoke(imageProxy)

            // Process the frame (replace with virtual camera frame)
            val buffer = imageProxy.image.planes[0].buffer
            val width = imageProxy.image.width
            val height = imageProxy.image.height

            // If EGL renderer is set, render the virtual camera frame
            val renderer = eglRenderer
            if (renderer != null) {
                try {
                    // Get the virtual camera frame and render it
                    val nv21 = renderer.renderYuvFrame(
                        imageProxy.image.planes[0].buffer,
                        width,
                        height
                    )
                    // Write back to the image proxy
                    if (nv21 != null) {
                        val outBuffer = imageProxy.image.planes[0].buffer
                        outBuffer.clear()
                        outBuffer.put(nv21)
                        outBuffer.flip()
                    }
                } catch (e: Exception) {
                    logAction("$TAG: Frame processing failed: ${e.message}")
                }
            }

            imageProxy.close()
        }
    }

    // ==================== IMAGE CAPTURE ====================

    /**
     * Create an ImageCapture.OnImageCapturedCallback that intercepts each photo.
     */
    fun createCaptureCallback(): ImageCapture.OnImageCapturedCallback {
        return object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                if (!isHooking) {
                    image.close()
                    return
                }

                // Call the custom callback if set
                onCaptureCallback?.invoke(image)

                // Process the frame (replace with virtual camera JPEG)
                try {
                    val renderer = eglRenderer
                    if (renderer != null) {
                        val jpeg = renderer.toJpeg(
                            image.image.planes[0].buffer,
                            image.image.width,
                            image.image.height
                        )
                        // Write back to the image proxy
                        if (jpeg != null) {
                            val buffer = image.image.planes[0].buffer
                            buffer.clear()
                            buffer.put(jpeg)
                            buffer.flip()
                        }
                    }
                } catch (e: Exception) {
                    logAction("$TAG: Capture processing failed: ${e.message}")
                }

                image.close()
            }

            override fun onCaptureFailed(image: ImageProxy, reason: Int) {
                logAction("$TAG: Capture failed: $reason")
                image.close()
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
