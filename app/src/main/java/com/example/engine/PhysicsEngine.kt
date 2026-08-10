package com.example.engine

import com.example.model.Building3D
import com.example.model.DecalStyle
import com.example.model.RimStyle
import com.example.model.SatelliteMapData
import com.example.model.SpoilerStyle
import com.example.model.TintLevel
import com.example.model.VehicleType
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

enum class ControlMode {
    STEERING_WHEEL,
    BUTTONS,
    TILT_ACCELEROMETER
}

enum class CameraMode {
    CHASE_THIRD_PERSON,
    OVERHEAD_DRONE,
    HOOD_FIRST_PERSON
}

enum class ParticleType {
    SMOKE,
    SPARK,
    DEBRIS,
    FIRE
}

data class Particle(
    var pos: Vec3,
    var vel: Vec3,
    var size: Float,
    var alpha: Float,
    var colorHex: Long,
    var lifetimeSec: Float,
    var maxLifetimeSec: Float,
    var type: ParticleType = ParticleType.SMOKE
)

enum class CollisionTargetType {
    BUILDING,
    TREE,
    TRAFFIC_CAR,
    STATIC_OBJECT
}

data class CollisionEvent(
    val pos: Vec3,
    val intensity: Float,
    val targetType: CollisionTargetType = CollisionTargetType.BUILDING,
    val timestamp: Long = System.currentTimeMillis()
)

data class TrafficCarState(
    val id: Int,
    val vehicleType: VehicleType,
    val colorHex: Long,
    var pos: Vec3,
    var headingAngleRad: Float,
    val speedMs: Float,
    val minBound: Vec3,
    val maxBound: Vec3
)

class CarState {
    var vehicleType: VehicleType = VehicleType.PRIDE_131
        set(value) {
            field = value
            customBodyColorHex = value.defaultColorHex
        }

    var customBodyColorHex: Long = VehicleType.PRIDE_131.defaultColorHex

    var selectedRimStyle: RimStyle = RimStyle.STOCK
    var selectedDecalStyle: DecalStyle = DecalStyle.NONE
    var selectedSpoilerStyle: SpoilerStyle = SpoilerStyle.DEFAULT
    var selectedTintLevel: TintLevel = TintLevel.MEDIUM_TINT

    var pos = Vec3(0f, 0.25f, 12f) // Start right on the Central Boulevard south lane
    var vel = Vec3(0f, 0f, 0f)
    var headingAngleRad: Float = 1.5707963f // Facing East (+X) along central boulevard
    var pitchAngleRad: Float = 0f
    var rollAngleRad: Float = 0f

    var speedKmh: Float = 0f
    var steeringAngleRad: Float = 0f
    var targetSteeringAngleRad: Float = 0f

    var throttleInput: Float = 0f // 0..1
    var brakeInput: Float = 0f // 0..1
    var isHandbrakeActive: Boolean = false

    var gear: Int = 1 // 1..5, -1 for Reverse
    var rpm: Float = 1000f

    var isDrifting: Boolean = false
    var driftScore: Int = 0

    // Dynamic car physical dimensions (meters)
    val width: Float get() = vehicleType.width
    val length: Float get() = vehicleType.length
    val height: Float get() = vehicleType.height

    // Visual animation states
    var wheelRotationRad: Float = 0f
    var frontWheelAngleRad: Float = 0f

    // Location info
    var currentStreetName: String = "بلوار مرکزی (Central Boulevard)"
}

class PhysicsEngine {
    val car = CarState()
    val particles = mutableListOf<Particle>()
    val trafficCars = mutableListOf<TrafficCarState>()
    var lastCollisionEvent: CollisionEvent? = null

    var screenShakeIntensity: Float = 0f
    var screenShakeOffsetX: Float = 0f
    var screenShakeOffsetY: Float = 0f

    init {
        initTrafficCars()
    }

