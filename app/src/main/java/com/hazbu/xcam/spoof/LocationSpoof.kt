package com.hazbu.xcam.spoof

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Build
import android.util.Log

/**
 * GPS / Location / Network Spoofing for non-rooted devices.
 *
 * Intercepts location requests from:
 * - FusedLocationProviderClient (modern apps)
 * - LocationManager (legacy apps)
 * - TelephonyManager.getNetworkOperatorName() (cell tower location)
 * - WifiInfo.getSSID() / getConnectionInfo() (Wi-Fi triangulation)
 * - NetworkInfo.getType() / getDetailedState() (network state)
 *
 * Supports:
 * - Custom lat/lng coordinates
 * - Custom altitude, bearing, speed, accuracy
 * - Custom cell tower info (MCC, MNC, LAC, CID)
 * - Custom Wi-Fi SSID/BSSID/MAC
 * - Custom network type and state
 *
 * Usage from XCamModule:
 *   val gpsSpoof = LocationSpoof(context)
 *   gpsSpoof.enable()
 *   gpsSpoof.setTargetLocation(37.7749, -122.4194) // SF
 *   // ... target app requests location, gets spoofed coords
 *   gpsSpoof.disable()
 */
class LocationSpoof(
    private val context: Context?,
    private val logAction: (String) -> Unit
) {

    companion object {
        private const val TAG = "xLocationSpoof"
    }

    // State
    private var isSpoofing = false
    private var isPhone = false

    // Target location
    private var targetLat: Double = 0.0
    private var targetLng: Double = 0.0
    private var targetAlt: Double = 0.0
    private var targetBearing: Float = 0f
    private var targetSpeed: Float = 0f
    private var targetAccuracy: Float = 10f

    // Cell tower info
    private var customMcc: Int = 0
    private var customMnc: Int = 0
    private var customLac: Int = 0
    private var customCid: Int = 0
    private var customNetworkOperator: String = ""

    // Wi-Fi info
    private var customSsid: String? = null
    private var customBssid: String? = null
    private var customMacAddress: String? = null

    // Network state
    private var customNetworkType: Int = 0
    private var customDetailedState: String = "CONNECTED"

    // Provider info
    private var customProvider: String = "gps"
    private var customTime: Long = System.currentTimeMillis()
    private var customElapsed: Long = 0L

    init {
        logAction("$TAG: LocationSpoof initialized")
    }

    // ==================== ENABLE/DISABLE ====================

    fun enable() {
        if (isSpoofing) return
        isSpoofing = true
        logAction("$TAG: Location spoofing ENABLED")
    }

    fun disable() {
        isSpoofing = false
        logAction("$TAG: Location spoofing DISABLED")
    }

    fun isSpoofing(): Boolean = isSpoofing

    fun enablePhoneMode() { isPhone = true }
    fun disablePhoneMode() { isPhone = false }
    fun isPhoneMode(): Boolean = isPhone

    // ==================== TARGET LOCATION ====================

    fun setTargetLocation(lat: Double, lng: Double) {
        this.targetLat = lat
        this.targetLng = lng
        logAction("$TAG: Target location set: ($lat, $lng)")
    }

    fun setTargetAltitude(alt: Double) { this.targetAlt = alt }
    fun setTargetBearing(bearing: Float) { this.targetBearing = bearing }
    fun setTargetSpeed(speed: Float) { this.targetSpeed = speed }
    fun setAccuracy(accuracy: Float) { this.targetAccuracy = accuracy }

    fun setProvider(provider: String) { this.customProvider = provider }
    fun setTime(time: Long) { this.customTime = time }
    fun setElapsed(elapsed: Long) { this.customElapsed = elapsed }

    fun getTargetLat(): Double = targetLat
    fun getTargetLng(): Double = targetLng
    fun getTargetAlt(): Double = targetAlt

    // ==================== CELL TOWER INFO ====================

    fun setCellTowerInfo(mcc: Int, mnc: Int, lac: Int, cid: Int) {
        this.customMcc = mcc
        this.customMnc = mnc
        this.customLac = lac
        this.customCid = cid
        this.customNetworkOperator = "$mcc$mnc"
        logAction("$TAG: Cell tower info set: MCC=$mcc, MNC=$mnc, LAC=$lac, CID=$cid")
    }

    fun setNetworkOperator(operator: String) { this.customNetworkOperator = operator }

    // ==================== WI-FI INFO ====================

    fun setWifiInfo(ssid: String?, bssid: String?, macAddress: String?) {
        this.customSsid = ssid
        this.customBssid = bssid
        this.customMacAddress = macAddress
        logAction("$TAG: Wi-Fi info set: SSID=$ssid, BSSID=$bssid, MAC=$macAddress")
    }

    // ==================== NETWORK STATE ====================

    fun setNetworkState(networkType: Int, detailedState: String) {
        this.customNetworkType = networkType
        this.customDetailedState = detailedState
        logAction("$TAG: Network state set: type=$networkType, state=$detailedState")
    }

    // ==================== SPOOVED LOCATION ====================

    /**
     * Get a spoofed Location object.
     * Returns null if no target location is set or spoofing is disabled.
     */
    fun getSpoofedLocation(): Location? {
        if (!isSpoofing) return null

        return try {
            val location = Location(customProvider).apply {
                latitude = targetLat
                longitude = targetLng
                altitude = targetAlt
                bearing = targetBearing
                speed = targetSpeed
                accuracy = targetAccuracy
                time = customTime
                elapsedRealtimeNanos = customElapsed
            }
            location
        } catch (e: Exception) {
            logAction("$TAG: Failed to create spoofed location: ${e.message}")
            null
        }
    }

    /**
     * Check if spoofed location is available.
     */
    fun isLocationAvailable(): Boolean = isSpoofing && (targetLat != 0.0 || targetLng != 0.0)

    /**
     * Get the current spoofed cell tower operator.
     */
    fun getNetworkOperator(): String {
        return if (isSpoofing && customNetworkOperator.isNotEmpty()) {
            customNetworkOperator
        } else {
            try {
                val tm = context?.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
                tm?.networkOperator ?: ""
            } catch (_: Exception) {
                ""
            }
        }
    }

    /**
     * Get the current spoofed Wi-Fi SSID.
     */
    fun getSsid(): String? {
        if (isSpoofing && customSsid != null) return customSsid
        return try {
            val wifi = context?.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            wifi?.connectionInfo?.ssid?.trim('"')
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Get the current spoofed Wi-Fi BSSID.
     */
    fun getBssid(): String? {
        if (isSpoofing && customBssid != null) return customBssid
        return try {
            val wifi = context?.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            wifi?.connectionInfo?.bssid
        } catch (_: Exception) {
            null
        }
    }

    // ==================== STATUS ====================

    /**
     * Get the current status.
     */
    fun getStatus(): String {
        val state = if (isSpoofing) "ENABLED" else "DISABLED"
        val phone = if (isPhone) "PHONE" else "NO_PHONE"
        val loc = if (isLocationAvailable()) "LOC: ($targetLat, $targetLng)" else "NO_LOCATION"
        return "$state | $phone | $loc"
    }

    /**
     * Check if the location spoof is ready.
     */
    fun isReady(): Boolean = isSpoofing && isLocationAvailable()

    /**
     * Release all resources.
     */
    fun release() {
        disable()
        logAction("$TAG: LocationSpoof released")
    }
}
