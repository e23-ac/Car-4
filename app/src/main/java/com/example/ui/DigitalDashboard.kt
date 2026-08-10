package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ElectricMeter
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.CarState
import kotlin.math.cos
import kotlin.math.sin

enum class DashboardTheme(
    val nameEn: String,
    val primaryColor: Color,
    val secondaryColor: Color,
    val bgOverlayColor: Color,
    val borderColor: Color
) {
    CYBER_NEON(
        nameEn = "Cyber Neon",
        primaryColor = Color(0xFF38BDF8),
        secondaryColor = Color(0xFFF43F5E),
        bgOverlayColor = Color(0xEE0B132B),
        borderColor = Color(0xFF00F0FF)
    ),
    SPORT_CRIMSON(
        nameEn = "Sport Crimson",
        primaryColor = Color(0xFFEF4444),
        secondaryColor = Color(0xFFF59E0B),
        bgOverlayColor = Color(0xEE18181B),
        borderColor = Color(0xFFDC2626)
    ),
    LUXURY_GOLD(
        nameEn = "Luxury Gold",
        primaryColor = Color(0xFFFACC15),
        secondaryColor = Color(0xFF10B981),
        bgOverlayColor = Color(0xEE0F172A),
        borderColor = Color(0xFFEAB308)
    )
}

enum class SpeedUnit(val label: String, val multiplier: Float) {
    KMH("KM/H", 1.0f),
    MPH("MPH", 0.621371f)
}