    private fun initTrafficCars() {
        trafficCars.clear()
        // Eastbound Central Boulevard (+X)
        trafficCars.add(
            TrafficCarState(
                id = 1,
                vehicleType = VehicleType.PEUGEOT_405,
                colorHex = 0xFF94A3B8,
                pos = Vec3(-180f, 0.25f, 15f),
                headingAngleRad = 1.5707963f,
                speedMs = 12f,
                minBound = Vec3(-200f, 0f, 15f),
                maxBound = Vec3(200f, 0f, 15f)
            )
        )
        trafficCars.add(
            TrafficCarState(
                id = 2,
                vehicleType = VehicleType.PEUGEOT_PARS,
                colorHex = 0xFF0F172A,
                pos = Vec3(-60f, 0.25f, 15f),
                headingAngleRad = 1.5707963f,
                speedMs = 14f,
                minBound = Vec3(-200f, 0f, 15f),
                maxBound = Vec3(200f, 0f, 15f)
            )
        )
        trafficCars.add(
            TrafficCarState(
                id = 3,
                vehicleType = VehicleType.PRIDE_131,
                colorHex = 0xFFF8FAFC,
                pos = Vec3(80f, 0.25f, 15f),
                headingAngleRad = 1.5707963f,
                speedMs = 11f,
                minBound = Vec3(-200f, 0f, 15f),
                maxBound = Vec3(200f, 0f, 15f)
            )
        )

        // Westbound Central Boulevard (-X)
        trafficCars.add(
            TrafficCarState(
                id = 4,
                vehicleType = VehicleType.DENA_PLUS,
                colorHex = 0xFF1E3A8A,
                pos = Vec3(180f, 0.25f, -15f),
                headingAngleRad = -1.5707963f,
                speedMs = 13f,
                minBound = Vec3(-200f, 0f, -15f),
                maxBound = Vec3(200f, 0f, -15f)
            )
        )
        trafficCars.add(
            TrafficCarState(
                id = 5,
                vehicleType = VehicleType.TOYOTA_LAND_CRUISER,
                colorHex = 0xFFF8FAFC,
                pos = Vec3(40f, 0.35f, -15f),
                headingAngleRad = -1.5707963f,
                speedMs = 15f,
                minBound = Vec3(-200f, 0f, -15f),
                maxBound = Vec3(200f, 0f, -15f)
            )
        )
        trafficCars.add(
            TrafficCarState(
                id = 6,
                vehicleType = VehicleType.TOYOTA_HILUX,
                colorHex = 0xFFDC2626,
                pos = Vec3(-100f, 0.35f, -15f),
                headingAngleRad = -1.5707963f,
                speedMs = 12f,
                minBound = Vec3(-200f, 0f, -15f),
                maxBound = Vec3(200f, 0f, -15f)
            )
        )

        // Side Avenues (North / South)
        trafficCars.add(
            TrafficCarState(
                id = 7,
                vehicleType = VehicleType.PRIDE_131,
                colorHex = 0xFFEAB308,
                pos = Vec3(-80f, 0.25f, -180f),
                headingAngleRad = 0f,
                speedMs = 10f,
                minBound = Vec3(-80f, 0f, -220f),
                maxBound = Vec3(-80f, 0f, 180f)
            )
        )
        trafficCars.add(
            TrafficCarState(
                id = 8,
                vehicleType = VehicleType.PEUGEOT_405,
                colorHex = 0xFF15803D,
                pos = Vec3(80f, 0.25f, 180f),
                headingAngleRad = 3.14159f,
                speedMs = 11f,
                minBound = Vec3(80f, 0f, -220f),
                maxBound = Vec3(80f, 0f, 180f)
            )
        )
    }

    // Dynamic Constants based on current selected vehicle
    private val maxSpeedForward: Float get() = car.vehicleType.maxSpeedKmh
    private val maxSpeedReverse = -48f // km/h
    private val maxSteerAngle = 0.65f // ~37 degrees
    private val accelerationForce: Float get() = car.vehicleType.accelerationForce
    private val brakingForce = 72f
    private val handbrakeGripDrop = 0.25f
    private val gravity = 9.81f

    fun resetCarPosition() {
        car.pos = Vec3(0f, 0.25f, 12f)
        car.vel = Vec3(0f, 0f, 0f)
        car.headingAngleRad = 1.5707963f // Facing East (+X)
        car.pitchAngleRad = 0f
        car.rollAngleRad = 0f
        car.speedKmh = 0f
        car.steeringAngleRad = 0f
        car.targetSteeringAngleRad = 0f
        car.throttleInput = 0f
        car.brakeInput = 0f
        car.rpm = 1000f
        car.gear = 1
        car.isDrifting = false
        particles.clear()
    }

