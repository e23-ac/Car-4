package com.example.model

import com.example.engine.Vec3

data class Building3D(
    val id: String,
    val name: String,
    val minX: Float,
    val maxX: Float,
    val minZ: Float,
    val maxZ: Float,
    val height: Float,
    val wallColorHex: Long = 0xFFECE7DE, // White/Cream stucco exterior
    val roofColorHex: Long = 0xFF5C626C, // Slate gray metal/asphalt roof
    val roofType: RoofType = RoofType.FLAT,
    val stories: Int = 5,
    val penthouseCount: Int = 4, // Number of roof modules on top
    val hasBalconies: Boolean = true,
    val isCommercialShop: Boolean = false,
    val signColorHex: Long = 0xFF2563EB
) {
    val centerX: Float get() = (minX + maxX) / 2f
    val centerZ: Float get() = (minZ + maxZ) / 2f
    val width: Float get() = maxX - minX
    val depth: Float get() = maxZ - minZ

    fun contains(x: Float, z: Float): Boolean {
        return x in minX..maxX && z in minZ..maxZ
    }
}

enum class RoofType {
    FLAT,
    PITCHED_RED, // Red roof landmark (Y-shape / Gabled)
    DOMED,
    TERRACED
}

data class RoadSegment(
    val id: String,
    val name: String,
    val startX: Float,
    val startZ: Float,
    val endX: Float,
    val endZ: Float,
    val width: Float,
    val isPrimary: Boolean = true,
    val isDirt: Boolean = false,
    val hasCrosswalks: Boolean = true
)

data class TerrainPatch(
    val minX: Float,
    val maxX: Float,
    val minZ: Float,
    val maxZ: Float,
    val baseHeight: Float,
    val topHeight: Float,
    val colorHex: Long = 0xFF967B5C,
    val isHill: Boolean = true
)

enum class TreeSpecies(
    val displayNameFa: String,
    val foliageColorHexDay: Long,
    val foliageColorHexSunset: Long,
    val foliageColorHexNight: Long
) {
    DECIDUOUS_LUSH("درخت پهن‌برگ سرسبز", 0xFF15803D, 0xFF166534, 0xFF052E16),
    PINE_CYPRESS("سرو و کاج کوهستانی", 0xFF0F5132, 0xFF0B3822, 0xFF041A0F),
    MEDITERRANEAN_PALM("نخل تزئینی بلوار", 0xFF16A34A, 0xFF15803D, 0xFF064E3B),
    AUTUMN_GOLDEN("چنار طلایی پاییزی", 0xFFD97706, 0xFFB45309, 0xFF451A03)
}

data class TreeItem(
    val position: Vec3,
    val radius: Float = 2.4f,
    val height: Float = 5.2f,
    val species: TreeSpecies = TreeSpecies.DECIDUOUS_LUSH,
    val hasPlanterBox: Boolean = true
)

data class StreetLight(
    val position: Vec3,
    val height: Float = 8f
)

object SatelliteMapData {
    // Map dimensions in world space (meters)
    const val MAP_MIN_X = -220f
    const val MAP_MAX_X = +220f
    const val MAP_MIN_Z = -300f
    const val MAP_MAX_Z = +300f

    val buildings = mutableListOf<Building3D>()
    val roads = mutableListOf<RoadSegment>()
    val terrains = mutableListOf<TerrainPatch>()
    val trees = mutableListOf<TreeItem>()
    val streetLights = mutableListOf<StreetLight>()

    init {
        buildMap()
    }

