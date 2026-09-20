package com.ahm.capacitor.camera.preview

import android.view.GestureDetector
import android.view.MotionEvent

internal class TapGestureDetector : GestureDetector.SimpleOnGestureListener() {
    override fun onDown(e: MotionEvent): Boolean = false

    override fun onSingleTapUp(e: MotionEvent): Boolean = true

    override fun onSingleTapConfirmed(e: MotionEvent): Boolean = true
}
