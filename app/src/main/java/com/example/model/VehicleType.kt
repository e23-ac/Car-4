package com.example.model

import androidx.compose.ui.graphics.Color

enum class VehicleType(
    val id: String,
    val nameFa: String,
    val nameEn: String,
    val descriptionFa: String,
    val maxSpeedKmh: Float,
    val accelerationForce: Float,
    val handlingRating: Float, // 0.0 to 1.0
    val driftMultiplier: Float, // Higher value = easier & wider drifting (e.g., 405)
    val tractionGrip: Float, // Higher value = sticky tires & stable cornering (e.g., Pars, Land Cruiser)
    val suspensionStiffness: Float, // Suspension firmness & roll resistance
    val turboBoostFactor: Float, // High RPM extra torque boost
    val specialTraitFa: String,
    val specialTraitEn: String,
    val width: Float,
    val length: Float,
    val height: Float,
    val weightKg: Int,
    val defaultColorHex: Long
) {
    PRIDE_131(
        id = "pride_131",
        nameFa = "پراید ۱۳۱",
        nameEn = "Saipa Pride 131",
        descriptionFa = "خودروی شهری چابک با سبک وزن، مصرف سوخت کم و مانورپذیری بالا در ترافیک شهر",
        maxSpeedKmh = 160f,
        accelerationForce = 48f,
        handlingRating = 0.85f,
        driftMultiplier = 1.0f,
        tractionGrip = 0.95f,
        suspensionStiffness = 0.85f,
        turboBoostFactor = 1.0f,
        specialTraitFa = "چابک و سبک‌وزن شهری",
        specialTraitEn = "Lightweight Urban Agility",
        width = 1.76f,
        length = 3.90f,
        height = 1.40f,
        weightKg = 910,
        defaultColorHex = 0xFFF1F5F9 // White
    ),
    PEUGEOT_405(
        id = "peugeot_405",
        nameFa = "پژو ۴۰۵",
        nameEn = "Peugeot 405 GLX",
        descriptionFa = "سدان کلاسیک و پرقدرت با بدنه آیرودینامیک، پایداری بالا در سرعت‌های بالا و تخصص عالی در دریفت",
        maxSpeedKmh = 185f,
        accelerationForce = 58f,
        handlingRating = 0.82f,
        driftMultiplier = 1.55f, // Special Drift Handling!
        tractionGrip = 0.80f,
        suspensionStiffness = 0.90f,
        turboBoostFactor = 1.08f,
        specialTraitFa = "فرمان‌دهی تخصصی دریفت و سرخوردن",
        specialTraitEn = "Rear Drift & Slide Tuning",
        width = 1.88f,
        length = 4.40f,
        height = 1.42f,
        weightKg = 1120,
        defaultColorHex = 0xFF94A3B8 // Silver Grey
    ),
    PEUGEOT_PARS(
        id = "peugeot_pars",
        nameFa = "پژو پارس (پرشیا)",
        nameEn = "Peugeot Pars ELX",
        descriptionFa = "سدان اسپرت و محبوب با ظاهر تهاجمی، چسبندگی عالی لاستیک‌ها و هندلینگ دقیق در پیچ‌ها",
        maxSpeedKmh = 205f,
        accelerationForce = 68f,
        handlingRating = 0.92f,
        driftMultiplier = 1.10f,
        tractionGrip = 1.25f, // Sport Traction
        suspensionStiffness = 1.15f,
        turboBoostFactor = 1.12f,
        specialTraitFa = "چسبندگی اسپرت و پایداری پیچ‌ها",
        specialTraitEn = "High-G Precision Grip",
        width = 1.90f,
        length = 4.48f,
        height = 1.42f,
        weightKg = 1180,
        defaultColorHex = 0xFFF8FAFC // Crystal White
    ),
    DENA_PLUS(
        id = "dena_plus",
        nameFa = "دنا پلاس توربو",
        nameEn = "Dena Plus Turbo",
        descriptionFa = "سدان لوکس و مدرن ایرانی با بوست قدرتمند توربوشارژر، شتاب ثانویه فوق‌العاده و سیستم پایداری",
        maxSpeedKmh = 222f,
        accelerationForce = 78f,
        handlingRating = 0.88f,
        driftMultiplier = 1.05f,
        tractionGrip = 1.15f,
        suspensionStiffness = 1.10f,
        turboBoostFactor = 1.38f, // High Turbo Boost!
        specialTraitFa = "بوست توربو و شتاب ثانویه بالا",
        specialTraitEn = "Turbocharged Power Surge",
        width = 1.94f,
        length = 4.56f,
        height = 1.45f,
        weightKg = 1260,
        defaultColorHex = 0xFF334155 // Charcoal Slate
    ),
    TOYOTA_LAND_CRUISER(
        id = "land_cruiser",
        nameFa = "تویوتا لندکروز V8",
        nameEn = "Toyota Land Cruiser",
        descriptionFa = "شاسی‌بلند قدرتمند V8 با جرم سنگین، سیستم تعلیق جذب ضربه، پایداری شاهانه و قدرت صخره‌نوردی",
        maxSpeedKmh = 228f,
        accelerationForce = 88f,
        handlingRating = 0.78f,
        driftMultiplier = 0.65f, // Ultra Stable Heavy Weight
        tractionGrip = 1.45f, // Heavy All-Wheel Stability
        suspensionStiffness = 1.40f,
        turboBoostFactor = 1.15f,
        specialTraitFa = "پایداری V8 سنگین و جذب ضربه عالی",
        specialTraitEn = "Heavy V8 Off-Road Stability",
        width = 2.15f,
        length = 4.95f,
        height = 1.92f,
        weightKg = 2600,
        defaultColorHex = 0xFFFFFFFF // Pearl White V8
    ),
    TOYOTA_HILUX(
        id = "hilux",
        nameFa = "تویوتا هایلوکس 4x4",
        nameEn = "Toyota Hilux Pickup",
        descriptionFa = "پیکاپ جان‌سخت دوکابین با گشتاور بالای دنده سنگین، شاسی مقاوم و قدرت عبور از تمام موانع",
        maxSpeedKmh = 198f,
        accelerationForce = 84f,
        handlingRating = 0.80f,
        driftMultiplier = 0.85f,
        tractionGrip = 1.35f,
        suspensionStiffness = 1.35f,
        turboBoostFactor = 1.20f,
        specialTraitFa = "گشتاور سنگین 4x4 و شاسی جان‌سخت",
        specialTraitEn = "Heavy Duty 4x4 Torque",
        width = 2.10f,
        length = 5.30f,
        height = 1.85f,
        weightKg = 2100,
        defaultColorHex = 0xFFCBD5E1 // Silver Metallic
    );

    val displayNameFa: String get() = nameFa
    val displayNameEn: String get() = nameEn
    val availableColorsHex: List<Long> get() = listOf(
        0xFFF8FAFC, // Crystal White
        0xFF94A3B8, // Silver Metallic
        0xFF0F172A, // Onyx Black
        0xFFDC2626, // Racing Red
        0xFF1E3A8A, // Imperial Blue
        0xFF15803D, // Emerald Green
        0xFFEAB308  // Sunset Yellow
    )
}

