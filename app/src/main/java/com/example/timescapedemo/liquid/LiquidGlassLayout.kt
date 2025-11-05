package com.example.timescapedemo.liquid

import android.content.Context
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.example.timescapedemo.R
import kotlin.math.max

/**
 * Lightweight port of Kyant's AndroidLiquidGlass layout. The original effect lives at
 * https://github.com/Kyant0/AndroidLiquidGlass. We replicate the shader locally so the demo
 * can run without pulling the full dependency while still matching the liquid glass look.
 */
class LiquidGlassLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val surfaceView = View(context)
    private val highlightView = View(context)

    private var isAnimating = false

    private var shaderWrapper: HighlightShaderWrapper? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        HighlightShaderWrapper()
    } else null

    private val choreographer by lazy(LazyThreadSafetyMode.NONE) { Choreographer.getInstance() }
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            shaderWrapper?.onFrame(frameTimeNanos)
            if (isAnimating) {
                choreographer.postFrameCallback(this)
            }
        }
    }

    init {
        setWillNotDraw(true)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        clipChildren = false
        clipToPadding = false

        val surfaceDrawable = ContextCompat.getDrawable(context, R.drawable.liquid_glass_surface)
        surfaceView.apply {
            background = surfaceDrawable
            alpha = 0.88f
        }
        val highlightDrawable = ContextCompat.getDrawable(context, R.drawable.liquid_glass_highlight)
        highlightView.apply {
            background = highlightDrawable
            alpha = 0.82f
        }

        addView(surfaceView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(highlightView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val blur = RenderEffect.createBlurEffect(26f, 26f, Shader.TileMode.DECAL)
            surfaceView.setRenderEffect(blur)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            shaderWrapper?.attach(highlightView)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop()
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            post { shaderWrapper?.updateSize(width, height) }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            shaderWrapper?.updateSize(w, h)
        }
    }

    fun setSceneLuminance(value: Float) {
        val luminance = value.coerceIn(0f, 1f)
        val isBright = luminance >= 0.55f
        surfaceView.alpha = if (isBright) 0.72f else 0.9f
        highlightView.alpha = if (isBright) 0.68f else 0.88f
        shaderWrapper?.setSceneBrightness(if (isBright) 0.65f else 1.1f)
    }

    private fun start() {
        if (isAnimating) return
        isAnimating = true
        shaderWrapper?.resetTime()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            choreographer.postFrameCallback(frameCallback)
        }
    }

    private fun stop() {
        if (!isAnimating) return
        isAnimating = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            choreographer.removeFrameCallback(frameCallback)
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private inner class HighlightShaderWrapper {
        private val shader = RuntimeShader(GLASS_SHADER)
        private var effect: RenderEffect? = null
        private var attachedView: View? = null
        private var lastFrameNs: Long = 0L
        private var time: Float = 0f
        private var resolutionW = 0f
        private var resolutionH = 0f

        fun attach(target: View) {
            attachedView = target
            updateEffect()
        }

        fun updateSize(w: Int, h: Int) {
            resolutionW = max(1, w).toFloat()
            resolutionH = max(1, h).toFloat()
            shader.setFloatUniform("resolution", resolutionW, resolutionH)
            updateEffect()
        }

        fun setSceneBrightness(scale: Float) {
            shader.setFloatUniform("caustics", scale)
            updateEffect()
        }

        fun resetTime() {
            lastFrameNs = 0L
            time = 0f
        }

        fun onFrame(frameTimeNanos: Long) {
            if (lastFrameNs == 0L) {
                lastFrameNs = frameTimeNanos
                return
            }
            val dt = (frameTimeNanos - lastFrameNs) / 1_000_000_000f
            lastFrameNs = frameTimeNanos
            time += dt
            shader.setFloatUniform("time", time)
            shader.setFloatUniform("intensity", 0.9f)
            updateEffect()
            attachedView?.invalidate()
        }

        private fun updateEffect() {
            val target = attachedView ?: return
            if (resolutionW <= 0f || resolutionH <= 0f) return
            val base = RenderEffect.createRuntimeShaderEffect(shader, "content")
            val combined = RenderEffect.createChainEffect(base, RenderEffect.createBlurEffect(18f, 18f, Shader.TileMode.DECAL))
            effect = combined
            target.setRenderEffect(effect)
        }
    }

    companion object {
        private const val GLASS_SHADER = """
            uniform shader content;
            uniform float2 resolution;
            uniform float time;
            uniform float intensity;
            uniform float caustics;

            float hash(float2 p) {
                return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453);
            }

            float noise(float2 p) {
                float2 i = floor(p);
                float2 f = fract(p);
                float a = hash(i);
                float b = hash(i + float2(1.0, 0.0));
                float c = hash(i + float2(0.0, 1.0));
                float d = hash(i + float2(1.0, 1.0));
                float2 u = f * f * (3.0 - 2.0 * f);
                return mix(a, b, u.x) + (c - a) * u.y * (1.0 - u.x) + (d - b) * u.x * u.y;
            }

            float causticField(float2 uv, float t) {
                float n1 = noise(uv * 6.0 + t * 0.8);
                float n2 = noise(uv * float2(8.0, 4.0) - t * 1.1);
                float streaks = sin((uv.x + t * 0.4) * 40.0) * 0.5 + 0.5;
                return mix(n1, n2, 0.5) * 0.65 + streaks * 0.35;
            }

            half4 main(float2 fragCoord) {
                float2 uv = fragCoord / resolution;
                float wave1 = sin((uv.y + time * 0.18) * 18.0) * 0.012;
                float wave2 = cos((uv.x + time * 0.13) * 24.0) * 0.01;
                float2 offset = float2(wave1, wave2) * intensity;
                float2 sampleCoord = fragCoord + offset * resolution;
                half4 color = content.eval(sampleCoord);

                float highlight = smoothstep(0.32, 0.94, uv.y + wave1 * 2.1 + wave2 * 1.8);
                float caustic = causticField(uv + wave1, time) * caustics * 0.55;
                float sheen = smoothstep(0.5, 1.0, sin((uv.x + time * 0.3) * 22.0) * 0.5 + 0.5);

                float brighten = 1.05 + highlight * 0.25 + sheen * 0.12 + caustic * 0.35;
                color.rgb *= brighten;
                color.rgb = mix(color.rgb, float3(1.0, 1.0, 1.0), 0.04 + highlight * 0.08);
                color.a *= mix(0.5, 0.92, highlight);
                return color;
            }
        """
    }
}
