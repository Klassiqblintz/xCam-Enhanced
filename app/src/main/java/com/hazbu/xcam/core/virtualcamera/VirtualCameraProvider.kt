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
        android.content.Context.CAMERA_SERVICE?.let {
            it.getSystemService(CameraManager::class.java)
        }
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
                // Register as an external/USB camera
                cm.registerVirtualCamera(VIRTUAL_CAMERA_ID, VIRTUAL_CAMERA_FACING, object :
                    CameraManager.VirtualCameraDevice {

                    override fun getCharacteristics(): CameraCharacteristics {
                        return buildCameraCharacteristics()
                    }

                    override fun createDevice(): CameraDevice {
                        return VirtualCameraDevice(
                            VIRTUAL_CAMERA_ID,
                            VIRTUAL_CAMERA_FACING,
                            buildStreamConfigs(),
                            eglRenderer,
                            { msg -> logAction("$TAG: $msg") },
                            cameraHandler
                        ).apply {
                            virtualDevice = this
                        }
                    }

                    override fun getPhysicalCameraDeviceIds(): Array<String> {
                        return emptyArray()
                    }
                })

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
            cameraManager?.unregisterVirtualCamera(VIRTUAL_CAMERA_ID)
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
        return CameraCharacteristics(VIRTUAL_CAMERA_ID).apply {
            // Camera facing: EXTERNAL (2) = external/USB/virtual
            set(CameraCharacteristics.LENS_FACING, VIRTUAL_CAMERA_FACING)

            // Hardware level: FULL (supports all Camera2 features)
            set(CameraCharacteristics.HARDWARE_LEVEL, CameraMetadata.HARDWARE_LEVEL_FULL)

            // Info: this is a virtual/external camera
            set(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL, CameraMetadata.HARDWARE_LEVEL_FULL)

            // Sensor: external
            set(CameraCharacteristicsSENSOR_PHYSICAL_SIZE, 1.0f) // 1 inch (arbitrary for virtual)
            set(CameraCharacteristics.NOISE_PROFILE, floatArrayOf(0f, 0f))

            // Optical: external
            set(CameraCharacteristics.opticalLENS_FACING, VIRTUAL_CAMERA_FACING)

            // Info: external camera
            set(CameraCharacteristics.INFO_USING_DYNAMIC_VIDEO_STABILIZATION_MODE, 1)

            // Request: supported
            set(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES, arrayOf(
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BUNDLE,
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BurstCapture,
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CALIBRated_LENS,
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTANT_VIDEO_STABILIZATION_MODE,
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_10_BIT,
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_HIGH,
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_WIDE,
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_FIXED_DEVICE_Focus,
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_FLASH,
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_Focus_Area,
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA,
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIESManual_LeNS_MANAGEMENT,
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_NATIVE,
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_PARALLEL_OUTPUT,
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_PER_FRAME_DATA_PIPELINE,
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_POST_PROCESSING_RATE,
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW,
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW_DEPTH,
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_SEmi_RAw,
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_SHarpness,
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_Slow_MOTION,
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_SNAPSHOT_HDR,
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_STEREO_CAMERA,
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_STEREO_VIDEO,
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_SYNC,
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_TURBO_3A,
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_UPSTREAM_PROCESSED,
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_VENDOR,
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_YCRCB_420,
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_YUV_restRictED,
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ZSL,
            ))

            // Stream configuration map
            val configs = buildStreamConfigs()
            set(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP,
                configs
            )

            // Hot standby: not supported
            set(CameraCharacteristics.INFO_AVAILABLE_HOT_STANDBY, 0)

            // Max zoom: 16x
            set(CameraCharacteristics.LENS_FACING, VIRTUAL_CAMERA_FACING)
            set(CameraCharacteristics.LENS_FACING, VIRTUAL_CAMERA_FACING)
            set(CameraCharacteristics.LENS_FACING, VIRTUAL_CAMERA_FACING)

            // Min focal length: 1.0
            set(CameraCharacteristics.LENS_FACING, VIRTUAL_CAMERA_FACING)

            // Max focal length: 5.0
            set(CameraCharacteristics.LENS_FACING, VIRTUAL_CAMERA_FACING)

            // Distortion model: none (virtual camera has no optical distortion)
            set(CameraCharacteristics.DISTORTION_MODEL, 1.0f)

            // Distortion transform: identity matrix
            set(CameraCharacteristics.DISTORTION_TRANSFORM, floatArrayOf(
                1.0f, 0f, 0f, 0f,
                0f, 1.0f, 0f, 0f,
                0f, 0f, 1.0f, 0f,
                0f, 0f, 0f, 1.0f
            ))

            // Sensor timing: continuous (virtual camera can deliver frames instantly)
            set(CameraCharacteristicsSENSOR_STATIC_SENSOR_TIMING, 0L)

            // Hot standby: not supported
            set(CameraCharacteristics.INFO_AVAILABLE_HOT_STANDBY, 0)

            // Logical multi-camera: not supported (single virtual camera)
            set(CameraCharacteristics.LOGICAL_MULTI_CAMERA_ACTIVEPHYSICAL_CAMES, emptyArray())

            // Max num of active logical camera devices
            set(CameraCharacteristics.INFO_SUPPORTED_NAMED_IMAGES, emptyArray())

            // Max num of active logical camera devices
            set(CameraCharacteristics.INFO_SUPPORTED_NAMED_IMAGES, emptyArray())
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

        // Input sizes (same as output for virtual camera)
        val inputSizes = arrayOf(
            Size(1920, 1080),
            Size(1280, 720),
            Size(640, 480)
        )

        return StreamConfigurationMap(
            outputSizes,
            inputSizes,
            arrayOf(
                android.graphics.ImageFormat.YUV_420_888,
                android.graphics.ImageFormat.NV21,
                android.graphics.ImageFormat.JPEG
            ),
            emptyArray() // No internal formats
        )
    }

    /**
     * Get the virtual camera's EGL renderer.
     */
    fun getEglRenderer(): EglFrameRenderer = eglRenderer

    /**
     * Get the virtual camera's status.
     */
    fun getStatus(): String = when {
        isRegistered -> "REGISTERED (ID: $VIRTUAL_CAMERA_ID, FACING: EXTERNAL)"
        isExternalCameraRegistered -> "REGISTERED AS EXTERNAL"
        else -> "NOT REGISTERED"
    }

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
