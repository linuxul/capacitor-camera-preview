package com.ahm.capacitor.camera.preview

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.TextureView

internal class CustomTextureView(context: Context) :
    TextureView(context),
    TextureView.SurfaceTextureListener {
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {}

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
}
