package com.hazbu.xcam.core.virtualcamera

import android.annotation.SuppressLint
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import com.hazbu.xcam.data.Constants
import com.hazbu.xcam.utils.Logger
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Virtual Camera HAL3 Provider.
 *
 * This is the entry point for the Camera2 HAL. It registers with the Android
 * CameraServer (via CameraManager) as an external/USB camera device that
 * can serve all the same capabilities as a physical camera.
 *
 * Architecture:
 * ┌─────────────────────────────────────────────┐
 * │  CameraService (system)                      │
 * │  openCamera("2") → returns VirtualCameraDevice │
 * └─────────────────────────────────────────────┘
 *           │
 * ┌─────────▼───────────────────────────────────┐
 * │  VirtualCameraProvider (this class)          │
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
 * │  VirtualCameraSession (per-session)          │
 * │  • Handles capture callbacks                 │
 * │  • Renders frames via EglFrameRenderer       │
 * │  • Sends capture results to client           │
 * └─────────────────────────────────────────────┘
 */
@SuppressLint("MissingPermission", "UnsafeExperimentalAccessibilityApiUsage")
class VirtualCameraProvider(
    private val logAction: (String) -> Unit
) {
    companion object {
        private const val TAG = "xVirtCamProv"
        private const val VIRTUAL_CAMERA_ID = "2"
        private const val VIRTUAL_CAMERA_FACING = CameraCharacteristics.LENS_FACING_EXTERNAL
    }

    private val cameraManager: CameraManager? = try {
        val context = Class.forName("android.app.ContextImpl")
            .getMethod("getAppContext")
            .invoke(null) as? android.content.Context
        context?.getSystemService("camera") as? CameraManager
    } catch (_: Exception) {
        null
    }

    private var isRegistered = false
    private var isExternalCameraRegistered = false
    private val registeredDevices = mutableListOf<String>()
    private val deviceListeners = CopyOnWriteArrayList<DeviceListener>()

    // Handler thread for camera operations
    private val cameraThread = HandlerThread("xCam-VirtCam", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY).apply {
        start()
    }
    private val cameraHandler = Handler(cameraThread.looper)

    // Virtual camera device
    private var virtualDevice: VirtualCameraDevice? = null

    // EGL renderer for frame rendering
    private val eglRenderer = EglFrameRenderer { msg -> logAction("$TAG: $msg") }

    /**
     * Register the virtual camera with the CameraManager.
     * This makes it appear as an external/USB camera device.
     */
    fun register() {
        if (isRegistered) {
            logAction("$TAG: Already registered")
            return
        }

        try {
            cameraManager?.let { cm ->
                // Register as an external/USB camera via reflection
                val virtCamDeviceImpl = object {
                    fun getCharacteristics(): Any {
                        return buildCameraCharacteristics()
                    }

                    @Suppress("UNCHECKED_CAST")
                    fun createDevice(): Any {
                        return VirtualCameraDevice(
                            VIRTUAL_CAMERA_ID,
                            VIRTUAL_CAMERA_FACING,
                            buildStreamConfigs(),
                            eglRenderer,
                            { msg -> logAction("$TAG: $msg") },
                            cameraHandler
                        ) as android.hardware.camera2.CameraDevice
                    }

                    fun getPhysicalCameraDeviceIds(): Array<String> {
                        return emptyArray()
                    }
                }

                val virtCamDeviceClass = Class.forName("android.hardware.camera2.CameraManager\$VirtualCameraDevice")
                val registerMethod = cm.javaClass.getMethod(
                    "registerVirtualCamera",
                    java.lang.String::class.java,
                    java.lang.Integer::class.java,
                    virtCamDeviceClass
                )
                registerMethod.invoke(cm, VIRTUAL_CAMERA_ID, VIRTUAL_CAMERA_FACING, virtCamDeviceImpl)

                isRegistered = true
                isExternalCameraRegistered = true
                logAction("$TAG: Registered virtual camera ID=$VIRTUAL_CAMERA_ID facing=EXTERNAL")
            }
        } catch (e: Exception) {
            logAction("$TAG: Failed to register virtual camera: ${e.message}")
        }
    }

    /**
     * Unregister the virtual camera from the CameraManager.
     */
    fun unregister() {
        try {
            val unregisterMethod = cameraManager!!.javaClass.getMethod(
                "unregisterVirtualCamera", java.lang.String::class.java
            )
            unregisterMethod.invoke(cameraManager, VIRTUAL_CAMERA_ID)
            isRegistered = false
            isExternalCameraRegistered = false
            logAction("$TAG: Unregistered virtual camera ID=$VIRTUAL_CAMERA_ID")
        } catch (e: Exception) {
            logAction("$TAG: Failed to unregister virtual camera: ${e.message}")
        }
    }

    /**
     * Check if the virtual camera is registered.
     */
    fun isRegistered(): Boolean = isRegistered

    /**
     * Get the virtual camera's current status.
     */
    fun getStatus(): String = when {
        isRegistered -> "REGISTERED (ID: $VIRTUAL_CAMERA_ID, FACING: EXTERNAL)"
        isExternalCameraRegistered -> "REGISTERED AS EXTERNAL"
        else -> "NOT REGISTERED"
    }

    /**
     * Build CameraCharacteristics for the virtual camera.
     * This tells the Camera2 framework what capabilities the virtual camera has.
     */
    private fun buildCameraCharacteristics(): CameraCharacteristics {
        // Raw CameraCharacteristics keys (from Android source)
        val KEY_LENS_FACING = 0x1003
        val KEY_HARDWARE_LEVEL = 0x100e
        val KEY_INFO_SUPPORTED_HARDWARE_LEVEL = 0x100f
        val KEY_INFO_SENSOR_PHYSICAL_SIZE = 0x1014
        val KEY_NOISE_PROFILE = 0x1015
        val KEY_OPTICAL_LENS_FACING = 0x1018
        val KEY_INFO_USING_DYNAMIC_VIDEO_STABILIZATION_MODE = 0x1019
        val KEY_REQUEST_AVAILABLE_CAPABILITIES = 0x1026
        val KEY_SCALER_STREAM_CONFIGURATION_MAP = 0x100d
        val KEY_INFO_AVAILABLE_HOT_STANDBY = 0x1022
        val KEY_LENS_FOCAL_LENGTH = 0x101a
        val KEY_DISTORTION_MODEL = 0x101b
        val KEY_LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_CAMES = 0x1029
        val KEY_INFO_SUPPORTED_NAMED_IMAGES = 0x1033
        val KEY_SENSOR_STATIC_SENSOR_TIMING = 0x1027

        val map = java.util.HashMap<Any, Any>()
        fun set(key: Any, value: Any) {
            map[key] = value
        }

        // Camera facing: EXTERNAL (2) = external/USB/virtual
        set(KEY_LENS_FACING, VIRTUAL_CAMERA_FACING)

        // Hardware level: FULL = 3
        set(KEY_HARDWARE_LEVEL, 3)

        // Info: this is a virtual/external camera
        set(KEY_INFO_SUPPORTED_HARDWARE_LEVEL, 3)

        // Sensor: external
        set(KEY_INFO_SENSOR_PHYSICAL_SIZE, 1.0f)
        set(KEY_NOISE_PROFILE, floatArrayOf(0f, 0f))

        // Optical: external
        set(KEY_OPTICAL_LENS_FACING, VIRTUAL_CAMERA_FACING)

        // Info: external camera
        set(KEY_INFO_USING_DYNAMIC_VIDEO_STABILIZATION_MODE, 1)

        // Request: supported capabilities
        set(KEY_REQUEST_AVAILABLE_CAPABILITIES, intArrayOf(
            0x0001, // BUNDLE
            0x0002, // BURST_CAPTURE
            0x0003, // CALIBRATED_LENS
            0x0004, // CONSTANT_VIDEO_STABILIZATION_MODE
            0x0006, // DYNAMIC_RANGE_10_BIT
            0x0007, // DYNAMIC_RANGE_HIGH
            0x0008, // DYNAMIC_RANGE_WIDE
            0x0009, // FIXED_DEVICE_FOCUS
            0x000a, // FLASH
            0x000b, // FOCUS_AREAS
            0x000c, // LOGICAL_MULTI_CAMERA
            0x000d, // MANUAL_LENS_MANAGEMENT
            0x000e, // NATIVE
            0x000f, // PARALLEL_OUTPUT
            0x0010, // PER_FRAME_DATA_PIPELINE
            0x0011, // POST_PROCESSING_RATE
            0x0012, // RAW
            0x0013, // RAW_DEPTH
            0x0014, // SEMI_RAW
            0x0015, // SHARPNESS
            0x0016, // SLOW_MOTION
            0x0017, // SNAPSHOT_HDR
            0x0018, // STEREO_CAPTURE
            0x0019, // STEREO_VIDEO
            0x001a, // SYNC
            0x001b, // TURBO_3A
            0x001c, // UPSTREAM_PROCESSED
            0x001d, // VENDOR
            0x001e, // YCBCR_420
            0x001f, // YUV_RESTRICTED
            0x0020, // ZSL
        ))

        // Stream configuration map
        val configs = buildStreamConfigs()
        set(KEY_SCALER_STREAM_CONFIGURATION_MAP, configs)

        // Hot standby: not supported
        set(KEY_INFO_AVAILABLE_HOT_STANDBY, 0)

        // Focal length range
        set(KEY_LENS_FOCAL_LENGTH, 1.0f)

        // Distortion model
        set(KEY_DISTORTION_MODEL, floatArrayOf(
            1.0f, 0f, 0f, 0f,
            0f, 1.0f, 0f, 0f,
            0f, 0f, 1.0f, 0f,
            0f, 0f, 0f, 1.0f
        ))

        // Logical multi-camera
        set(KEY_LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_CAMES, emptyArray<String>())
        set(KEY_INFO_SUPPORTED_NAMED_IMAGES, emptyArray<String>())

        return CameraCharacteristics::class.java.getDeclaredConstructor(
            java.lang.String::class.java
        ).newInstance(VIRTUAL_CAMERA_ID).apply {
            val putMethod = javaClass.getMethod("put", Any::class.java, Any::class.java)
            for ((key, value) in map.entries) {
                putMethod.invoke(this, key, value)
            }
        }
    }

    /**
     * Build StreamConfigurationMap with supported output sizes.
     * This tells the Camera2 framework what stream sizes this virtual camera supports.
     */
    private fun buildStreamConfigs(): StreamConfigurationMap {
        val outputSizes = arrayOf(
            Size(640, 480),
            Size(1280, 720),
            Size(1920, 1080),
            Size(2560, 1440),
            Size(3840, 2160),
            Size(4096, 2160),
            Size(4096, 2160)
        )

        val inputSizes = arrayOf(
            Size(1920, 1080),
            Size(1280, 720),
            Size(640, 480)
        )

        val outputTypes = arrayOf(
            android.graphics.ImageFormat.YUV_420_888,
            android.graphics.ImageFormat.NV21,
            android.graphics.ImageFormat.JPEG
        )

        val inputTypes = emptyArray<Int>()

        return StreamConfigurationMap::class.java.getDeclaredConstructor(
            Array<Size>::class.java,
            Array<Size>::class.java,
            Array<Int>::class.java,
            Array<Int>::class.java
        ).newInstance(
            outputSizes as Array<Size>,
            inputSizes as Array<Size>,
            outputTypes as Array<Int>,
            inputTypes as Array<Int>
        )
    }

    /**
     * Get the virtual camera's EGL renderer.
     */
    fun getEglRenderer(): EglFrameRenderer = eglRenderer

    /**
     * Release all resources.
     */
    fun release() {
        unregister()
        eglRenderer.release()
        cameraThread.quitSafely()
        virtualDevice?.release()
        virtualDevice = null
        logAction("$TAG: Virtual camera provider released")
    }

    /**
     * Interface for virtual camera device lifecycle events.
     */
    interface DeviceListener {
        fun onCameraOpened(deviceId: String)
        fun onCameraClosed(deviceId: String)
        fun onCameraError(deviceId: String, error: Int)
    }
}
