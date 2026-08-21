package com.streamify.app.ui.screens

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamify.app.ui.theme.*
import com.streamify.app.util.StreamifyHapticEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

@Composable
fun PrismaticSplashScreen(
    onPreWarmComplete: suspend () -> Unit,
    onAnimationComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val timeline = remember { Animatable(0f) }
    var touchPos by remember { mutableStateOf(Offset.Zero) }
    val textMeasurer = rememberTextMeasurer()
    val scope = rememberCoroutineScope()

    // 1. Compile AGSL Shader on Android 13+ / RenderNode Fallback
    val runtimeShader = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                RuntimeShader(SPLASH_AGSL_SHADER)
            } catch (e: Exception) {
                null
            }
        } else null
    }

    // 2. Dual Background Pre-Warming Engine & Multi-Sensory Haptics
    LaunchedEffect(Unit) {
        // Haptic Cue 1: Laser Shimmer Ignition
        StreamifyHapticEngine.scrubberTick()

        val hapticJob = scope.launch {
            // Milestone 2: Singularity Lock Detent at ~1540ms
            kotlinx.coroutines.delay(1540)
            StreamifyHapticEngine.tokenImpactDetent()

            // Milestone 3: Dispersion Flutter Detent at ~2860ms
            kotlinx.coroutines.delay(1320)
            StreamifyHapticEngine.magneticQueueGrab()
        }

        val animJob = async {
            timeline.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 4400, easing = LinearEasing)
            )
        }

        val preWarmJob = async(Dispatchers.IO) {
            onPreWarmComplete()
        }

        awaitAll(animJob, preWarmJob)
        hapticJob.cancel()
        onAnimationComplete()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = { offset ->
                        touchPos = offset
                        tryAwaitRelease()
                        touchPos = Offset.Zero
                    },
                    onTap = {
                        // Instant Fast-Forward on tap if warmed up
                        scope.launch {
                            timeline.animateTo(
                                targetValue = 1f,
                                animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing)
                            )
                            onAnimationComplete()
                        }
                    }
                )
            }
            .graphicsLayer {
                val progress = timeline.value

                // 4. Spatial Hero Glide at Exit Phase (Morphs toward Top-Left App Bar)
                if (progress >= 0.85f) {
                    val exitFraction = ((progress - 0.85f) / 0.15f).coerceIn(0f, 1f)
                    scaleX = 1f - (exitFraction * 0.65f)
                    scaleY = 1f - (exitFraction * 0.65f)
                    translationX = -size.width * 0.38f * exitFraction
                    translationY = -size.height * 0.42f * exitFraction
                    alpha = 1f - exitFraction
                }
            }
            .drawBehind {
                drawRect(color = Color(0xFF07070A)) // True OLED Base
            },
        contentAlignment = Alignment.Center
    ) {
        val p = timeline.value

        // High-Performance AGSL Shader (Android 13+) with Fallback 2D Canvas
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val center = Offset(width / 2f, height / 2f)

            // 1. Hardware AGSL Shader Layer
            if (runtimeShader != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                try {
                    runtimeShader.setFloatUniform("uResolution", width, height)
                    runtimeShader.setFloatUniform("uTime", p * 4.4f)
                    runtimeShader.setFloatUniform("uProgress", p)
                    runtimeShader.setFloatUniform("uTouch", touchPos.x, touchPos.y)
                    drawRect(brush = ShaderBrush(runtimeShader))
                } catch (e: Throwable) {
                    // Fail-safe: absorb and continue with 2D Canvas
                }
            }

            val p1End = 0.350f
            val p2End = 0.650f
            val p3End = 0.880f
            val p4End = 1.000f

            // --- PHASE 1: HARMONIC GENESIS & DOMINATING TYPOGRAPHY ---
            if (p < p1End) {
                val p1 = (p / p1End).coerceIn(0f, 1f)

                // Laser-thin horizontal audio frequency ray expanding along the screen
                drawLine(
                    color = ActiveControl.copy(alpha = p1 * 0.85f),
                    start = Offset(center.x - (width / 2f * p1), center.y),
                    end = Offset(center.x + (width / 2f * p1), center.y),
                    strokeWidth = 2.5.dp.toPx()
                )

                // Dominant Center Neon Halo Bloom behind STREAMIFY
                val glowRadius = (width * 0.42f) * p1
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Primary.copy(alpha = 0.35f * p1),
                            ActiveControl.copy(alpha = 0.20f * p1),
                            Color.Transparent
                        ),
                        center = Offset(center.x, center.y - 18.dp.toPx()),
                        radius = glowRadius.coerceAtLeast(1f)
                    ),
                    radius = glowRadius.coerceAtLeast(1f),
                    center = Offset(center.x, center.y - 18.dp.toPx())
                )

                // Text Reveal: Dominant "S T R E A M I F Y"
                val streamifyLayout = textMeasurer.measure(
                    text = AnnotatedString("S T R E A M I F Y"),
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 8.sp,
                        fontFamily = StreamifyFontFamily
                    )
                )
                val streamifyOffset = Offset(
                    center.x - (streamifyLayout.size.width / 2f),
                    center.y - (streamifyLayout.size.height / 2f) - 18.dp.toPx()
                )

                val shimmerX = (p1 * width * 1.6f) - (width * 0.3f)
                drawText(
                    textLayoutResult = streamifyLayout,
                    topLeft = streamifyOffset,
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            TextSecondary.copy(alpha = 0.35f),
                            Color.White,
                            Primary,
                            ActiveControl,
                            Color.White,
                            TextSecondary.copy(alpha = 0.35f)
                        ),
                        startX = shimmerX - 260f,
                        endX = shimmerX + 260f
                    )
                )

                // Kinetic Flying Dominant Subtitle: "DEVELOPED BY SIREEN"
                val flyProg = ((p1 - 0.18f) / 0.82f).coerceIn(0f, 1f)
                if (flyProg > 0f) {
                    val flyEase = 1f - (1f - flyProg).pow(3f)
                    val flyY = center.y + 22.dp.toPx() + ((1f - flyEase) * 26.dp.toPx())
                    val flyAlpha = (flyEase * 1.15f).coerceIn(0f, 1f)

                    val sireenAnnotated = buildAnnotatedString {
                        withStyle(
                            SpanStyle(
                                color = Color.White.copy(alpha = flyAlpha * 0.9f),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 4.sp
                            )
                        ) {
                            append("DEVELOPED BY ")
                        }
                        withStyle(
                            SpanStyle(
                                color = Primary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 5.5.sp
                            )
                        ) {
                            append("S I R E E N")
                        }
                    }

                    val sireenLayout = textMeasurer.measure(
                        text = sireenAnnotated,
                        style = TextStyle(fontFamily = StreamifyFontFamily)
                    )
                    val sireenOffset = Offset(
                        center.x - (sireenLayout.size.width / 2f),
                        flyY
                    )

                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Primary.copy(alpha = flyAlpha * 0.45f),
                                ActiveControl.copy(alpha = flyAlpha * 0.25f),
                                Color.Transparent
                            ),
                            center = Offset(center.x + 35.dp.toPx(), flyY + (sireenLayout.size.height / 2f)),
                            radius = sireenLayout.size.width * 0.65f
                        ),
                        radius = sireenLayout.size.width * 0.65f,
                        center = Offset(center.x + 35.dp.toPx(), flyY + (sireenLayout.size.height / 2f))
                    )

                    drawText(
                        textLayoutResult = sireenLayout,
                        topLeft = sireenOffset,
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Primary.copy(alpha = flyAlpha * 0.8f),
                                Color.White.copy(alpha = flyAlpha),
                                Color(0xFFFF2A6D).copy(alpha = flyAlpha),
                                Color.White.copy(alpha = flyAlpha),
                                Primary.copy(alpha = flyAlpha * 0.8f)
                            ),
                            startX = shimmerX - 200f,
                            endX = shimmerX + 200f
                        )
                    )
                }
            }

            // Fallback for Pre-Android 13 Devices (Render 2D Prismatic Ribbons)
            if (runtimeShader == null) {
                if (p >= p1End && p < p3End) {
                    val p2 = ((p - p1End) / (p3End - p1End)).coerceIn(0f, 1f)
                    val zoomCurve = 1f - (1f - p2).pow(4f)
                    val badgeRadius = (26.dp.toPx()) * (1f + (zoomCurve * 2.8f))

                    val plasmaAlpha = (0.4f + 0.6f * sin(p2 * PI * 4).toFloat()).coerceIn(0f, 1f)
                    drawCircle(
                        color = Primary.copy(alpha = plasmaAlpha * 0.75f),
                        radius = badgeRadius * 1.35f,
                        center = center,
                        blendMode = BlendMode.Screen
                    )

                    drawCircle(
                        color = Primary,
                        radius = badgeRadius,
                        center = center
                    )

                    val trianglePath = Path().apply {
                        moveTo(center.x - (badgeRadius * 0.3f), center.y - (badgeRadius * 0.4f))
                        lineTo(center.x + (badgeRadius * 0.45f), center.y)
                        lineTo(center.x - (badgeRadius * 0.3f), center.y + (badgeRadius * 0.4f))
                        close()
                    }
                    drawPath(path = trianglePath, color = ActiveControl)
                }

                if (p >= p2End && p < p4End) {
                    val p3 = ((p - p2End) / (p4End - p2End)).coerceIn(0f, 1f)
                    val dispersionCurve = 1f - (1f - p3).pow(3f)

                    val ribbonColors = listOf(
                        Color(0xFF8B5CF6), Color(0xFFA855F7),
                        Color(0xFF06B6D4), Color(0xFF10B981),
                        Color(0xFFF59E0B), Color(0xFFFF0000), Color(0xFFEC4899)
                    )

                    val reusablePath = Path()
                    val maxRibbons = 16

                    for (i in 0 until maxRibbons) {
                        val baseAngle = (i * (360f / maxRibbons)) * (PI / 180f).toFloat()
                        val ribbonLength = (width * 0.90f) * dispersionCurve
                        val strokeWidth = (14.dp.toPx()) * (1f - dispersionCurve).coerceAtLeast(1f)

                        val frequency = 4.5f
                        val amplitude = (18.dp.toPx()) * dispersionCurve
                        val phaseShift = i * 0.45f

                        reusablePath.reset()
                        val segments = 30
                        for (s in 0..segments) {
                            val t = s.toFloat() / segments
                            val dist = ribbonLength * t
                            val waveOffset = sin(t * PI * frequency + phaseShift + (p * PI * 2)).toFloat() * amplitude
                            val x = center.x + cos(baseAngle) * dist + cos(baseAngle + (PI / 2f).toFloat()) * waveOffset
                            val y = center.y + sin(baseAngle) * dist + sin(baseAngle + (PI / 2f).toFloat()) * waveOffset

                            if (s == 0) reusablePath.moveTo(x, y) else reusablePath.lineTo(x, y)
                        }

                        val color = ribbonColors[i % ribbonColors.size]
                        drawPath(
                            path = reusablePath,
                            color = color.copy(alpha = (1f - (p3 * 0.45f)).coerceIn(0f, 1f)),
                            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                            blendMode = BlendMode.Screen
                        )
                    }
                }
            }
        }

        // --- DEVELOPED BY SIREEN SIGNATURE BAR ---
        val alphaProg = timeline.value
        val sigAlpha = when {
            alphaProg < 0.25f -> (alphaProg / 0.25f).coerceIn(0f, 1f)
            alphaProg > 0.85f -> (1f - ((alphaProg - 0.85f) / 0.15f)).coerceIn(0f, 1f)
            else -> 1f
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "DEVELOPED BY SIREEN",
                style = LocalAppTypography.current.songArtist.copy(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 4.sp
                ),
                color = TextMain.copy(alpha = sigAlpha * 0.90f)
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = "120 FPS Acoustic AI Engine",
                style = LocalAppTypography.current.songArtist.copy(
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.5.sp
                ),
                color = Primary.copy(alpha = sigAlpha * 0.80f)
            )
        }
    }
}