    fun update(dtSec: Float) {
        val dt = dtSec.coerceIn(0.001f, 0.05f)

        // 1. Steering smooth interpolation
        val vt = car.vehicleType
        val steerSpeed = 20.0f * vt.handlingRating
        car.steeringAngleRad += (car.targetSteeringAngleRad - car.steeringAngleRad) * min(1f, steerSpeed * dt)
        car.frontWheelAngleRad = car.steeringAngleRad

        // 2. Current Car Orientation Directions
        val forwardDir = Vec3(sin(car.headingAngleRad), 0f, cos(car.headingAngleRad))
        val rightDir = Vec3(cos(car.headingAngleRad), 0f, -sin(car.headingAngleRad))

        // Decompose velocity into forward (longitudinal) and lateral (transverse) speeds
        var forwardSpeed = car.vel.dot(forwardDir)
        var lateralSpeed = car.vel.dot(rightDir)

        // 3. Engine Throttle, Turbo Boost, Braking & Reverse
        val turboMultiplier = if (car.rpm > 3200f) vt.turboBoostFactor else 1.0f
        val effectiveAccelForce = accelerationForce * turboMultiplier

        if (car.throttleInput > 0f) {
            if (forwardSpeed < -0.3f) {
                // Moving backwards -> Gas acts as strong brake to stop reverse motion
                forwardSpeed += car.throttleInput * brakingForce * dt
            } else {
                // Forward acceleration
                car.gear = if (car.speedKmh < 25f) 1 else car.gear.coerceAtLeast(1)
                val powerFactor = (1.0f - abs(car.speedKmh) / maxSpeedForward).coerceIn(0.20f, 1.0f)
                forwardSpeed += car.throttleInput * effectiveAccelForce * powerFactor * dt

                // Traction Control & Gas stabilization based on tractionGrip
                val gasStabilizeRate = 12.0f * vt.tractionGrip
                lateralSpeed *= (1.0f - gasStabilizeRate * dt).coerceIn(0f, 1f)
            }
        }

        if (car.brakeInput > 0f) {
            if (forwardSpeed > 0.3f) {
                // Moving forward -> Brake decelerates car
                forwardSpeed -= car.brakeInput * brakingForce * dt
            } else {
                // Reverse acceleration
                car.gear = -1
                val powerFactor = (1.0f - abs(car.speedKmh) / 45f).coerceIn(0.25f, 1.0f)
                forwardSpeed -= car.brakeInput * effectiveAccelForce * 0.75f * powerFactor * dt

                // Traction control in reverse
                lateralSpeed *= (1.0f - 14.0f * vt.tractionGrip * dt).coerceIn(0f, 1f)
            }
        }

        // Coasting friction when neutral
        if (car.throttleInput == 0f && car.brakeInput == 0f) {
            val friction = 10f * dt
            if (abs(forwardSpeed) <= friction) {
                forwardSpeed = 0f
            } else {
                val s = if (forwardSpeed > 0f) 1f else -1f
                forwardSpeed -= s * friction
            }
        }

        // Air drag
        forwardSpeed *= (1.0f - 0.15f * dt).coerceIn(0.90f, 1.0f)

        // 4. Advanced High-Speed Cornering & Realistic Drift Mechanics
        val wheelbase = car.length * 0.58f
        val directionSign = if (forwardSpeed >= -0.1f) 1f else -1f
        val steerFactor = tan(car.steeringAngleRad).coerceIn(-0.85f, 0.85f)
        val speedFactor = (abs(forwardSpeed) / (1.0f + abs(forwardSpeed) * 0.026f)).coerceIn(0f, 32f)

        // Base Ackermann Yaw Rate
        var yawRate = (speedFactor / wheelbase) * steerFactor * directionSign * vt.handlingRating

        // Centrifugal lateral acceleration exerted on tire contact patches
        val lateralAccel = abs(forwardSpeed * yawRate)
        val maxGripAccel = 11.0f * vt.tractionGrip

        // Drift initiation conditions:
        // 1) Handbrake pulled while moving (> 1 m/s)
        // 2) High speed turning exceeding tire cornering grip
        // 3) Power slide (heavy throttle + sharp turn at speed)
        // 4) Existing lateral slip angle
        val driftSlipThreshold = 3.0f / vt.driftMultiplier
        val isHandbrakeDrift = car.isHandbrakeActive && abs(forwardSpeed) > 1.2f
        val isPowerSlide = (car.throttleInput > 0.55f) && abs(car.steeringAngleRad) > 0.18f && abs(forwardSpeed) > 6.5f
        val isHighSpeedGripLoss = lateralAccel > maxGripAccel && abs(forwardSpeed) > 5.0f
        val isExistingSlip = abs(lateralSpeed) > driftSlipThreshold && abs(forwardSpeed) > 3.0f

        car.isDrifting = isHandbrakeDrift || isPowerSlide || isHighSpeedGripLoss || isExistingSlip

        if (car.isDrifting) {
            // Calculate lateral slip injection
            val slipForce = when {
                isHandbrakeDrift -> 15.0f * (1.0f / vt.tractionGrip) * dt
                isPowerSlide -> 10.0f * vt.driftMultiplier * dt
                else -> (lateralAccel - maxGripAccel).coerceAtLeast(0f) * 0.7f * dt
            }

            // Apply lateral slip in turn direction
            if (abs(car.steeringAngleRad) > 0.04f) {
                lateralSpeed += slipForce * sign(-steerFactor)
            } else if (isHandbrakeDrift && abs(lateralSpeed) < 2.5f) {
                // Handbrake flick without steering induces tail whip
                val whipDirection = if (car.steeringAngleRad != 0f) sign(-car.steeringAngleRad) else 1f
                lateralSpeed += 11.0f * (1.0f / vt.tractionGrip) * dt * whipDirection
            }

            // Counter-steering Control Mechanics:
            // When player steers opposite to lateral slide (sign(steering) != sign(lateralSpeed)),
            // counter-steering stabilizes the car's yaw spin and provides smooth drift angle control!
            val isCounterSteering = (lateralSpeed * car.steeringAngleRad) < -0.01f
            if (isCounterSteering) {
                yawRate *= 0.38f // Stabilize oversteer spin
                val counterDamp = 2.2f * vt.handlingRating
                lateralSpeed *= (1.0f - counterDamp * dt).coerceIn(0.68f, 1.0f)
            } else {
                // Not counter-steering: increase drift body angle (oversteer)
                yawRate *= (1.0f + 0.32f * vt.driftMultiplier)
            }

            // Dynamic Lateral Velocity Decay during Drift (Gliding sliding friction)
            val driftGripFactor = if (car.isHandbrakeActive) 0.15f else (0.42f / vt.driftMultiplier)
            val driftDampRate = (1.0f - driftGripFactor) * 7.5f * vt.tractionGrip
            lateralSpeed *= (1.0f - driftDampRate * dt).coerceIn(0.60f, 1.0f)

            // Accumulate Drift Score
            val totalSlipSpeed = sqrt(lateralSpeed * lateralSpeed + (if (isHandbrakeDrift) forwardSpeed * forwardSpeed * 0.25f else 0f))
            val driftGain = (totalSlipSpeed * abs(forwardSpeed) * 0.65f * vt.driftMultiplier * dt).toInt()
            if (driftGain > 0) {
                car.driftScore += driftGain
            }

            // Spawn Tire Smoke Particles
            val smokeIntensity = (totalSlipSpeed / 7.5f).coerceIn(0.4f, 1.8f)
            val rearLeft = car.pos - forwardDir * (car.length * 0.40f) - rightDir * (car.width * 0.42f)
            val rearRight = car.pos - forwardDir * (car.length * 0.40f) + rightDir * (car.width * 0.42f)

            spawnSmokeParticle(rearLeft, smokeIntensity)
            spawnSmokeParticle(rearRight, smokeIntensity)

            if (abs(lateralSpeed) > 6.5f) {
                val frontLeft = car.pos + forwardDir * (car.length * 0.38f) - rightDir * (car.width * 0.42f)
                val frontRight = car.pos + forwardDir * (car.length * 0.38f) + rightDir * (car.width * 0.42f)
                spawnSmokeParticle(frontLeft, smokeIntensity * 0.65f)
                spawnSmokeParticle(frontRight, smokeIntensity * 0.65f)
            }
        } else {
            // Normal Driving: High static tire grip erases lateral sliding quickly
            val normalGripDamp = 28.0f * vt.tractionGrip
            lateralSpeed *= (1.0f - normalGripDamp * dt).coerceIn(0f, 1f)
        }

        // Standing Launch Burnout Smoke Effect
        if (car.throttleInput > 0.85f && car.speedKmh < 20f && (car.isHandbrakeActive || car.brakeInput > 0.25f)) {
            val rearLeft = car.pos - forwardDir * (car.length * 0.40f) - rightDir * (car.width * 0.42f)
            val rearRight = car.pos - forwardDir * (car.length * 0.40f) + rightDir * (car.width * 0.42f)
            spawnSmokeParticle(rearLeft, 1.25f)
            spawnSmokeParticle(rearRight, 1.25f)
        }

        // 5. Apply Heading Angle Update from Yaw Rate
        if (abs(forwardSpeed) > 0.05f || car.isDrifting) {
            car.headingAngleRad += yawRate * dt
        }

        // 6. Reconstruct Velocity Vector locked strictly to Car Orientation
        val newForwardDir = Vec3(sin(car.headingAngleRad), 0f, cos(car.headingAngleRad))
        val newRightDir = Vec3(cos(car.headingAngleRad), 0f, -sin(car.headingAngleRad))

        car.vel = newForwardDir * forwardSpeed + newRightDir * lateralSpeed

        // 7. Update Position
        car.pos += car.vel * dt

        // Speed in km/h
        car.speedKmh = car.vel.length() * 3.6f

        // Wheel Rotation
        car.wheelRotationRad += (forwardSpeed / (car.length * 0.3f)) * dt

        // RPM calculation
        val targetRpm = when {
            car.throttleInput > 0f -> 2000f + (car.speedKmh % 35f) * 120f + car.throttleInput * 1500f
            else -> 1000f + abs(car.speedKmh) * 30f
        }.coerceIn(900f, 6800f)
        car.rpm += (targetRpm - car.rpm) * min(1f, dt * 8f)

        // Automatic Gear Shift logic
        if (car.gear > 0) {
            car.gear = when {
                car.speedKmh < 25f -> 1
                car.speedKmh < 50f -> 2
                car.speedKmh < 85f -> 3
                car.speedKmh < 125f -> 4
                else -> 5
            }
        }

        // 7. Check Ground Terrain Height & Suspension Ground Clamping
        val terrainHeight = getTerrainHeightAt(car.pos.x, car.pos.z)
        // Ensure car never clips below ground or road level (minimum Y is 0.2f above terrain)
        car.pos.y = maxOf(0.2f, terrainHeight + 0.2f)

        // Dynamic Suspension Pitch & Roll FX (Visual lean on accelerating/braking/turning)
        val accelMagnitude = (forwardSpeed - car.speedKmh / 3.6f) / dt
        val pitchDamp = 0.005f / vt.suspensionStiffness
        val rollDamp = 0.008f / vt.suspensionStiffness
        val targetPitch = (-accelMagnitude * pitchDamp).coerceIn(-0.08f, 0.08f)
        val targetRoll = (-lateralSpeed * rollDamp).coerceIn(-0.10f, 0.10f)

        car.pitchAngleRad += (targetPitch - car.pitchAngleRad) * min(1f, 10f * dt)
        car.rollAngleRad += (targetRoll - car.rollAngleRad) * min(1f, 10f * dt)

        // 8. 3D Collision Detection against Buildings, Trees, Traffic Cars & Objects
        checkBuildingCollisions()
        checkTreeCollisions()
        checkTrafficCarCollisions()
        checkStreetLightCollisions()
        checkMapBoundaries()

        // Screen Shake Dampening Decay
        if (screenShakeIntensity > 0.005f) {
            screenShakeOffsetX = (Math.random().toFloat() - 0.5f) * 2.5f * screenShakeIntensity
            screenShakeOffsetY = (Math.random().toFloat() - 0.5f) * 2.5f * screenShakeIntensity
            screenShakeIntensity *= (1.0f - 14.0f * dt).coerceIn(0f, 1f)
        } else {
            screenShakeIntensity = 0f
            screenShakeOffsetX = 0f
            screenShakeOffsetY = 0f
        }

        // 9. Update Active Particle System
        updateParticles(dt)

        // 10. Update Street Name display
        updateStreetName()

        // 11. Update AI Traffic Cars movement
        updateTrafficCars(dt)
    }

