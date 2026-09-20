@file:Suppress("DEPRECATION")

package com.ahm.capacitor.camera.preview

import android.app.Activity
import android.app.Fragment
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.Camera
import android.hardware.Camera.PictureCallback
import android.hardware.Camera.ShutterCallback
import android.media.AudioManager
import android.media.CamcorderProfile
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.RelativeLayout
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sqrt

public class CameraActivity : Fragment() {
    public interface CameraPreviewListener {
        public fun onPictureTaken(originalPicture: String?)

        public fun onPictureTakenError(message: String?)

        public fun onSnapshotTaken(originalPicture: String?)

        public fun onSnapshotTakenError(message: String?)

        public fun onFocusSet(pointX: Int, pointY: Int)

        public fun onFocusSetError(message: String?)

        public fun onBackButton()

        public fun onCameraStarted()

        public fun onStartRecordVideo()

        public fun onStartRecordVideoError(message: String?)

        public fun onStopRecordVideo(file: String?)

        public fun onStopRecordVideoError(error: String?)
    }

    private enum class RecordingState {
        INITIALIZING,
        STARTED,
        STOPPED
    }

    private var eventListener: CameraPreviewListener? = null

    public var mainLayout: FrameLayout? = null

    public var frameContainerLayout: FrameLayout? = null

    private var mPreview: Preview? = null
    private var canTakePicture = true

    private var rootView: View? = null
    private var cameraParameters: Camera.Parameters? = null

    public var camera: Camera? = null
        private set
    private var numberOfCameras = 0
    private var cameraCurrentlyLocked = 0
    private var currentQuality = 0

    private val mRecordingState = RecordingState.INITIALIZING
    private var mRecorder: MediaRecorder? = null
    private var recordFilePath: String? = null
    private var opacity = 0f

    // The first rear facing camera
    private var defaultCameraId = 0

    public var defaultCamera: String? = null

    public var tapToTakePicture: Boolean = false

    public var dragEnabled: Boolean = false

    public var tapToFocus: Boolean = false

    public var disableExifHeaderStripping: Boolean = false

    public var storeToFile: Boolean = false

    public var toBack: Boolean = false

    public var enableOpacity: Boolean = false

    public var enableZoom: Boolean = false

    public var width: Int = 0

    public var height: Int = 0

    public var x: Int = 0

    public var y: Int = 0

    private var appResourcesPackage: String? = null

    private var mDist = 0f

    public fun setEventListener(listener: CameraPreviewListener?) {
        eventListener = listener
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        appResourcesPackage = activity.packageName

        // Inflate the layout for this fragment
        rootView = inflater.inflate(resources.getIdentifier("camera_activity", "layout", appResourcesPackage), container, false)
        createCameraPreview()
        return rootView
    }

    public fun setRect(x: Int, y: Int, width: Int?, height: Int?) {
        this.x = x
        this.y = y
        this.width = width ?: ViewGroup.LayoutParams.MATCH_PARENT
        this.height = height ?: ViewGroup.LayoutParams.MATCH_PARENT
    }

    private fun <T : View> findViewByName(name: String): T =
        rootView!!.findViewById(resources.getIdentifier(name, "id", appResourcesPackage))

