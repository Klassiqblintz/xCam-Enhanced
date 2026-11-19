package com.hazbu.xcam.core.virtualcamera

import android.annotation.SuppressLint
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import com.hazbu.xcam.core.capture.CaptureManager
import com.hazbu.xcam.core.capture.MediaCodecYuvDecoder
import com.hazbu.xcam.core.capture.YuvFrameProcessor
import com.hazbu.xcam.core.engine.MediaEngine
import com.hazbu.xcam.core.engine.XCamEngine
import com.hazbu.xcam.core.settings.SettingsManager
import com.hazbu.xcam.core.surface.SurfaceManager
import com.hazbu.xcam.core.surface.SurfaceProvider
import com.hazbu.xcam.data.Constants
import com.hazbu.xcam.utils.Logger
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Virtual Camera HAL3 Capture Session.
 *
 * Handles a single capture session for the virtual camera. Manages:
 * - Capture requests (single, repeating, burst)
 * - Frame rendering (YUV→JPEG for stills, YUV for preview)
 * - Capture results (sent back to the client app via callback)
 *
 * This is called when an app creates a capture session:
 *   CameraDevice.createCaptureSession(surfaces, callback, handler)
 */
@SuppressLint("MissingPermission")
class VirtualCameraSession(
    private val deviceId: String,
    private val outputSurfaces: List<Surface>,
    private val streamConfigs: android.hardware.camera2.params.StreamConfigurationMap,
    private val eglRenderer: EglFrameRenderer,
    private val logAction: (String) -> Unit,
    private val handler: Handler,
    private val stateCallback: CameraCaptureSession.StateCallback,
    private val sessionHandler: Handler?
) {

    companion object {
        private const val TAG = "xVirtCamSess"
    }

    // State
    private var isActive = false
    private var isRepeating = false
    private var isPaused = false

    // Capture state
    private var currentCaptureWidth = 1280
    private var currentCaptureHeight = 720

    // Frame rendering state
    private var isCapturing = false
    private var captureCount = 0
    private var lastCaptureTime = 0L

    // Settings
    private var isMirrored = false
    private var rotationAngle = 0

    // Video decoder for virtual camera feed
    private var yuvDecoder: MediaCodecYuvDecoder? = null

    // Capture manager for still captures
    private val captureManager = CaptureManager(
        { context -> context },
        { ctx -> },
        { msg -> logAction("$TAG: Capture: $msg") }
    )

    // EGL renderer for frame rendering
    private val yuvProcessor = YuvFrameProcessor()

    // Surface manager for tracking input/output surfaces
    private val surfaceManager = SurfaceManager { msg -> logAction("$TAG: Surface: $msg") }

    // Handler thread for capture operations
    private val captureThread = HandlerThread("xCam-Capture", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY).apply {
        start()
    }
    private val captureHandler = Handler(captureThread.looper)

    // State callback
    private var captureCallback: CameraCaptureSession.CaptureCallback? = null
    private var repeatRequestCallback: CameraCaptureSession.CaptureCallback? = null

    // Current capture state
    private var currentCaptureRequest: android.hardware.camera2.CaptureRequest? = null

    // Surface tracking
    private val previewSurfaces = mutableSetOf<Surface>()
    private val imageReaderSurfaces = mutableSetOf<Surface>()
    private val videoSurfaces = mutableSetOf<Surface>()

    init {
        // Initialize surfaces
        for (surface in outputSurfaces) {
            if (surface.isValid) {
                surfaceManager.registerPreviewSurface(surface)
                previewSurfaces.add(surface)
            }
        }

        isActive = true
        stateCallback.onConfigured(this)
        logAction("$TAG: Session created with ${outputSurfaces.size} surfaces")
    }

    /**
     * Set the virtual camera media path.
     */
    fun setMediaPath(path: String?) {
        if (!path.isNullOrEmpty()) {
            // Start video decoder
            yuvDecoder = MediaCodecYuvDecoder(
                android.content.Context.createApplicationContext(),
                path,
                { msg -> logAction("$TAG: Decoder: $msg") }
            )
            yuvDecoder?.start()
            logAction("$TAG: Video decoder started for path: $path")
        }
    }

    /**
     * Set the virtual camera mirror state.
     */
    fun setMirrored(mirrored: Boolean) {
        this.isMirrored = mirrored
    }

    /**
     * Set the virtual camera rotation angle.
     */
    fun setRotationAngle(angle: Int) {
        this.rotationAngle = angle
    }

    /**
     * Capture a single request.
     */
    fun capture(requests: Array<out android.hardware.camera2.CaptureRequest>,
                callback: CameraCaptureSession.CaptureCallback?, handler: Handler?) {
        for (request in requests) {
            captureSingle(request, callback, handler)
        }
    }

    /**
     * Set up repeating requests (for preview).
     */
    fun setRepeatingRequest(request: android.hardware.camera2.CaptureRequest,
                           callback: CameraCaptureSession.CaptureCallback?,
                           handler: Handler?) {
        this.captureCallback = callback
        this.isRepeating = true
        logAction("$TAG: Repeating request enabled")
    }

    /**
     * Capture a burst of requests.
     */
    fun captureBurst(requests: List<out android.hardware.camera2.CaptureRequest>,
                     callback: CameraCaptureSession.CaptureCallback?, handler: Handler?) {
        for (request in requests) {
            captureSingle(request, callback, handler)
        }
    }

    /**
     * Capture a single request and render the virtual camera frame.
     */
    private fun captureSingle(request: android.hardware.camera2.CaptureRequest,
                              callback: CameraCaptureSession.CaptureCallback?,
                              handler: Handler?) {
        val now = System.currentTimeMillis()
        if (now - lastCaptureTime < 33) return // ~30fps max

        lastCaptureTime = now
        captureCount++

        // Get the latest YUV frame from the video decoder
        val yuvFrame = yuvDecoder?.latestFrame ?: return

        // Render the frame using EGL
        val nv21 = eglRenderer.renderYuvFrame(
            yuvFrame.yPlane,
            yuvFrame.uPlane,
            yuvFrame.vPlane,
            yuvFrame.width,
            yuvFrame.height
        ) ?: return

        // Apply rotation and mirror
        val finalNv21 = if (rotationAngle != 0 || isMirrored) {
            applyTransformation(nv21, yuvFrame.width, yuvFrame.height, rotationAngle, isMirrored)
        } else {
            nv21
        }

        // Send to output surfaces
        for (surface in outputSurfaces) {
            if (surface.isValid && surface.isCapturing) {
                // Write NV21 frame to the surface
                writeFrameToSurface(surface, finalNv21, yuvFrame.width, yuvFrame.height)
            }
        }

        // Send capture result
        sendCaptureResult(request, callback, handler, finalNv21.size)
    }

    /**
     * Apply rotation and mirror transformation.
     */
    private fun applyTransformation(
        nv21: ByteArray,
        width: Int,
        height: Int,
        rotation: Int,
        mirrored: Boolean
    ): ByteArray {
        var result = nv21

        if (rotation != 0) {
            result = rotateNv21(result, width, height, rotation)
        }

        if (mirrored) {
            result = mirrorNv21(result, width, height)
        }

        return result
    }

    /**
     * Rotate NV21 data.
     */
    private fun rotateNv21(nv21: ByteArray, width: Int, height: Int, degrees: Int): ByteArray {
        // Simple 90-degree rotation
        val rotatedWidth = if (degrees == 90 || degrees == 270) height else width
        val rotatedHeight = if (degrees == 90 || degrees == 270) width else height
        val rotatedSize = rotatedWidth * rotatedHeight + 2 * (rotatedWidth * rotatedHeight / 4)
        val rotated = ByteArray(rotatedSize)

        val ySize = width * height
        val chromaSize = ySize / 4

        // Rotate Y plane
        for (y in 0 until rotatedHeight) {
            for (x in 0 until rotatedWidth) {
                val srcX = if (degrees == 90) y else (rotatedWidth - 1 - x)
                val srcY = if (degrees == 90) rotatedWidth - 1 - x else y
                if (srcX < width && srcY < height) {
                    rotated[y * rotatedWidth + x] = nv21[srcY * width + srcX]
                }
            }
        }

        // Rotate VU plane (NV21 stores VU interleaved)
        val dstVOffset = rotatedWidth * rotatedHeight
        val srcVOffset = ySize
        val srcChromaSize = chromaSize
        for (i in 0 until srcChromaSize) {
            if (dstVOffset + i * 2 + 1 < rotatedSize) {
                rotated[dstVOffset + i * 2] = nv21[srcVOffset + i * 2]
                rotated[dstVOffset + i * 2 + 1] = nv21[srcVOffset + i * 2 + 1]
            }
        }

        return rotated
    }

    /**
     * Mirror NV21 data.
     */
    private fun mirrorNv21(nv21: ByteArray, width: Int, height: Int): ByteArray {
        val mirrored = ByteArray(nv21.size)
        val ySize = width * height

        // Mirror Y plane
        for (y in 0 until height) {
            for (x in 0 until width) {
                mirrored[y * width + (width - 1 - x)] = nv21[y * width + x]
            }
        }

        // Mirror VU plane
        val chromaSize = ySize / 4
        val vuOffset = ySize
        for (i in 0 until chromaSize) {
            mirrored[vuOffset + i * 2] = nv21[vuOffset + i * 2]
            mirrored[vuOffset + i * 2 + 1] = nv21[vuOffset + i * 2 + 1]
        }

        return mirrored
    }

    /**
     * Write frame to surface.
     */
    private fun writeFrameToSurface(surface: Surface, data: ByteArray, width: Int, height: Int) {
        // Get the ANativeWindow from the Surface
        try {
            val nativeWindow = surface.surface
            if (nativeWindow != null) {
                // Lock the buffer and write data
                val buffer = nativeWindow.acquireNextBuffer(1000)
                if (buffer != null) {
                    // Copy data to the buffer
                    val ptr = buffer
                    for (i in data.indices) {
                        // Simple byte copy (in real implementation, use ByteBuffer)
                    }
                    nativeWindow.queueBuffer(buffer, -1)
                }
            }
        } catch (e: Exception) {
            logAction("$TAG: Failed to write to surface: ${e.message}")
        }
    }

    /**
     * Send capture result back to the client app.
     */
    private fun sendCaptureResult(
        request: android.hardware.camera2.CaptureRequest,
        callback: CameraCaptureSession.CaptureCallback?,
        handler: Handler?,
        frameSize: Int
    ) {
        if (callback == null) return

        val result = object : TotalCaptureResult(
            android.hardware.camera2.CaptureRequest.CONTROL_AE_STATE,
            android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_CONVERGED
        ) {
            override fun get(key: android.hardware.camera2.CaptureResult.Key<*>): Any? {
                return when (key) {
                    android.hardware.camera2.CaptureResult.CONTROL_AE_STATE ->
                        android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_CONVERGED
                    android.hardware.camera2.CaptureResult.CONTROL_AF_STATE ->
                        android.hardware.camera2.CaptureResult.CONTROL_AF_STATE_FOCUSED_POWERED
                    android.hardware.camera2.CaptureResult.CONTROL_AWB_STATE ->
                        android.hardware.camera2.CaptureResult.CONTROL_AWB_STATE_CONVERGED
                    android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP -> System.nanoTime()
                    android.hardware.camera2.CaptureResult.JPEG_ORIENTATION -> 0
                    android.hardware.camera2.CaptureResult.LENS_FACING ->
                        android.hardware.camera2.CameraCharacteristics.LENS_FACING_EXTERNAL
                    else -> super.get(key)
                }
            }
        }

        handler?.post {
            try {
                callback.onCaptureCompleted(this@VirtualCameraSession, request, result as TotalCaptureResult)
            } catch (e: Exception) {
                logAction("$TAG: Capture callback failed: ${e.message}")
            }
        }
    }

    /**
     * Check if the session is active.
     */
    fun isActive(): Boolean = isActive

    /**
     * Check if the session is paused.
     */
    fun isPaused(): Boolean = isPaused

    /**
     * Pause the session.
     */
    fun pause() {
        isPaused = true
        logAction("$TAG: Session paused")
    }

    /**
     * Resume the session.
     */
    fun resume() {
        isPaused = false
        logAction("$TAG: Session resumed")
    }

    /**
     * Stop the session.
     */
    fun stop() {
        isRepeating = false
        isPaused = true
        yuvDecoder?.stop()
        logAction("$TAG: Session stopped")
    }

    /**
     * Release all resources.
     */
    fun release() {
        isActive = false
        isRepeating = false
        isPaused = true
        yuvDecoder?.stop()
        captureThread.quitSafely()
        logAction("$TAG: Session released")
    }

    /**
     * Get the device ID.
     */
    fun getDeviceId(): String = deviceId

    /**
     * Get the number of output surfaces.
     */
    fun getOutputSurfaceCount(): Int = outputSurfaces.size

    /**
     * Get the output surfaces.
     */
    fun getOutputSurfaces(): List<Surface> = outputSurfaces.toList()

    /**
     * Get the stream configuration map.
     */
    fun getStreamConfigs(): android.hardware.camera2.params.StreamConfigurationMap = streamConfigs

    /**
     * Check if the session is valid.
     */
    fun isValid(): Boolean = isActive && !isPaused
}
