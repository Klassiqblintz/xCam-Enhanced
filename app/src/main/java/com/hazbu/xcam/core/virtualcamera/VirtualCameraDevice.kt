package com.hazbu.xcam.core.virtualcamera

import android.annotation.SuppressLint
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.util.SparseIntArray
import android.view.Surface
import com.hazbu.xcam.core.capture.CaptureManager
import com.hazbu.xcam.core.capture.MediaCodecYuvDecoder
import com.hazbu.xcam.core.engine.MediaEngine
import com.hazbu.xcam.core.engine.XCamEngine
import com.hazbu.xcam.core.settings.SettingsManager
import com.hazbu.xcam.core.surface.SurfaceManager
import com.hazbu.xcam.data.Constants
import com.hazbu.xcam.utils.Logger
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Virtual Camera HAL3 Device.
 *
 * Represents a single virtual camera device from the CameraServer's perspective.
 * Handles opening the device, creating capture sessions, and routing capture
 * requests to the appropriate output surfaces.
 *
 * This is called by the CameraService when an app calls:
 *   CameraManager.openCamera("2", callback, handler)
 *
 * The returned object implements:
 *   - CameraDevice (for device state management)
 *   - createCaptureSession() → VirtualCameraSession
 *   - createCaptureRequest() → CaptureRequest.Builder
 *   - capture() / setRepeatingRequest() / captureBurst()
 */
