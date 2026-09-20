@file:Suppress("DEPRECATION")

package com.ahm.capacitor.camera.preview

import android.app.Activity
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.TextureView
import android.view.View
import android.widget.RelativeLayout
import java.io.IOException
import kotlin.math.abs

internal class Preview
@JvmOverloads
constructor(context: Context, private val enableOpacity: Boolean = false) :
    RelativeLayout(context),
    SurfaceHolder.Callback,
    TextureView.SurfaceTextureListener {
    private var mSurfaceView: CustomSurfaceView? = null
    private var mTextureView: CustomTextureView? = null
    private var mHolder: SurfaceHolder? = null
    private var mSurface: SurfaceTexture? = null
    var mPreviewSize: Camera.Size? = null
    private var mSupportedPreviewSizes: List<Camera.Size>? = null
    private var mCamera: Camera? = null
    private var cameraId = 0
    var displayOrientation: Int = 0
        private set
    var cameraFacing: Int = Camera.CameraInfo.CAMERA_FACING_BACK
        private set
    private var opacity = 1f

    init {
        if (!enableOpacity) {
            val surfaceView = CustomSurfaceView(context)
            mSurfaceView = surfaceView
            addView(surfaceView)
            requestLayout()

            // Install a SurfaceHolder.Callback so we get notified when the
            // underlying surface is created and destroyed.
            mHolder =
                surfaceView.holder.also {
                    it.addCallback(this)
                    it.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS)
                }
        } else {
            // Use a TextureView so we can manage opacity
            val textureView = CustomTextureView(context)
            mTextureView = textureView
            // Install a SurfaceTextureListener so we get notified
            textureView.surfaceTextureListener = this
            textureView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            addView(textureView)
            requestLayout()
        }
    }

    fun setCamera(camera: Camera?, cameraId: Int) {
        if (camera != null) {
            mCamera = camera
            this.cameraId = cameraId
            mSupportedPreviewSizes = camera.parameters.supportedPreviewSizes
            setCameraDisplayOrientation()

            val focusModes = camera.parameters.supportedFocusModes

            val params = camera.parameters
            if (focusModes.contains("continuous-picture")) {
                params.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
            } else if (focusModes.contains("continuous-video")) {
                params.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO
            } else if (focusModes.contains("auto")) {
                params.focusMode = Camera.Parameters.FOCUS_MODE_AUTO
            }
            camera.parameters = params
        }
    }

    fun printPreviewSize(from: String) {
        // Throws without a preview size, as it always did.
        val size = mPreviewSize!!
        Log.d(TAG, "printPreviewSize from " + from + ": > width: " + size.width + " height: " + size.height)
    }

    fun setCameraPreviewSize() {
        val camera = mCamera ?: return
        // Throws without a preview size, as it always did.
        val size = mPreviewSize!!
        val parameters = camera.parameters
        parameters.setPreviewSize(size.width, size.height)
        camera.parameters = parameters
    }

    fun setCameraDisplayOrientation() {
        val info = Camera.CameraInfo()
        val display = (context as Activity).windowManager.defaultDisplay
        val rotation = display.rotation
        val dm = DisplayMetrics()

        Camera.getCameraInfo(cameraId, info)
        display.getMetrics(dm)

        val degrees =
            when (rotation) {
                Surface.ROTATION_0 -> 0
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
        cameraFacing = info.facing
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            displayOrientation = (info.orientation + degrees) % 360
            displayOrientation = (360 - displayOrientation) % 360
        } else {
            displayOrientation = (info.orientation - degrees + 360) % 360
        }

        Log.d(TAG, "screen is rotated " + degrees + "deg from natural")
        Log.d(
            TAG,
            (if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) "front" else "back") +
                " camera is oriented -" +
                info.orientation +
                "deg from natural"
        )
        Log.d(TAG, "need to rotate preview " + displayOrientation + "deg")
        // Throws when the camera was released (a configuration change while paused), as it always did.
        mCamera!!.setDisplayOrientation(displayOrientation)
    }

    fun switchCamera(camera: Camera, cameraId: Int) {
        try {
            setCamera(camera, cameraId)

            Log.d("CameraPreview", "before set camera")

            // The view for the active mode is created in init, so it is never null here.
            val v: View
            if (enableOpacity) {
                camera.setPreviewTexture(mSurface)
                v = mTextureView!!
            } else {
                camera.setPreviewDisplay(mHolder)
                v = mSurfaceView!!
            }

            Log.d("CameraPreview", "before getParameters")

            val parameters = camera.parameters

            Log.d("CameraPreview", "before setPreviewSize")

            mSupportedPreviewSizes = parameters.supportedPreviewSizes
            // Throws when the camera reports no preview sizes, as it always did.
            val size = getOptimalPreviewSize(mSupportedPreviewSizes, v.width, v.height)!!
            mPreviewSize = size
            parameters.setPreviewSize(size.width, size.height)
            Log.d(TAG, size.width.toString() + " " + size.height)

            camera.parameters = parameters
        } catch (exception: IOException) {
            Log.e(TAG, exception.message ?: "null")
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // We purposely disregard child measurements because act as a
        // wrapper to a SurfaceView that centers the camera preview instead
        // of stretching it.
        val width = resolveSize(suggestedMinimumWidth, widthMeasureSpec)
        val height = resolveSize(suggestedMinimumHeight, heightMeasureSpec)
        setMeasuredDimension(width, height)

        if (mSupportedPreviewSizes != null) {
            mPreviewSize = getOptimalPreviewSize(mSupportedPreviewSizes, width, height)
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        if (changed && childCount > 0) {
            val child = getChildAt(0)

            val width = r - l
            val height = b - t

            var previewWidth = width
            var previewHeight = height

            val previewSize = mPreviewSize
            if (previewSize != null) {
                previewWidth = previewSize.width
                previewHeight = previewSize.height

                if (displayOrientation == 90 || displayOrientation == 270) {
                    previewWidth = previewSize.height
                    previewHeight = previewSize.width
                }
            }

            val nW: Int
            val nH: Int
            val top: Int
            val left: Int

            val scale = 1.0f

            // Center the child SurfaceView within the parent.
            if (width * previewHeight < height * previewWidth) {
                Log.d(TAG, "center horizontally")
                val scaledChildWidth = ((previewWidth * height / previewHeight) * scale).toInt()
                nW = (width + scaledChildWidth) / 2
                nH = (height * scale).toInt()
                top = 0
                left = (width - scaledChildWidth) / 2
            } else {
                Log.d(TAG, "center vertically")
                val scaledChildHeight = ((previewHeight * width / previewWidth) * scale).toInt()
                nW = (width * scale).toInt()
                nH = (height + scaledChildHeight) / 2
                top = (height - scaledChildHeight) / 2
                left = 0
            }
            child.layout(left, top, nW, nH)

            Log.d("layout", "left:$left")
            Log.d("layout", "top:$top")
            Log.d("layout", "right:$nW")
            Log.d("layout", "bottom:$nH")
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        // The Surface has been created, acquire the camera and tell it where
        // to draw.
        try {
            val camera = mCamera
            if (camera != null) {
                mSurfaceView?.setWillNotDraw(false)
                camera.setPreviewDisplay(holder)
            }
        } catch (exception: Exception) {
            Log.e(TAG, "Exception caused by setPreviewDisplay()", exception)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // Surface will be destroyed when we return, so stop the preview.
        try {
            mCamera?.stopPreview()
        } catch (exception: Exception) {
            Log.e(TAG, "Exception caused by surfaceDestroyed()", exception)
        }
    }

    private fun getOptimalPreviewSize(sizes: List<Camera.Size>?, w: Int, h: Int): Camera.Size? {
        var targetRatio = w.toDouble() / h
        if (displayOrientation == 90 || displayOrientation == 270) {
            targetRatio = h.toDouble() / w
        }

        if (sizes == null) {
            return null
        }

        var optimalSize: Camera.Size? = null
        var minDiff = Double.MAX_VALUE

        val targetHeight = h

        // Try to find an size match aspect ratio and size
        for (size in sizes) {
            val ratio = size.width.toDouble() / size.height
            if (abs(ratio - targetRatio) > ASPECT_TOLERANCE) continue
            if (abs(size.height - targetHeight) < minDiff) {
                optimalSize = size
                minDiff = abs(size.height - targetHeight).toDouble()
            }
        }

        // Cannot find the one match the aspect ratio, ignore the requirement
        if (optimalSize == null) {
            minDiff = Double.MAX_VALUE
            for (size in sizes) {
                if (abs(size.height - targetHeight) < minDiff) {
                    optimalSize = size
                    minDiff = abs(size.height - targetHeight).toDouble()
                }
            }
        }

        // Throws for an empty list, as it always did.
        val result = optimalSize!!
        Log.d(TAG, "optimal preview size: w: " + result.width + " h: " + result.height)
        return result
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
        val camera = mCamera
        if (camera != null) {
            try {
                // Now that the size is known, set up the camera parameters and begin
                // the preview.
                mSupportedPreviewSizes = camera.parameters.supportedPreviewSizes
                if (mSupportedPreviewSizes != null) {
                    mPreviewSize = getOptimalPreviewSize(mSupportedPreviewSizes, w, h)
                }
                startCamera(camera)
            } catch (exception: Exception) {
                Log.e(TAG, "Exception caused by surfaceChanged()", exception)
            }
        }
    }

    private fun startCamera(camera: Camera) {
        val parameters = camera.parameters
        // Both callers catch the exception thrown when there is no preview size yet.
        val size = mPreviewSize!!
        parameters.setPreviewSize(size.width, size.height)
        requestLayout()
        camera.parameters = parameters
        camera.startPreview()
    }

    //  Texture Callbacks

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        // The Surface has been created, acquire the camera and tell it where
        // to draw.
        try {
            mSurface = surface
            if (mSupportedPreviewSizes != null) {
                mPreviewSize = getOptimalPreviewSize(mSupportedPreviewSizes, width, height)
            }
            val camera = mCamera
            if (camera != null) {
                mTextureView?.alpha = opacity
                camera.setPreviewTexture(surface)
                startCamera(camera)
            }
        } catch (exception: Exception) {
            Log.e(TAG, "Exception caused by onSurfaceTextureAvailable()", exception)
        }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        try {
            mCamera?.stopPreview()
        } catch (exception: Exception) {
            Log.e(TAG, "Exception caused by onSurfaceTextureDestroyed()", exception)
            return false
        }
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    fun setOneShotPreviewCallback(callback: Camera.PreviewCallback?) {
        mCamera?.setOneShotPreviewCallback(callback)
    }

    fun setOpacity(opacity: Float) {
        this.opacity = opacity
        if (mCamera != null && enableOpacity) {
            mTextureView?.alpha = opacity
        }
    }

    private companion object {
        private const val TAG = "Preview"
        private const val ASPECT_TOLERANCE = 0.1
    }
}
