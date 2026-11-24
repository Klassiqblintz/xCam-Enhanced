package com.hazbu.xcam.xposed

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Build
import android.view.Surface
import androidx.media3.common.util.UnstableApi
import com.hazbu.xcam.core.audio.MediaCodecAudioDecoder
import com.hazbu.xcam.core.capture.CaptureManager
import com.hazbu.xcam.core.capture.MediaCodecYuvDecoder
import com.hazbu.xcam.core.capture.YuvFrameProcessor
import com.hazbu.xcam.core.engine.MediaEngine
import com.hazbu.xcam.core.engine.XCamEngine
import com.hazbu.xcam.core.settings.SettingsManager
import com.hazbu.xcam.core.virtualcamera.VirtualCameraManager
import com.hazbu.xcam.core.surface.SurfaceManager
import com.hazbu.xcam.core.surface.SurfaceProvider
import com.hazbu.xcam.core.telemetry.PipelineTracker
import com.hazbu.xcam.utils.Logger
import com.hazbu.xcam.utils.SystemUtils
import com.hazbu.xcam.utils.UIUtils
import com.hazbu.xcam.data.Constants
import com.hazbu.xcam.spoof.DeviceInfoSpoofer
import com.hazbu.xcam.spoof.RootDetectionBypass
import com.hazbu.xcam.spoof.FingerprintSpoof
import com.hazbu.xcam.spoof.LocationSpoof
import com.hazbu.xcam.splash.CameraXHooks
import com.hazbu.xcam.splash.DepthIRCamera
import com.hazbu.xcam.splash.WebRtcDeepHooks
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

@UnstableApi
class XCamModule : XposedModule() {
    private var isInitialized = false
    private var mContext: Context? = null
    private var hooksInstalled = false
    private val ignoreHooks = ThreadLocal.withInitial { false }
    @Volatile private var yuvDecoder: MediaCodecYuvDecoder? = null
    @Volatile private var audioDecoder: MediaCodecAudioDecoder? = null
    private val yuvLock = Any()
    private val audioLock = Any()
    
    private val injectors = XCamInjectors(this)
    private val settings = SettingsManager()
    private val surfaceManager = SurfaceManager { printLog(it) }
    private val surfaceProvider = SurfaceProvider { printLog(it) }
    private val mediaEngine = MediaEngine { printLog(it) }
    private val yuvProcessor = YuvFrameProcessor()
    
    private val captureManager = CaptureManager(
        contextProvider = { mContext },
        refreshSettingsAction = { settings.refreshSettings(it) },
    ) { printLog(it) }
    
    private val engine = XCamEngine(
        contextProvider = { mContext },
        settingsProvider = { settings },
        surfaceManager = surfaceManager,
        mediaEngine = mediaEngine,
        surfaceProvider = surfaceProvider,
    ) { printLog(it) }

    // Virtual camera manager (Camera2 HAL3 external camera registration)
    private val virtualCameraManager = VirtualCameraManager { printLog(it) }

    // Device info / build prop spoofer (IMEI, Android ID, Serial, Build props)
    private val deviceSpoofer = DeviceInfoSpoofer(null) { printLog(it) }

    // Root/Xposed/Emulator detection bypass
    private val rootBypass = RootDetectionBypass(null) { printLog(it) }

    // Fingerprint sensor spoofing (Biometric API)
    private val fpSpoof = FingerprintSpoof(null) { printLog(it) }

    // GPS / Location / Network spoofing
    private val locationSpoof = LocationSpoof(null) { printLog(it) }

    // CameraX hooks (ImageAnalysis/ImageCapture/Preview)
    private val cameraXHooks = CameraXHooks(null) { printLog(it) }

    // Depth/IR camera support (anti-spoofing for iProov/Onfido/Jumio)
    private val depthIR = DepthIRCamera(null) { printLog(it) }

    // WebRTC/WebView deep hooks (WebRTC, Canvas, Bitmap)
    private val webRtcHooks = WebRtcDeepHooks(null) { printLog(it) }