    private fun updateTrafficCars(dt: Float) {
        for (tCar in trafficCars) {
            val fwd = Vec3(sin(tCar.headingAngleRad), 0f, cos(tCar.headingAngleRad))
            tCar.pos = tCar.pos + fwd * (tCar.speedMs * dt)

            if (fwd.x > 0.5f && tCar.pos.x > tCar.maxBound.x) {
                tCar.pos = Vec3(tCar.minBound.x, tCar.pos.y, tCar.pos.z)
            } else if (fwd.x < -0.5f && tCar.pos.x < tCar.minBound.x) {
                tCar.pos = Vec3(tCar.maxBound.x, tCar.pos.y, tCar.pos.z)
            } else if (fwd.z > 0.5f && tCar.pos.z > tCar.maxBound.z) {
                tCar.pos = Vec3(tCar.pos.x, tCar.pos.y, tCar.minBound.z)
            } else if (fwd.z < -0.5f && tCar.pos.z < tCar.minBound.z) {
                tCar.pos = Vec3(tCar.pos.x, tCar.pos.y, tCar.maxBound.z)
            }
        }
    }

    private fun getTerrainHeightAt(x: Float, z: Float): Float {
        // Base ground level is 0
        var height = 0f
        for (tp in SatelliteMapData.terrains) {
            if (x >= tp.minX && x <= tp.maxX && z >= tp.minZ && z <= tp.maxZ) {
                val dx = min(x - tp.minX, tp.maxX - x) / ((tp.maxX - tp.minX) / 2f)
                val dz = min(z - tp.minZ, tp.maxZ - z) / ((tp.maxZ - tp.minZ) / 2f)
                val factor = (dx * dz).coerceIn(0f, 1f)
                height = max(height, tp.topHeight * factor)
            }
        }
        return height
    }

