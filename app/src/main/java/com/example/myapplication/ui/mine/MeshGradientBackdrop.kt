package com.example.myapplication.ui.mine

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Port of FluxDO's `paper_shaders/shaders/mesh_gradient.frag` GPU animated mesh gradient.
 * Renders real AGSL RuntimeShader on Android 13+ (API 33+), with smooth multi-orb Canvas fallback below API 33.
 */
@Composable
internal fun MeshGradientBackdrop(
    colors: List<Color>,
    backgroundColor: Color,
    modifier: Modifier = Modifier,
    distortion: Float = 0.8f,
    swirl: Float = 0.1f,
    speed: Float = 1.0f,
) {
    var timeSeconds by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(speed) {
        val startNanos = withInfiniteAnimationFrameNanos { it }
        while (true) {
            withInfiniteAnimationFrameNanos { frameNanos ->
                timeSeconds = ((frameNanos - startNanos) / 1_000_000_000f) * speed
            }
        }
    }

    val safeColors = remember(colors) {
        if (colors.isEmpty()) listOf(backgroundColor) else colors.take(7)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val shader = remember { runCatching { RuntimeShader(MeshGradientAgslSource) }.getOrNull() }
        if (shader != null) {
            Canvas(modifier = modifier.fillMaxSize()) {
                val w = size.width.coerceAtLeast(1f)
                val h = size.height.coerceAtLeast(1f)
                shader.setFloatUniform("u_resolution", w, h)
                shader.setFloatUniform("u_time", timeSeconds)
                shader.setFloatUniform("u_colorsCount", safeColors.size.toFloat())
                shader.setFloatUniform("u_distortion", distortion)
                shader.setFloatUniform("u_swirl", swirl)
                shader.setFloatUniform(
                    "u_colorBack",
                    backgroundColor.red,
                    backgroundColor.green,
                    backgroundColor.blue,
                    backgroundColor.alpha,
                )
                for (i in 0 until 7) {
                    val c = safeColors.getOrNull(i) ?: Color.Transparent
                    shader.setFloatUniform("u_color$i", c.red, c.green, c.blue, c.alpha)
                }
                drawRect(brush = ShaderBrush(shader))
            }
            return
        }
    }

    // API < 33 smooth animated orb fallback
    Box(modifier = modifier.fillMaxSize().background(backgroundColor)) {
        Canvas(Modifier.fillMaxSize()) {
            val t = 0.5f * (timeSeconds + 41.5f)
            safeColors.forEachIndexed { i, col ->
                val a = i * 0.37f
                val b = 0.6f + ((i / 3f) % 1f) * 0.9f
                val c2 = 0.8f + (((i + 1) / 4f) % 1f)
                val px = (0.5f + 0.38f * sin(t * b + a)) * size.width
                val py = (0.5f + 0.38f * cos(t * c2 + a * 1.5f)) * size.height
                val radius = maxOf(size.width, size.height) * 0.72f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(col.copy(alpha = 0.78f), Color.Transparent),
                        center = Offset(px, py),
                        radius = radius,
                    ),
                    radius = radius,
                    center = Offset(px, py),
                )
            }
        }
    }
}

private val MeshGradientAgslSource = """
    uniform float2 u_resolution;
    uniform float u_time;
    uniform float u_colorsCount;
    uniform float u_distortion;
    uniform float u_swirl;
    uniform float4 u_colorBack;
    uniform float4 u_color0;
    uniform float4 u_color1;
    uniform float4 u_color2;
    uniform float4 u_color3;
    uniform float4 u_color4;
    uniform float4 u_color5;
    uniform float4 u_color6;

    float2 rotate2d(float2 uv, float th) {
        float c = cos(th);
        float s = sin(th);
        return float2(c * uv.x + s * uv.y, -s * uv.x + c * uv.y);
    }

    float4 getColor(int i) {
        if (i == 0) return u_color0;
        if (i == 1) return u_color1;
        if (i == 2) return u_color2;
        if (i == 3) return u_color3;
        if (i == 4) return u_color4;
        if (i == 5) return u_color5;
        return u_color6;
    }

    float2 getPosition(int i, float t) {
        float fi = float(i);
        float a = fi * 0.37;
        float b = 0.6 + fract(fi / 3.0) * 0.9;
        float c2 = 0.8 + fract((fi + 1.0) / 4.0);
        float x = sin(t * b + a);
        float y = cos(t * c2 + a * 1.5);
        return 0.5 + 0.5 * float2(x, y);
    }

    half4 main(float2 fragCoord) {
        float2 uv = fragCoord / max(u_resolution, float2(1.0));
        float firstFrameOffset = 41.5;
        float t = 0.5 * (u_time + firstFrameOffset);

        float radius = smoothstep(0.0, 1.0, length(uv - 0.5));
        float center = 1.0 - radius;

        uv.x += u_distortion * center * sin(t + 0.4 * smoothstep(0.0, 1.0, uv.y))
            * cos(0.2 * t + 2.4 * smoothstep(0.0, 1.0, uv.y));
        uv.y += u_distortion * center * cos(t + 2.0 * smoothstep(0.0, 1.0, uv.x));
        uv.x += u_distortion * center * 0.5 * sin(t + 0.8 * smoothstep(0.0, 1.0, uv.y))
            * cos(0.2 * t + 4.8 * smoothstep(0.0, 1.0, uv.y));
        uv.y += u_distortion * center * 0.5 * cos(t + 4.0 * smoothstep(0.0, 1.0, uv.x));

        float2 uvRotated = uv - float2(0.5);
        float angle = 3.0 * u_swirl * radius;
        uvRotated = rotate2d(uvRotated, -angle);
        uvRotated += float2(0.5);

        float3 color = float3(0.0);
        float opacity = 0.0;
        float totalWeight = 0.0;
        int cnt = int(u_colorsCount);

        for (int i = 0; i < 7; i++) {
            if (i < cnt) {
                float2 pos = getPosition(i, t);
                float4 col = getColor(i);
                float3 colorFraction = col.rgb * col.a;
                float opacityFraction = col.a;

                float d = max(length(uvRotated - pos), 1e-4);
                float d2 = d * d;
                float weight = 1.0 / (d2 * d * sqrt(d) + 1e-3);

                color += colorFraction * weight;
                opacity += opacityFraction * weight;
                totalWeight += weight;
            }
        }

        color /= max(1e-4, totalWeight);
        opacity /= max(1e-4, totalWeight);
        opacity = clamp(opacity, 0.0, 1.0);

        float3 bgColor = u_colorBack.rgb * u_colorBack.a;
        color = color + bgColor * (1.0 - opacity);
        opacity = opacity + u_colorBack.a * (1.0 - opacity);

        return half4(clamp(color, 0.0, 1.0), opacity);
    }
""".trimIndent()