    fun isIgnoringHooks(): Boolean = ignoreHooks.get() ?: false
    fun setIgnoringHooks(ignore: Boolean) { ignoreHooks.set(ignore) }

    val context: Context? get() = mContext
    val mediaPath: String? get() = settings.mediaPath
    var previewSwapped: Boolean
        get() = surfaceManager.previewSwapped
        set(value) { surfaceManager.previewSwapped = value }

    fun printLog(msg: String, tr: Throwable? = null) {
        if (tr != null || (msg.contains("Error") || msg.contains("failed") || msg.contains("FATAL"))) {
            Logger.e(this, msg, tr)
        } else {
            Logger.i(this, msg)
        }
    }

    fun logInit(msg: String) = Logger.i(this, "[INIT] $msg")
    fun logHook(msg: String) = Logger.d(this, "[HOOK] $msg")

    fun showToast(message: String) = UIUtils.showToast(mContext, message) { printLog(it) }

    fun isCapturingState() = captureManager.isCapturing

    fun triggerCaptureState() {
        captureManager.triggerCaptureState { engine.getCurrentPosition().toInt() }
    }

    fun getCurrentPosition(): Long = engine.getCurrentPosition()

    fun registerPreviewSurface(s: Surface) = surfaceManager.registerPreviewSurface(s)
    fun registerImageReaderSurface(s: Surface, f: Int, w: Int, h: Int) = surfaceManager.registerImageReaderSurface(s, f, w, h)
    fun isPreviewSurface(s: Surface?) = surfaceManager.isPreviewSurface(s)
    fun isNonPreviewSurface(s: Surface?) = surfaceManager.isNonPreviewSurface(s)
    fun logSessionOutput(s: Surface) = surfaceManager.logSessionOutput(s)
    fun incrementSessionGeneration() = surfaceManager.incrementSessionGeneration()
    fun clearPreviewSurfaces() = surfaceManager.clearPreviewSurfaces(engine.isPlaying())

    fun recordPipelineNode(node: String) {
        PipelineTracker.recordNode(mContext, node)
    }

    @JvmOverloads
    fun reportPipelineCapture(width: Int = 0, height: Int = 0, thumbnail: ByteArray? = null) {
        PipelineTracker.reportCapture(mContext, width, height, thumbnail)
    }

    private fun getOrCreateYuvDecoder(ctx: Context, path: String): MediaCodecYuvDecoder {
        return yuvDecoder ?: synchronized(yuvLock) {
            yuvDecoder ?: MediaCodecYuvDecoder(ctx, path) { printLog(it) }.apply { start() }.also { yuvDecoder = it }
        }
    }

    private fun getOrCreateAudioDecoder(ctx: Context, path: String): MediaCodecAudioDecoder {
        return audioDecoder ?: synchronized(audioLock) {
            audioDecoder ?: MediaCodecAudioDecoder(ctx, path) { printLog(it) }.apply { start() }.also { audioDecoder = it }
        }
    }

    fun injectYuvFrame(image: android.media.Image, width: Int, height: Int) {
        val path = settings.mediaPath
        val ctx = mContext
        if (path != null && ctx != null && path.lowercase().endsWith(".mp4")) {
            val decoder = getOrCreateYuvDecoder(ctx, path)
            val frame = decoder.latestFrame
            if (frame != null) {
                yuvProcessor.injectYuvFrame(image, frame)
                return
            }
        }

        val jpeg = handleStreamFrame(width, height) ?: return
        yuvProcessor.injectToImage(image, jpeg)
    }