private const val SPLASH_AGSL_SHADER = """
    uniform float2 uResolution;
    uniform float  uTime;
    uniform float  uProgress;
    uniform float2 uTouch;

    float hash(float2 p) {
        return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453);
    }

    float4 main(float2 fragCoord) {
        float2 uv = (fragCoord - 0.5 * uResolution) / min(uResolution.x, uResolution.y);
        float2 touchOffset = (uTouch - 0.5 * uResolution) / min(uResolution.x, uResolution.y);
        
        // Gravitational lens pull toward touch coordinates
        float touchDist = length(uv - touchOffset);
        uv -= (touchOffset - uv) * 0.08 * exp(-touchDist * 3.0) * smoothstep(0.0, 0.8, uProgress);

        float d = length(uv);
        float angle = atan(uv.y, uv.x);

        // Singularity Core Dynamics
        float coreRadius = 0.18 * smoothstep(0.35, 0.65, uProgress) * (1.0 - smoothstep(0.85, 1.0, uProgress));
        float pulse = 0.03 * sin(uTime * 8.0 + angle * 4.0);
        float core = smoothstep(coreRadius + pulse, coreRadius * 0.2, d);

        // 16 Prismatic Dispersion Filament Ribbons
        float filaments = 0.0;
        if (uProgress > 0.60) {
            float burstProgress = smoothstep(0.60, 0.90, uProgress);
            float spiral = sin(angle * 16.0 + uTime * 4.0 - d * 12.0);
            filaments = smoothstep(0.7, 1.0, spiral) * exp(-d * (3.0 - burstProgress * 2.0)) * burstProgress;
        }

        // Chromatic Aberration Spectrum
        float3 col;
        col.r = core * 0.95 + filaments * 0.85 + (0.04 / (d + 0.08)) * smoothstep(0.0, 0.5, uProgress);
        col.g = core * 0.12 + filaments * 0.45 + (0.02 / (d + 0.12)) * smoothstep(0.4, 0.8, uProgress);
        col.b = core * 0.35 + filaments * 0.95 + (0.06 / (d + 0.06)) * smoothstep(0.2, 0.7, uProgress);

        // Subtle 35mm Analog Film Grain
        float grain = (hash(fragCoord + uTime) - 0.5) * 0.035;
        col += grain;

        // Fade out to deep OLED black at end of sequence
        float globalAlpha = 1.0 - smoothstep(0.90, 1.0, uProgress);
        return float4(col * globalAlpha, globalAlpha);
    }
"""