    private fun createCameraPreview() {
        if (mPreview == null) {
            setDefaultCameraId()

            // set box position and size
            val layoutParams = FrameLayout.LayoutParams(width, height)
            layoutParams.setMargins(x, y, 0, 0)
            val container = findViewByName<FrameLayout>("frame_container")
            frameContainerLayout = container
            container.layoutParams = layoutParams

            // video view
            val preview = Preview(activity, enableOpacity)
            mPreview = preview
            val main = findViewByName<FrameLayout>("video_view")
            mainLayout = main
            main.layoutParams =
                RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT)
            main.addView(preview)
            main.isEnabled = false

            if (enableZoom) {
                setupTouchAndBackButton(container)
            }
        }
    }

    private fun setupTouchAndBackButton(container: FrameLayout) {
        val gestureDetector = GestureDetector(activity.applicationContext, TapGestureDetector())

        activity.runOnUiThread {
            container.isClickable = true
            container.setOnTouchListener(
                object : View.OnTouchListener {
                    private var mLastTouchX = 0
                    private var mLastTouchY = 0
                    private var mPosX = 0
                    private var mPosY = 0

                    override fun onTouch(v: View, event: MotionEvent): Boolean {
                        val layoutParams = container.layoutParams as FrameLayout.LayoutParams

                        val isSingleTapTouch = gestureDetector.onTouchEvent(event)
                        val action = event.action
                        val eventCount = event.pointerCount
                        Log.d(TAG, "onTouch event, action, count: $event, $action, $eventCount")
                        if (eventCount > 1) {
                            // handle multi-touch events
                            // Throws while the camera is released, as it always did.
                            val params = camera!!.parameters
                            if (action == MotionEvent.ACTION_POINTER_DOWN) {
                                mDist = getFingerSpacing(event)
                            } else if (action == MotionEvent.ACTION_MOVE && params.isZoomSupported) {
                                handleZoom(event, params)
                            }
                        } else {
                            if (action != MotionEvent.ACTION_MOVE && isSingleTapTouch) {
                                if (tapToTakePicture && tapToFocus) {
                                    setFocusArea(event.getX(0).toInt(), event.getY(0).toInt()) { success, _ ->
                                        if (success) {
                                            takePicture(0, 0, 85)
                                        } else {
                                            Log.d(TAG, "onTouch:" + " setFocusArea() did not suceed")
                                        }
                                    }
                                } else if (tapToTakePicture) {
                                    takePicture(0, 0, 85)
                                } else if (tapToFocus) {
                                    setFocusArea(event.getX(0).toInt(), event.getY(0).toInt()) { success, _ ->
                                        if (success) {
                                            // A callback to JS might make sense here.
                                        } else {
                                            Log.d(TAG, "onTouch:" + " setFocusArea() did not suceed")
                                        }
                                    }
                                }
                                return true
                            } else {
                                if (dragEnabled) {
                                    when (event.action) {
                                        MotionEvent.ACTION_DOWN ->
                                            if (mLastTouchX == 0 || mLastTouchY == 0) {
                                                mLastTouchX = event.rawX.toInt() - layoutParams.leftMargin
                                                mLastTouchY = event.rawY.toInt() - layoutParams.topMargin
                                            } else {
                                                mLastTouchX = event.rawX.toInt()
                                                mLastTouchY = event.rawY.toInt()
                                            }

                                        MotionEvent.ACTION_MOVE -> {
                                            val x = event.rawX.toInt()
                                            val y = event.rawY.toInt()

                                            val dx = (x - mLastTouchX).toFloat()
                                            val dy = (y - mLastTouchY).toFloat()

                                            mPosX = (mPosX + dx).toInt()
                                            mPosY = (mPosY + dy).toInt()

                                            layoutParams.leftMargin = mPosX
                                            layoutParams.topMargin = mPosY

                                            container.layoutParams = layoutParams

                                            // Remember this touch position for the next move event
                                            mLastTouchX = x
                                            mLastTouchY = y
                                        }

                                        else -> {}
                                    }
                                }
                            }
                        }
                        return true
                    }
                }
            )
            container.isFocusableInTouchMode = true
            container.requestFocus()
            container.setOnKeyListener { _, keyCode, _ ->
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    eventListener?.onBackButton()
                    true
                } else {
                    false
                }
            }
        }
    }

    private fun handleZoom(event: MotionEvent, params: Camera.Parameters) {
        val camera = camera ?: return
        camera.cancelAutoFocus()
        val maxZoom = params.maxZoom
        var zoom = params.zoom
        val newDist = getFingerSpacing(event)
        if (newDist > mDist) {
            // zoom in
            if (zoom < maxZoom) zoom++
        } else if (newDist < mDist) {
            // zoom out
            if (zoom > 0) zoom--
        }
        mDist = newDist
        params.zoom = zoom
        camera.parameters = params
    }

    private fun setDefaultCameraId() {
        // Find the total number of cameras available
        numberOfCameras = Camera.getNumberOfCameras()

        val facing = if ("front" == defaultCamera) Camera.CameraInfo.CAMERA_FACING_FRONT else Camera.CameraInfo.CAMERA_FACING_BACK

        // Find the ID of the default camera
        val cameraInfo = Camera.CameraInfo()
        for (i in 0 until numberOfCameras) {
            Camera.getCameraInfo(i, cameraInfo)
            if (cameraInfo.facing == facing) {
                defaultCameraId = i
                break
            }
        }
    }

    override fun onResume() {
        super.onResume()

        val camera = Camera.open(defaultCameraId)
        this.camera = camera

        if (cameraParameters != null) {
            camera.parameters = cameraParameters
        }

        cameraCurrentlyLocked = defaultCameraId

        // onCreateView always runs first and creates the preview.
        val preview = mPreview!!
        if (preview.mPreviewSize == null) {
            preview.setCamera(camera, cameraCurrentlyLocked)
            eventListener?.onCameraStarted()
        } else {
            preview.switchCamera(camera, cameraCurrentlyLocked)
            camera.startPreview()
        }

        Log.d(TAG, "cameraCurrentlyLocked:$cameraCurrentlyLocked")

        val container = findViewByName<FrameLayout>("frame_container")

        val viewTreeObserver = container.viewTreeObserver

        if (viewTreeObserver.isAlive) {
            viewTreeObserver.addOnGlobalLayoutListener(
                object : ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        container.viewTreeObserver.removeOnGlobalLayoutListener(this)
                        container.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
                        val currentActivity: Activity? = activity
                        if (isAdded && currentActivity != null) {
                            val frameCamContainerLayout = findViewByName<RelativeLayout>("frame_camera_cont")

                            val camViewLayout = FrameLayout.LayoutParams(container.width, container.height)
                            camViewLayout.gravity = Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL
                            frameCamContainerLayout.layoutParams = camViewLayout
                        }
                    }
                }
            )
        }
    }

    override fun onPause() {
        super.onPause()

        // Because the Camera object is a shared resource, it's very important to release it when the activity is paused.
        val camera = camera
        if (camera != null) {
            setDefaultCameraId()
            mPreview?.setCamera(null, -1)
            camera.setPreviewCallback(null)
            camera.release()
            this.camera = null
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        val container = findViewByName<FrameLayout>("frame_container")

        val previousOrientation =
            if (container.height > container.width) Configuration.ORIENTATION_PORTRAIT else Configuration.ORIENTATION_LANDSCAPE
        // Checks if the orientation of the screen has changed
        if (newConfig.orientation != previousOrientation) {
            val frameCamContainerLayout = findViewByName<RelativeLayout>("frame_camera_cont")

            container.layoutParams.width = frameCamContainerLayout.height
            container.layoutParams.height = frameCamContainerLayout.width

            frameCamContainerLayout.layoutParams.width = frameCamContainerLayout.height
            frameCamContainerLayout.layoutParams.height = frameCamContainerLayout.width

            container.invalidate()
            container.requestLayout()

            frameCamContainerLayout.forceLayout()

            mPreview!!.setCameraDisplayOrientation()
        }
    }

    /**
     * Method to get the front camera id if the current camera is back and visa versa
     *
     * @return front or back camera id depending on the currently active camera
     */
    private fun getNextCameraId(): Int {
        var nextCameraId = 0

        // Find the total number of cameras available
        // NOTE: The getNumberOfCameras() method in Android's android.hardware.camera API returns the total
        // number of cameras available on the device. The number might not be limited to just the front
        // and back cameras because modern smartphones often come with more than two cameras.
        // For example, devices might have:
        // - a main (back) camera.
        // - a wide-angle camera.
        // - a telephoto camera.
        // - a depth-sensing camera.
        // - an ultrawide camera.
        // - a macro camera.
        // etc.
        numberOfCameras = Camera.getNumberOfCameras()

        val nextFacing =
            if (cameraCurrentlyLocked == Camera.CameraInfo.CAMERA_FACING_BACK) {
                Camera.CameraInfo.CAMERA_FACING_FRONT
            } else {
                Camera.CameraInfo.CAMERA_FACING_BACK
            }

        // Find the next ID of the camera to switch to (front if the current is back and visa versa)
        val cameraInfo = Camera.CameraInfo()
        for (i in 0 until numberOfCameras) {
            Camera.getCameraInfo(i, cameraInfo)
            if (cameraInfo.facing == nextFacing) {
                nextCameraId = i
                break
            }
        }
        return nextCameraId
    }

    public fun switchCamera() {
        // check for availability of multiple cameras
        if (numberOfCameras == 1) {
            // There is only one camera available
        } else {
            Log.d(TAG, "numberOfCameras: $numberOfCameras")

            // OK, we have multiple cameras. Release this camera -> cameraCurrentlyLocked
            camera?.let {
                it.stopPreview()
                mPreview!!.setCamera(null, -1)
                it.release()
                camera = null
            }

            Log.d(TAG, "cameraCurrentlyLocked := $cameraCurrentlyLocked")
            try {
                cameraCurrentlyLocked = getNextCameraId()
                Log.d(TAG, "cameraCurrentlyLocked new: $cameraCurrentlyLocked")
            } catch (exception: Exception) {
                Log.d(TAG, exception.message ?: "null")
            }

            // Acquire the next camera and request Preview to reconfigure parameters.
            val newCamera = Camera.open(cameraCurrentlyLocked)
            camera = newCamera

            val previousParameters = cameraParameters
            if (previousParameters != null) {
                Log.d(TAG, "camera parameter not null")

                // Check for flashMode as well to prevent error on frontward facing camera.
                val supportedFlashModesNewCamera = newCamera.parameters.supportedFlashModes
                val currentFlashModePreviousCamera = previousParameters.flashMode
                if (supportedFlashModesNewCamera != null && supportedFlashModesNewCamera.contains(currentFlashModePreviousCamera)) {
                    Log.d(TAG, "current flash mode supported on new camera. setting params")
                    // The parameters are not applied to the new camera: what can be changed differs from one device to
                    // another, and those settings can be changed through the plugin methods.
                } else {
                    Log.d(TAG, "current flash mode NOT supported on new camera")
                }
            } else {
                Log.d(TAG, "camera parameter NULL")
            }

            // Without a view yet this throws, and the plugin reports it as "failed to flip camera".
            mPreview!!.switchCamera(newCamera, cameraCurrentlyLocked)

            newCamera.startPreview()
        }
    }

    public fun setCameraParameters(params: Camera.Parameters?) {
        cameraParameters = params

        if (camera != null && params != null) {
            camera?.parameters = params
        }
    }

    public fun hasFrontCamera(): Boolean = activity.applicationContext.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT)

    private val shutterCallback =
        ShutterCallback {
            // do nothing, availabilty of this callback causes default system shutter sound to work
        }

    private fun getTempDirectoryPath(): String {
        // Use internal storage
        val cache = activity.cacheDir

        // Create the cache directory if it doesn't exist
        cache.mkdirs()
        return cache.absolutePath
    }

    private fun getTempFilePath(): String =
        getTempDirectoryPath() + "/cpcp_capture_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8) + ".jpg"

    private val jpegPictureCallback =
        PictureCallback { bytes, _ ->
            Log.d(TAG, "CameraPreview jpegPictureCallback")

            var data = bytes
            try {
                if (!disableExifHeaderStripping) {
                    val matrix = Matrix()
                    if (cameraCurrentlyLocked == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                        matrix.preScale(1.0f, -1.0f)
                    }

                    val exifInterface = ExifInterface(ByteArrayInputStream(data))
                    val rotation = exifInterface.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                    val rotationInDegrees = exifToDegrees(rotation)

                    if (rotation != 0) {
                        matrix.preRotate(rotationInDegrees.toFloat())
                    }

                    // Check if matrix has changed. In that case, apply matrix and override data
                    if (!matrix.isIdentity) {
                        val bitmap = applyMatrix(BitmapFactory.decodeByteArray(data, 0, data.size), matrix)

                        val outputStream = ByteArrayOutputStream()
                        bitmap.compress(CompressFormat.JPEG, currentQuality, outputStream)
                        data = outputStream.toByteArray()
                    }
                }

                if (!storeToFile) {
                    val encodedImage = Base64.encodeToString(data, Base64.NO_WRAP)

                    eventListener?.onPictureTaken(encodedImage)
                } else {
                    val path = getTempFilePath()
                    val out = FileOutputStream(path)
                    out.write(data)
                    out.close()
                    eventListener?.onPictureTaken(path)
                }
                Log.d(TAG, "CameraPreview pictureTakenHandler called back")
            } catch (e: OutOfMemoryError) {
                // most likely failed to allocate memory for rotateBitmap
                Log.d(TAG, "CameraPreview OutOfMemoryError")
                // failed to allocate memory
                eventListener?.onPictureTakenError("Picture too large (memory)")
            } catch (e: IOException) {
                Log.d(TAG, "CameraPreview IOException")
                eventListener?.onPictureTakenError("IO Error when extracting exif")
            } catch (e: Exception) {
                Log.d(TAG, "CameraPreview onPictureTaken general exception")
            } finally {
                canTakePicture = true
                camera?.startPreview()
            }
        }

    /**
     * Get the supported picture size that:
     * - matches exactly width and height
     * - has the closest aspect ratio to the preview aspect ratio
     * - has picture.width and picture.height closest to width and height
     * - has the highest supported picture width and height up to 2 Megapixel if width == 0 || height == 0
     */
    private fun getOptimalPictureSize(
        camera: Camera,
        width: Int,
        height: Int,
        previewSize: Camera.Size,
        supportedSizes: List<Camera.Size>
    ): Camera.Size {
        val size = camera.Size(width, height)

        // convert to landscape if necessary
        if (size.width < size.height) {
            val temp = size.width
            size.width = size.height
            size.height = temp
        }

        val requestedSize = camera.Size(size.width, size.height)

        var previewAspectRatio = previewSize.width.toDouble() / previewSize.height.toDouble()

        if (previewAspectRatio < 1.0) {
            // reset ratio to landscape
            previewAspectRatio = 1.0 / previewAspectRatio
        }

        Log.d(TAG, "CameraPreview previewAspectRatio $previewAspectRatio")

        val aspectTolerance = 0.1
        var bestDifference = Double.MAX_VALUE

        for (supportedSize in supportedSizes) {
            // Perfect match
            if (supportedSize == requestedSize) {
                Log.d(TAG, "CameraPreview optimalPictureSize " + supportedSize.width + 'x' + supportedSize.height)
                return supportedSize
            }

            val difference = abs(previewAspectRatio - (supportedSize.width.toDouble() / supportedSize.height.toDouble()))

            if (difference < bestDifference - aspectTolerance) {
                // better aspectRatio found
                if ((width != 0 && height != 0) || (supportedSize.width * supportedSize.height < 2048 * 1024)) {
                    size.width = supportedSize.width
                    size.height = supportedSize.height
                    bestDifference = difference
                }
            } else if (difference < bestDifference + aspectTolerance) {
                // same aspectRatio found (within tolerance)
                if (width == 0 || height == 0) {
                    // set highest supported resolution below 2 Megapixel
                    if ((size.width < supportedSize.width) && (supportedSize.width * supportedSize.height < 2048 * 1024)) {
                        size.width = supportedSize.width
                        size.height = supportedSize.height
                    }
                } else {
                    // check if this pictureSize closer to requested width and height
                    if (
                        abs(width * height - supportedSize.width * supportedSize.height) <
                        abs(width * height - size.width * size.height)
                    ) {
                        size.width = supportedSize.width
                        size.height = supportedSize.height
                    }
                }
            }
        }
        Log.d(TAG, "CameraPreview optimalPictureSize " + size.width + 'x' + size.height)
        return size
    }

    public fun setOpacity(opacity: Float) {
        Log.d(TAG, "set opacity:$opacity")
        this.opacity = opacity
        // The plugin only calls this while the camera runs, so the preview exists.
        mPreview!!.setOpacity(opacity)
    }

    public fun takeSnapshot(quality: Int) {
        // The plugin only calls this while the camera runs.
        camera!!.setPreviewCallback { frame, camera ->
            try {
                val preview = mPreview!!
                val parameters = camera.parameters
                val size = parameters.previewSize
                val orientation = preview.displayOrientation
                val bytes =
                    if (preview.cameraFacing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                        rotateNV21(frame, size.width, size.height, (360 - orientation) % 360)
                    } else {
                        rotateNV21(frame, size.width, size.height, orientation)
                    }
                // switch width/height when rotating 90/270 deg
                val rect =
                    if (orientation == 90 || orientation == 270) {
                        Rect(0, 0, size.height, size.width)
                    } else {
                        Rect(0, 0, size.width, size.height)
                    }
                val yuvImage = YuvImage(bytes, parameters.previewFormat, rect.width(), rect.height(), null)
                val byteArrayOutputStream = ByteArrayOutputStream()
                yuvImage.compressToJpeg(rect, quality, byteArrayOutputStream)
                val data = byteArrayOutputStream.toByteArray()
                byteArrayOutputStream.close()
                eventListener?.onSnapshotTaken(Base64.encodeToString(data, Base64.NO_WRAP))
            } catch (e: IOException) {
                Log.d(TAG, "CameraPreview IOException")
                eventListener?.onSnapshotTakenError("IO Error")
            } finally {
                this.camera?.setPreviewCallback(null)
            }
        }
    }

    public fun takePicture(width: Int, height: Int, quality: Int) {
        Log.d(TAG, "CameraPreview takePicture width: $width, height: $height, quality: $quality")

        val preview = mPreview
        if (preview != null) {
            if (!canTakePicture) {
                return
            }

            canTakePicture = false

            Thread {
                // Throws while the camera is released, as it always did.
                val camera = camera!!
                val params = camera.parameters

                val size = getOptimalPictureSize(camera, width, height, params.previewSize, params.supportedPictureSizes)
                params.setPictureSize(size.width, size.height)
                currentQuality = quality

                if (cameraCurrentlyLocked == Camera.CameraInfo.CAMERA_FACING_FRONT && !storeToFile) {
                    // The image will be recompressed in the callback
                    params.jpegQuality = 99
                } else {
                    params.jpegQuality = quality
                }

                if (cameraCurrentlyLocked == Camera.CameraInfo.CAMERA_FACING_FRONT && disableExifHeaderStripping) {
                    val degrees =
                        when (activity.windowManager.defaultDisplay.rotation) {
                            Surface.ROTATION_0 -> 0
                            Surface.ROTATION_90 -> 180
                            Surface.ROTATION_180 -> 270
                            Surface.ROTATION_270 -> 0
                            else -> 0
                        }
                    // The info is never filled in, so this always takes the back-facing branch with orientation 0.
                    val info = Camera.CameraInfo()
                    var orientation: Int
                    if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                        orientation = (info.orientation + degrees) % 360
                        if (degrees != 0) {
                            orientation = (360 - orientation) % 360
                        }
                    } else {
                        orientation = (info.orientation - degrees + 360) % 360
                    }
                    params.setRotation(orientation)
                } else {
                    params.setRotation(preview.displayOrientation)
                }

                camera.parameters = params
                camera.takePicture(shutterCallback, null, jpegPictureCallback)
            }.start()
        } else {
            canTakePicture = true
        }
    }

    public fun startRecord(
        filePath: String?,
        camera: String?,
        width: Int,
        height: Int,
        quality: Int,
        withFlash: Boolean,
        maxDuration: Int
    ) {
        Log.d(TAG, "CameraPreview startRecord camera: $camera width: $width, height: $height, quality: $quality")
        muteStream(true, activity)
        if (mRecordingState == RecordingState.STARTED) {
            Log.d(TAG, "Already Recording")
            return
        }

        recordFilePath = filePath
        val orientationHint = calculateOrientationHint()

        // The plugin only calls this while the camera runs.
        val device = this.camera!!
        val cameraParams = device.parameters
        if (withFlash) {
            cameraParams.flashMode = Camera.Parameters.FLASH_MODE_TORCH
            device.parameters = cameraParams
            device.startPreview()
        }

        device.unlock()
        val recorder = MediaRecorder()
        mRecorder = recorder

        try {
            recorder.setCamera(device)

            val profile =
                when {
                    CamcorderProfile.hasProfile(defaultCameraId, CamcorderProfile.QUALITY_HIGH) ->
                        CamcorderProfile.get(defaultCameraId, CamcorderProfile.QUALITY_HIGH)

                    CamcorderProfile.hasProfile(defaultCameraId, CamcorderProfile.QUALITY_480P) ->
                        CamcorderProfile.get(defaultCameraId, CamcorderProfile.QUALITY_480P)

                    CamcorderProfile.hasProfile(defaultCameraId, CamcorderProfile.QUALITY_720P) ->
                        CamcorderProfile.get(defaultCameraId, CamcorderProfile.QUALITY_720P)

                    CamcorderProfile.hasProfile(defaultCameraId, CamcorderProfile.QUALITY_1080P) ->
                        CamcorderProfile.get(defaultCameraId, CamcorderProfile.QUALITY_1080P)

                    else -> CamcorderProfile.get(defaultCameraId, CamcorderProfile.QUALITY_LOW)
                }

            recorder.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            recorder.setVideoSource(MediaRecorder.VideoSource.CAMERA)
            recorder.setProfile(profile)
            recorder.setOutputFile(filePath)
            recorder.setOrientationHint(orientationHint)
            recorder.setMaxDuration(maxDuration)

            recorder.prepare()
            Log.d(TAG, "Starting recording")
            recorder.start()
            eventListener?.onStartRecordVideo()
        } catch (e: IOException) {
            eventListener?.onStartRecordVideoError(e.message)
        }
    }

    public fun calculateOrientationHint(): Int {
        val dm = DisplayMetrics()
        val info = Camera.CameraInfo()
        Camera.getCameraInfo(defaultCameraId, info)
        val cameraRotationOffset = info.orientation
        val display = activity.windowManager.defaultDisplay

        display.getMetrics(dm)

        val degrees =
            when (display.rotation) {
                Surface.ROTATION_0 -> 0
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }

        var orientation: Int
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            orientation = (cameraRotationOffset + degrees) % 360
            if (degrees != 0) {
                orientation = (360 - orientation) % 360
            }
        } else {
            orientation = (cameraRotationOffset - degrees + 360) % 360
        }
        Log.w(TAG, "************orientationHint ***********= $orientation")

        return orientation
    }

    public fun stopRecord() {
        Log.d(TAG, "stopRecord")

        try {
            // Without a recording this throws, and the plugin rejects the call with the exception's message.
            val recorder = mRecorder!!
            recorder.stop()
            recorder.reset() // clear recorder configuration
            recorder.release() // release the recorder object
            mRecorder = null
            val camera = camera!!
            camera.lock()
            val cameraParams = camera.parameters
            cameraParams.flashMode = Camera.Parameters.FLASH_MODE_OFF
            camera.parameters = cameraParams
            camera.startPreview()
            eventListener?.onStopRecordVideo(recordFilePath)
        } catch (e: Exception) {
            eventListener?.onStopRecordVideoError(e.message)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    public fun muteStream(mute: Boolean, activity: Activity) {
        // Looks the audio service up and changes nothing, as it always did.
        activity.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    public fun setFocusArea(pointX: Int, pointY: Int, callback: Camera.AutoFocusCallback) {
        val camera = camera ?: return
        camera.cancelAutoFocus()

        val parameters = camera.parameters

        val focusRect = calculateTapArea(pointX.toFloat(), pointY.toFloat(), 1f)
        parameters.focusMode = Camera.Parameters.FOCUS_MODE_AUTO
        parameters.focusAreas = listOf(Camera.Area(focusRect, 1000))

        if (parameters.maxNumMeteringAreas > 0) {
            val meteringRect = calculateTapArea(pointX.toFloat(), pointY.toFloat(), 1.5f)
            parameters.meteringAreas = listOf(Camera.Area(meteringRect, 1000))
        }

        try {
            setCameraParameters(parameters)
            camera.autoFocus(callback)
        } catch (e: Exception) {
            Log.d(TAG, e.message ?: "null")
            callback.onAutoFocus(false, this.camera)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun calculateTapArea(pointX: Float, pointY: Float, coefficient: Float): Rect {
        var x = pointX
        var y = pointY
        if (x < 100) {
            x = 100f
        }
        if (x > width - 100) {
            x = (width - 100).toFloat()
        }
        if (y < 100) {
            y = 100f
        }
        if (y > height - 100) {
            y = (height - 100).toFloat()
        }
        return Rect(
            Math.round(((x - 100) * 2000) / width - 1000),
            Math.round(((y - 100) * 2000) / height - 1000),
            Math.round(((x + 100) * 2000) / width - 1000),
            Math.round(((y + 100) * 2000) / height - 1000)
        )
    }

    public companion object {
        private const val TAG = "CameraActivity"

        @JvmStatic
        public fun applyMatrix(source: Bitmap, matrix: Matrix?): Bitmap =
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)

        private fun exifToDegrees(exifOrientation: Int): Int = when (exifOrientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }

        internal fun rotateNV21(yuv: ByteArray, width: Int, height: Int, rotation: Int): ByteArray {
            if (rotation == 0) return yuv
            require(rotation % 90 == 0 && rotation >= 0 && rotation <= 270) { "0 <= rotation < 360, rotation % 90 == 0" }

            val output = ByteArray(yuv.size)
            val frameSize = width * height
            val swap = rotation % 180 != 0
            val xflip = rotation % 270 != 0
            val yflip = rotation >= 180

            for (j in 0 until height) {
                for (i in 0 until width) {
                    val yIn = j * width + i
                    val uIn = frameSize + (j shr 1) * width + (i and 1.inv())
                    val vIn = uIn + 1

                    val wOut = if (swap) height else width
                    val hOut = if (swap) width else height
                    val iSwapped = if (swap) j else i
                    val jSwapped = if (swap) i else j
                    val iOut = if (xflip) wOut - iSwapped - 1 else iSwapped
                    val jOut = if (yflip) hOut - jSwapped - 1 else jSwapped

                    val yOut = jOut * wOut + iOut
                    val uOut = frameSize + (jOut shr 1) * wOut + (iOut and 1.inv())
                    val vOut = uOut + 1

                    output[yOut] = yuv[yIn]
                    output[uOut] = yuv[uIn]
                    output[vOut] = yuv[vIn]
                }
            }
            return output
        }

        /**
         * Determine the space between the first two fingers
         */
        private fun getFingerSpacing(event: MotionEvent): Float {
            val x = event.getX(0) - event.getX(1)
            val y = event.getY(0) - event.getY(1)
            return sqrt((x * x + y * y).toDouble()).toFloat()
        }
    }
}