    private fun buildMap() {
        buildings.clear()
        roads.clear()
        terrains.clear()
        trees.clear()
        streetLights.clear()

        // -------------------------------------------------------------
        // 1. CENTRAL DUAL CARRIAGEWAY BOULEVARD (بلوار مرکزی دوطرفه)
        // -------------------------------------------------------------
        // North Lane (Eastbound / Westbound)
        roads.add(RoadSegment("rd_blvd_north", "بلوار مرکزی - باند شمالی", -210f, -12f, +210f, -12f, 11f, isPrimary = true))
        // South Lane (Westbound / Eastbound)
        roads.add(RoadSegment("rd_blvd_south", "بلوار مرکزی - باند جنوبی", -210f, +12f, +210f, +12f, 11f, isPrimary = true))

        // Center Green Boulevard Median Strip (رفوژ سرسبز وسط بلوار)
        terrains.add(
            TerrainPatch(
                minX = -210f, maxX = +210f,
                minZ = -5f, maxZ = +5f,
                baseHeight = 0f, topHeight = 0.25f,
                colorHex = 0xFF2E7D32, // Dark Emerald Grass
                isHill = false
            )
        )

        // Trees planted directly inside the Central Boulevard Median
        var blvdX = -190f
        var treeIdx = 0
        while (blvdX <= 190f) {
            // Leave openings for cross intersections
            if (kotlin.math.abs(blvdX - (-100f)) > 8f &&
                kotlin.math.abs(blvdX - (0f)) > 10f &&
                kotlin.math.abs(blvdX - (+100f)) > 8f
            ) {
                val species = if (treeIdx % 2 == 0) TreeSpecies.MEDITERRANEAN_PALM else TreeSpecies.DECIDUOUS_LUSH
                trees.add(
                    TreeItem(
                        position = Vec3(blvdX, 0f, 0f),
                        radius = 2.6f,
                        height = 5.8f,
                        species = species,
                        hasPlanterBox = true
                    )
                )
                treeIdx++
            }
            blvdX += 12f
        }

        // Boulevard Street Lights along Median
        var blvdLightX = -180f
        while (blvdLightX <= 180f) {
            streetLights.add(StreetLight(position = Vec3(blvdLightX, 0f, 0f), height = 7.5f))
            blvdLightX += 28f
        }

        // -------------------------------------------------------------
        // 2. TOP SECTION (NORTH): APARTMENT BUILDINGS (قسمت بالا - آپارتمانی)
        // -------------------------------------------------------------
        // Landmark Red Roof Apartment Complex North
        buildings.add(
            Building3D(
                id = "red_roof_hub",
                name = "مجتمع آپارتمانی قرمز - مرکزی",
                minX = -20f, maxX = +20f,
                minZ = -275f, maxZ = -255f,
                height = 16f,
                wallColorHex = 0xFFF8F6F0,
                roofColorHex = 0xFFD32F2F,
                roofType = RoofType.PITCHED_RED,
                stories = 5,
                penthouseCount = 1,
                hasBalconies = true
            )
        )
        buildings.add(
            Building3D(
                id = "red_roof_wing_l",
                name = "مجتمع آپارتمانی قرمز - جناح غربی",
                minX = -45f, maxX = -20f,
                minZ = -272f, maxZ = -258f,
                height = 14f,
                wallColorHex = 0xFFF8F6F0,
                roofColorHex = 0xFFD32F2F,
                roofType = RoofType.PITCHED_RED,
                stories = 4,
                penthouseCount = 0,
                hasBalconies = true
            )
        )
        buildings.add(
            Building3D(
                id = "red_roof_wing_r",
                name = "مجتمع آپارتمانی قرمز - جناح شرقی",
                minX = +20f, maxX = +45f,
                minZ = -272f, maxZ = -258f,
                height = 14f,
                wallColorHex = 0xFFF8F6F0,
                roofColorHex = 0xFFD32F2F,
                roofType = RoofType.PITCHED_RED,
                stories = 4,
                penthouseCount = 0,
                hasBalconies = true
            )
        )

        // Apartment Blocks Row 0 (Z = -235 to -212)
        buildings.add(Building3D("apt_r0_w", "بلوک آپارتمانی آفتاب ۱", -145f, -25f, -235f, -212f, 18f, 0xFFECE7DE, 0xFF4B5563, stories = 6, penthouseCount = 4, hasBalconies = true))
        buildings.add(Building3D("apt_r0_e", "بلوک آپارتمانی آفتاب ۲", -15f, +105f, -235f, -212f, 18f, 0xFFECE7DE, 0xFF4B5563, stories = 6, penthouseCount = 4, hasBalconies = true))

        // Apartment Blocks Row 1 (Z = -180 to -157)
        buildings.add(Building3D("apt_r1_w", "بلوک آپارتمانی نیلوفر ۱", -150f, -25f, -180f, -157f, 17f, 0xFFF3EFE6, 0xFF374151, stories = 5, penthouseCount = 4, hasBalconies = true))
        buildings.add(Building3D("apt_r1_e", "بلوک آپارتمانی نیلوفر ۲", -15f, +110f, -180f, -157f, 17f, 0xFFF3EFE6, 0xFF374151, stories = 5, penthouseCount = 4, hasBalconies = true))
        buildings.add(Building3D("apt_r1_far_e", "برج آپارتمانی پارس", +120f, +185f, -170f, -145f, 21f, 0xFFE2E8F0, 0xFF1E293B, stories = 7, penthouseCount = 3, hasBalconies = true))

        // Apartment Blocks Row 2 (Z = -125 to -102)
        buildings.add(Building3D("apt_r2_w", "بلوک آپارتمانی سپهر ۱", -155f, -25f, -125f, -102f, 16f, 0xFFECE7DE, 0xFF4B5563, stories = 5, penthouseCount = 4, hasBalconies = true))
        buildings.add(Building3D("apt_r2_e", "بلوک آپارتمانی سپهر ۲", -15f, +110f, -125f, -102f, 16f, 0xFFECE7DE, 0xFF4B5563, stories = 5, penthouseCount = 4, hasBalconies = true))

        // Apartment Blocks Row 3 (Z = -70 to -47)
        buildings.add(Building3D("apt_r3_w", "مجتمع آپارتمانی آریا غربی", -155f, -25f, -70f, -47f, 16f, 0xFFF5F2EC, 0xFF475569, stories = 5, penthouseCount = 4, hasBalconies = true))
        buildings.add(Building3D("apt_r3_e", "مجتمع آپارتمانی آریا شرقی", -15f, +110f, -70f, -47f, 16f, 0xFFF5F2EC, 0xFF475569, stories = 5, penthouseCount = 4, hasBalconies = true))

        // North Connecting Streets
        roads.add(RoadSegment("rd_n_str0", "خیابان شمالی ۰", -170f, -242f, +160f, -242f, 11f, isPrimary = false))
        roads.add(RoadSegment("rd_n_str1", "خیابان شمالی ۱", -170f, -190f, +190f, -190f, 11f, isPrimary = false))
        roads.add(RoadSegment("rd_n_str2", "خیابان شمالی ۲", -170f, -135f, +160f, -135f, 11f, isPrimary = false))
        roads.add(RoadSegment("rd_n_str3", "خیابان شمالی ۳", -170f, -80f, +160f, -80f, 11f, isPrimary = false))
        roads.add(RoadSegment("rd_n_str4", "خیابان شمالی ۴", -170f, -32f, +160f, -32f, 11f, isPrimary = false))

        // -------------------------------------------------------------
        // 3. BOTTOM SECTION (SOUTH): COMMERCIAL SHOPS (قسمت پایین - مغازه)
        // -------------------------------------------------------------
        // Commercial Frontage Row 1 (Right below Boulevard, Z = 32f to 52f)
        val shopRow1Xs = floatArrayOf(-170f, -125f, -80f, -35f, +10f, +55f, +100f, +145f)
        val shopRow1Names = arrayOf(
            "فروشگاه بزرگ کوثر", "مغازه لوازم یدکی پراید", "سوپرمارکت لاله", "پاساژ تجاری اطلس",
            "کافه و رستوران راد", "نمایشگاه اتومبیل پارس", "فروشگاه لوازم الکترونیک", "نانوایی و قنادی"
        )
        val shopColors = longArrayOf(
            0xFF2563EB, 0xFFDC2626, 0xFF16A34A, 0xFFD97706,
            0xFF7C3AED, 0xFF0284C7, 0xFFEA580C, 0xFF059669
        )

        for ((i, xVal) in shopRow1Xs.withIndex()) {
            buildings.add(
                Building3D(
                    id = "shop_r1_$i",
                    name = shopRow1Names[i % shopRow1Names.size],
                    minX = xVal, maxX = xVal + 36f,
                    minZ = 32f, maxZ = 54f,
                    height = 8.5f,
                    wallColorHex = 0xFFFAF3E0,
                    roofColorHex = 0xFF475569,
                    stories = 2,
                    penthouseCount = 0,
                    hasBalconies = false,
                    isCommercialShop = true,
                    signColorHex = shopColors[i % shopColors.size]
                )
            )
        }

        // Commercial Frontage Row 2 (Z = 85f to 110f)
        val shopRow2Xs = floatArrayOf(-170f, -125f, -80f, -35f, +10f, +55f, +100f, +145f)
        val shopRow2Names = arrayOf(
            "ابزارآلات و صنعتی", "بوتیک پوشاک صدف", "مغازه صوتی تصویری", "مجموعه تعمیرگاهی",
            "داروخانه شبانه‌روزی", "فروشگاه موبایل و رایانه", "مغازه فرش و موکت", "هایپرمارکت بزرگ"
        )

        for ((i, xVal) in shopRow2Xs.withIndex()) {
            buildings.add(
                Building3D(
                    id = "shop_r2_$i",
                    name = shopRow2Names[i % shopRow2Names.size],
                    minX = xVal, maxX = xVal + 36f,
                    minZ = 85f, maxZ = 110f,
                    height = 8.0f,
                    wallColorHex = 0xFFF1F5F9,
                    roofColorHex = 0xFF334155,
                    stories = 2,
                    penthouseCount = 0,
                    hasBalconies = false,
                    isCommercialShop = true,
                    signColorHex = shopColors[(i + 3) % shopColors.size]
                )
            )
        }

        // Commercial Frontage Row 3 & Passages (Z = 145f to 172f)
        val shopRow3Xs = floatArrayOf(-165f, -115f, -65f, -15f, +35f, +85f, +135f)
        val shopRow3Names = arrayOf(
            "مرکز خرید آفتاب", "مغازه شیشه و آینه", "تعویض روغنی و پنچرگیری", "پاساژ علاءالدین",
            "فروشگاه لوازم خانگی", "مغازه رنگ و ابزار", "رستوران سنتـی"
        )

        for ((i, xVal) in shopRow3Xs.withIndex()) {
            buildings.add(
                Building3D(
                    id = "shop_r3_$i",
                    name = shopRow3Names[i % shopRow3Names.size],
                    minX = xVal, maxX = xVal + 40f,
                    minZ = 145f, maxZ = 172f,
                    height = 9.0f,
                    wallColorHex = 0xFFFFFBEB,
                    roofColorHex = 0xFF1E293B,
                    stories = 2,
                    penthouseCount = 0,
                    hasBalconies = false,
                    isCommercialShop = true,
                    signColorHex = shopColors[(i + 5) % shopColors.size]
                )
            )
        }

        // Commercial Frontage Row 4 (South Highway Shops, Z = 205f to 235f)
        val shopRow4Xs = floatArrayOf(-160f, -100f, -40f, +20f, +80f, +140f)
        val shopRow4Names = arrayOf(
            "مجتمع تجاری جنوب", "فروشگاه مصالح ساختمانی", "مغازه مکانیکی و صافکاری",
            "هایپر مارکت بزرگ جنوب", "نمایشگاه سنگ و کاشی", "پمپ بنزین و خدمات خودرو"
        )

        for ((i, xVal) in shopRow4Xs.withIndex()) {
            buildings.add(
                Building3D(
                    id = "shop_r4_$i",
                    name = shopRow4Names[i % shopRow4Names.size],
                    minX = xVal, maxX = xVal + 48f,
                    minZ = 205f, maxZ = 235f,
                    height = 9.5f,
                    wallColorHex = 0xFFF8FAFC,
                    roofColorHex = 0xFF0F172A,
                    stories = 2,
                    penthouseCount = 0,
                    hasBalconies = false,
                    isCommercialShop = true,
                    signColorHex = shopColors[(i + 2) % shopColors.size]
                )
            )
        }

        // South Commercial Connecting Streets
        roads.add(RoadSegment("rd_s_str1", "راسته بازار ۱", -170f, 68f, +180f, 68f, 11f, isPrimary = false))
        roads.add(RoadSegment("rd_s_str2", "راسته بازار ۲", -170f, 126f, +180f, 126f, 11f, isPrimary = false))
        roads.add(RoadSegment("rd_s_str3", "راسته بازار ۳", -170f, 188f, +180f, 188f, 11f, isPrimary = false))
        roads.add(RoadSegment("rd_south_hwy", "بزرگراه جنوبی", -180f, 255f, +190f, 255f, 15f, isPrimary = true))

        // -------------------------------------------------------------
        // 4. VERTICAL CONNECTING SPINE STREETS
        // -------------------------------------------------------------
        roads.add(RoadSegment("rd_west_outer", "خيابان غربی", -170f, -242f, -170f, 255f, 12f, isPrimary = true))
        roads.add(RoadSegment("rd_center_cut", "خيابان مرکزی (صداقت)", -10f, -242f, -10f, 255f, 12f, isPrimary = true))
        roads.add(RoadSegment("rd_east_mid", "خيابان شرقی", +110f, -242f, +110f, 255f, 12f, isPrimary = true))
        roads.add(RoadSegment("rd_east_outer", "کنارگذر شرقی", +180f, -242f, +180f, 255f, 12f, isPrimary = true))

        // Dirt Trails through Western & Eastern Hills
        roads.add(RoadSegment("dirt_w1", "مسیر خاکی کوهستان غرب", -210f, -290f, -170f, -200f, 8f, isDirt = true))
        roads.add(RoadSegment("dirt_w2", "جاده خاکی غرب", -210f, -150f, -170f, 50f, 8f, isDirt = true))
        roads.add(RoadSegment("dirt_e1", "مسیر خاکی تپه‌های شرق", +180f, -240f, +215f, -280f, 8f, isDirt = true))
        roads.add(RoadSegment("dirt_e2", "جاده خاکی تپه‌های شرقی", +180f, 0f, +215f, 150f, 8f, isDirt = true))

        // -------------------------------------------------------------
        // 5. TERRAIN HILLS
        // -------------------------------------------------------------
        terrains.add(TerrainPatch(-220f, -165f, -300f, -230f, 0f, 22f, 0xFF856B4E)) // NW Mountain
        terrains.add(TerrainPatch(-220f, -175f, -230f, 50f, 0f, 18f, 0xFF9E8364))  // West Slopes
        terrains.add(TerrainPatch(-220f, -175f, 50f, 300f, 0f, 15f, 0xFF886E52))   // SW Slopes
        terrains.add(TerrainPatch(+160f, +220f, -300f, -220f, 0f, 20f, 0xFF967B5C)) // NE Hills
        terrains.add(TerrainPatch(+175f, +220f, -220f, 300f, 0f, 16f, 0xFFA08566)) // East Terraced Dirt

        // -------------------------------------------------------------
        // 6. TREES PLANTED IN NEIGHBORHOODS & STREETS
        // -------------------------------------------------------------
        val treeRowZs = floatArrayOf(-200f, -145f, -90f, -40f, 40f, 100f, 160f, 220f)
        var nbrCount = 0
        for (z in treeRowZs) {
            var x = -140f
            while (x <= 150f) {
                if (kotlin.math.abs(x - (-10f)) > 8f && kotlin.math.abs(x - 110f) > 8f) {
                    val sp = when (nbrCount % 4) {
                        0 -> TreeSpecies.DECIDUOUS_LUSH
                        1 -> TreeSpecies.AUTUMN_GOLDEN
                        2 -> TreeSpecies.PINE_CYPRESS
                        else -> TreeSpecies.DECIDUOUS_LUSH
                    }
                    trees.add(
                        TreeItem(
                            position = Vec3(x, 0f, z),
                            radius = 2.5f,
                            height = 5.2f,
                            species = sp,
                            hasPlanterBox = true
                        )
                    )
                    nbrCount++
                }
                x += 14f
            }
        }

        // -------------------------------------------------------------
        // 7. STREET LIGHTS ALONG STREETS
        // -------------------------------------------------------------
        val lightZs = floatArrayOf(-242f, -190f, -135f, -80f, -32f, 68f, 126f, 188f, 255f)
        val lightXs = floatArrayOf(-170f, -70f, -10f, +50f, +110f, +180f)

        for (z in lightZs) {
            for (x in lightXs) {
                streetLights.add(StreetLight(Vec3(x, 0f, z - 4f)))
            }
        }
    }
}

