package com.example.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import com.example.engine.CameraMode
import com.example.engine.ControlMode
import com.example.engine.PhysicsEngine
import com.example.engine.SoundEngine
import com.example.model.VehicleType
import com.example.renderer.Renderer3D
import com.example.renderer.TimeOfDay

@Composable
fun GameScreen() {
    val physicsEngine = remember { PhysicsEngine() }
    val soundEngine = remember { SoundEngine() }
    val renderer3D = remember { Renderer3D() }

    var inGarage by remember { mutableStateOf(true) }
    var isPaused by remember { mutableStateOf(false) }
    var isMapExpanded by remember { mutableStateOf(false) }

    var controlMode by remember { mutableStateOf(ControlMode.STEERING_WHEEL) }
    var cameraMode by remember { mutableStateOf(CameraMode.CHASE_THIRD_PERSON) }
    var timeOfDay by remember { mutableStateOf(TimeOfDay.DAY) }
    var isMuted by remember { mutableStateOf(false) }

    var screenWidthPx by remember { mutableFloatStateOf(1080f) }
    var screenHeightPx by remember { mutableFloatStateOf(1920f) }
    var frameTick by remember { mutableLongStateOf(0L) }

    // Start Sound Engine
    DisposableEffect(Unit) {
        soundEngine.start()
        onDispose {
            soundEngine.stop()
        }
    }

    // 60 FPS Game Loop
    LaunchedEffect(isPaused, inGarage) {
        var lastNano = System.nanoTime()
        while (!isPaused && !inGarage) {
            withFrameNanos { currentNano ->
                val dtSec = ((currentNano - lastNano) / 1_000_000_000.0f).coerceIn(0.001f, 0.05f)
                lastNano = currentNano

                // Physics Step
                physicsEngine.update(dtSec)

                // Trigger Compose Recomposition for 60 FPS UI/Canvas update
                frameTick = currentNano

                // Sound Sync
                soundEngine.currentRpm = physicsEngine.car.rpm
                soundEngine.throttleInput = physicsEngine.car.throttleInput
                soundEngine.speedKmh = physicsEngine.car.speedKmh
                soundEngine.isDrifting = physicsEngine.car.isDrifting
                soundEngine.vehicleType = physicsEngine.car.vehicleType
                soundEngine.isMuted = isMuted

                if (physicsEngine.lastCollisionEvent != null) {
                    val event = physicsEngine.lastCollisionEvent!!
                    soundEngine.triggerCollision(event.intensity)
                    physicsEngine.lastCollisionEvent = null
                }
            }
        }
    }

    if (inGarage) {
        GarageScreen(
            carState = physicsEngine.car,
            onSelectVehicle = { selectedType, colorHex ->
                physicsEngine.car.vehicleType = selectedType
                physicsEngine.car.customBodyColorHex = colorHex
            },
            onStartDrive = {
                inGarage = false
            }
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onGloballyPositioned { coordinates ->
                    screenWidthPx = coordinates.size.width.toFloat()
                    screenHeightPx = coordinates.size.height.toFloat()
                }
                .testTag("game_screen")
        ) {
            // 3D Canvas Scene View
            Canvas(modifier = Modifier.fillMaxSize()) {
                @Suppress("UNUSED_VARIABLE")
                val tick = frameTick // Subscribe to frameTick for 60 FPS redraws

                renderer3D.timeOfDay = timeOfDay
                renderer3D.cameraMode = cameraMode

                renderer3D.render(
                    drawScope = this,
                    car = physicsEngine.car,
                    trafficCars = physicsEngine.trafficCars,
                    particles = physicsEngine.particles,
                    screenWidthPx = screenWidthPx,
                    screenHeightPx = screenHeightPx,
                    screenShakeOffsetX = physicsEngine.screenShakeOffsetX,
                    screenShakeOffsetY = physicsEngine.screenShakeOffsetY
                )
            }

            // HUD Overlay
            GameHUD(
                car = physicsEngine.car,
                frameTick = frameTick,
                controlMode = controlMode,
                cameraMode = cameraMode,
                timeOfDay = timeOfDay,
                isMuted = isMuted,
                isPaused = isPaused,
                onSteerInput = { steer ->
                    physicsEngine.car.targetSteeringAngleRad = steer * 0.60f
                },
                onThrottleInput = { throttle ->
                    physicsEngine.car.throttleInput = throttle
                },
                onBrakeInput = { brake ->
                    physicsEngine.car.brakeInput = brake
                },
                onHandbrakeInput = { handbrake ->
                    physicsEngine.car.isHandbrakeActive = handbrake
                },
                onHornInput = { horn ->
                    soundEngine.isHornActive = horn
                },
                onGearToggle = {
                    physicsEngine.car.gear = if (physicsEngine.car.gear == -1) 1 else -1
                },
                onCameraSwitch = {
                    cameraMode = when (cameraMode) {
                        CameraMode.CHASE_THIRD_PERSON -> CameraMode.OVERHEAD_DRONE
                        CameraMode.OVERHEAD_DRONE -> CameraMode.HOOD_FIRST_PERSON
                        CameraMode.HOOD_FIRST_PERSON -> CameraMode.CHASE_THIRD_PERSON
                    }
                },
                onTimeOfDayToggle = {
                    timeOfDay = when (timeOfDay) {
                        TimeOfDay.DAWN -> TimeOfDay.DAY
                        TimeOfDay.DAY -> TimeOfDay.SUNSET
                        TimeOfDay.SUNSET -> TimeOfDay.DUSK
                        TimeOfDay.DUSK -> TimeOfDay.NIGHT
                        TimeOfDay.NIGHT -> TimeOfDay.DAWN
                    }
                    renderer3D.isAutoCycleActive = false
                },
                onSoundToggle = {
                    isMuted = !isMuted
                },
                onResetCar = {
                    physicsEngine.resetCarPosition()
                },
                onPauseToggle = {
                    isPaused = !isPaused
                },
                onExpandMapClick = {
                    isMapExpanded = true
                }
            )

            // Dialogs
            if (isMapExpanded) {
                ExpandedMapModal(
                    car = physicsEngine.car,
                    onClose = { isMapExpanded = false }
                )
            }

            if (isPaused) {
                PauseSettingsDialog(
                    controlMode = controlMode,
                    timeOfDay = timeOfDay,
                    isMuted = isMuted,
                    onControlModeChange = { mode -> controlMode = mode },
                    onTimeOfDayChange = { tod ->
                        timeOfDay = tod
                        renderer3D.isAutoCycleActive = false
                    },
                    onSoundToggle = { isMuted = !isMuted },
                    onResetCar = { physicsEngine.resetCarPosition() },
                    onOpenGarage = {
                        isPaused = false
                        inGarage = true
                    },
                    onResume = { isPaused = false }
                )
            }
        }
    }
}
