package com.hazbu.xcam.core.virtualcamera

/**
 * Configuration constants for the Camera2 HAL3 Virtual Camera.
 * Implements the Android Virtual Camera stack (AOSP hardware/interfaces/camera/).
 */
object VirtualCameraConstants {

    /**
     * Camera facing direction for the virtual camera.
     * EXTERNAL (2) = external/USB/virtual camera per CameraCharacteristics
     */
    const val VIRTUAL_CAMERA_FACING = android.hardware.camera2.CameraCharacteristics.LENS_FACING_EXTERNAL

    /**
     * Camera ID used to register with the CameraServer.
     * Typically camera ID "2" or "3" (after physical cameras 0 and 1).
     */
    const val VIRTUAL_CAMERA_ID = "2"

    /**
     * Hardware level reported by the virtual camera.
     * FULL = supports all Camera2 API features (most important for KYC bypass).
     * EXTERNAL = USB/virtual camera level (limited 3A, no flash).
     */
    const val VIRTUAL_HARDWARE_LEVEL = 3

    /**
     * Sensor info - this is a virtual/external sensor
     */
    const val VIRTUAL_SENSOR_ACTIVE_AE_AVAILABLE = true
    const val VIRTUAL_SENSOR_ACTIVE_AF_AVAILABLE = true
    const val VIRTUAL_SENSOR_ACTIVE_AWB_AVAILABLE = true

    /**
     * Maximum number of output streams supported.
     * We need at least: 1 preview + 1 ImageCapture + 1 ImageAnalysis + 1 VideoRecord
     */
    const val VIRTUAL_MAX_STREAMS = 4

    /**
     * Default virtual camera resolution (width x height in pixels).
     * Used when no specific resolution is requested.
     */
    const val VIRTUAL_DEFAULT_WIDTH = 1280
    const val VIRTUAL_DEFAULT_HEIGHT = 720

    /**
     * Supported JPEG resolutions for ImageCapture.
     */
    const val VIRTUAL_MAX_JPEG_SIZE = 1920 * 1080

    /**
     * Supported YUV/NV21 preview resolutions.
     */
    const val VIRTUAL_MIN_PREVIEW_SIZE = 320 * 240
    const val VIRTUAL_MAX_PREVIEW_SIZE = 3840 * 2160

    /**
     * Maximum number of concurrent capture requests.
     */
    const val VIRTUAL_MAX_REQUESTS = 16

    /**
     * Supported flash modes.
     */
    const val FLASH_MODE_OFF = "off"
    const val FLASH_MODE_ON = "on"
    const val FLASH_MODE_RED_EYE = "red_eye"

    /**
     * Supported white balance modes.
     */
    const val WB_MODE_OFF = "off"
    const val WB_MODE_AUTO = "auto"
    const val WB_MODE_COLD = "cold"
    const val WB_MODE_WARM = "warm"

    /**
     * Supported scene modes.
     */
    const val SCENE_MODE_FACE_DETECTION = "face_detection"
    const val SCENE_MODE_FPS_COMPRESSION = "fps_compression"
    const val SCENE_MODE_HDR = "hdr"
    const val SCENE_MODE_NARROW_DEPTH_OF_FIELD = "narrow_depth_of_field"
    const val SCENE_MODE_SLOW_MOTION = "slow_motion"
    const val SCENE_MODE_SUPER_HORIZONTAL_VIEW = "super_horizontal_view"

    /**
     * Supported effect modes.
     */
    const val EFFECT_MODE_NONE = "none"
    const val EFFECT_MODE_AQUA = "aqua"
    const val EFFECT_MODE_BLACKBOARD = "blackboard"
    const val EFFECT_MODE_MONO = "mono"
    const val EFFECT_MODE_NEGATIVE = "negative"
    const val EFFECT_MODE_POSTERIZE = "posterize"
    const val EFFECT_MODE_SEPIA = "sepia"
    const val EFFECT_MODE_SKETCH = "sketch"
    const val EFFECT_MODE_SATURATED = "saturated"
    const val EFFECT_MODE_SATURATED_EXTRA = "saturated_extra"
    const val EFFECT_MODE_TONE_ENCODE = "tone_encode"
    const val EFFECT_MODE_TONE_MAP = "tone_map"
    const val EFFECT_MODE_VIGNETTE = "vignette"

