package com.example.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.CarState
import com.example.model.DecalStyle
import com.example.model.RimStyle
import com.example.model.SpoilerStyle
import com.example.model.TintLevel
import com.example.model.VehicleType
import com.example.renderer.Renderer3D
import kotlinx.coroutines.delay

private enum class GarageTab(val labelFa: String, val icon: ImageVector) {
    VEHICLES("خودروها", Icons.Default.DirectionsCar),
    PAINT("رنگ بدنه", Icons.Default.Palette),
    RIMS("رینگ اسپرت", Icons.Default.Build),
    DECALS("خط‌کشی و طرح", Icons.Default.Style),
    SPOILER("اسپویلر و باله", Icons.Default.Star),
    TINT("شیشه دودی", Icons.Default.Visibility)
}

@Composable
fun GarageScreen(
    carState: CarState,
    onSelectVehicle: (VehicleType, Long) -> Unit,
    onStartDrive: () -> Unit
) {
    val allVehicles = remember { VehicleType.entries }
    var selectedTab by remember { mutableStateOf(GarageTab.VEHICLES) }

    var selectedType by remember { mutableStateOf(carState.vehicleType) }
    var selectedColorHex by remember { mutableLongStateOf(carState.customBodyColorHex) }
    var selectedRimStyle by remember { mutableStateOf(carState.selectedRimStyle) }
    var selectedDecalStyle by remember { mutableStateOf(carState.selectedDecalStyle) }
    var selectedSpoilerStyle by remember { mutableStateOf(carState.selectedSpoilerStyle) }
    var selectedTintLevel by remember { mutableStateOf(carState.selectedTintLevel) }

    var turntableRotation by remember { mutableFloatStateOf(0.85f) }
    var isUserInteracting by remember { mutableStateOf(false) }

    val renderer = remember { Renderer3D() }
    val listState = rememberLazyListState()

    // Sync state into carState
    carState.vehicleType = selectedType
    carState.customBodyColorHex = selectedColorHex
    carState.selectedRimStyle = selectedRimStyle
    carState.selectedDecalStyle = selectedDecalStyle
    carState.selectedSpoilerStyle = selectedSpoilerStyle
    carState.selectedTintLevel = selectedTintLevel

    // Scroll vehicle carousel
    LaunchedEffect(selectedType) {
        val index = allVehicles.indexOf(selectedType)
        if (index >= 0) {
            listState.animateScrollToItem(index)
        }
    }

    // Smooth ambient turntable rotation
    LaunchedEffect(isUserInteracting) {
        if (!isUserInteracting) {
            while (true) {
                turntableRotation += 0.007f
                delay(16)
            }
        }
    }

    val currentIndex = allVehicles.indexOf(selectedType)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF030712),
                        Color(0xFF0F172A),
                        Color(0xFF020617)
                    )
                )
            )
    ) {
        // 1. FULL-SCREEN 3D INTERACTIVE SHOWROOM CANVAS
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { isUserInteracting = true },
                        onDragEnd = { isUserInteracting = false },
                        onDragCancel = { isUserInteracting = false },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            turntableRotation += dragAmount.x * 0.008f
                        }
                    )
                }
        ) {
            renderer.renderGarageShowroom(
                drawScope = this,
                car = carState,
                rotationAngleRad = turntableRotation,
                screenWidthPx = size.width,
                screenHeightPx = size.height
            )
        }

        // 2. TOP GLASSMOPHIC HEADER & CATEGORY TABS BAR
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(top = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header Title Pill
            Surface(
                color = Color(0xD90F172A),
                shape = RoundedCornerShape(24.dp),
                border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF38BDF8))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.DirectionsCar,
                        contentDescription = null,
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "گاراژ و تیونینگ اختصاصی  •  ${selectedType.displayNameFa}",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Category Navigation Tabs (Vehicles, Paint, Rims, Decals, Spoiler, Tint)
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                itemsIndexed(GarageTab.entries) { idx, tab ->
                    val isSelected = (tab == selectedTab)
                    Surface(
                        modifier = Modifier
                            .clickable { selectedTab = tab }
                            .testTag("garage_tab_$idx"),
                        shape = RoundedCornerShape(14.dp),
                        color = if (isSelected) Color(0xFF0284C7) else Color(0xB30F172A),
                        border = androidx.compose.foundation.BorderStroke(
                            width = if (isSelected) 1.5.dp else 1.dp,
                            color = if (isSelected) Color(0xFF38BDF8) else Color(0x33FFFFFF)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = null,
                                tint = if (isSelected) Color.White else Color(0xFF94A3B8),
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = tab.labelFa,
                                color = if (isSelected) Color.White else Color(0xFFCBD5E1),
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Black else FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // TAB CONTENT PANEL
            when (selectedTab) {
                GarageTab.VEHICLES -> {
                    LazyRow(
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        itemsIndexed(allVehicles) { index, vehicle ->
                            val isSelected = (vehicle == selectedType)
                            Surface(
                                modifier = Modifier
                                    .clickable {
                                        selectedType = vehicle
                                        selectedColorHex = vehicle.defaultColorHex
                                        onSelectVehicle(selectedType, selectedColorHex)
                                    }
                                    .testTag("vehicle_card_$index"),
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) Color(0xEB0284C7) else Color(0x990F172A),
                                border = androidx.compose.foundation.BorderStroke(
                                    width = if (isSelected) 1.5.dp else 1.dp,
                                    color = if (isSelected) Color(0xFF38BDF8) else Color(0x22FFFFFF)
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = vehicle.displayNameFa,
                                        color = if (isSelected) Color.White else Color(0xFFCBD5E1),
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "${vehicle.maxSpeedKmh.toInt()} km/h",
                                        color = if (isSelected) Color(0xFFE0F2FE) else Color(0xFF38BDF8),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }

                GarageTab.PAINT -> {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        itemsIndexed(selectedType.availableColorsHex) { colIdx, colorHex ->
                            val isSelected = (colorHex == selectedColorHex)
                            Surface(
                                modifier = Modifier
                                    .clickable {
                                        selectedColorHex = colorHex
                                        onSelectVehicle(selectedType, selectedColorHex)
                                    }
                                    .testTag("paint_color_$colIdx"),
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0xD90F172A),
                                border = androidx.compose.foundation.BorderStroke(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) Color(0xFF38BDF8) else Color(0x33FFFFFF)
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(20.dp)
                                            .clip(CircleShape)
                                            .background(Color(colorHex))
                                            .border(1.dp, Color.White, CircleShape)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = when (colorHex) {
                                            0xFFF8FAFC -> "سفید متالیک"
                                            0xFF94A3B8 -> "نقره‌ای"
                                            0xFF0F172A -> "مشکی متالیک"
                                            0xFFDC2626 -> "قرمز اسپرت"
                                            0xFF1E3A8A -> "آبی متالیک"
                                            0xFF15803D -> "سبز متالیک"
                                            0xFFEAB308 -> "زرد اسپرت"
                                            else -> "رنگ سفارشی"
                                        },
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }

                GarageTab.RIMS -> {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        itemsIndexed(RimStyle.entries) { rimIdx, rim ->
                            val isSelected = (rim == selectedRimStyle)
                            Surface(
                                modifier = Modifier
                                    .clickable { selectedRimStyle = rim }
                                    .testTag("rim_style_$rimIdx"),
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) Color(0xEB0284C7) else Color(0xD90F172A),
                                border = androidx.compose.foundation.BorderStroke(
                                    width = if (isSelected) 1.5.dp else 1.dp,
                                    color = if (isSelected) Color(0xFF38BDF8) else Color(0x33FFFFFF)
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(16.dp)
                                            .clip(CircleShape)
                                            .background(Color(rim.rimColorHex))
                                            .border(1.dp, Color.White, CircleShape)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = rim.displayNameFa,
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }

                GarageTab.DECALS -> {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        itemsIndexed(DecalStyle.entries) { decalIdx, decal ->
                            val isSelected = (decal == selectedDecalStyle)
                            Surface(
                                modifier = Modifier
                                    .clickable { selectedDecalStyle = decal }
                                    .testTag("decal_style_$decalIdx"),
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) Color(0xEB0284C7) else Color(0xD90F172A),
                                border = androidx.compose.foundation.BorderStroke(
                                    width = if (isSelected) 1.5.dp else 1.dp,
                                    color = if (isSelected) Color(0xFF38BDF8) else Color(0x33FFFFFF)
                                )
                            ) {
                                Text(
                                    text = decal.displayNameFa,
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }

                GarageTab.SPOILER -> {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        itemsIndexed(SpoilerStyle.entries) { spoilerIdx, spoiler ->
                            val isSelected = (spoiler == selectedSpoilerStyle)
                            Surface(
                                modifier = Modifier
                                    .clickable { selectedSpoilerStyle = spoiler }
                                    .testTag("spoiler_style_$spoilerIdx"),
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) Color(0xEB0284C7) else Color(0xD90F172A),
                                border = androidx.compose.foundation.BorderStroke(
                                    width = if (isSelected) 1.5.dp else 1.dp,
                                    color = if (isSelected) Color(0xFF38BDF8) else Color(0x33FFFFFF)
                                )
                            ) {
                                Text(
                                    text = spoiler.displayNameFa,
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }

                GarageTab.TINT -> {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        itemsIndexed(TintLevel.entries) { tintIdx, tint ->
                            val isSelected = (tint == selectedTintLevel)
                            Surface(
                                modifier = Modifier
                                    .clickable { selectedTintLevel = tint }
                                    .testTag("tint_level_$tintIdx"),
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) Color(0xEB0284C7) else Color(0xD90F172A),
                                border = androidx.compose.foundation.BorderStroke(
                                    width = if (isSelected) 1.5.dp else 1.dp,
                                    color = if (isSelected) Color(0xFF38BDF8) else Color(0x33FFFFFF)
                                )
                            ) {
                                Text(
                                    text = tint.displayNameFa,
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // 3. FLOATING SIDE ARROW BUTTONS (Prev / Next Car)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center)
                .padding(horizontal = 12.dp)
        ) {
            IconButton(
                onClick = {
                    val prevIdx = if (currentIndex > 0) currentIndex - 1 else allVehicles.size - 1
                    selectedType = allVehicles[prevIdx]
                    selectedColorHex = selectedType.defaultColorHex
                    onSelectVehicle(selectedType, selectedColorHex)
                },
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .size(44.dp)
                    .background(Color(0xB30F172A), CircleShape)
                    .border(1.5.dp, Color(0x6638BDF8), CircleShape)
                    .testTag("prev_vehicle_btn")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "خودرو قبلی",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }

            IconButton(
                onClick = {
                    val nextIdx = (currentIndex + 1) % allVehicles.size
                    selectedType = allVehicles[nextIdx]
                    selectedColorHex = selectedType.defaultColorHex
                    onSelectVehicle(selectedType, selectedColorHex)
                },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(44.dp)
                    .background(Color(0xB30F172A), CircleShape)
                    .border(1.5.dp, Color(0x6638BDF8), CircleShape)
                    .testTag("next_vehicle_btn")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "خودرو بعدی",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // 4. FLOATING BOTTOM PANELS (Vehicle Performance Specs + Start Driving Action)
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            // LEFT PANEL: Vehicle Info & Performance Metrics
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 10.dp),
                shape = RoundedCornerShape(18.dp),
                color = Color(0xD90F172A),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x3338BDF8))
            ) {
                Column(
                    modifier = Modifier.padding(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            Text(
                                text = selectedType.displayNameFa,
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Black
                            )
                            Text(
                                text = selectedType.displayNameEn,
                                color = Color(0xFF94A3B8),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Surface(
                            color = Color(0x3338BDF8),
                            shape = RoundedCornerShape(6.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF38BDF8))
                        ) {
                            Text(
                                text = selectedType.specialTraitFa,
                                color = Color(0xFF38BDF8),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Performance Bars
                    StatBar(
                        label = "سرعت",
                        valueText = "${selectedType.maxSpeedKmh.toInt()} km/h",
                        progress = (selectedType.maxSpeedKmh / 260f).coerceIn(0.1f, 1.0f)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    StatBar(
                        label = "قدرت",
                        valueText = "${selectedType.accelerationForce.toInt()} HP",
                        progress = (selectedType.accelerationForce / 100f).coerceIn(0.1f, 1.0f)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    StatBar(
                        label = "چسبندگی",
                        valueText = "${(selectedType.tractionGrip * 100).toInt()}%",
                        progress = selectedType.tractionGrip.coerceIn(0.1f, 1.0f)
                    )
                }
            }

            // RIGHT PANEL: Drive Now Action Button
            Surface(
                modifier = Modifier.width(180.dp),
                shape = RoundedCornerShape(18.dp),
                color = Color(0xD90F172A),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x3338BDF8))
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "آماده حرکت؟",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Button(
                        onClick = {
                            onSelectVehicle(selectedType, selectedColorHex)
                            onStartDrive()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .testTag("start_driving_btn"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF0284C7),
                            contentColor = Color.White
                        )
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "شروع رانندگی",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatBar(
    label: String,
    valueText: String,
    progress: Float
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = label,
            color = Color(0xFF94A3B8),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(42.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .weight(1f)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = Color(0xFF0284C7),
            trackColor = Color(0xFF1E293B),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = valueText,
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.ExtraBold
        )
    }
}