    fun handleNv21Frame(width: Int, height: Int): ByteArray? {
        val path = settings.mediaPath ?: return null
        val ctx = mContext ?: return null

        if (path.lowercase().endsWith(".mp4")) {
            val decoder = getOrCreateYuvDecoder(ctx, path)
            val frame = decoder.latestFrame
            if (frame != null) {
                return yuvProcessor.frameToNv21(frame, width, height)
            }
        }

        val jpeg = handleStreamFrame(width, height) ?: return null
        return try {
            val bmp = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return null
            val nv21 = yuvProcessor.rgbToNv21(bmp, width, height)
            bmp.recycle()
            nv21
        } catch (_: Throwable) {
            null
        }
    }

    fun stopEngine() {
        synchronized(yuvLock) {
            yuvDecoder?.stop()
            yuvDecoder = null
        }
        synchronized(audioLock) {
            audioDecoder?.stop()
            audioDecoder = null
        }
        engine.stop()
        captureManager.reset()
        injectors.imageReaderHook.reset()
    }
    fun handleCamera1Preview(st: SurfaceTexture) = engine.handleCamera1Preview(st)
    fun handleModernPreview(s: Surface) = engine.handleModernPreview(s)
    fun getDummySurface() = engine.getDummySurface()

    // Virtual camera API
    fun startVirtualCamera() { virtualCameraManager.start() }
    fun stopVirtualCamera() { virtualCameraManager.stop() }
    fun isVirtualCameraActive(): Boolean = virtualCameraManager.isActive()
    fun setVirtualCameraMediaPath(path: String?) { virtualCameraManager.setMediaPath(path) }
    fun setVirtualCameraMirrored(mirrored: Boolean) { virtualCameraManager.setMirrored(mirrored) }
    fun setVirtualCameraRotation(angle: Int) { virtualCameraManager.setRotationAngle(angle) }
    fun getVirtualCameraStatus(): String = virtualCameraManager.getStatus()
    fun isVirtualCameraEnabled(): Boolean = virtualCameraManager.isEnabled()
    fun releaseVirtualCamera() { virtualCameraManager.release() }

    // Device info / build prop spoofing API
    fun setDeviceAndroidId(id: String?) { deviceSpoofer.setAndroidId(id) }
    fun setDeviceImei(imei: String?) { deviceSpoofer.setImei(imei) }
    fun setDeviceId(deviceId: String?) { deviceSpoofer.setDeviceId(deviceId) }
    fun setDeviceSerial(serial: String?) { deviceSpoofer.setSerial(serial) }
    fun setDeviceHardware(hardware: String?) { deviceSpoofer.setHardware(hardware) }
    fun setDeviceProduct(product: String?) { deviceSpoofer.setProduct(product) }
    fun setDeviceManufacturer(manufacturer: String?) { deviceSpoofer.setManufacturer(manufacturer) }
    fun setDeviceBrand(brand: String?) { deviceSpoofer.setBrand(brand) }
    fun setDeviceModel(model: String?) { deviceSpoofer.setModel(model) }
    fun setDeviceBoard(board: String?) { deviceSpoofer.setBoard(board) }
    fun setDeviceBootloader(bootloader: String?) { deviceSpoofer.setBootloader(bootloader) }
    fun setDeviceRadio(radio: String?) { deviceSpoofer.setRadio(radio) }
    fun setDeviceDevice(device: String?) { deviceSpoofer.setDevice(device) }
    fun setDeviceOta(ota: String?) { deviceSpoofer.setOta(ota) }
    fun setDeviceFingerprint(fingerprint: String?) { deviceSpoofer.setFingerprint(fingerprint) }
    fun setDeviceSdkInt(sdkInt: String?) { deviceSpoofer.setSdkInt(sdkInt) }
    fun setDeviceRelease(release: String?) { deviceSpoofer.setRelease(release) }
    fun setDeviceIncremental(incremental: String?) { deviceSpoofer.setIncremental(incremental) }
    fun setDeviceCodename(codename: String?) { deviceSpoofer.setCodename(codename) }
    fun setSystemProperty(key: String, value: String) { deviceSpoofer.setSystemProperty(key, value) }
    fun getDeviceStatus(): String = deviceSpoofer.getStatus()
    fun getSystemPropertyValue(key: String, default: String): String = deviceSpoofer.getSystemPropertyValue(key, default)
    fun getSystemPropertyValue(key: String): String = deviceSpoofer.getSystemPropertyValue(key)

