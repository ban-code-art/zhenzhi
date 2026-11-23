package com.example.myapplication.ui.components.learning

import android.content.Context
import android.graphics.RuntimeShader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * AGSL fragment shader for theme-adaptive mother-of-pearl (nacre) thin-film iridescence.
 * Four HCT-derived anchor colors (uColorA..uColorD) interfere across FBM micro-relief
 * normals, a Fresnel grazing highlight, a sweeping foil ribbon, and crystalline pearl glints.
 * Masked to strictly zero inside protected text/icon rects with smooth feathering outside.
 */
private const val PEARL_SURFACE_AGSL = """
uniform float2 uResolution;
uniform float uPhase;
uniform float2 uTilt;
uniform float uDark;
uniform float uFoilStrength;
uniform float uTailWeight;
uniform float uGlintStrength;
uniform float4 uColorA;
uniform float4 uColorB;
uniform float4 uColorC;
uniform float4 uColorD;
uniform float4 uClear0;
uniform float4 uClear1;
uniform float uFeather;

const float TAU = 6.2831853;

float hash(float2 p) {
    return fract(sin(dot(p, float2(12.9898, 78.233))) * 43758.5453);
}

float noise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = hash(i);
    float b = hash(i + float2(1.0, 0.0));
    float c = hash(i + float2(0.0, 1.0));
    float d = hash(i + float2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

float fbm(float2 p) {
    float v = 0.0;
    v += 0.62 * noise(p);
    v += 0.38 * noise(p * 2.13 + float2(3.7, 1.9));
    return v;
}

float rectClearance(float2 p, float4 r, float feather) {
    if (r.z <= r.x || r.w <= r.y) return 1.0;
    float2 d = max(max(r.xy - p, p - r.zw), float2(0.0));
    return smoothstep(0.0, max(feather, 1.0), length(d));
}

half3 themeNacreSpectrum(float t) {
    float x = fract(t) * 4.0;
    half3 a = half3(uColorA.rgb);
    half3 b = half3(uColorB.rgb);
    half3 c = half3(uColorC.rgb);
    half3 d = mix(b, half3(uColorD.rgb), clamp(uTailWeight, 0.0, 1.0));
    if (x < 1.0) return mix(a, b, smoothstep(0.0, 1.0, x));
    if (x < 2.0) return mix(b, c, smoothstep(0.0, 1.0, x - 1.0));
    if (x < 3.0) return mix(c, d, smoothstep(0.0, 1.0, x - 2.0));
    return mix(d, a, smoothstep(0.0, 1.0, x - 3.0));
}

half4 main(float2 fragCoord) {
    float clear = rectClearance(fragCoord, uClear0, uFeather) *
                  rectClearance(fragCoord, uClear1, uFeather);
    if (clear <= 0.001) {
        return half4(0.0);
    }

    float2 res = max(uResolution, float2(1.0));
    float2 uv = fragCoord / res;
    float aspect = res.x / res.y;
    float2 centered = (uv - 0.5) * float2(aspect, 1.0);

    float phaseAngle = uPhase * TAU;
    float2 flow = float2(cos(phaseAngle), sin(phaseAngle)) * 0.28 + uTilt * 0.42;

    float2 nacreUv = uv * float2(3.4 * aspect, 3.4) + flow * 0.65;
    float h0 = fbm(nacreUv);
    float hx = fbm(nacreUv + float2(0.045, 0.0));
    float hy = fbm(nacreUv + float2(0.0, 0.045));
    float3 normal = normalize(float3((h0 - hx) * 4.2, (h0 - hy) * 4.2, 1.0));

    float3 viewDir = normalize(float3(
        uTilt.x * 1.45 + sin(phaseAngle) * 0.42,
        uTilt.y * 1.35 + cos(phaseAngle) * 0.30 - 0.28,
        1.0
    ));

    float ndv = clamp(dot(normal, viewDir), 0.0, 1.0);
    float fresnel = pow(1.0 - ndv, 1.6);

    float diag = dot(centered, float2(0.78, 0.62));
    float crossDiag = dot(centered, float2(-0.55, 0.83));
    float shift1 = diag * 0.65 + h0 * 0.45 + uPhase * 1.0 + dot(uTilt, float2(0.45, -0.35));
    float shift2 = crossDiag * 0.58 + fbm(nacreUv * 1.4 + 4.1) * 0.38 - uPhase * 1.0 + uTilt.y * 0.30;
    float shift3 = length(centered + uTilt * 0.22) * 0.72 + h0 * 0.30;

    half3 iri1 = themeNacreSpectrum(shift1);
    half3 iri2 = themeNacreSpectrum(shift2 + 0.25);
    half3 iri3 = themeNacreSpectrum(shift3 + 0.55);
    half3 nacre = iri1 * 0.46 + iri2 * 0.34 + iri3 * 0.20;

    float sweepCenter = -0.85 + fract(uPhase + uTilt.x * 0.18 - uTilt.y * 0.12) * 2.7;
    float sweepDist = (uv.x * 0.82 + uv.y * 0.34) - sweepCenter;
    float sweepBand = exp(-sweepDist * sweepDist * 22.0);
    half3 sweepTint = mix(nacre, half3(1.0, 0.99, 0.97), 0.46);

    float2 cell = floor(fragCoord * 0.22);
    float cellRand = hash(cell);
    float sparkleMask = step(0.968, cellRand);
    float twinkle = 0.5 + 0.5 * sin(phaseAngle * 2.0 + cellRand * TAU * 7.0);
    float sparkle = sparkleMask * twinkle * (0.25 + 0.75 * sweepBand) * uGlintStrength;

    float strength = clamp(uFoilStrength, 0.3, 1.2);
    float baseAlpha = (mix(0.24, 0.30, uDark) + fresnel * mix(0.20, 0.26, uDark)) * strength;
    float sweepAlpha = sweepBand * mix(0.30, 0.34, uDark) * strength;
    float totalAlpha = clamp((baseAlpha + sweepAlpha + sparkle * 0.40) * clear, 0.0, 0.72);

    half3 color = mix(nacre, sweepTint, clamp(sweepBand * 0.72 + sparkle * 0.55, 0.0, 1.0));
    color += half3(1.0, 0.99, 0.96) * half(sparkle * 0.50);
    return half4(color * totalAlpha, totalAlpha);
}
"""

