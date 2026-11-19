package com.hazbu.xcam.spoof

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Fingerprint Sensor Spoofing for non-rooted devices.
 *
 * Intercepts fingerprint authentication at the BiometricPrompt/FingerprintManager level
 * and returns success with fake/recorded biometric data.
 *
 * Supported APIs:
 * - FingerprintManager.authenticate() (pre-Android 10)
 * - BiometricPrompt.authenticate() (Android 10+)
 * - FingerprintSensor.onFrame() (raw fingerprint image)
 * - IInstanceManager.getFingerprint() (system-level fingerprint service)
 *
 * Usage from XCamModule:
 *   val fpSpoof = FingerprintSpoof(context)
 *   fpSpoof.enable()
 *   fpSpoof.setRecordedFingerprint(imageBytes) // optional
 *   // ... target app authenticates, sees success
 *   fpSpoof.disable()
 */
class FingerprintSpoof(
    private val context: Context?,
    private val logAction: (String) -> Unit
) {

    companion object {
        private const val TAG = "xFpSpoof"
    }

    // State
    private var isSpoofing = false
    private var isPhone = false

    // Record fingerprint image (optional - if set, returns this image)
    private var recordedFingerprint: ByteArray? = null

    // Authentication state
    @Volatile
    private var lastAuthResult: Boolean = false
    @Volatile
    private var lastAuthTime: Long = 0L

    // Handler for async callbacks
    private val handler = Handler(Looper.getMainLooper())

    // Listener for authentication events
    private var authListener: FingerprintAuthListener? = null

    init {
        logAction("$TAG: FingerprintSpoof initialized")
    }

    // ==================== ENABLE/DISABLE ====================

    fun enable() {
        if (isSpoofing) return
        isSpoofing = true
        logAction("$TAG: Fingerprint spoofing ENABLED")
    }

    fun disable() {
        isSpoofing = false
        logAction("$TAG: Fingerprint spoofing DISABLED")
    }

    fun isSpoofing(): Boolean = isSpoofing

    fun enablePhoneMode() { isPhone = true; logAction("$TAG: Phone mode enabled") }
    fun disablePhoneMode() { isPhone = false; logAction("$TAG: Phone mode disabled") }
    fun isPhoneMode(): Boolean = isPhone

    // ==================== RECORDING ====================

    fun setRecordedFingerprint(imageBytes: ByteArray?) {
        this.recordedFingerprint = imageBytes
        logAction("$TAG: Recorded fingerprint set (${imageBytes?.size ?: 0} bytes)")
    }

    fun getRecordedFingerprint(): ByteArray? = recordedFingerprint

    fun clearRecordedFingerprint() {
        recordedFingerprint = null
        logAction("$TAG: Recorded fingerprint cleared")
    }

    // ==================== AUTHENTICATION ====================

    /**
     * Authenticate with the spoofed fingerprint.
     * This is called by the hook when the target app requests fingerprint auth.
     */
    fun authenticate(): Boolean {
        if (!isSpoofing) return false

        lastAuthResult = true
        lastAuthTime = System.currentTimeMillis()

        // Notify listener if set
        authListener?.onAuthSuccess()

        logAction("$TAG: Auth SUCCESS (spoofed)")
        return true
    }

    /**
     * Authenticate with a specific result.
     * Use this to simulate failed auth.
     */
    fun authenticate(success: Boolean) {
        lastAuthResult = success
        lastAuthTime = System.currentTimeMillis()

        if (success) {
            authListener?.onAuthSuccess()
            logAction("$TAG: Auth SUCCESS (spoofed)")
        } else {
            authListener?.onAuthFailed()
            logAction("$TAG: Auth FAILED (spoofed)")
        }
    }

    /**
     * Check if the last authentication was successful.
     */
    fun getLastAuthResult(): Boolean = lastAuthResult

    /**
     * Get the timestamp of the last authentication.
     */
    fun getLastAuthTime(): Long = lastAuthTime

    /**
     * Check if authentication is allowed (spoofing is enabled and phone mode active).
     */
    fun isAuthAllowed(): Boolean = isSpoofing && isPhone

    // ==================== LISTENER ====================

    /**
     * Set a listener for authentication events.
     */
    fun setAuthListener(listener: FingerprintAuthListener?) {
        this.authListener = listener
    }

    /**
     * Interface for fingerprint authentication events.
     */
    interface FingerprintAuthListener {
        fun onAuthSuccess()
        fun onAuthFailed()
        fun onAuthError(message: String)
    }

    // ==================== RAW FRAGMENT (Android 11+) ====================

    /**
     * Get a fake fingerprint sensor frame (Android 11+).
     * Returns null if no recorded fingerprint is set.
     */
    fun getFakeSensorFrame(): ByteArray? {
        if (!isSpoofing) return null
        return recordedFingerprint
    }

    /**
     * Check if the device has a fingerprint sensor.
     */
    fun hasFingerprintSensor(): Boolean {
        return try {
            val pm = context?.packageManager
            pm?.hasSystemFeature(android.content.pm.PackageManager.FEATURE_FINGERPRINT) ?: false
        } catch (_: Exception) {
            false
        }
    }

    // ==================== STATUS ====================

    /**
     * Get the current spoof status.
     */
    fun getStatus(): String {
        val state = if (isSpoofing) "ENABLED" else "DISABLED"
        val phone = if (isPhone) "PHONE" else "NO_PHONE"
        val auth = if (lastAuthResult) "LAST_AUTH: SUCCESS" else "LAST_AUTH: FAILED"
        val fp = if (recordedFingerprint != null) "RECORDED_FP: YES" else "RECORDED_FP: NO"
        return "$state | $phone | $auth | $fp"
    }

    /**
     * Check if the fingerprint spoof is ready (enabled, phone mode, and optionally has recorded FP).
     */
    fun isReady(): Boolean = isSpoofing && isPhone

    /**
     * Release all resources.
     */
    fun release() {
        disable()
        clearRecordedFingerprint()
        authListener = null
        logAction("$TAG: FingerprintSpoof released")
    }
}
