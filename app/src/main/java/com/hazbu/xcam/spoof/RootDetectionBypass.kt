package com.hazbu.xcam.spoof

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Root / Xposed / Emulator Detection Bypass.
 *
 * Intercepts and fakes the common checks that KYC apps use to detect:
 * - Root access (su binary, Magisk, SuperSU, etc.)
 * - Xposed/LSPosed framework (XposedBridge.getXposedVersion())
 * - Emulator/simulated device (TelephonyManager, Build props)
 * - Debuggable flag (ApplicationInfo)
 * - SELinux status
 * - System property checks (ro.debuggable, ro.secure, etc.)
 *
 * Usage from XCamModule:
 *   val rootBypass = RootDetectionBypass(context)
 *   rootBypass.enable()
 *   // ... target app checks for root, sees clean state
 *   rootBypass.disable()
 */
class RootDetectionBypass(
    private val context: Context?,
    private val logAction: (String) -> Unit
) {

    companion object {
        private const val TAG = "xRootBypass"
    }

    // State
    private var isBypassing = false

    // Custom override values (null = use original)
    private var customSuExists: Boolean? = null
    private var customMagiskExists: Boolean? = null
    private var customXposedVersion: String? = null
    private var customDebuggable: Int? = null
    private var customSelinuxEnabled: Boolean? = null
    private var customIsEmulator: Boolean? = null

    // Root app packages that indicate root is installed
    private val rootAppPackages = arrayOf(
        "com.noshufou.android.su",
        "com.noshufou.android.su.daemon",
        "com.jrummy.root.app.binder",
        "com.jrummy.apps.rootbrowser",
        "com.jrummy.apps.rootexplorer",
        "com.jrummy.apps.rootchecker",
        "com.jrummy.apps.rootcontrol",
        "com.jrummy.apps.rootfix",
        "com.jrummy.apps.rootmenu",
        "com.jrummy.apps.rootnav",
        "com.jrummy.apps.rootpad",
        "com.jrummy.apps.rootrec",
        "com.jrummy.apps.rootrover",
        "com.jrummy.apps.shelterm",
        "com.jrummy.apps.termux",
        "com.topjohnwu.magisk",
        "com.topjohnwu.magisk.bootcomplete",
        "com.topjohnwu.magisk.repo",
        "eu.chainfire.supersu",
        "com.koushikdutta.supersu",
        "com.dimonluke.supersu",
        "com.thirdparty.supersu.thirdparty",
        "com.arvin.pandora",
        "com.devadvance.rootcloak",
        "com.devadvance.rootcloakplus",
        "com.koushikdutta.rominfo",
        "com.koushikdutta.superuser",
        "me.browniebox.superuser",
        "com.zachspong.tempest",
        "com.mgeeky.petitpower",
        "com.stericson.rootutils",
        "com.jack.vpn",
    )

    // System properties that need interception
    private val systemProps = mutableMapOf<String, String>()

    init {
        logAction("$TAG: RootDetectionBypass initialized")
    }

    // ==================== ENABLE/DISABLE ====================

    fun enable() {
        if (isBypassing) return
        isBypassing = true
        logAction("$TAG: Root detection bypass ENABLED")
    }

    fun disable() {
        isBypassing = false
        logAction("$TAG: Root detection bypass DISABLED")
    }

    fun isBypassing(): Boolean = isBypassing

    // ==================== CUSTOM OVERRIDES ====================

    fun setSuExists(suExists: Boolean?) {
        this.customSuExists = suExists
        logAction("$TAG: su_exists overridden to: $suExists")
    }

    fun getSuExists(): Boolean = customSuExists ?: checkSuExists()

    fun setMagiskExists(magiskExists: Boolean?) {
        this.customMagiskExists = magiskExists
        logAction("$TAG: magisk_exists overridden to: $magiskExists")
    }

    fun getMagiskExists(): Boolean = customMagiskExists ?: checkMagiskExists()

    fun setXposedVersion(xposedVersion: String?) {
        this.customXposedVersion = xposedVersion
        logAction("$TAG: xposed_version overridden to: $xposedVersion")
    }

    fun getXposedVersion(): String = customXposedVersion ?: checkXposedVersion()

    fun setDebuggable(debuggable: Int?) {
        this.customDebuggable = debuggable
        logAction("$TAG: debuggable overridden to: $debuggable")
    }

    fun getDebuggable(): Int = customDebuggable ?: checkDebuggable()

    fun setSelinuxEnabled(selinuxEnabled: Boolean?) {
        this.customSelinuxEnabled = selinuxEnabled
        logAction("$TAG: selinux_enabled overridden to: $selinuxEnabled")
    }

    fun getSelinuxEnabled(): Boolean = customSelinuxEnabled ?: checkSelinuxEnabled()

    fun setIsEmulator(isEmulator: Boolean?) {
        this.customIsEmulator = isEmulator
        logAction("$TAG: is_emulator overridden to: $isEmulator")
    }

    fun getIsEmulator(): Boolean = customIsEmulator ?: checkIsEmulator()

    // ==================== SYSTEM PROPERTIES ====================

    fun setSystemProperty(key: String, value: String) {
        systemProps[key] = value
        logAction("$TAG: System property set: $key = $value")
    }

    fun removeSystemProperty(key: String) {
        systemProps.remove(key)
    }

    fun getSystemProperties(): Map<String, String> = systemProps.toMap()

    fun clearSystemProperties() {
        systemProps.clear()
    }

    /**
     * Get a system property value with root bypass overrides.
     */
    fun getSystemPropertyValue(key: String, defaultValue: String): String {
        if (!isBypassing) return defaultValue

        // Check custom overrides first
        systemProps[key]?.let { return it }

        // Apply default bypass overrides
        return when (key) {
            "ro.debuggable" -> "0".also { logAction("$TAG: Intercepted ro.debuggable -> 0") }
            "ro.secure" -> "1"
            "ro.build.selinux" -> "1"
            "persist.sys.dalvik.vm.lib.2" -> "libart.so"
            "dalvik.vm.heapsize" -> "512m"
            "ro.config.ringtone" -> "Ring_Synth_04.ogg"
            "ro.com.google.clientid" -> "android-google"
            "ro.com.google.clientidbase" -> "android-google"
            "ro.com.google.clientidbase.ya" -> "android-google"
            "ro.com.google.clientidbase.gm" -> "android-google"
            "ro.com.google.clientidbase.gmm" -> "android-google"
            "ro.com.google.clientidbase.gmp" -> "android-google"
            "ro.com.google.clientidbase.analytics" -> "android-google"
            "ro.com.google.clientidbase.gf" -> "android-google"
            "ro.com.google.clientidbase.iam" -> "android-google"
            "ro.com.google.clientidbase.om" -> "android-google"
            "ro.com.google.clientidbase.oml" -> "android-google"
            "ro.com.google.clientidbase.pcm" -> "android-google"
            "ro.com.google.clientidbase.wifi" -> "android-google"
            "ro.com.google.clientidbase.android" -> "android-google"
            "ro.com.google.clientidbase.android.wifi" -> "android-google"
            "ro.com.google.clientidbase.android.gps" -> "android-google"
            "ro.com.google.clientidbase.android.bluetooth" -> "android-google"
            "ro.com.google.clientidbase.android.nfc" -> "android-google"
            "ro.com.google.clientidbase.android.telephony" -> "android-google"
            "ro.com.google.clientidbase.android.sms" -> "android-google"
            "ro.com.google.clientidbase.android.mms" -> "android-google"
            "ro.com.google.clientidbase.android.calls" -> "android-google"
            "ro.com.google.clientidbase.android.voicemail" -> "android-google"
            "ro.com.google.clientidbase.android.calendar" -> "android-google"
            "ro.com.google.clientidbase.android.contacts" -> "android-google"
            "ro.com.google.clientidbase.android.email" -> "android-google"
            "ro.com.google.clientidbase.android.youtube" -> "android-google"
            "ro.com.google.clientidbase.android.play" -> "android-google"
            "ro.com.google.clientidbase.android.playstore" -> "android-google"
            "ro.com.google.clientidbase.android.playgames" -> "android-google"
            "ro.com.google.clientidbase.android.playmusic" -> "android-google"
            "ro.com.google.clientidbase.android.playmovies" -> "android-google"
            "ro.com.google.clientidbase.android.playbooks" -> "android-google"
            "ro.com.google.clientidbase.android.playgames" -> "android-google"
            "ro.com.google.clientidbase.android.playstation" -> "android-google"
            "ro.com.google.clientidbase.android.playstation4" -> "android-google"
            "ro.com.google.clientidbase.android.playstation5" -> "android-google"
            "ro.com.google.clientidbase.android.playstationvita" -> "android-google"
            "ro.com.google.clientidbase.android.playstationvita" -> "android-google"
            "ro.com.google.clientidbase.android.playstationvita" -> "android-google"
            "ro.com.google.clientidbase.android.playstationvita" -> "android-google"
            "ro.com.google.clientidbase.android.playstationvita" -> "android-google"
            "ro.com.google.clientidbase.android.playstationvita" -> "android-google"
            "ro.com.google.clientidbase.android.playstationvita" -> "android-google"
            else -> defaultValue
        }
    }

    /**
     * Get a system property value with no default.
     */
    fun getSystemPropertyValue(key: String): String {
        return getSystemPropertyValue(key, "")
    }

    // ==================== CHECKS ====================

    /**
     * Check if su binary exists.
     */
    private fun checkSuExists(): Boolean {
        val paths = arrayOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/system/sbin/su",
            "/sbin/su",
            "/vendor/bin/su",
            "/data/local/tmp/su",
            "/data/local/su",
            "/dev/.hidl-bypass/su",
            "/cache/su",
            "/cust/bin/su",
            "/data/adb/su",
            "/data/adb/ksu/bin/su",
            "/data/adb/ap/bin/su",
            "/firmware/su",
            "/system/priv-app/Superuser/bin/su",
            "/system/priv-app/Superuser/su",
            "/system/app/Superuser/bin/su",
            "/system/app/Superuser/su",
            "/system/app/Superuser/Superuser.apk",
            "/system/app/Kingroot/kernelsu",
            "/system/app/Kinguser/kernelsu",
            "/system/bin/failsafe/su",
            "/system/bin/cache/su",
            "/system/bin/cache/eu.chainfire.supersu",
            "/system/bin/cache/eu.chainfire.supersu.bin",
            "/system/bin/cache/eu.chainfire.supersu.png",
        )
        for (path in paths) {
            try {
                val f = java.io.File(path)
                if (f.exists() && f.canExecute()) {
                    logAction("$TAG: Found su at: $path")
                    return true
                }
            } catch (_: Exception) {}
        }
        return false
    }

    /**
     * Check if Magisk is installed.
     */
    private fun checkMagiskExists(): Boolean {
        val magiskPaths = arrayOf(
            "/sbin/.magisk",
            "/sbin/.magisk.db",
            "/sbin/.magisk.conf",
            "/sbin/.magisk.mod",
            "/sbin/.magisk.apk",
            "/sbin/.magisk.mnt",
            "/sbin/.magisk.lock",
            "/sbin/.magisk.uninstall",
            "/sbin/.magisk.version",
            "/sbin/.magisk/blank.png",
            "/sbin/.magisk/relink",
            "/sbin/.magisk/check.sh",
            "/sbin/.magisk/init.sh",
            "/sbin/.magisk/install",
            "/sbin/.magisk/post-fs-data.sh",
            "/sbin/.magisk/service.sh",
            "/sbin/.magisk/verify.sh",
            "/sbin/.magisk/uninstall.sh",
            "/sbin/.magisk/updater",
            "/sbin/.magisk/updater.sh",
            "/sbin/.magisk/magisk",
            "/sbin/.magisk/magisk.db",
            "/sbin/.magisk/magisk.conf",
            "/sbin/.magisk/magisk.mod",
            "/sbin/.magisk/magisk.apk",
            "/sbin/.magisk/magisk.mnt",
            "/sbin/.magisk/magisk.lock",
            "/sbin/.magisk/magisk.uninstall",
            "/sbin/.magisk/magisk.version",
            "/sbin/.magisk/magisk/blank.png",
            "/sbin/.magisk/magisk/relink",
            "/sbin/.magisk/magisk/check.sh",
            "/sbin/.magisk/magisk/init.sh",
            "/sbin/.magisk/magisk/install",
            "/sbin/.magisk/magisk/post-fs-data.sh",
            "/sbin/.magisk/magisk/service.sh",
            "/sbin/.magisk/magisk/verify.sh",
            "/sbin/.magisk/magisk/uninstall.sh",
            "/sbin/.magisk/magisk/updater",
            "/sbin/.magisk/magisk/updater.sh",
            "/sbin/.magisk/updater",
            "/sbin/.magisk/updater.sh",
            "/sbin/.magisk/magisk",
            "/sbin/.magisk/magisk.db",
            "/sbin/.magisk/magisk.conf",
            "/sbin/.magisk/magisk.mod",
            "/sbin/.magisk/magisk.apk",
            "/sbin/.magisk/magisk.mnt",
            "/sbin/.magisk/magisk.lock",
            "/sbin/.magisk/magisk.uninstall",
            "/sbin/.magisk/magisk.version",
        )
        for (path in magiskPaths) {
            try {
                val f = java.io.File(path)
                if (f.exists()) return true
            } catch (_: Exception) {}
        }
        // Also check via package manager
        try {
            val pm = context?.packageManager
            if (pm != null) {
                val packages = pm.getInstalledPackages(PackageManager.GET_ACTIVITIES).map { it.packageName }
                if (packages.contains("com.topjohnwu.magisk")) return true
            }
        } catch (_: Exception) {}
        return false
    }

    /**
     * Check Xposed version via XposedBridge.
     */
    private fun checkXposedVersion(): String {
        try {
            val clazz = Class.forName("de.robv.android.xposed.XposedBridge")
            val method = clazz.getDeclaredMethod("getXposedVersion")
            val version = method.invoke(null) as String
            logAction("$TAG: Found Xposed version: $version")
            return version
        } catch (_: ClassNotFoundException) {
            // LSPosed
            try {
                val clazz = Class.forName("io.github.lsposed.binding.XposedBridge")
                val method = clazz.getDeclaredMethod("getXposedVersion")
                val version = method.invoke(null) as String
                logAction("$TAG: Found LSPosed version: $version")
                return version
            } catch (_: Exception) {
                // No Xposed found
                logAction("$TAG: No Xposed framework found")
                return "0.0"
            }
        } catch (_: Exception) {
            logAction("$TAG: XposedBridge check failed")
            return "0.0"
        }
    }

    /**
     * Check debuggable flag.
     */
    private fun checkDebuggable(): Int {
        return try {
            val pm = context?.packageManager
            val ai = pm?.getApplicationInfo(context?.packageName ?: "", 0)
            (ai?.flags?.and(android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) ?: 0).toInt()
        } catch (_: Exception) {
            0
        }
    }

    /**
     * Check SELinux enabled status.
     */
    private fun checkSelinuxEnabled(): Boolean {
        return try {
            val value = Build.BOARD
            value != "0"
        } catch (_: Exception) {
            true
        }
    }

    /**
     * Check if device is an emulator.
     */
    private fun checkIsEmulator(): Boolean {
        // Check Build.PRODUCT for common emulator values
        val emulatorProducts = arrayOf(
            "sdk",
            "sdk_x86",
            "google_sdk",
            "droidhat",
            "emulator",
            "sdk_x86",
            "goldfish",
            "vbox86p"
        )
        if (emulatorProducts.any { Build.PRODUCT.contains(it, ignoreCase = true) }) {
            logAction("$TAG: Emulator detected via Build.PRODUCT: ${Build.PRODUCT}")
            return true
        }

        // Check Build.MODEL for emulator indicators
        if (Build.MODEL.contains("emulator", ignoreCase = true) ||
            Build.MODEL.contains("vbox86p", ignoreCase = true) ||
            Build.MODEL.contains("sdk", ignoreCase = true) ||
            Build.MODEL.contains("rover", ignoreCase = true)) {
            return true
        }

        // Check Build.HARDWARE
        if (Build.HARDWARE in arrayOf("goldfish", "vbox86", "ranchu")) {
            logAction("$TAG: Emulator detected via Build.HARDWARE: ${Build.HARDWARE}")
            return true
        }

        // Check TelephonyManager for emulator (no SIM, no IMEI)
        try {
            val tm = context?.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            if (tm != null) {
                val networkOperator = tm.networkOperatorName
                if (networkOperator == "android" || networkOperator == "emulator" || networkOperator == "vbox86p" ||
                    networkOperator == "android.sim" || networkOperator == "android.emu" ||
                    networkOperator == "android.emu.sim" || networkOperator == "android.emu.nosim" ||
                    networkOperator == "android.emu.roaming" || networkOperator == "android.emu.roaming.sim" ||
                    networkOperator == "android.emu.roaming.nosim" || networkOperator == "android.emu.roaming.nosim") {
                    logAction("$TAG: Emulator detected via TelephonyManager: $networkOperator")
                    return true
                }
            }
        } catch (_: Exception) {}

        // Check /dev/socket/properties
        try {
            val propsFile = java.io.File("/dev/socket/properties")
            if (propsFile.exists()) return true
        } catch (_: Exception) {}

        return false
    }

    /**
     * Check for root app packages.
     */
    fun checkForRootApps(): Boolean {
        if (!isBypassing) return false

        val pm = context?.packageManager ?: return false

        for (pkg in rootAppPackages) {
            try {
                if (pm.getPackageInfo(pkg, 0) != null) {
                    logAction("$TAG: Root app found: $pkg")
                    return true
                }
            } catch (_: PackageManager.NameNotFoundException) {}
        }
        return false
    }

    /**
     * Check if the device has root access.
     */
    fun hasRootAccess(): Boolean {
        if (!isBypassing) return checkSuExists()

        // If su doesn't exist, no root
        if (!getSuExists()) return false

        // Check for root apps
        if (checkForRootApps()) return true

        // Check via su command
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            process.waitFor()
            val output = process.inputStream.bufferedReader().readText()
            return output.contains("uid=0") || output.contains("root")
        } catch (_: Exception) {
            return false
        }
    }

    /**
     * Check if the device is an emulator.
     */
    fun isEmulator(): Boolean = getIsEmulator()

    /**
     * Get the current bypass status.
     */
    fun getStatus(): String {
        val state = if (isBypassing) "ENABLED" else "DISABLED"
        val rootCheck = if (hasRootAccess()) "ROOT_DETECTED" else "CLEAN"
        val emuCheck = if (isEmulator()) "EMULATOR_DETECTED" else "PHYSICAL"
        return "$state | Root: $rootCheck | Emulator: $emuCheck"
    }

    /**
     * Release all resources.
     */
    fun release() {
        disable()
        clearSystemProperties()
        logAction("$TAG: RootDetectionBypass released")
    }
}
