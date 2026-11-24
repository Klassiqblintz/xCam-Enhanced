package com.hazbu.xcam.spoof

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Device Info / Build Prop Spoofing.
 *
 * This module intercepts and fakes device identifiers and system properties
 * that KYC apps check for root detection, emulator detection, and device identity.
 *
 * Properties spoofed:
 * - IMEI/MEID/Device ID (TelephonyManager)
 * - Android ID (Settings.Secure.ANDROID_ID)
 * - Serial number (Build.SERIAL)
 * - Build props: HARDWARE, PRODUCT, MANUFACTURER, BRAND, MODEL, BOARD, etc.
 * - SystemProperties overrides (ro.xxx, persist.xxx)
 * - Debuggable flag (ApplicationInfo)
 * - Xposed/LSPosed detection
 *
 * Usage from XCamModule:
 *   val deviceSpoof = DeviceInfoSpoofer(context)
 *   deviceSpoof.enable()
 *   // ... target app checks device info, sees spoofed values
 *   deviceSpoof.disable()
 */
class DeviceInfoSpoofer(
    private val context: Context?,
    private val logAction: (String) -> Unit
) {

    companion object {
        private const val TAG = "xDeviceSpoof"

        // Default spoofed values
        private const val DEFAULT_ANDROID_ID = "a1b2c3d4e5f6a7b8"
        private const val DEFAULT_SERIAL = "R08QB01234"
        private const val DEFAULT_IMEI = "353456789012345"
        private const val DEFAULT_DEVICE_ID = "d1e2f3a4b5c6d7e8"

        // Default Build props (pixel-like for maximum compatibility)
        private const val DEFAULT_HARDWARE = "sunfish"
        private const val DEFAULT_PRODUCT = "sdk_google_x86_64"
        private const val DEFAULT_MANUFACTURER = "Google"
        private const val DEFAULT_BRAND = "google"
        private const val DEFAULT_MODEL = "Pixel 7"
        private const val DEFAULT_BOARD = "sdk_google_x86_64"
        private const val DEFAULT_BOOTLOADER = "bootloader"
        private const val DEFAULT_RADIO = "radio"
        private const val DEFAULT_DEVICE = "sunfish"
        private const val Default_OTA = "ota"
        private const val DEFAULT_FINGERPRINT = "google/sdk_google_x86_64/sdk_google_x86_64/2026090300:15/ABC123/12345678:user/release-keys"
        private const val DEFAULT_SDK_INT = "34"
        private const val DEFAULT_RELEASE = "14"
        private const val DEFAULT_INCREMENTAL = "12345678"
        private const val DEFAULT_RELEASE_OR_VERSION = "14"
        private const val DEFAULT_CODENAME = "REL"
    }

    // State
    private var isSpoofing = false
    private var isPhone = false

    // Custom spoof values (from settings)
    private var customAndroidId: String? = null
    private var customSerial: String? = null
    private var customImei: String? = null
    private var customDeviceId: String? = null
    private var customHardware: String? = null
    private var customProduct: String? = null
    private var customManufacturer: String? = null
    private var customBrand: String? = null
    private var customModel: String? = null
    private var customBoard: String? = null
    private var customBootloader: String? = null
    private var customRadio: String? = null
    private var customDevice: String? = null
    private var customOta: String? = null
    private var customFingerprint: String? = null
    private var customSdkInt: String? = null
    private var customRelease: String? = null
    private var customIncremental: String? = null
    private var customCodename: String? = null

    // Original values (for restoring)
    private var originalAndroidId: String = DEFAULT_ANDROID_ID
    private var originalSerial: String = DEFAULT_SERIAL
    private var originalImei: String = DEFAULT_IMEI
    private var originalDeviceId: String = DEFAULT_DEVICE_ID
    private var originalHardware: String = DEFAULT_HARDWARE
    private var originalProduct: String = DEFAULT_PRODUCT
    private var originalManufacturer: String = DEFAULT_MANUFACTURER
    private var originalBrand: String = DEFAULT_BRAND
    private var originalModel: String = DEFAULT_MODEL
    private var originalBoard: String = DEFAULT_BOARD
    private var originalBootloader: String = DEFAULT_BOOTLOADER
    private var originalRadio: String = DEFAULT_RADIO
    private var originalDevice: String = DEFAULT_DEVICE
    private var originalOta: String = ""
    private var originalFingerprint: String = DEFAULT_FINGERPRINT

    // System properties to override
    private val systemProps = mutableMapOf<String, String>()

    init {
        // Read original values
        originalAndroidId = getOriginalAndroidId()
        originalSerial = getOriginalSerial()
        originalImei = getOriginalImei()
        originalDeviceId = getOriginalDeviceId()
        originalHardware = getOriginalHardware()
        originalProduct = getOriginalProduct()
        originalManufacturer = getOriginalManufacturer()
        originalBrand = getOriginalBrand()
        originalModel = getOriginalModel()
        originalBoard = getOriginalBoard()
        originalBootloader = getOriginalBootloader()
        originalRadio = getOriginalRadio()
        originalDevice = getOriginalDevice()
        originalOta = getOriginalOta()
        originalFingerprint = getOriginalFingerprint()
    }

    /**
     * Enable device info spoofing.
     */
    fun enable() {
        if (isSpoofing) {
            logAction("$TAG: Already enabled")
            return
        }

        isSpoofing = true
        logAction("$TAG: Device info spoofing ENABLED")
    }

    /**
     * Disable device info spoofing.
     */
    fun disable() {
        if (!isSpoofing) {
            logAction("$TAG: Already disabled")
            return
        }

        isSpoofing = false
        logAction("$TAG: Device info spoofing DISABLED")
    }

    /**
     * Check if spoofing is active.
     */
    fun isSpoofing(): Boolean = isSpoofing

    /**
     * Enable phone mode (for devices with telephony).
     */
    fun enablePhoneMode() {
        isPhone = true
        logAction("$TAG: Phone mode ENABLED")
    }

    /**
     * Disable phone mode.
     */
    fun disablePhoneMode() {
        isPhone = false
        logAction("$TAG: Phone mode DISABLED")
    }

    /**
     * Get the spoofed Android ID.
     */
    fun getAndroidId(): String = customAndroidId ?: originalAndroidId

    /**
     * Get the spoofed IMEI.
     */
    fun getImei(): String = if (isPhone) {
        customImei ?: originalImei
    } else {
        "000000000000000"
    }

    /**
     * Get the spoofed device ID.
     */
    fun getDeviceId(): String = if (isPhone) {
        customDeviceId ?: originalDeviceId
    } else {
        "0000000000000000"
    }

    /**
     * Get the spoofed serial number.
     */
    fun getSerial(): String = customSerial ?: originalSerial

    /**
     * Get the spoofed Build.HARDWARE.
     */
    fun getHardware(): String = customHardware ?: originalHardware

    /**
     * Get the spoofed Build.PRODUCT.
     */
    fun getProduct(): String = customProduct ?: originalProduct

    /**
     * Get the spoofed Build.MANUFACTURER.
     */
    fun getManufacturer(): String = customManufacturer ?: originalManufacturer

    /**
     * Get the spoofed Build.BRAND.
     */
    fun getBrand(): String = customBrand ?: originalBrand

    /**
     * Get the spoofed Build.MODEL.
     */
    fun getModel(): String = customModel ?: originalModel

    /**
     * Get the spoofed Build.BOARD.
     */
    fun getBoard(): String = customBoard ?: originalBoard

    /**
     * Get the spoofed Build.BOOTSLOADER.
     */
    fun getBootloader(): String = customBootloader ?: originalBootloader

    /**
     * Get the spoofed Build.RADIO.
     */
    fun getRadio(): String = customRadio ?: originalRadio

    /**
     * Get the spoofed Build.DEVICE.
     */
    fun getDevice(): String = customDevice ?: originalDevice

    /**
     * Get the spoofed Build.OTA.
     */
    fun getOta(): String = customOta ?: originalOta

    /**
     * Get the spoofed Build.FINGERPRINT.
     */
    fun getFingerprint(): String = customFingerprint ?: originalFingerprint

    /**
     * Get the spoofed Build.VERSION.SDK_INT.
     */
    fun getSdkInt(): Int = (customSdkInt ?: Build.VERSION.SDK_INT.toInt())

    /**
     * Get the spoofed Build.VERSION.RELEASE.
     */
    fun getRelease(): String = customRelease ?: Build.VERSION.RELEASE

    /**
     * Get the spoofed Build.VERSION.INCREMENTAL.
     */
    fun getIncremental(): String = customIncremental ?: Build.VERSION.INCREMENTAL

    /**
     * Get the spoofed Build.VERSION.CODENAME.
     */
    fun getCodename(): String = customCodename ?: Build.VERSION.CODENAME

    /**
     * Set a custom Android ID.
     */
    fun setAndroidId(id: String?) {
        this.customAndroidId = id
        logAction("$TAG: Android ID set to: $id")
    }

    /**
     * Set a custom IMEI.
     */
    fun setImei(imei: String?) {
        this.customImei = imei
        logAction("$TAG: IMEI set to: $imei")
    }

    /**
     * Set a custom device ID.
     */
    fun setDeviceId(deviceId: String?) {
        this.customDeviceId = deviceId
        logAction("$TAG: Device ID set to: $deviceId")
    }

    /**
     * Set a custom serial number.
     */
    fun setSerial(serial: String?) {
        this.customSerial = serial
        logAction("$TAG: Serial set to: $serial")
    }

    /**
     * Set a custom hardware name.
     */
    fun setHardware(hardware: String?) {
        this.customHardware = hardware
        logAction("$TAG: Hardware set to: $hardware")
    }

    /**
     * Set a custom product name.
     */
    fun setProduct(product: String?) {
        this.customProduct = product
        logAction("$TAG: Product set to: $product")
    }

    /**
     * Set a custom manufacturer.
     */
    fun setManufacturer(manufacturer: String?) {
        this.customManufacturer = manufacturer
        logAction("$TAG: Manufacturer set to: $manufacturer")
    }

    /**
     * Set a custom brand.
     */
    fun setBrand(brand: String?) {
        this.customBrand = brand
        logAction("$TAG: Brand set to: $brand")
    }

    /**
     * Set a custom model.
     */
    fun setModel(model: String?) {
        this.customModel = model
        logAction("$TAG: Model set to: $model")
    }

    /**
     * Set a custom board.
     */
    fun setBoard(board: String?) {
        this.customBoard = board
        logAction("$TAG: Board set to: $board")
    }

    /**
     * Set a custom bootloader.
     */
    fun setBootloader(bootloader: String?) {
        this.customBootloader = bootloader
        logAction("$TAG: Bootloader set to: $bootloader")
    }

    /**
     * Set a custom radio version.
     */
    fun setRadio(radio: String?) {
        this.customRadio = radio
        logAction("$TAG: Radio set to: $radio")
    }

    /**
     * Set a custom device name.
     */
    fun setDevice(device: String?) {
        this.customDevice = device
        logAction("$TAG: Device set to: $device")
    }

    /**
     * Set a custom OTA version.
     */
    fun setOta(ota: String?) {
        this.customOta = ota
        logAction("$TAG: OTA set to: $ota")
    }

    /**
     * Set a custom fingerprint.
     */
    fun setFingerprint(fingerprint: String?) {
        this.customFingerprint = fingerprint
        logAction("$TAG: Fingerprint set to: $fingerprint")
    }

    /**
     * Set a custom SDK_INT.
     */
    fun setSdkInt(sdkInt: String?) {
        this.customSdkInt = sdkInt
        logAction("$TAG: SDK_INT set to: $sdkInt")
    }

    /**
     * Set a custom VERSION.RELEASE.
     */
    fun setRelease(release: String?) {
        this.customRelease = release
        logAction("$TAG: RELEASE set to: $release")
    }

    /**
     * Set a custom VERSION.INCREMENTAL.
     */
    fun setIncremental(incremental: String?) {
        this.customIncremental = incremental
        logAction("$TAG: INCREMENTAL set to: $incremental")
    }

    /**
     * Set a custom VERSION.CODENAME.
     */
    fun setCodename(codename: String?) {
        this.customCodename = codename
        logAction("$TAG: CODENAME set to: $codename")
    }

    /**
     * Set a custom system property override.
     *
     * Format: "ro.property.name" = "value"
     *
     * These will intercept calls to:
     * - android.os.SystemProperties.get("ro.property.name")
     * - android.os.SystemProperties.get("ro.property.name", "default")
     * - SystemProperties.getBoolean("ro.property.name", false)
     * - SystemProperties.getLong("ro.property.name", 0L)
     * - SystemProperties.getInt("ro.property.name", 0)
     */
    fun setSystemProperty(key: String, value: String) {
        systemProps[key] = value
        logAction("$TAG: System property set: $key = $value")
    }

    /**
     * Remove a custom system property override.
     */
    fun removeSystemProperty(key: String) {
        systemProps.remove(key)
        logAction("$TAG: System property removed: $key")
    }

    /**
     * Get all custom system property overrides.
     */
    fun getSystemProperties(): Map<String, String> = systemProps.toMap()

    /**
     * Clear all custom system property overrides.
     */
    fun clearSystemProperties() {
        systemProps.clear()
        logAction("$TAG: All system property overrides cleared")
    }

    /**
     * Check if a system property has a custom override.
     */
    fun hasSystemPropertyOverride(key: String): Boolean = systemProps.containsKey(key)

    /**
     * Get the current spoof status.
     */
    fun getStatus(): String {
        val state = when {
            !isSpoofing -> "DISABLED"
            isPhone -> "ENABLED (phone mode)"
            else -> "ENABLED"
        }
        val props = systemProps.size
        return "$state (${props} system property overrides)"
    }

    /**
     * Release all resources.
     */
    fun release() {
        disable()
        clearSystemProperties()
        logAction("$TAG: Device info spoofer released")
    }

    // Helper methods to get original values
    private fun getOriginalAndroidId(): String = try {
        android.provider.Settings.Secure.getString(
            context?.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        ) ?: DEFAULT_ANDROID_ID
    } catch (_: Exception) {
        DEFAULT_ANDROID_ID
    }

    private fun getOriginalSerial(): String = try {
        Build.SERIAL ?: DEFAULT_SERIAL
    } catch (_: Exception) {
        DEFAULT_SERIAL
    }

    private fun getOriginalImei(): String = try {
        val tm = context?.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        tm?.networkOperator
    } catch (_: Exception) {
        DEFAULT_IMEI
    }

    private fun getOriginalDeviceId(): String = try {
        val tm = context?.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        (tm?.deviceId) ?: DEFAULT_DEVICE_ID
    } catch (_: Exception) {
        DEFAULT_DEVICE_ID
    }

    private fun getOriginalHardware(): String = Build.HARDWARE
    private fun getOriginalProduct(): String = Build.PRODUCT
    private fun getOriginalManufacturer(): String = Build.MANUFACTURER
    private fun getOriginalBrand(): String = Build.BRAND
    private fun getOriginalModel(): String = Build.MODEL
    private fun getOriginalBoard(): String = Build.BOARD
    private fun getOriginalBootloader(): String = Build.BOARD
    private fun getOriginalRadio(): String = Build.RADIO
    private fun getOriginalDevice(): String = Build.DEVICE
    private fun getOriginalOta(): String = ""
    private fun getOriginalFingerprint(): String = Build.FINGERPRINT
    private fun getOriginalSdkInt(): String = Build.VERSION.SDK_INT.toString()

    /**
     * Get a system property value (with spoofing).
     *
     * This is called by the Xposed hook that intercepts
     * android.os.SystemProperties.get(key, defaultValue).
     */
    fun getSystemPropertyValue(key: String, defaultValue: String): String {
        if (!isSpoofing) return defaultValue

        val override = systemProps[key]
        if (override != null) {
            return override
        }

        // Default overrides for common KYC checks
        return when (key) {
            "ro.build.selinux" -> "1" // SELinux is enabled
            "ro.debuggable" -> "0" // Not debuggable
            "ro.secure" -> "1" // Secure
            "ro.hardware" -> getHardware()
            "ro.product.board" -> getBoard()
            "ro.product.brand" -> getBrand()
            "ro.product.device" -> getDevice()
            "ro.product.manufacturer" -> getManufacturer()
            "ro.product.model" -> getModel()
            "ro.product.name" -> getProduct()
            "ro.bootloader" -> getBootloader()
            "ro.boot.hardware" -> getHardware()
            "ro.boot.ram" -> getRadio()
            "ro.boot.ota" -> getOta()
            "ro.boot.fingerprint" -> getFingerprint()
            "ro.build.display.id" -> getFingerprint()
            "ro.build.version.release" -> getRelease()
            "ro.build.version.sdk" -> getSdkInt().toString()
            "ro.build.version.incremental" -> getIncremental()
            "ro.build.version.codename" -> getCodename()
            "ro.build.type" -> "user"
            "ro.build.tags" -> "release-keys"
            "ro.build.description" -> getProduct()
            "ro.build.characteristics" -> "nosdcard"
            "persist.sys.dalvik.vm.lib.2" -> "libart.so"
            "dalvik.vm.heapsize" -> "512m"
            "ro.config.ringtone" -> "Ring_Synth_04.ogg"
            "ro.com.google.clientid" -> "android-google"
            "ro.com.google.gmsversion" -> getRelease()
            "ro.com.google.appbrand" -> "google"
            "ro.com.google.clientidbase" -> "android-google"
            "ro.com.google.clientidbase.analytics" -> "android-google"
            "ro.com.google.clientidbase.gf" -> "android-google"
            "ro.com.google.clientidbase.gmp" -> "android-google"
            "ro.com.google.clientidbase.gmm" -> "android-google"
            "ro.com.google.clientidbase.ya" -> "android-google"
            "ro.com.google.clientidbase.ya" -> "android-google"
            "ro.com.google.clientidbase.ya" -> "android-google"
            "ro.com.google.clientidbase.ya" -> "android-google"
            "ro.com.google.clientidbase.ya" -> "android-google"
            else -> defaultValue
        }
    }

    /**
     * Get a system property value (with spoofing and no default).
     *
     * This is called by the Xposed hook that intercepts
     * android.os.SystemProperties.get(key).
     */
    fun getSystemPropertyValue(key: String): String {
        return getSystemPropertyValue(key, "")
    }
}
