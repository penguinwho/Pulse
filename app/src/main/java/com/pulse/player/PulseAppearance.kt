package com.pulse.player

import android.animation.TimeInterpolator
import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.Window
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.ColorUtils

/** Applies the selected colour preset without replacing the user's light/dark preference. */
object PulseAppearance {
    data class Palette(val accent: Int, val glow: Int, val glass: Int, val backdropStart: Int, val backdropEnd: Int)

    fun palette(context: Context): Palette {
        val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val accent = when (AppPreferences.accent(context)) {
            AppPreferences.OCEAN -> Color.rgb(57, 157, 221)
            AppPreferences.SUNSET -> Color.rgb(210, 80, 121)
            else -> Color.rgb(130, 82, 190)
        }
        val customGradient = AppPreferences.customGradient(context)?.let { (start, end) ->
            runCatching { Color.parseColor(start) to Color.parseColor(end) }.getOrNull()
        }
        if (!dark) {
            val end = when (AppPreferences.accent(context)) {
                AppPreferences.OCEAN -> Color.rgb(224, 247, 255)
                AppPreferences.SUNSET -> Color.rgb(255, 235, 242)
                else -> Color.rgb(244, 235, 255)
            }
            val start = customGradient?.first?.let { ColorUtils.blendARGB(Color.WHITE, it, 0.16f) } ?: Color.rgb(255, 251, 255)
            val finish = customGradient?.second?.let { ColorUtils.blendARGB(Color.WHITE, it, 0.18f) } ?: end
            return Palette(accent, accent, 0xBEFFFFFF.toInt(), start, finish)
        }
        val base = when (AppPreferences.accent(context)) {
            AppPreferences.OCEAN -> Palette(Color.rgb(91, 205, 255), Color.rgb(66, 150, 255), 0xB8294B66.toInt(), Color.rgb(19, 32, 47), Color.rgb(20, 66, 83))
            AppPreferences.SUNSET -> Palette(Color.rgb(255, 145, 166), Color.rgb(255, 105, 105), 0xB85B2948.toInt(), Color.rgb(49, 22, 39), Color.rgb(91, 42, 45))
            else -> Palette(Color.rgb(206, 159, 255), Color.rgb(141, 96, 223), 0xB63B2A50.toInt(), Color.rgb(30, 19, 44), Color.rgb(63, 35, 82))
        }
        return customGradient?.let { base.copy(backdropStart = it.first, backdropEnd = it.second) } ?: base
    }

    fun applyWindow(activity: Activity, palette: Palette = palette(activity)) {
        val light = ColorUtils.calculateLuminance(palette.backdropStart) > 0.5
        activity.window.statusBarColor = Color.TRANSPARENT
        activity.window.navigationBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        activity.window.decorView.systemUiVisibility = if (light) {
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        } else 0
    }

    fun applyGlass(
        context: Context,
        backdrop: View,
        glassViews: List<View> = emptyList(),
        accentText: List<TextView> = emptyList(),
        accentIcons: List<ImageView> = emptyList(),
    ) {
        val palette = palette(context)
        backdrop.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(palette.backdropStart, ColorUtils.blendARGB(palette.backdropStart, palette.backdropEnd, 0.55f), palette.backdropEnd)
        )
        glassViews.forEach { it.backgroundTintList = ColorStateList.valueOf(palette.glass) }
        accentText.forEach { it.setTextColor(palette.accent) }
        accentIcons.forEach { it.imageTintList = ColorStateList.valueOf(palette.accent) }
    }

    fun applyPlayerScrim(context: Context, scrim: View, accentButton: ImageView) {
        val palette = palette(context)
        scrim.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                ColorUtils.setAlphaComponent(Color.BLACK, 154),
                ColorUtils.setAlphaComponent(palette.glow, 72),
                ColorUtils.setAlphaComponent(Color.BLACK, 206)
            )
        )
        accentButton.backgroundTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(palette.accent, 70))
    }

    fun enter(view: View, distanceDp: Float = 18f) {
        val distance = distanceDp * view.resources.displayMetrics.density
        view.alpha = 0f
        view.translationY = distance
        view.animate().alpha(1f).translationY(0f).setDuration(320).setInterpolator(EMPHASIZED).start()
    }

    private val EMPHASIZED = TimeInterpolator { input ->
        val shifted = input - 1f
        1f + shifted * shifted * shifted
    }
}
