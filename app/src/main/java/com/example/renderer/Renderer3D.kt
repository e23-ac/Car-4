package com.example.renderer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import com.example.engine.CameraMode
import com.example.engine.CarState
import com.example.engine.Mat4
import com.example.engine.Particle
import com.example.engine.Vec3
import com.example.model.Building3D
import com.example.model.DecalStyle
import com.example.model.RimStyle
import com.example.model.RoofType
import com.example.model.SatelliteMapData
import com.example.model.SpoilerStyle
import com.example.model.TintLevel
import com.example.model.VehicleType
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

enum class TimeOfDay {
    DAWN,
    DAY,
    SUNSET,
    DUSK,
    NIGHT
}

class Polygon3D {
    val px = FloatArray(8)
    val py = FloatArray(8)
    var vertexCount: Int = 0
    var color: Color = Color.Black
    var isOutline: Boolean = false
    var outlineColor: Color = Color.Unspecified
    var avgZ: Float = 0f
    var layer: Float = 2.0f
}

class Renderer3D {
    var timeOfDay = TimeOfDay.DAY
    var cameraMode = CameraMode.CHASE_THIRD_PERSON
    var isAutoCycleActive = false
    var autoCycleProgress = 0.25f // 0.0=Dawn, 0.25=Day, 0.5=Sunset, 0.7=Dusk, 0.85=Night
    var headlightMode = 1 // 0 = Off, 1 = Low Beam (Auto), 2 = High Beam

    private val starPositions = Array(90) { i ->
        val rx = (i * 17 + 31) % 100 / 100f
        val ry = (i * 23 + 13) % 45 / 100f
        val rBrightness = 0.4f + ((i * 7) % 60) / 100f
        Triple(rx, ry, rBrightness)
    }

    private val polygonPool = Array(18000) { Polygon3D() }
    private var activePolygonCount = 0

    fun render(
        drawScope: DrawScope,
        car: CarState,
        trafficCars: List<com.example.engine.TrafficCarState> = emptyList(),
        particles: List<Particle>,
        screenWidthPx: Float,
        screenHeightPx: Float,
        screenShakeOffsetX: Float = 0f,
        screenShakeOffsetY: Float = 0f
    ) {
        val aspect = screenWidthPx / screenHeightPx.coerceAtLeast(1f)

        // 0. Auto Day-Night Cycle Timer
        if (isAutoCycleActive) {
            autoCycleProgress = (autoCycleProgress + 0.0004f) % 1.0f
            timeOfDay = when {
                autoCycleProgress < 0.15f -> TimeOfDay.DAWN
                autoCycleProgress < 0.45f -> TimeOfDay.DAY
                autoCycleProgress < 0.60f -> TimeOfDay.SUNSET
                autoCycleProgress < 0.72f -> TimeOfDay.DUSK
                else -> TimeOfDay.NIGHT
            }
        }

        // 1. Camera Eye & Target Position (with dynamic Screen Shake offset)
        val shakeVec = Vec3(screenShakeOffsetX * 0.15f, screenShakeOffsetY * 0.25f, screenShakeOffsetX * 0.15f)
        val cameraEye = getCameraEyePosition(car) + shakeVec
        val cameraTarget = getCameraTargetPosition(car) + Vec3(screenShakeOffsetX * 0.10f, screenShakeOffsetY * 0.10f, 0f)

        // 2. Atmosphere & Sky Rendering
        drawSkyBackground(drawScope, screenWidthPx, screenHeightPx, cameraEye, cameraTarget, aspect)

        activePolygonCount = 0

        // 3. 3D Vehicle Model
        collectCarPolygons(car, cameraEye, cameraTarget, aspect, screenWidthPx, screenHeightPx)

        // 3.5. Dynamic AI Traffic Vehicles on City Streets
        val dummyCarState = CarState()
        for (tCar in trafficCars) {
            val distSq = (tCar.pos.x - cameraEye.x) * (tCar.pos.x - cameraEye.x) + (tCar.pos.z - cameraEye.z) * (tCar.pos.z - cameraEye.z)
            if (distSq > 180f * 180f) continue

            dummyCarState.vehicleType = tCar.vehicleType
            dummyCarState.customBodyColorHex = tCar.colorHex
            dummyCarState.pos = tCar.pos
            dummyCarState.headingAngleRad = tCar.headingAngleRad
            dummyCarState.brakeInput = 0f

            collectCarPolygons(dummyCarState, cameraEye, cameraTarget, aspect, screenWidthPx, screenHeightPx)
        }

        // 4. Terrain Base & Surrounding Mountains
        collectTerrainPolygons(cameraEye, cameraTarget, aspect, screenWidthPx, screenHeightPx)

        // 5. Roads, Crosswalks & Lane Markings (LOD Culling)
        collectRoadPolygons(cameraEye, cameraTarget, aspect, screenWidthPx, screenHeightPx)

        // 6. Sidewalk Trees, Green Parks & Street Posts (LOD Culling)
        collectStreetDecorations(cameraEye, cameraTarget, aspect, screenWidthPx, screenHeightPx)

        // 7. 3D Detailed Buildings (Windows, Doors, Roofs) (LOD Culling)
        collectBuildingPolygons(cameraEye, cameraTarget, aspect, screenWidthPx, screenHeightPx)

        // 8. Painter's Depth Sorting (Layer first, then Far to Near)
        java.util.Arrays.sort(polygonPool, 0, activePolygonCount) { a, b ->
            if (a.layer != b.layer) {
                a.layer.compareTo(b.layer)
            } else {
                b.avgZ.compareTo(a.avgZ)
            }
        }

        // 9. Draw 3D Polygons to Canvas
        val path = Path()
        for (i in 0 until activePolygonCount) {
            val poly = polygonPool[i]
            val vc = poly.vertexCount
            if (vc < 3) continue
            path.reset()
            path.moveTo(poly.px[0], poly.py[0])
            for (v in 1 until vc) {
                path.lineTo(poly.px[v], poly.py[v])
            }
            path.close()

            drawScope.drawPath(path = path, color = poly.color, style = Fill)
            if (poly.isOutline) {
                drawScope.drawPath(
                    path = path,
                    color = poly.outlineColor,
                    style = Stroke(width = 1.2f)
                )
            }
        }

        // 10. Functional Headlight & Taillight Beams (User Car & Traffic Cars)
        val isNightOrDusk = timeOfDay == TimeOfDay.NIGHT || timeOfDay == TimeOfDay.DUSK || timeOfDay == TimeOfDay.DAWN
        val isHeadlightsOn = headlightMode == 2 || (headlightMode == 1 && isNightOrDusk)

        if (isHeadlightsOn || car.brakeInput > 0.05f) {
            drawVehicleLighting(
                carPos = car.pos,
                headingAngleRad = car.headingAngleRad,
                brakeInput = car.brakeInput,
                isHighBeam = (headlightMode == 2),
                eye = cameraEye, target = cameraTarget, aspect = aspect, screenW = screenWidthPx, screenH = screenHeightPx
            )
        }

        if (isNightOrDusk) {
            for (tCar in trafficCars) {
                val distSq = (tCar.pos.x - cameraEye.x) * (tCar.pos.x - cameraEye.x) + (tCar.pos.z - cameraEye.z) * (tCar.pos.z - cameraEye.z)
                if (distSq > 140f * 140f) continue
                drawVehicleLighting(
                    carPos = tCar.pos,
                    headingAngleRad = tCar.headingAngleRad,
                    brakeInput = 0f,
                    isHighBeam = false,
                    eye = cameraEye, target = cameraTarget, aspect = aspect, screenW = screenWidthPx, screenH = screenHeightPx,
                    isTrafficCar = true
                )
            }
        }

        // 11. Particles (Tire Smoke & Impact Sparks)
        renderParticles(drawScope, particles, cameraEye, cameraTarget, aspect, screenWidthPx, screenHeightPx)
    }

    private fun projectPoint(
        worldPoint: Vec3,
        cameraEye: Vec3,
        cameraTarget: Vec3,
        aspect: Float,
        fovYDeg: Float = 60f
    ): Vec3? {
        val dx = worldPoint.x - cameraEye.x
        val dy = worldPoint.y - cameraEye.y
        val dz = worldPoint.z - cameraEye.z

        val forward = (cameraTarget - cameraEye).normalized()
        var right = Vec3(0f, 1f, 0f).cross(forward).normalized()
        if (right.lengthSquared() < 0.0001f) {
            right = Vec3(1f, 0f, 0f)
        }
        val up = forward.cross(right).normalized()

        val zView = dx * forward.x + dy * forward.y + dz * forward.z
        if (zView < 0.4f) return null // Near plane clipping

        val xView = dx * right.x + dy * right.y + dz * right.z
        val yView = dx * up.x + dy * up.y + dz * up.z

        val tanFov = kotlin.math.tan(Math.toRadians(fovYDeg / 2.0)).toFloat()
        val f = 1.0f / tanFov

        val xNdc = (xView * f) / (aspect * zView)
        val yNdc = (yView * f) / zView

        return Vec3(xNdc, yNdc, zView)
    }

    private fun ndcToScreen(ndc: Vec3, screenW: Float, screenH: Float): Offset {
        val sx = (ndc.x + 1f) * 0.5f * screenW
        val sy = (1f - ndc.y) * 0.5f * screenH
        return Offset(sx, sy)
    }

    private fun getCameraEyePosition(car: CarState): Vec3 {
        val forwardDir = Vec3(sin(car.headingAngleRad), 0f, cos(car.headingAngleRad))
        val upDir = Vec3(0f, 1f, 0f)

        return when (cameraMode) {
            CameraMode.CHASE_THIRD_PERSON -> {
                car.pos - forwardDir * 11f + upDir * 4.8f
            }
            CameraMode.OVERHEAD_DRONE -> {
                car.pos - forwardDir * 2f + upDir * 28f
            }
            CameraMode.HOOD_FIRST_PERSON -> {
                car.pos + forwardDir * 0.7f + upDir * 1.1f
            }
        }
    }

    private fun getCameraTargetPosition(car: CarState): Vec3 {
        val forwardDir = Vec3(sin(car.headingAngleRad), 0f, cos(car.headingAngleRad))
        return when (cameraMode) {
            CameraMode.CHASE_THIRD_PERSON -> car.pos + forwardDir * 4f + Vec3(0f, 1.2f, 0f)
            CameraMode.OVERHEAD_DRONE -> car.pos + forwardDir * 2f
            CameraMode.HOOD_FIRST_PERSON -> car.pos + forwardDir * 25f + Vec3(0f, 0.9f, 0f)
        }
    }

    private fun drawSkyBackground(
        drawScope: DrawScope, width: Float, height: Float, eye: Vec3, target: Vec3, aspect: Float
    ) {
        val skyBrush = when (timeOfDay) {
            TimeOfDay.DAWN -> Brush.verticalGradient(
                colors = listOf(Color(0xFF1E1B4B), Color(0xFF7C2D12), Color(0xFFEA580C), Color(0xFFFEF08A))
            )
            TimeOfDay.DAY -> Brush.verticalGradient(
                colors = listOf(Color(0xFF0284C7), Color(0xFF38BDF8), Color(0xFFBAE6FD), Color(0xFFE0F2FE))
            )
            TimeOfDay.SUNSET -> Brush.verticalGradient(
                colors = listOf(Color(0xFF3B0764), Color(0xFF9333EA), Color(0xFFEA580C), Color(0xFFFACC15))
            )
            TimeOfDay.DUSK -> Brush.verticalGradient(
                colors = listOf(Color(0xFF090D16), Color(0xFF1E1B4B), Color(0xFF4C1D95), Color(0xFF9A3412))
            )
            TimeOfDay.NIGHT -> Brush.verticalGradient(
                colors = listOf(Color(0xFF020617), Color(0xFF0F172A), Color(0xFF1E293B))
            )
        }
        drawScope.drawRect(brush = skyBrush)

        // Twinkling Star Field for Night & Dusk
        if (timeOfDay == TimeOfDay.NIGHT || timeOfDay == TimeOfDay.DUSK) {
            val starAlphaBase = if (timeOfDay == TimeOfDay.NIGHT) 0.85f else 0.45f
            for (s in starPositions) {
                val sx = s.first * width
                val sy = s.second * height * 0.65f
                val r = (1.5f + s.third * 2.0f)
                val c = Color(0xFFF8FAFC).copy(alpha = (s.third * starAlphaBase).coerceIn(0.1f, 1.0f))
                drawScope.drawCircle(color = c, radius = r, center = Offset(sx, sy))
            }
        }

        // Celestial Sun / Moon Disk
        val sunWorldPos = when (timeOfDay) {
            TimeOfDay.DAWN -> eye + Vec3(-180f, 25f, 120f)
            TimeOfDay.DAY -> eye + Vec3(80f, 140f, 160f)
            TimeOfDay.SUNSET -> eye + Vec3(180f, 28f, 120f)
            TimeOfDay.DUSK -> eye + Vec3(190f, -10f, 120f)
            TimeOfDay.NIGHT -> eye + Vec3(-90f, 130f, 150f)
        }
        val sunProj = projectPoint(sunWorldPos, eye, target, aspect)
        if (sunProj != null) {
            val sunScreen = ndcToScreen(sunProj, width, height)
            val sunColor = when (timeOfDay) {
                TimeOfDay.DAWN -> Color(0xFFFDE047)
                TimeOfDay.DAY -> Color(0xFFFEF08A)
                TimeOfDay.SUNSET -> Color(0xFFFB923C)
                TimeOfDay.DUSK -> Color(0xFFEA580C)
                TimeOfDay.NIGHT -> Color(0xFFF8FAFC)
            }
            val haloColor = when (timeOfDay) {
                TimeOfDay.DAWN -> Color(0xFFF97316)
                TimeOfDay.DAY -> Color(0xFF38BDF8)
                TimeOfDay.SUNSET -> Color(0xFFEF4444)
                TimeOfDay.DUSK -> Color(0xFF818CF8)
                TimeOfDay.NIGHT -> Color(0xFF38BDF8)
            }
            drawScope.drawCircle(color = haloColor.copy(alpha = 0.30f), radius = 75f, center = sunScreen)
            drawScope.drawCircle(color = sunColor.copy(alpha = 0.45f), radius = 48f, center = sunScreen)
            drawScope.drawCircle(color = sunColor, radius = 28f, center = sunScreen)
        }
    }

    private fun worldToView(
        worldPt: Vec3, cameraEye: Vec3, forward: Vec3, right: Vec3, up: Vec3
    ): Vec3 {
        val dx = worldPt.x - cameraEye.x
        val dy = worldPt.y - cameraEye.y
        val dz = worldPt.z - cameraEye.z
        val xView = dx * right.x + dy * right.y + dz * right.z
        val yView = dx * up.x + dy * up.y + dz * up.z
        val zView = dx * forward.x + dy * forward.y + dz * forward.z
        return Vec3(xView, yView, zView)
    }

    private val tempClipList = ArrayList<Vec3>(8)
    private fun clipPolygonNear(v1: Vec3, v2: Vec3, v3: Vec3, v4: Vec3, zNear: Float): Int {
        tempClipList.clear()

        // Sutherland-Hodgman clipping against z >= zNear
        val in1 = v1.z >= zNear
        val in2 = v2.z >= zNear
        val in3 = v3.z >= zNear
        val in4 = v4.z >= zNear

        fun addEdge(a: Vec3, b: Vec3, aIn: Boolean, bIn: Boolean) {
            if (aIn) {
                tempClipList.add(a)
                if (!bIn) {
                    val t = (zNear - a.z) / (b.z - a.z)
                    tempClipList.add(Vec3(a.x + t * (b.x - a.x), a.y + t * (b.y - a.y), zNear))
                }
            } else if (bIn) {
                val t = (zNear - a.z) / (b.z - a.z)
                tempClipList.add(Vec3(a.x + t * (b.x - a.x), a.y + t * (b.y - a.y), zNear))
            }
        }

        addEdge(v1, v2, in1, in2)
        addEdge(v2, v3, in2, in3)
        addEdge(v3, v4, in3, in4)
        addEdge(v4, v1, in4, in1)

        return tempClipList.size
    }

    private fun addQuadPolygon(
        w1: Vec3,
        w2: Vec3,
        w3: Vec3,
        w4: Vec3,
        color: Color,
        eye: Vec3,
        target: Vec3,
        aspect: Float,
        screenW: Float,
        screenH: Float,
        isOutline: Boolean = false,
        outlineColor: Color = Color.Unspecified,
        layer: Float = 2.0f,
        avgZBias: Float = 0f
    ) {
        val forward = (target - eye).normalized()
        var right = Vec3(0f, 1f, 0f).cross(forward).normalized()
        if (right.lengthSquared() < 0.0001f) {
            right = Vec3(1f, 0f, 0f)
        }
        val up = forward.cross(right).normalized()

        val v1 = worldToView(w1, eye, forward, right, up)
        val v2 = worldToView(w2, eye, forward, right, up)
        val v3 = worldToView(w3, eye, forward, right, up)
        val v4 = worldToView(w4, eye, forward, right, up)

        // Calculate true 3D view-space centroid before clipping for stable depth sorting
        val unclippedAvgZ = (v1.z + v2.z + v3.z + v4.z) * 0.25f
        if (unclippedAvgZ < 0.1f) return

        val count = clipPolygonNear(v1, v2, v3, v4, 0.3f)
        if (count < 3) return

        if (activePolygonCount >= polygonPool.size) return
        val poly = polygonPool[activePolygonCount++]

        val tanFov = 0.57735f // tan(30 deg) for FOV 60
        val f = 1.0f / tanFov

        for (i in 0 until count) {
            val cv = tempClipList[i]
            val xNdc = (cv.x * f) / (aspect * cv.z)
            val yNdc = (cv.y * f) / cv.z
            poly.px[i] = (xNdc + 1f) * 0.5f * screenW
            poly.py[i] = (1f - yNdc) * 0.5f * screenH
        }

        poly.vertexCount = count
        poly.color = color
        poly.isOutline = isOutline
        poly.outlineColor = outlineColor
        poly.layer = layer
        poly.avgZ = unclippedAvgZ + avgZBias
    }

    private fun addTrianglePolygon(
        w1: Vec3,
        w2: Vec3,
        w3: Vec3,
        color: Color,
        eye: Vec3,
        target: Vec3,
        aspect: Float,
        screenW: Float,
        screenH: Float,
        isOutline: Boolean = false,
        outlineColor: Color = Color.Unspecified,
        layer: Float = 2.0f,
        avgZBias: Float = 0f
    ) {
        addQuadPolygon(w1, w2, w3, w3, color, eye, target, aspect, screenW, screenH, isOutline, outlineColor, layer, avgZBias)
    }

    private fun collectTerrainPolygons(
        eye: Vec3, target: Vec3, aspect: Float, screenW: Float, screenH: Float
    ) {
        val desertGroundColor = when (timeOfDay) {
            TimeOfDay.DAWN -> Color(0xFFC48D6C)
            TimeOfDay.DAY -> Color(0xFFCBAA7B) // Warm desert soil tan from satellite photo
            TimeOfDay.SUNSET -> Color(0xFF9E7E5A)
            TimeOfDay.DUSK -> Color(0xFF4A3B2C)
            TimeOfDay.NIGHT -> Color(0xFF281E15)
        }

        val minX = SatelliteMapData.MAP_MIN_X - 120f
        val maxX = SatelliteMapData.MAP_MAX_X + 120f
        val minZ = SatelliteMapData.MAP_MIN_Z - 120f
        val maxZ = SatelliteMapData.MAP_MAX_Z + 120f

        val stepX = 24f
        val stepZ = 24f
        val gridX = kotlin.math.ceil((maxX - minX) / stepX).toInt()
        val gridZ = kotlin.math.ceil((maxZ - minZ) / stepZ).toInt()

        for (ix in 0 until gridX) {
            val x1 = minX + ix * stepX
            val x2 = minX + (ix + 1) * stepX
            for (iz in 0 until gridZ) {
                val z1 = minZ + iz * stepZ
                val z2 = minZ + (iz + 1) * stepZ

                val midX = (x1 + x2) / 2f
                val midZ = (z1 + z2) / 2f
                val distSq = (midX - eye.x) * (midX - eye.x) + (midZ - eye.z) * (midZ - eye.z)
                if (distSq > 500f * 500f) continue

                addQuadPolygon(
                    Vec3(x1, 0f, z1), Vec3(x2, 0f, z1),
                    Vec3(x2, 0f, z2), Vec3(x1, 0f, z2),
                    desertGroundColor, eye, target, aspect, screenW, screenH, layer = 0.0f
                )
            }
        }

        // Surrounding Terraced Earth/Dirt Hills
        for (tp in SatelliteMapData.terrains) {
            val mountainColor = when (timeOfDay) {
                TimeOfDay.DAWN -> Color(0xFF704A5E)
                TimeOfDay.DAY -> Color(tp.colorHex)
                TimeOfDay.SUNSET -> Color(0xFF6E563C)
                TimeOfDay.DUSK -> Color(0xFF1E182A)
                TimeOfDay.NIGHT -> Color(0xFF10141D)
            }

            addQuadPolygon(
                Vec3(tp.minX, tp.baseHeight, tp.minZ),
                Vec3(tp.maxX, tp.baseHeight, tp.minZ),
                Vec3(tp.maxX, tp.topHeight, tp.maxZ),
                Vec3(tp.minX, tp.topHeight, tp.maxZ),
                mountainColor, eye, target, aspect, screenW, screenH, layer = 0.0f
            )
        }
    }