    // Root / Xposed / Emulator detection bypass API
    fun setSuExists(suExists: Boolean?) { rootBypass.setSuExists(suExists) }
    fun getSuExists(): Boolean = rootBypass.getSuExists()
    fun setMagiskExists(magiskExists: Boolean?) { rootBypass.setMagiskExists(magiskExists) }
    fun getMagiskExists(): Boolean = rootBypass.getMagiskExists()
    fun setXposedVersion(version: String?) { rootBypass.setXposedVersion(version) }
    fun getXposedVersion(): String = rootBypass.getXposedVersion()
    fun setDebuggable(debuggable: Int?) { rootBypass.setDebuggable(debuggable) }
    fun getDebuggable(): Int = rootBypass.getDebuggable()
    fun setSelinuxEnabled(enabled: Boolean?) { rootBypass.setSelinuxEnabled(enabled) }
    fun getSelinuxEnabled(): Boolean = rootBypass.getSelinuxEnabled()
    fun setIsEmulator(isEmulator: Boolean?) { rootBypass.setIsEmulator(isEmulator) }
    fun getIsEmulator(): Boolean = rootBypass.getIsEmulator()
    fun setSystemPropertyOverride(key: String, value: String) { rootBypass.setSystemProperty(key, value) }
    fun getSystemPropertyOverride(key: String, default: String): String = rootBypass.getSystemPropertyValue(key, default)
    fun getRootBypassStatus(): String = rootBypass.getStatus()

    // Fingerprint sensor spoofing API
    fun enableFpSpoof() { fpSpoof.enable() }
    fun disableFpSpoof() { fpSpoof.disable() }
    fun isFpSpoofing(): Boolean = fpSpoof.isSpoofing()
    fun setFpRecorded(imageBytes: ByteArray?) { fpSpoof.setRecordedFingerprint(imageBytes) }
    fun getFpStatus(): String = fpSpoof.getStatus()
    fun isFpReady(): Boolean = fpSpoof.isReady()
    fun fpAuthenticate(): Boolean = fpSpoof.authenticate()

    // GPS / Location / Network spoofing API
    fun setLocation(lat: Double, lng: Double) { locationSpoof.setTargetLocation(lat, lng) }
    fun setAltitude(alt: Double) { locationSpoof.setTargetAltitude(alt) }
    fun setBearing(bearing: Float) { locationSpoof.setTargetBearing(bearing) }
    fun setSpeed(speed: Float) { locationSpoof.setTargetSpeed(speed) }
    fun setAccuracy(accuracy: Float) { locationSpoof.setAccuracy(accuracy) }
    fun setCellTowerInfo(mcc: Int, mnc: Int, lac: Int, cid: Int) { locationSpoof.setCellTowerInfo(mcc, mnc, lac, cid) }
    fun setWifiInfo(ssid: String?, bssid: String?, mac: String?) { locationSpoof.setWifiInfo(ssid, bssid, mac) }
    fun getSpoofedLocation() = locationSpoof.getSpoofedLocation()
    fun getLocationStatus(): String = locationSpoof.getStatus()
    fun isLocationAvailable(): Boolean = locationSpoof.isLocationAvailable()

    // CameraX hooks API
    fun enableCameraX() { cameraXHooks.enable() }
    fun disableCameraX() { cameraXHooks.disable() }
    fun isCameraXHooking(): Boolean = cameraXHooks.isHooking()
    fun setCameraXCameraFacing(facing: Int) { cameraXHooks.setCameraFacing(facing) }
    fun setCameraXResolution(width: Int, height: Int) { cameraXHooks.setResolution(width, height) }
    fun getCameraXStatus(): String = cameraXHooks.getStatus()
    fun isCameraXReady(): Boolean = cameraXHooks.isCameraXReady()

