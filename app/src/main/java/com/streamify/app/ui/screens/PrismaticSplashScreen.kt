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
    // 1. Extended Timeline State Machine (4400ms for mesmerizing cinematic experience & complete background pre-warming)
    val progress = remember { Animatable(0f) }
    val textMeasurer = rememberTextMeasurer()

    // 2. Seamless Background Pre-Warming Engine
    LaunchedEffect(Unit) {
        val animJob = async {
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 4400, easing = LinearEasing)
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
            val p1End = 0.350f // 0 - 1540ms (Harmonic Genesis & Dominant Typography)
            val p2End = 0.650f // 1540 - 2860ms (Acoustic Singularity Zoom)
            val p3End = 0.880f // 2860 - 3870ms (Prismatic Dispersion)
            val p4End = 1.000f // 3870 - 4400ms (Quantum Dissolve)

            // --- PHASE 4: QUANTUM DISSOLVE ---
            if (p > p3End) {
                val p4 = ((p - p3End) / (p4End - p3End)).coerceIn(0f, 1f)
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(Primary.copy(alpha = p4 * 0.45f), Color.Transparent),
                        center = center,
                        radius = width * 1.2f
                    )
                )
            }

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

                // 1. Dominant Center Neon Halo Bloom behind STREAMIFY
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

                // 2. Text Reveal: Dominant "S T R E A M I F Y"
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

                // Traveling Chromatic Shimmer Gradient Sweep
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

                // 3. Kinetic Flying Dominant Subtitle: "DEVELOPED BY SIREEN"
                val flyProg = ((p1 - 0.18f) / 0.82f).coerceIn(0f, 1f)
                if (flyProg > 0f) {
                    val flyEase = 1f - (1f - flyProg).pow(3f) // Cubic ease-out flight curve
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

                    // Kinetic Flying Neon Glow Bloom on "SIREEN"
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

                    // Flying Text with synchronized chromatic laser sweep
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

            // --- PHASE 2: ACOUSTIC SINGULARITY ZOOM ---
            if (p >= p1End && p < p3End) {
                val p2 = ((p - p1End) / (p3End - p1End)).coerceIn(0f, 1f)
                val zoomCurve = 1f - (1f - p2).pow(4f)
                val badgeRadius = (26.dp.toPx()) * (1f + (zoomCurve * 2.8f))

                // Pulsating crimson plasma
                val plasmaAlpha = (0.4f + 0.6f * sin(p2 * PI * 4).toFloat()).coerceIn(0f, 1f)
                drawCircle(
                    color = Primary.copy(alpha = plasmaAlpha * 0.75f),
                    radius = badgeRadius * 1.35f,
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

        // --- DEVELOPED BY SIREEN SIGNATURE BAR ---
        val alphaProg = progress.value
        val sigAlpha = when {
            alphaProg < 0.25f -> (alphaProg / 0.25f).coerceIn(0f, 1f)
            alphaProg > 0.88f -> (1f - ((alphaProg - 0.88f) / 0.12f)).coerceIn(0f, 1f)
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
