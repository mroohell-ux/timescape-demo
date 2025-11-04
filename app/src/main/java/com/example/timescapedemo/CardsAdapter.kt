package com.example.timescapedemo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.max
import kotlin.math.min

data class CardItem(
    val title: String,
    val snippet: String,
    val time: String,
    val bg: BgImage? = null
)

/** Non-“glass” tint options that keep the card transparent. */
sealed class TintStyle {
    data object None : TintStyle()
    data class MultiplyDark(@ColorInt val color: Int, val alpha: Float = 0.18f) : TintStyle()
    data class ScreenLight(@ColorInt val color: Int, val alpha: Float = 0.18f) : TintStyle()
    data class Colorize(@ColorInt val color: Int, val amount: Float = 0.35f) : TintStyle()
    data class Sepia(val amount: Float = 1f) : TintStyle()
    data class Duotone(@ColorInt val dark: Int, @ColorInt val light: Int, val amount: Float = 1f) : TintStyle()
}

class CardsAdapter(
    private val items: List<CardItem>,
    private val tint: TintStyle,
    private val onItemClick: (index: Int) -> Unit
) : RecyclerView.Adapter<CardsAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val time: TextView = v.findViewById(R.id.time)
        val title: TextView = v.findViewById(R.id.title)
        val snippet: TextView = v.findViewById(R.id.snippet)
        val bg: ImageView = v.findViewById(R.id.bgImage)
        val textScrim: View = v.findViewById(R.id.textScrim)
        val liquidTint: View = v.findViewById(R.id.liquidTint)
        val centerGlow: View = v.findViewById(R.id.centerGlow)
        val highlight: View = v.findViewById(R.id.highlight)
    }

    // Cache luminance by key (drawable id or uri string)
    private val luminanceCache = mutableMapOf<String, Float>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_card, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]

        // ---- Bind text ----
        holder.time.text = item.time
        holder.title.text = item.title
        holder.snippet.text = item.snippet

        // ---- Bind background image (drawable or Uri) ----
        when (val b = item.bg) {
            is BgImage.Res    -> holder.bg.setImageResource(b.id)
            is BgImage.UriRef -> holder.bg.setImageURI(b.uri)
            null              -> holder.bg.setImageDrawable(null)
        }
        holder.bg.alpha = 0.96f

        // Base blur (keeps transparency)
        var baseEffect: RenderEffect? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            baseEffect = RenderEffect.createBlurEffect(26f, 26f, Shader.TileMode.CLAMP)
        }

        // Apply NON-GLASS tint directly to image
        applyTintToImage(holder.bg, tint, baseEffect)

        // ---- Adaptive readability (local scrim + text color swap) ----
        val key = when (val b = item.bg) {
            is BgImage.Res    -> "res:${b.id}"
            is BgImage.UriRef -> "uri:${b.uri}"
            null              -> "none"
        }
        val lum = luminanceCache.getOrPut(key) {
            when (val bg = item.bg) {
                is BgImage.Res    -> computeAvgLuminanceFromRes(holder.itemView, bg.id)
                is BgImage.UriRef -> computeAvgLuminanceFromUri(holder.itemView, bg.uri)
                else -> 0.5f
            }
        }
        val isBright = lum >= 0.55f

        holder.textScrim.alpha = if (isBright) 0.32f else 0.16f
        holder.liquidTint.alpha = if (isBright) 0.36f else 0.5f
        holder.centerGlow.alpha = if (isBright) 0.28f else 0.42f
        holder.highlight.alpha = 0.62f

        if (isBright) {
            holder.title.setTextColor(0xFF1E1E1E.toInt())
            holder.snippet.setTextColor(0xE0111111.toInt())
            holder.time.setTextColor(0xAA111111.toInt())
            clearShadow(holder.title, holder.snippet)
        } else {
            holder.title.setTextColor(0xFFFFFFFF.toInt())
            holder.snippet.setTextColor(0xF5FFFFFF.toInt())
            holder.time.setTextColor(0xD8FFFFFF.toInt())
            addShadow(holder.title, holder.snippet)
        }

        holder.itemView.setOnClickListener {
            val idx = holder.bindingAdapterPosition
            if (idx != RecyclerView.NO_POSITION) onItemClick(idx)
        }
    }

    override fun getItemCount(): Int = items.size

    // ---------- Tint implementations ----------
    private fun applyTintToImage(iv: ImageView, style: TintStyle, baseEffect: RenderEffect?) {
        when (style) {
            is TintStyle.None -> {
                iv.colorFilter = null
                if (baseEffect != null) iv.setRenderEffect(baseEffect)
            }
            is TintStyle.MultiplyDark -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    iv.colorFilter = BlendModeColorFilter(withAlpha(style.color, style.alpha), BlendMode.MULTIPLY)
                } else iv.colorFilter = ColorMatrixColorFilter(ColorMatrix())
                if (baseEffect != null) iv.setRenderEffect(baseEffect)
            }
            is TintStyle.ScreenLight -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    iv.colorFilter = BlendModeColorFilter(withAlpha(style.color, style.alpha), BlendMode.SCREEN)
                } else iv.colorFilter = ColorMatrixColorFilter(ColorMatrix())
                if (baseEffect != null) iv.setRenderEffect(baseEffect)
            }
            is TintStyle.Colorize -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    iv.colorFilter = BlendModeColorFilter(withAlpha(style.color, style.amount.coerceIn(0f,1f)), BlendMode.COLOR)
                } else iv.colorFilter = ColorMatrixColorFilter(colorizeMatrix(style.color, style.amount))
                if (baseEffect != null) iv.setRenderEffect(baseEffect)
            }
            is TintStyle.Sepia -> {
                iv.colorFilter = ColorMatrixColorFilter(sepiaMatrix(style.amount))
                if (baseEffect != null) iv.setRenderEffect(baseEffect)
            }
            is TintStyle.Duotone -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val shader = RuntimeShader(DUOTONE_SHADER)
                    setDuotoneUniforms(shader, style)
                    val duo = RenderEffect.createRuntimeShaderEffect(shader, "content")
                    val final = if (baseEffect != null) RenderEffect.createChainEffect(duo, baseEffect) else duo
                    iv.setRenderEffect(final)
                    iv.colorFilter = null
                } else {
                    applyTintToImage(iv, TintStyle.Colorize(style.light, style.amount * 0.6f), baseEffect)
                }
            }
        }
    }

    private fun withAlpha(@ColorInt color: Int, a: Float): Int {
        val aa = (a.coerceIn(0f, 1f) * 255).toInt()
        return (color and 0x00FFFFFF) or (aa shl 24)
    }

    // --- Text helpers ---
    private fun addShadow(vararg tv: TextView) { tv.forEach { it.setShadowLayer(4f, 0f, 1f, 0x66000000) } }
    private fun clearShadow(vararg tv: TextView) { tv.forEach { it.setShadowLayer(0f, 0f, 0f, 0) } }

    // --- Luminance helpers (fast, downsampled) ---
    private fun computeAvgLuminanceFromRes(root: View, resId: Int): Float {
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = 32
        }
        val bmp = BitmapFactory.decodeResource(root.context.resources, resId, opts) ?: return 0.5f
        val v = averageLum(bmp); bmp.recycle(); return v
    }
    private fun computeAvgLuminanceFromUri(root: View, uri: Uri): Float {
        val cr = root.context.contentResolver
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = 32
        }
        cr.openInputStream(uri)?.use { stream ->
            val bmp = BitmapFactory.decodeStream(stream, null, opts) ?: return 0.5f
            val v = averageLum(bmp); bmp.recycle(); return v
        }
        return 0.5f
    }
    private fun averageLum(bmp: Bitmap): Float {
        val w = bmp.width
        val h = bmp.height
        val stepX = max(1, w / 24)
        val stepY = max(1, h / 24)
        var sum = 0f; var n = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val c = bmp.getPixel(x, y)
                val r = Color.red(c) / 255f
                val g = Color.green(c) / 255f
                val b = Color.blue(c) / 255f
                sum += 0.2126f * r + 0.7152f * g + 0.0722f * b
                n++; x += stepX
            }
            y += stepY
        }
        val avg = if (n > 0) sum / n else 0.5f
        return min(1f, max(0f, avg))
    }

    // --- Color matrices ---
    private fun colorizeMatrix(@ColorInt color: Int, amount: Float): ColorMatrix {
        val t = amount.coerceIn(0f, 1f)
        val sat = 1f - t
        val cm = ColorMatrix().apply { setSaturation(sat) }
        val r = Color.red(color) / 255f
        val g = Color.green(color) / 255f
        val b = Color.blue(color) / 255f
        val bias = 0.12f * t
        val push = ColorMatrix(
            floatArrayOf(
                1f, 0f, 0f, 0f, 255f * bias * r,
                0f, 1f, 0f, 0f, 255f * bias * g,
                0f, 0f, 1f, 0f, 255f * bias * b,
                0f, 0f, 0f, 1f, 0f
            )
        )
        cm.postConcat(push)
        return cm
    }
    private fun sepiaMatrix(tIn: Float): ColorMatrix {
        val t = tIn.coerceIn(0f, 1f)
        val id = floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
        val sep = floatArrayOf(
            0.393f, 0.769f, 0.189f, 0f, 0f,
            0.349f, 0.686f, 0.168f, 0f, 0f,
            0.272f, 0.534f, 0.131f, 0f, 0f,
            0f,     0f,     0f,     1f, 0f
        )
        val out = FloatArray(20)
        for (i in 0 until 20) out[i] = id[i] + (sep[i] - id[i]) * t
        return ColorMatrix(out)
    }

    // --- Duotone shader (Android 13+) ---
    private fun setDuotoneUniforms(shader: RuntimeShader, style: TintStyle.Duotone) {
        val dark = style.dark
        val light = style.light
        shader.setFloatUniform("amount", style.amount.coerceIn(0f, 1f))
        shader.setFloatUniform("dark", Color.red(dark)/255f, Color.green(dark)/255f, Color.blue(dark)/255f, 1f)
        shader.setFloatUniform("light", Color.red(light)/255f, Color.green(light)/255f, Color.blue(light)/255f, 1f)
    }
    @Suppress("ConstPropertyName")
    private companion object {
        private const val DUOTONE_SHADER = """
            uniform shader content;
            uniform float amount;
            uniform vec4 dark;
            uniform vec4 light;
            half4 main(float2 p) {
                half4 c = content.eval(p);
                float l = max(max(c.r, c.g), c.b);
                vec3 tone = mix(dark.rgb, light.rgb, l);
                vec3 outRGB = mix(c.rgb, tone, amount);
                return half4(outRGB, c.a);
            }
        """
    }
}