    // Depth/IR camera API
    fun enableDepthIR() { depthIR.enable() }
    fun disableDepthIR() { depthIR.disable() }
    fun isDepthIRHooking(): Boolean = depthIR.isHooking()
    fun setDepthFrame(frame: ByteArray?, width: Int, height: Int) { depthIR.setDepthFrame(frame, width, height) }
    fun setIRFrame(frame: ByteArray?, width: Int, height: Int) { depthIR.setIRFrame(frame, width, height) }
    fun setDepthCameraFacing(facing: Int) { depthIR.setDepthCameraFacing(facing) }
    fun setIRCameraFacing(facing: Int) { depthIR.setIRCameraFacing(facing) }
    fun getDepthIRStatus(): String = depthIR.getStatus()
    fun isDepthIRReady(): Boolean = depthIR.isReady()

    // WebRTC/WebView deep hooks API
    fun enableWebRtc() { webRtcHooks.enable() }
    fun disableWebRtc() { webRtcHooks.disable() }
    fun isWebRtcHooking(): Boolean = webRtcHooks.isHooking()
    fun createFakeCapturer(surface: android.view.Surface) = webRtcHooks.createFakeCapturer(surface)
    fun createFakeSurfaceTextureHelper() = webRtcHooks.createFakeSurfaceTextureHelper()
    fun hookCamera2CapturerStart(capturer: Any, callback: Any) { webRtcHooks.hookCamera2CapturerStart(capturer, callback) }
    fun createFakeVideoTrack(pc: Any, trackId: String) = webRtcHooks.createFakeVideoTrack(pc, trackId)
    fun addFakeVideoTrack(pc: Any, track: Any) { webRtcHooks.addFakeVideoTrack(pc, track) }
    fun hookGetUserMedia(webView: android.webkit.WebView) { webRtcHooks.hookWebRtcGetUserMedia(webView) }
    fun hookAddTrack(webView: android.webkit.WebView) { webRtcHooks.hookWebRtcAddTrack(webView) }
    fun injectFakeTrack(webView: android.webkit.WebView, track: Any, streamId: String) { webRtcHooks.injectFakeTrack(webView, track, streamId) }
    fun getWebRtcStatus(): String = webRtcHooks.getStatus()
    fun isWebRtcReady(): Boolean = webRtcHooks.isReady()

    fun injectAudioBytes(buffer: ByteArray, offset: Int, length: Int) {
        val path = settings.mediaPath ?: return
        val ctx = mContext ?: return
        getOrCreateAudioDecoder(ctx, path).readBytes(buffer, offset, length)
    }

    fun injectAudioShorts(buffer: ShortArray, offset: Int, length: Int) {
        val path = settings.mediaPath ?: return
        val ctx = mContext ?: return
        getOrCreateAudioDecoder(ctx, path).readShorts(buffer, offset, length)
    }

    fun injectAudioByteBuffer(buffer: java.nio.ByteBuffer, length: Int) {
        val path = settings.mediaPath ?: return
        val ctx = mContext ?: return
        getOrCreateAudioDecoder(ctx, path).readByteBuffer(buffer, length)
    }

    fun getImageReaderCaptureWidth(): Int = injectors.imageReaderHook.activeCaptureWidth
    fun getImageReaderCaptureHeight(): Int = injectors.imageReaderHook.activeCaptureHeight

    @JvmOverloads
    fun handleCapture(w: Int = 0, h: Int = 0, maxSize: Int = Int.MAX_VALUE): ByteArray? {
        val irW = injectors.imageReaderHook.activeCaptureWidth
        val irH = injectors.imageReaderHook.activeCaptureHeight
        val targetW = if (w > 0) w else if (irW > 0) irW else Constants.DEFAULT_CAPTURE_WIDTH
        val targetH = if (h > 0) h else if (irH > 0) irH else Constants.DEFAULT_CAPTURE_HEIGHT
        return captureManager.handleCapture(
            settings.mediaPath, targetW, targetH, settings.rotationAngle, settings.isMirrored, maxSize,
            { isIgnoringHooks() },
            { setIgnoringHooks(it) },
            yuvDecoder?.latestFrame
        )
    }

