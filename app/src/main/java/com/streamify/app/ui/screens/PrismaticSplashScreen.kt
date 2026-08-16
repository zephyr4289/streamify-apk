package com.streamify.app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamify.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

@Composable
fun PrismaticSplashScreen(
    onPreWarmComplete: suspend () -> Unit,
    onAnimationComplete: () -> Unit
) {
    // 1. Deterministic Timeline State Machine (0f to 1f over 2800ms)
    val progress = remember { Animatable(0f) }
    val textMeasurer = rememberTextMeasurer()

    // 2. Seamless Background Pre-Warming Engine
    LaunchedEffect(Unit) {
        val animJob = async {
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 2800, easing = LinearEasing)
            )
        }
        val preWarmJob = async(Dispatchers.IO) {
            onPreWarmComplete()
        }

        // Wait for both the cinematic animation and the background backend warm-up
        awaitAll(animJob, preWarmJob)
        onAnimationComplete()
    }

    // 3. The Single GPU Canvas RenderNode
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgBase),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val p = progress.value
            val width = size.width
            val height = size.height
            val center = Offset(width / 2f, height / 2f)

            // --- PHASE TIMINGS ---
            val p1End = 0.286f // 0 - 800ms (Harmonic Genesis)
            val p2End = 0.571f // 800 - 1600ms (Singularity Zoom)
            val p3End = 0.857f // 1600 - 2400ms (Prismatic Dispersion)
            val p4End = 1.000f // 2400 - 2800ms (Quantum Dissolve)

            // --- PHASE 4: QUANTUM DISSOLVE ---
            if (p > p3End) {
                val p4 = ((p - p3End) / (p4End - p3End)).coerceIn(0f, 1f)
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(Primary.copy(alpha = p4 * 0.45f), Color.Transparent),
                        center = center,
                        radius = width * 1.1f
                    )
                )
            }

            // --- PHASE 1: HARMONIC GENESIS ---
            if (p < p1End) {
                val p1 = (p / p1End).coerceIn(0f, 1f)

                // Laser-thin horizontal audio frequency ray
                drawLine(
                    color = ActiveControl.copy(alpha = p1 * 0.8f),
                    start = Offset(center.x - (width / 2f * p1), center.y),
                    end = Offset(center.x + (width / 2f * p1), center.y),
                    strokeWidth = 2.dp.toPx()
                )

                // Text Reveal with traveling chromatic shimmer
                val textLayout = textMeasurer.measure(
                    text = AnnotatedString("S T R E A M I F Y"),
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 6.sp,
                        fontFamily = StreamifyFontFamily
                    )
                )
                val textOffset = Offset(
                    center.x - (textLayout.size.width / 2f),
                    center.y - (textLayout.size.height / 2f)
                )

                // Shimmer gradient sweep
                val shimmerX = (p1 * width * 1.5f) - (width * 0.25f)
                drawText(
                    textLayoutResult = textLayout,
                    topLeft = textOffset,
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            TextSecondary.copy(alpha = 0.4f),
                            ActiveControl,
                            Primary,
                            ActiveControl,
                            TextSecondary.copy(alpha = 0.4f)
                        ),
                        startX = shimmerX - 220f,
                        endX = shimmerX + 220f
                    )
                )
            }

            // --- PHASE 2: ACOUSTIC SINGULARITY ZOOM ---
            if (p >= p1End && p < p3End) {
                val p2 = ((p - p1End) / (p3End - p1End)).coerceIn(0f, 1f)
                // Exponential cubic-bezier zoom curve
                val zoomCurve = 1f - (1f - p2).pow(4f)
                val badgeRadius = (22.dp.toPx()) * (1f + (zoomCurve * 2.8f))

                // Pulsating crimson plasma
                val plasmaAlpha = (0.4f + 0.6f * sin(p2 * PI * 4).toFloat()).coerceIn(0f, 1f)
                drawCircle(
                    color = Primary.copy(alpha = plasmaAlpha * 0.7f),
                    radius = badgeRadius * 1.3f,
                    center = center,
                    blendMode = BlendMode.Screen
                )

                // Core "S" Play Badge
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

            // --- PHASE 3: PRISMATIC SPECTRUM DISPERSION ---
            if (p >= p2End && p < p4End) {
                val p3 = ((p - p2End) / (p4End - p2End)).coerceIn(0f, 1f)
                val dispersionCurve = 1f - (1f - p3).pow(3f)

                // 16 Dynamic Acoustic Spectrum Ribbons
                val ribbonColors = listOf(
                    Color(0xFF8B5CF6), Color(0xFFA855F7), // Deep Bass (Violet/Purple)
                    Color(0xFF06B6D4), Color(0xFF10B981), // Mid Frequencies (Cyan/Emerald)
                    Color(0xFFF59E0B), Color(0xFFFF0000), Color(0xFFEC4899) // Harmonics & Treble (Amber/Red/Magenta)
                )

                val reusablePath = Path()
                val maxRibbons = 16

                for (i in 0 until maxRibbons) {
                    val baseAngle = (i * (360f / maxRibbons)) * (PI / 180f).toFloat()
                    val ribbonLength = (width * 0.85f) * dispersionCurve
                    val strokeWidth = (14.dp.toPx()) * (1f - dispersionCurve).coerceAtLeast(1f)

                    val frequency = 4.5f
                    val amplitude = (16.dp.toPx()) * dispersionCurve
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

        // --- DEVELOPED BY SIREEN SIGNATURE BAR ---
        val alphaProg = progress.value
        val sigAlpha = when {
            alphaProg < 0.286f -> (alphaProg / 0.286f).coerceIn(0f, 1f)
            alphaProg > 0.857f -> (1f - ((alphaProg - 0.857f) / 0.143f)).coerceIn(0f, 1f)
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
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 3.sp
                ),
                color = TextSecondary.copy(alpha = sigAlpha * 0.75f)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "120 FPS Acoustic AI Engine",
                style = LocalAppTypography.current.songArtist.copy(
                    fontSize = 9.sp,
                    letterSpacing = 1.sp
                ),
                color = Primary.copy(alpha = sigAlpha * 0.65f)
            )
        }
    }
}
