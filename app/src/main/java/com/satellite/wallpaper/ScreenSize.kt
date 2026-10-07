package com.satellite.wallpaper

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager

/** Tamaño real de la pantalla en vertical (incluye barras del sistema): el mismo que usa el fondo animado. */
object ScreenSize {
    fun real(context: Context): Pair<Int, Int> {
        val wm = context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val w: Int
        val h: Int
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            w = b.width(); h = b.height()
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(m)
            w = m.widthPixels; h = m.heightPixels
        }
        return Pair(minOf(w, h), maxOf(w, h))
    }

    /** Relación ancho/alto en vertical. */
    fun aspect(context: Context): Float {
        val (w, h) = real(context)
        return w.toFloat() / h.toFloat()
    }
}
