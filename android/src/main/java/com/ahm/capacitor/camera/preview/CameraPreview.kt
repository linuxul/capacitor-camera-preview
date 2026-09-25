@file:Suppress("DEPRECATION")

package com.ahm.capacitor.camera.preview

import android.Manifest.permission.CAMERA
import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.FrameLayout
import com.getcapacitor.JSObject
import com.getcapacitor.Logger
import com.getcapacitor.PermissionState
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginException
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import java.io.File
import org.json.JSONArray

@CapacitorPlugin(name = "CameraPreview", permissions = [Permission(strings = [CAMERA], alias = CameraPreview.CAMERA_PERMISSION_ALIAS)])
public class CameraPreview :
    Plugin(),
    CameraActivity.CameraPreviewListener {
    private var captureCallbackId: String? = ""
    private var snapshotCallbackId: String? = ""
    private var recordCallbackId: String? = ""
    private var cameraStartCallbackId: String? = ""

    // keep track of previously specified orientation to support locking orientation:
    private var previousOrientationRequest = -1

    private var fragment: CameraActivity? = null
    private val containerViewId = 20

    private val hasCamera: Boolean
        get() = fragment?.camera != null

    @PluginMethod
    public fun start(call: PluginCall) {
        if (PermissionState.GRANTED == getPermissionState(CAMERA_PERMISSION_ALIAS)) {
            startCamera(call)
        } else {
            requestPermissionForAlias(CAMERA_PERMISSION_ALIAS, call, "handleCameraPermissionResult")
        }
    }

    @PluginMethod
    public fun flip(call: PluginCall) {
        try {
            // Without a preview this throws, which is reported like any other failure to flip.
            fragment!!.switchCamera()
        } catch (e: Exception) {
            Logger.debug(logTag, "Camera flip exception: $e")
            throw PluginException("failed to flip camera")
        }
        call.resolve()
    }

    @PluginMethod
    public fun setOpacity(call: PluginCall) {
        val fragment = fragment
        if (fragment?.camera == null) {
            throw PluginException("Camera is not running")
        }

        val opacity = call.getFloat("opacity") ?: 1f
        fragment.setOpacity(opacity)
        // Nothing answers this call later: it used to be saved and left pending
        call.resolve()
    }

    @PluginMethod
    public fun capture(call: PluginCall) {
        val fragment = fragment
        if (fragment?.camera == null) {
            throw PluginException("Camera is not running")
        }
        bridge.saveCall(call)
        captureCallbackId = call.callbackId

        val quality = call.getInt("quality") ?: 85
        // Image Dimensions - Optional
        val width = call.getInt("width") ?: 0
        val height = call.getInt("height") ?: 0
        fragment.takePicture(width, height, quality)
    }

    @PluginMethod
    public fun captureSample(call: PluginCall) {
        val fragment = fragment
        if (fragment?.camera == null) {
            throw PluginException("Camera is not running")
        }
        bridge.saveCall(call)
        snapshotCallbackId = call.callbackId

        val quality = call.getInt("quality") ?: 85
        fragment.takeSnapshot(quality)
    }

    // stop does all of its work on the main thread but stays on the plugin thread: start hands its view work to the
    // main thread from the plugin thread, and a MAIN stop could run before the work of an earlier start.
    @SuppressLint("WrongConstant")
    @PluginMethod
    public fun stop(call: PluginCall) {
        bridge.activity.runOnUiThread {
            val containerView = bridge.activity.findViewById<FrameLayout>(containerViewId)

            // allow orientation changes after closing camera:
            bridge.activity.requestedOrientation = previousOrientationRequest
            bridge.webView.setOnTouchListener(null)

            if (containerView != null) {
                (bridge.webView.parent as ViewGroup).removeView(containerView)
                bridge.webView.setBackgroundColor(Color.WHITE)
                val fragmentTransaction = activity.fragmentManager.beginTransaction()
                fragment?.let { fragmentTransaction.remove(it) }
                fragmentTransaction.commit()
                fragment = null

                call.resolve()
            } else {
                call.reject("camera already stopped")
            }
        }
    }

    @PluginMethod
    public fun getSupportedFlashModes(call: PluginCall) {
        val camera = fragment?.camera
        if (camera == null) {
            throw PluginException("Camera is not running")
        }

        val supportedFlashModes: List<String>? = camera.parameters.supportedFlashModes
        val jsonFlashModes = JSONArray()
        supportedFlashModes?.forEach { jsonFlashModes.put(it) }

        val jsObject = JSObject()
        jsObject.put("result", jsonFlashModes)
        call.resolve(jsObject)
    }

    @PluginMethod
    public fun setFlashMode(call: PluginCall) {
        val fragment = fragment
        val camera = fragment?.camera
        if (fragment == null || camera == null) {
            throw PluginException("Camera is not running")
        }

        val flashMode = call.getString("flashMode")
        if (flashMode.isNullOrEmpty()) {
            throw PluginException("flashMode required parameter is missing")
        }

        val params = camera.parameters

        // A camera without a flash reports no list at all, and this throws as it always did.
        val supportedFlashModes = camera.parameters.supportedFlashModes!!
        if (supportedFlashModes.indexOf(flashMode) == -1) {
            throw PluginException("Flash mode not recognised: $flashMode")
        }
        params.flashMode = flashMode

        fragment.setCameraParameters(params)

        call.resolve()
    }

    @PluginMethod
    public fun startRecordVideo(call: PluginCall) {
        val fragment = fragment
        if (fragment?.camera == null) {
            throw PluginException("Camera is not running")
        }
        val filename = "videoTmp"
        videoFilePath = activity.cacheDir.toString() + "/"

        val position = call.getString("position", "front")
        val width = call.getInt("width") ?: 0
        val height = call.getInt("height") ?: 0
        val withFlash = call.getBoolean("withFlash") ?: false
        val maxDuration = call.getInt("maxDuration") ?: 0
        bridge.saveCall(call)
        recordCallbackId = call.callbackId

        bridge.activity.runOnUiThread {
            fragment.startRecord(getFilePath(filename), position, width, height, 70, withFlash, maxDuration)
        }

        call.resolve()
    }

    @PluginMethod
    public fun stopRecordVideo(call: PluginCall) {
        val fragment = fragment
        if (fragment?.camera == null) {
            throw PluginException("Camera is not running")
        }

        println("stopRecordVideo - Callbackid=" + call.callbackId)

        bridge.saveCall(call)
        recordCallbackId = call.callbackId

        fragment.stopRecord()
    }

    @PluginMethod
    public fun isCameraStarted(call: PluginCall) {
        val ret = JSObject()
        ret.put("value", hasCamera)
        call.resolve(ret)
    }

    @PermissionCallback
    private fun handleCameraPermissionResult(call: PluginCall) {
        if (PermissionState.GRANTED == getPermissionState(CAMERA_PERMISSION_ALIAS)) {
            startCamera(call)
        } else {
            Logger.debug(logTag, "User denied camera permission: " + getPermissionState(CAMERA_PERMISSION_ALIAS))
            call.reject("Permission failed: user denied access to camera.")
        }
    }

    private fun startCamera(call: PluginCall) {
        val requestedPosition = call.getString("position")
        val position = if (requestedPosition.isNullOrEmpty() || "rear" == requestedPosition) "back" else "front"

        val x = call.getInt("x") ?: 0
        val y = call.getInt("y") ?: 0
        val width = call.getInt("width") ?: 0
        val height = call.getInt("height") ?: 0
        val paddingBottom = call.getInt("paddingBottom") ?: 0
        val toBack = call.getBoolean("toBack") ?: false
        val storeToFile = call.getBoolean("storeToFile") ?: false
        val enableOpacity = call.getBoolean("enableOpacity") ?: false
        val enableZoom = call.getBoolean("enableZoom") ?: false
        val disableExifHeaderStripping = call.getBoolean("disableExifHeaderStripping") ?: true
        val lockOrientation = call.getBoolean("lockAndroidOrientation") ?: false
        previousOrientationRequest = bridge.activity.requestedOrientation

        val newFragment = CameraActivity()
        newFragment.setEventListener(this)
        newFragment.defaultCamera = position
        newFragment.tapToTakePicture = false
        newFragment.dragEnabled = false
        newFragment.tapToFocus = true
        newFragment.disableExifHeaderStripping = disableExifHeaderStripping
        newFragment.storeToFile = storeToFile
        newFragment.toBack = toBack
        newFragment.enableOpacity = enableOpacity
        newFragment.enableZoom = enableZoom
        fragment = newFragment

        bridge.activity.runOnUiThread {
            val metrics = bridge.activity.resources.displayMetrics
            // lock orientation if specified in options:
            if (lockOrientation) {
                bridge.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
            }

            // offset
            val computedX = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, x.toFloat(), metrics).toInt()
            val computedY = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, y.toFloat(), metrics).toInt()

            // size
            var computedWidth: Int? = null
            var computedHeight: Int? = null
            var computedPaddingBottom = 0

            if (paddingBottom != 0) {
                computedPaddingBottom = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, paddingBottom.toFloat(), metrics).toInt()
            }

            if (width != 0) {
                computedWidth = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, width.toFloat(), metrics).toInt()
            }

            if (height != 0) {
                computedHeight =
                    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, height.toFloat(), metrics).toInt() - computedPaddingBottom
            }

            newFragment.setRect(computedX, computedY, computedWidth, computedHeight)

            if (bridge.activity.findViewById<FrameLayout>(containerViewId) == null) {
                val containerView = FrameLayout(activity.applicationContext)
                containerView.id = containerViewId

                bridge.webView.setBackgroundColor(Color.TRANSPARENT)
                (bridge.webView.parent as ViewGroup).addView(containerView)
                if (toBack) {
                    bridge.webView.parent.bringChildToFront(bridge.webView)
                }

                val fragmentTransaction = bridge.activity.fragmentManager.beginTransaction()
                fragmentTransaction.add(containerView.id, newFragment)
                fragmentTransaction.commit()

                // NOTE: we don't return invoke call.resolve here because it must be invoked in onCameraStarted
                // otherwise the plugin start method might resolve/return before the camera is actually set in CameraActivity
                // onResume method (see this line mCamera = Camera.open(defaultCameraId);) and the next subsequent plugin
                // method invocations (for example, getSupportedFlashModes) might fails with "Camera is not running" error
                // because camera is not available yet and hasCamera method will return false
                // Please also see https://developer.android.com/reference/android/hardware/Camera.html#open%28int%29
                bridge.saveCall(call)
                cameraStartCallbackId = call.callbackId
            } else {
                call.reject("camera already started")
            }
        }
    }

    override fun onPictureTaken(originalPicture: String?) {
        val jsObject = JSObject()
        jsObject.put("value", originalPicture)
        bridge.getSavedCall(captureCallbackId)?.resolve(jsObject)
    }

    override fun onPictureTakenError(message: String?) {
        bridge.getSavedCall(captureCallbackId)?.reject(message)
    }

    override fun onSnapshotTaken(originalPicture: String?) {
        val jsObject = JSObject()
        jsObject.put("value", originalPicture)
        bridge.getSavedCall(snapshotCallbackId)?.resolve(jsObject)
    }

    override fun onSnapshotTakenError(message: String?) {
        bridge.getSavedCall(snapshotCallbackId)?.reject(message)
    }

    override fun onFocusSet(pointX: Int, pointY: Int) {}

    override fun onFocusSetError(message: String?) {}

    override fun onBackButton() {}

    override fun onCameraStarted() {
        if (fragment?.toBack == true) {
            setupBroadcast()
        }

        val pluginCall = bridge.getSavedCall(cameraStartCallbackId)
        if (pluginCall != null) {
            pluginCall.resolve()
            bridge.releaseCall(pluginCall)
        } else {
            Logger.warn(logTag, "onCameraStarted but no saved start call (cameraStartCallbackId=$cameraStartCallbackId)")
        }
    }

    override fun onStartRecordVideo() {}

    override fun onStartRecordVideoError(message: String?) {
        bridge.getSavedCall(recordCallbackId)?.reject(message)
    }

    override fun onStopRecordVideo(file: String?) {
        val jsObject = JSObject()
        jsObject.put("videoFilePath", file)
        bridge.getSavedCall(recordCallbackId)?.resolve(jsObject)
    }

    override fun onStopRecordVideoError(error: String?) {
        bridge.getSavedCall(recordCallbackId)?.reject(error)
    }

    private fun getFilePath(filename: String): String {
        var fileName = filename

        var i = 1

        while (File(videoFilePath + fileName + VIDEO_FILE_EXTENSION).exists()) {
            // Add number suffix if file exists
            fileName = filename + '_' + i
            i++
        }

        return videoFilePath + fileName + VIDEO_FILE_EXTENSION
    }

    /** When touch event is triggered, relay it to camera view if needed so it can support pinch zoom */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupBroadcast() {
        bridge.webView.isClickable = true
        bridge.webView.setOnTouchListener { _, event ->
            val fragment = fragment
            if (fragment != null && fragment.toBack) {
                fragment.frameContainerLayout?.dispatchTouchEvent(event)
            }
            false
        }
    }

    internal companion object {
        const val CAMERA_PERMISSION_ALIAS = "camera"

        private var videoFilePath = ""
        private const val VIDEO_FILE_EXTENSION = ".mp4"
    }
}
