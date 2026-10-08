package com.androidcast.controller

import android.content.Context
import android.widget.FrameLayout

/** A FrameLayout that is always 16:9, like the TV screen. */
class AspectFrameLayout(context: Context) : FrameLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(width * 9 / 16, MeasureSpec.EXACTLY))
    }
}