    private fun checkBuildingCollisions() {
        val halfL = car.length * 0.5f + 0.2f
        val halfW = car.width * 0.5f + 0.2f
        val cosA = cos(car.headingAngleRad)
        val sinA = sin(car.headingAngleRad)

        // 4 corners of car in world coordinates
        val corners = arrayOf(
            Vec3(car.pos.x + (-halfW * cosA - halfL * sinA), car.pos.y, car.pos.z + (-halfW * sinA + halfL * cosA)), // Front Left
            Vec3(car.pos.x + (halfW * cosA - halfL * sinA), car.pos.y, car.pos.z + (halfW * sinA + halfL * cosA)),  // Front Right
            Vec3(car.pos.x + (-halfW * cosA + halfL * sinA), car.pos.y, car.pos.z + (-halfW * sinA - halfL * cosA)), // Rear Left
            Vec3(car.pos.x + (halfW * cosA + halfL * sinA), car.pos.y, car.pos.z + (halfW * sinA - halfL * cosA))   // Rear Right
        )

        for (b in SatelliteMapData.buildings) {
            // Broadphase check
            val effectiveMargin = maxOf(halfL, halfW) + 0.5f
            if (car.pos.x < b.minX - effectiveMargin || car.pos.x > b.maxX + effectiveMargin ||
                car.pos.z < b.minZ - effectiveMargin || car.pos.z > b.maxZ + effectiveMargin
            ) {
                continue
            }

            var collided = false
            var pushX = 0f
            var pushZ = 0f
            var maxPenetration = 0f

            for (corner in corners) {
                if (corner.x >= b.minX && corner.x <= b.maxX &&
                    corner.z >= b.minZ && corner.z <= b.maxZ
                ) {
                    collided = true
                    val penLeft = corner.x - b.minX
                    val penRight = b.maxX - corner.x
                    val penTop = corner.z - b.minZ
                    val penBottom = b.maxZ - corner.z

                    val minPen = minOf(penLeft, penRight, penTop, penBottom)
                    if (minPen > maxPenetration) {
                        maxPenetration = minPen
                        when (minPen) {
                            penLeft -> { pushX = -penLeft; pushZ = 0f }
                            penRight -> { pushX = penRight; pushZ = 0f }
                            penTop -> { pushX = 0f; pushZ = -penTop }
                            penBottom -> { pushX = 0f; pushZ = penBottom }
                        }
                    }
                }
            }

            if (collided) {
                val impactIntensity = car.vel.length()
                if (impactIntensity > 1.2f) {
                    triggerImpact(car.pos, impactIntensity, CollisionTargetType.BUILDING)
                }

                // Resolve penetration
                car.pos.x += pushX
                car.pos.z += pushZ

                // Bounce & damp velocity
                if (pushX != 0f) {
                    car.vel.x = -car.vel.x * 0.25f
                }
                if (pushZ != 0f) {
                    car.vel.z = -car.vel.z * 0.25f
                }
            }
        }
    }