    private fun collectRoadPolygons(
        eye: Vec3, target: Vec3, aspect: Float, screenW: Float, screenH: Float
    ) {
        val asphaltColor = when (timeOfDay) {
            TimeOfDay.DAWN -> Color(0xFF3F495A)
            TimeOfDay.DAY -> Color(0xFF334155)
            TimeOfDay.SUNSET -> Color(0xFF1E293B)
            TimeOfDay.DUSK -> Color(0xFF172033)
            TimeOfDay.NIGHT -> Color(0xFF0F172A)
        }
        val tireTrackColor = when (timeOfDay) {
            TimeOfDay.DAWN -> Color(0xFF2D3748)
            TimeOfDay.DAY -> Color(0xFF242E3D)
            TimeOfDay.SUNSET -> Color(0xFF151D2A)
            TimeOfDay.DUSK -> Color(0xFF101726)
            TimeOfDay.NIGHT -> Color(0xFF0A0F1B)
        }
        val sidewalkTopColor = Color(0xFF94A3B8)
        val sidewalkBevelColor = Color(0xFF64748B)
        val yellowLine = Color(0xFFFACC15)
        val whiteLine = Color(0xFFF8FAFC)
        val dirtRoadColor = Color(0xFF855322)
        val dirtRutColor = Color(0xFF5E3914)

        for (r in SatelliteMapData.roads) {
            val dx = r.endX - r.startX
            val dz = r.endZ - r.startZ
            val totalLen = kotlin.math.sqrt(dx * dx + dz * dz)
            if (totalLen < 0.1f) continue

            val dirX = dx / totalLen
            val dirZ = dz / totalLen

            // Right perpendicular vector
            val normX = dirZ
            val normZ = -dirX

            val halfW = r.width / 2f
            val curbW = 0.8f
            val sidewalkW = 2.2f

            val stepLen = 10f
            val steps = kotlin.math.ceil(totalLen / stepLen).toInt().coerceAtLeast(1)
            val stepDt = totalLen / steps

            if (r.isDirt) {
                // Render enhanced off-road dirt track with wheel ruts
                for (i in 0 until steps) {
                    val t1 = i * stepDt
                    val t2 = (i + 1) * stepDt

                    val midX = r.startX + (t1 + t2) * 0.5f * dirX
                    val midZ = r.startZ + (t1 + t2) * 0.5f * dirZ
                    val distSq = (midX - eye.x) * (midX - eye.x) + (midZ - eye.z) * (midZ - eye.z)
                    if (distSq > 450f * 450f) continue

                    val p1 = Vec3(r.startX + t1 * dirX, 0.02f, r.startZ + t1 * dirZ)
                    val p2 = Vec3(r.startX + t2 * dirX, 0.02f, r.startZ + t2 * dirZ)

                    val normVec = Vec3(normX, 0f, normZ)

                    // Dirt road base
                    addQuadPolygon(
                        p1 - normVec * halfW,
                        p2 - normVec * halfW,
                        p2 + normVec * halfW,
                        p1 + normVec * halfW,
                        dirtRoadColor, eye, target, aspect, screenW, screenH, layer = 1.0f
                    )

                    // Dual tire ruts down dirt trail
                    val rutOff = halfW * 0.45f
                    val rutW = 0.5f
                    addQuadPolygon(
                        p1 - normVec * (rutOff + rutW),
                        p2 - normVec * (rutOff + rutW),
                        p2 - normVec * (rutOff - rutW),
                        p1 - normVec * (rutOff - rutW),
                        dirtRutColor, eye, target, aspect, screenW, screenH, layer = 1.05f
                    )
                    addQuadPolygon(
                        p1 + normVec * (rutOff - rutW),
                        p2 + normVec * (rutOff - rutW),
                        p2 + normVec * (rutOff + rutW),
                        p1 + normVec * (rutOff + rutW),
                        dirtRutColor, eye, target, aspect, screenW, screenH, layer = 1.05f
                    )
                }
                continue
            }

            // Paved Asphalt City Street
            for (i in 0 until steps) {
                val t1 = i * stepDt
                val t2 = (i + 1) * stepDt

                val midX = r.startX + (t1 + t2) * 0.5f * dirX
                val midZ = r.startZ + (t1 + t2) * 0.5f * dirZ
                val distSq = (midX - eye.x) * (midX - eye.x) + (midZ - eye.z) * (midZ - eye.z)
                if (distSq > 450f * 450f) continue

                val isHoriz = abs(dx) > abs(dz)
                val inIntersection = isAtIntersection(midX, midZ, isHoriz)

                val p1 = Vec3(r.startX + t1 * dirX, 0.02f, r.startZ + t1 * dirZ)
                val p2 = Vec3(r.startX + t2 * dirX, 0.02f, r.startZ + t2 * dirZ)
                val dirVec = Vec3(dirX, 0f, dirZ)
                val normVec = Vec3(normX, 0f, normZ)

                // 1. Main Asphalt Surface
                addQuadPolygon(
                    p1 - normVec * halfW,
                    p2 - normVec * halfW,
                    p2 + normVec * halfW,
                    p1 + normVec * halfW,
                    asphaltColor, eye, target, aspect, screenW, screenH, layer = 1.0f
                )

                // 2. Tire Track Wear Shading down lanes
                val laneOffset = halfW * 0.5f
                val wearW = 0.45f
                if (!inIntersection) {
                    addQuadPolygon(
                        p1 - normVec * (laneOffset + wearW),
                        p2 - normVec * (laneOffset + wearW),
                        p2 - normVec * (laneOffset - wearW),
                        p1 - normVec * (laneOffset - wearW),
                        tireTrackColor, eye, target, aspect, screenW, screenH, layer = 1.05f
                    )
                    addQuadPolygon(
                        p1 + normVec * (laneOffset - wearW),
                        p2 + normVec * (laneOffset - wearW),
                        p2 + normVec * (laneOffset + wearW),
                        p1 + normVec * (laneOffset + wearW),
                        tireTrackColor, eye, target, aspect, screenW, screenH, layer = 1.05f
                    )
                }

                // 3. Sidewalks and 3D Elevated Curbs with Painted Stripes & Paving Grid (Only outside intersections)
                if (!inIntersection) {
                    val paintToggle = (i % 2 == 0)
                    val curbColor = if (paintToggle) Color(0xFFDC2626) else Color(0xFFF8FAFC)
                    val curbJointColor = Color(0xFF1E293B)
                    val flagstoneSeamColor = Color(0xFF64748B)

                    // --- Left Side Curb & Sidewalk ---
                    val lCurbStart = p1 - normVec * halfW
                    val lCurbEnd = p2 - normVec * halfW
                    val lSidewalkStart = p1 - normVec * (halfW + curbW)
                    val lSidewalkEnd = p2 - normVec * (halfW + curbW)
                    val lOuterStart = p1 - normVec * (halfW + curbW + sidewalkW)
                    val lOuterEnd = p2 - normVec * (halfW + curbW + sidewalkW)

                    // Vertical Front Curb Wall
                    addQuadPolygon(
                        lCurbStart, lCurbEnd,
                        lCurbEnd + Vec3(0f, 0.08f, 0f), lCurbStart + Vec3(0f, 0.08f, 0f),
                        curbColor, eye, target, aspect, screenW, screenH, layer = 1.1f
                    )
                    // Curb Stone Joint Divider (every step)
                    addQuadPolygon(
                        lCurbStart, lCurbStart + dirVec * 0.12f,
                        lCurbStart + dirVec * 0.12f + Vec3(0f, 0.08f, 0f), lCurbStart + Vec3(0f, 0.08f, 0f),
                        curbJointColor, eye, target, aspect, screenW, screenH, layer = 1.11f
                    )
                    // Top Curb Bevel
                    addQuadPolygon(
                        lCurbStart + Vec3(0f, 0.08f, 0f), lCurbEnd + Vec3(0f, 0.08f, 0f),
                        lSidewalkEnd + Vec3(0f, 0.08f, 0f), lSidewalkStart + Vec3(0f, 0.08f, 0f),
                        sidewalkBevelColor, eye, target, aspect, screenW, screenH, layer = 1.1f
                    )
                    // Elevated Sidewalk Pavement Base
                    addQuadPolygon(
                        lSidewalkStart + Vec3(0f, 0.08f, 0f), lSidewalkEnd + Vec3(0f, 0.08f, 0f),
                        lOuterEnd + Vec3(0f, 0.08f, 0f), lOuterStart + Vec3(0f, 0.08f, 0f),
                        sidewalkTopColor, eye, target, aspect, screenW, screenH, layer = 1.1f
                    )
                    // Sidewalk Flagstone Seam (Longitudinal center seam + Transverse joints)
                    val lMidStart = (lSidewalkStart + lOuterStart) * 0.5f + Vec3(0f, 0.082f, 0f)
                    val lMidEnd = (lSidewalkEnd + lOuterEnd) * 0.5f + Vec3(0f, 0.082f, 0f)
                    addQuadPolygon(
                        lMidStart - normVec * 0.06f, lMidEnd - normVec * 0.06f,
                        lMidEnd + normVec * 0.06f, lMidStart + normVec * 0.06f,
                        flagstoneSeamColor, eye, target, aspect, screenW, screenH, layer = 1.12f
                    )
                    addQuadPolygon(
                        lSidewalkStart - dirVec * 0.06f + Vec3(0f, 0.082f, 0f),
                        lOuterStart - dirVec * 0.06f + Vec3(0f, 0.082f, 0f),
                        lOuterStart + dirVec * 0.06f + Vec3(0f, 0.082f, 0f),
                        lSidewalkStart + dirVec * 0.06f + Vec3(0f, 0.082f, 0f),
                        flagstoneSeamColor, eye, target, aspect, screenW, screenH, layer = 1.12f
                    )

                    // --- Right Side Curb & Sidewalk ---
                    val rCurbStart = p1 + normVec * halfW
                    val rCurbEnd = p2 + normVec * halfW
                    val rSidewalkStart = p1 + normVec * (halfW + curbW)
                    val rSidewalkEnd = p2 + normVec * (halfW + curbW)
                    val rOuterStart = p1 + normVec * (halfW + curbW + sidewalkW)
                    val rOuterEnd = p2 + normVec * (halfW + curbW + sidewalkW)

                    // Vertical Front Curb Wall
                    addQuadPolygon(
                        rCurbStart, rCurbEnd,
                        rCurbEnd + Vec3(0f, 0.08f, 0f), rCurbStart + Vec3(0f, 0.08f, 0f),
                        curbColor, eye, target, aspect, screenW, screenH, layer = 1.1f
                    )
                    // Curb Stone Joint Divider
                    addQuadPolygon(
                        rCurbStart, rCurbStart + dirVec * 0.12f,
                        rCurbStart + dirVec * 0.12f + Vec3(0f, 0.08f, 0f), rCurbStart + Vec3(0f, 0.08f, 0f),
                        curbJointColor, eye, target, aspect, screenW, screenH, layer = 1.11f
                    )
                    // Top Curb Bevel
                    addQuadPolygon(
                        rCurbStart + Vec3(0f, 0.08f, 0f), rCurbEnd + Vec3(0f, 0.08f, 0f),
                        rSidewalkEnd + Vec3(0f, 0.08f, 0f), rSidewalkStart + Vec3(0f, 0.08f, 0f),
                        sidewalkBevelColor, eye, target, aspect, screenW, screenH, layer = 1.1f
                    )
                    // Elevated Sidewalk Pavement
                    addQuadPolygon(
                        rSidewalkStart + Vec3(0f, 0.08f, 0f), rSidewalkEnd + Vec3(0f, 0.08f, 0f),
                        rOuterEnd + Vec3(0f, 0.08f, 0f), rOuterStart + Vec3(0f, 0.08f, 0f),
                        sidewalkTopColor, eye, target, aspect, screenW, screenH, layer = 1.1f
                    )
                    // Sidewalk Flagstone Seam
                    val rMidStart = (rSidewalkStart + rOuterStart) * 0.5f + Vec3(0f, 0.082f, 0f)
                    val rMidEnd = (rSidewalkEnd + rOuterEnd) * 0.5f + Vec3(0f, 0.082f, 0f)
                    addQuadPolygon(
                        rMidStart - normVec * 0.06f, rMidEnd - normVec * 0.06f,
                        rMidEnd + normVec * 0.06f, rMidStart + normVec * 0.06f,
                        flagstoneSeamColor, eye, target, aspect, screenW, screenH, layer = 1.12f
                    )
                    addQuadPolygon(
                        rSidewalkStart - dirVec * 0.06f + Vec3(0f, 0.082f, 0f),
                        rOuterStart - dirVec * 0.06f + Vec3(0f, 0.082f, 0f),
                        rOuterStart + dirVec * 0.06f + Vec3(0f, 0.082f, 0f),
                        rSidewalkStart + dirVec * 0.06f + Vec3(0f, 0.082f, 0f),
                        flagstoneSeamColor, eye, target, aspect, screenW, screenH, layer = 1.12f
                    )
                }

                // 4. Double Yellow Center Lines for Primary Roads
                if (r.isPrimary && !inIntersection) {
                    val lineW = 0.22f
                    addQuadPolygon(
                        p1 - normVec * lineW + Vec3(0f, 0.02f, 0f),
                        p2 - normVec * lineW + Vec3(0f, 0.02f, 0f),
                        p2 + normVec * lineW + Vec3(0f, 0.02f, 0f),
                        p1 + normVec * lineW + Vec3(0f, 0.02f, 0f),
                        yellowLine, eye, target, aspect, screenW, screenH, layer = 1.2f
                    )
                }

                // 5. Dashed White Lane Dividers for Wide Roads
                if (!r.isPrimary && r.width >= 10f && !inIntersection && (i % 2 == 0)) {
                    val dashW = 0.18f
                    addQuadPolygon(
                        p1 - normVec * dashW + Vec3(0f, 0.02f, 0f),
                        p2 - normVec * dashW + Vec3(0f, 0.02f, 0f),
                        p2 + normVec * dashW + Vec3(0f, 0.02f, 0f),
                        p1 + normVec * dashW + Vec3(0f, 0.02f, 0f),
                        whiteLine, eye, target, aspect, screenW, screenH, layer = 1.2f
                    )
                }

                // 6. Crosswalk Zebra Stripes at Intersections
                if (inIntersection && r.hasCrosswalks && (i % 2 == 0)) {
                    val stripeNum = 7
                    val stripeW = halfW * 1.8f / stripeNum
                    for (s in 0 until stripeNum) {
                        if (s % 2 == 0) continue
                        val sOffset = -halfW * 0.9f + s * stripeW
                        val s1 = p1 + normVec * sOffset + Vec3(0f, 0.03f, 0f)
                        val s2 = p2 + normVec * sOffset + Vec3(0f, 0.03f, 0f)
                        val s3 = p2 + normVec * (sOffset + stripeW * 0.7f) + Vec3(0f, 0.03f, 0f)
                        val s4 = p1 + normVec * (sOffset + stripeW * 0.7f) + Vec3(0f, 0.03f, 0f)
                        addQuadPolygon(s1, s2, s3, s4, whiteLine, eye, target, aspect, screenW, screenH, layer = 1.25f)
                    }
                }
            }
        }
    }

