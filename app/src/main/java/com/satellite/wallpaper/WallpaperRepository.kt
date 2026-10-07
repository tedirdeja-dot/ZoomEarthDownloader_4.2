package com.satellite.wallpaper

import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object WallpaperRepository {

    private const val TAG = "WallpaperRepository"
    private const val CACHE_NAME = "satellite_cache.jpg"
    private const val PREFS = "satellite_wallpaper"
    private const val KEY_ZOOM = "zoom"
    private const val KEY_OFFSET_X = "offset_x"
    private const val KEY_OFFSET_Y = "offset_y"
    private const val KEY_UPDATED_AT = "updated_at"
    private const val KEY_VIEW_URL = "view_url"
    private const val KEY_LOCK_STATIC = "lock_static"
    private const val KEY_LOG = "attempt_log"
    private const val KEY_MIN_INTERVAL = "min_interval_min"

    /** Opciones del tiempo mínimo entre dos actualizaciones, en minutos. */
    val INTERVAL_OPTIONS_MIN = intArrayOf(15, 30, 60, 120, 240)
    const val DEFAULT_INTERVAL_MIN = 60

    /** Si una captura no termina en este tiempo se libera el bloqueo para no quedar atascados. */
    private const val WATCHDOG_MS = 300_000L

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    @Volatile private var runToken = 0L

    /** Motivo del último fallo de captura (null si la última fue correcta). */
    @Volatile
    var lastError: String? = null
        private set

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, 0)

    fun cacheFile(context: Context): File = File(context.filesDir, CACHE_NAME)

    // ---------- Selección de vista (recordada) ----------

    /** true si el usuario ya eligió una vista de Zoom Earth. */
    fun hasSelection(context: Context): Boolean = prefs(context).contains(KEY_VIEW_URL)

    /** URL de Zoom Earth elegida por el usuario (o la de por defecto). */
    fun viewUrl(context: Context): String =
        prefs(context).getString(KEY_VIEW_URL, null) ?: ZoomEarthCapture.PAGE_URL

    /** Guarda la vista elegida. Si cambia, invalida la imagen en caché y el encuadre. */
    fun saveViewUrl(context: Context, url: String) {
        val p = prefs(context)
        val changed = p.getString(KEY_VIEW_URL, null) != url
        val e = p.edit().putString(KEY_VIEW_URL, url)
        if (changed) {
            e.remove(KEY_UPDATED_AT).remove(KEY_ZOOM).remove(KEY_OFFSET_X).remove(KEY_OFFSET_Y)
            runCatching { cacheFile(context).delete() }
        }
        e.apply()
    }

    fun lockEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_LOCK_STATIC, true)

    fun setLockEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_LOCK_STATIC, value).apply()
    }

    // ---------- Tiempo mínimo entre actualizaciones ----------

    /** true si el usuario ya eligió el tiempo mínimo (primer inicio = false). */
    fun hasMinInterval(context: Context): Boolean = prefs(context).contains(KEY_MIN_INTERVAL)

    fun minIntervalMin(context: Context): Int =
        prefs(context).getInt(KEY_MIN_INTERVAL, DEFAULT_INTERVAL_MIN)

    fun setMinIntervalMin(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_MIN_INTERVAL, minutes).apply()
    }

    fun intervalLabel(minutes: Int): String = when {
        minutes < 60 -> "$minutes min"
        minutes == 60 -> "1 hora"
        minutes % 60 == 0 -> "${minutes / 60} horas"
        else -> "$minutes min"
    }

    // ---------- Caché / encuadre ----------

    fun updatedAt(context: Context): Long {
        val v = prefs(context).getLong(KEY_UPDATED_AT, 0L)
        return if (v > 0) v else cacheFile(context).lastModified()
    }

    fun loadCached(context: Context): Bitmap? {
        val f = cacheFile(context).takeIf { it.exists() } ?: return null
        val bmp = runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()
            ?.takeIf { it.width > 0 && it.height > 0 }
        if (bmp != null) return bmp
        // Solo se borra si el archivo está realmente corrupto. Un fallo de decodificación por
        // memoria u otra causa transitoria NO debe destruir la última imagen buena.
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeFile(f.absolutePath, o) }
        if (o.outWidth <= 0 || o.outHeight <= 0) runCatching { f.delete() }
        return null
    }

    /** true si el sistema indica una red con acceso a Internet validado. */
    fun isOnline(context: Context): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps != null &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } catch (t: Throwable) {
        true // si no se puede comprobar, se intenta igualmente
    }

    fun loadState(context: Context): CropState {
        val p = prefs(context)
        return CropState(
            p.getFloat(KEY_ZOOM, 1f),
            p.getFloat(KEY_OFFSET_X, 0f),
            p.getFloat(KEY_OFFSET_Y, 0f)
        ).clamped()
    }

    fun saveState(context: Context, state: CropState) {
        val s = state.clamped()
        prefs(context).edit()
            .putFloat(KEY_ZOOM, s.zoom)
            .putFloat(KEY_OFFSET_X, s.offsetX)
            .putFloat(KEY_OFFSET_Y, s.offsetY)
            .apply()
    }

    // ---------- Registro de intentos (diagnóstico) ----------

    @Synchronized
    fun note(context: Context, source: String, text: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val old = prefs(context).getString(KEY_LOG, "")!!.split("\n").filter { it.isNotBlank() }
        val log = (old + "$time · $source · $text").takeLast(10).joinToString("\n")
        prefs(context).edit().putString(KEY_LOG, log).apply()
        Log.i(TAG, "$source: $text")
    }

    /** Últimos intentos de descarga (más reciente al final). */
    fun attemptLog(context: Context): String = prefs(context).getString(KEY_LOG, "")!!

    // ---------- Descarga ----------

    /**
     * Captura la vista elegida de Zoom Earth y actualiza la caché (y la pantalla de bloqueo).
     * Misma estructura que la APK original: refresh() hace el trabajo, al terminar avisa con el
     * broadcast CACHE_UPDATED y el fondo animado vuelve a pintarse.
     * force = false: no hace nada si la imagen tiene menos de [minAgeMs] (por defecto, el tiempo mínimo elegido).
     * host != null: captura con el WebView visible dentro de ese contenedor (modo fiable).
     * host == null: captura en segundo plano (cascada de estrategias).
     * callback(true) solo si se generó una imagen nueva; se invoca en el hilo principal.
     */
    fun refresh(
        context: Context,
        force: Boolean = false,
        host: ViewGroup? = null,
        minAgeMs: Long? = null,
        source: String = "manual",
        callback: (Boolean) -> Unit = {}
    ) {
        val app = context.applicationContext
        main.post {
            // Por defecto, el tiempo mínimo elegido por el usuario; solo lo ignoran force=true (manual/prueba).
            val minAge = minAgeMs ?: (minIntervalMin(app) * 60_000L)
            val ageMs = System.currentTimeMillis() - updatedAt(app)
            val fresh = cacheFile(app).exists() && ageMs < minAge
            // Se disparan muy a menudo: solo se registra lo que hace algo.
            val noisy = source == "visibilidad" || source == "pantalla encendida"
            if (!force && fresh) {
                if (!noisy) note(app, source, "omitido: imagen de hace ${ageMs / 60_000} min (mínimo ${minAge / 60_000} min)")
                callback(false); return@post
            }
            if (!isOnline(app)) {
                if (!noisy) note(app, source, "omitido: sin conexión; se mantiene la imagen actual")
                callback(false); return@post
            }
            if (!running.compareAndSet(false, true)) {
                if (!noisy) note(app, source, "omitido: ya hay una descarga en curso")
                callback(false); return@post
            }
            val token = System.nanoTime()
            runToken = token
            note(app, source, "iniciando captura (${if (host != null) "WebView visible" else "segundo plano"})")

            // Red de seguridad: si la captura no termina nunca, se libera el bloqueo.
            main.postDelayed({
                if (runToken == token) {
                    runToken = 0L
                    running.set(false)
                    note(app, source, "FALLO: la captura no terminó en ${WATCHDOG_MS / 1000} s; se libera el bloqueo")
                    callback(false)
                }
            }, WATCHDOG_MS)

            val tmp = File(app.filesDir, "$CACHE_NAME.download")
            runCatching { tmp.delete() } // nunca reutilizar restos de un intento anterior
            val done: (String?) -> Unit = { captureError ->
                executor.execute {
                    // Si la red se perdió durante la captura, la imagen puede estar incompleta: se descarta.
                    val error = captureError
                        ?: if (!isOnline(app)) "se perdió la conexión durante la captura" else null
                    val ok = error == null && commit(app, tmp)
                    if (!ok) runCatching { tmp.delete() }
                    main.post {
                        if (runToken != token) return@post // el vigilante ya liberó esta captura
                        runToken = 0L
                        running.set(false)
                        lastError = if (ok) null else (error ?: "la imagen capturada no es válida")
                        note(
                            app, source,
                            if (ok) "OK, imagen actualizada [${ZoomEarthCapture.lastMode}]"
                            else "FALLO: $lastError (se mantiene la imagen anterior)"
                        )
                        callback(ok)
                        if (ok) {
                            // Primero se repinta el fondo animado (como la APK original); la pantalla de bloqueo estática después.
                            app.sendBroadcast(
                                Intent(SatelliteWallpaperService.ACTION_CACHE_UPDATED)
                                    .setPackage(app.packageName)
                            )
                            if (lockEnabled(app)) executor.execute { applyLockScreen(app) }
                        }
                    }
                }
            }
            try {
                if (host != null) ZoomEarthCapture.capture(app, tmp, host, viewUrl(app)) { done(it) }
                else ZoomEarthCapture.captureBackground(app, tmp, viewUrl(app)) { done(it) }
            } catch (t: Throwable) {
                done("excepción al iniciar la captura: ${t.javaClass.simpleName} ${t.message}")
            }
        }
    }

    /**
     * Pinta la imagen encuadrada (con fecha y hora) como fondo ESTÁTICO de la pantalla de bloqueo.
     * Garantiza que el bloqueo se actualice aunque el launcher no muestre el fondo animado ahí.
     */
    fun applyLockScreen(context: Context) {
        if (Build.VERSION.SDK_INT < 24) return
        val app = context.applicationContext
        val src = loadCached(app) ?: return
        val (sw, sh) = ScreenSize.real(app)
        val out = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        try {
            val c = Canvas(out)
            c.drawColor(0xFF10212B.toInt())
            CropMath.draw(c, src, out.width, out.height, loadState(app))
            CropMath.drawUpdatedAt(c, updatedAt(app), out.width, out.height)
            WallpaperManager.getInstance(app)
                .setBitmap(out, null, true, WallpaperManager.FLAG_LOCK)
        } catch (t: Throwable) {
            Log.w(TAG, "applyLockScreen", t)
        } finally {
            src.recycle()
            out.recycle()
        }
    }

    fun applyLockScreenAsync(context: Context) {
        val app = context.applicationContext
        executor.execute { applyLockScreen(app) }
    }

    private fun commit(app: Context, tmp: File): Boolean = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(tmp.absolutePath, o)
        check(o.outWidth > 0 && o.outHeight > 0) { "La imagen capturada no tiene un tamaño válido" }
        val dest = cacheFile(app)
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
        prefs(app).edit().putLong(KEY_UPDATED_AT, System.currentTimeMillis()).apply()
        true
    } catch (t: Throwable) {
        runCatching { tmp.delete() }
        false
    }
}
