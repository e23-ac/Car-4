package com.example.ui

import kotlin.math.abs
import kotlin.math.atan2
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.CameraMode
import com.example.engine.CarState
import com.example.engine.ControlMode
import com.example.renderer.TimeOfDay

fun Modifier.holdTouchInput(onPressedStateChange: (Boolean) -> Unit): Modifier = this.pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        onPressedStateChange(true)
        val pointerId = down.id
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == pointerId }
            if (change != null && change.pressed) {
                change.consume()
            } else {
                break
            }
        }
        onPressedStateChange(false)
    }
}

@Composable
fun GameHUD(
    car: CarState,
    frameTick: Long,
    controlMode: ControlMode,
    cameraMode: CameraMode,
    timeOfDay: TimeOfDay,
    isMuted: Boolean,
    isPaused: Boolean,
    onSteerInput: (Float) -> Unit,
    onThrottleInput: (Float) -> Unit,
    onBrakeInput: (Float) -> Unit,
    onHandbrakeInput: (Boolean) -> Unit,
    onHornInput: (Boolean) -> Unit,
    onGearToggle: () -> Unit,
    onCameraSwitch: () -> Unit,
    onTimeOfDayToggle: () -> Unit,
    onSoundToggle: () -> Unit,
    onResetCar: () -> Unit,
    onPauseToggle: () -> Unit,
    onExpandMapClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    @Suppress("UNUSED_VARIABLE")
    val tick = frameTick

    Box(modifier = modifier.fillMaxSize()) {
        // TOP HUD BAR
        TopHUDBar(
            car = car,
            cameraMode = cameraMode,
            timeOfDay = timeOfDay,
            isMuted = isMuted,
            onCameraSwitch = onCameraSwitch,
            onTimeOfDayToggle = onTimeOfDayToggle,
            onSoundToggle = onSoundToggle,
            onResetCar = onResetCar,
            onPauseToggle = onPauseToggle,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 16.dp, start = 14.dp, end = 14.dp)
        )

        // SPEEDOMETER HUD TOP LEFT (NO OVERLAP WITH MAP)
        SpeedometerHUD(
            car = car,
            onGearToggle = onGearToggle,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 68.dp, start = 14.dp)
        )

        // MINIMAP TOP RIGHT (NO OVERLAP WITH SPEEDOMETER)
        MinimapView(
            car = car,
            onExpandMapClick = onExpandMapClick,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 68.dp, end = 14.dp)
        )

        // DRIFT BADGE NOTIFICATION
        if (car.isDrifting) {
            Surface(
                color = Color(0xEEEF4444),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(2.dp, Color.White),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 135.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🔥 دریفت / DRIFTING!", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Black)
                }
            }
        }

        // BOTTOM STEERING CONTROLS (LEFT SIDE)
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 20.dp, bottom = 18.dp)
        ) {
            if (controlMode == ControlMode.STEERING_WHEEL) {
                SteeringWheelControl(onSteerInput = onSteerInput)
            } else {
                ArrowButtonsControl(onSteerInput = onSteerInput)
            }
        }

        // BOTTOM PEDALS & HANDBRAKE (RIGHT SIDE)
        PedalsControl(
            onThrottleInput = onThrottleInput,
            onBrakeInput = onBrakeInput,
            onHandbrakeInput = onHandbrakeInput,
            onHornInput = onHornInput,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 18.dp)
        )
    }
}