    private fun checkTreeCollisions() {
        for (tree in SatelliteMapData.trees) {
            val dx = car.pos.x - tree.position.x
            val dz = car.pos.z - tree.position.z
            val dist = sqrt(dx * dx + dz * dz)
            val minDist = (car.length * 0.38f) + tree.radius * 0.45f
            if (dist < minDist && dist > 0.001f) {
                val penetration = minDist - dist
                val nx = dx / dist
                val nz = dz / dist

                car.pos.x += nx * penetration
                car.pos.z += nz * penetration

                val impactSpeed = car.vel.length()
                if (impactSpeed > 1.2f) {
                    triggerImpact(car.pos, impactSpeed, CollisionTargetType.TREE)
                }

                val dot = car.vel.x * nx + car.vel.z * nz
                if (dot < 0f) {
                    car.vel.x -= 1.3f * dot * nx
                    car.vel.z -= 1.3f * dot * nz
                    car.vel *= 0.45f
                }
            }
        }
    }

    private fun checkTrafficCarCollisions() {
        for (tCar in trafficCars) {
            val dx = car.pos.x - tCar.pos.x
            val dz = car.pos.z - tCar.pos.z
            val dist = sqrt(dx * dx + dz * dz)
            val minDist = (car.length + tCar.vehicleType.length) * 0.42f
            if (dist < minDist && dist > 0.001f) {
                val penetration = minDist - dist
                val nx = dx / dist
                val nz = dz / dist

                car.pos.x += nx * penetration * 0.65f
                car.pos.z += nz * penetration * 0.65f

                val relVelX = car.vel.x - (sin(tCar.headingAngleRad) * tCar.speedMs)
                val relVelZ = car.vel.z - (cos(tCar.headingAngleRad) * tCar.speedMs)
                val relSpeed = sqrt(relVelX * relVelX + relVelZ * relVelZ)

                if (relSpeed > 1.2f) {
                    triggerImpact(car.pos, relSpeed, CollisionTargetType.TRAFFIC_CAR)
                }

                val dot = car.vel.x * nx + car.vel.z * nz
                if (dot < 0f) {
                    car.vel.x -= 1.4f * dot * nx
                    car.vel.z -= 1.4f * dot * nz
                    car.vel *= 0.50f
                }
            }
        }
    }

    private fun checkStreetLightCollisions() {
        for (light in SatelliteMapData.streetLights) {
            val dx = car.pos.x - light.position.x
            val dz = car.pos.z - light.position.z
            val dist = sqrt(dx * dx + dz * dz)
            val minDist = (car.length * 0.35f) + 0.8f
            if (dist < minDist && dist > 0.001f) {
                val penetration = minDist - dist
                val nx = dx / dist
                val nz = dz / dist

                car.pos.x += nx * penetration
                car.pos.z += nz * penetration

                val impactSpeed = car.vel.length()
                if (impactSpeed > 1.2f) {
                    triggerImpact(car.pos, impactSpeed, CollisionTargetType.STATIC_OBJECT)
                }

                val dot = car.vel.x * nx + car.vel.z * nz
                if (dot < 0f) {
                    car.vel.x -= 1.2f * dot * nx
                    car.vel.z -= 1.2f * dot * nz
                    car.vel *= 0.60f
                }
            }
        }
    }