    fun handleStreamFrame(w: Int, h: Int) = captureManager.handleStreamFrame(
        settings.mediaPath, w, h, settings.rotationAngle, settings.isMirrored,
        { isIgnoringHooks() },
        { setIgnoringHooks(it) },
    )

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        super.onPackageReady(param)
        val processName = SystemUtils.getProcessNameStrict()
        if (param.packageName == "com.hazbu.xcam") {
            hookManagerApp(param)
            return
        }
        
        if (!processName.contains(param.packageName)) return
        if (hooksInstalled) return
        hooksInstalled = true

        incrementSessionGeneration()
        clearPreviewSurfaces()
        PipelineTracker.reset()
        logInit(">>> ACTIVE IN: $processName (API ${Build.VERSION.SDK_INT}) <<<")

        // Start virtual camera if enabled in settings
        if (settings.mediaPath != null) {
            virtualCameraManager.start()
            virtualCameraManager.setMirrored(settings.isMirrored)
            virtualCameraManager.setRotationAngle(settings.rotationAngle)
            virtualCameraManager.setMediaPath(settings.mediaPath)
        }

        // Enable device info spoofing
        deviceSpoofer.enable()
        logInit("[+] Device info spoofing ENABLED")

        // Enable root/Xposed/emulator bypass
        rootBypass.enable()
        logInit("[+] Root/Xposed/Emulator bypass ENABLED")

        // Enable fingerprint spoofing if phone has fingerprint sensor
        if (fpSpoof.hasFingerprintSensor()) {
            fpSpoof.enable()
            fpSpoof.enablePhoneMode()
            logInit("[+] Fingerprint sensor spoofing ENABLED")
        }

        // Enable GPS/Location spoofing if phone mode active
        if (locationSpoof.isPhoneMode() || true) {
            locationSpoof.enable()
            logInit("[+] GPS/Location spoofing ENABLED")
        }

        // Enable CameraX hooks if available
        if (cameraXHooks.isCameraXAvailable) {
            cameraXHooks.enable()
            logInit("[+] CameraX hooks ENABLED")
        }

        // Enable Depth/IR camera support
        depthIR.enable()
        logInit("[+] Depth/IR camera ENABLED")

        // Enable WebRTC/WebView deep hooks if available
        if (webRtcHooks.isWebRtcAvailable) {
            webRtcHooks.enable()
            logInit("[+] WebRTC/WebView deep hooks ENABLED")
        }

        hookContextInit()
        injectors.install(param)
    }

    private fun hookManagerApp(param: XposedModuleInterface.PackageReadyParam) {
        try {
            val clazz = param.classLoader.loadClass("com.hazbu.xcam.ui.MainActivity")
            hook(clazz.getDeclaredMethod("checkSelfActive")).intercept { true }
        } catch (_: Throwable) {}
    }

    private fun hookContextInit() {
        val currentApp = SystemUtils.getCurrentApplication()
        if (currentApp != null && !isInitialized) {
            mContext = currentApp
            logInit("Context immediately resolved from ActivityThread: ${mContext?.packageName}")
            mContext?.let { settings.refreshSettings(it) }
            isInitialized = true
        }

        try {
            val attachMethod = Class.forName("android.content.ContextWrapper")
                .getDeclaredMethod("attachBaseContext", Context::class.java)

            hook(attachMethod).intercept { chain ->
                val result = chain.proceed()
                if (!isInitialized) {
                    mContext = chain.thisObject as? Context
                    logInit("Context Initialized: ${mContext?.packageName}")
                    mContext?.let { settings.refreshSettings(it) }
                    isInitialized = true
                }
                result
            }
        } catch (e: Exception) {
            logInit("Context hook failure: ${e.message}")
        }
    }
}