@Composable
fun DigitalDashboard(
    car: CarState,
    onGearToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isExpandedCluster by remember { mutableStateOf(false) }
    var selectedUnit by remember { mutableStateOf(SpeedUnit.KMH) }
    var currentTheme by remember { mutableStateOf(DashboardTheme.CYBER_NEON) }

    // Redline flashing animation for high RPM (> 6000 RPM)
    val infiniteTransition = rememberInfiniteTransition(label = "redline_flash")
    val redlineAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(180, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Surface(
        color = currentTheme.bgOverlayColor,
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, currentTheme.borderColor),
        modifier = modifier.testTag("digital_dashboard_surface")
    ) {
        Column(
            modifier = Modifier.padding(10.dp)
        ) {
            // Dashboard Top Bar (Vehicle Name & Interactive Toggles)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Vehicle Badge
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(currentTheme.primaryColor)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = car.vehicleType.displayNameEn,
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Interactive Controls
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Speed unit toggle button
                    Surface(
                        color = Color(0xFF1E293B),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                selectedUnit = if (selectedUnit == SpeedUnit.KMH) SpeedUnit.MPH else SpeedUnit.KMH
                            }
                            .testTag("unit_toggle_btn")
                    ) {
                        Text(
                            text = selectedUnit.label,
                            color = currentTheme.primaryColor,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    // Dashboard Theme Cycle button
                    IconButton(
                        onClick = {
                            val nextOrdinal = (currentTheme.ordinal + 1) % DashboardTheme.values().size
                            currentTheme = DashboardTheme.values()[nextOrdinal]
                        },
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1E293B))
                            .testTag("theme_toggle_btn")
                    ) {
                        Icon(
                            Icons.Default.Tune,
                            contentDescription = "Theme",
                            tint = currentTheme.primaryColor,
                            modifier = Modifier.size(14.dp)
                        )
                    }

                    // Expand / Collapse Instrument Cluster button
                    IconButton(
                        onClick = { isExpandedCluster = !isExpandedCluster },
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1E293B))
                            .testTag("expand_cluster_btn")
                    ) {
                        Icon(
                            if (isExpandedCluster) Icons.Default.Speed else Icons.Default.ElectricMeter,
                            contentDescription = "Expand",
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Core Cluster Display Area
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Tachometer & Speedometer Combined Arc Gauge
                val speed = car.speedKmh * selectedUnit.multiplier
                val maxSpeed = car.vehicleType.maxSpeedKmh * selectedUnit.multiplier
                val speedRatio = (speed / maxSpeed).coerceIn(0f, 1f)
                val rpmRatio = ((car.rpm - 1000f) / 7000f).coerceIn(0f, 1f)
                val isRedline = car.rpm >= 6000f

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(72.dp)
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val strokeWidth = 5.5.dp.toPx()

                        // Outer Track
                        drawArc(
                            color = Color(0xFF1E293B),
                            startAngle = 135f,
                            sweepAngle = 270f,
                            useCenter = false,
                            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                        )

                        // RPM Fill Arc
                        val arcColor = if (isRedline) {
                            Color(0xFFEF4444).copy(alpha = redlineAlpha)
                        } else {
                            currentTheme.primaryColor
                        }

                        drawArc(
                            brush = Brush.horizontalGradient(
                                listOf(
                                    currentTheme.primaryColor,
                                    currentTheme.secondaryColor,
                                    Color(0xFFEF4444)
                                )
                            ),
                            startAngle = 135f,
                            sweepAngle = 270f * rpmRatio,
                            useCenter = false,
                            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                        )

                        // Inner Speed Line Arc
                        drawArc(
                            color = currentTheme.primaryColor.copy(alpha = 0.4f),
                            startAngle = 135f,
                            sweepAngle = 270f * speedRatio,
                            useCenter = false,
                            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }

                    // Center Digital Speed Numerals
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "${speed.toInt()}",
                            color = Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.clickable {
                                selectedUnit = if (selectedUnit == SpeedUnit.KMH) SpeedUnit.MPH else SpeedUnit.KMH
                            }
                        )
                        Text(
                            text = selectedUnit.label,
                            color = currentTheme.primaryColor,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Right Panel: Gear Shifter, RPM & Status Indicators
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Interactive Gear Shifter Display
                        Surface(
                            color = when {
                                car.gear == -1 -> Color(0xFFEF4444)
                                car.gear == 0 -> Color(0xFFF59E0B)
                                else -> currentTheme.primaryColor
                            },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onGearToggle() }
                                .testTag("gear_shifter_badge")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (car.gear == -1) "R" else "D${car.gear}",
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = FontFamily.Monospace
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (car.gear == -1) "REV" else "GEAR",
                                    color = Color.White.copy(alpha = 0.85f),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // RPM Digital Readout
                        Column {
                            Text(
                                text = "RPM",
                                color = Color(0xFF64748B),
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${car.rpm.toInt()}",
                                color = if (isRedline) Color(0xFFEF4444) else Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Status Warning Lights Row
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Handbrake Status Light
                        StatusLightBadge(
                            label = "(P)",
                            isActive = car.isHandbrakeActive,
                            activeColor = Color(0xFFEF4444)
                        )

                        // Drift Status Light
                        StatusLightBadge(
                            label = "DRIFT",
                            isActive = car.isDrifting,
                            activeColor = Color(0xFFF59E0B)
                        )

                        // Turbo Boost Load Indicator Light
                        StatusLightBadge(
                            label = "TURBO",
                            isActive = car.throttleInput > 0.6f && car.rpm > 3200f,
                            activeColor = currentTheme.primaryColor
                        )
                    }
                }
            }

            // Expanded Cluster Telemetry View (when toggled on)
            AnimatedVisibility(
                visible = isExpandedCluster,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    Spacer(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Color(0xFF334155))
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    // Throttle Bar & Drift Telemetry Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Throttle Gauge Bar
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("GAS / LOAD", color = Color(0xFF94A3B8), fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                Text("${(car.throttleInput * 100).toInt()}%", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(Color(0xFF1E293B))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(car.throttleInput.coerceIn(0f, 1f))
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(currentTheme.primaryColor)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        // Drift Score Counter
                        Column(horizontalAlignment = Alignment.End) {
                            Text("DRIFT SCORE", color = Color(0xFF94A3B8), fontSize = 8.sp, fontWeight = FontWeight.Bold)
                            Text(
                                text = "${car.driftScore} PTS",
                                color = Color(0xFFFACC15),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusLightBadge(
    label: String,
    isActive: Boolean,
    activeColor: Color
) {
    Surface(
        color = if (isActive) activeColor.copy(alpha = 0.25f) else Color(0xFF1E293B),
        shape = RoundedCornerShape(4.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isActive) activeColor else Color(0xFF334155)
        )
    ) {
        Text(
            text = label,
            color = if (isActive) activeColor else Color(0xFF64748B),
            fontSize = 8.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
        )
    }
}