/**
 * AGSL fragment shader for the dynamic theme-harmonized iridescent perimeter highlight.
 */
private const val PEARL_RIM_AGSL = """
uniform float2 uResolution;
uniform float uPhase;
uniform float2 uTilt;
uniform float uDark;
uniform float uRimStrength;
uniform float4 uRimA;
uniform float4 uRimB;
uniform float4 uRimHighlight;

const float TAU = 6.2831853;

half3 rimSpectrum(float t) {
    float x = fract(t) * 3.0;
    half3 a = half3(uRimA.rgb);
    half3 b = half3(uRimB.rgb);
    half3 c = half3(uRimHighlight.rgb);
    if (x < 1.0) return mix(a, b, smoothstep(0.0, 1.0, x));
    if (x < 2.0) return mix(b, c, smoothstep(0.0, 1.0, x - 1.0));
    return mix(c, a, smoothstep(0.0, 1.0, x - 2.0));
}

half4 main(float2 fragCoord) {
    float2 res = max(uResolution, float2(1.0));
    float2 centered = (fragCoord / res) - 0.5;
    centered.x *= res.x / res.y;

    float theta = atan(centered.y, centered.x);
    float normAngle = theta / TAU;
    float phaseAngle = uPhase * TAU;
    float lightAngle = phaseAngle + atan(uTilt.y, uTilt.x + 0.001) * 0.45;

    float primaryArc = pow(max(0.0, cos(theta - lightAngle)), 5.0);
    float counterArc = pow(max(0.0, cos(theta - lightAngle - 3.1415926)), 8.0);
    float specular = clamp(primaryArc * 0.85 + counterArc * 0.55, 0.0, 1.0);

    half3 iri = rimSpectrum(normAngle * 1.5 + uPhase * 1.0 + uTilt.x * 0.25 - uTilt.y * 0.20);
    half3 highlight = mix(iri, half3(uRimHighlight.rgb), specular * 0.72);
    float baseAlpha = mix(0.52, 0.46, uDark) * clamp(uRimStrength, 0.4, 1.1);
    float alpha = mix(baseAlpha, 0.96, specular);
    return half4(highlight * alpha, alpha);
}
"""

@Stable
internal class StudyCardTiltState {
    var sensorRoll by mutableFloatStateOf(0f)
        internal set
    var sensorPitch by mutableFloatStateOf(0f)
        internal set
    var pointerX by mutableFloatStateOf(0f)
        internal set
    var pointerY by mutableFloatStateOf(0f)
        internal set

    val combinedX: Float get() = (sensorRoll * 0.65f + pointerX * 0.55f).coerceIn(-1f, 1f)
    val combinedY: Float get() = (sensorPitch * 0.65f + pointerY * 0.55f).coerceIn(-1f, 1f)
}

/**
 * Tracks relative device orientation (TYPE_ROTATION_VECTOR) with resting-angle
 * calibration and a small deadzone so a stationary device produces zero jitter.
 */
