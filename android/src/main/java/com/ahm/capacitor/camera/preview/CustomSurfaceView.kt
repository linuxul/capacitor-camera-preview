package com.ahm.capacitor.camera.preview

import android.content.Context
import android.view.SurfaceHolder
import android.view.SurfaceView

internal class CustomSurfaceView(context: Context) :
    SurfaceView(context),
    SurfaceHolder.Callback {
    override fun surfaceCreated(holder: SurfaceHolder) {}

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {}
}