    /**
     * Supported color correction aberration modes.
     */
    const val COLOR_CORRECTION_ABRERATION_MODE_FAST = "fast"
    const val COLOR_CORRECTION_ABRERATION_MODE_HIGH_QUALITY = "high_quality"
    const val COLOR_CORRECTION_ABRERATION_MODE_OFF = "off"

    /**
     * Supported edge modes.
     */
    const val EDGE_MODE_FAST = "fast"
    const val EDGE_MODE_HIGH_QUALITY = "high_quality"
    const val EDGE_MODE_OFF = "off"
    const val EDGE_MODE_SLOW = "slow"

    /**
     * Supported noise reduction modes.
     */
    const val NOISE_REDUCTION_MODE_FAST = "fast"
    const val NOISE_REDUCTION_MODE_HIGH_QUALITY = "high_quality"
    const val NOISE_REDUCTION_MODE_OFF = "off"
    const val NOISE_REDUCTION_MODE_OFF_SPEED = "off_speed"
    const val NOISE_REDUCTION_MODE_QUALITY_SPEED_TRADEOFF = "quality_speed_tradeoff"

    /**
     * Supported sharpness modes.
     */
    const val SHARPNESS_MODE_FAST = "fast"
    const val SHARPNESS_MODE_HIGH_QUALITY = "high_quality"
    const val SHARPNESS_MODE_OFF = "off"
    const val SHARPNESS_MODE_SLOW = "slow"

    /**
     * Supported anti-blink modes.
     */
    const val ANTIBLINK_MODE_DISABLED = 0
    const val ANTIBLINK_MODE_ENABLED = 1

    /**
     * Supported color correction transforms.
     */
    const val COLOR_CORRECTION_TRANSFORM_MODE_FAST = "fast"
    const val COLOR_CORRECTION_TRANSFORM_MODE_HIGH_QUALITY = "high_quality"
    const val COLOR_CORRECTION_TRANSFORM_MODE_SLOW = "slow"

    /**
     * Supported control modes.
     */
    const val CONTROL_MODE_OFF = 0
    const val CONTROL_MODEN_NORMAL = 1

    /**
     * Supported control AE modes.
     */
    const val CONTROL_AE_MODE_OFF = 0
    const val CONTROL_AE_MODE_ON = 1
    const val CONTROL_AE_MODE_ON_ALWAYS_FLASH = 2
    const val CONTROL_AE_MODE_ON_AUTO_FLASH = 3
    const val CONTROL_AE_MODE_ON_AUTO_FLASH_FRESH = 4
    const val CONTROL_AE_MODE_ON_REDEye = 5
    const val CONTROL_AE_MODE_ON_CONSTANT_FLASH = 6

    /**
     * Supported control AF modes.
     */
    const val CONTROL_AF_MODE_OFF = 0
    const val CONTROL_AF_MODE_AUTO = 1
    const val CONTROL_AF_MODE_MACRO = 2
    const val CONTROL_AF_MODE_CONTINUOUS_VIDEO = 3
    const val CONTROL_AF_MODE_CONTINUOUS_PICTURE = 4
    const val CONTROL_AF_MODE_EDOF = 5
    const val CONTROL_AF_MODE_CON = 6

    /**
     * Supported control AWB modes.
     */
    const val CONTROL_AWB_MODE_OFF = 0
    const val CONTROL_AWB_MODE_AUTO = 1
    const val CONTROL_AWB_MODE_TUNGSTEN = 2
    const val CONTROL_AWB_MODE_FLUORESCENT = 3
    const val CONTROL_AWB_MODE_WARM_FLUORESCENT = 4
    const val CONTROL_AWB_MODE_DAYLIGHT = 5
    const val CONTROL_AWB_MODE_CLOUDY_DAYLIGHT = 6
    const val CONTROL_AWB_SHAFT = 7
    const val CONTROL_AWB_MODE_SNOW = 8
    const val CONTROL_AWB_MODE_SUNSET = 9

    /**
     * Supported jpeg orientations.
     */
    val JPEG_ORIENTATIONS = intArrayOf(0, 90, 180, 270)

    /**
     * Supported jpeg thumbnail orientations.
     */
    val JPEG_THUMBNAIL_ORIENTATIONS = intArrayOf(0, 90, 180, 270)

    /**
     * Supported JPEG quality levels.
     */
    val JPEG_QUALITY_LEVELS = intArrayOf(0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100)

    /**
     * Supported JPEG thumbnail sizes.
     */
    const val JPEG_THUMBNAIL_SIZE = "640x480"
}
