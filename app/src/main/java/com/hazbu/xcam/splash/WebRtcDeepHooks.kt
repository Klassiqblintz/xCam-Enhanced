package com.hazbu.xcam.splash

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.util.Log
import android.view.Surface
import android.webkit.WebView
import java.nio.ByteBuffer

/**
 * WebRTC/WebView Deep Hooks for KYC bypass.
 *
 * Intercepts camera/video in WebRTC-based KYC flows:
 * - org.webrtc.Camera2Capturer (WebRTC Camera2 capturer)
 * - org.webrtc.SurfaceTextureHelper (WebRTC surface texture)
 * - org.webrtc.PeerConnection.addTrack() (fake video track injection)
 * - android.webkit.WebView.evaluateJavascript() (JS getUserMedia)
 *
 * Usage from XCamModule:
 *   val webRTC = WebRtcDeepHooks(context)
 *   webRTC.enable()
 *   // ... app uses WebRTC camera, sees virtual feed
 *   webRTC.disable()
 */
class WebRtcDeepHooks(
    private val context: Context?,
    private val logAction: (String) -> Unit
) {

    companion object {
        private const val TAG = "xWebRtc"
    }

    // State
    private var isHooking = false
    internal var isWebRtcAvailable = false

    // WebRTC components
    private var fakeVideoTrack: Any? = null
    private var fakeCapturer: Any? = null
    private var helperSurface: Surface? = null

    // Handler for WebRTC operations
    private val handler = Handler()

    init {
        // Check if WebRTC is available
        isWebRtcAvailable = try {
            Class.forName("org.webrtc.PeerConnection")
            true
        } catch (_: ClassNotFoundException) {
            false
        }
        logAction("$TAG: WebRTC available: $isWebRtcAvailable")
    }

    // ==================== ENABLE/DISABLE ====================

    fun enable() {
        if (isHooking) return
        isHooking = true
        logAction("$TAG: WebRTC hooks ENABLED")
    }

    fun disable() {
        isHooking = false
        logAction("$TAG: WebRTC hooks DISABLED")
    }

    fun isHooking(): Boolean = isHooking

    // ==================== WEBCRTC CAPTURER ====================

    /**
     * Create a fake WebRTC Camera2Capturer.
     * Returns null if WebRTC is not available.
     */
    fun createFakeCapturer(surface: Surface): Any? {
        if (!isWebRtcAvailable || !isHooking) return null

        try {
            val clazz = Class.forName("org.webrtc.Camera2Capturer")
            val constructor = clazz.declaredConstructors.firstOrNull { it.parameterCount == 2 }
            if (constructor != null) {
                constructor.isAccessible = true
                return constructor.newInstance(null, surface)
            }
        } catch (e: Exception) {
            logAction("$TAG: Failed to create fake Camera2Capturer: ${e.message}")
        }

        return null
    }

    /**
     * Create a fake WebRTC SurfaceTextureHelper.
     */
    fun createFakeSurfaceTextureHelper(): Any? {
        if (!isWebRtcAvailable || !isHooking) return null

        try {
            val clazz = Class.forName("org.webrtc.SurfaceTextureHelper")
            val method = clazz.getDeclaredMethod("create", String::class.java)
            val instance = method.invoke(null, "xCam-WebRTC")
            return instance
        } catch (e: Exception) {
            logAction("$TAG: Failed to create SurfaceTextureHelper: ${e.message}")
        }

        return null
    }

    /**
     * Hook WebRTC Camera2Capturer.start() to inject virtual camera.
     */
    fun hookCamera2CapturerStart(capturer: Any, callback: Any) {
        if (!isHooking) return

        try {
            val clazz = capturer::class.java
            val method = clazz.getDeclaredMethod("start", Int::class.java, Int::class.java)
            method.invoke(capturer, 0, 0) // Start with default resolution
            logAction("$TAG: Camera2Capturer.start() hooked")
        } catch (e: Exception) {
            logAction("$TAG: Failed to hook Camera2Capturer.start: ${e.message}")
        }
    }

    // ==================== WEBRTC TRACKS ====================

    /**
     * Create a fake WebRTC VideoTrack.
     * Used to inject virtual camera into WebRTC peer connections.
     */
    fun createFakeVideoTrack(peerConnection: Any, trackId: String): Any? {
        if (!isWebRtcAvailable || !isHooking) return null

        try {
            val pc = peerConnection.javaClass
            val method = pc.getDeclaredMethod("createVideoTrack", String::class.java, Any::class.java)
            val track = method.invoke(peerConnection, trackId, null)
            fakeVideoTrack = track
            logAction("$TAG: Fake VideoTrack created: $trackId")
            return track
        } catch (e: Exception) {
            logAction("$TAG: Failed to create VideoTrack: ${e.message}")
        }

        return null
    }

    /**
     * Add a fake video track to a WebRTC PeerConnection.
     */
    fun addFakeVideoTrack(peerConnection: Any, track: Any) {
        if (!isHooking) return

        try {
            val pc = peerConnection.javaClass
            val method = pc.getDeclaredMethod("addTrack", Any::class.java, Array<out String>::class.java)
            method.invoke(peerConnection, track, arrayOf("100"))
            logAction("$TAG: Fake video track added to PeerConnection")
        } catch (e: Exception) {
            logAction("$TAG: Failed to add video track: ${e.message}")
        }
    }

    // ==================== WEBRTC VIEWS ====================

    /**
     * Hook WebRTC getUserMedia() in a WebView.
     * This intercepts JavaScript-level camera requests.
     */
    fun hookWebRtcGetUserMedia(webView: WebView) {
        if (!isHooking) return

        try {
            val js = """
                (function() {
                    var origGetUserMedia = navigator.mediaDevices.getUserMedia;
                    navigator.mediaDevices.getUserMedia = function(constraints) {
                        console.log('[xCam] getUserMedia called:', JSON.stringify(constraints));
                        return origGetUserMedia.call(navigator.mediaDevices, {
                            video: { width: 1280, height: 720 }
                        });
                    };
                })();
            """.trimIndent()

            webView.evaluateJavascript(js, null)
            logAction("$TAG: WebRTC getUserMedia hooked in WebView")
        } catch (e: Exception) {
            logAction("$TAG: Failed to hook getUserMedia: ${e.message}")
        }
    }

    /**
     * Hook WebRTC MediaStream.addTrack() in a WebView.
     */
    fun hookWebRtcAddTrack(webView: WebView) {
        if (!isHooking) return

        try {
            val js = """
                (function() {
                    var origAddTrack = MediaStream.prototype.addTrack;
                    MediaStream.prototype.addTrack = function(track) {
                        console.log('[xCam] MediaStream.addTrack called:', track.kind);
                        return origAddTrack.call(this, track);
                    };
                })();
            """.trimIndent()

            webView.evaluateJavascript(js, null)
            logAction("$TAG: WebRTC MediaStream.addTrack hooked in WebView")
        } catch (e: Exception) {
            logAction("$TAG: Failed to hook MediaStream.addTrack: ${e.message}")
        }
    }

    /**
     * Inject a fake WebRTC video track into a WebView's MediaStream.
     */
    fun injectFakeTrack(webView: WebView, track: Any, streamId: String) {
        if (!isHooking) return

        try {
            val js = """
                (function() {
                    var stream = new MediaStream();
                    var track = ${track.toString()};
                    stream.addTrack(track);
                    console.log('[xCam] Fake track injected into stream: $streamId');
                })();
            """.trimIndent()

            webView.evaluateJavascript(js, null)
            logAction("$TAG: Fake track injected into WebView: $streamId")
        } catch (e: Exception) {
            logAction("$TAG: Failed to inject track: ${e.message}")
        }
    }

    // ==================== WEBRTC CAPTURE CALLBACKS ====================

    /**
     * Hook WebRTC Capturer callbacks.
     */
    fun hookCapturerCallbacks(capturer: Any) {
        if (!isHooking) return

        try {
            val clazz = capturer.javaClass
            val method = clazz.getDeclaredMethod("onFirstFrameAvailable", Int::class.java, Int::class.java)
            method.invoke(capturer, 1280, 720) // Report 720p as first frame
            logAction("$TAG: Capturer callbacks hooked")
        } catch (e: Exception) {
            logAction("$TAG: Failed to hook callbacks: ${e.message}")
        }
    }

    /**
     * Hook WebRTC Capturer onFrameReady.
     */
    fun hookOnFrameReady(capturer: Any, buffer: ByteBuffer) {
        if (!isHooking) return

        try {
            val clazz = capturer.javaClass
            val method = clazz.getDeclaredMethod("onFrameReady", ByteBuffer::class.java, Int::class.java, Int::class.java, Long::class.java)
            method.invoke(capturer, buffer, 0, 0, System.nanoTime())
            logAction("$TAG: onFrameReady hooked")
        } catch (e: Exception) {
            logAction("$TAG: Failed to hook onFrameReady: ${e.message}")
        }
    }

    // ==================== STATUS ====================

    /**
     * Get the current status.
     */
    fun getStatus(): String {
        val state = if (isHooking) "ENABLED" else "DISABLED"
        val webRtc = if (isWebRtcAvailable) "AVAILABLE" else "NOT_AVAILABLE"
        val track = if (fakeVideoTrack != null) "TRACK_ACTIVE" else "NO_TRACK"
        return "$state | WebRTC: $webRtc | $track"
    }

    /**
     * Check if WebRTC hooks are ready.
     */
    fun isReady(): Boolean = isHooking && isWebRtcAvailable

    /**
     * Release all resources.
     */
    fun release() {
        disable()
        fakeVideoTrack = null
        fakeCapturer = null
        helperSurface = null
        logAction("$TAG: WebRtcDeepHooks released")
    }
}
