package com.example.timescapedemo

import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes

enum class SocialNetwork(
    @DrawableRes val iconRes: Int,
    @ColorRes val accentColorRes: Int
) {
    FACEBOOK(R.drawable.ic_network_facebook, R.color.network_facebook),
    INSTAGRAM(R.drawable.ic_network_instagram, R.color.network_instagram),
    LINKEDIN(R.drawable.ic_network_linkedin, R.color.network_linkedin),
    TWITTER(R.drawable.ic_network_twitter, R.color.network_twitter);

    companion object {
        fun fromRaw(raw: String?): SocialNetwork {
            if (raw.isNullOrBlank()) return FACEBOOK
            return values().firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: FACEBOOK
        }
    }
}