    private fun triggerImpact(pos: Vec3, intensity: Float, targetType: CollisionTargetType) {
        lastCollisionEvent = CollisionEvent(pos, intensity, targetType)
        screenShakeIntensity = (intensity * 0.22f).coerceIn(0.20f, 2.2f)

        val particleCount = (intensity * 4f).toInt().coerceIn(6, 28)
        when (targetType) {
            CollisionTargetType.TREE -> {
                for (i in 0 until particleCount) {
                    spawnSparkParticle(pos)
                    spawnSmokeParticle(pos, intensity * 0.8f)
                    val leafColor = if (Math.random() > 0.3) 0xFF15803DL else 0xFF78350FL
                    spawnDebrisParticle(pos, leafColor, intensity)
                }
            }
            CollisionTargetType.TRAFFIC_CAR -> {
                for (i in 0 until particleCount) {
                    spawnSparkParticle(pos)
                    spawnSmokeParticle(pos, intensity * 1.2f)
                    spawnDebrisParticle(pos, 0xFFE2E8F0L, intensity)
                    if (intensity > 12f) {
                        spawnFireParticle(pos)
                    }
                }
            }
            CollisionTargetType.BUILDING, CollisionTargetType.STATIC_OBJECT -> {
                for (i in 0 until particleCount) {
                    spawnSparkParticle(pos)
                    spawnSmokeParticle(pos, intensity)
                    spawnDebrisParticle(pos, 0xFF94A3B8L, intensity)
                }
            }
        }
    }

    private fun checkMapBoundaries() {
        val minX = SatelliteMapData.MAP_MIN_X + 5f
        val maxX = SatelliteMapData.MAP_MAX_X - 5f
        val minZ = SatelliteMapData.MAP_MIN_Z + 5f
        val maxZ = SatelliteMapData.MAP_MAX_Z - 5f

        if (car.pos.x < minX) {
            car.pos.x = minX
            val impact = abs(car.vel.x)
            if (impact > 1.5f) triggerImpact(car.pos, impact, CollisionTargetType.STATIC_OBJECT)
            car.vel.x = abs(car.vel.x) * 0.2f
        } else if (car.pos.x > maxX) {
            car.pos.x = maxX
            val impact = abs(car.vel.x)
            if (impact > 1.5f) triggerImpact(car.pos, impact, CollisionTargetType.STATIC_OBJECT)
            car.vel.x = -abs(car.vel.x) * 0.2f
        }

        if (car.pos.z < minZ) {
            car.pos.z = minZ
            val impact = abs(car.vel.z)
            if (impact > 1.5f) triggerImpact(car.pos, impact, CollisionTargetType.STATIC_OBJECT)
            car.vel.z = abs(car.vel.z) * 0.2f
        } else if (car.pos.z > maxZ) {
            car.pos.z = maxZ
            val impact = abs(car.vel.z)
            if (impact > 1.5f) triggerImpact(car.pos, impact, CollisionTargetType.STATIC_OBJECT)
            car.vel.z = -abs(car.vel.z) * 0.2f
        }
    }

    private fun spawnSmokeParticle(pos: Vec3, intensity: Float = 1.0f) {
        if (particles.size > 280) return
        val rx = (Math.random() - 0.5).toFloat() * 0.45f
        val ry = (Math.random() * 0.35 + 0.15).toFloat()
        val rz = (Math.random() - 0.5).toFloat() * 0.45f

        val grey = (180 + (Math.random() * 65).toInt()).coerceIn(0, 255)
        val argb = 0xFF000000L or ((grey shl 16) or (grey shl 8) or grey).toLong()

        particles.add(
            Particle(
                pos = Vec3(pos.x + rx, pos.y + ry, pos.z + rz),
                vel = Vec3(rx * 1.8f, ry * 1.5f + 0.35f, rz * 1.8f),
                size = 0.65f * intensity.coerceAtLeast(0.5f),
                alpha = (0.75f + Math.random().toFloat() * 0.20f) * intensity.coerceIn(0.2f, 1.0f),
                colorHex = argb,
                lifetimeSec = 0.85f,
                maxLifetimeSec = 0.85f,
                type = ParticleType.SMOKE
            )
        )
    }