@Composable
internal fun rememberStudyCardTiltState(enabled: Boolean): StudyCardTiltState {
    val state = remember { StudyCardTiltState() }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val enabledState = rememberUpdatedState(enabled)

    DisposableEffect(context, lifecycleOwner, enabled) {
        if (!enabled) {
            state.sensorRoll = 0f
            state.sensorPitch = 0f
            return@DisposableEffect onDispose {}
        }
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (sensorManager == null || rotationSensor == null) {
            return@DisposableEffect onDispose {}
        }

        var baselinePitch: Float? = null
        var baselineRoll: Float? = null
        val alpha = 0.14f
        val matrix = FloatArray(9)
        val orientation = FloatArray(3)
        var registered = false

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (!enabledState.value || event?.sensor?.type != Sensor.TYPE_ROTATION_VECTOR) return
                SensorManager.getRotationMatrixFromVector(matrix, event.values)
                SensorManager.getOrientation(matrix, orientation)
                val pitch = orientation[1]
                val roll = orientation[2]
                if (baselinePitch == null || baselineRoll == null) {
                    baselinePitch = pitch
                    baselineRoll = roll
                    return
                }
                val rawPitch = ((pitch - baselinePitch!!) / (PI.toFloat() * 0.28f)).coerceIn(-1f, 1f)
                val rawRoll = ((roll - baselineRoll!!) / (PI.toFloat() * 0.28f)).coerceIn(-1f, 1f)
                val nextRoll = alpha * rawRoll + (1f - alpha) * state.sensorRoll
                val nextPitch = alpha * rawPitch + (1f - alpha) * state.sensorPitch
                if (abs(nextRoll - state.sensorRoll) > 0.015f) state.sensorRoll = nextRoll
                if (abs(nextPitch - state.sensorPitch) > 0.015f) state.sensorPitch = nextPitch
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        fun updateRegistration(started: Boolean) {
            if (started && !registered) {
                baselinePitch = null
                baselineRoll = null
                registered = sensorManager.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_UI)
            } else if (!started && registered) {
                sensorManager.unregisterListener(listener)
                registered = false
            }
        }

        updateRegistration(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        val observer = LifecycleEventObserver { _, _ ->
            updateRegistration(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (registered) sensorManager.unregisterListener(listener)
        }
    }
    return state
}

internal interface StudyCardIridescentRenderer {
    fun surfaceBrush(
        size: Size,
        phase: Float,
        tiltX: Float,
        tiltY: Float,
        style: StudyCardIridescentStyle,
        protectedRects: List<StudyReadabilityRect>,
        featherPx: Float,
    ): Brush

    fun rimBrush(
        size: Size,
        phase: Float,
        tiltX: Float,
        tiltY: Float,
        style: StudyCardIridescentStyle,
    ): Brush
}

@Composable
internal fun rememberStudyCardIridescentRenderer(): StudyCardIridescentRenderer {
    return remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            AgslIridescentRenderer.createOrFallback()
        } else {
            FallbackIridescentRenderer
        }
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class AgslIridescentRenderer(
    private val surfaceShader: RuntimeShader,
    private val rimShader: RuntimeShader,
) : StudyCardIridescentRenderer {
    private val surfaceBrush = ShaderBrush(surfaceShader)
    private val rimBrush = ShaderBrush(rimShader)

    override fun surfaceBrush(
        size: Size,
        phase: Float,
        tiltX: Float,
        tiltY: Float,
        style: StudyCardIridescentStyle,
        protectedRects: List<StudyReadabilityRect>,
        featherPx: Float,
    ): Brush {
        val w = size.width.coerceAtLeast(1f)
        val h = size.height.coerceAtLeast(1f)
        surfaceShader.setFloatUniform("uResolution", w, h)
        surfaceShader.setFloatUniform("uPhase", phase)
        surfaceShader.setFloatUniform("uTilt", tiltX, tiltY)
        surfaceShader.setFloatUniform("uDark", if (style.dark) 1f else 0f)
        surfaceShader.setFloatUniform("uFoilStrength", style.foilIntensity)
        surfaceShader.setFloatUniform("uTailWeight", style.tailWeight)
        surfaceShader.setFloatUniform("uGlintStrength", style.glintStrength)
        surfaceShader.setColorUniform("uColorA", style.iridescentA)
        surfaceShader.setColorUniform("uColorB", style.iridescentB)
        surfaceShader.setColorUniform("uColorC", style.iridescentC)
        surfaceShader.setColorUniform("uColorD", style.iridescentD)
        val r0 = protectedRects.getOrNull(0)
        val r1 = protectedRects.getOrNull(1)
        if (r0 != null) surfaceShader.setFloatUniform("uClear0", r0.left, r0.top, r0.right, r0.bottom)
        else surfaceShader.setFloatUniform("uClear0", 0f, 0f, 0f, 0f)
        if (r1 != null) surfaceShader.setFloatUniform("uClear1", r1.left, r1.top, r1.right, r1.bottom)
        else surfaceShader.setFloatUniform("uClear1", 0f, 0f, 0f, 0f)
        surfaceShader.setFloatUniform("uFeather", featherPx.coerceAtLeast(1f))
        return surfaceBrush
    }

    override fun rimBrush(
        size: Size,
        phase: Float,
        tiltX: Float,
        tiltY: Float,
        style: StudyCardIridescentStyle,
    ): Brush {
        val w = size.width.coerceAtLeast(1f)
        val h = size.height.coerceAtLeast(1f)
        rimShader.setFloatUniform("uResolution", w, h)
        rimShader.setFloatUniform("uPhase", phase)
        rimShader.setFloatUniform("uTilt", tiltX, tiltY)
        rimShader.setFloatUniform("uDark", if (style.dark) 1f else 0f)
        rimShader.setFloatUniform("uRimStrength", style.rimStrength)
        rimShader.setColorUniform("uRimA", style.rimPrismA)
        rimShader.setColorUniform("uRimB", style.rimPrismB)
        rimShader.setColorUniform("uRimHighlight", style.rimHighlight)
        return rimBrush
    }

    private fun RuntimeShader.setColorUniform(name: String, color: Color) {
        setFloatUniform(name, color.red, color.green, color.blue, color.alpha)
    }

    companion object {
        fun createOrFallback(): StudyCardIridescentRenderer = runCatching {
            AgslIridescentRenderer(
                surfaceShader = RuntimeShader(PEARL_SURFACE_AGSL),
                rimShader = RuntimeShader(PEARL_RIM_AGSL),
            )
        }.getOrElse { FallbackIridescentRenderer }
    }
}

internal object FallbackIridescentRenderer : StudyCardIridescentRenderer {
    override fun surfaceBrush(
        size: Size,
        phase: Float,
        tiltX: Float,
        tiltY: Float,
        style: StudyCardIridescentStyle,
        protectedRects: List<StudyReadabilityRect>,
        featherPx: Float,
    ): Brush {
        val shift = ((phase + tiltX * 0.14f - tiltY * 0.10f) % 1f + 1f) % 1f
        val offset = -0.65f + shift * 2.3f
        val start = Offset(size.width * (offset - 0.35f), size.height * (-0.2f + tiltY * 0.25f))
        val end = Offset(size.width * (offset + 0.55f), size.height * (1.2f + tiltY * 0.25f))
        val peak = (if (style.dark) 0.25f else 0.32f) * style.foilIntensity
        val mid = (if (style.dark) 0.17f else 0.22f) * style.foilIntensity
        val tail = mid * (0.4f + 0.6f * style.tailWeight)
        return Brush.linearGradient(
            colorStops = arrayOf(
                0.0f to Color.Transparent,
                0.18f to style.iridescentB.copy(alpha = mid * 0.78f),
                0.36f to style.iridescentA.copy(alpha = mid),
                0.52f to style.rimHighlight.copy(alpha = peak),
                0.68f to style.iridescentC.copy(alpha = mid),
                0.86f to style.iridescentD.copy(alpha = tail),
                1.0f to Color.Transparent,
            ),
            start = start,
            end = end,
        )
    }

    override fun rimBrush(
        size: Size,
        phase: Float,
        tiltX: Float,
        tiltY: Float,
        style: StudyCardIridescentStyle,
    ): Brush {
        val angle = (phase * 2.0 * PI + tiltX * 0.9 - tiltY * 0.7).toFloat()
        val dx = cos(angle) * size.width * 0.65f
        val dy = sin(angle) * size.height * 0.85f
        val cx = size.width * 0.5f
        val cy = size.height * 0.5f
        val strength = style.rimStrength
        return Brush.linearGradient(
            colorStops = arrayOf(
                0.0f to style.rimPrismA.copy(alpha = 0.75f * strength),
                0.26f to style.rimHighlight.copy(alpha = 0.94f),
                0.52f to style.rimPrismB.copy(alpha = 0.72f * strength),
                0.78f to style.iridescentC.copy(alpha = 0.66f * strength),
                1.0f to style.rimPrismA.copy(alpha = 0.75f * strength),
            ),
            start = Offset(cx - dx, cy - dy),
            end = Offset(cx + dx, cy + dy),
        )
    }
}