@Composable
fun TopHUDBar(
    car: CarState,
    cameraMode: CameraMode,
    timeOfDay: TimeOfDay,
    isMuted: Boolean,
    onCameraSwitch: () -> Unit,
    onTimeOfDayToggle: () -> Unit,
    onSoundToggle: () -> Unit,
    onResetCar: () -> Unit,
    onPauseToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Location & Pride Badge
        Surface(
            color = Color(0xEE0F172A),
            shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF38BDF8))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF22C55E))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "${car.vehicleType.displayNameFa} | ${car.vehicleType.displayNameEn}",
                        color = Color(0xFF38BDF8),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        text = car.currentStreetName,
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Action Buttons
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            IconButton(
                onClick = onCameraSwitch,
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Color(0xEE1E293B))
                    .testTag("camera_switch_btn")
            ) {
                Icon(Icons.Default.Cameraswitch, contentDescription = "Camera", tint = Color.White, modifier = Modifier.size(20.dp))
            }

            IconButton(
                onClick = onTimeOfDayToggle,
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Color(0xEE1E293B))
                    .testTag("time_toggle_btn")
            ) {
                val icon = when (timeOfDay) {
                    TimeOfDay.DAWN -> Icons.Default.WbSunny
                    TimeOfDay.DAY -> Icons.Default.WbSunny
                    TimeOfDay.SUNSET -> Icons.Default.LightMode
                    TimeOfDay.DUSK -> Icons.Default.NightsStay
                    TimeOfDay.NIGHT -> Icons.Default.NightsStay
                }
                Icon(icon, contentDescription = "Time", tint = Color(0xFFFACC15), modifier = Modifier.size(20.dp))
            }

            IconButton(
                onClick = onSoundToggle,
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Color(0xEE1E293B))
                    .testTag("sound_toggle_btn")
            ) {
                Icon(
                    if (isMuted) Icons.AutoMirrored.Filled.VolumeMute else Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = "Sound",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }

            IconButton(
                onClick = onResetCar,
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Color(0xEE1E293B))
                    .testTag("reset_car_btn")
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Reset", tint = Color(0xFF38BDF8), modifier = Modifier.size(20.dp))
            }

            IconButton(
                onClick = onPauseToggle,
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Color(0xEE0F172A))
                    .testTag("pause_btn")
            ) {
                Icon(Icons.Default.Pause, contentDescription = "Pause", tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
fun SpeedometerHUD(car: CarState, onGearToggle: () -> Unit, modifier: Modifier = Modifier) {
    DigitalDashboard(
        car = car,
        onGearToggle = onGearToggle,
        modifier = modifier
    )
}

@Composable
fun SteeringWheelControl(onSteerInput: (Float) -> Unit) {
    var rotationAngle by remember { mutableFloatStateOf(0f) }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Angle & Indicator Tag
        Surface(
            color = Color(0xEE0F172A),
            shape = RoundedCornerShape(10.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF38BDF8))
        ) {
            Text(
                text = when {
                    rotationAngle < -5f -> "◄ چپ (${(-rotationAngle).toInt()}°)"
                    rotationAngle > 5f -> "راست (${rotationAngle.toInt()}°) ►"
                    else -> "◄ مستقیم ►"
                },
                color = if (abs(rotationAngle) > 5f) Color(0xFF38BDF8) else Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(150.dp)
                .clip(CircleShape)
                .background(Color(0xEE020617))
                .border(3.5.dp, if (abs(rotationAngle) > 10f) Color(0xFF38BDF8) else Color(0xFF475569), CircleShape)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val cx = size.width / 2f
                        val cy = size.height / 2f

                        var prevTouchAngleRad = atan2(
                            (down.position.y - cy).toDouble(),
                            (down.position.x - cx).toDouble()
                        )

                        val pointerId = down.id
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == pointerId }
                            if (change != null) {
                                if (change.pressed) {
                                    change.consume()
                                    val currentTouchAngleRad = atan2(
                                        (change.position.y - cy).toDouble(),
                                        (change.position.x - cx).toDouble()
                                    )
                                    var diffRad = currentTouchAngleRad - prevTouchAngleRad
                                    if (diffRad.isNaN() || diffRad.isInfinite()) diffRad = 0.0
                                    while (diffRad > Math.PI) diffRad -= 2 * Math.PI
                                    while (diffRad < -Math.PI) diffRad += 2 * Math.PI

                                    val diffDeg = Math.toDegrees(diffRad).toFloat()
                                    val newAngle = (rotationAngle + diffDeg * 1.25f).coerceIn(-180f, 180f)
                                    rotationAngle = newAngle
                                    prevTouchAngleRad = currentTouchAngleRad

                                    val steerNormalized = (newAngle / 180f).coerceIn(-1f, 1f)
                                    onSteerInput(steerNormalized)
                                } else {
                                    break
                                }
                            }
                        }

                        // Smooth snap back
                        rotationAngle = 0f
                        onSteerInput(0f)
                    }
                }
                .testTag("steering_wheel")
        ) {
            Canvas(modifier = Modifier.fillMaxSize().rotate(rotationAngle)) {
                val w = size.width
                val h = size.height
                val center = Offset(w / 2f, h / 2f)

                // 1. Outer Heavy Steering Rim (Leather Grip)
                drawCircle(
                    color = Color(0xFF1E293B),
                    radius = w * 0.44f,
                    style = Stroke(width = w * 0.11f)
                )

                // 2. Chrome Inner Accent Ring
                drawCircle(
                    color = Color(0xFF38BDF8),
                    radius = w * 0.41f,
                    style = Stroke(width = 3.5f)
                )

                // 3. Top Center Red Alignment Marker (Rally Style)
                drawArc(
                    color = Color(0xFFEF4444),
                    startAngle = 262f,
                    sweepAngle = 16f,
                    useCenter = false,
                    style = Stroke(width = w * 0.11f)
                )

                // 4. Center Metallic Horn Hub
                drawCircle(
                    color = Color(0xFF0F172A),
                    radius = w * 0.22f
                )
                drawCircle(
                    color = Color(0xFF38BDF8),
                    radius = w * 0.22f,
                    style = Stroke(width = 3.5f)
                )

                // 5. 3 Thick Metallic Spokes
                drawLine(
                    color = Color(0xFF64748B),
                    start = center,
                    end = Offset(w * 0.08f, h * 0.5f),
                    strokeWidth = w * 0.08f
                )
                drawLine(
                    color = Color(0xFF64748B),
                    start = center,
                    end = Offset(w * 0.92f, h * 0.5f),
                    strokeWidth = w * 0.08f
                )
                drawLine(
                    color = Color(0xFF64748B),
                    start = center,
                    end = Offset(w * 0.5f, h * 0.92f),
                    strokeWidth = w * 0.08f
                )
            }

            // Center Badge
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "SAIPA",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black
                )
                Text(
                    text = "سایپا",
                    color = Color(0xFF38BDF8),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun ArrowButtonsControl(onSteerInput: (Float) -> Unit) {
    var isLeftPressed by remember { mutableStateOf(false) }
    var isRightPressed by remember { mutableStateOf(false) }

    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(if (isLeftPressed) Color(0xFF0284C7) else Color(0xEE0F172A))
                .border(2.5.dp, Color(0xFF38BDF8), CircleShape)
                .holdTouchInput { pressed ->
                    isLeftPressed = pressed
                    if (pressed) onSteerInput(-1.0f) else if (!isRightPressed) onSteerInput(0.0f)
                }
                .testTag("steer_left_btn")
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Left", tint = Color.White, modifier = Modifier.size(28.dp))
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(if (isRightPressed) Color(0xFF0284C7) else Color(0xEE0F172A))
                .border(2.5.dp, Color(0xFF38BDF8), CircleShape)
                .holdTouchInput { pressed ->
                    isRightPressed = pressed
                    if (pressed) onSteerInput(1.0f) else if (!isLeftPressed) onSteerInput(0.0f)
                }
                .testTag("steer_right_btn")
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Right", tint = Color.White, modifier = Modifier.size(28.dp))
        }
    }
}