@SuppressLint("MissingPermission")
class VirtualCameraDevice(
    private val deviceId: String,
    private val deviceFacing: Int,
    private val streamConfigs: android.hardware.camera2.params.StreamConfigurationMap,
    private val eglRenderer: EglFrameRenderer,
    private val logAction: (String) -> Unit,
    private val handler: Handler
) {

    companion object {
        private const val TAG = "xVirtCamDev"

        // Default preview orientation
        private val ORIENTATION_MAP = SparseIntArray().apply {
            put(0, 90)
            put(90, 0)
            put(180, 270)
            put(270, 180)
        }
    }

    // State
    private var isInitialized = false
    private var isDestroyed = false
    private val activeSessions = CopyOnWriteArrayList<VirtualCameraSession>()

    // Camera state callback (set by caller)
    private var stateCallback: CameraDevice.StateCallback? = null

    // Settings
    private var isMirrored = false
    private var rotationAngle = 0
    private var mediaPath: String? = null

    // Video decoder for virtual camera feed
    private var yuvDecoder: MediaCodecYuvDecoder? = null
    private var mediaEngine: MediaEngine? = null
    private var captureManager: CaptureManager? = null

    // Surface manager for tracking input/output surfaces
    private val surfaceManager = SurfaceManager { msg -> logAction("$TAG: $msg") }

    // Capture state
    private var isCapturing = false
    private var currentCaptureWidth = 1280
    private var currentCaptureHeight = 720

    // Handler for camera operations
    private val cameraHandler = handler

    /**
     * Initialize the virtual camera device.
     */
    fun initialize() {
        isInitialized = true
        logAction("$TAG: Virtual camera device initialized (ID: $deviceId, Facing: $deviceFacing)")
    }

    /**
     * Set the state callback.
     */
    fun setStateCallback(callback: CameraDevice.StateCallback) {
        stateCallback = callback
        // Report success immediately (virtual camera is always "ready")
        cameraHandler.post {
            callback.onOpened(object : CameraDevice {
                override fun getId(): String = deviceId
                override fun createCaptureSession(
                    outputSurfaces: List<Surface>,
                    sessionCallback: CameraCaptureSession.StateCallback,
                    handler: Handler?
                ): CameraCaptureSession = this@VirtualCameraDevice.createCaptureSession(
                    outputSurfaces, sessionCallback, handler
                )
                override fun createCaptureRequest(template: Int): CaptureRequest.Builder =
                    this@VirtualCameraDevice.createCaptureRequest(template)
                override fun capture(
                    requests: Array<out CaptureRequest>,
                    callback: CameraCaptureSession.CaptureCallback?,
                    handler: Handler?
                ): Unit = this@VirtualCameraDevice.capture(requests, callback, handler)
                override fun setRepeatingRequest(
                    request: CaptureRequest,
                    callback: CameraCaptureSession.CaptureCallback?,
                    handler: Handler?
                ): Unit = this@VirtualCameraDevice.setRepeatingRequest(request, callback, handler)
                override fun captureBurst(
                    requests: List<out CaptureRequest>,
                    callback: CameraCaptureSession.CaptureCallback?,
                    handler: Handler?
                ): Unit = this@VirtualCameraDevice.captureBurst(requests, callback, handler)
                override fun cancelCaptures(): Unit = Unit
                override fun setSessionThermalStatus(thermalStatus: Int): Unit = Unit
                override fun setTorchState(state: Boolean): Boolean = false
                override fun getColdFlashCharacteristics(): android.hardware.camera2.FlashInfo.ColdFlash? = null
                override fun getChargedFlashCharacteristics(): android.hardware.camera2.FlashInfo.ChargedFlash? = null
                override fun release(): Unit = this@VirtualCameraDevice.release()
                override fun hashCode(): Int = this@VirtualCameraDevice.hashCode()
                override fun equals(other: Any?): Boolean = this@VirtualCameraDevice == other
                override fun toString(): String = this@VirtualCameraDevice.toString()
            })
        }
    }

    /**
     * Create a capture session with the given output surfaces.
     * This is called by the Camera2 framework when an app creates a capture session.
     */
    @Synchronized
    fun createCaptureSession(
        outputSurfaces: List<Surface>,
        sessionCallback: CameraCaptureSession.StateCallback,
        handler: Handler?
    ): VirtualCameraSession {
        val session = VirtualCameraSession(
            deviceId,
            outputSurfaces,
            streamConfigs,
            eglRenderer,
            { msg -> logAction("$TAG: Session: $msg") },
            cameraHandler,
            sessionCallback,
            handler
        )

        activeSessions.add(session)
        logAction("$TAG: Created capture session with ${outputSurfaces.size} output surfaces")
        return session
    }

    /**
     * Create a capture request for the given template.
     */
    fun createCaptureRequest(template: Int): CaptureRequest.Builder {
        return CaptureRequest.Builder(
            android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE,
            template
        ).apply {
            // Set default controls
            set(android.hardware.camera2.CaptureRequest.CONTROL_MODE, 1) // ENABLED
            set(android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE,
                android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            set(android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE,
                android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE_ON)
        }
    }

    /**
     * Capture a single request.
     */
    fun capture(requests: Array<out CaptureRequest>, callback: CameraCaptureSession.CaptureCallback?,
                handler: Handler?) {
        // Route to active sessions
        for (session in activeSessions) {
            session.capture(requests, callback, handler)
        }
    }

    /**
     * Set up repeating requests.
     */
    fun setRepeatingRequest(request: CaptureRequest, callback: CameraCaptureSession.CaptureCallback?,
                            handler: Handler?) {
        for (session in activeSessions) {
            session.setRepeatingRequest(request, callback, handler)
        }
    }

    /**
     * Set up repeating requests for a burst.
     */
    fun captureBurst(requests: List<out CaptureRequest>, callback: CameraCaptureSession.CaptureCallback?,
                     handler: Handler?) {
        for (session in activeSessions) {
            session.captureBurst(requests, callback, handler)
        }
    }

    /**
     * Get the camera ID.
     */
    fun getId(): String = deviceId

    /**
     * Get the camera facing direction.
     */
    fun getFacing(): Int = deviceFacing

    /**
     * Check if the device is initialized.
     */
    fun isInitialized(): Boolean = isInitialized

    /**
     * Check if the device is destroyed.
     */
    fun isDestroyed(): Boolean = isDestroyed

    /**
     * Release all resources.
     */
    @Synchronized
    fun release() {
        isDestroyed = true
        for (session in activeSessions.toList()) {
            session.release()
        }
        activeSessions.clear()
        yuvDecoder?.stop()
        mediaEngine?.stop()
        logAction("$TAG: Virtual camera device released")
    }

    /**
     * Set the virtual camera media path.
     */
    fun setMediaPath(path: String?) {
        this.mediaPath = path
        if (!path.isNullOrEmpty()) {
            // Start video decoder for the virtual camera
            startVideoDecoder(path)
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
     * Start the video decoder for the virtual camera feed.
     */
    private fun startVideoDecoder(path: String) {
        yuvDecoder?.stop()
        mediaEngine?.stop()

        // Start the video engine
        mediaEngine = MediaEngine { msg -> logAction("$TAG: Engine: $msg") }

        // Start the video decoder
        yuvDecoder = MediaCodecYuvDecoder(
            android.content.Context::class.java.classLoader
                ?.loadClass("android.content.Context")?.getDeclaredField("CONTEXT_SERVICE")
                ?.get(null) as? android.content.Context
                ?: android.content.Context.createApplicationContext(),
            path,
            { msg -> logAction("$TAG: Decoder: $msg") }
        )

        // Get the context from the handler
        val context = try {
            val activityThread = Class.forName("android.app.ActivityThread")
            val currentApplication = activityThread.getMethod("currentApplication")
                .invoke(null) as? android.app.Application
            currentApplication ?: android.content.Context.createApplicationContext()
        } catch (_: Exception) {
            android.content.Context.createApplicationContext()
        }

        yuvDecoder = MediaCodecYuvDecoder(
            context,
            path,
            { msg -> logAction("$TAG: Decoder: $msg") }
        )
        yuvDecoder?.start()
        logAction("$TAG: Video decoder started for path: $path")
    }

    /**
     * Get the latest YUV frame from the video decoder.
     */
    fun getLatestYuvFrame(): MediaCodecYuvDecoder.YuvFrame? {
        return yuvDecoder?.latestFrame
    }

    /**
     * Check if the virtual camera is active (has a valid video feed).
     */
    fun isActive(): Boolean = isInitialized && mediaPath != null

    /**
     * Get the current capture dimensions.
     */
    fun getCaptureDimensions(): Pair<Int, Int> {
        return Pair(currentCaptureWidth, currentCaptureHeight)
    }

    /**
     * Set the current capture dimensions.
     */
    fun setCaptureDimensions(width: Int, height: Int) {
        this.currentCaptureWidth = width
        this.currentCaptureHeight = height
    }

    /**
     * Get the current capture size.
     */
    fun getCaptureSize(): Int {
        return currentCaptureWidth * currentCaptureHeight
    }

    /**
     * Get the stream configuration map.
     */
    fun getStreamConfigs(): android.hardware.camera2.params.StreamConfigurationMap {
        return streamConfigs
    }

    /**
     * Check if the device is valid (initialized and not destroyed).
     */
    fun isValid(): Boolean = isInitialized && !isDestroyed

    /**
     * Get the number of active sessions.
     */
    fun getActiveSessionCount(): Int = activeSessions.size
}
