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
import kotlin.math.abs
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
    private var lastSceneLuminance = -1f

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
            alpha = 0.84f
        }

        addView(surfaceView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(highlightView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val blur = RenderEffect.createBlurEffect(26f, 26f, Shader.TileMode.DECAL)
            surfaceView.setRenderEffect(blur)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            shaderWrapper?.attach(highlightView)
            shaderWrapper?.setEnvironment(0.5f, false)
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
        if (abs(luminance - lastSceneLuminance) < 0.01f) {
            return
        }
        lastSceneLuminance = luminance
        val isBright = luminance >= 0.58f
        val surfaceAlpha = if (isBright) 0.74f else 0.9f
        val highlightAlpha = if (isBright) 0.64f else 0.88f
        animateAlpha(surfaceView, surfaceAlpha)
        animateAlpha(highlightView, highlightAlpha)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            shaderWrapper?.setEnvironment(luminance, isBright)
        }
    }

    private fun animateAlpha(target: View, to: Float) {
        if (!target.isLaidOut) {
            target.alpha = to
            return
        }
        if (abs(target.alpha - to) < 0.01f) {
            return
        }
        target.animate().alpha(to).setDuration(220L).start()
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

        fun setEnvironment(luminance: Float, bright: Boolean) {
            shader.setFloatUniform("envLum", luminance)
            val brightness = if (bright) 0.78f else 1.1f
            val sparkle = if (bright) 0.56f else 0.86f
            val glint = if (bright) 0.48f else 0.8f
            shader.setFloatUniform("brightness", brightness)
            shader.setFloatUniform("sparkle", sparkle)
            shader.setFloatUniform("glint", glint)
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
            updateEffect()
            attachedView?.invalidate()
        }

        private fun updateEffect() {
            val target = attachedView ?: return
            if (resolutionW <= 0f || resolutionH <= 0f) return
            val base = RenderEffect.createRuntimeShaderEffect(shader, "content")
            val combined = RenderEffect.createChainEffect(base, RenderEffect.createBlurEffect(20f, 20f, Shader.TileMode.DECAL))
            effect = combined
            target.setRenderEffect(effect)
        }
    }

    companion object {
        private const val GLASS_SHADER = """
            uniform shader content;
            uniform float2 resolution;
            uniform float time;
            uniform float envLum;
            uniform float brightness;
            uniform float sparkle;
            uniform float glint;

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

            float fbm(float2 p) {
                float value = 0.0;
                float amplitude = 0.5;
                for (int i = 0; i < 4; ++i) {
                    value += noise(p) * amplitude;
                    p *= 2.0;
                    amplitude *= 0.5;
                }
                return value;
            }

            half4 main(float2 fragCoord) {
                float2 safeRes = max(resolution, float2(1.0, 1.0));
                float2 uv = fragCoord / safeRes;
                float aspect = safeRes.x / safeRes.y;
                float2 centered = uv - 0.5;

                float flow = time * 0.22;
                float wave1 = sin((centered.y + flow) * 18.0) * 0.018;
                float wave2 = cos((centered.x * aspect - flow * 0.6) * 22.0) * 0.014;
                float swirl = sin((centered.x * aspect + centered.y * 0.6 + flow * 0.8) * 14.0) * 0.012;

                float2 offset = float2(wave1 + swirl, wave2 - swirl);
                float2 sampleCoord = fragCoord + offset * safeRes * sparkle;
                half4 color = content.eval(sampleCoord);

                float band = smoothstep(0.18, 0.74, uv.y + sin((uv.x + flow * 0.8) * 8.0) * 0.09);
                float topSheen = smoothstep(0.02, 0.28, uv.y + wave1 * 2.6);
                float bottomFade = smoothstep(0.86, 1.04, uv.y);
                float streaks = smoothstep(0.25, 0.95, sin((uv.x * 0.7 + flow * 1.4) * 32.0) * 0.5 + 0.5);

                float caustic = fbm(uv * float2(9.0, 5.5) + float2(flow * 1.2, -flow * 1.6));
                float sparkleMask = pow(max(0.0, caustic - 0.35), 3.0);
                float highlight = max(max(band, topSheen), sparkleMask * 1.3);
                highlight = max(highlight, streaks * glint);

                float ambient = mix(0.55, 1.18, envLum);
                float baseBright = brightness * ambient;
                float finalBright = baseBright + highlight * 0.45 + topSheen * 0.15;

                color.rgb *= finalBright;
                color.rgb = mix(color.rgb, float3(1.0, 1.0, 1.0), 0.08 + highlight * 0.12 + topSheen * 0.05);
                color.a *= mix(0.58, 0.95, band) * (1.0 - bottomFade * 0.35);
                return color;
            }
        """
    }
}
