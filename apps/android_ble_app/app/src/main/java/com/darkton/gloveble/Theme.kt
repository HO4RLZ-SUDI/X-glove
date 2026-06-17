package com.darkton.gloveble

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------
// User theme preferences
// ---------------------------------------------------------------------------

/** Light / dark / follow-system choice. */
enum class ThemeMode(val label: String) {
    SYSTEM("ตามระบบ"),
    LIGHT("สว่าง"),
    DARK("มืด")
}

/**
 * A fixed accent palette. `seed` is the swatch shown in the picker; the full
 * light/dark schemes are built in [accentScheme]. Dynamic (Material You) color
 * is handled separately and only on Android 12+.
 */
enum class AccentTheme(val label: String, val seed: Color) {
    TEAL("เทอร์ควอยซ์", Color(0xFF14B8A6)),
    VIOLET("ม่วง", Color(0xFF8B5CF6)),
    SUNSET("ส้มอาทิตย์", Color(0xFFF97316)),
    ROSE("ชมพู", Color(0xFFF43F5E)),
    OCEAN("น้ำเงิน", Color(0xFF3B82F6)),
    FOREST("เขียวป่า", Color(0xFF22C55E))
}

/**
 * Holds the user's theme choices as Compose state and persists them. Created
 * once in [MainActivity] and read by [GloveBleTheme]; the Settings screen
 * mutates it through the setters so changes apply and survive restarts.
 */
class ThemeController(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("glove_theme", Context.MODE_PRIVATE)

    val dynamicColorSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    var mode by mutableStateOf(
        runCatching { ThemeMode.valueOf(prefs.getString(KEY_MODE, ThemeMode.SYSTEM.name)!!) }
            .getOrDefault(ThemeMode.SYSTEM)
    )
        private set

    var accent by mutableStateOf(
        runCatching { AccentTheme.valueOf(prefs.getString(KEY_ACCENT, AccentTheme.TEAL.name)!!) }
            .getOrDefault(AccentTheme.TEAL)
    )
        private set

    // Default dynamic color ON where supported so the app matches the user's wallpaper.
    var dynamicColor by mutableStateOf(
        dynamicColorSupported && prefs.getBoolean(KEY_DYNAMIC, true)
    )
        private set

    fun selectMode(next: ThemeMode) {
        mode = next
        prefs.edit().putString(KEY_MODE, next.name).apply()
    }

    fun selectAccent(next: AccentTheme) {
        accent = next
        prefs.edit().putString(KEY_ACCENT, next.name).apply()
    }

    fun selectDynamicColor(enabled: Boolean) {
        if (!dynamicColorSupported) return
        dynamicColor = enabled
        prefs.edit().putBoolean(KEY_DYNAMIC, enabled).apply()
    }

    private companion object {
        const val KEY_MODE = "mode"
        const val KEY_ACCENT = "accent"
        const val KEY_DYNAMIC = "dynamic"
    }
}

// ---------------------------------------------------------------------------
// Neutral surfaces shared by every accent (keeps the app feeling consistent)
// ---------------------------------------------------------------------------

private val DarkNeutrals = darkColorScheme(
    background = Color(0xFF0B1014),
    onBackground = Color(0xFFE2E8F0),
    surface = Color(0xFF121A21),
    onSurface = Color(0xFFE2E8F0),
    surfaceVariant = Color(0xFF1C2730),
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF3A4A58),
    outlineVariant = Color(0xFF24303B),
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A)
)

private val LightNeutrals = lightColorScheme(
    background = Color(0xFFF3F6F8),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE6ECF0),
    onSurfaceVariant = Color(0xFF55606B),
    outline = Color(0xFF8A99A8),
    outlineVariant = Color(0xFFD3DDE4),
    error = Color(0xFFDC2626),
    onError = Color(0xFFFFFFFF)
)

/** Accent hues: primary / secondary / tertiary for dark and light variants. */
private data class AccentColors(
    val darkPrimary: Color,
    val darkPrimaryContainer: Color,
    val darkOnPrimaryContainer: Color,
    val darkSecondary: Color,
    val darkTertiary: Color,
    val lightPrimary: Color,
    val lightPrimaryContainer: Color,
    val lightOnPrimaryContainer: Color,
    val lightSecondary: Color,
    val lightTertiary: Color
)

