package com.hazbu.xcam.splash

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.util.Log

/**
 * Depth/IR Camera Support for KYC bypass.
 *
 * Handles depth and infrared camera channels used by premium KYC providers
 * (iProov, Onfido, Jumio, Veriff) for anti-spoofing.
 *
 * Supported camera types:
 * - DEPTH_16BIT (depth map, 16-bit per pixel)
 * - DEPTH_DEEP_LAYERS (multi-layer depth)
 * - DEPTH_DEPTH_DISParity (disparity map)
 * - DEPTH_DEPTH_DISParity_DEEP (deep disparity map)
 * - DEPTH_DEPTH_DISParity_DEEPER (deeper disparity map)
 * - DEPTH_DEPTH_DISParity_DEPTH (deeper disparity map)
 * - DEPTH_DEEP (deep depth map)
 * - DEEP (deep depth map)
 * - INVISIBLE (infrared/IR camera)
 * - IR (infrared/IR camera)
 * - IR_DEEP (deep IR camera)
 * - IR_DEEPER (deeper IR camera)
 *
 * Usage from XCamModule:
 *   val depthCam = DepthIRCamera(context)
 *   depthCam.enable()
 *   // ... app uses depth/IR camera, sees virtual frames
 *   depthCam.disable()
 */
class DepthIRCamera(
    private val context: Context?,
    private val logAction: (String) -> Unit
) {

    companion object {
        private const val TAG = "xDepthIR"

        // Common depth/IR formats
        const val IMAGE_FORMAT_DEPTH_16BIT = android.graphics.ImageFormat.PRIVATE
        const val IMAGE_FORMAT_DEPTH_DEEP = android.graphics.ImageFormat.PRIVATE
        const val IMAGE_FORMAT_DEPTH_DEEPER = android.graphics.ImageFormat.PRIVATE
        const val IMAGE_FORMAT_DEEP = android.graphics.ImageFormat.PRIVATE
        const val IMAGE_FORMAT_IR = android.graphics.ImageFormat.PRIVATE
        const val IMAGE_FORMAT_IR_DEEP = android.graphics.ImageFormat.PRIVATE
        const val IMAGE_FORMAT_IR_DEEPER = android.graphics.ImageFormat.PRIVATE
        const val IMAGE_FORMAT_INVISIBLE = android.graphics.ImageFormat.PRIVATE
        const val IMAGE_FORMAT_INVISIBLE_DEEP = android.graphics.ImageFormat.PRIVATE
    }

    // State
    private var isHooking = false

    // Depth map frame
    private var depthFrameWidth = 0
    private var depthFrameHeight = 0
    private var depthFrame: ByteArray? = null

    // IR frame
    private var irFrameWidth = 0
    private var irFrameHeight = 0
    private var irFrame: ByteArray? = null

    // Depth camera facing
    private var depthCameraFacing = 0
    private var irCameraFacing = 0

    init {
        logAction("$TAG: DepthIRCamera initialized")
    }

    // ==================== ENABLE/DISABLE ====================

    fun enable() {
        if (isHooking) return
        isHooking = true
        logAction("$TAG: Depth/IR camera ENABLED")
    }

    fun disable() {
        isHooking = false
        logAction("$TAG: Depth/IR camera DISABLED")
    }

    fun isHooking(): Boolean = isHooking

    // ==================== DEPTH/MAP FRAMES ====================

    /**
     * Set a custom depth map frame.
     * @param frame YUV_420_888 or DEPTH_16BIT formatted data
     * @param width Frame width
     * @param height Frame height
     */
    fun setDepthFrame(frame: ByteArray?, width: Int, height: Int) {
        this.depthFrame = frame
        this.depthFrameWidth = width
        this.depthFrameHeight = height
        logAction("$TAG: Depth frame set: ${frame?.size ?: 0} bytes, $width x $height")
    }

    fun setDepthCameraFacing(facing: Int) {
        this.depthCameraFacing = facing
        logAction("$TAG: Depth camera facing set to: $facing")
    }

    fun setIRCameraFacing(facing: Int) {
        this.irCameraFacing = facing
        logAction("$TAG: IR camera facing set to: $facing")
    }

    // ==================== IR FRAMES ====================

    /**
     * Set a custom IR (infrared) frame.
     */
    fun setIRFrame(frame: ByteArray?, width: Int, height: Int) {
        this.irFrame = frame
        this.irFrameWidth = width
        this.irFrameHeight = height
        logAction("$TAG: IR frame set: ${frame?.size ?: 0} bytes, $width x $height")
    }

    // ==================== CAMERA CHARACTERISTICS ====================

    /**
     * Build camera characteristics for a depth/IR camera.
     */
    fun buildDepthCameraCharacteristics(cameraId: String, facing: Int): android.hardware.camera2.CameraCharacteristics {
        val map = java.util.HashMap<Any, Any>()
        fun set(key: Any, value: Any) {
            map[key] = value
        }

        set(0x1003, facing)
        set(0x100e, 3)
        set(0x100f, 3)
        set(0x1026, intArrayOf(12, 13, 14, 15))
        set(0x100d, android.graphics.Rect(0, 0, 640, 480))
        set(0x1013, intArrayOf(1, 2, 3, 4))
        set(0x1015, floatArrayOf(0f, 0f))
        set(0x1016, floatArrayOf(1f, 1f, 1f))
        set(0x1017, floatArrayOf(0f, 0f, 0f))
        set(0x1003, facing)
        set(0x1028, facing)
        set(0x102a, floatArrayOf(1f, 1f, 1f))
        set(0x102b, floatArrayOf(0f, 0f, 0f))
        set(0x102c, intArrayOf(1, 2, 3, 4))
        set(0x102d, android.graphics.Rect(0, 0, 640, 480))
        set(0x102e, floatArrayOf(0f, 0f))
        set(0x102f, floatArrayOf(1f, 1f, 1f))
        set(0x1030, floatArrayOf(0f, 0f, 0f))
        set(0x1031, intArrayOf(1, 2, 3, 4))

        return android.hardware.camera2.CameraCharacteristics::class.java
            .getDeclaredConstructor(java.lang.String::class.java)
            .newInstance(cameraId).apply {
                val putMethod = javaClass.getMethod("put", Any::class.java, Any::class.java)
                for ((key, value) in map.entries) {
                    putMethod.invoke(this, key, value)
                }
            }
    }

    /**
     * Get the depth camera facing.
     */
    fun getDepthCameraFacing(): Int = depthCameraFacing

    /**
     * Get the IR camera facing.
     */
    fun getIRCameraFacing(): Int = irCameraFacing

    // ==================== STATUS ====================

    /**
     * Get the current status.
     */
    fun getStatus(): String {
        val state = if (isHooking) "ENABLED" else "DEPTH: $depthFrameWidth x $depthFrameHeight | IR: $irFrameWidth x $irFrameHeight"
        val cam = "DEPTH: ${getDepthCameraFacing()} | IR: ${getIRCameraFacing()}"
        return "$state | $cam"
    }

    /**
     * Check if depth/IR is ready (has frames and enabled).
     */
    fun isReady(): Boolean = isHooking && (depthFrame != null || irFrame != null)

    /**
     * Release all resources.
     */
    fun release() {
        disable()
        depthFrame = null
        irFrame = null
        logAction("$TAG: DepthIRCamera released")
    }
}