@Composable
fun PedalsControl(
    onThrottleInput: (Float) -> Unit,
    onBrakeInput: (Float) -> Unit,
    onHandbrakeInput: (Boolean) -> Unit = {},
    onHornInput: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var isGasPressed by remember { mutableStateOf(false) }
    var isBrakePressed by remember { mutableStateOf(false) }
    var isHandbrakePressed by remember { mutableStateOf(false) }
    var isHornPressed by remember { mutableStateOf(false) }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        // Horn & Handbrake Aux Controls Column
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Horn Button (بوق)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(if (isHornPressed) Color(0xFFEAB308) else Color(0xEE0F172A))
                    .border(2.dp, if (isHornPressed) Color(0xFFFEF08A) else Color(0xFFEAB308), CircleShape)
                    .holdTouchInput { pressed ->
                        isHornPressed = pressed
                        onHornInput(pressed)
                    }
                    .testTag("horn_btn")
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "📢", fontSize = 15.sp)
                    Text(text = "بوق", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }

            // Handbrake Button (دستی / DRIFT)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(width = 62.dp, height = 62.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (isHandbrakePressed)
                            Brush.verticalGradient(listOf(Color(0xFFEA580C), Color(0xFF9A3412)))
                        else
                            Brush.verticalGradient(listOf(Color(0xFF1E293B), Color(0xFF0F172A)))
                    )
                    .border(
                        width = 2.dp,
                        color = if (isHandbrakePressed) Color(0xFFFB923C) else Color(0xFF64748B),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .holdTouchInput { pressed ->
                        isHandbrakePressed = pressed
                        onHandbrakeInput(pressed)
                    }
                    .testTag("handbrake_btn")
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "(P)",
                        color = if (isHandbrakePressed) Color.White else Color(0xFFFB923C),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        text = "دستی",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Realistic Brake Pedal / پدال ترمز
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(width = 86.dp, height = 112.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    if (isBrakePressed)
                        Brush.verticalGradient(listOf(Color(0xFFDC2626), Color(0xFF7F1D1D)))
                    else
                        Brush.verticalGradient(listOf(Color(0xFF1E293B), Color(0xFF0F172A)))
                )
                .border(
                    width = 2.5.dp,
                    color = if (isBrakePressed) Color(0xFFF87171) else Color(0xFF475569),
                    shape = RoundedCornerShape(14.dp)
                )
                .holdTouchInput { pressed ->
                    isBrakePressed = pressed
                    onBrakeInput(if (pressed) 1f else 0f)
                }
                .testTag("brake_pedal")
        ) {
            // Anti-slip rubber ridges texture overlay
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val ridgeColor = if (isBrakePressed) Color(0x66FFFFFF) else Color(0x3364748B)
                // Draw 5 horizontal rubber grip lines
                for (i in 1..5) {
                    val y = h * (i / 6f)
                    drawLine(
                        color = ridgeColor,
                        start = Offset(w * 0.15f, y),
                        end = Offset(w * 0.85f, y),
                        strokeWidth = 3f,
                        cap = StrokeCap.Round
                    )
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "ترمز",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Black
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "BRAKE",
                    color = if (isBrakePressed) Color.White else Color(0xFFF87171),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Realistic Gas Pedal / پدال گاز
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(width = 88.dp, height = 142.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    if (isGasPressed)
                        Brush.verticalGradient(listOf(Color(0xFF16A34A), Color(0xFF14532D)))
                    else
                        Brush.verticalGradient(listOf(Color(0xFF1E293B), Color(0xFF0F172A)))
                )
                .border(
                    width = 2.5.dp,
                    color = if (isGasPressed) Color(0xFF4ADE80) else Color(0xFF475569),
                    shape = RoundedCornerShape(16.dp)
                )
                .holdTouchInput { pressed ->
                    isGasPressed = pressed
                    onThrottleInput(if (pressed) 1f else 0f)
                }
                .testTag("gas_pedal")
        ) {
            // Anti-slip vertical rubber grooves texture overlay
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val grooveColor = if (isGasPressed) Color(0x66FFFFFF) else Color(0x3364748B)
                // Draw 4 vertical rubber grip lines
                for (i in 1..4) {
                    val x = w * (i / 5f)
                    drawLine(
                        color = grooveColor,
                        start = Offset(x, h * 0.15f),
                        end = Offset(x, h * 0.85f),
                        strokeWidth = 3f,
                        cap = StrokeCap.Round
                    )
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "گاز",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Black
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "GAS ▲",
                    color = if (isGasPressed) Color.White else Color(0xFF4ADE80),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