enum class RimStyle(val displayNameFa: String, val displayNameEn: String, val rimColorHex: Long) {
    STOCK("رینگ فابریک", "Stock Factory", 0xFFCBD5E1),
    DIAMOND_CUT("رینگ تراش الماس", "Diamond Cut", 0xFFE2E8F0),
    SPORT_DARK("رینگ اسپرت مشکی", "Sport Matte Black", 0xFF1E293B),
    GOLD_MESH("رینگ طلایی BBS", "Gold Mesh BBS", 0xFFEAB308)
}

enum class DecalStyle(val displayNameFa: String, val displayNameEn: String) {
    NONE("ساده فابریک", "Clean OEM"),
    RACING_STRIPE("خط‌کشی مسابقه‌ای", "Racing Stripe"),
    TURBO_BADGE("طرح گرافیکی توربو", "Turbo Graphic")
}

enum class SpoilerStyle(val displayNameFa: String, val displayNameEn: String) {
    DEFAULT("فابریک بدنه", "Factory Body"),
    SPORT_LIP("اسپویلر لبه‌ای", "Sport Lip Spoiler"),
    GT_WING("باله GT کربن", "GT Carbon Wing")
}

enum class TintLevel(val displayNameFa: String, val alpha: Float) {
    CLEAR("شیشه شفاف", 0.85f),
    MEDIUM_TINT("دودی ۳۰٪", 0.92f),
    DARK_VIP("دودی ۷۰٪ VIP", 0.98f)
}

val VEHICLE_COLOR_PALETTE = listOf(
    Color(0xFFF8FAFC), // White
    Color(0xFF94A3B8), // Silver Metallic
    Color(0xFF0F172A), // Onyx Black
    Color(0xFFDC2626), // Sport Red
    Color(0xFF1E3A8A), // Deep Imperial Blue
    Color(0xFF475569)  // Dark Charcoal
)
