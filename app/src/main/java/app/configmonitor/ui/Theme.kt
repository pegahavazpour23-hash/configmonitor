package app.configmonitor.ui

import android.content.Context
import android.graphics.Typeface
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.configmonitor.util.Format

/** Colors with a fixed meaning (healthy / slow / down) that Material's scheme has no slot for. */
@Immutable
class StatusColors(
    val up: Color,
    val slow: Color,
    val down: Color,
    val downText: Color,
    val downContainer: Color,
    val downBorder: Color,
)

private val DarkStatus = StatusColors(
    up = Color(0xFF2DD4A0),
    slow = Color(0xFFF5B544),
    down = Color(0xFFFF5C6A),
    downText = Color(0xFFFF8A94),
    downContainer = Color(0xFF1F1216),
    downBorder = Color(0xFF3A1A20),
)

private val LightStatus = StatusColors(
    up = Color(0xFF0B8F6B),
    slow = Color(0xFFB7791F),
    down = Color(0xFFD93A4A),
    downText = Color(0xFFB4232F),
    downContainer = Color(0xFFFDEEF0),
    downBorder = Color(0xFFF5C6CC),
)

val LocalStatusColors = staticCompositionLocalOf { DarkStatus }
val LocalMono = staticCompositionLocalOf<FontFamily> { FontFamily.Monospace }
val LocalReduceMotion = staticCompositionLocalOf { false }

private val DarkColors = darkColorScheme(
    primary = Color(0xFF2DD4A0),
    onPrimary = Color(0xFF04130E),
    primaryContainer = Color(0xFF123D31),
    onPrimaryContainer = Color(0xFFB8F3DF),
    background = Color(0xFF0E1116),
    onBackground = Color(0xFFE6EAF0),
    surface = Color(0xFF161B22),
    onSurface = Color(0xFFE6EAF0),
    surfaceVariant = Color(0xFF1C222B),
    onSurfaceVariant = Color(0xFF8B95A5),
    outline = Color(0xFF232A34),
    outlineVariant = Color(0xFF2B3340),
    error = Color(0xFFFF5C6A),
    onError = Color(0xFF14090B),
    errorContainer = Color(0xFF3A1A20),
    onErrorContainer = Color(0xFFFF8A94),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0B8F6B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD3F5E9),
    onPrimaryContainer = Color(0xFF05503C),
    background = Color(0xFFF5F7FA),
    onBackground = Color(0xFF0E1116),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0E1116),
    surfaceVariant = Color(0xFFEEF1F5),
    onSurfaceVariant = Color(0xFF5B6676),
    outline = Color(0xFFDDE3EC),
    outlineVariant = Color(0xFFC9D1DD),
    error = Color(0xFFD93A4A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFDE4E7),
    onErrorContainer = Color(0xFFA3202D),
)

/** One FontFamily per weight, because a platform Typeface carries its own weight. */
class FontSet(
    val w400: FontFamily,
    val w500: FontFamily,
    val w600: FontFamily,
    val w700: FontFamily,
    val mono: FontFamily,
    val loaded: Boolean,
)

/**
 * Loads Vazirmatn (Persian UI) or Inter (English UI) plus JetBrains Mono from assets/fonts.
 * The files are fetched by scripts/fetch-assets.sh. If they are missing the system font is used.
 */
object AppFonts {
    fun load(ctx: Context, persian: Boolean): FontSet {
        val names: Set<String> = try {
            ctx.assets.list("fonts")?.toSet() ?: emptySet()
        } catch (e: Exception) {
            emptySet()
        }
        val candidates = if (persian) listOf("Vazirmatn.ttf", "Inter.ttf") else listOf("Inter.ttf", "Vazirmatn.ttf")
        val file: String? = candidates.firstOrNull { it in names }
        val monoFile: String? = if ("JetBrainsMono.ttf" in names) "JetBrainsMono.ttf" else null

        fun family(f: String?, weight: Int): FontFamily? {
            if (f == null) return null
            return try {
                val tf = Typeface.Builder(ctx.assets, "fonts/$f")
                    .setFontVariationSettings("'wght' $weight")
                    .build()
                if (tf == null) null else FontFamily(tf)
            } catch (e: Throwable) {
                null
            }
        }

        val f400 = family(file, 400)
        val fallback = FontFamily.Default
        return FontSet(
            w400 = f400 ?: fallback,
            w500 = family(file, 500) ?: fallback,
            w600 = family(file, 600) ?: fallback,
            w700 = family(file, 700) ?: fallback,
            mono = family(monoFile, 400) ?: FontFamily.Monospace,
            loaded = f400 != null,
        )
    }
}

private fun Typography.withFonts(f: FontSet): Typography {
    // with real font files the weight is baked into the family; with the system fallback keep a normal weight scale
    fun TextStyle.fit(family: FontFamily, weight: FontWeight): TextStyle =
        copy(fontFamily = family, fontWeight = if (f.loaded) FontWeight.Normal else weight)

    return copy(
        displayLarge = displayLarge.fit(f.w700, FontWeight.Bold),
        displayMedium = displayMedium.fit(f.w700, FontWeight.Bold),
        displaySmall = displaySmall.fit(f.w700, FontWeight.Bold),
        headlineLarge = headlineLarge.fit(f.w700, FontWeight.Bold),
        headlineMedium = headlineMedium.fit(f.w700, FontWeight.Bold),
        headlineSmall = headlineSmall.fit(f.w700, FontWeight.Bold),
        titleLarge = titleLarge.fit(f.w600, FontWeight.SemiBold),
        titleMedium = titleMedium.fit(f.w600, FontWeight.SemiBold),
        titleSmall = titleSmall.fit(f.w600, FontWeight.SemiBold),
        bodyLarge = bodyLarge.fit(f.w400, FontWeight.Normal),
        bodyMedium = bodyMedium.fit(f.w400, FontWeight.Normal),
        bodySmall = bodySmall.fit(f.w400, FontWeight.Normal),
        labelLarge = labelLarge.fit(f.w500, FontWeight.Medium),
        labelMedium = labelMedium.fit(f.w500, FontWeight.Medium),
        labelSmall = labelSmall.fit(f.w500, FontWeight.Medium),
    )
}

@Composable
fun AppTheme(darkTheme: Boolean, reduceMotion: Boolean = false, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val persian = Format.isFa(ctx)
    val fonts = remember(persian) { AppFonts.load(ctx.applicationContext, persian) }
    val typography = remember(fonts) { Typography().withFonts(fonts) }
    CompositionLocalProvider(
        LocalStatusColors provides (if (darkTheme) DarkStatus else LightStatus),
        LocalMono provides fonts.mono,
        LocalReduceMotion provides reduceMotion,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = typography,
            shapes = Shapes(
                small = RoundedCornerShape(10.dp),
                medium = RoundedCornerShape(14.dp),
                large = RoundedCornerShape(20.dp),
            ),
            content = content,
        )
    }
}
