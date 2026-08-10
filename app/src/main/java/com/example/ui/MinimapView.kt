package com.example.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.CarState
import com.example.model.RoofType
import com.example.model.SatelliteMapData

@Composable
fun MinimapView(
    car: CarState,
    onExpandMapClick: () -> Unit,
    modifier: Modifier = Modifier,
    sizeDp: Dp = 120.dp
) {
    Box(
        modifier = modifier
            .size(sizeDp)
            .clip(CircleShape)
            .background(Color(0x660F172A)) // Transparent glass overlay background
            .border(2.dp, Color(0xDD38BDF8), CircleShape)
            .clickable { onExpandMapClick() }
            .testTag("minimap_view")
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawSatelliteMinimap(car = car, isFullScreen = false)
        }

        // Compass "N" badge
        Text(
            text = "N",
            color = Color(0xFFEF4444),
            fontSize = 11.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 4.dp)
        )

        // Expand icon
        Icon(
            imageVector = Icons.Default.Fullscreen,
            contentDescription = "Expand Map",
            tint = Color.White,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp)
                .size(16.dp)
        )
    }
}

fun DrawScope.drawSatelliteMinimap(
    car: CarState,
    isFullScreen: Boolean
) {
    val mapWidth = SatelliteMapData.MAP_MAX_X - SatelliteMapData.MAP_MIN_X // 440f
    val mapDepth = SatelliteMapData.MAP_MAX_Z - SatelliteMapData.MAP_MIN_Z // 600f

    // Scale calculation:
    // Full screen shows the entire 1:1 map overview
    // HUD overlay mode (isFullScreen = false) zooms in around player position for local navigation
    val localViewRadius = 75f // 75 meters view radius around player
    val scale = if (isFullScreen) {
        minOf(size.width / mapWidth, size.height / mapDepth)
    } else {
        size.width / (localViewRadius * 2f)
    }

    val offsetX = if (isFullScreen) (size.width - mapWidth * scale) / 2f else 0f
    val offsetY = if (isFullScreen) (size.height - mapDepth * scale) / 2f else 0f

    val screenCenterX = size.width / 2f
    val screenCenterY = size.height / 2f

    fun worldToMapX(x: Float): Float {
        return if (isFullScreen) {
            offsetX + (x - SatelliteMapData.MAP_MIN_X) * scale
        } else {
            screenCenterX + (x - car.pos.x) * scale
        }
    }

    fun worldToMapY(z: Float): Float {
        return if (isFullScreen) {
            offsetY + (z - SatelliteMapData.MAP_MIN_Z) * scale
        } else {
            screenCenterY + (z - car.pos.z) * scale
        }
    }

    // 1. Base Ground Layer
    if (isFullScreen) {
        drawRect(color = Color(0xFF181512), size = size) // Dark outer border
        drawRect(
            color = Color(0xFFCBAA7B),
            topLeft = Offset(offsetX, offsetY),
            size = Size(mapWidth * scale, mapDepth * scale)
        )
    } else {
        // Semi-transparent circular ground base for HUD overlay
        drawCircle(
            color = Color(0x99A88F68),
            radius = size.width / 2f,
            center = Offset(screenCenterX, screenCenterY)
        )
    }

    // 2. Earthwork Dirt Patches & Hillsides
    for (t in SatelliteMapData.terrains) {
        val tx = worldToMapX(t.minX)
        val ty = worldToMapY(t.minZ)
        val tw = (t.maxX - t.minX) * scale
        val th = (t.maxZ - t.minZ) * scale

        // Skip items out of bounds when in HUD overlay mode
        if (!isFullScreen && (tx + tw < 0f || tx > size.width || ty + th < 0f || ty > size.height)) {
            continue
        }

        val patchColor = if (isFullScreen) Color(t.colorHex) else Color(t.colorHex).copy(alpha = 0.75f)
        drawRect(
            color = patchColor,
            topLeft = Offset(tx, ty),
            size = Size(tw, th)
        )
    }

    // 3. Dirt Tracks & Excavation Cuts
    for (r in SatelliteMapData.roads) {
        if (!r.isDirt) continue
        val x1 = worldToMapX(r.startX)
        val y1 = worldToMapY(r.startZ)
        val x2 = worldToMapX(r.endX)
        val y2 = worldToMapY(r.endZ)
        val roadWidthPx = (r.width * scale).coerceAtLeast(2f)

        drawLine(
            color = Color(0xFF8A6C4A),
            start = Offset(x1, y1),
            end = Offset(x2, y2),
            strokeWidth = roadWidthPx
        )
    }

    // 4. Asphalt Paved Streets, Central Boulevard & Parking Aprons
    for (r in SatelliteMapData.roads) {
        if (r.isDirt) continue
        val x1 = worldToMapX(r.startX)
        val y1 = worldToMapY(r.startZ)
        val x2 = worldToMapX(r.endX)
        val y2 = worldToMapY(r.endZ)
        val roadWidthPx = (r.width * scale).coerceAtLeast(2.5f)

        // Sidewalk curb
        drawLine(
            color = Color(0xAA8A8F98),
            start = Offset(x1, y1),
            end = Offset(x2, y2),
            strokeWidth = roadWidthPx + 1.2f
        )
        // Asphalt pavement
        drawLine(
            color = Color(0xEE2C2F34),
            start = Offset(x1, y1),
            end = Offset(x2, y2),
            strokeWidth = roadWidthPx
        )

        // Yellow central lane markings for primary roads
        if (r.isPrimary) {
            drawLine(
                color = Color(0xFFFACC15),
                start = Offset(x1, y1),
                end = Offset(x2, y2),
                strokeWidth = if (isFullScreen) 1.0f else 1.2f
            )
        }
    }

    // 5. Sidewalk Trees & Boulevard Median Trees (Green Canopy Dots)
    for (t in SatelliteMapData.trees) {
        val tx = worldToMapX(t.position.x)
        val ty = worldToMapY(t.position.z)
        if (!isFullScreen && (tx < -10f || tx > size.width + 10f || ty < -10f || ty > size.height + 10f)) {
            continue
        }
        val tr = (t.radius * scale).coerceAtLeast(1.5f)
        drawCircle(
            color = Color(0xEE166534),
            radius = tr,
            center = Offset(tx, ty)
        )
    }

    // 6. Buildings with Shadows & Roof Modules
    val shadowOffsetX = -6f * scale
    val shadowOffsetY = 10f * scale

    // 6a. Building Shadows
    for (b in SatelliteMapData.buildings) {
        val bx = worldToMapX(b.minX)
        val by = worldToMapY(b.minZ)
        val bw = (b.maxX - b.minX) * scale
        val bh = (b.maxZ - b.minZ) * scale

        if (!isFullScreen && (bx + bw < -20f || bx > size.width + 20f || by + bh < -20f || by > size.height + 20f)) {
            continue
        }

        val shadowPath = Path().apply {
            moveTo(bx, by + bh)
            lineTo(bx + bw, by + bh)
            lineTo(bx + bw + shadowOffsetX, by + bh + shadowOffsetY)
            lineTo(bx + shadowOffsetX, by + bh + shadowOffsetY)
            lineTo(bx + shadowOffsetX, by + shadowOffsetY)
            lineTo(bx, by)
            close()
        }
        drawPath(path = shadowPath, color = Color(0x441E1A16))
    }

    // 6b. Building Footprints & Roof Details
    for (b in SatelliteMapData.buildings) {
        val bx = worldToMapX(b.minX)
        val by = worldToMapY(b.minZ)
        val bw = (b.maxX - b.minX) * scale
        val bh = (b.maxZ - b.minZ) * scale

        if (!isFullScreen && (bx + bw < -10f || bx > size.width + 10f || by + bh < -10f || by > size.height + 10f)) {
            continue
        }

        val isRedRoof = b.roofType == RoofType.PITCHED_RED
        val wallColor = Color(b.wallColorHex).copy(alpha = 0.9f)
        val roofColor = if (isRedRoof) Color(0xFFDC2626) else Color(b.roofColorHex).copy(alpha = 0.95f)

        // Wall parapet edge
        drawRect(
            color = wallColor,
            topLeft = Offset(bx, by),
            size = Size(bw, bh)
        )

        // Inner roof deck
        val padX = (0.6f * scale).coerceAtLeast(0.6f)
        val padY = (0.6f * scale).coerceAtLeast(0.6f)
        drawRect(
            color = roofColor,
            topLeft = Offset(bx + padX, by + padY),
            size = Size((bw - padX * 2f).coerceAtLeast(1f), (bh - padY * 2f).coerceAtLeast(1f))
        )

        // Commercial Shop Sign highlights on map
        if (b.isCommercialShop) {
            val signColor = Color(b.signColorHex)
            drawRect(
                color = signColor,
                topLeft = Offset(bx, by + bh - (1.2f * scale).coerceAtLeast(1.5f)),
                size = Size(bw, (1.2f * scale).coerceAtLeast(1.5f))
            )
        }

        // Roof penthouse boxes / chiller modules
        if (b.penthouseCount > 0 && bw > 8f) {
            val moduleWidth = bw / (b.penthouseCount + 1)
            for (p in 1..b.penthouseCount) {
                val mx = bx + p * moduleWidth - moduleWidth * 0.25f
                val my = by + bh * 0.2f
                val mw = moduleWidth * 0.5f
                val mh = bh * 0.6f
                drawRect(
                    color = Color(0xFFB0B6BE),
                    topLeft = Offset(mx, my),
                    size = Size(mw.coerceAtLeast(1.2f), mh.coerceAtLeast(1.2f))
                )
            }
        }

        // Outer dark outline
        drawRect(
            color = Color(0xFF1E232A),
            topLeft = Offset(bx, by),
            size = Size(bw, bh),
            style = Stroke(width = 0.8f)
        )
    }

    // 7. Radar Crosshairs for HUD mode
    if (!isFullScreen) {
        drawLine(
            color = Color(0x3338BDF8),
            start = Offset(0f, screenCenterY),
            end = Offset(size.width, screenCenterY),
            strokeWidth = 1f
        )
        drawLine(
            color = Color(0x3338BDF8),
            start = Offset(screenCenterX, 0f),
            end = Offset(screenCenterX, size.height),
            strokeWidth = 1f
        )
    }

    // 8. Player Car Position Marker & Real-Time Heading Arrow
    val carMapX = worldToMapX(car.pos.x)
    val carMapY = worldToMapY(car.pos.z)
    val angleDeg = Math.toDegrees(car.headingAngleRad.toDouble()).toFloat()

    // Glowing radar position pulse ring
    drawCircle(
        color = Color(0x4438BDF8),
        radius = if (isFullScreen) 10f else 12f,
        center = Offset(carMapX, carMapY)
    )
    drawCircle(
        color = Color(0xFF38BDF8),
        radius = if (isFullScreen) 10f else 12f,
        center = Offset(carMapX, carMapY),
        style = Stroke(width = 1.5f)
    )

    // Player Direction Arrow (Pride Car Position)
    rotate(degrees = angleDeg, pivot = Offset(carMapX, carMapY)) {
        val arrowPath = Path().apply {
            moveTo(carMapX, carMapY - 9f)
            lineTo(carMapX + 6f, carMapY + 7f)
            lineTo(carMapX, carMapY + 4f)
            lineTo(carMapX - 6f, carMapY + 7f)
            close()
        }
        drawPath(path = arrowPath, color = Color(0xFF0284C7))
        drawPath(path = arrowPath, color = Color.White, style = Stroke(width = 1.5f))
    }
}
