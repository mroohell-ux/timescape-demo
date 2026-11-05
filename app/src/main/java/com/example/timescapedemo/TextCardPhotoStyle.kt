package com.example.timescapedemo

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.ColorDrawable
import android.widget.ImageView

internal object TextCardPhotoStyle {
    private const val PHOTO_OPACITY = .72f
    private val colorFilter = ColorMatrixColorFilter(ColorMatrix().apply {
        setSaturation(.90f)
        postConcat(ColorMatrix(floatArrayOf(
            .96f, 0f, 0f, 0f, 5.12f,
            0f, .96f, 0f, 0f, 5.12f,
            0f, 0f, .96f, 0f, 5.12f,
            0f, 0f, 0f, PHOTO_OPACITY, 0f
        )))
    })

    fun applyTo(image: ImageView) {
        if (image is PhotographImageView) {
            image.setFadingEdges(true)
            val density = image.resources.displayMetrics.density
            image.setSoftening(3.5f * density, centerRadiusPx = 2f * density)
        }
        image.setRenderEffect(null)
        image.scaleType = ImageView.ScaleType.CENTER_CROP
        image.colorFilter = colorFilter
        // Only photo alpha changes; the glass underneath, lettering and icons keep their own alpha.
        image.background = ColorDrawable(0x283B8EBD)
        image.foreground = null
    }
}