    private fun spawnSparkParticle(pos: Vec3) {
        if (particles.size > 320) return
        val rx = (Math.random() - 0.5).toFloat() * 7.5f
        val ry = (Math.random() * 4.5 + 1.2).toFloat()
        val rz = (Math.random() - 0.5).toFloat() * 7.5f

        particles.add(
            Particle(
                pos = Vec3(pos.x, pos.y + 0.4f, pos.z),
                vel = Vec3(rx, ry, rz),
                size = 0.35f,
                alpha = 1.0f,
                colorHex = if (Math.random() > 0.4) 0xFFFFCC00L else 0xFFFFA500L,
                lifetimeSec = 0.45f,
                maxLifetimeSec = 0.45f,
                type = ParticleType.SPARK
            )
        )
    }

    private fun spawnDebrisParticle(pos: Vec3, colorHex: Long, intensity: Float) {
        if (particles.size > 320) return
        val rx = (Math.random() - 0.5).toFloat() * 5.0f
        val ry = (Math.random() * 3.5 + 0.8).toFloat()
        val rz = (Math.random() - 0.5).toFloat() * 5.0f

        particles.add(
            Particle(
                pos = Vec3(pos.x, pos.y + 0.5f, pos.z),
                vel = Vec3(rx, ry, rz),
                size = 0.40f * intensity.coerceIn(0.5f, 1.5f),
                alpha = 0.95f,
                colorHex = colorHex,
                lifetimeSec = 0.65f,
                maxLifetimeSec = 0.65f,
                type = ParticleType.DEBRIS
            )
        )
    }

    private fun spawnFireParticle(pos: Vec3) {
        if (particles.size > 320) return
        val rx = (Math.random() - 0.5).toFloat() * 2.0f
        val ry = (Math.random() * 2.5 + 0.5).toFloat()
        val rz = (Math.random() - 0.5).toFloat() * 2.0f

        particles.add(
            Particle(
                pos = Vec3(pos.x, pos.y + 0.3f, pos.z),
                vel = Vec3(rx, ry, rz),
                size = 0.55f,
                alpha = 1.0f,
                colorHex = 0xFFEF4444L,
                lifetimeSec = 0.35f,
                maxLifetimeSec = 0.35f,
                type = ParticleType.FIRE
            )
        )
    }

    private fun updateParticles(dt: Float) {
        val iterator = particles.iterator()
        while (iterator.hasNext()) {
            val p = iterator.next()
            p.lifetimeSec -= dt
            if (p.lifetimeSec <= 0f) {
                iterator.remove()
            } else {
                p.pos += p.vel * dt
                when (p.type) {
                    ParticleType.SMOKE -> {
                        p.size += dt * 2.2f
                        p.vel.y += dt * 0.35f
                        p.vel.x *= (1.0f - 1.1f * dt).coerceAtLeast(0f)
                        p.vel.z *= (1.0f - 1.1f * dt).coerceAtLeast(0f)
                    }
                    ParticleType.SPARK -> {
                        p.size *= (1.0f - 0.8f * dt).coerceAtLeast(0.1f)
                        p.vel.y -= 9.8f * dt
                        p.vel.x *= (1.0f - 1.8f * dt).coerceAtLeast(0f)
                        p.vel.z *= (1.0f - 1.8f * dt).coerceAtLeast(0f)
                    }
                    ParticleType.DEBRIS -> {
                        p.vel.y -= 11.0f * dt
                        p.vel.x *= (1.0f - 1.5f * dt).coerceAtLeast(0f)
                        p.vel.z *= (1.0f - 1.5f * dt).coerceAtLeast(0f)
                    }
                    ParticleType.FIRE -> {
                        p.size += dt * 1.5f
                        p.vel.y += dt * 1.2f
                    }
                }
                val lifeProgress = (p.lifetimeSec / p.maxLifetimeSec).coerceIn(0f, 1f)
                p.alpha = (lifeProgress * lifeProgress).coerceIn(0f, 1f)
            }
        }
    }

    private fun updateStreetName() {
        val z = car.pos.z
        val x = car.pos.x

        car.currentStreetName = when {
            z in -20f..+20f -> "بلوار مرکزی دوطرفه (Central Boulevard)"
            z < -230f -> "مجتمع آپارتمانی آفتاب شمالی"
            z in -230f..-195f -> "خیابان آپارتمانی ۱ (شمالی)"
            z in -195f..-140f -> "خیابان آپارتمانی ۲ (شمالی)"
            z in -140f..-85f -> "خیابان آپارتمانی ۳ (شمالی)"
            z in -85f..-20f -> "خیابان آپارتمانی ۴ (شمالی)"
            z in +20f..+65f -> "راسته مغازه‌ها و فروشگاه‌ها (جنوبی)"
            z in +65f..+120f -> "راسته بازار و پاساژها (جنوبی)"
            z in +120f..+180f -> "خیابان تجاری جنوب"
            z > +180f -> "بزرگراه تجاری جنوب"
            x < -190f -> "کنارگذر غربی"
            x > +180f -> "کنارگذر شرقی"
            else -> "خیابان مرکزی"
        }
    }
}
