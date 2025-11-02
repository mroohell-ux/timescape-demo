package com.example.timescapedemo

import android.net.Uri
import androidx.annotation.DrawableRes

sealed class BgImage {
    data class Res(@DrawableRes val id: Int) : BgImage()
    data class UriRef(val uri: Uri) : BgImage()
}