    private fun isAtIntersection(x: Float, z: Float, isHorizontalRoad: Boolean): Boolean {
        for (other in SatelliteMapData.roads) {
            val otherIsHorizontal = abs(other.endX - other.startX) > abs(other.endZ - other.startZ)
            if (isHorizontalRoad != otherIsHorizontal) {
                val margin = 1.5f
                if (otherIsHorizontal) {
                    val halfW = other.width / 2f + margin
                    val minX = minOf(other.startX, other.endX) - margin
                    val maxX = maxOf(other.startX, other.endX) + margin
                    if (x in minX..maxX && z in (other.startZ - halfW)..(other.startZ + halfW)) {
                        return true
                    }
                } else {
                    val halfW = other.width / 2f + margin
                    val minZ = minOf(other.startZ, other.endZ) - margin
                    val maxZ = maxOf(other.startZ, other.endZ) + margin
                    if (z in minZ..maxZ && x in (other.startX - halfW)..(other.startX + halfW)) {
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun isInsideAnyBuilding(x: Float, z: Float, margin: Float = 1.0f): Boolean {
        for (b in SatelliteMapData.buildings) {
            if (x >= b.minX - margin && x <= b.maxX + margin &&
                z >= b.minZ - margin && z <= b.maxZ + margin) {
                return true
            }
        }
        return false
    }

    private fun collectStreetDecorations(
        eye: Vec3, target: Vec3, aspect: Float, screenW: Float, screenH: Float
    ) {
        val lampColor = Color(0xFF475569)
        val glowColor = if (timeOfDay == TimeOfDay.NIGHT) Color(0xFFFEF08A) else Color(0xFFCBD5E1)

        for (r in SatelliteMapData.roads) {
            if (!r.isPrimary || r.isDirt) continue
            val isHorizontal = abs(r.endX - r.startX) > abs(r.endZ - r.startZ)
            val offsetDist = r.width / 2f + 2.5f

            if (isHorizontal) {
                var x = minOf(r.startX, r.endX) + 18f
                val maxX = maxOf(r.startX, r.endX)
                var loopCount = 0
                while (x < maxX && loopCount < 100) {
                    val distSq = (x - eye.x) * (x - eye.x) + (r.startZ - eye.z) * (r.startZ - eye.z)
                    if (distSq < 180f * 180f) {
                        if (!isInsideAnyBuilding(x + 18f, r.startZ - offsetDist)) {
                            drawLampPole(Vec3(x + 18f, 0f, r.startZ - offsetDist), lampColor, glowColor, eye, target, aspect, screenW, screenH)
                        }
                    }
                    x += 36f
                    loopCount++
                }
            } else {
                var z = minOf(r.startZ, r.endZ) + 18f
                val maxZ = maxOf(r.startZ, r.endZ)
                var loopCount = 0
                while (z < maxZ && loopCount < 100) {
                    val distSq = (r.startX - eye.x) * (r.startX - eye.x) + (z - eye.z) * (z - eye.z)
                    if (distSq < 180f * 180f) {
                        if (!isInsideAnyBuilding(r.startX - offsetDist, z + 18f)) {
                            drawLampPole(Vec3(r.startX - offsetDist, 0f, z + 18f), lampColor, glowColor, eye, target, aspect, screenW, screenH)
                        }
                    }
                    z += 36f
                    loopCount++
                }
            }
        }

        // Draw trees defined in map data with ground shadows, planter boxes, and species geometry
        for (treeItem in SatelliteMapData.trees) {
            val distSq = (treeItem.position.x - eye.x) * (treeItem.position.x - eye.x) + (treeItem.position.z - eye.z) * (treeItem.position.z - eye.z)
            if (distSq < 220f * 220f) {
                if (!isInsideAnyBuilding(treeItem.position.x, treeItem.position.z)) {
                    draw3DTreeWithShadow(treeItem, eye, target, aspect, screenW, screenH)
                }
            }
        }
    }

    private fun draw3DTreeWithShadow(
        tree: com.example.model.TreeItem,
        eye: Vec3, target: Vec3, aspect: Float, screenW: Float, screenH: Float
    ) {
        val pos = tree.position
        val species = tree.species

        // 1. Soft Ground Shadow
        val shadowColor = Color(0x35000000)
        val shadowDx = when (timeOfDay) {
            TimeOfDay.DAWN -> -3.2f
            TimeOfDay.DAY -> 0.8f
            TimeOfDay.SUNSET -> 3.2f
            TimeOfDay.DUSK -> 1.0f
            TimeOfDay.NIGHT -> 0.4f
        }
        val shadowDz = when (timeOfDay) {
            TimeOfDay.DAWN -> -1.2f
            TimeOfDay.DAY -> 1.0f
            TimeOfDay.SUNSET -> 2.2f
            TimeOfDay.DUSK -> 0.6f
            TimeOfDay.NIGHT -> 0.4f
        }
        val sr = tree.radius * 1.1f
        val s1 = pos + Vec3(-sr + shadowDx, 0.03f, -sr + shadowDz)
        val s2 = pos + Vec3(sr + shadowDx, 0.03f, -sr + shadowDz)
        val s3 = pos + Vec3(sr + shadowDx, 0.03f, sr + shadowDz)
        val s4 = pos + Vec3(-sr + shadowDx, 0.03f, sr + shadowDz)
        addQuadPolygon(s1, s2, s3, s4, shadowColor, eye, target, aspect, screenW, screenH, layer = 1.35f)

        // 2. Concrete/Stone Tree Planter Box with Dark Soil Bed
        if (tree.hasPlanterBox) {
            val pw = tree.radius * 0.7f
            val ph = 0.18f
            val planterColor = Color(0xFF64748B)
            val soilColor = Color(0xFF3D2314)

            val p1 = pos + Vec3(-pw, 0f, -pw)
            val p2 = pos + Vec3(pw, 0f, -pw)
            val p3 = pos + Vec3(pw, 0f, pw)
            val p4 = pos + Vec3(-pw, 0f, pw)

            val pt1 = pos + Vec3(-pw, ph, -pw)
            val pt2 = pos + Vec3(pw, ph, -pw)
            val pt3 = pos + Vec3(pw, ph, pw)
            val pt4 = pos + Vec3(-pw, ph, pw)

            // Planter walls
            addQuadPolygon(p1, p2, pt2, pt1, planterColor, eye, target, aspect, screenW, screenH)
            addQuadPolygon(p2, p3, pt3, pt2, planterColor, eye, target, aspect, screenW, screenH)
            addQuadPolygon(p3, p4, pt4, pt3, planterColor, eye, target, aspect, screenW, screenH)
            addQuadPolygon(p4, p1, pt1, pt4, planterColor, eye, target, aspect, screenW, screenH)
            // Soil top
            addQuadPolygon(pt1, pt2, pt3, pt4, soilColor, eye, target, aspect, screenW, screenH)
        }

        // Base Foliage Color from species according to timeOfDay
        val baseFoliageHex = when (timeOfDay) {
            TimeOfDay.DAWN -> species.foliageColorHexDay
            TimeOfDay.DAY -> species.foliageColorHexDay
            TimeOfDay.SUNSET -> species.foliageColorHexSunset
            TimeOfDay.DUSK -> species.foliageColorHexSunset
            TimeOfDay.NIGHT -> species.foliageColorHexNight
        }
        val foliageColor = Color(baseFoliageHex)
        val trunkColor = Color(0xFF78350F)

        // Render Tree by Species
        when (species) {
            com.example.model.TreeSpecies.MEDITERRANEAN_PALM -> drawPalmTree(pos, tree.height, foliageColor, eye, target, aspect, screenW, screenH)
            com.example.model.TreeSpecies.PINE_CYPRESS -> drawPineTree(pos, tree.radius, tree.height, foliageColor, eye, target, aspect, screenW, screenH)
            com.example.model.TreeSpecies.AUTUMN_GOLDEN -> drawDeciduousTree(pos, tree.radius, tree.height, foliageColor, trunkColor, eye, target, aspect, screenW, screenH)
            com.example.model.TreeSpecies.DECIDUOUS_LUSH -> drawDeciduousTree(pos, tree.radius, tree.height, foliageColor, trunkColor, eye, target, aspect, screenW, screenH)
        }
    }

    private fun drawPalmTree(
        pos: Vec3, height: Float, foliageColor: Color,
        eye: Vec3, target: Vec3, aspect: Float, screenW: Float, screenH: Float
    ) {
        val trunkColor = Color(0xFF854D0E)
        val darkTrunk = Color(0xFF53310B)
        val trunkSegments = 5
        val segH = height / trunkSegments
        val baseR = 0.28f

        // 1. Curved Ringed Palm Trunk
        var currPos = pos
        for (i in 0 until trunkSegments) {
            val t = i.toFloat() / trunkSegments
            val leanDx = sin(t * 1.5f) * 0.25f
            val nextPos = pos + Vec3(leanDx, (i + 1) * segH, 0f)
            val r1 = baseR * (1.0f - t * 0.35f)
            val r2 = baseR * (1.0f - (t + 0.2f) * 0.35f)

            val p1 = currPos + Vec3(-r1, 0f, -r1)
            val p2 = currPos + Vec3(r1, 0f, -r1)
            val p3 = currPos + Vec3(r1, 0f, r1)
            val p4 = currPos + Vec3(-r1, 0f, r1)

            val pt1 = nextPos + Vec3(-r2, 0f, -r2)
            val pt2 = nextPos + Vec3(r2, 0f, -r2)
            val pt3 = nextPos + Vec3(r2, 0f, r2)
            val pt4 = nextPos + Vec3(-r2, 0f, r2)

            addQuadPolygon(p4, p3, pt3, pt4, trunkColor, eye, target, aspect, screenW, screenH)
            addQuadPolygon(p2, p1, pt1, pt2, darkTrunk, eye, target, aspect, screenW, screenH)
            addQuadPolygon(p1, p4, pt4, pt1, darkTrunk, eye, target, aspect, screenW, screenH)
            addQuadPolygon(p3, p2, pt2, pt3, trunkColor, eye, target, aspect, screenW, screenH)

            currPos = nextPos
        }

        // 2. Sweeping 3D Palm Fronds Radiating Out
        val crownPos = currPos
        val frondCount = 8
        val frondLen = 2.8f
        val lightFrond = foliageColor
        val darkFrond = Color(
            red = (foliageColor.red * 0.75f).coerceIn(0f, 1f),
            green = (foliageColor.green * 0.75f).coerceIn(0f, 1f),
            blue = (foliageColor.blue * 0.75f).coerceIn(0f, 1f)
        )

        for (f in 0 until frondCount) {
            val angle = f * (2f * Math.PI.toFloat() / frondCount)
            val tipX = crownPos.x + cos(angle) * frondLen
            val tipZ = crownPos.z + sin(angle) * frondLen
            val tipY = crownPos.y - 0.6f

            val midX = crownPos.x + cos(angle) * (frondLen * 0.5f)
            val midZ = crownPos.z + sin(angle) * (frondLen * 0.5f)
            val midY = crownPos.y + 0.4f

            val sideX = -sin(angle) * 0.35f
            val sideZ = cos(angle) * 0.35f

            val pBase = crownPos
            val pMid1 = Vec3(midX + sideX, midY, midZ + sideZ)
            val pMid2 = Vec3(midX - sideX, midY, midZ - sideZ)
            val pTip = Vec3(tipX, tipY, tipZ)

            val fColor = if (f % 2 == 0) lightFrond else darkFrond
            addTrianglePolygon(pBase, pMid1, pTip, fColor, eye, target, aspect, screenW, screenH)
            addTrianglePolygon(pBase, pTip, pMid2, fColor, eye, target, aspect, screenW, screenH)
        }
    }

    private fun drawPineTree(
        pos: Vec3, radius: Float, height: Float, foliageColor: Color,
        eye: Vec3, target: Vec3, aspect: Float, screenW: Float, screenH: Float
    ) {
        val trunkW = 0.22f
        val trunkH = height * 0.35f
        val trunkColor = Color(0xFF523B24)

        // Trunk
        val t000 = pos + Vec3(-trunkW, 0f, -trunkW)
        val t100 = pos + Vec3(trunkW, 0f, -trunkW)
        val t101 = pos + Vec3(trunkW, 0f, trunkW)
        val t001 = pos + Vec3(-trunkW, 0f, trunkW)

        val t010 = pos + Vec3(-trunkW, trunkH, -trunkW)
        val t110 = pos + Vec3(trunkW, trunkH, -trunkW)
        val t111 = pos + Vec3(trunkW, trunkH, trunkW)
        val t011 = pos + Vec3(-trunkW, trunkH, trunkW)

        addQuadPolygon(t001, t101, t111, t011, trunkColor, eye, target, aspect, screenW, screenH)
        addQuadPolygon(t100, t000, t010, t110, trunkColor, eye, target, aspect, screenW, screenH)
        addQuadPolygon(t000, t001, t011, t010, trunkColor, eye, target, aspect, screenW, screenH)
        addQuadPolygon(t101, t100, t110, t111, trunkColor, eye, target, aspect, screenW, screenH)

        // 3 Tiers of Conical Evergreen Needle Frustums
        val topFoliage = foliageColor
        val sideFoliage = Color(
            red = (foliageColor.red * 0.85f).coerceIn(0f, 1f),
            green = (foliageColor.green * 0.85f).coerceIn(0f, 1f),
            blue = (foliageColor.blue * 0.85f).coerceIn(0f, 1f)
        )
        val darkFoliage = Color(
            red = (foliageColor.red * 0.65f).coerceIn(0f, 1f),
            green = (foliageColor.green * 0.65f).coerceIn(0f, 1f),
            blue = (foliageColor.blue * 0.65f).coerceIn(0f, 1f)
        )

        val tiers = 3
        val tierH = (height - trunkH) / tiers
        for (tier in 0 until tiers) {
            val bY = pos.y + trunkH + tier * tierH * 0.75f
            val tY = bY + tierH * 1.2f
            val bR = radius * (1.0f - tier * 0.25f)
            val tR = bR * 0.3f

            drawCanopyFrustum(
                baseY = bY, topY = tY,
                baseR = bR, topR = tR,
                center = pos,
                sideColor = sideFoliage, darkColor = darkFoliage, topColor = topFoliage,
                eye = eye, target = target, aspect = aspect, screenW = screenW, screenH = screenH
            )
        }
    }

    private fun drawDeciduousTree(
        pos: Vec3, radius: Float, height: Float, foliageColor: Color, trunkColor: Color,
        eye: Vec3, target: Vec3, aspect: Float, screenW: Float, screenH: Float
    ) {
        val trunkW = 0.25f
        val trunkH = height * 0.42f

        // Trunk
        val t000 = pos + Vec3(-trunkW, 0f, -trunkW)
        val t100 = pos + Vec3(trunkW, 0f, -trunkW)
        val t101 = pos + Vec3(trunkW, 0f, trunkW)
        val t001 = pos + Vec3(-trunkW, 0f, trunkW)

        val t010 = pos + Vec3(-trunkW, trunkH, -trunkW)
        val t110 = pos + Vec3(trunkW, trunkH, -trunkW)
        val t111 = pos + Vec3(trunkW, trunkH, trunkW)
        val t011 = pos + Vec3(-trunkW, trunkH, trunkW)

        val darkTrunk = Color(
            red = (trunkColor.red * 0.7f).coerceIn(0f, 1f),
            green = (trunkColor.green * 0.7f).coerceIn(0f, 1f),
            blue = (trunkColor.blue * 0.7f).coerceIn(0f, 1f)
        )

        addQuadPolygon(t001, t101, t111, t011, trunkColor, eye, target, aspect, screenW, screenH)
        addQuadPolygon(t100, t000, t010, t110, darkTrunk, eye, target, aspect, screenW, screenH)
        addQuadPolygon(t000, t001, t011, t010, darkTrunk, eye, target, aspect, screenW, screenH)
        addQuadPolygon(t101, t100, t110, t111, trunkColor, eye, target, aspect, screenW, screenH)

        // Tiered Lush Foliage Spherical Clusters
        val topFoliage = foliageColor
        val sideFoliage = Color(
            red = (foliageColor.red * 0.88f).coerceIn(0f, 1f),
            green = (foliageColor.green * 0.88f).coerceIn(0f, 1f),
            blue = (foliageColor.blue * 0.88f).coerceIn(0f, 1f)
        )
        val darkFoliage = Color(
            red = (foliageColor.red * 0.70f).coerceIn(0f, 1f),
            green = (foliageColor.green * 0.70f).coerceIn(0f, 1f),
            blue = (foliageColor.blue * 0.70f).coerceIn(0f, 1f)
        )

        // Lower Canopy Frustum
        drawCanopyFrustum(
            baseY = pos.y + trunkH,
            topY = pos.y + trunkH + height * 0.35f,
            baseR = radius,
            topR = radius * 0.65f,
            center = pos,
            sideColor = sideFoliage, darkColor = darkFoliage, topColor = topFoliage,
            eye = eye, target = target, aspect = aspect, screenW = screenW, screenH = screenH
        )

        // Upper Crown Frustum
        drawCanopyFrustum(
            baseY = pos.y + trunkH + height * 0.25f,
            topY = pos.y + height,
            baseR = radius * 0.85f,
            topR = 0.15f,
            center = pos,
            sideColor = topFoliage, darkColor = sideFoliage, topColor = topFoliage,
            eye = eye, target = target, aspect = aspect, screenW = screenW, screenH = screenH
        )
    }

    private fun drawCanopyFrustum(
        baseY: Float, topY: Float,
        baseR: Float, topR: Float,
        center: Vec3,
        sideColor: Color, darkColor: Color, topColor: Color,
        eye: Vec3, target: Vec3, aspect: Float, screenW: Float, screenH: Float
    ) {
        val sides = 8
        val bPts = Array(sides) { i ->
            val angle = i * (2f * Math.PI.toFloat() / sides)
            center + Vec3(baseR * cos(angle), baseY - center.y, baseR * sin(angle))
        }
        val tPts = Array(sides) { i ->
            val angle = i * (2f * Math.PI.toFloat() / sides)
            center + Vec3(topR * cos(angle), topY - center.y, topR * sin(angle))
        }

        val centerBase = center + Vec3(0f, baseY - center.y, 0f)
        val centerTop = center + Vec3(0f, topY - center.y, 0f)

        for (i in 0 until sides) {
            val j = (i + 1) % sides
            val angle = i * (2f * Math.PI.toFloat() / sides)
            val shade = 0.8f + 0.2f * sin(angle)
            val faceColor = Color(
                red = (sideColor.red * shade).coerceIn(0f, 1f),
                green = (sideColor.green * shade).coerceIn(0f, 1f),
                blue = (sideColor.blue * shade).coerceIn(0f, 1f)
            )

            // Sloped side wall quad
            addQuadPolygon(bPts[i], bPts[j], tPts[j], tPts[i], faceColor, eye, target, aspect, screenW, screenH)

            // Bottom cap section
            addQuadPolygon(centerBase, bPts[i], bPts[j], centerBase, darkColor, eye, target, aspect, screenW, screenH)

            // Top cap section
            if (topR > 0.15f) {
                addQuadPolygon(centerTop, tPts[j], tPts[i], centerTop, topColor, eye, target, aspect, screenW, screenH)
            }
        }
    }

    private fun drawLampPole(
        base: Vec3, poleColor: Color, bulbColor: Color,
        eye: Vec3, target: Vec3, aspect: Float, screenW: Float, screenH: Float
    ) {
        val h = 6.2f
        val w = 0.15f
        val p0 = base + Vec3(-w, 0f, -w); val p1 = base + Vec3(w, 0f, -w)
        val p2 = base + Vec3(w, 0f, w);   val p3 = base + Vec3(-w, 0f, w)
        val pt0 = base + Vec3(-w, h, -w); val pt1 = base + Vec3(w, h, -w)
        val pt2 = base + Vec3(w, h, w);   val pt3 = base + Vec3(-w, h, w)

        val darkPole = Color(red = poleColor.red * 0.8f, green = poleColor.green * 0.8f, blue = poleColor.blue * 0.8f)

        addQuadPolygon(p3, p2, pt2, pt3, poleColor, eye, target, aspect, screenW, screenH)
        addQuadPolygon(p1, p0, pt0, pt1, darkPole, eye, target, aspect, screenW, screenH)
        addQuadPolygon(p0, p3, pt3, pt0, darkPole, eye, target, aspect, screenW, screenH)
        addQuadPolygon(p2, p1, pt1, pt2, poleColor, eye, target, aspect, screenW, screenH)

        // Light bulb box on top
        val bw = 0.45f
        val bh = 0.45f
        val bBase = base + Vec3(0f, h, 0f)
        val b0 = bBase + Vec3(-bw, 0f, -bw); val b1 = bBase + Vec3(bw, 0f, -bw)
        val b2 = bBase + Vec3(bw, 0f, bw);   val b3 = bBase + Vec3(-bw, 0f, bw)
        val bt0 = bBase + Vec3(-bw, bh, -bw); val bt1 = bBase + Vec3(bw, bh, -bw)
        val bt2 = bBase + Vec3(bw, bh, bw);   val bt3 = bBase + Vec3(-bw, bh, bw)

        val effectiveBulbColor = when (timeOfDay) {
            TimeOfDay.DAY -> Color(0xFF475569)
            TimeOfDay.SUNSET -> Color(0xFFF59E0B)
            TimeOfDay.DAWN -> Color(0xFFFDE047)
            TimeOfDay.DUSK, TimeOfDay.NIGHT -> Color(0xFFFEF08A)
        }

        addQuadPolygon(b3, b2, bt2, bt3, effectiveBulbColor, eye, target, aspect, screenW, screenH)
        addQuadPolygon(b1, b0, bt0, bt1, effectiveBulbColor, eye, target, aspect, screenW, screenH)
        addQuadPolygon(b0, b3, bt3, bt0, effectiveBulbColor, eye, target, aspect, screenW, screenH)
        addQuadPolygon(b2, b1, bt1, bt2, effectiveBulbColor, eye, target, aspect, screenW, screenH)
        addQuadPolygon(bt3, bt2, bt1, bt0, effectiveBulbColor, eye, target, aspect, screenW, screenH)

        // Functional Ground Light Pool and Volumetric Cone Beam for Dusk, Night & Dawn
        if (timeOfDay == TimeOfDay.NIGHT || timeOfDay == TimeOfDay.DUSK || timeOfDay == TimeOfDay.DAWN) {
            val lightRadius = 6.0f
            val poolCenter = base + Vec3(0f, 0.03f, 0f)
            val poolAlpha = if (timeOfDay == TimeOfDay.NIGHT) 0.42f else 0.28f
            val poolColor = Color(0xFFFEF08A).copy(alpha = poolAlpha)

            val pL1 = poolCenter + Vec3(-lightRadius, 0f, -lightRadius)
            val pL2 = poolCenter + Vec3(lightRadius, 0f, -lightRadius)
            val pL3 = poolCenter + Vec3(lightRadius, 0f, lightRadius)
            val pL4 = poolCenter + Vec3(-lightRadius, 0f, lightRadius)

            addQuadPolygon(pL1, pL2, pL3, pL4, poolColor, eye, target, aspect, screenW, screenH, layer = 1.08f)

            val coneTop = bBase + Vec3(0f, -0.2f, 0f)
            val shaftAlpha = if (timeOfDay == TimeOfDay.NIGHT) 0.14f else 0.08f
            val shaftColor = Color(0xFFFDE047).copy(alpha = shaftAlpha)

            addQuadPolygon(
                coneTop + Vec3(-0.3f, 0f, 0f), coneTop + Vec3(0.3f, 0f, 0f),
                pL2, pL1, shaftColor, eye, target, aspect, screenW, screenH, layer = 2.2f
            )
        }
    }

    private fun collectBuildingPolygons(
        eye: Vec3, target: Vec3, aspect: Float, screenW: Float, screenH: Float
    ) {
        val windowColor = when (timeOfDay) {
            TimeOfDay.DAWN -> Color(0xFFFDE047).copy(alpha = 0.85f)
            TimeOfDay.DAY -> Color(0xFF0284C7).copy(alpha = 0.88f) // Crisp sky blue glass
            TimeOfDay.SUNSET -> Color(0xFFF97316).copy(alpha = 0.9f) // Warm amber reflection
            TimeOfDay.DUSK -> Color(0xFFFACC15)
            TimeOfDay.NIGHT -> Color(0xFFFEF08A) // Warm lit window during night
        }
        val windowFrameColor = Color(0xFF1E293B) // Dark aluminum frame
        val windowSillColor = Color(0xFFCBD5E1) // Stone sill
        val glassGlareColor = Color(0xD0F0F9FF) // White corner reflection highlight
        val darkAccent = Color(0xFF333740) // Dark frame/balcony accent
        val graniteBaseColor = Color(0xFF334155) // Ground granite base skirt ("سنگ ازار")
        val moldingColor = Color(0xFF94A3B8) // Horizontal floor separator cornice
        val concreteApronColor = Color(0xFFE8E2D8) // Light sidewalk apron at building base
        val shadowColor = Color(0x33000000)

        for (b in SatelliteMapData.buildings) {
            val cx = (b.minX + b.maxX) / 2f
            val cz = (b.minZ + b.maxZ) / 2f
            val dx = cx - eye.x
            val dz = cz - eye.z
            val distSq = dx * dx + dz * dz

            // Skip buildings that are beyond maximum city horizon
            if (distSq > 650f * 650f) continue

            val wallColor = getBuildingWallColor(b)
            val roofColor = getBuildingRoofColor(b)

            val x1 = b.minX; val x2 = b.maxX
            val z1 = b.minZ; val z2 = b.maxZ
            val h = b.height

            // 0a. Base Concrete Sidewalk Apron (ground level)
            val apron = 2.2f
            addQuadPolygon(
                Vec3(x1 - apron, 0.04f, z1 - apron),
                Vec3(x2 + apron, 0.04f, z1 - apron),
                Vec3(x2 + apron, 0.04f, z2 + apron),
                Vec3(x1 - apron, 0.04f, z2 + apron),
                concreteApronColor, eye, target, aspect, screenW, screenH, layer = 1.1f
            )

            val v000 = Vec3(x1, 0f, z1); val v100 = Vec3(x2, 0f, z1)
            val v101 = Vec3(x2, 0f, z2); val v001 = Vec3(x1, 0f, z2)

            val v010 = Vec3(x1, h, z1); val v110 = Vec3(x2, h, z1)
            val v111 = Vec3(x2, h, z2); val v011 = Vec3(x1, h, z2)

            // 1. Building Exterior Walls with Directional Shading
            val frontColor = wallColor
            val backColor = wallColor.copy(red = (wallColor.red * 0.85f).coerceIn(0f, 1f), green = (wallColor.green * 0.85f).coerceIn(0f, 1f), blue = (wallColor.blue * 0.85f).coerceIn(0f, 1f))
            val leftColor = wallColor.copy(red = (wallColor.red * 0.90f).coerceIn(0f, 1f), green = (wallColor.green * 0.90f).coerceIn(0f, 1f), blue = (wallColor.blue * 0.90f).coerceIn(0f, 1f))
            val rightColor = wallColor.copy(red = (wallColor.red * 0.95f).coerceIn(0f, 1f), green = (wallColor.green * 0.95f).coerceIn(0f, 1f), blue = (wallColor.blue * 0.95f).coerceIn(0f, 1f))

            if (eye.z > z2) addQuadPolygon(v001, v101, v111, v011, frontColor, eye, target, aspect, screenW, screenH, true, shadowColor)
            if (eye.z < z1) addQuadPolygon(v100, v000, v010, v110, backColor, eye, target, aspect, screenW, screenH, true, shadowColor)
            if (eye.x < x1) addQuadPolygon(v000, v001, v011, v010, leftColor, eye, target, aspect, screenW, screenH, true, shadowColor)
            if (eye.x > x2) addQuadPolygon(v101, v100, v110, v111, rightColor, eye, target, aspect, screenW, screenH, true, shadowColor)

            // 1b. Ground Foundation Granite Skirt ("سنگ ازار bottom 1.2m")
            val skirtH = 1.2f
            if (eye.z > z2) addQuadPolygon(v001, v101, Vec3(x2, skirtH, z2 + 0.02f), Vec3(x1, skirtH, z2 + 0.02f), graniteBaseColor, eye, target, aspect, screenW, screenH)
            if (eye.z < z1) addQuadPolygon(v100, v000, Vec3(x1, skirtH, z1 - 0.02f), Vec3(x2, skirtH, z1 - 0.02f), graniteBaseColor, eye, target, aspect, screenW, screenH)
            if (eye.x < x1) addQuadPolygon(v000, v001, Vec3(x1 - 0.02f, skirtH, z2), Vec3(x1 - 0.02f, skirtH, z1), graniteBaseColor, eye, target, aspect, screenW, screenH)
            if (eye.x > x2) addQuadPolygon(v101, v100, Vec3(x2 + 0.02f, skirtH, z1), Vec3(x2 + 0.02f, skirtH, z2), graniteBaseColor, eye, target, aspect, screenW, screenH)

            // 1c. Floor Division Cornices ("ابزار افقی بین طبقات")
            if (distSq < 220f * 220f && b.stories > 1) {
                val storyH = h / b.stories
                for (floor in 1 until b.stories) {
                    val mY = floor * storyH
                    val mW = 0.18f
                    if (eye.z > z2) {
                        addQuadPolygon(
                            Vec3(x1 - 0.1f, mY, z2 + 0.04f), Vec3(x2 + 0.1f, mY, z2 + 0.04f),
                            Vec3(x2 + 0.1f, mY + mW, z2 + 0.04f), Vec3(x1 - 0.1f, mY + mW, z2 + 0.04f),
                            moldingColor, eye, target, aspect, screenW, screenH
                        )
                    }
                    if (eye.z < z1) {
                        addQuadPolygon(
                            Vec3(x1 - 0.1f, mY, z1 - 0.04f), Vec3(x2 + 0.1f, mY, z1 - 0.04f),
                            Vec3(x2 + 0.1f, mY + mW, z1 - 0.04f), Vec3(x1 - 0.1f, mY + mW, z1 - 0.04f),
                            moldingColor, eye, target, aspect, screenW, screenH
                        )
                    }
                }
            }

            // 2. Facade Details: Windows, Recessed Balconies, or Commercial Shop Signs & Display Windows
            if (distSq < 200f * 200f && b.roofType == RoofType.FLAT) {
                if (b.isCommercialShop) {
                    // Commercial Storefront Facade
                    val signColor = Color(b.signColorHex)
                    val glassWindowColor = when (timeOfDay) {
                        TimeOfDay.DAWN -> Color(0xFFFDE047).copy(alpha = 0.88f)
                        TimeOfDay.DAY -> Color(0xFF0EA5E9).copy(alpha = 0.9f)
                        TimeOfDay.SUNSET -> Color(0xFFF59E0B).copy(alpha = 0.9f)
                        TimeOfDay.DUSK -> Color(0xFFFACC15).copy(alpha = 0.92f)
                        TimeOfDay.NIGHT -> Color(0xFFFEF08A).copy(alpha = 0.95f) // Bright lit shop interior
                    }

                    // Large Glass Store Display Window on Ground Floor
                    val winY1 = 0.6f
                    val winY2 = 3.6f
                    val winX1 = x1 + 1.2f
                    val winX2 = x2 - 1.2f

                    if (eye.z > z2 && winX2 > winX1) {
                        // Glass Display Window Pane
                        addQuadPolygon(
                            Vec3(winX1, winY1, z2 + 0.05f), Vec3(winX2, winY1, z2 + 0.05f),
                            Vec3(winX2, winY2, z2 + 0.05f), Vec3(winX1, winY2, z2 + 0.05f),
                            glassWindowColor, eye, target, aspect, screenW, screenH
                        )
                        // Electric Roll-Up Shutter Box ("باکس کرکره برقی") Above Storefront
                        addQuadPolygon(
                            Vec3(winX1 - 0.2f, winY2, z2 + 0.12f), Vec3(winX2 + 0.2f, winY2, z2 + 0.12f),
                            Vec3(winX2 + 0.2f, winY2 + 0.35f, z2 + 0.12f), Vec3(winX1 - 0.2f, winY2 + 0.35f, z2 + 0.12f),
                            Color(0xFF475569), eye, target, aspect, screenW, screenH
                        )

                        // 3D Stripe Awning / Canopy ("سایبان آکاردئونی مغازه") projecting over sidewalk
                        val awnY = winY2 + 0.1f
                        val awnDepth = 1.1f
                        val awnDrop = 0.45f
                        val numStripes = 6
                        val stripeW = (winX2 - winX1 + 0.8f) / numStripes
                        val awnXStart = winX1 - 0.4f
                        for (st in 0 until numStripes) {
                            val stX1 = awnXStart + st * stripeW
                            val stX2 = stX1 + stripeW
                            val stripeColor = if (st % 2 == 0) signColor else Color(0xFFF8FAFC)
                            addQuadPolygon(
                                Vec3(stX1, awnY, z2 + 0.06f), Vec3(stX2, awnY, z2 + 0.06f),
                                Vec3(stX2, awnY - awnDrop, z2 + awnDepth), Vec3(stX1, awnY - awnDrop, z2 + awnDepth),
                                stripeColor, eye, target, aspect, screenW, screenH
                            )
                        }

                        // Colorful Shop Signboard ("تابلو مغازه") Above Awning
                        val signY1 = winY2 + 0.45f
                        val signY2 = signY1 + 1.4f
                        val signDepth = 0.18f
                        addQuadPolygon(
                            Vec3(winX1 - 0.5f, signY1, z2 + signDepth), Vec3(winX2 + 0.5f, signY1, z2 + signDepth),
                            Vec3(winX2 + 0.5f, signY2, z2 + signDepth), Vec3(winX1 - 0.5f, signY2, z2 + signDepth),
                            signColor, eye, target, aspect, screenW, screenH
                        )
                        // Signboard Top Canopy Trim
                        addQuadPolygon(
                            Vec3(winX1 - 0.6f, signY2, z2 + signDepth + 0.2f), Vec3(winX2 + 0.6f, signY2, z2 + signDepth + 0.2f),
                            Vec3(winX2 + 0.6f, signY2 + 0.25f, z2), Vec3(winX1 - 0.6f, signY2 + 0.25f, z2),
                            Color(0xFF1E293B), eye, target, aspect, screenW, screenH
                        )
                    } else if (eye.z < z1 && winX2 > winX1) {
                        // Rear Store Entrance / Display
                        addQuadPolygon(
                            Vec3(winX1, winY1, z1 - 0.05f), Vec3(winX2, winY1, z1 - 0.05f),
                            Vec3(winX2, winY2, z1 - 0.05f), Vec3(winX1, winY2, z1 - 0.05f),
                            glassWindowColor, eye, target, aspect, screenW, screenH
                        )
                    }
                } else {
                    // Residential Apartment Facade (Windows, Frames & Balconies)
                    val storyH = h / b.stories
                    val len = x2 - x1
                    val numSections = maxOf(2, (len / 12f).toInt())
                    val secW = len / numSections

                    for (floor in 0 until b.stories) {
                        val yBottom = floor * storyH + 0.4f
                        val yTop = yBottom + storyH * 0.62f

                        for (s in 0 until numSections) {
                            val secX1 = x1 + s * secW + 1.2f
                            val secX2 = secX1 + secW - 2.4f
                            if (secX2 <= secX1 + 1f) continue

                            // Front Facade Balcony/Window Alcove
                            if (eye.z > z2) {
                                if (s % 2 == 1 && b.hasBalconies && floor > 0) {
                                    // Recessed Balcony
                                    val alcoveDepth = 0.55f
                                    addQuadPolygon(
                                        Vec3(secX1, yBottom, z2 - alcoveDepth), Vec3(secX2, yBottom, z2 - alcoveDepth),
                                        Vec3(secX2, yTop, z2 - alcoveDepth), Vec3(secX1, yTop, z2 - alcoveDepth),
                                        darkAccent, eye, target, aspect, screenW, screenH
                                    )
                                    // Balcony Front Dark Railing with Glass Panel
                                    val railH = yBottom + storyH * 0.28f
                                    addQuadPolygon(
                                        Vec3(secX1, yBottom, z2 + 0.04f), Vec3(secX2, yBottom, z2 + 0.04f),
                                        Vec3(secX2, railH, z2 + 0.04f), Vec3(secX1, railH, z2 + 0.04f),
                                        darkAccent, eye, target, aspect, screenW, screenH
                                    )
                                } else {
                                    // Standard Glass Window Pair with Dark Frame & Stone Sill
                                    val midX = (secX1 + secX2) / 2f
                                    val fOff = 0.08f // Frame width

                                    // Window 1 Frame & Sill
                                    addQuadPolygon(
                                        Vec3(secX1 - fOff, yBottom + 0.35f, z2 + 0.035f), Vec3(midX - 0.15f, yBottom + 0.35f, z2 + 0.035f),
                                        Vec3(midX - 0.15f, yTop + fOff, z2 + 0.035f), Vec3(secX1 - fOff, yTop + fOff, z2 + 0.035f),
                                        windowFrameColor, eye, target, aspect, screenW, screenH
                                    )
                                    addQuadPolygon(
                                        Vec3(secX1, yBottom + 0.4f, z2 + 0.05f), Vec3(midX - 0.2f, yBottom + 0.4f, z2 + 0.05f),
                                        Vec3(midX - 0.2f, yTop, z2 + 0.05f), Vec3(secX1, yTop, z2 + 0.05f),
                                        windowColor, eye, target, aspect, screenW, screenH
                                    )
                                    // Window Sill
                                    addQuadPolygon(
                                        Vec3(secX1 - 0.12f, yBottom + 0.32f, z2 + 0.08f), Vec3(midX - 0.12f, yBottom + 0.32f, z2 + 0.08f),
                                        Vec3(midX - 0.12f, yBottom + 0.40f, z2 + 0.02f), Vec3(secX1 - 0.12f, yBottom + 0.40f, z2 + 0.02f),
                                        windowSillColor, eye, target, aspect, screenW, screenH
                                    )

                                    // Window 2 Frame & Sill
                                    addQuadPolygon(
                                        Vec3(midX + 0.15f, yBottom + 0.35f, z2 + 0.035f), Vec3(secX2 + fOff, yBottom + 0.35f, z2 + 0.035f),
                                        Vec3(secX2 + fOff, yTop + fOff, z2 + 0.035f), Vec3(midX + 0.15f, yTop + fOff, z2 + 0.035f),
                                        windowFrameColor, eye, target, aspect, screenW, screenH
                                    )
                                    addQuadPolygon(
                                        Vec3(midX + 0.2f, yBottom + 0.4f, z2 + 0.05f), Vec3(secX2, yBottom + 0.4f, z2 + 0.05f),
                                        Vec3(secX2, yTop, z2 + 0.05f), Vec3(midX + 0.2f, yTop, z2 + 0.05f),
                                        windowColor, eye, target, aspect, screenW, screenH
                                    )
                                    // Window Sill
                                    addQuadPolygon(
                                        Vec3(midX + 0.12f, yBottom + 0.32f, z2 + 0.08f), Vec3(secX2 + 0.12f, yBottom + 0.32f, z2 + 0.08f),
                                        Vec3(secX2 + 0.12f, yBottom + 0.40f, z2 + 0.02f), Vec3(midX + 0.12f, yBottom + 0.40f, z2 + 0.02f),
                                        windowSillColor, eye, target, aspect, screenW, screenH
                                    )
                                }
                            }

                            // Rear Facade Windows
                            if (eye.z < z1) {
                                addQuadPolygon(
                                    Vec3(secX1, yBottom + 0.4f, z1 - 0.04f), Vec3(secX2, yBottom + 0.4f, z1 - 0.04f),
                                    Vec3(secX2, yTop, z1 - 0.04f), Vec3(secX1, yTop, z1 - 0.04f),
                                    windowColor, eye, target, aspect, screenW, screenH
                                )
                            }
                        }
                    }

                    // Side Wall Windows (Left & Right) for 3D depth!
                    val depthZ = z2 - z1
                    if (depthZ >= 10f) {
                        val numSideSec = maxOf(1, (depthZ / 10f).toInt())
                        val sideSecW = depthZ / numSideSec
                        for (floor in 1 until b.stories) {
                            val yBottom = floor * storyH + 0.4f
                            val yTop = yBottom + storyH * 0.60f
                            for (ss in 0 until numSideSec) {
                                val sZ1 = z1 + ss * sideSecW + 1.2f
                                val sZ2 = sZ1 + sideSecW - 2.4f
                                if (sZ2 <= sZ1 + 0.8f) continue

                                if (eye.x < x1) {
                                    addQuadPolygon(
                                        Vec3(x1 - 0.04f, yBottom, sZ1), Vec3(x1 - 0.04f, yBottom, sZ2),
                                        Vec3(x1 - 0.04f, yTop, sZ2), Vec3(x1 - 0.04f, yTop, sZ1),
                                        windowColor, eye, target, aspect, screenW, screenH
                                    )
                                }
                                if (eye.x > x2) {
                                    addQuadPolygon(
                                        Vec3(x2 + 0.04f, yBottom, sZ2), Vec3(x2 + 0.04f, yBottom, sZ1),
                                        Vec3(x2 + 0.04f, yTop, sZ1), Vec3(x2 + 0.04f, yTop, sZ2),
                                        windowColor, eye, target, aspect, screenW, screenH
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 3. Roof Structures: Flat Asphalt Deck, Roof Parapet Border, Coping Caps & Equipment
            if (b.roofType == RoofType.PITCHED_RED) {
                if (eye.y > h) {
                    val midZ = (z1 + z2) / 2f
                    val apexH = h + 6.5f
                    val apexL = Vec3(x1, apexH, midZ)
                    val apexR = Vec3(x2, apexH, midZ)

                    addQuadPolygon(v010, v110, apexR, apexL, roofColor, eye, target, aspect, screenW, screenH)
                    addQuadPolygon(apexL, apexR, v111, v011, roofColor, eye, target, aspect, screenW, screenH)
                }
            } else {
                // Flat Roof Asphalt Surface
                if (eye.y > h - 1.0f) {
                    addQuadPolygon(v010, v011, v111, v110, roofColor, eye, target, aspect, screenW, screenH)

                    // 4. Parapet Perimeter Walls around Roof Edge (+0.85m height with Coping Cap)
                    val paraH = h + 0.85f
                    val paraColor = wallColor
                    val capColor = Color(0xFF1E293B) // Dark stone coping cap

                    // Front Parapet Inner/Outer Ledge
                    addQuadPolygon(
                        Vec3(x1, h, z2), Vec3(x2, h, z2),
                        Vec3(x2, paraH, z2), Vec3(x1, paraH, z2),
                        paraColor, eye, target, aspect, screenW, screenH
                    )
                    addQuadPolygon(
                        Vec3(x1 - 0.1f, paraH, z2 + 0.1f), Vec3(x2 + 0.1f, paraH, z2 + 0.1f),
                        Vec3(x2 + 0.1f, paraH + 0.1f, z2 - 0.2f), Vec3(x1 - 0.1f, paraH + 0.1f, z2 - 0.2f),
                        capColor, eye, target, aspect, screenW, screenH
                    )
                    // Back Parapet
                    addQuadPolygon(
                        Vec3(x1, h, z1), Vec3(x2, h, z1),
                        Vec3(x2, paraH, z1), Vec3(x1, paraH, z1),
                        paraColor, eye, target, aspect, screenW, screenH
                    )
                    // Left Parapet
                    addQuadPolygon(
                        Vec3(x1, h, z1), Vec3(x1, h, z2),
                        Vec3(x1, paraH, z2), Vec3(x1, paraH, z1),
                        paraColor, eye, target, aspect, screenW, screenH
                    )
                    // Right Parapet
                    addQuadPolygon(
                        Vec3(x2, h, z1), Vec3(x2, h, z2),
                        Vec3(x2, paraH, z2), Vec3(x2, paraH, z1),
                        paraColor, eye, target, aspect, screenW, screenH
                    )

                    // 5. Protruding Elevator Shaft / Stairwell Towers & Water Tanks on Roof
                    val len = x2 - x1
                    if (len >= 20f && distSq < 320f * 320f) {
                        val numTowers = minOf(5, maxOf(2, (len / 22f).toInt()))
                        val towerW = 2.2f
                        val towerD = 2.8f
                        val towerH = 2.2f
                        val midZ = (z1 + z2) / 2f

                        val spacing = (len - towerW) / (numTowers + 1)
                        for (i in 1..numTowers) {
                            val twX = x1 + spacing * i
                            val twZ = midZ - towerD / 2f

                            val t000 = Vec3(twX, h, twZ)
                            val t100 = Vec3(twX + towerW, h, twZ)
                            val t101 = Vec3(twX + towerW, h, twZ + towerD)
                            val t001 = Vec3(twX, h, twZ + towerD)

                            val t010 = Vec3(twX, h + towerH, twZ)
                            val t110 = Vec3(twX + towerW, h + towerH, twZ)
                            val t111 = Vec3(twX + towerW, h + towerH, twZ + towerD)
                            val t011 = Vec3(twX, h + towerH, twZ + towerD)

                            // Tower Walls
                            addQuadPolygon(t001, t101, t111, t011, wallColor, eye, target, aspect, screenW, screenH)
                            addQuadPolygon(t100, t000, t010, t110, wallColor, eye, target, aspect, screenW, screenH)
                            addQuadPolygon(t000, t001, t011, t010, wallColor, eye, target, aspect, screenW, screenH)
                            addQuadPolygon(t101, t100, t110, t111, wallColor, eye, target, aspect, screenW, screenH)

                            // Tower Hollow Vent Top Cap (Dark)
                            val topVentColor = Color(0xFF1B1E26)
                            addQuadPolygon(t010, t011, t111, t110, topVentColor, eye, target, aspect, screenW, screenH)

                            // Galvanized Metallic Water Storage Tank ("منبع آب پشت بام") next to tower
                            val tkX = twX + towerW + 1.2f
                            val tkZ = twZ + 0.4f
                            if (tkX + 1.5f < x2) {
                                val tkR = 0.7f
                                val tkH = 1.4f
                                val tkColor = Color(0xFFCBD5E1) // Galvanized silver
                                addQuadPolygon(
                                    Vec3(tkX - tkR, h + 0.4f, tkZ - tkR), Vec3(tkX + tkR, h + 0.4f, tkZ - tkR),
                                    Vec3(tkX + tkR, h + 0.4f + tkH, tkZ - tkR), Vec3(tkX - tkR, h + 0.4f + tkH, tkZ - tkR),
                                    tkColor, eye, target, aspect, screenW, screenH
                                )
                                addQuadPolygon(
                                    Vec3(tkX - tkR, h + 0.4f, tkZ + tkR), Vec3(tkX + tkR, h + 0.4f, tkZ + tkR),
                                    Vec3(tkX + tkR, h + 0.4f + tkH, tkZ + tkR), Vec3(tkX - tkR, h + 0.4f + tkH, tkZ + tkR),
                                    tkColor, eye, target, aspect, screenW, screenH
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun collectCarPolygons(
        car: CarState, eye: Vec3, target: Vec3, aspect: Float, screenW: Float, screenH: Float
    ) {
        when (car.vehicleType) {
            VehicleType.PRIDE_131 -> collectPrideCarPolygons(car, eye, target, aspect, screenW, screenH)
            VehicleType.PEUGEOT_405 -> collectPeugeot405Polygons(car, eye, target, aspect, screenW, screenH)
            VehicleType.PEUGEOT_PARS -> collectPeugeotParsPolygons(car, eye, target, aspect, screenW, screenH)
            VehicleType.DENA_PLUS -> collectDenaPlusPolygons(car, eye, target, aspect, screenW, screenH)
            VehicleType.TOYOTA_LAND_CRUISER -> collectLandCruiserPolygons(car, eye, target, aspect, screenW, screenH)
            VehicleType.TOYOTA_HILUX -> collectHiluxPolygons(car, eye, target, aspect, screenW, screenH)
        }
    }

    private fun addIranianLicensePlate(
        carMat: Mat4, posOffset: Vec3, width: Float, height: Float,
        eye: Vec3, target: Vec3, aspect: Float, screenW: Float, screenH: Float,
        isFront: Boolean = true
    ) {
        val plateWhite = Color(0xFFF8FAFC)
        val plateBlue = Color(0xFF1E3A8A)
        val halfW = width / 2f
        val halfH = height / 2f
        val zOff1 = if (isFront) 0.02f else -0.02f
        val zOff2 = if (isFront) 0.025f else -0.025f

        val p1 = carMat.transformPoint(posOffset + Vec3(-halfW, -halfH, zOff1))
        val p2 = carMat.transformPoint(posOffset + Vec3(halfW, -halfH, zOff1))
        val p3 = carMat.transformPoint(posOffset + Vec3(halfW, halfH, zOff1))
        val p4 = carMat.transformPoint(posOffset + Vec3(-halfW, halfH, zOff1))
        addQuadPolygon(p1, p2, p3, p4, plateWhite, eye, target, aspect, screenW, screenH)

        val b1 = carMat.transformPoint(posOffset + Vec3(-halfW, -halfH, zOff2))
        val b2 = carMat.transformPoint(posOffset + Vec3(-halfW + width * 0.18f, -halfH, zOff2))
        val b3 = carMat.transformPoint(posOffset + Vec3(-halfW + width * 0.18f, halfH, zOff2))
        val b4 = carMat.transformPoint(posOffset + Vec3(-halfW, halfH, zOff2))
        addQuadPolygon(b1, b2, b3, b4, plateBlue, eye, target, aspect, screenW, screenH)
    }

    private fun addSideMirrors(
        carMat: Mat4, halfW: Float, yPos: Float, zPos: Float, mirrorColor: Color,
        eye: Vec3, target: Vec3, aspect: Float, screenW: Float, screenH: Float
    ) {
        // Left Mirror
        val mL1 = carMat.transformPoint(Vec3(-halfW - 0.01f, yPos, zPos))
        val mL2 = carMat.transformPoint(Vec3(-halfW - 0.18f, yPos + 0.02f, zPos - 0.06f))
        val mL3 = carMat.transformPoint(Vec3(-halfW - 0.18f, yPos + 0.14f, zPos - 0.06f))
        val mL4 = carMat.transformPoint(Vec3(-halfW - 0.01f, yPos + 0.12f, zPos))
        addQuadPolygon(mL1, mL2, mL3, mL4, mirrorColor, eye, target, aspect, screenW, screenH)

        // Right Mirror
        val mR1 = carMat.transformPoint(Vec3(halfW + 0.01f, yPos, zPos))
        val mR2 = carMat.transformPoint(Vec3(halfW + 0.18f, yPos + 0.02f, zPos - 0.06f))
        val mR3 = carMat.transformPoint(Vec3(halfW + 0.18f, yPos + 0.14f, zPos - 0.06f))
        val mR4 = carMat.transformPoint(Vec3(halfW + 0.01f, yPos + 0.12f, zPos))
        addQuadPolygon(mR1, mR2, mR3, mR4, mirrorColor, eye, target, aspect, screenW, screenH)
    }

    private fun addDetailed3DWheels(
        carMat: Mat4,
        wheelOffsets: Array<Vec3>,
        rimStyle: RimStyle,
        tireRadius: Float = 0.28f,
        eye: Vec3, target: Vec3, aspect: Float, screenW: Float, screenH: Float
    ) {
        val rubberColor = Color(0xFF0F172A)
        val brakeDiscColor = Color(0xFF475569)
        val caliperColor = Color(0xFFDC2626) // Sport Red Brake Caliper
        val mainRimColor = Color(rimStyle.rimColorHex)

        for (wOff in wheelOffsets) {
            val isLeftWheel = wOff.x < 0f
            val sideSign = if (isLeftWheel) -1f else 1f

            // 1. Rubber Tire Outer Box
            val w1 = carMat.transformPoint(wOff + Vec3(-0.06f, -tireRadius * 0.45f, -tireRadius))
            val w2 = carMat.transformPoint(wOff + Vec3(0.06f, -tireRadius * 0.45f, -tireRadius))
            val w3 = carMat.transformPoint(wOff + Vec3(0.06f, tireRadius * 1.05f, tireRadius))
            val w4 = carMat.transformPoint(wOff + Vec3(-0.06f, tireRadius * 1.05f, tireRadius))
            addQuadPolygon(w1, w2, w3, w4, rubberColor, eye, target, aspect, screenW, screenH)

            // 2. Brake Disc & Red Caliper
            val bd1 = carMat.transformPoint(wOff + Vec3(sideSign * 0.02f, 0.0f, -tireRadius * 0.65f))
            val bd2 = carMat.transformPoint(wOff + Vec3(sideSign * 0.04f, 0.0f, -tireRadius * 0.65f))
            val bd3 = carMat.transformPoint(wOff + Vec3(sideSign * 0.04f, tireRadius * 0.7f, tireRadius * 0.65f))
            val bd4 = carMat.transformPoint(wOff + Vec3(sideSign * 0.02f, tireRadius * 0.7f, tireRadius * 0.65f))
            addQuadPolygon(bd1, bd2, bd3, bd4, brakeDiscColor, eye, target, aspect, screenW, screenH)

            val cal1 = carMat.transformPoint(wOff + Vec3(sideSign * 0.05f, tireRadius * 0.35f, -tireRadius * 0.3f))
            val cal2 = carMat.transformPoint(wOff + Vec3(sideSign * 0.07f, tireRadius * 0.35f, -tireRadius * 0.3f))
            val cal3 = carMat.transformPoint(wOff + Vec3(sideSign * 0.07f, tireRadius * 0.75f, 0.0f))
            val cal4 = carMat.transformPoint(wOff + Vec3(sideSign * 0.05f, tireRadius * 0.75f, 0.0f))
            addQuadPolygon(cal1, cal2, cal3, cal4, caliperColor, eye, target, aspect, screenW, screenH)

            // 3. Rim Outer Ring
            val rimX = sideSign * 0.065f
            val r1 = carMat.transformPoint(wOff + Vec3(rimX, 0.02f, -tireRadius * 0.62f))
            val r2 = carMat.transformPoint(wOff + Vec3(rimX + sideSign * 0.015f, 0.02f, -tireRadius * 0.62f))
            val r3 = carMat.transformPoint(wOff + Vec3(rimX + sideSign * 0.015f, tireRadius * 0.82f, tireRadius * 0.62f))
            val r4 = carMat.transformPoint(wOff + Vec3(rimX, tireRadius * 0.82f, tireRadius * 0.62f))
            addQuadPolygon(r1, r2, r3, r4, mainRimColor, eye, target, aspect, screenW, screenH)

            // 4. Rim Center Hub Accent
            val hubX = sideSign * 0.082f
            val h1 = carMat.transformPoint(wOff + Vec3(hubX, tireRadius * 0.30f, -tireRadius * 0.20f))
            val h2 = carMat.transformPoint(wOff + Vec3(hubX, tireRadius * 0.30f, tireRadius * 0.20f))
            val h3 = carMat.transformPoint(wOff + Vec3(hubX, tireRadius * 0.55f, tireRadius * 0.20f))
            val h4 = carMat.transformPoint(wOff + Vec3(hubX, tireRadius * 0.55f, -tireRadius * 0.20f))
            val hubColor = if (rimStyle == RimStyle.GOLD_MESH) Color(0xFFFEF08A) else Color(0xFF0284C7)
            addQuadPolygon(h1, h2, h3, h4, hubColor, eye, target, aspect, screenW, screenH)
        }
    }

    private fun addDecalsAndStripes(
        carMat: Mat4,
        decalStyle: DecalStyle,
        halfW: Float, halfL: Float, h: Float,
        eye: Vec3, target: Vec3, aspect: Float, screenW: Float, screenH: Float
    ) {
        if (decalStyle == DecalStyle.NONE) return

        val stripeColor = if (decalStyle == DecalStyle.RACING_STRIPE) Color(0xFFF8FAFC) else Color(0xFFEAB308)
        val zOff = 0.02f

        if (decalStyle == DecalStyle.RACING_STRIPE) {
            val stripeW = 0.18f
            val gap = 0.06f

            // Left Hood Stripe
            val hl1 = carMat.transformPoint(Vec3(-gap - stripeW, 0.39f, halfL - 0.05f))
            val hl2 = carMat.transformPoint(Vec3(-gap, 0.39f, halfL - 0.05f))
            val hl3 = carMat.transformPoint(Vec3(-gap, h * 0.56f, halfL * 0.38f + zOff))
            val hl4 = carMat.transformPoint(Vec3(-gap - stripeW, h * 0.56f, halfL * 0.38f + zOff))
            addQuadPolygon(hl1, hl2, hl3, hl4, stripeColor, eye, target, aspect, screenW, screenH)

            // Right Hood Stripe
            val hr1 = carMat.transformPoint(Vec3(gap, 0.39f, halfL - 0.05f))
            val hr2 = carMat.transformPoint(Vec3(gap + stripeW, 0.39f, halfL - 0.05f))
            val hr3 = carMat.transformPoint(Vec3(gap + stripeW, h * 0.56f, halfL * 0.38f + zOff))
            val hr4 = carMat.transformPoint(Vec3(gap, h * 0.56f, halfL * 0.38f + zOff))
            addQuadPolygon(hr1, hr2, hr3, hr4, stripeColor, eye, target, aspect, screenW, screenH)

            // Roof Dual Stripes
            val rf1 = carMat.transformPoint(Vec3(-gap - stripeW, h + 0.01f, halfL * 0.08f))
            val rf2 = carMat.transformPoint(Vec3(gap + stripeW, h + 0.01f, halfL * 0.08f))
            val rf3 = carMat.transformPoint(Vec3(gap + stripeW, h + 0.01f, -halfL * 0.45f))
            val rf4 = carMat.transformPoint(Vec3(-gap - stripeW, h + 0.01f, -halfL * 0.45f))
            addQuadPolygon(rf1, rf2, rf3, rf4, stripeColor, eye, target, aspect, screenW, screenH)
        } else if (decalStyle == DecalStyle.TURBO_BADGE) {
            val sideY1 = 0.28f
            val sideY2 = 0.38f
            val tL1 = carMat.transformPoint(Vec3(-halfW - 0.015f, sideY1, -halfL * 0.5f))
            val tL2 = carMat.transformPoint(Vec3(-halfW - 0.015f, sideY1, halfL * 0.5f))
            val tL3 = carMat.transformPoint(Vec3(-halfW - 0.015f, sideY2, halfL * 0.4f))
            val tL4 = carMat.transformPoint(Vec3(-halfW - 0.015f, sideY2, -halfL * 0.5f))
            addQuadPolygon(tL1, tL2, tL3, tL4, Color(0xFF38BDF8), eye, target, aspect, screenW, screenH)

            val tR1 = carMat.transformPoint(Vec3(halfW + 0.015f, sideY1, -halfL * 0.5f))
            val tR2 = carMat.transformPoint(Vec3(halfW + 0.015f, sideY1, halfL * 0.5f))
            val tR3 = carMat.transformPoint(Vec3(halfW + 0.015f, sideY2, halfL * 0.4f))
            val tR4 = carMat.transformPoint(Vec3(halfW + 0.015f, sideY2, -halfL * 0.5f))
            addQuadPolygon(tR1, tR2, tR3, tR4, Color(0xFF38BDF8), eye, target, aspect, screenW, screenH)
        }
    }

    private fun addCustomSpoiler(
        carMat: Mat4,
        spoilerStyle: SpoilerStyle,
        halfW: Float, halfL: Float, h: Float,
        eye: Vec3, target: Vec3, aspect: Float, screenW: Float, screenH: Float
    ) {
        if (spoilerStyle == SpoilerStyle.DEFAULT) return

        val carbonColor = Color(0xFF1E293B)
        val lipColor = Color(0xFF0F172A)

        if (spoilerStyle == SpoilerStyle.SPORT_LIP) {
            val sp1 = carMat.transformPoint(Vec3(-halfW * 0.88f, h * 0.55f, -halfL - 0.04f))
            val sp2 = carMat.transformPoint(Vec3(halfW * 0.88f, h * 0.55f, -halfL - 0.04f))
            val sp3 = carMat.transformPoint(Vec3(halfW * 0.88f, h * 0.59f, -halfL + 0.02f))
            val sp4 = carMat.transformPoint(Vec3(-halfW * 0.88f, h * 0.59f, -halfL + 0.02f))
            addQuadPolygon(sp1, sp2, sp3, sp4, lipColor, eye, target, aspect, screenW, screenH)
        } else if (spoilerStyle == SpoilerStyle.GT_WING) {
            val stL1 = carMat.transformPoint(Vec3(-halfW * 0.5f, h * 0.52f, -halfL * 0.85f))
            val stL2 = carMat.transformPoint(Vec3(-halfW * 0.5f, h * 0.52f, -halfL * 0.95f))
            val stL3 = carMat.transformPoint(Vec3(-halfW * 0.5f, h * 0.78f, -halfL * 0.95f))
            val stL4 = carMat.transformPoint(Vec3(-halfW * 0.5f, h * 0.78f, -halfL * 0.85f))
            addQuadPolygon(stL1, stL2, stL3, stL4, Color(0xFF0F172A), eye, target, aspect, screenW, screenH)

            val stR1 = carMat.transformPoint(Vec3(halfW * 0.5f, h * 0.52f, -halfL * 0.85f))
            val stR2 = carMat.transformPoint(Vec3(halfW * 0.5f, h * 0.52f, -halfL * 0.95f))
            val stR3 = carMat.transformPoint(Vec3(halfW * 0.5f, h * 0.78f, -halfL * 0.95f))
            val stR4 = carMat.transformPoint(Vec3(halfW * 0.5f, h * 0.78f, -halfL * 0.85f))
            addQuadPolygon(stR1, stR2, stR3, stR4, Color(0xFF0F172A), eye, target, aspect, screenW, screenH)

            val wg1 = carMat.transformPoint(Vec3(-halfW * 1.05f, h * 0.78f, -halfL * 0.80f))
            val wg2 = carMat.transformPoint(Vec3(halfW * 1.05f, h * 0.78f, -halfL * 0.80f))
            val wg3 = carMat.transformPoint(Vec3(halfW * 1.05f, h * 0.82f, -halfL * 1.02f))
            val wg4 = carMat.transformPoint(Vec3(-halfW * 1.05f, h * 0.82f, -halfL * 1.02f))
            addQuadPolygon(wg1, wg2, wg3, wg4, carbonColor, eye, target, aspect, screenW, screenH)
        }
    }

    private fun addDoorHandlesAndWipers(
        carMat: Mat4, halfW: Float, halfL: Float, h: Float,
        eye: Vec3, target: Vec3, aspect: Float, screenW: Float, screenH: Float
    ) {
        val handleColor = Color(0xFF334155)
        val wiperColor = Color(0xFF0F172A)

        val hL1 = carMat.transformPoint(Vec3(-halfW - 0.015f, h * 0.50f, halfL * 0.15f))
        val hL2 = carMat.transformPoint(Vec3(-halfW - 0.015f, h * 0.50f, halfL * 0.25f))
        val hL3 = carMat.transformPoint(Vec3(-halfW - 0.015f, h * 0.54f, halfL * 0.25f))
        val hL4 = carMat.transformPoint(Vec3(-halfW - 0.015f, h * 0.54f, halfL * 0.15f))
        addQuadPolygon(hL1, hL2, hL3, hL4, handleColor, eye, target, aspect, screenW, screenH)

        val hR1 = carMat.transformPoint(Vec3(halfW + 0.015f, h * 0.50f, halfL * 0.15f))
        val hR2 = carMat.transformPoint(Vec3(halfW + 0.015f, h * 0.50f, halfL * 0.25f))
        val hR3 = carMat.transformPoint(Vec3(halfW + 0.015f, h * 0.54f, halfL * 0.25f))
        val hR4 = carMat.transformPoint(Vec3(halfW + 0.015f, h * 0.54f, halfL * 0.15f))
        addQuadPolygon(hR1, hR2, hR3, hR4, handleColor, eye, target, aspect, screenW, screenH)

        val wp1 = carMat.transformPoint(Vec3(-halfW * 0.6f, h * 0.58f, halfL * 0.36f))
        val wp2 = carMat.transformPoint(Vec3(-halfW * 0.1f, h * 0.62f, halfL * 0.32f))
        val wp3 = carMat.transformPoint(Vec3(-halfW * 0.1f, h * 0.64f, halfL * 0.32f))
        val wp4 = carMat.transformPoint(Vec3(-halfW * 0.6f, h * 0.60f, halfL * 0.36f))
        addQuadPolygon(wp1, wp2, wp3, wp4, wiperColor, eye, target, aspect, screenW, screenH)
    }

    /**
     * 3D SAIPA PRIDE 131 MODEL
     */
    private fun collectPrideCarPolygons(
        car: CarState, eye: Vec3, target: Vec3, aspect: Float, screenW: Float, screenH: Float
    ) {
        val carMat = Mat4.translation(car.pos.x, car.pos.y, car.pos.z)
            .multiply(Mat4.rotationY(-car.headingAngleRad))

        val halfW = car.width / 2f
        val halfL = car.length / 2f
        val h = car.height

        val bodyColor = Color(car.customBodyColorHex)
        val bodyShadow = bodyColor.copy(red = (bodyColor.red * 0.82f).coerceIn(0f, 1f), green = (bodyColor.green * 0.82f).coerceIn(0f, 1f), blue = (bodyColor.blue * 0.82f).coerceIn(0f, 1f))
        val blackTrim = Color(0xFF1E293B)
        val glassColor = Color(0xFF020617)
        val chromeColor = Color(0xFF94A3B8)
        val headlightColor = Color(0xFFFEF08A)
        val brakeLightColor = if (car.brakeInput > 0.1f) Color(0xFFFF0000) else Color(0xFF990000)

        // Drop Shadow
        val shadowRelY = -0.09f
        val sOut1 = carMat.transformPoint(Vec3(-halfW * 1.15f, shadowRelY, -halfL * 1.08f))
        val sOut2 = carMat.transformPoint(Vec3(halfW * 1.15f, shadowRelY, -halfL * 1.08f))
        val sOut3 = carMat.transformPoint(Vec3(halfW * 1.15f, shadowRelY, halfL * 1.08f))
        val sOut4 = carMat.transformPoint(Vec3(-halfW * 1.15f, shadowRelY, halfL * 1.08f))
        addQuadPolygon(sOut1, sOut2, sOut3, sOut4, Color(0x33000000), eye, target, aspect, screenW, screenH, layer = 1.3f, avgZBias = 0.002f)

        // Wheels & Customization Body Parts
        val wheelOffsets = arrayOf(
            Vec3(-halfW * 0.95f, -0.05f, halfL * 0.60f),
            Vec3(halfW * 0.95f, -0.05f, halfL * 0.60f),
            Vec3(-halfW * 0.95f, -0.05f, -halfL * 0.60f),
            Vec3(halfW * 0.95f, -0.05f, -halfL * 0.60f)
        )
        addDetailed3DWheels(carMat, wheelOffsets, car.selectedRimStyle, tireRadius = 0.26f, eye = eye, target = target, aspect = aspect, screenW = screenW, screenH = screenH)
        addDecalsAndStripes(carMat, car.selectedDecalStyle, halfW, halfL, h, eye, target, aspect, screenW, screenH)
        addCustomSpoiler(carMat, car.selectedSpoilerStyle, halfW, halfL, h, eye, target, aspect, screenW, screenH)
        addDoorHandlesAndWipers(carMat, halfW, halfL, h, eye, target, aspect, screenW, screenH)

        // Front & Rear Black Bumpers
        val b001 = carMat.transformPoint(Vec3(-halfW, 0.12f, halfL))
        val b101 = carMat.transformPoint(Vec3(halfW, 0.12f, halfL))
        val b111 = carMat.transformPoint(Vec3(halfW, 0.38f, halfL))
        val b011 = carMat.transformPoint(Vec3(-halfW, 0.38f, halfL))
        addQuadPolygon(b001, b101, b111, b011, blackTrim, eye, target, aspect, screenW, screenH)

        val b000 = carMat.transformPoint(Vec3(-halfW, 0.12f, -halfL))
        val b100 = carMat.transformPoint(Vec3(halfW, 0.12f, -halfL))
        val b110 = carMat.transformPoint(Vec3(halfW, 0.38f, -halfL))
        val b010 = carMat.transformPoint(Vec3(-halfW, 0.38f, -halfL))
        addQuadPolygon(b100, b000, b010, b110, blackTrim, eye, target, aspect, screenW, screenH)

        // License Plates (Front & Rear)
        addIranianLicensePlate(carMat, Vec3(0f, 0.24f, halfL + 0.01f), 0.45f, 0.10f, eye, target, aspect, screenW, screenH, isFront = true)
        addIranianLicensePlate(carMat, Vec3(0f, 0.24f, -halfL - 0.01f), 0.45f, 0.10f, eye, target, aspect, screenW, screenH, isFront = false)

        // Side Mirrors
        addSideMirrors(carMat, halfW, h * 0.58f, halfL * 0.25f, blackTrim, eye, target, aspect, screenW, screenH)

        // Left & Right Lower Body Side Walls
        val sL1 = carMat.transformPoint(Vec3(-halfW, 0.12f, halfL))
        val sL2 = carMat.transformPoint(Vec3(-halfW, h * 0.58f, halfL))
        val sL3 = carMat.transformPoint(Vec3(-halfW, h * 0.58f, -halfL))
        val sL4 = carMat.transformPoint(Vec3(-halfW, 0.12f, -halfL))
        addQuadPolygon(sL1, sL2, sL3, sL4, bodyShadow, eye, target, aspect, screenW, screenH)

        val sR1 = carMat.transformPoint(Vec3(halfW, 0.12f, halfL))
        val sR2 = carMat.transformPoint(Vec3(halfW, 0.12f, -halfL))
        val sR3 = carMat.transformPoint(Vec3(halfW, h * 0.58f, -halfL))
        val sR4 = carMat.transformPoint(Vec3(halfW, h * 0.58f, halfL))
        addQuadPolygon(sR1, sR2, sR3, sR4, bodyColor, eye, target, aspect, screenW, screenH)

        // 1. Hood (from Front Bumper to Windshield Base)
        val hd1 = carMat.transformPoint(Vec3(-halfW, 0.38f, halfL))
        val hd2 = carMat.transformPoint(Vec3(halfW, 0.38f, halfL))
        val hd3 = carMat.transformPoint(Vec3(halfW * 0.85f, h * 0.58f, halfL * 0.35f))
        val hd4 = carMat.transformPoint(Vec3(-halfW * 0.85f, h * 0.58f, halfL * 0.35f))
        addQuadPolygon(hd1, hd2, hd3, hd4, bodyColor, eye, target, aspect, screenW, screenH)

        // 2. Windshield
        val w1 = carMat.transformPoint(Vec3(-halfW * 0.85f, h * 0.58f, halfL * 0.35f))
        val w2 = carMat.transformPoint(Vec3(halfW * 0.85f, h * 0.58f, halfL * 0.35f))
        val w3 = carMat.transformPoint(Vec3(-halfW * 0.72f, h, halfL * 0.08f))
        val w4 = carMat.transformPoint(Vec3(halfW * 0.72f, h, halfL * 0.08f))
        addQuadPolygon(w1, w2, w4, w3, glassColor, eye, target, aspect, screenW, screenH)

        // 3. Roof
        val r1 = carMat.transformPoint(Vec3(-halfW * 0.72f, h, -halfL * 0.45f))
        val r2 = carMat.transformPoint(Vec3(halfW * 0.72f, h, -halfL * 0.45f))
        addQuadPolygon(w3, w4, r2, r1, bodyColor, eye, target, aspect, screenW, screenH)

        // 4. Rear Glass Window
        val rw1 = carMat.transformPoint(Vec3(-halfW * 0.80f, h * 0.58f, -halfL * 0.75f))
        val rw2 = carMat.transformPoint(Vec3(halfW * 0.80f, h * 0.58f, -halfL * 0.75f))
        addQuadPolygon(r1, r2, rw2, rw1, glassColor, eye, target, aspect, screenW, screenH)

        // 5. Trunk Lid (from Rear Glass to Rear Bumper)
        val tk1 = carMat.transformPoint(Vec3(-halfW * 0.80f, h * 0.58f, -halfL * 0.75f))
        val tk2 = carMat.transformPoint(Vec3(halfW * 0.80f, h * 0.58f, -halfL * 0.75f))
        val tk3 = carMat.transformPoint(Vec3(halfW, 0.38f, -halfL))
        val tk4 = carMat.transformPoint(Vec3(-halfW, 0.38f, -halfL))
        addQuadPolygon(tk1, tk2, tk3, tk4, bodyColor, eye, target, aspect, screenW, screenH)

        // Side Glass Windows (Left & Right)
        val sgL1 = carMat.transformPoint(Vec3(-halfW - 0.01f, h * 0.62f, -halfL * 0.65f))
        val sgL2 = carMat.transformPoint(Vec3(-halfW - 0.01f, h * 0.62f, halfL * 0.30f))
        val sgL3 = carMat.transformPoint(Vec3(-halfW * 0.74f, h - 0.02f, halfL * 0.08f))
        val sgL4 = carMat.transformPoint(Vec3(-halfW * 0.74f, h - 0.02f, -halfL * 0.45f))
        addQuadPolygon(sgL1, sgL2, sgL3, sgL4, glassColor, eye, target, aspect, screenW, screenH)

        val sgR1 = carMat.transformPoint(Vec3(halfW + 0.01f, h * 0.62f, -halfL * 0.65f))
        val sgR2 = carMat.transformPoint(Vec3(halfW + 0.01f, h * 0.62f, halfL * 0.30f))
        val sgR3 = carMat.transformPoint(Vec3(halfW * 0.74f, h - 0.02f, halfL * 0.08f))
        val sgR4 = carMat.transformPoint(Vec3(halfW * 0.74f, h - 0.02f, -halfL * 0.45f))
        addQuadPolygon(sgR1, sgR2, sgR3, sgR4, glassColor, eye, target, aspect, screenW, screenH)

        // Side Black Protective Moulding
        val stL1 = carMat.transformPoint(Vec3(-halfW - 0.02f, 0.46f, -halfL * 0.7f))
        val stL2 = carMat.transformPoint(Vec3(-halfW - 0.02f, 0.46f, halfL * 0.7f))
        val stL3 = carMat.transformPoint(Vec3(-halfW - 0.02f, 0.52f, halfL * 0.7f))
        val stL4 = carMat.transformPoint(Vec3(-halfW - 0.02f, 0.52f, -halfL * 0.7f))
        addQuadPolygon(stL1, stL2, stL3, stL4, blackTrim, eye, target, aspect, screenW, screenH)

        val stR1 = carMat.transformPoint(Vec3(halfW + 0.02f, 0.46f, -halfL * 0.7f))
        val stR2 = carMat.transformPoint(Vec3(halfW + 0.02f, 0.46f, halfL * 0.7f))
        val stR3 = carMat.transformPoint(Vec3(halfW + 0.02f, 0.52f, halfL * 0.7f))
        val stR4 = carMat.transformPoint(Vec3(halfW + 0.02f, 0.52f, -halfL * 0.7f))
        addQuadPolygon(stR1, stR2, stR3, stR4, blackTrim, eye, target, aspect, screenW, screenH)

        // Front Grille & Saipa Badge
        val g1 = carMat.transformPoint(Vec3(-halfW * 0.45f, 0.40f, halfL + 0.02f))
        val g2 = carMat.transformPoint(Vec3(halfW * 0.45f, 0.40f, halfL + 0.02f))
        val g3 = carMat.transformPoint(Vec3(halfW * 0.45f, 0.55f, halfL + 0.02f))
        val g4 = carMat.transformPoint(Vec3(-halfW * 0.45f, 0.55f, halfL + 0.02f))
        addQuadPolygon(g1, g2, g3, g4, blackTrim, eye, target, aspect, screenW, screenH)

        val emb1 = carMat.transformPoint(Vec3(-0.1f, 0.46f, halfL + 0.03f))
        val emb2 = carMat.transformPoint(Vec3(0.1f, 0.46f, halfL + 0.03f))
        val emb3 = carMat.transformPoint(Vec3(0.1f, 0.50f, halfL + 0.03f))
        val emb4 = carMat.transformPoint(Vec3(-0.1f, 0.50f, halfL + 0.03f))
        addQuadPolygon(emb1, emb2, emb3, emb4, chromeColor, eye, target, aspect, screenW, screenH)

        // Headlights
        val hL1 = carMat.transformPoint(Vec3(-halfW * 0.88f, 0.40f, halfL + 0.02f))
        val hL2 = carMat.transformPoint(Vec3(-halfW * 0.48f, 0.40f, halfL + 0.02f))
        val hL3 = carMat.transformPoint(Vec3(-halfW * 0.48f, 0.55f, halfL + 0.02f))
        val hL4 = carMat.transformPoint(Vec3(-halfW * 0.88f, 0.55f, halfL + 0.02f))
        addQuadPolygon(hL1, hL2, hL3, hL4, headlightColor, eye, target, aspect, screenW, screenH)

        val hR1 = carMat.transformPoint(Vec3(halfW * 0.48f, 0.40f, halfL + 0.02f))
        val hR2 = carMat.transformPoint(Vec3(halfW * 0.88f, 0.40f, halfL + 0.02f))
        val hR3 = carMat.transformPoint(Vec3(halfW * 0.88f, 0.55f, halfL + 0.02f))
        val hR4 = carMat.transformPoint(Vec3(halfW * 0.48f, 0.55f, halfL + 0.02f))
        addQuadPolygon(hR1, hR2, hR3, hR4, headlightColor, eye, target, aspect, screenW, screenH)

        // Rear Taillights (Left & Right separate)
        val tL1 = carMat.transformPoint(Vec3(-halfW * 0.88f, 0.42f, -halfL - 0.02f))
        val tL2 = carMat.transformPoint(Vec3(-halfW * 0.50f, 0.42f, -halfL - 0.02f))
        val tL3 = carMat.transformPoint(Vec3(-halfW * 0.50f, 0.58f, -halfL - 0.02f))
        val tL4 = carMat.transformPoint(Vec3(-halfW * 0.88f, 0.58f, -halfL - 0.02f))
        addQuadPolygon(tL1, tL2, tL3, tL4, brakeLightColor, eye, target, aspect, screenW, screenH)

        val tR1 = carMat.transformPoint(Vec3(halfW * 0.50f, 0.42f, -halfL - 0.02f))
        val tR2 = carMat.transformPoint(Vec3(halfW * 0.88f, 0.42f, -halfL - 0.02f))
        val tR3 = carMat.transformPoint(Vec3(halfW * 0.88f, 0.58f, -halfL - 0.02f))
        val tR4 = carMat.transformPoint(Vec3(halfW * 0.50f, 0.58f, -halfL - 0.02f))
        addQuadPolygon(tR1, tR2, tR3, tR4, brakeLightColor, eye, target, aspect, screenW, screenH)

        // Exhaust Tip
        val ex1 = carMat.transformPoint(Vec3(-halfW * 0.60f, 0.16f, -halfL - 0.08f))
        val ex2 = carMat.transformPoint(Vec3(-halfW * 0.48f, 0.16f, -halfL - 0.08f))
        val ex3 = carMat.transformPoint(Vec3(-halfW * 0.48f, 0.22f, -halfL))
        val ex4 = carMat.transformPoint(Vec3(-halfW * 0.60f, 0.22f, -halfL))
        addQuadPolygon(ex1, ex2, ex3, ex4, chromeColor, eye, target, aspect, screenW, screenH)
    }

    /**
     * 3D PEUGEOT 405 GLX MODEL
     */
    private fun collectPeugeot405Polygons(
        car: CarState, eye: Vec3, target: Vec3, aspect: Float, screenW: Float, screenH: Float
    ) {
        val carMat = Mat4.translation(car.pos.x, car.pos.y, car.pos.z)
            .multiply(Mat4.rotationY(-car.headingAngleRad))

        val halfW = car.width / 2f
        val halfL = car.length / 2f
        val h = car.height

        val bodyColor = Color(car.customBodyColorHex)
        val bodyShadow = bodyColor.copy(red = (bodyColor.red * 0.80f).coerceIn(0f, 1f), green = (bodyColor.green * 0.80f).coerceIn(0f, 1f), blue = (bodyColor.blue * 0.80f).coerceIn(0f, 1f))
        val blackTrim = Color(0xFF1E293B)
        val glassColor = Color(0xFF020617)
        val lionGold = Color(0xFFEAB308)
        val headlightColor = Color(0xFFFEF08A)
        val brakeLightColor = if (car.brakeInput > 0.1f) Color(0xFFFF0000) else Color(0xFF990000)

        // Drop Shadow
        val shadowRelY = -0.09f
        val sOut1 = carMat.transformPoint(Vec3(-halfW * 1.15f, shadowRelY, -halfL * 1.08f))
        val sOut2 = carMat.transformPoint(Vec3(halfW * 1.15f, shadowRelY, -halfL * 1.08f))
        val sOut3 = carMat.transformPoint(Vec3(halfW * 1.15f, shadowRelY, halfL * 1.08f))
        val sOut4 = carMat.transformPoint(Vec3(-halfW * 1.15f, shadowRelY, halfL * 1.08f))
        addQuadPolygon(sOut1, sOut2, sOut3, sOut4, Color(0x33000000), eye, target, aspect, screenW, screenH, layer = 1.3f, avgZBias = 0.002f)

        // Wheels & Customization
        val wheelOffsets = arrayOf(
            Vec3(-halfW * 0.95f, -0.05f, halfL * 0.62f),
            Vec3(halfW * 0.95f, -0.05f, halfL * 0.62f),
            Vec3(-halfW * 0.95f, -0.05f, -halfL * 0.62f),
            Vec3(halfW * 0.95f, -0.05f, -halfL * 0.62f)
        )
        addDetailed3DWheels(carMat, wheelOffsets, car.selectedRimStyle, tireRadius = 0.28f, eye = eye, target = target, aspect = aspect, screenW = screenW, screenH = screenH)
        addDecalsAndStripes(carMat, car.selectedDecalStyle, halfW, halfL, h, eye, target, aspect, screenW, screenH)
        addCustomSpoiler(carMat, car.selectedSpoilerStyle, halfW, halfL, h, eye, target, aspect, screenW, screenH)
        addDoorHandlesAndWipers(carMat, halfW, halfL, h, eye, target, aspect, screenW, screenH)

        // Lower Front & Rear Bumpers
        val b001 = carMat.transformPoint(Vec3(-halfW, 0.10f, halfL))
        val b101 = carMat.transformPoint(Vec3(halfW, 0.10f, halfL))
        val b111 = carMat.transformPoint(Vec3(halfW, 0.38f, halfL))
        val b011 = carMat.transformPoint(Vec3(-halfW, 0.38f, halfL))
        addQuadPolygon(b001, b101, b111, b011, blackTrim, eye, target, aspect, screenW, screenH)

        val rb1 = carMat.transformPoint(Vec3(-halfW, 0.10f, -halfL))
        val rb2 = carMat.transformPoint(Vec3(halfW, 0.10f, -halfL))
        val rb3 = carMat.transformPoint(Vec3(halfW, 0.38f, -halfL))
        val rb4 = carMat.transformPoint(Vec3(-halfW, 0.38f, -halfL))
        addQuadPolygon(rb2, rb1, rb4, rb3, blackTrim, eye, target, aspect, screenW, screenH)

        // Iranian License Plates
        addIranianLicensePlate(carMat, Vec3(0f, 0.22f, halfL + 0.01f), 0.48f, 0.11f, eye, target, aspect, screenW, screenH, isFront = true)
        addIranianLicensePlate(carMat, Vec3(0f, 0.22f, -halfL - 0.01f), 0.48f, 0.11f, eye, target, aspect, screenW, screenH, isFront = false)

        // Side Mirrors
        addSideMirrors(carMat, halfW, h * 0.54f, halfL * 0.30f, bodyColor, eye, target, aspect, screenW, screenH)

        // Left & Right Lower Body Sides
        val sL1 = carMat.transformPoint(Vec3(-halfW, 0.10f, halfL))
        val sL2 = carMat.transformPoint(Vec3(-halfW, h * 0.52f, halfL))
        val sL3 = carMat.transformPoint(Vec3(-halfW, h * 0.52f, -halfL))
        val sL4 = carMat.transformPoint(Vec3(-halfW, 0.10f, -halfL))
        addQuadPolygon(sL1, sL2, sL3, sL4, bodyShadow, eye, target, aspect, screenW, screenH)

        val sR1 = carMat.transformPoint(Vec3(halfW, 0.10f, halfL))
        val sR2 = carMat.transformPoint(Vec3(halfW, 0.10f, -halfL))
        val sR3 = carMat.transformPoint(Vec3(halfW, h * 0.52f, -halfL))
        val sR4 = carMat.transformPoint(Vec3(halfW, h * 0.52f, halfL))
        addQuadPolygon(sR1, sR2, sR3, sR4, bodyColor, eye, target, aspect, screenW, screenH)

        // 1. Sleek 405 Hood
        val hd1 = carMat.transformPoint(Vec3(-halfW, 0.38f, halfL))
        val hd2 = carMat.transformPoint(Vec3(halfW, 0.38f, halfL))
        val hd3 = carMat.transformPoint(Vec3(halfW * 0.85f, h * 0.52f, halfL * 0.40f))
        val hd4 = carMat.transformPoint(Vec3(-halfW * 0.85f, h * 0.52f, halfL * 0.40f))
        addQuadPolygon(hd1, hd2, hd3, hd4, bodyColor, eye, target, aspect, screenW, screenH)

        // 2. Windshield
        val w1 = carMat.transformPoint(Vec3(-halfW * 0.85f, h * 0.52f, halfL * 0.40f))
        val w2 = carMat.transformPoint(Vec3(halfW * 0.85f, h * 0.52f, halfL * 0.40f))
        val w3 = carMat.transformPoint(Vec3(-halfW * 0.74f, h, halfL * 0.10f))
        val w4 = carMat.transformPoint(Vec3(halfW * 0.74f, h, halfL * 0.10f))
        addQuadPolygon(w1, w2, w4, w3, glassColor, eye, target, aspect, screenW, screenH)

        // 3. Cabin Roof
        val r1 = carMat.transformPoint(Vec3(-halfW * 0.74f, h, -halfL * 0.40f))
        val r2 = carMat.transformPoint(Vec3(halfW * 0.74f, h, -halfL * 0.40f))
        addQuadPolygon(w3, w4, r2, r1, bodyColor, eye, target, aspect, screenW, screenH)

        // 4. Rear Slanted Window
        val rw1 = carMat.transformPoint(Vec3(-halfW * 0.82f, h * 0.52f, -halfL * 0.70f))
        val rw2 = carMat.transformPoint(Vec3(halfW * 0.82f, h * 0.52f, -halfL * 0.70f))
        addQuadPolygon(r1, r2, rw2, rw1, glassColor, eye, target, aspect, screenW, screenH)

        // 5. Trunk Lid
        val tk1 = carMat.transformPoint(Vec3(-halfW * 0.82f, h * 0.52f, -halfL * 0.70f))
        val tk2 = carMat.transformPoint(Vec3(halfW * 0.82f, h * 0.52f, -halfL * 0.70f))
        val tk3 = carMat.transformPoint(Vec3(halfW, 0.38f, -halfL))
        val tk4 = carMat.transformPoint(Vec3(-halfW, 0.38f, -halfL))
        addQuadPolygon(tk1, tk2, tk3, tk4, bodyColor, eye, target, aspect, screenW, screenH)

        // Side Glass Windows
        val sgL1 = carMat.transformPoint(Vec3(-halfW - 0.01f, h * 0.56f, -halfL * 0.60f))
        val sgL2 = carMat.transformPoint(Vec3(-halfW - 0.01f, h * 0.56f, halfL * 0.35f))
        val sgL3 = carMat.transformPoint(Vec3(-halfW * 0.76f, h - 0.02f, halfL * 0.10f))
        val sgL4 = carMat.transformPoint(Vec3(-halfW * 0.76f, h - 0.02f, -halfL * 0.40f))
        addQuadPolygon(sgL1, sgL2, sgL3, sgL4, glassColor, eye, target, aspect, screenW, screenH)

        val sgR1 = carMat.transformPoint(Vec3(halfW + 0.01f, h * 0.56f, -halfL * 0.60f))
        val sgR2 = carMat.transformPoint(Vec3(halfW + 0.01f, h * 0.56f, halfL * 0.35f))
        val sgR3 = carMat.transformPoint(Vec3(halfW * 0.76f, h - 0.02f, halfL * 0.10f))
        val sgR4 = carMat.transformPoint(Vec3(halfW * 0.76f, h - 0.02f, -halfL * 0.40f))
        addQuadPolygon(sgR1, sgR2, sgR3, sgR4, glassColor, eye, target, aspect, screenW, screenH)

        // Peugeot 3-Slat Front Grille with Golden Lion Badge
        val g1 = carMat.transformPoint(Vec3(-halfW * 0.55f, 0.38f, halfL + 0.02f))
        val g2 = carMat.transformPoint(Vec3(halfW * 0.55f, 0.38f, halfL + 0.02f))
        val g3 = carMat.transformPoint(Vec3(halfW * 0.55f, 0.50f, halfL + 0.02f))
        val g4 = carMat.transformPoint(Vec3(-halfW * 0.55f, 0.50f, halfL + 0.02f))
        addQuadPolygon(g1, g2, g3, g4, blackTrim, eye, target, aspect, screenW, screenH)

        // Peugeot Lion Badge
        val lion1 = carMat.transformPoint(Vec3(-0.12f, 0.42f, halfL + 0.03f))
        val lion2 = carMat.transformPoint(Vec3(0.12f, 0.42f, halfL + 0.03f))
        val lion3 = carMat.transformPoint(Vec3(0.12f, 0.48f, halfL + 0.03f))
        val lion4 = carMat.transformPoint(Vec3(-0.12f, 0.48f, halfL + 0.03f))
        addQuadPolygon(lion1, lion2, lion3, lion4, lionGold, eye, target, aspect, screenW, screenH)

        // 405 Headlights (Trapezoidal slant)
        val hL1 = carMat.transformPoint(Vec3(-halfW * 0.92f, 0.38f, halfL + 0.02f))
        val hL2 = carMat.transformPoint(Vec3(-halfW * 0.58f, 0.38f, halfL + 0.02f))
        val hL3 = carMat.transformPoint(Vec3(-halfW * 0.58f, 0.50f, halfL + 0.02f))
        val hL4 = carMat.transformPoint(Vec3(-halfW * 0.92f, 0.50f, halfL + 0.02f))
        addQuadPolygon(hL1, hL2, hL3, hL4, headlightColor, eye, target, aspect, screenW, screenH)

        val hR1 = carMat.transformPoint(Vec3(halfW * 0.58f, 0.38f, halfL + 0.02f))
        val hR2 = carMat.transformPoint(Vec3(halfW * 0.92f, 0.38f, halfL + 0.02f))
        val hR3 = carMat.transformPoint(Vec3(halfW * 0.92f, 0.50f, halfL + 0.02f))
        val hR4 = carMat.transformPoint(Vec3(halfW * 0.58f, 0.50f, halfL + 0.02f))
        addQuadPolygon(hR1, hR2, hR3, hR4, headlightColor, eye, target, aspect, screenW, screenH)

        // Rear 405 Horizontal Taillights (Left & Right separate)
        val tL1 = carMat.transformPoint(Vec3(-halfW * 0.92f, 0.38f, -halfL - 0.02f))
        val tL2 = carMat.transformPoint(Vec3(-halfW * 0.48f, 0.38f, -halfL - 0.02f))
        val tL3 = carMat.transformPoint(Vec3(-halfW * 0.48f, 0.52f, -halfL - 0.02f))
        val tL4 = carMat.transformPoint(Vec3(-halfW * 0.92f, 0.52f, -halfL - 0.02f))
        addQuadPolygon(tL1, tL2, tL3, tL4, brakeLightColor, eye, target, aspect, screenW, screenH)

        val tR1 = carMat.transformPoint(Vec3(halfW * 0.48f, 0.38f, -halfL - 0.02f))
        val tR2 = carMat.transformPoint(Vec3(halfW * 0.92f, 0.38f, -halfL - 0.02f))
        val tR3 = carMat.transformPoint(Vec3(halfW * 0.92f, 0.52f, -halfL - 0.02f))
        val tR4 = carMat.transformPoint(Vec3(halfW * 0.48f, 0.52f, -halfL - 0.02f))
        addQuadPolygon(tR1, tR2, tR3, tR4, brakeLightColor, eye, target, aspect, screenW, screenH)
    }

    /**
     * 3D PEUGEOT PARS (PERSIAN PERSIA) MODEL
     */
    private fun collectPeugeotParsPolygons(
        car: CarState, eye: Vec3, target: Vec3, aspect: Float, screenW: Float, screenH: Float
    ) {
        val carMat = Mat4.translation(car.pos.x, car.pos.y, car.pos.z)
            .multiply(Mat4.rotationY(-car.headingAngleRad))

        val halfW = car.width / 2f
        val halfL = car.length / 2f
        val h = car.height

        val bodyColor = Color(car.customBodyColorHex)
        val bodyShadow = bodyColor.copy(red = (bodyColor.red * 0.80f).coerceIn(0f, 1f), green = (bodyColor.green * 0.80f).coerceIn(0f, 1f), blue = (bodyColor.blue * 0.80f).coerceIn(0f, 1f))
        val glassColor = Color(0xFF020617)
        val xenonWhite = Color(0xFFE0F2FE)
        val redPin = Color(0xFFDC2626)
        val brakeLightColor = if (car.brakeInput > 0.1f) Color(0xFFFF0000) else Color(0xFF990000)

        // Drop Shadow
        val shadowRelY = -0.09f
        val sOut1 = carMat.transformPoint(Vec3(-halfW * 1.15f, shadowRelY, -halfL * 1.08f))
        val sOut2 = carMat.transformPoint(Vec3(halfW * 1.15f, shadowRelY, -halfL * 1.08f))
        val sOut3 = carMat.transformPoint(Vec3(halfW * 1.15f, shadowRelY, halfL * 1.08f))
        val sOut4 = carMat.transformPoint(Vec3(-halfW * 1.15f, shadowRelY, halfL * 1.08f))
        addQuadPolygon(sOut1, sOut2, sOut3, sOut4, Color(0x33000000), eye, target, aspect, screenW, screenH, layer = 1.3f, avgZBias = 0.002f)

        // Wheels & Customization
        val wheelOffsets = arrayOf(
            Vec3(-halfW * 0.95f, -0.05f, halfL * 0.62f),
            Vec3(halfW * 0.95f, -0.05f, halfL * 0.62f),
            Vec3(-halfW * 0.95f, -0.05f, -halfL * 0.62f),
            Vec3(halfW * 0.95f, -0.05f, -halfL * 0.62f)
        )
        addDetailed3DWheels(carMat, wheelOffsets, car.selectedRimStyle, tireRadius = 0.28f, eye = eye, target = target, aspect = aspect, screenW = screenW, screenH = screenH)
        addDecalsAndStripes(carMat, car.selectedDecalStyle, halfW, halfL, h, eye, target, aspect, screenW, screenH)
        addCustomSpoiler(carMat, car.selectedSpoilerStyle, halfW, halfL, h, eye, target, aspect, screenW, screenH)
        addDoorHandlesAndWipers(carMat, halfW, halfL, h, eye, target, aspect, screenW, screenH)

        // Sport Front Bumper & Rear Bumper
        val b001 = carMat.transformPoint(Vec3(-halfW, 0.10f, halfL))
        val b101 = carMat.transformPoint(Vec3(halfW, 0.10f, halfL))
        val b111 = carMat.transformPoint(Vec3(halfW, 0.36f, halfL))
        val b011 = carMat.transformPoint(Vec3(-halfW, 0.36f, halfL))
        addQuadPolygon(b001, b101, b111, b011, bodyColor, eye, target, aspect, screenW, screenH)

        val rb1 = carMat.transformPoint(Vec3(-halfW, 0.10f, -halfL))
        val rb2 = carMat.transformPoint(Vec3(halfW, 0.10f, -halfL))
        val rb3 = carMat.transformPoint(Vec3(halfW, 0.38f, -halfL))
        val rb4 = carMat.transformPoint(Vec3(-halfW, 0.38f, -halfL))
        addQuadPolygon(rb2, rb1, rb4, rb3, bodyColor, eye, target, aspect, screenW, screenH)

        // Fog lamps
        val fogL1 = carMat.transformPoint(Vec3(-halfW * 0.80f, 0.14f, halfL + 0.02f))
        val fogL2 = carMat.transformPoint(Vec3(-halfW * 0.60f, 0.14f, halfL + 0.02f))
        val fogL3 = carMat.transformPoint(Vec3(-halfW * 0.60f, 0.22f, halfL + 0.02f))
        val fogL4 = carMat.transformPoint(Vec3(-halfW * 0.80f, 0.22f, halfL + 0.02f))
        addQuadPolygon(fogL1, fogL2, fogL3, fogL4, xenonWhite, eye, target, aspect, screenW, screenH)

        val fogR1 = carMat.transformPoint(Vec3(halfW * 0.60f, 0.14f, halfL + 0.02f))
        val fogR2 = carMat.transformPoint(Vec3(halfW * 0.80f, 0.14f, halfL + 0.02f))
        val fogR3 = carMat.transformPoint(Vec3(halfW * 0.80f, 0.22f, halfL + 0.02f))
        val fogR4 = carMat.transformPoint(Vec3(halfW * 0.60f, 0.22f, halfL + 0.02f))
        addQuadPolygon(fogR1, fogR2, fogR3, fogR4, xenonWhite, eye, target, aspect, screenW, screenH)

        // Pars Cat-Eye Headlights (Sharp aggressive angle!)
        val hL1 = carMat.transformPoint(Vec3(-halfW * 0.94f, 0.36f, halfL + 0.02f))
        val hL2 = carMat.transformPoint(Vec3(-halfW * 0.45f, 0.36f, halfL + 0.02f))
        val hL3 = carMat.transformPoint(Vec3(-halfW * 0.50f, 0.52f, halfL + 0.02f))
        val hL4 = carMat.transformPoint(Vec3(-halfW * 0.94f, 0.52f, halfL + 0.02f))
        addQuadPolygon(hL1, hL2, hL3, hL4, xenonWhite, eye, target, aspect, screenW, screenH)

        val hR1 = carMat.transformPoint(Vec3(halfW * 0.45f, 0.36f, halfL + 0.02f))
        val hR2 = carMat.transformPoint(Vec3(halfW * 0.94f, 0.36f, halfL + 0.02f))
        val hR3 = carMat.transformPoint(Vec3(halfW * 0.94f, 0.52f, halfL + 0.02f))
        val hR4 = carMat.transformPoint(Vec3(halfW * 0.50f, 0.52f, halfL + 0.02f))
        addQuadPolygon(hR1, hR2, hR3, hR4, xenonWhite, eye, target, aspect, screenW, screenH)

        // Pars Grille emblem with Red Pin
        val g1 = carMat.transformPoint(Vec3(-halfW * 0.42f, 0.38f, halfL + 0.02f))
        val g2 = carMat.transformPoint(Vec3(halfW * 0.42f, 0.38f, halfL + 0.02f))
        val g3 = carMat.transformPoint(Vec3(halfW * 0.42f, 0.50f, halfL + 0.02f))
        val g4 = carMat.transformPoint(Vec3(-halfW * 0.42f, 0.50f, halfL + 0.02f))
        addQuadPolygon(g1, g2, g3, g4, bodyColor, eye, target, aspect, screenW, screenH)

        val pin1 = carMat.transformPoint(Vec3(-0.08f, 0.42f, halfL + 0.03f))
        val pin2 = carMat.transformPoint(Vec3(0.08f, 0.42f, halfL + 0.03f))
        val pin3 = carMat.transformPoint(Vec3(0.08f, 0.46f, halfL + 0.03f))
        val pin4 = carMat.transformPoint(Vec3(-0.08f, 0.46f, halfL + 0.03f))
        addQuadPolygon(pin1, pin2, pin3, pin4, redPin, eye, target, aspect, screenW, screenH)

        // Iranian License Plates
        addIranianLicensePlate(carMat, Vec3(0f, 0.22f, halfL + 0.01f), 0.48f, 0.11f, eye, target, aspect, screenW, screenH, isFront = true)
        addIranianLicensePlate(carMat, Vec3(0f, 0.22f, -halfL - 0.01f), 0.48f, 0.11f, eye, target, aspect, screenW, screenH, isFront = false)

        // Side Mirrors
        addSideMirrors(carMat, halfW, h * 0.56f, halfL * 0.32f, bodyColor, eye, target, aspect, screenW, screenH)

        // Left & Right Lower Body Sides
        val sL1 = carMat.transformPoint(Vec3(-halfW, 0.10f, halfL))
        val sL2 = carMat.transformPoint(Vec3(-halfW, h * 0.54f, halfL))
        val sL3 = carMat.transformPoint(Vec3(-halfW, h * 0.54f, -halfL))
        val sL4 = carMat.transformPoint(Vec3(-halfW, 0.10f, -halfL))
        addQuadPolygon(sL1, sL2, sL3, sL4, bodyShadow, eye, target, aspect, screenW, screenH)

        val sR1 = carMat.transformPoint(Vec3(halfW, 0.10f, halfL))
        val sR2 = carMat.transformPoint(Vec3(halfW, 0.10f, -halfL))
        val sR3 = carMat.transformPoint(Vec3(halfW, h * 0.54f, -halfL))
        val sR4 = carMat.transformPoint(Vec3(halfW, h * 0.54f, halfL))
        addQuadPolygon(sR1, sR2, sR3, sR4, bodyColor, eye, target, aspect, screenW, screenH)

        // 1. Pars Sport Hood
        val hd1 = carMat.transformPoint(Vec3(-halfW, 0.36f, halfL))
        val hd2 = carMat.transformPoint(Vec3(halfW, 0.36f, halfL))
        val hd3 = carMat.transformPoint(Vec3(halfW * 0.85f, h * 0.54f, halfL * 0.38f))
        val hd4 = carMat.transformPoint(Vec3(-halfW * 0.85f, h * 0.54f, halfL * 0.38f))
        addQuadPolygon(hd1, hd2, hd3, hd4, bodyColor, eye, target, aspect, screenW, screenH)

        // 2. Windshield
        val w1 = carMat.transformPoint(Vec3(-halfW * 0.85f, h * 0.54f, halfL * 0.38f))
        val w2 = carMat.transformPoint(Vec3(halfW * 0.85f, h * 0.54f, halfL * 0.38f))
        val w3 = carMat.transformPoint(Vec3(-halfW * 0.74f, h, halfL * 0.08f))
        val w4 = carMat.transformPoint(Vec3(halfW * 0.74f, h, halfL * 0.08f))
        addQuadPolygon(w1, w2, w4, w3, glassColor, eye, target, aspect, screenW, screenH)

        // 3. Cabin Roof
        val r1 = carMat.transformPoint(Vec3(-halfW * 0.74f, h, -halfL * 0.42f))
        val r2 = carMat.transformPoint(Vec3(halfW * 0.74f, h, -halfL * 0.42f))
        addQuadPolygon(w3, w4, r2, r1, bodyColor, eye, target, aspect, screenW, screenH)

        // 4. Rear Glass Window
        val rw1 = carMat.transformPoint(Vec3(-halfW * 0.82f, h * 0.54f, -halfL * 0.72f))
        val rw2 = carMat.transformPoint(Vec3(halfW * 0.82f, h * 0.54f, -halfL * 0.72f))
        addQuadPolygon(r1, r2, rw2, rw1, glassColor, eye, target, aspect, screenW, screenH)

        // 5. Trunk Deck & Lip Spoiler
        val tk1 = carMat.transformPoint(Vec3(-halfW * 0.82f, h * 0.54f, -halfL * 0.72f))
        val tk2 = carMat.transformPoint(Vec3(halfW * 0.82f, h * 0.54f, -halfL * 0.72f))
        val tk3 = carMat.transformPoint(Vec3(halfW, 0.38f, -halfL))
        val tk4 = carMat.transformPoint(Vec3(-halfW, 0.38f, -halfL))
        addQuadPolygon(tk1, tk2, tk3, tk4, bodyColor, eye, target, aspect, screenW, screenH)

        val sp1 = carMat.transformPoint(Vec3(-halfW * 0.90f, h * 0.55f, -halfL - 0.04f))
        val sp2 = carMat.transformPoint(Vec3(halfW * 0.90f, h * 0.55f, -halfL - 0.04f))
        val sp3 = carMat.transformPoint(Vec3(halfW * 0.90f, h * 0.58f, -halfL))
        val sp4 = carMat.transformPoint(Vec3(-halfW * 0.90f, h * 0.58f, -halfL))
        addQuadPolygon(sp1, sp2, sp3, sp4, bodyColor, eye, target, aspect, screenW, screenH)

        // Side Glass Windows
        val sgL1 = carMat.transformPoint(Vec3(-halfW - 0.01f, h * 0.58f, -halfL * 0.62f))
        val sgL2 = carMat.transformPoint(Vec3(-halfW - 0.01f, h * 0.58f, halfL * 0.35f))
        val sgL3 = carMat.transformPoint(Vec3(-halfW * 0.76f, h - 0.02f, halfL * 0.10f))
        val sgL4 = carMat.transformPoint(Vec3(-halfW * 0.76f, h - 0.02f, -halfL * 0.42f))
        addQuadPolygon(sgL1, sgL2, sgL3, sgL4, glassColor, eye, target, aspect, screenW, screenH)

        val sgR1 = carMat.transformPoint(Vec3(halfW + 0.01f, h * 0.58f, -halfL * 0.62f))
        val sgR2 = carMat.transformPoint(Vec3(halfW + 0.01f, h * 0.58f, halfL * 0.35f))
        val sgR3 = carMat.transformPoint(Vec3(halfW * 0.76f, h - 0.02f, halfL * 0.10f))
        val sgR4 = carMat.transformPoint(Vec3(halfW * 0.76f, h - 0.02f, -halfL * 0.42f))
        addQuadPolygon(sgR1, sgR2, sgR3, sgR4, glassColor, eye, target, aspect, screenW, screenH)

        // Pars Red Rear Crystal Lights (Left & Right separate)
        val tL1 = carMat.transformPoint(Vec3(-halfW * 0.94f, 0.38f, -halfL - 0.02f))
        val tL2 = carMat.transformPoint(Vec3(-halfW * 0.46f, 0.38f, -halfL - 0.02f))
        val tL3 = carMat.transformPoint(Vec3(-halfW * 0.46f, 0.52f, -halfL - 0.02f))
        val tL4 = carMat.transformPoint(Vec3(-halfW * 0.94f, 0.52f, -halfL - 0.02f))
        addQuadPolygon(tL1, tL2, tL3, tL4, brakeLightColor, eye, target, aspect, screenW, screenH)

        val tR1 = carMat.transformPoint(Vec3(halfW * 0.46f, 0.38f, -halfL - 0.02f))
        val tR2 = carMat.transformPoint(Vec3(halfW * 0.94f, 0.38f, -halfL - 0.02f))
        val tR3 = carMat.transformPoint(Vec3(halfW * 0.94f, 0.52f, -halfL - 0.02f))
        val tR4 = carMat.transformPoint(Vec3(halfW * 0.46f, 0.52f, -halfL - 0.02f))
        addQuadPolygon(tR1, tR2, tR3, tR4, brakeLightColor, eye, target, aspect, screenW, screenH)

        // Dual Chrome Exhaust
        val ex1 = carMat.transformPoint(Vec3(halfW * 0.50f, 0.16f, -halfL - 0.08f))
        val ex2 = carMat.transformPoint(Vec3(halfW * 0.68f, 0.16f, -halfL - 0.08f))
        val ex3 = carMat.transformPoint(Vec3(halfW * 0.68f, 0.22f, -halfL))
        val ex4 = carMat.transformPoint(Vec3(halfW * 0.50f, 0.22f, -halfL))
        addQuadPolygon(ex1, ex2, ex3, ex4, Color(0xFFCBD5E1), eye, target, aspect, screenW, screenH)
    }

    /**
     * 3D DENA PLUS TURBO MODEL
     */
    private fun collectDenaPlusPolygons(
        car: CarState, eye: Vec3, target: Vec3, aspect: Float, screenW: Float, screenH: Float
    ) {
        val carMat = Mat4.translation(car.pos.x, car.pos.y, car.pos.z)
            .multiply(Mat4.rotationY(-car.headingAngleRad))

        val halfW = car.width / 2f
        val halfL = car.length / 2f
        val h = car.height

        val bodyColor = Color(car.customBodyColorHex)
        val bodyShadow = bodyColor.copy(red = (bodyColor.red * 0.78f).coerceIn(0f, 1f), green = (bodyColor.green * 0.78f).coerceIn(0f, 1f), blue = (bodyColor.blue * 0.78f).coerceIn(0f, 1f))
        val blackGrille = Color(0xFF020617)
        val glassColor = Color(0xFF0F172A)
        val drlWhite = Color(0xFFF8FAFC)
        val brakeLightColor = if (car.brakeInput > 0.1f) Color(0xFFFF0000) else Color(0xFF990000)

        // Drop Shadow
        val shadowRelY = -0.09f
        val sOut1 = carMat.transformPoint(Vec3(-halfW * 1.15f, shadowRelY, -halfL * 1.08f))
        val sOut2 = carMat.transformPoint(Vec3(halfW * 1.15f, shadowRelY, -halfL * 1.08f))
        val sOut3 = carMat.transformPoint(Vec3(halfW * 1.15f, shadowRelY, halfL * 1.08f))
        val sOut4 = carMat.transformPoint(Vec3(-halfW * 1.15f, shadowRelY, halfL * 1.08f))
        addQuadPolygon(sOut1, sOut2, sOut3, sOut4, Color(0x33000000), eye, target, aspect, screenW, screenH, layer = 1.3f, avgZBias = 0.002f)

        // Wheels & Customization
        val wheelOffsets = arrayOf(
            Vec3(-halfW * 0.95f, -0.05f, halfL * 0.63f),
            Vec3(halfW * 0.95f, -0.05f, halfL * 0.63f),
            Vec3(-halfW * 0.95f, -0.05f, -halfL * 0.63f),
            Vec3(halfW * 0.95f, -0.05f, -halfL * 0.63f)
        )
        addDetailed3DWheels(carMat, wheelOffsets, car.selectedRimStyle, tireRadius = 0.29f, eye = eye, target = target, aspect = aspect, screenW = screenW, screenH = screenH)
        addDecalsAndStripes(carMat, car.selectedDecalStyle, halfW, halfL, h, eye, target, aspect, screenW, screenH)
        addCustomSpoiler(carMat, car.selectedSpoilerStyle, halfW, halfL, h, eye, target, aspect, screenW, screenH)
        addDoorHandlesAndWipers(carMat, halfW, halfL, h, eye, target, aspect, screenW, screenH)

        // Front Bumper & Rear Bumper
        val b001 = carMat.transformPoint(Vec3(-halfW, 0.10f, halfL))
        val b101 = carMat.transformPoint(Vec3(halfW, 0.10f, halfL))
        val b111 = carMat.transformPoint(Vec3(halfW, 0.38f, halfL))
        val b011 = carMat.transformPoint(Vec3(-halfW, 0.38f, halfL))
        addQuadPolygon(b001, b101, b111, b011, bodyColor, eye, target, aspect, screenW, screenH)

        val rb1 = carMat.transformPoint(Vec3(-halfW, 0.10f, -halfL))
        val rb2 = carMat.transformPoint(Vec3(halfW, 0.10f, -halfL))
        val rb3 = carMat.transformPoint(Vec3(halfW, 0.38f, -halfL))
        val rb4 = carMat.transformPoint(Vec3(-halfW, 0.38f, -halfL))
        addQuadPolygon(rb2, rb1, rb4, rb3, bodyColor, eye, target, aspect, screenW, screenH)

        // Dena Large Black Hexagonal Front Single-Frame Grille ("جلوپنجره بزرگ دنا")
        val g1 = carMat.transformPoint(Vec3(-halfW * 0.55f, 0.15f, halfL + 0.02f))
        val g2 = carMat.transformPoint(Vec3(halfW * 0.55f, 0.15f, halfL + 0.02f))
        val g3 = carMat.transformPoint(Vec3(halfW * 0.50f, 0.54f, halfL + 0.02f))
        val g4 = carMat.transformPoint(Vec3(-halfW * 0.50f, 0.54f, halfL + 0.02f))
        addQuadPolygon(g1, g2, g3, g4, blackGrille, eye, target, aspect, screenW, screenH)

        // Dena LED DRL Headlight Strips
        val hL1 = carMat.transformPoint(Vec3(-halfW * 0.95f, 0.38f, halfL + 0.02f))
        val hL2 = carMat.transformPoint(Vec3(-halfW * 0.52f, 0.38f, halfL + 0.02f))
        val hL3 = carMat.transformPoint(Vec3(-halfW * 0.52f, 0.54f, halfL + 0.02f))
        val hL4 = carMat.transformPoint(Vec3(-halfW * 0.95f, 0.54f, halfL + 0.02f))
        addQuadPolygon(hL1, hL2, hL3, hL4, drlWhite, eye, target, aspect, screenW, screenH)

        val hR1 = carMat.transformPoint(Vec3(halfW * 0.52f, 0.38f, halfL + 0.02f))
        val hR2 = carMat.transformPoint(Vec3(halfW * 0.95f, 0.38f, halfL + 0.02f))
        val hR3 = carMat.transformPoint(Vec3(halfW * 0.95f, 0.54f, halfL + 0.02f))
        val hR4 = carMat.transformPoint(Vec3(halfW * 0.52f, 0.54f, halfL + 0.02f))
        addQuadPolygon(hR1, hR2, hR3, hR4, drlWhite, eye, target, aspect, screenW, screenH)

        // Iranian License Plates & Side Mirrors
        addIranianLicensePlate(carMat, Vec3(0f, 0.22f, halfL + 0.01f), 0.48f, 0.11f, eye, target, aspect, screenW, screenH, isFront = true)
        addIranianLicensePlate(carMat, Vec3(0f, 0.22f, -halfL - 0.01f), 0.48f, 0.11f, eye, target, aspect, screenW, screenH, isFront = false)
        addSideMirrors(carMat, halfW, h * 0.58f, halfL * 0.32f, bodyColor, eye, target, aspect, screenW, screenH)

        // Left & Right Lower Body Side Walls
        val sL1 = carMat.transformPoint(Vec3(-halfW, 0.10f, halfL))
        val sL2 = carMat.transformPoint(Vec3(-halfW, h * 0.56f, halfL))
        val sL3 = carMat.transformPoint(Vec3(-halfW, h * 0.56f, -halfL))
        val sL4 = carMat.transformPoint(Vec3(-halfW, 0.10f, -halfL))
        addQuadPolygon(sL1, sL2, sL3, sL4, bodyShadow, eye, target, aspect, screenW, screenH)

        val sR1 = carMat.transformPoint(Vec3(halfW, 0.10f, halfL))
        val sR2 = carMat.transformPoint(Vec3(halfW, 0.10f, -halfL))
        val sR3 = carMat.transformPoint(Vec3(halfW, h * 0.56f, -halfL))
        val sR4 = carMat.transformPoint(Vec3(halfW, h * 0.56f, halfL))
        addQuadPolygon(sR1, sR2, sR3, sR4, bodyColor, eye, target, aspect, screenW, screenH)

        // 1. Dena Modern Hood
        val hd1 = carMat.transformPoint(Vec3(-halfW, 0.38f, halfL))
        val hd2 = carMat.transformPoint(Vec3(halfW, 0.38f, halfL))
        val hd3 = carMat.transformPoint(Vec3(halfW * 0.85f, h * 0.56f, halfL * 0.38f))
        val hd4 = carMat.transformPoint(Vec3(-halfW * 0.85f, h * 0.56f, halfL * 0.38f))
        addQuadPolygon(hd1, hd2, hd3, hd4, bodyColor, eye, target, aspect, screenW, screenH)

        // 2. Windshield
        val w1 = carMat.transformPoint(Vec3(-halfW * 0.85f, h * 0.56f, halfL * 0.38f))
        val w2 = carMat.transformPoint(Vec3(halfW * 0.85f, h * 0.56f, halfL * 0.38f))
        val w3 = carMat.transformPoint(Vec3(-halfW * 0.74f, h, halfL * 0.08f))
        val w4 = carMat.transformPoint(Vec3(halfW * 0.74f, h, halfL * 0.08f))
        addQuadPolygon(w1, w2, w4, w3, glassColor, eye, target, aspect, screenW, screenH)

        // 3. Cabin Roof
        val r1 = carMat.transformPoint(Vec3(-halfW * 0.74f, h, -halfL * 0.42f))
        val r2 = carMat.transformPoint(Vec3(halfW * 0.74f, h, -halfL * 0.42f))
        addQuadPolygon(w3, w4, r2, r1, bodyColor, eye, target, aspect, screenW, screenH)

        // 4. Rear Glass Window
        val rw1 = carMat.transformPoint(Vec3(-halfW * 0.82f, h * 0.56f, -halfL * 0.72f))
        val rw2 = carMat.transformPoint(Vec3(halfW * 0.82f, h * 0.56f, -halfL * 0.72f))
        addQuadPolygon(r1, r2, rw2, rw1, glassColor, eye, target, aspect, screenW, screenH)

        // 5. Trunk Lid
        val tk1 = carMat.transformPoint(Vec3(-halfW * 0.82f, h * 0.56f, -halfL * 0.72f))
        val tk2 = carMat.transformPoint(Vec3(halfW * 0.82f, h * 0.56f, -halfL * 0.72f))
        val tk3 = carMat.transformPoint(Vec3(halfW, 0.38f, -halfL))
        val tk4 = carMat.transformPoint(Vec3(-halfW, 0.38f, -halfL))
        addQuadPolygon(tk1, tk2, tk3, tk4, bodyColor, eye, target, aspect, screenW, screenH)

        // Side Glass Windows
        val sgL1 = carMat.transformPoint(Vec3(-halfW - 0.01f, h * 0.60f, -halfL * 0.62f))
        val sgL2 = carMat.transformPoint(Vec3(-halfW - 0.01f, h * 0.60f, halfL * 0.35f))
        val sgL3 = carMat.transformPoint(Vec3(-halfW * 0.76f, h - 0.02f, halfL * 0.10f))
        val sgL4 = carMat.transformPoint(Vec3(-halfW * 0.76f, h - 0.02f, -halfL * 0.42f))
        addQuadPolygon(sgL1, sgL2, sgL3, sgL4, glassColor, eye, target, aspect, screenW, screenH)

        val sgR1 = carMat.transformPoint(Vec3(halfW + 0.01f, h * 0.60f, -halfL * 0.62f))
        val sgR2 = carMat.transformPoint(Vec3(halfW + 0.01f, h * 0.60f, halfL * 0.35f))
        val sgR3 = carMat.transformPoint(Vec3(halfW * 0.76f, h - 0.02f, halfL * 0.10f))
        val sgR4 = carMat.transformPoint(Vec3(halfW * 0.76f, h - 0.02f, -halfL * 0.42f))
        addQuadPolygon(sgR1, sgR2, sgR3, sgR4, glassColor, eye, target, aspect, screenW, screenH)

        // Dena Rear LED C-Shaped Taillights (Left & Right separate)
        val tL1 = carMat.transformPoint(Vec3(-halfW * 0.94f, 0.40f, -halfL - 0.02f))
        val tL2 = carMat.transformPoint(Vec3(-halfW * 0.50f, 0.40f, -halfL - 0.02f))
        val tL3 = carMat.transformPoint(Vec3(-halfW * 0.50f, 0.56f, -halfL - 0.02f))
        val tL4 = carMat.transformPoint(Vec3(-halfW * 0.94f, 0.56f, -halfL - 0.02f))
        addQuadPolygon(tL1, tL2, tL3, tL4, brakeLightColor, eye, target, aspect, screenW, screenH)

        val tR1 = carMat.transformPoint(Vec3(halfW * 0.50f, 0.40f, -halfL - 0.02f))
        val tR2 = carMat.transformPoint(Vec3(halfW * 0.94f, 0.40f, -halfL - 0.02f))
        val tR3 = carMat.transformPoint(Vec3(halfW * 0.94f, 0.56f, -halfL - 0.02f))
        val tR4 = carMat.transformPoint(Vec3(halfW * 0.50f, 0.56f, -halfL - 0.02f))
        addQuadPolygon(tR1, tR2, tR3, tR4, brakeLightColor, eye, target, aspect, screenW, screenH)

        // Dual Exhaust
        val ex1 = carMat.transformPoint(Vec3(halfW * 0.50f, 0.16f, -halfL - 0.08f))
        val ex2 = carMat.transformPoint(Vec3(halfW * 0.68f, 0.16f, -halfL - 0.08f))
        val ex3 = carMat.transformPoint(Vec3(halfW * 0.68f, 0.22f, -halfL))
        val ex4 = carMat.transformPoint(Vec3(halfW * 0.50f, 0.22f, -halfL))
        addQuadPolygon(ex1, ex2, ex3, ex4, Color(0xFFCBD5E1), eye, target, aspect, screenW, screenH)
    }

    /**
     * 3D TOYOTA LAND CRUISER V8 MODEL
     */
    private fun collectLandCruiserPolygons(
        car: CarState, eye: Vec3, target: Vec3, aspect: Float, screenW: Float, screenH: Float
    ) {
        val carMat = Mat4.translation(car.pos.x, car.pos.y, car.pos.z)
            .multiply(Mat4.rotationY(-car.headingAngleRad))

        val halfW = car.width / 2f
        val halfL = car.length / 2f
        val h = car.height // 1.92m height

        val bodyColor = Color(car.customBodyColorHex)
        val bodyShadow = bodyColor.copy(red = (bodyColor.red * 0.78f).coerceIn(0f, 1f), green = (bodyColor.green * 0.78f).coerceIn(0f, 1f), blue = (bodyColor.blue * 0.78f).coerceIn(0f, 1f))
        val chromeColor = Color(0xFFE2E8F0)
        val darkPlastic = Color(0xFF1E293B)
        val glassColor = Color(0xFF0F172A)
        val hidWhite = Color(0xFFFEF08A)
        val brakeLightColor = if (car.brakeInput > 0.1f) Color(0xFFFF0000) else Color(0xFF990000)
        val reverseLightColor = Color(0xFFF8FAFC)

        // Drop Shadow
        val shadowRelY = -0.09f
        val sOut1 = carMat.transformPoint(Vec3(-halfW * 1.15f, shadowRelY, -halfL * 1.08f))
        val sOut2 = carMat.transformPoint(Vec3(halfW * 1.15f, shadowRelY, -halfL * 1.08f))
        val sOut3 = carMat.transformPoint(Vec3(halfW * 1.15f, shadowRelY, halfL * 1.08f))
        val sOut4 = carMat.transformPoint(Vec3(-halfW * 1.15f, shadowRelY, halfL * 1.08f))
        addQuadPolygon(sOut1, sOut2, sOut3, sOut4, Color(0x33000000), eye, target, aspect, screenW, screenH, layer = 1.3f, avgZBias = 0.002f)

        // Wheels & Customization
        val wheelOffsets = arrayOf(
            Vec3(-halfW * 0.92f, 0.05f, halfL * 0.60f),
            Vec3(halfW * 0.92f, 0.05f, halfL * 0.60f),
            Vec3(-halfW * 0.92f, 0.05f, -halfL * 0.60f),
            Vec3(halfW * 0.92f, 0.05f, -halfL * 0.60f)
        )
        addDetailed3DWheels(carMat, wheelOffsets, car.selectedRimStyle, tireRadius = 0.35f, eye = eye, target = target, aspect = aspect, screenW = screenW, screenH = screenH)
        addDecalsAndStripes(carMat, car.selectedDecalStyle, halfW, halfL, h, eye, target, aspect, screenW, screenH)
        addCustomSpoiler(carMat, car.selectedSpoilerStyle, halfW, halfL, h, eye, target, aspect, screenW, screenH)
        addDoorHandlesAndWipers(carMat, halfW, halfL, h, eye, target, aspect, screenW, screenH)

        // Side Running Board Steps ("پله رکاب")
        val stepL1 = carMat.transformPoint(Vec3(-halfW - 0.06f, 0.20f, -halfL * 0.6f))
        val stepL2 = carMat.transformPoint(Vec3(-halfW - 0.06f, 0.20f, halfL * 0.6f))
        val stepL3 = carMat.transformPoint(Vec3(-halfW, 0.26f, halfL * 0.6f))
        val stepL4 = carMat.transformPoint(Vec3(-halfW, 0.26f, -halfL * 0.6f))
        addQuadPolygon(stepL1, stepL2, stepL3, stepL4, chromeColor, eye, target, aspect, screenW, screenH)

        val stepR1 = carMat.transformPoint(Vec3(halfW, 0.26f, -halfL * 0.6f))
        val stepR2 = carMat.transformPoint(Vec3(halfW, 0.26f, halfL * 0.6f))
        val stepR3 = carMat.transformPoint(Vec3(halfW + 0.06f, 0.20f, halfL * 0.6f))
        val stepR4 = carMat.transformPoint(Vec3(halfW + 0.06f, 0.20f, -halfL * 0.6f))
        addQuadPolygon(stepR1, stepR2, stepR3, stepR4, chromeColor, eye, target, aspect, screenW, screenH)

        // Front Bumper
        val b001 = carMat.transformPoint(Vec3(-halfW, 0.15f, halfL))
        val b101 = carMat.transformPoint(Vec3(halfW, 0.15f, halfL))
        val b111 = carMat.transformPoint(Vec3(halfW, 0.45f, halfL))
        val b011 = carMat.transformPoint(Vec3(-halfW, 0.45f, halfL))
        addQuadPolygon(b001, b101, b111, b011, bodyColor, eye, target, aspect, screenW, screenH)

        // Front Skid Plate
        val sk1 = carMat.transformPoint(Vec3(-halfW * 0.70f, 0.12f, halfL + 0.02f))
        val sk2 = carMat.transformPoint(Vec3(halfW * 0.70f, 0.12f, halfL + 0.02f))
        val sk3 = carMat.transformPoint(Vec3(halfW * 0.70f, 0.25f, halfL + 0.02f))
        val sk4 = carMat.transformPoint(Vec3(-halfW * 0.70f, 0.25f, halfL + 0.02f))
        addQuadPolygon(sk1, sk2, sk3, sk4, chromeColor, eye, target, aspect, screenW, screenH)

        // Land Cruiser Prominent Chrome Grille
        val g1 = carMat.transformPoint(Vec3(-halfW * 0.65f, 0.35f, halfL + 0.03f))
        val g2 = carMat.transformPoint(Vec3(halfW * 0.65f, 0.35f, halfL + 0.03f))
        val g3 = carMat.transformPoint(Vec3(halfW * 0.65f, 0.68f, halfL + 0.03f))
        val g4 = carMat.transformPoint(Vec3(-halfW * 0.65f, 0.68f, halfL + 0.03f))
        addQuadPolygon(g1, g2, g3, g4, chromeColor, eye, target, aspect, screenW, screenH)

        // Toyota Front Oval Emblem
        val emb1 = carMat.transformPoint(Vec3(-0.16f, 0.52f, halfL + 0.05f))
        val emb2 = carMat.transformPoint(Vec3(0.16f, 0.52f, halfL + 0.05f))
        val emb3 = carMat.transformPoint(Vec3(0.16f, 0.60f, halfL + 0.05f))
        val emb4 = carMat.transformPoint(Vec3(-0.16f, 0.60f, halfL + 0.05f))
        addQuadPolygon(emb1, emb2, emb3, emb4, Color(0xFFFEF08A), eye, target, aspect, screenW, screenH)

        // Large Rectangular HID Headlights
        val hL1 = carMat.transformPoint(Vec3(-halfW * 0.96f, 0.45f, halfL + 0.03f))
        val hL2 = carMat.transformPoint(Vec3(-halfW * 0.66f, 0.45f, halfL + 0.03f))
        val hL3 = carMat.transformPoint(Vec3(-halfW * 0.66f, 0.68f, halfL + 0.03f))
        val hL4 = carMat.transformPoint(Vec3(-halfW * 0.96f, 0.68f, halfL + 0.03f))
        addQuadPolygon(hL1, hL2, hL3, hL4, hidWhite, eye, target, aspect, screenW, screenH)

        val hR1 = carMat.transformPoint(Vec3(halfW * 0.66f, 0.45f, halfL + 0.03f))
        val hR2 = carMat.transformPoint(Vec3(halfW * 0.96f, 0.45f, halfL + 0.03f))
        val hR3 = carMat.transformPoint(Vec3(halfW * 0.96f, 0.68f, halfL + 0.03f))
        val hR4 = carMat.transformPoint(Vec3(halfW * 0.66f, 0.68f, halfL + 0.03f))
        addQuadPolygon(hR1, hR2, hR3, hR4, hidWhite, eye, target, aspect, screenW, screenH)

        // Plates & Mirrors
        addIranianLicensePlate(carMat, Vec3(0f, 0.28f, halfL + 0.04f), 0.52f, 0.12f, eye, target, aspect, screenW, screenH, isFront = true)
        addIranianLicensePlate(carMat, Vec3(0f, 0.30f, -halfL - 0.04f), 0.52f, 0.12f, eye, target, aspect, screenW, screenH, isFront = false)
        addSideMirrors(carMat, halfW, h * 0.62f, halfL * 0.28f, chromeColor, eye, target, aspect, screenW, screenH)

        // Left & Right Lower SUV Body Side Walls
        val sL1 = carMat.transformPoint(Vec3(-halfW, 0.15f, halfL))
        val sL2 = carMat.transformPoint(Vec3(-halfW, h * 0.58f, halfL))
        val sL3 = carMat.transformPoint(Vec3(-halfW, h * 0.58f, -halfL))
        val sL4 = carMat.transformPoint(Vec3(-halfW, 0.15f, -halfL))
        addQuadPolygon(sL1, sL2, sL3, sL4, bodyShadow, eye, target, aspect, screenW, screenH)

        val sR1 = carMat.transformPoint(Vec3(halfW, 0.15f, halfL))
        val sR2 = carMat.transformPoint(Vec3(halfW, 0.15f, -halfL))
        val sR3 = carMat.transformPoint(Vec3(halfW, h * 0.58f, -halfL))
        val sR4 = carMat.transformPoint(Vec3(halfW, h * 0.58f, halfL))
        addQuadPolygon(sR1, sR2, sR3, sR4, bodyColor, eye, target, aspect, screenW, screenH)

        // 1. SUV Massive Hood
        val hd1 = carMat.transformPoint(Vec3(-halfW, 0.45f, halfL))
        val hd2 = carMat.transformPoint(Vec3(halfW, 0.45f, halfL))
        val hd3 = carMat.transformPoint(Vec3(halfW * 0.88f, h * 0.58f, halfL * 0.30f))
        val hd4 = carMat.transformPoint(Vec3(-halfW * 0.88f, h * 0.58f, halfL * 0.30f))
        addQuadPolygon(hd1, hd2, hd3, hd4, bodyColor, eye, target, aspect, screenW, screenH)

        // 2. Windshield
        val w1 = carMat.transformPoint(Vec3(-halfW * 0.88f, h * 0.58f, halfL * 0.30f))
        val w2 = carMat.transformPoint(Vec3(halfW * 0.88f, h * 0.58f, halfL * 0.30f))
        val w3 = carMat.transformPoint(Vec3(-halfW * 0.82f, h, halfL * 0.05f))
        val w4 = carMat.transformPoint(Vec3(halfW * 0.82f, h, halfL * 0.05f))
        addQuadPolygon(w1, w2, w4, w3, glassColor, eye, target, aspect, screenW, screenH)

        // 3. SUV Flat Long Roof
        val r1 = carMat.transformPoint(Vec3(-halfW * 0.82f, h, -halfL * 0.80f))
        val r2 = carMat.transformPoint(Vec3(halfW * 0.82f, h, -halfL * 0.80f))
        addQuadPolygon(w3, w4, r2, r1, bodyColor, eye, target, aspect, screenW, screenH)

        // Roof Luggage Rails ("باربند سقفی")
        val railL1 = carMat.transformPoint(Vec3(-halfW * 0.78f, h + 0.02f, -halfL * 0.7f))
        val railL2 = carMat.transformPoint(Vec3(-halfW * 0.78f, h + 0.02f, halfL * 0.0f))
        val railL3 = carMat.transformPoint(Vec3(-halfW * 0.78f, h + 0.10f, halfL * 0.0f))
        val railL4 = carMat.transformPoint(Vec3(-halfW * 0.78f, h + 0.10f, -halfL * 0.7f))
        addQuadPolygon(railL1, railL2, railL3, railL4, chromeColor, eye, target, aspect, screenW, screenH)

        val railR1 = carMat.transformPoint(Vec3(halfW * 0.78f, h + 0.10f, -halfL * 0.7f))
        val railR2 = carMat.transformPoint(Vec3(halfW * 0.78f, h + 0.10f, halfL * 0.0f))
        val railR3 = carMat.transformPoint(Vec3(halfW * 0.78f, h + 0.02f, halfL * 0.0f))
        val railR4 = carMat.transformPoint(Vec3(halfW * 0.78f, h + 0.02f, -halfL * 0.7f))
        addQuadPolygon(railR1, railR2, railR3, railR4, chromeColor, eye, target, aspect, screenW, screenH)

        // Side Glass Windows
        val sgL1 = carMat.transformPoint(Vec3(-halfW - 0.01f, h * 0.62f, -halfL * 0.88f))
        val sgL2 = carMat.transformPoint(Vec3(-halfW - 0.01f, h * 0.62f, halfL * 0.28f))
        val sgL3 = carMat.transformPoint(Vec3(-halfW * 0.82f, h - 0.02f, halfL * 0.05f))
        val sgL4 = carMat.transformPoint(Vec3(-halfW * 0.82f, h - 0.02f, -halfL * 0.75f))
        addQuadPolygon(sgL1, sgL2, sgL3, sgL4, glassColor, eye, target, aspect, screenW, screenH)

        val sgR1 = carMat.transformPoint(Vec3(halfW + 0.01f, h * 0.62f, -halfL * 0.88f))
        val sgR2 = carMat.transformPoint(Vec3(halfW + 0.01f, h * 0.62f, halfL * 0.28f))
        val sgR3 = carMat.transformPoint(Vec3(halfW * 0.82f, h - 0.02f, halfL * 0.05f))
        val sgR4 = carMat.transformPoint(Vec3(halfW * 0.82f, h - 0.02f, -halfL * 0.75f))
        addQuadPolygon(sgR1, sgR2, sgR3, sgR4, glassColor, eye, target, aspect, screenW, screenH)

        // REAR TAILGATE & BACK DESIGN (COMPLETELY REBUILT)
        // Upper Slanted Rear Glass Window
        val rw1 = carMat.transformPoint(Vec3(-halfW * 0.84f, h * 0.58f, -halfL))
        val rw2 = carMat.transformPoint(Vec3(halfW * 0.84f, h * 0.58f, -halfL))
        addQuadPolygon(r1, r2, rw2, rw1, glassColor, eye, target, aspect, screenW, screenH)

        // Main Lower Tailgate Body Panel
        val tg1 = carMat.transformPoint(Vec3(-halfW, 0.22f, -halfL))
        val tg2 = carMat.transformPoint(Vec3(halfW, 0.22f, -halfL))
        val tg3 = carMat.transformPoint(Vec3(halfW, h * 0.58f, -halfL))
        val tg4 = carMat.transformPoint(Vec3(-halfW, h * 0.58f, -halfL))
        addQuadPolygon(tg1, tg2, tg3, tg4, bodyColor, eye, target, aspect, screenW, screenH)

        // Distinct Left LED Taillight Block (Outer Red + Inner Clear Reverse)
        val tL1 = carMat.transformPoint(Vec3(-halfW * 0.98f, 0.46f, -halfL - 0.02f))
        val tL2 = carMat.transformPoint(Vec3(-halfW * 0.60f, 0.46f, -halfL - 0.02f))
        val tL3 = carMat.transformPoint(Vec3(-halfW * 0.60f, 0.70f, -halfL - 0.02f))
        val tL4 = carMat.transformPoint(Vec3(-halfW * 0.98f, 0.70f, -halfL - 0.02f))
        addQuadPolygon(tL1, tL2, tL3, tL4, brakeLightColor, eye, target, aspect, screenW, screenH)

        val tLIn1 = carMat.transformPoint(Vec3(-halfW * 0.75f, 0.52f, -halfL - 0.03f))
        val tLIn2 = carMat.transformPoint(Vec3(-halfW * 0.60f, 0.52f, -halfL - 0.03f))
        val tLIn3 = carMat.transformPoint(Vec3(-halfW * 0.60f, 0.64f, -halfL - 0.03f))
        val tLIn4 = carMat.transformPoint(Vec3(-halfW * 0.75f, 0.64f, -halfL - 0.03f))
        addQuadPolygon(tLIn1, tLIn2, tLIn3, tLIn4, reverseLightColor, eye, target, aspect, screenW, screenH)

        // Distinct Right LED Taillight Block
        val tR1 = carMat.transformPoint(Vec3(halfW * 0.60f, 0.46f, -halfL - 0.02f))
        val tR2 = carMat.transformPoint(Vec3(halfW * 0.98f, 0.46f, -halfL - 0.02f))
        val tR3 = carMat.transformPoint(Vec3(halfW * 0.98f, 0.70f, -halfL - 0.02f))
        val tR4 = carMat.transformPoint(Vec3(halfW * 0.60f, 0.70f, -halfL - 0.02f))
        addQuadPolygon(tR1, tR2, tR3, tR4, brakeLightColor, eye, target, aspect, screenW, screenH)

        val tRIn1 = carMat.transformPoint(Vec3(halfW * 0.60f, 0.52f, -halfL - 0.03f))
        val tRIn2 = carMat.transformPoint(Vec3(halfW * 0.75f, 0.52f, -halfL - 0.03f))
        val tRIn3 = carMat.transformPoint(Vec3(halfW * 0.75f, 0.64f, -halfL - 0.03f))
        val tRIn4 = carMat.transformPoint(Vec3(halfW * 0.60f, 0.64f, -halfL - 0.03f))
        addQuadPolygon(tRIn1, tRIn2, tRIn3, tRIn4, reverseLightColor, eye, target, aspect, screenW, screenH)

        // Chrome "LAND CRUISER" Trunk Bar
        val cg1 = carMat.transformPoint(Vec3(-halfW * 0.58f, 0.56f, -halfL - 0.025f))
        val cg2 = carMat.transformPoint(Vec3(halfW * 0.58f, 0.56f, -halfL - 0.025f))
        val cg3 = carMat.transformPoint(Vec3(halfW * 0.58f, 0.63f, -halfL - 0.025f))
        val cg4 = carMat.transformPoint(Vec3(-halfW * 0.58f, 0.63f, -halfL - 0.025f))
        addQuadPolygon(cg1, cg2, cg3, cg4, chromeColor, eye, target, aspect, screenW, screenH)

        // Toyota V8 Oval Rear Badge
        val tB1 = carMat.transformPoint(Vec3(-0.14f, 0.65f, -halfL - 0.025f))
        val tB2 = carMat.transformPoint(Vec3(0.14f, 0.65f, -halfL - 0.025f))
        val tB3 = carMat.transformPoint(Vec3(0.14f, 0.73f, -halfL - 0.025f))
        val tB4 = carMat.transformPoint(Vec3(-0.14f, 0.73f, -halfL - 0.025f))
        addQuadPolygon(tB1, tB2, tB3, tB4, Color(0xFFFEF08A), eye, target, aspect, screenW, screenH)

        // Mounted 3D Rear Spare Tire Cover ("زاپاس لندکروز")
        val sprR = 0.32f
        val sprX1 = carMat.transformPoint(Vec3(-sprR, 0.28f, -halfL - 0.12f))
        val sprX2 = carMat.transformPoint(Vec3(sprR, 0.28f, -halfL - 0.12f))
        val sprX3 = carMat.transformPoint(Vec3(sprR, 0.58f, -halfL - 0.12f))
        val sprX4 = carMat.transformPoint(Vec3(-sprR, 0.58f, -halfL - 0.12f))
        addQuadPolygon(sprX1, sprX2, sprX3, sprX4, bodyColor, eye, target, aspect, screenW, screenH)

        val sprRing1 = carMat.transformPoint(Vec3(-sprR - 0.03f, 0.25f, -halfL - 0.11f))
        val sprRing2 = carMat.transformPoint(Vec3(sprR + 0.03f, 0.25f, -halfL - 0.11f))
        val sprRing3 = carMat.transformPoint(Vec3(sprR + 0.03f, 0.61f, -halfL - 0.11f))
        val sprRing4 = carMat.transformPoint(Vec3(-sprR - 0.03f, 0.61f, -halfL - 0.11f))
        addQuadPolygon(sprRing1, sprRing2, sprRing3, sprRing4, chromeColor, eye, target, aspect, screenW, screenH)

        // Heavy Rear Step Bumper
        val rb1 = carMat.transformPoint(Vec3(-halfW, 0.08f, -halfL - 0.08f))
        val rb2 = carMat.transformPoint(Vec3(halfW, 0.08f, -halfL - 0.08f))
        val rb3 = carMat.transformPoint(Vec3(halfW, 0.22f, -halfL - 0.08f))
        val rb4 = carMat.transformPoint(Vec3(-halfW, 0.22f, -halfL - 0.08f))
        addQuadPolygon(rb1, rb2, rb3, rb4, darkPlastic, eye, target, aspect, screenW, screenH)

        val rbStep1 = carMat.transformPoint(Vec3(-halfW * 0.90f, 0.22f, -halfL - 0.08f))
        val rbStep2 = carMat.transformPoint(Vec3(halfW * 0.90f, 0.22f, -halfL - 0.08f))
        val rbStep3 = carMat.transformPoint(Vec3(halfW * 0.90f, 0.22f, -halfL))
        val rbStep4 = carMat.transformPoint(Vec3(-halfW * 0.90f, 0.22f, -halfL))
        addQuadPolygon(rbStep1, rbStep2, rbStep3, rbStep4, chromeColor, eye, target, aspect, screenW, screenH)

        // Dual Chrome Exhaust Pipes
        val ex1 = carMat.transformPoint(Vec3(halfW * 0.55f, 0.12f, -halfL - 0.12f))
        val ex2 = carMat.transformPoint(Vec3(halfW * 0.78f, 0.12f, -halfL - 0.12f))
        val ex3 = carMat.transformPoint(Vec3(halfW * 0.78f, 0.18f, -halfL - 0.04f))
        val ex4 = carMat.transformPoint(Vec3(halfW * 0.55f, 0.18f, -halfL - 0.04f))
        addQuadPolygon(ex1, ex2, ex3, ex4, chromeColor, eye, target, aspect, screenW, screenH)
    }

    /**
     * 3D TOYOTA HILUX PICKUP MODEL
     */
    private fun collectHiluxPolygons(
        car: CarState, eye: Vec3, target: Vec3, aspect: Float, screenW: Float, screenH: Float
    ) {
        val carMat = Mat4.translation(car.pos.x, car.pos.y, car.pos.z)
            .multiply(Mat4.rotationY(-car.headingAngleRad))

        val halfW = car.width / 2f
        val halfL = car.length / 2f
        val h = car.height // 1.85m height

        val bodyColor = Color(car.customBodyColorHex)
        val bodyShadow = bodyColor.copy(
            red = (bodyColor.red * 0.78f).coerceIn(0f, 1f),
            green = (bodyColor.green * 0.78f).coerceIn(0f, 1f),
            blue = (bodyColor.blue * 0.78f).coerceIn(0f, 1f)
        )
        val chromeColor = Color(0xFFE2E8F0)
        val darkPlastic = Color(0xFF1E293B)
        val glassColor = Color(0xFF0F172A)
        val bedLinerColor = Color(0xFF0F172A)
        val ledWhite = Color(0xFFFEF08A)
        val brakeLightColor = if (car.brakeInput > 0.1f) Color(0xFFFF0000) else Color(0xFF990000)

        // Drop Shadow
        val shadowRelY = -0.09f
        val sOut1 = carMat.transformPoint(Vec3(-halfW * 1.15f, shadowRelY, -halfL * 1.08f))
        val sOut2 = carMat.transformPoint(Vec3(halfW * 1.15f, shadowRelY, -halfL * 1.08f))
        val sOut3 = carMat.transformPoint(Vec3(halfW * 1.15f, shadowRelY, halfL * 1.08f))
        val sOut4 = carMat.transformPoint(Vec3(-halfW * 1.15f, shadowRelY, halfL * 1.08f))
        addQuadPolygon(sOut1, sOut2, sOut3, sOut4, Color(0x33000000), eye, target, aspect, screenW, screenH, layer = 1.3f, avgZBias = 0.002f)

        // Off-Road Knobby Tires & Custom Wheels
        val wheelOffsets = arrayOf(
            Vec3(-halfW * 0.94f, 0.05f, halfL * 0.65f),
            Vec3(halfW * 0.94f, 0.05f, halfL * 0.65f),
            Vec3(-halfW * 0.94f, 0.05f, -halfL * 0.55f),
            Vec3(halfW * 0.94f, 0.05f, -halfL * 0.55f)
        )
        addDetailed3DWheels(carMat, wheelOffsets, car.selectedRimStyle, tireRadius = 0.36f, eye = eye, target = target, aspect = aspect, screenW = screenW, screenH = screenH)
        addDecalsAndStripes(carMat, car.selectedDecalStyle, halfW, halfL, h, eye, target, aspect, screenW, screenH)
        addCustomSpoiler(carMat, car.selectedSpoilerStyle, halfW, halfL, h, eye, target, aspect, screenW, screenH)
        addDoorHandlesAndWipers(carMat, halfW, halfL, h, eye, target, aspect, screenW, screenH)

        // Heavy-Duty Bull-Bar & Front Bumper
        val bb1 = carMat.transformPoint(Vec3(-halfW * 0.98f, 0.15f, halfL + 0.06f))
        val bb2 = carMat.transformPoint(Vec3(halfW * 0.98f, 0.15f, halfL + 0.06f))
        val bb3 = carMat.transformPoint(Vec3(halfW * 0.98f, 0.42f, halfL + 0.06f))
        val bb4 = carMat.transformPoint(Vec3(-halfW * 0.98f, 0.42f, halfL + 0.06f))
        addQuadPolygon(bb1, bb2, bb3, bb4, darkPlastic, eye, target, aspect, screenW, screenH)

        // Front Lower Metal Skid Plate
        val sk1 = carMat.transformPoint(Vec3(-halfW * 0.70f, 0.08f, halfL + 0.08f))
        val sk2 = carMat.transformPoint(Vec3(halfW * 0.70f, 0.08f, halfL + 0.08f))
        val sk3 = carMat.transformPoint(Vec3(halfW * 0.70f, 0.22f, halfL + 0.07f))
        val sk4 = carMat.transformPoint(Vec3(-halfW * 0.70f, 0.22f, halfL + 0.07f))
        addQuadPolygon(sk1, sk2, sk3, sk4, chromeColor, eye, target, aspect, screenW, screenH)

        // Heavy Off-Road Bull-Bar Tube Frame
        val tube1 = carMat.transformPoint(Vec3(-halfW * 0.85f, 0.32f, halfL + 0.12f))
        val tube2 = carMat.transformPoint(Vec3(halfW * 0.85f, 0.32f, halfL + 0.12f))
        val tube3 = carMat.transformPoint(Vec3(halfW * 0.85f, 0.46f, halfL + 0.12f))
        val tube4 = carMat.transformPoint(Vec3(-halfW * 0.85f, 0.46f, halfL + 0.12f))
        addQuadPolygon(tube1, tube2, tube3, tube4, darkPlastic, eye, target, aspect, screenW, screenH)

        // Twin Auxiliary Bumper Lights
        val bFogL1 = carMat.transformPoint(Vec3(-0.35f, 0.35f, halfL + 0.13f))
        val bFogL2 = carMat.transformPoint(Vec3(-0.12f, 0.35f, halfL + 0.13f))
        val bFogL3 = carMat.transformPoint(Vec3(-0.12f, 0.43f, halfL + 0.13f))
        val bFogL4 = carMat.transformPoint(Vec3(-0.35f, 0.43f, halfL + 0.13f))
        addQuadPolygon(bFogL1, bFogL2, bFogL3, bFogL4, ledWhite, eye, target, aspect, screenW, screenH)

        val bFogR1 = carMat.transformPoint(Vec3(0.12f, 0.35f, halfL + 0.13f))
        val bFogR2 = carMat.transformPoint(Vec3(0.35f, 0.35f, halfL + 0.13f))
        val bFogR3 = carMat.transformPoint(Vec3(0.35f, 0.43f, halfL + 0.13f))
        val bFogR4 = carMat.transformPoint(Vec3(0.12f, 0.43f, halfL + 0.13f))
        addQuadPolygon(bFogR1, bFogR2, bFogR3, bFogR4, ledWhite, eye, target, aspect, screenW, screenH)

        // Front Grille Face & Toyota Emblem
        val g1 = carMat.transformPoint(Vec3(-halfW * 0.58f, 0.42f, halfL + 0.02f))
        val g2 = carMat.transformPoint(Vec3(halfW * 0.58f, 0.42f, halfL + 0.02f))
        val g3 = carMat.transformPoint(Vec3(halfW * 0.52f, 0.62f, halfL + 0.02f))
        val g4 = carMat.transformPoint(Vec3(-halfW * 0.52f, 0.62f, halfL + 0.02f))
        addQuadPolygon(g1, g2, g3, g4, darkPlastic, eye, target, aspect, screenW, screenH)

        val emb1 = carMat.transformPoint(Vec3(-0.16f, 0.50f, halfL + 0.04f))
        val emb2 = carMat.transformPoint(Vec3(0.16f, 0.50f, halfL + 0.04f))
        val emb3 = carMat.transformPoint(Vec3(0.16f, 0.56f, halfL + 0.04f))
        val emb4 = carMat.transformPoint(Vec3(-0.16f, 0.56f, halfL + 0.04f))
        addQuadPolygon(emb1, emb2, emb3, emb4, chromeColor, eye, target, aspect, screenW, screenH)

        // Aggressive LED Projector Headlights
        val hL1 = carMat.transformPoint(Vec3(-halfW * 0.96f, 0.44f, halfL + 0.02f))
        val hL2 = carMat.transformPoint(Vec3(-halfW * 0.58f, 0.44f, halfL + 0.02f))
        val hL3 = carMat.transformPoint(Vec3(-halfW * 0.52f, 0.62f, halfL + 0.02f))
        val hL4 = carMat.transformPoint(Vec3(-halfW * 0.96f, 0.62f, halfL + 0.02f))
        addQuadPolygon(hL1, hL2, hL3, hL4, ledWhite, eye, target, aspect, screenW, screenH)

        val hR1 = carMat.transformPoint(Vec3(halfW * 0.58f, 0.44f, halfL + 0.02f))
        val hR2 = carMat.transformPoint(Vec3(halfW * 0.96f, 0.44f, halfL + 0.02f))
        val hR3 = carMat.transformPoint(Vec3(halfW * 0.96f, 0.62f, halfL + 0.02f))
        val hR4 = carMat.transformPoint(Vec3(halfW * 0.52f, 0.62f, halfL + 0.02f))
        addQuadPolygon(hR1, hR2, hR3, hR4, ledWhite, eye, target, aspect, screenW, screenH)

        // Iranian License Plates (Front & Rear)
        addIranianLicensePlate(carMat, Vec3(0f, 0.28f, halfL + 0.08f), 0.52f, 0.12f, eye, target, aspect, screenW, screenH, isFront = true)
        addIranianLicensePlate(carMat, Vec3(0f, 0.38f, -halfL - 0.03f), 0.52f, 0.12f, eye, target, aspect, screenW, screenH, isFront = false)

        // Chrome Side Mirrors
        addSideMirrors(carMat, halfW, h * 0.60f, halfL * 0.30f, chromeColor, eye, target, aspect, screenW, screenH)

        // Double-Cab Lower Body Side Panels (100% Solid Body Work)
        val sL1 = carMat.transformPoint(Vec3(-halfW, 0.15f, halfL))
        val sL2 = carMat.transformPoint(Vec3(-halfW, h * 0.58f, halfL))
        val sL3 = carMat.transformPoint(Vec3(-halfW, h * 0.58f, -halfL * 0.20f))
        val sL4 = carMat.transformPoint(Vec3(-halfW, 0.15f, -halfL * 0.20f))
        addQuadPolygon(sL1, sL2, sL3, sL4, bodyShadow, eye, target, aspect, screenW, screenH)

        val sR1 = carMat.transformPoint(Vec3(halfW, 0.15f, halfL))
        val sR2 = carMat.transformPoint(Vec3(halfW, 0.15f, -halfL * 0.20f))
        val sR3 = carMat.transformPoint(Vec3(halfW, h * 0.58f, -halfL * 0.20f))
        val sR4 = carMat.transformPoint(Vec3(halfW, h * 0.58f, halfL))
        addQuadPolygon(sR1, sR2, sR3, sR4, bodyColor, eye, target, aspect, screenW, screenH)

        // Molded Black Off-Road Fender Flares (Front & Rear Wheel Arches)
        val ffFL1 = carMat.transformPoint(Vec3(-halfW - 0.03f, 0.22f, halfL * 0.45f))
        val ffFL2 = carMat.transformPoint(Vec3(-halfW - 0.03f, 0.22f, halfL * 0.85f))
        val ffFL3 = carMat.transformPoint(Vec3(-halfW - 0.03f, 0.48f, halfL * 0.80f))
        val ffFL4 = carMat.transformPoint(Vec3(-halfW - 0.03f, 0.48f, halfL * 0.50f))
        addQuadPolygon(ffFL1, ffFL2, ffFL3, ffFL4, darkPlastic, eye, target, aspect, screenW, screenH)

        val ffFR1 = carMat.transformPoint(Vec3(halfW + 0.03f, 0.22f, halfL * 0.45f))
        val ffFR2 = carMat.transformPoint(Vec3(halfW + 0.03f, 0.22f, halfL * 0.85f))
        val ffFR3 = carMat.transformPoint(Vec3(halfW + 0.03f, 0.48f, halfL * 0.80f))
        val ffFR4 = carMat.transformPoint(Vec3(halfW + 0.03f, 0.48f, halfL * 0.50f))
        addQuadPolygon(ffFR1, ffFR2, ffFR3, ffFR4, darkPlastic, eye, target, aspect, screenW, screenH)

        // Tubular Rock Slider Side Steps
        val stL1 = carMat.transformPoint(Vec3(-halfW - 0.08f, 0.20f, -halfL * 0.18f))
        val stL2 = carMat.transformPoint(Vec3(-halfW - 0.08f, 0.20f, halfL * 0.35f))
        val stL3 = carMat.transformPoint(Vec3(-halfW, 0.25f, halfL * 0.35f))
        val stL4 = carMat.transformPoint(Vec3(-halfW, 0.25f, -halfL * 0.18f))
        addQuadPolygon(stL1, stL2, stL3, stL4, chromeColor, eye, target, aspect, screenW, screenH)

        val stR1 = carMat.transformPoint(Vec3(halfW, 0.25f, -halfL * 0.18f))
        val stR2 = carMat.transformPoint(Vec3(halfW, 0.25f, halfL * 0.35f))
        val stR3 = carMat.transformPoint(Vec3(halfW + 0.08f, 0.20f, halfL * 0.35f))
        val stR4 = carMat.transformPoint(Vec3(halfW + 0.08f, 0.20f, -halfL * 0.18f))
        addQuadPolygon(stR1, stR2, stR3, stR4, chromeColor, eye, target, aspect, screenW, screenH)

        // Muscular Hood & Intercooler Scoop
        val hd1 = carMat.transformPoint(Vec3(-halfW, 0.62f, halfL))
        val hd2 = carMat.transformPoint(Vec3(halfW, 0.62f, halfL))
        val hd3 = carMat.transformPoint(Vec3(halfW * 0.88f, h * 0.58f, halfL * 0.32f))
        val hd4 = carMat.transformPoint(Vec3(-halfW * 0.88f, h * 0.58f, halfL * 0.32f))
        addQuadPolygon(hd1, hd2, hd3, hd4, bodyColor, eye, target, aspect, screenW, screenH)

        // Intercooler Air Scoop
        val sc1 = carMat.transformPoint(Vec3(-0.28f, h * 0.58f + 0.01f, halfL * 0.52f))
        val sc2 = carMat.transformPoint(Vec3(0.28f, h * 0.58f + 0.01f, halfL * 0.52f))
        val sc3 = carMat.transformPoint(Vec3(0.22f, h * 0.58f + 0.06f, halfL * 0.68f))
        val sc4 = carMat.transformPoint(Vec3(-0.22f, h * 0.58f + 0.06f, halfL * 0.68f))
        addQuadPolygon(sc1, sc2, sc3, sc4, darkPlastic, eye, target, aspect, screenW, screenH)

        // Off-Road Snorkel Air Intake (Right A-Pillar)
        val snk1 = carMat.transformPoint(Vec3(halfW * 0.86f, h * 0.58f, halfL * 0.28f))
        val snk2 = carMat.transformPoint(Vec3(halfW * 0.94f, h * 0.58f, halfL * 0.28f))
        val snk3 = carMat.transformPoint(Vec3(halfW * 0.88f, h + 0.08f, halfL * 0.08f))
        val snk4 = carMat.transformPoint(Vec3(halfW * 0.82f, h + 0.08f, halfL * 0.08f))
        addQuadPolygon(snk1, snk2, snk3, snk4, darkPlastic, eye, target, aspect, screenW, screenH)

        // Double-Cab Windshield & Roof
        val w1 = carMat.transformPoint(Vec3(-halfW * 0.88f, h * 0.58f, halfL * 0.32f))
        val w2 = carMat.transformPoint(Vec3(halfW * 0.88f, h * 0.58f, halfL * 0.32f))
        val w3 = carMat.transformPoint(Vec3(-halfW * 0.80f, h, halfL * 0.08f))
        val w4 = carMat.transformPoint(Vec3(halfW * 0.80f, h, halfL * 0.08f))
        addQuadPolygon(w1, w2, w4, w3, glassColor, eye, target, aspect, screenW, screenH)

        val r1 = carMat.transformPoint(Vec3(-halfW * 0.80f, h, -halfL * 0.18f))
        val r2 = carMat.transformPoint(Vec3(halfW * 0.80f, h, -halfL * 0.18f))
        addQuadPolygon(w3, w4, r2, r1, bodyColor, eye, target, aspect, screenW, screenH)

        val rw1 = carMat.transformPoint(Vec3(-halfW * 0.85f, h * 0.58f, -halfL * 0.20f))
        val rw2 = carMat.transformPoint(Vec3(halfW * 0.85f, h * 0.58f, -halfL * 0.20f))
        addQuadPolygon(r1, r2, rw2, rw1, glassColor, eye, target, aspect, screenW, screenH)

        // Side Glass Windows
        val sgL1 = carMat.transformPoint(Vec3(-halfW - 0.01f, h * 0.60f, -halfL * 0.18f))
        val sgL2 = carMat.transformPoint(Vec3(-halfW - 0.01f, h * 0.60f, halfL * 0.30f))
        val sgL3 = carMat.transformPoint(Vec3(-halfW * 0.80f, h - 0.02f, halfL * 0.08f))
        val sgL4 = carMat.transformPoint(Vec3(-halfW * 0.80f, h - 0.02f, -halfL * 0.16f))
        addQuadPolygon(sgL1, sgL2, sgL3, sgL4, glassColor, eye, target, aspect, screenW, screenH)

        val sgR1 = carMat.transformPoint(Vec3(halfW + 0.01f, h * 0.60f, -halfL * 0.18f))
        val sgR2 = carMat.transformPoint(Vec3(halfW + 0.01f, h * 0.60f, halfL * 0.30f))
        val sgR3 = carMat.transformPoint(Vec3(halfW * 0.80f, h - 0.02f, halfL * 0.08f))
        val sgR4 = carMat.transformPoint(Vec3(halfW * 0.80f, h - 0.02f, -halfL * 0.16f))
        addQuadPolygon(sgR1, sgR2, sgR3, sgR4, glassColor, eye, target, aspect, screenW, screenH)

        // Open Rear Truck Bed Floor
        val bFloor1 = carMat.transformPoint(Vec3(-halfW * 0.88f, 0.36f, -halfL * 0.22f))
        val bFloor2 = carMat.transformPoint(Vec3(halfW * 0.88f, 0.36f, -halfL * 0.22f))
        val bFloor3 = carMat.transformPoint(Vec3(halfW * 0.88f, 0.36f, -halfL * 0.96f))
        val bFloor4 = carMat.transformPoint(Vec3(-halfW * 0.88f, 0.36f, -halfL * 0.96f))
        addQuadPolygon(bFloor1, bFloor2, bFloor3, bFloor4, bedLinerColor, eye, target, aspect, screenW, screenH)

        // Truck Bed Outer Side Walls
        val bWallL1 = carMat.transformPoint(Vec3(-halfW, 0.18f, -halfL * 0.20f))
        val bWallL2 = carMat.transformPoint(Vec3(-halfW, 0.18f, -halfL))
        val bWallL3 = carMat.transformPoint(Vec3(-halfW, h * 0.54f, -halfL))
        val bWallL4 = carMat.transformPoint(Vec3(-halfW, h * 0.54f, -halfL * 0.20f))
        addQuadPolygon(bWallL1, bWallL2, bWallL3, bWallL4, bodyShadow, eye, target, aspect, screenW, screenH)

        val bWallR1 = carMat.transformPoint(Vec3(halfW, 0.18f, -halfL * 0.20f))
        val bWallR2 = carMat.transformPoint(Vec3(halfW, 0.18f, -halfL))
        val bWallR3 = carMat.transformPoint(Vec3(halfW, h * 0.54f, -halfL))
        val bWallR4 = carMat.transformPoint(Vec3(halfW, h * 0.54f, -halfL * 0.20f))
        addQuadPolygon(bWallR1, bWallR2, bWallR3, bWallR4, bodyColor, eye, target, aspect, screenW, screenH)

        // Off-Road "4x4 TURBO" Decal Band on Bed Side
        val dclL1 = carMat.transformPoint(Vec3(-halfW - 0.01f, 0.28f, -halfL * 0.82f))
        val dclL2 = carMat.transformPoint(Vec3(-halfW - 0.01f, 0.28f, -halfL * 0.35f))
        val dclL3 = carMat.transformPoint(Vec3(-halfW - 0.01f, 0.38f, -halfL * 0.35f))
        val dclL4 = carMat.transformPoint(Vec3(-halfW - 0.01f, 0.38f, -halfL * 0.82f))
        addQuadPolygon(dclL1, dclL2, dclL3, dclL4, Color(0xFF38BDF8), eye, target, aspect, screenW, screenH)

        val dclR1 = carMat.transformPoint(Vec3(halfW + 0.01f, 0.28f, -halfL * 0.82f))
        val dclR2 = carMat.transformPoint(Vec3(halfW + 0.01f, 0.28f, -halfL * 0.35f))
        val dclR3 = carMat.transformPoint(Vec3(halfW + 0.01f, 0.38f, -halfL * 0.35f))
        val dclR4 = carMat.transformPoint(Vec3(halfW + 0.01f, 0.38f, -halfL * 0.82f))
        addQuadPolygon(dclR1, dclR2, dclR3, dclR4, Color(0xFF38BDF8), eye, target, aspect, screenW, screenH)

        // Tubular Steel Roll Bar behind Cab + Roof Light Pods
        val rb1 = carMat.transformPoint(Vec3(-halfW * 0.82f, h * 0.58f, -halfL * 0.22f))
        val rb2 = carMat.transformPoint(Vec3(halfW * 0.82f, h * 0.58f, -halfL * 0.22f))
        val rb3 = carMat.transformPoint(Vec3(halfW * 0.82f, h + 0.14f, -halfL * 0.22f))
        val rb4 = carMat.transformPoint(Vec3(-halfW * 0.82f, h + 0.14f, -halfL * 0.22f))
        addQuadPolygon(rb1, rb2, rb3, rb4, chromeColor, eye, target, aspect, screenW, screenH)

        // 4 Auxiliary Roof Spotlights on Roll Bar
        val podW = 0.18f
        val podSpacing = 0.32f
        for (i in -1..2) {
            val px = (i - 0.5f) * podSpacing
            val p1 = carMat.transformPoint(Vec3(px - podW / 2f, h + 0.12f, -halfL * 0.21f))
            val p2 = carMat.transformPoint(Vec3(px + podW / 2f, h + 0.12f, -halfL * 0.21f))
            val p3 = carMat.transformPoint(Vec3(px + podW / 2f, h + 0.24f, -halfL * 0.21f))
            val p4 = carMat.transformPoint(Vec3(px - podW / 2f, h + 0.24f, -halfL * 0.21f))
            addQuadPolygon(p1, p2, p3, p4, ledWhite, eye, target, aspect, screenW, screenH)
        }

        // Tailgate & Vertical Off-Road Taillights
        val tg1 = carMat.transformPoint(Vec3(-halfW, 0.18f, -halfL - 0.02f))
        val tg2 = carMat.transformPoint(Vec3(halfW, 0.18f, -halfL - 0.02f))
        val tg3 = carMat.transformPoint(Vec3(halfW, h * 0.54f, -halfL - 0.02f))
        val tg4 = carMat.transformPoint(Vec3(-halfW, h * 0.54f, -halfL - 0.02f))
        addQuadPolygon(tg1, tg2, tg3, tg4, bodyColor, eye, target, aspect, screenW, screenH)

        // Central TOYOTA Tailgate Badge Panel
        val tB1 = carMat.transformPoint(Vec3(-halfW * 0.45f, 0.34f, -halfL - 0.03f))
        val tB2 = carMat.transformPoint(Vec3(halfW * 0.45f, 0.34f, -halfL - 0.03f))
        val tB3 = carMat.transformPoint(Vec3(halfW * 0.45f, 0.46f, -halfL - 0.03f))
        val tB4 = carMat.transformPoint(Vec3(-halfW * 0.45f, 0.46f, -halfL - 0.03f))
        addQuadPolygon(tB1, tB2, tB3, tB4, darkPlastic, eye, target, aspect, screenW, screenH)

        val tL1 = carMat.transformPoint(Vec3(-halfW * 0.98f, 0.32f, -halfL - 0.03f))
        val tL2 = carMat.transformPoint(Vec3(-halfW * 0.82f, 0.32f, -halfL - 0.03f))
        val tL3 = carMat.transformPoint(Vec3(-halfW * 0.82f, h * 0.52f, -halfL - 0.03f))
        val tL4 = carMat.transformPoint(Vec3(-halfW * 0.98f, h * 0.52f, -halfL - 0.03f))
        addQuadPolygon(tL1, tL2, tL3, tL4, brakeLightColor, eye, target, aspect, screenW, screenH)

        val tR1 = carMat.transformPoint(Vec3(halfW * 0.82f, 0.32f, -halfL - 0.03f))
        val tR2 = carMat.transformPoint(Vec3(halfW * 0.98f, 0.32f, -halfL - 0.03f))
        val tR3 = carMat.transformPoint(Vec3(halfW * 0.98f, h * 0.52f, -halfL - 0.03f))
        val tR4 = carMat.transformPoint(Vec3(halfW * 0.82f, h * 0.52f, -halfL - 0.03f))
        addQuadPolygon(tR1, tR2, tR3, tR4, brakeLightColor, eye, target, aspect, screenW, screenH)

        // Rear Step Bumper with Tow Receiver
        val rB1 = carMat.transformPoint(Vec3(-halfW * 0.90f, 0.12f, -halfL - 0.08f))
        val rB2 = carMat.transformPoint(Vec3(halfW * 0.90f, 0.12f, -halfL - 0.08f))
        val rB3 = carMat.transformPoint(Vec3(halfW * 0.90f, 0.24f, -halfL - 0.02f))
        val rB4 = carMat.transformPoint(Vec3(-halfW * 0.90f, 0.24f, -halfL - 0.02f))
        addQuadPolygon(rB1, rB2, rB3, rB4, chromeColor, eye, target, aspect, screenW, screenH)
    }

    /**
     * Renders 3D Garage Showroom with rotating turntable stage, studio softbox lighting, soft shadows & ambient occlusion
     */
    fun renderGarageShowroom(
        drawScope: DrawScope,
        car: CarState,
        rotationAngleRad: Float,
        screenWidthPx: Float,
        screenHeightPx: Float
    ) {
        val aspect = screenWidthPx / screenHeightPx.coerceAtLeast(1f)
        val cameraEye = Vec3(0f, 1.45f, 4.4f)
        val cameraTarget = Vec3(0f, 0.15f, 0f)

        // Showroom Studio Background Gradient (Deep Metallic Charcoal to Warm Accent Sky)
        drawScope.drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color(0xFF030712), Color(0xFF0F172A), Color(0xFF1E293B), Color(0xFF090D16))
            )
        )

        activePolygonCount = 0

        // 1. Studio Overhead Softbox Light Fixture (Ceiling Light Canopy)
        val sbY = 4.8f
        val sbW = 2.4f
        val sbL = 3.6f
        val sb1 = Vec3(-sbW, sbY, sbL)
        val sb2 = Vec3(sbW, sbY, sbL)
        val sb3 = Vec3(sbW, sbY, -sbL)
        val sb4 = Vec3(-sbW, sbY, -sbL)
        addQuadPolygon(sb1, sb2, sb3, sb4, Color(0xFFF8FAFC), cameraEye, cameraTarget, aspect, screenWidthPx, screenHeightPx, layer = 0.1f)

        // Softbox Bevel Frame
        val sf1 = Vec3(-sbW - 0.15f, sbY + 0.1f, sbL + 0.15f)
        val sf2 = Vec3(sbW + 0.15f, sbY + 0.1f, sbL + 0.15f)
        val sf3 = Vec3(sbW + 0.15f, sbY + 0.1f, -sbL - 0.15f)
        val sf4 = Vec3(-sbW - 0.15f, sbY + 0.1f, -sbL - 0.15f)
        addQuadPolygon(sf1, sf2, sf3, sf4, Color(0xFF334155), cameraEye, cameraTarget, aspect, screenWidthPx, screenHeightPx, layer = 0.08f)

        // 2. Showroom Back Wall Studio Accent Lighting & LED Pillars
        val wallZ = -4.8f
        // Back Wall Matte Dark Panel
        addQuadPolygon(
            Vec3(-8f, 0f, wallZ), Vec3(8f, 0f, wallZ),
            Vec3(8f, 6f, wallZ), Vec3(-8f, 6f, wallZ),
            Color(0xFF0F172A), cameraEye, cameraTarget, aspect, screenWidthPx, screenHeightPx, layer = 0.12f
        )

        // Vertical Studio Accent LED Light Bars
        for (x in listOf(-6f, -3.8f, -1.8f, 0f, 1.8f, 3.8f, 6f)) {
            addQuadPolygon(
                Vec3(x - 0.18f, 0f, wallZ + 0.02f), Vec3(x + 0.18f, 0f, wallZ + 0.02f),
                Vec3(x + 0.18f, 5.5f, wallZ + 0.02f), Vec3(x - 0.18f, 5.5f, wallZ + 0.02f),
                Color(0xFF38BDF8), cameraEye, cameraTarget, aspect, screenWidthPx, screenHeightPx, layer = 0.2f
            )
            // Outer Ambient Glow Band
            addQuadPolygon(
                Vec3(x - 0.35f, 0f, wallZ + 0.01f), Vec3(x + 0.35f, 0f, wallZ + 0.01f),
                Vec3(x + 0.35f, 5.5f, wallZ + 0.01f), Vec3(x - 0.35f, 5.5f, wallZ + 0.01f),
                Color(0x330284C7), cameraEye, cameraTarget, aspect, screenWidthPx, screenHeightPx, layer = 0.18f
            )
        }

        // 3. Showroom Circular Turntable Stage Floor with Metallic Rim
        val turntableCenter = Vec3(0f, 0.02f, 0f)
        val radius = 3.6f
        val segments = 32
        for (i in 0 until segments) {
            val a1 = (i * 2 * Math.PI / segments).toFloat()
            val a2 = ((i + 1) * 2 * Math.PI / segments).toFloat()
            val p1 = Vec3(sin(a1) * radius, 0.02f, cos(a1) * radius)
            val p2 = Vec3(sin(a2) * radius, 0.02f, cos(a2) * radius)
            addTrianglePolygon(
                turntableCenter, p1, p2,
                if (i % 2 == 0) Color(0xFF1E293B) else Color(0xFF334155),
                cameraEye, cameraTarget, aspect, screenWidthPx, screenHeightPx, layer = 0.5f
            )
        }

        // Polished Floor Overhead Softbox Reflection Ring
        val refRadius = 2.0f
        for (i in 0 until segments) {
            val a1 = (i * 2 * Math.PI / segments).toFloat()
            val a2 = ((i + 1) * 2 * Math.PI / segments).toFloat()
            val p1 = Vec3(sin(a1) * refRadius, 0.022f, cos(a1) * refRadius)
            val p2 = Vec3(sin(a2) * refRadius, 0.022f, cos(a2) * refRadius)
            val p3 = Vec3(sin(a2) * (refRadius + 0.35f), 0.022f, cos(a2) * (refRadius + 0.35f))
            val p4 = Vec3(sin(a1) * (refRadius + 0.35f), 0.022f, cos(a1) * (refRadius + 0.35f))
            addQuadPolygon(p1, p2, p3, p4, Color(0x22F8FAFC), cameraEye, cameraTarget, aspect, screenWidthPx, screenHeightPx, layer = 0.52f)
        }

        // Showroom Glowing Edge Rings (Chrome Bevel & LED Rim)
        val rInner = radius
        val rMid = radius + 0.12f
        val rOuter = radius + 0.28f
        for (i in 0 until segments) {
            val a1 = (i * 2 * Math.PI / segments).toFloat()
            val a2 = ((i + 1) * 2 * Math.PI / segments).toFloat()

            // Inner Ring (Cyan Glow)
            val p1 = Vec3(sin(a1) * rInner, 0.03f, cos(a1) * rInner)
            val p2 = Vec3(sin(a2) * rInner, 0.03f, cos(a2) * rInner)
            val p3 = Vec3(sin(a2) * rMid, 0.03f, cos(a2) * rMid)
            val p4 = Vec3(sin(a1) * rMid, 0.03f, cos(a1) * rMid)
            addQuadPolygon(p1, p2, p3, p4, Color(0xFF38BDF8), cameraEye, cameraTarget, aspect, screenWidthPx, screenHeightPx, layer = 0.6f)

            // Outer Ring (Electric Blue Chrome Edge)
            val q1 = Vec3(sin(a1) * rMid, 0.03f, cos(a1) * rMid)
            val q2 = Vec3(sin(a2) * rMid, 0.03f, cos(a2) * rMid)
            val q3 = Vec3(sin(a2) * rOuter, 0.03f, cos(a2) * rOuter)
            val q4 = Vec3(sin(a1) * rOuter, 0.03f, cos(a1) * rOuter)
            addQuadPolygon(q1, q2, q3, q4, Color(0xFF0284C7), cameraEye, cameraTarget, aspect, screenWidthPx, screenHeightPx, layer = 0.6f)
        }

        // 4. Multi-Layer Soft Shadows Engine (Penumbra + Ambient Occlusion under Car)
        // Helper to rotate local car floor bounds by rotationAngleRad
        val cosR = cos(rotationAngleRad)
        val sinR = sin(rotationAngleRad)

        fun addRotatedShadowQuad(w: Float, l: Float, yHeight: Float, color: Color, layerVal: Float) {
            val hW = w / 2f
            val hL = l / 2f
            // Local 4 corners of shadow box
            val c1 = Vec3(-hW * cosR - (-hL) * sinR, yHeight, -hW * sinR + (-hL) * cosR)
            val c2 = Vec3(hW * cosR - (-hL) * sinR, yHeight, hW * sinR + (-hL) * cosR)
            val c3 = Vec3(hW * cosR - hL * sinR, yHeight, hW * sinR + hL * cosR)
            val c4 = Vec3(-hW * cosR - hL * sinR, yHeight, -hW * sinR + hL * cosR)
            addQuadPolygon(c1, c2, c3, c4, color, cameraEye, cameraTarget, aspect, screenWidthPx, screenHeightPx, layer = layerVal, avgZBias = 0.001f)
        }

        // Tier 1: Wide Soft Penumbra Ambient Falloff
        addRotatedShadowQuad(w = 3.2f, l = 4.8f, yHeight = 0.032f, color = Color(0x1F000000), layerVal = 0.65f)

        // Tier 2: Mid Penumbra Soft Shadow
        addRotatedShadowQuad(w = 2.6f, l = 4.0f, yHeight = 0.035f, color = Color(0x3B000000), layerVal = 0.66f)

        // Tier 3: Core Chassis Shadow Box
        addRotatedShadowQuad(w = 2.0f, l = 3.2f, yHeight = 0.038f, color = Color(0x7F000000), layerVal = 0.67f)

        // Tier 4: Deep Underbody Ambient Occlusion (AO)
        addRotatedShadowQuad(w = 1.5f, l = 2.5f, yHeight = 0.040f, color = Color(0xCC000000), layerVal = 0.68f)

        // 5. Wheel Contact Ambient Occlusion (AO) Patches directly under each of the 4 tires
        val wheelOffsetX = 0.82f
        val wheelOffsetZ = 1.25f
        val wheelRadiusAO = 0.32f
        val wheelAOCol = Color(0xDD000000)

        val localWheelPositions = listOf(
            Vec3(-wheelOffsetX, 0.042f, wheelOffsetZ),   // Front Left
            Vec3(wheelOffsetX, 0.042f, wheelOffsetZ),    // Front Right
            Vec3(-wheelOffsetX, 0.042f, -wheelOffsetZ),  // Rear Left
            Vec3(wheelOffsetX, 0.042f, -wheelOffsetZ)    // Rear Right
        )

        for (locPos in localWheelPositions) {
            val wx = locPos.x * cosR - locPos.z * sinR
            val wz = locPos.x * sinR + locPos.z * cosR
            val center = Vec3(wx, 0.042f, wz)
            val octSegments = 8
            for (s in 0 until octSegments) {
                val a1 = (s * 2 * Math.PI / octSegments).toFloat()
                val a2 = ((s + 1) * 2 * Math.PI / octSegments).toFloat()
                val p1 = center + Vec3(sin(a1) * wheelRadiusAO, 0f, cos(a1) * wheelRadiusAO)
                val p2 = center + Vec3(sin(a2) * wheelRadiusAO, 0f, cos(a2) * wheelRadiusAO)
                addTrianglePolygon(center, p1, p2, wheelAOCol, cameraEye, cameraTarget, aspect, screenWidthPx, screenHeightPx, layer = 0.69f)
            }
        }

        // Render car centered on turntable with rotation
        val origPos = car.pos
        val origHeading = car.headingAngleRad
        car.pos = Vec3(0f, 0.25f, 0f)
        car.headingAngleRad = rotationAngleRad

        collectCarPolygons(car, cameraEye, cameraTarget, aspect, screenWidthPx, screenHeightPx)

        // Restore state
        car.pos = origPos
        car.headingAngleRad = origHeading

        // Painter's Sort
        java.util.Arrays.sort(polygonPool, 0, activePolygonCount) { a, b ->
            if (a.layer != b.layer) {
                a.layer.compareTo(b.layer)
            } else {
                b.avgZ.compareTo(a.avgZ)
            }
        }

        // Render Polygons to Canvas
        val path = Path()
        for (i in 0 until activePolygonCount) {
            val poly = polygonPool[i]
            val vc = poly.vertexCount
            if (vc < 3) continue
            path.reset()
            path.moveTo(poly.px[0], poly.py[0])
            for (v in 1 until vc) {
                path.lineTo(poly.px[v], poly.py[v])
            }
            path.close()

            drawScope.drawPath(path = path, color = poly.color, style = Fill)
            if (poly.isOutline) {
                drawScope.drawPath(path = path, color = poly.outlineColor, style = Stroke(width = 1.2f))
            }
        }
    }

    private fun drawVehicleLighting(
        carPos: Vec3, headingAngleRad: Float, brakeInput: Float, isHighBeam: Boolean,
        eye: Vec3, target: Vec3, aspect: Float, screenW: Float, screenH: Float,
        isTrafficCar: Boolean = false
    ) {
        val forwardDir = Vec3(sin(headingAngleRad), 0f, cos(headingAngleRad))
        val rightDir = Vec3(cos(headingAngleRad), 0f, -sin(headingAngleRad))

        val beamDist = if (isHighBeam) 72f else 36f
        val beamSpread = if (isHighBeam) 12f else 8f
        val beamAlphaCore = if (isHighBeam) 0.58f else 0.38f

        // 1. Ground Spotlight Polygon - Core Beam
        val p1 = carPos + forwardDir * 1.8f - rightDir * 0.75f + Vec3(0f, 0.03f, 0f)
        val p2 = carPos + forwardDir * 1.8f + rightDir * 0.75f + Vec3(0f, 0.03f, 0f)
        val p3 = carPos + forwardDir * beamDist + rightDir * beamSpread + Vec3(0f, 0.03f, 0f)
        val p4 = carPos + forwardDir * beamDist - rightDir * beamSpread + Vec3(0f, 0.03f, 0f)

        addQuadPolygon(p1, p2, p3, p4, Color(0xFFFEF08A).copy(alpha = beamAlphaCore), eye, target, aspect, screenW, screenH, layer = 1.09f)

        // Outer soft spread cone
        val outerDist = beamDist * 1.25f
        val outerSpread = beamSpread * 1.35f
        val op3 = carPos + forwardDir * outerDist + rightDir * outerSpread + Vec3(0f, 0.03f, 0f)
        val op4 = carPos + forwardDir * outerDist - rightDir * outerSpread + Vec3(0f, 0.03f, 0f)
        addQuadPolygon(p1, p2, op3, op4, Color(0xFFFDE047).copy(alpha = beamAlphaCore * 0.5f), eye, target, aspect, screenW, screenH, layer = 1.08f)

        // 2. 3D Volumetric Light Shafts
        if (!isTrafficCar) {
            val leftLens = carPos + forwardDir * 1.8f - rightDir * 0.75f + Vec3(0f, 0.65f, 0f)
            val rightLens = carPos + forwardDir * 1.8f + rightDir * 0.75f + Vec3(0f, 0.65f, 0f)
            val leftBeamEnd = leftLens + forwardDir * (beamDist * 0.6f) - Vec3(0f, 0.5f, 0f)
            val rightBeamEnd = rightLens + forwardDir * (beamDist * 0.6f) - Vec3(0f, 0.5f, 0f)
            val beamW = if (isHighBeam) 2.0f else 1.4f

            addQuadPolygon(
                leftLens - rightDir * 0.15f, leftLens + rightDir * 0.15f,
                leftBeamEnd + rightDir * beamW, leftBeamEnd - rightDir * beamW,
                Color(0xFFFEF08A).copy(alpha = 0.16f), eye, target, aspect, screenW, screenH, layer = 2.5f
            )
            addQuadPolygon(
                rightLens - rightDir * 0.15f, rightLens + rightDir * 0.15f,
                rightBeamEnd + rightDir * beamW, rightBeamEnd - rightDir * beamW,
                Color(0xFFFEF08A).copy(alpha = 0.16f), eye, target, aspect, screenW, screenH, layer = 2.5f
            )
        }

        // 3. Rear Tail & Brake Light Ground Pools
        val rearCenter = carPos - forwardDir * 2.0f + Vec3(0f, 0.03f, 0f)
        val rearP1 = rearCenter - rightDir * 1.1f
        val rearP2 = rearCenter + rightDir * 1.1f
        val isBraking = brakeInput > 0.08f
        val rearDist = if (isBraking) 7.5f else 3.8f
        val rearSpread = if (isBraking) 3.2f else 1.8f
        val rearP3 = rearCenter - forwardDir * rearDist + rightDir * rearSpread
        val rearP4 = rearCenter - forwardDir * rearDist - rightDir * rearSpread

        val tailColor = if (isBraking) Color(0xFFFF0000).copy(alpha = 0.52f) else Color(0xFFDC2626).copy(alpha = 0.22f)
        addQuadPolygon(rearP1, rearP2, rearP3, rearP4, tailColor, eye, target, aspect, screenW, screenH, layer = 1.09f)
    }

    private fun renderParticles(
        drawScope: DrawScope, particles: List<Particle>, eye: Vec3, target: Vec3, aspect: Float, screenW: Float, screenH: Float
    ) {
        for (p in particles) {
            val proj = projectPoint(p.pos, eye, target, aspect) ?: continue
            if (proj.z <= 0.2f) continue
            val screenPos = ndcToScreen(proj, screenW, screenH)

            val radius = (p.size * 135f / proj.z).coerceIn(1.5f, 65f)

            when (p.type) {
                com.example.engine.ParticleType.SPARK -> {
                    // Bright glowing collision spark with white core
                    drawScope.drawCircle(color = Color(p.colorHex).copy(alpha = p.alpha), radius = radius, center = screenPos)
                    drawScope.drawCircle(color = Color(0xFFFFFFFF).copy(alpha = p.alpha), radius = radius * 0.45f, center = screenPos)
                }
                com.example.engine.ParticleType.DEBRIS -> {
                    // Impact debris / leaf / metal shard
                    drawScope.drawCircle(color = Color(p.colorHex).copy(alpha = p.alpha), radius = radius * 0.75f, center = screenPos)
                }
                com.example.engine.ParticleType.FIRE -> {
                    // Impact flame burst
                    drawScope.drawCircle(color = Color(0xFFEF4444).copy(alpha = p.alpha * 0.8f), radius = radius * 1.3f, center = screenPos)
                    drawScope.drawCircle(color = Color(0xFFFACC15).copy(alpha = p.alpha), radius = radius * 0.6f, center = screenPos)
                }
                com.example.engine.ParticleType.SMOKE -> {
                    // Volumetric tire smoke / crash smoke cloud (outer soft halo + inner core cloud)
                    val baseColor = Color(p.colorHex)
                    val outerAlpha = (p.alpha * 0.35f).coerceIn(0f, 1f)
                    val innerAlpha = (p.alpha * 0.65f).coerceIn(0f, 1f)

                    drawScope.drawCircle(
                        color = baseColor.copy(alpha = outerAlpha),
                        radius = radius * 1.45f,
                        center = screenPos
                    )
                    drawScope.drawCircle(
                        color = baseColor.copy(alpha = innerAlpha),
                        radius = radius * 0.85f,
                        center = screenPos
                    )
                }
            }
        }
    }

    private fun getBuildingWallColor(b: Building3D): Color {
        val base = Color(b.wallColorHex)
        return when (timeOfDay) {
            TimeOfDay.DAWN -> base.copy(red = (base.red * 0.95f).coerceIn(0f, 1f), green = (base.green * 0.85f).coerceIn(0f, 1f))
            TimeOfDay.DAY -> base
            TimeOfDay.SUNSET -> base.copy(red = (base.red * 0.9f).coerceIn(0f, 1f), green = (base.green * 0.75f).coerceIn(0f, 1f))
            TimeOfDay.DUSK -> Color(0xFF1E2235)
            TimeOfDay.NIGHT -> Color(0xFF1E293B)
        }
    }

    private fun getBuildingRoofColor(b: Building3D): Color {
        val base = Color(b.roofColorHex)
        return when (timeOfDay) {
            TimeOfDay.DAWN -> base.copy(red = (base.red * 0.9f).coerceIn(0f, 1f), green = (base.green * 0.8f).coerceIn(0f, 1f))
            TimeOfDay.DAY -> base
            TimeOfDay.SUNSET -> base.copy(red = (base.red * 0.85f).coerceIn(0f, 1f), green = (base.green * 0.7f).coerceIn(0f, 1f))
            TimeOfDay.DUSK -> Color(0xFF151928)
            TimeOfDay.NIGHT -> Color(0xFF0F172A)
        }
    }
}