private fun accentColors(accent: AccentTheme): AccentColors = when (accent) {
    AccentTheme.TEAL -> AccentColors(
        Color(0xFF4FD1C5), Color(0xFF0F4C45), Color(0xFFB2F5EA), Color(0xFFFBBF24), Color(0xFF93C5FD),
        Color(0xFF0F766E), Color(0xFFCCFBF1), Color(0xFF073D38), Color(0xFFB45309), Color(0xFF2563EB)
    )
    AccentTheme.VIOLET -> AccentColors(
        Color(0xFFC4B5FD), Color(0xFF4C1D95), Color(0xFFEDE9FE), Color(0xFFF0ABFC), Color(0xFF7DD3FC),
        Color(0xFF6D28D9), Color(0xFFEDE9FE), Color(0xFF3B0764), Color(0xFFA21CAF), Color(0xFF0369A1)
    )
    AccentTheme.SUNSET -> AccentColors(
        Color(0xFFFDBA74), Color(0xFF7C2D12), Color(0xFFFFEDD5), Color(0xFFFCD34D), Color(0xFFFCA5A5),
        Color(0xFFC2410C), Color(0xFFFFEDD5), Color(0xFF431407), Color(0xFFB45309), Color(0xFFDC2626)
    )
    AccentTheme.ROSE -> AccentColors(
        Color(0xFFFDA4AF), Color(0xFF881337), Color(0xFFFFE4E6), Color(0xFFF9A8D4), Color(0xFFA5B4FC),
        Color(0xFFBE123C), Color(0xFFFFE4E6), Color(0xFF4C0519), Color(0xFFBE185D), Color(0xFF4F46E5)
    )
    AccentTheme.OCEAN -> AccentColors(
        Color(0xFF93C5FD), Color(0xFF1E3A8A), Color(0xFFDBEAFE), Color(0xFF67E8F9), Color(0xFFA5B4FC),
        Color(0xFF1D4ED8), Color(0xFFDBEAFE), Color(0xFF172554), Color(0xFF0891B2), Color(0xFF4F46E5)
    )
    AccentTheme.FOREST -> AccentColors(
        Color(0xFF86EFAC), Color(0xFF14532D), Color(0xFFDCFCE7), Color(0xFFFDE047), Color(0xFF7DD3FC),
        Color(0xFF15803D), Color(0xFFDCFCE7), Color(0xFF052E16), Color(0xFFCA8A04), Color(0xFF0284C7)
    )
}

private fun darkSchemeFor(accent: AccentTheme): ColorScheme {
    val c = accentColors(accent)
    return DarkNeutrals.copy(
        primary = c.darkPrimary,
        onPrimary = Color(0xFF06201D),
        primaryContainer = c.darkPrimaryContainer,
        onPrimaryContainer = c.darkOnPrimaryContainer,
        secondary = c.darkSecondary,
        onSecondary = Color(0xFF2B2300),
        secondaryContainer = c.darkSecondary.copy(alpha = 0.22f),
        onSecondaryContainer = c.darkSecondary,
        tertiary = c.darkTertiary,
        onTertiary = Color(0xFF0B2D5B)
    )
}

private fun lightSchemeFor(accent: AccentTheme): ColorScheme {
    val c = accentColors(accent)
    return LightNeutrals.copy(
        primary = c.lightPrimary,
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = c.lightPrimaryContainer,
        onPrimaryContainer = c.lightOnPrimaryContainer,
        secondary = c.lightSecondary,
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = c.lightPrimaryContainer,
        onSecondaryContainer = c.lightOnPrimaryContainer,
        tertiary = c.lightTertiary,
        onTertiary = Color(0xFFFFFFFF)
    )
}

private val GloveShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(26.dp)
)

@Composable
fun GloveBleTheme(
    controller: ThemeController,
    content: @Composable () -> Unit
) {
    val dark = when (controller.mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val context = LocalContext.current
    val colorScheme = when {
        controller.dynamicColor && controller.dynamicColorSupported ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkSchemeFor(controller.accent)
        else -> lightSchemeFor(controller.accent)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = GloveShapes,
        typography = MaterialTheme.typography,
        content = content
    )
}
