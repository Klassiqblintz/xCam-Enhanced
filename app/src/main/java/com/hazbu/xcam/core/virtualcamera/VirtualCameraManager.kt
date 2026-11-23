package com.hazbu.xcam.core.virtualcamera

import android.annotation.SuppressLint
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.util.Log
import android.util.Size
import com.hazbu.xcam.data.Constants
import com.hazbu.xcam.utils.Logger
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Virtual Camera Manager - High-level coordinator for the Camera2 HAL3 virtual camera.
 *
 * This class:
 * 1. Registers the virtual camera with the Android CameraServer
 * 2. Manages the virtual camera lifecycle (start/stop)
 * 3. Provides the virtual camera status to other xCam components
 * 4. Handles frame rendering for the virtual camera feed
 *
 * Usage from XCamModule:
 *   val virtCam = VirtualCameraManager(context)
 *   virtCam.start()
 *   // ... app opens camera, receives virtual feed
 *   virtCam.stop()
 *
 * Architecture:
 * ┌─────────────────────────────────────────────┐
 * │  VirtualCameraManager (this class)           │
 * │  • Registers/Unregisters with CameraServer    │
 * │  • Manages VirtualCameraProvider             │
 * │  • Manages EglFrameRenderer                  │
 * │  • Provides status to xCamModule             │
 * └─────────────────────────────────────────────┘
 *           │
 * ┌─────────▼───────────────────────────────────┐
 * │  VirtualCameraProvider (HAL3 entry point)    │
 * │  • Registers with CameraManager              │
 * │  • Reports EXTERNAL hardware level           │
 * │  • Reports all supported stream configs      │
 * │  • Creates VirtualCameraDevice on open       │
 * └─────────────────────────────────────────────┘
 *           │
 * ┌─────────▼───────────────────────────────────┐
 * │  VirtualCameraDevice (per-camera instance)   │
 * │  • Creates capture sessions                  │
 * │  • Manages capture requests                  │
 * │  • Routes output to VirtualCameraSession     │
 * └─────────────────────────────────────────────┘
 *           │
 * ┌─────────▼───────────────────────────────────┐
 * │  VirtualCameraSession (per-capture)          │
 * │  • Handles capture callbacks                 │
 * │  • Renders frames via EglFrameRenderer       │
 * │  • Sends capture results to client           │
 * └─────────────────────────────────────────────┘
 *           │
 * ┌─────────▼───────────────────────────────────┐
 * │  EglFrameRenderer (frame rendering)          │
 * │  • EGL context/shaders for YUV→RGB           │
 * │  • Renders YUV_420_888 frames to NV21/JPEG   │
 * └─────────────────────────────────────────────┘
 */
@SuppressLint("MissingPermission")
class VirtualCameraManager(
    private val logAction: (String) -> Unit
) {
    companion object {
        private const val TAG = "xVirtCamMgr"
        private const val VIRTUAL_CAMERA_ID = "2"
        private const val VIRTUAL_CAMERA_FACING = CameraCharacteristics.LENS_FACING_EXTERNAL

        // Virtual camera capabilities
        private val SUPPORTED_STREAM_TYPES = intArrayOf(
            android.graphics.ImageFormat.YUV_420_888,
            android.graphics.ImageFormat.NV21,
            android.graphics.ImageFormat.JPEG
        )
    }

    // State
    private var isStarted = false
    private var isPaused = false

    // Components
    private val provider = VirtualCameraProvider { msg -> logAction("$TAG: Provider: $msg") }
    private val eglRenderer = EglFrameRenderer { msg -> logAction("$TAG: EGL: $msg") }

    // Settings
    private var mediaPath: String? = null
    private var isMirrored = false
    private var rotationAngle = 0

    // Listener
    private var statusListener: StatusListener? = null

    /**
     * Start the virtual camera.
     * This registers the virtual camera with the Android CameraServer.
     */
    fun start() {
        if (isStarted) {
            logAction("$TAG: Already started")
            return
        }

        try {
            // Register with CameraServer
            provider.register()
            isStarted = true

            logAction("$TAG: Virtual camera started successfully")
            logAction("$TAG: Status: ${provider.getStatus()}")

            // Notify listener
            statusListener?.onStatusChanged(true)
        } catch (e: Exception) {
            logAction("$TAG: Failed to start virtual camera: ${e.message}")
            isStarted = false
            statusListener?.onStatusChanged(false)
        }
    }

    /**
     * Stop the virtual camera.
     * This unregisters the virtual camera from the Android CameraServer.
     */
    fun stop() {
        if (!isStarted) {
            logAction("$TAG: Already stopped")
            return
        }

        try {
            provider.unregister()
            isStarted = false
            logAction("$TAG: Virtual camera stopped")

            // Notify listener
            statusListener?.onStatusChanged(false)
        } catch (e: Exception) {
            logAction("$TAG: Failed to stop virtual camera: ${e.message}")
        }
    }

    /**
     * Check if the virtual camera is started.
     */
    fun isStarted(): Boolean = isStarted

    /**
     * Check if the virtual camera is active (started and not paused).
     */
    fun isActive(): Boolean = isStarted && !isPaused

    /**
     * Pause the virtual camera (keep registered, but stop rendering).
     */
    fun pause() {
        isPaused = true
        logAction("$TAG: Virtual camera paused")
    }

    /**
     * Resume the virtual camera (start rendering again).
     */
    fun resume() {
        isPaused = false
        logAction("$TAG: Virtual camera resumed")
    }

    /**
     * Get the virtual camera's current status.
     */
    fun getStatus(): String = provider.getStatus()

    /**
     * Get the virtual camera's enabled state.
     */
    fun isEnabled(): Boolean = isStarted && !isPaused

    /**
     * Set the virtual camera media path.
     */
    fun setMediaPath(path: String?) {
        this.mediaPath = path
        logAction("$TAG: Media path set to: $path")
    }

    /**
     * Get the virtual camera media path.
     */
    fun getMediaPath(): String? = mediaPath

    /**
     * Set the virtual camera mirror state.
     */
    fun setMirrored(mirrored: Boolean) {
        this.isMirrored = mirrored
        logAction("$TAG: Mirror state set to: $mirrored")
    }

    /**
     * Get the virtual camera mirror state.
     */
    fun isMirrored(): Boolean = isMirrored

    /**
     * Set the virtual camera rotation angle.
     */
    fun setRotationAngle(angle: Int) {
        this.rotationAngle = angle
        logAction("$TAG: Rotation angle set to: $angle")
    }

    /**
     * Get the virtual camera rotation angle.
     */
    fun getRotationAngle(): Int = rotationAngle

    /**
     * Check if the virtual camera is ready to render frames.
     */
    fun isReadyForRendering(): Boolean = isStarted && !isPaused && mediaPath != null

    /**
     * Get the EGL renderer.
     */
    fun getEglRenderer(): EglFrameRenderer = eglRenderer

    /**
     * Get the virtual camera provider.
     */
    fun getProvider(): VirtualCameraProvider = provider

    /**
     * Release all resources.
     */
    fun release() {
        stop()
        eglRenderer.release()
        logAction("$TAG: Virtual camera manager released")
    }

    /**
     * Set the status listener.
     */
    fun setStatusListener(listener: StatusListener?) {
        this.statusListener = listener
    }

    /**
     * Interface for virtual camera status changes.
     */
    interface StatusListener {
        fun onStatusChanged(isActive: Boolean)
    }
}
