package com.satellite.wallpaper

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.app.AlertDialog
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var preview: CropPreviewView
    private lateinit var button: Button
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var previewContainer: android.view.ViewGroup
    private lateinit var changeView: Button
    private lateinit var grantOverlay: Button
    private lateinit var lockStatic: CheckBox
    private var leavingActivity = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applySafeBottomInset()

        preview = findViewById(R.id.crop_preview)
        button = findViewById(R.id.set_wallpaper)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)
        previewContainer = findViewById(R.id.preview_container)
        status.setOnClickListener { startDownload() }

        preview.onStateChanged = { WallpaperRepository.saveState(this, it) }
        button.setOnClickListener { openConfirm() }

        changeView = findViewById(R.id.change_view)
        grantOverlay = findViewById(R.id.grant_overlay)
        lockStatic = findViewById(R.id.lock_static)

        changeView.setOnClickListener { openPicker() }
        grantOverlay.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        findViewById<Button>(R.id.grant_battery).setOnClickListener {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                )
            }
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2001)
        }
        findViewById<Button>(R.id.test_bg).setOnClickListener {
            android.widget.Toast.makeText(this, R.string.test_bg_started, android.widget.Toast.LENGTH_LONG).show()
            WallpaperRepository.refresh(this, force = true, host = null, source = "prueba")
        }
        UnlockService.start(this) // mantiene la app viva y escucha el desbloqueo aunque esté cerrada
        lockStatic.isChecked = WallpaperRepository.lockEnabled(this)
        lockStatic.setOnCheckedChangeListener { _, checked ->
            WallpaperRepository.setLockEnabled(this, checked)
            if (checked) WallpaperRepository.applyLockScreenAsync(this)
        }

        setupIntervalSpinner()

        if (!WallpaperRepository.hasMinInterval(this)) {
            // Primer inicio: primero se pide el tiempo mínimo entre actualizaciones.
            askIntervalFirstTime { startFlow() }
        } else {
            startFlow()
        }
    }

    private fun startFlow() {
        if (!isActivityUsable()) return
        if (!WallpaperRepository.hasSelection(this)) {
            // Primer inicio: el usuario elige qué vista de Zoom Earth quiere.
            openPicker()
        } else {
            loadCachedThenRefresh()
        }
    }

    private fun intervalAdapter(): ArrayAdapter<String> =
        ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            WallpaperRepository.INTERVAL_OPTIONS_MIN.map { WallpaperRepository.intervalLabel(it) }
        )

    private fun intervalIndex(minutes: Int): Int =
        WallpaperRepository.INTERVAL_OPTIONS_MIN.indexOf(minutes).takeIf { it >= 0 }
            ?: WallpaperRepository.INTERVAL_OPTIONS_MIN.indexOf(WallpaperRepository.DEFAULT_INTERVAL_MIN)

    /** Menú desplegable permanente en la pantalla principal para cambiar el tiempo mínimo. */
    private fun setupIntervalSpinner() {
        val spinner = findViewById<Spinner>(R.id.interval_spinner)
        spinner.adapter = intervalAdapter()
        spinner.setSelection(intervalIndex(WallpaperRepository.minIntervalMin(this)), false)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val min = WallpaperRepository.INTERVAL_OPTIONS_MIN[pos]
                // Mientras no se haya elegido en el diálogo inicial, no se guarda nada desde aquí.
                if (WallpaperRepository.hasMinInterval(this@MainActivity) &&
                    min != WallpaperRepository.minIntervalMin(this@MainActivity)
                ) {
                    WallpaperRepository.setMinIntervalMin(this@MainActivity, min)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /** Primer inicio: diálogo con menú desplegable (15 min, 30 min, 1 hora, 2 horas, 4 horas). */
    private fun askIntervalFirstTime(onChosen: () -> Unit) {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val spinner = Spinner(this).apply {
            adapter = intervalAdapter()
            setSelection(intervalIndex(WallpaperRepository.DEFAULT_INTERVAL_MIN))
        }
        val box = android.widget.LinearLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(spinner, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.interval_title)
            .setMessage(R.string.interval_message)
            .setView(box)
            .setCancelable(false)
            .setPositiveButton(R.string.confirm_accept) { _, _ ->
                val min = WallpaperRepository.INTERVAL_OPTIONS_MIN[spinner.selectedItemPosition]
                WallpaperRepository.setMinIntervalMin(this, min)
                findViewById<Spinner>(R.id.interval_spinner)
                    .setSelection(intervalIndex(min), false)
                onChosen()
            }
            .show()
    }

    private fun versionLabel(): String = runCatching {
        val pi = packageManager.getPackageInfo(packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
        "v${pi.versionName} (build $code)"
    }.getOrDefault("v?")

    override fun onResume() {
        super.onResume()
        findViewById<TextView>(R.id.version).text = "Versión ${versionLabel()}"
        grantOverlay.visibility =
            if (Settings.canDrawOverlays(this)) View.GONE else View.VISIBLE
        val ignoring = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        findViewById<View>(R.id.grant_battery).visibility = if (ignoring) View.GONE else View.VISIBLE
        val log = WallpaperRepository.attemptLog(this)
        findViewById<TextView>(R.id.attempt_log).text =
            if (log.isBlank()) "${versionLabel()} · sin intentos en segundo plano todavía." else "${versionLabel()} · últimos intentos:\n$log"
    }

    private fun openPicker() {
        startActivityForResult(Intent(this, PickViewActivity::class.java), REQ_PICK)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (!isActivityUsable()) return
        if (requestCode == REQ_CONFIRM) {
            if (resultCode == RESULT_OK) saveAndOpenWallpaperPicker()
            return
        }
        if (requestCode != REQ_PICK) return
        val hasImage = WallpaperRepository.cacheFile(this).exists()
        if (resultCode == RESULT_OK || !hasImage) startDownload() else loadCachedThenRefresh()
    }

    private fun loadCachedThenRefresh() {
        val cached = runCatching { WallpaperRepository.loadCached(this) }.getOrNull()
        if (cached != null) {
            showImageSafely(cached)
            // Con imagen guardada, al abrir la app se respeta el tiempo mínimo (force = false).
            captureNow(fallbackToWindow = false, force = false) { ok ->
                if (!isActivityUsable() || !ok) return@captureNow
                runCatching { WallpaperRepository.loadCached(this) }.getOrNull()
                    ?.let { showImageSafely(it) }
            }
        } else {
            startDownload()
        }
    }

    override fun onDestroy() {
        leavingActivity = true
        super.onDestroy()
    }

    private fun applySafeBottomInset() {
        val root = findViewById<View>(R.id.root_container)
        val basePadding = root.paddingBottom
        val extra = (24 * resources.displayMetrics.density).toInt()
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bottom = if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(WindowInsets.Type.systemBars()).bottom
            } else {
                @Suppress("DEPRECATION") insets.systemWindowInsetBottom
            }
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, basePadding + bottom + extra)
            insets
        }
        root.requestApplyInsets()
    }

    private fun startDownload() {
        progress.visibility = View.VISIBLE
        status.visibility = View.VISIBLE
        status.setText(R.string.loading)
        button.isEnabled = false
        captureNow(fallbackToWindow = true) { ok ->
            if (!isActivityUsable()) return@captureNow
            progress.visibility = View.GONE
            // Si la descarga falla pero hay una imagen anterior, se muestra esa en lugar de un error.
            val bmp = runCatching { WallpaperRepository.loadCached(this) }.getOrNull()
            if (bmp != null) showImageSafely(bmp) else showError()
        }
    }

    /**
     * Captura con el mismo método que el fondo en segundo plano (imagen del tamaño y proporción de la
     * pantalla). Si falla y [fallbackToWindow], reintenta con el WebView visible dentro de la app.
     */
    private fun captureNow(fallbackToWindow: Boolean, force: Boolean = true, onDone: (Boolean) -> Unit) {
        WallpaperRepository.refresh(this, force = force, host = null, source = "manual") { ok ->
            if (ok || !fallbackToWindow || !isActivityUsable()) {
                onDone(ok)
            } else {
                progress.visibility = View.GONE
                status.visibility = View.GONE
                WallpaperRepository.refresh(
                    this, force = true, host = previewContainer, source = "manual (ventana)"
                ) { ok2 -> onDone(ok2) }
            }
        }
    }

    /** Muestra la imagen a pantalla completa con Aceptar / Cancelar antes de aplicar el fondo. */
    private fun openConfirm() {
        WallpaperRepository.saveState(this, preview.state)
        startActivityForResult(Intent(this, ConfirmWallpaperActivity::class.java), REQ_CONFIRM)
    }

    private fun showImageSafely(bitmap: Bitmap) {
        try {
            showImage(bitmap)
        } catch (t: Throwable) {
            showError()
        }
    }

    private fun showImage(bitmap: Bitmap) {
        progress.visibility = View.GONE
        status.visibility = View.GONE
        preview.setBitmap(
            bitmap,
            WallpaperRepository.loadState(this),
            WallpaperRepository.updatedAt(this)
        )
        button.isEnabled = true
    }

    private fun showError() {
        if (!isActivityUsable()) return
        progress.visibility = View.GONE
        status.visibility = View.VISIBLE
        val reason = WallpaperRepository.lastError
        status.text = getString(R.string.download_error) +
            (if (reason != null) "\n\nMotivo: $reason" else "") +
            "\n\n" + getString(R.string.tap_retry)
        button.isEnabled = false
    }

    private fun isActivityUsable(): Boolean = !(leavingActivity || isFinishing || isDestroyed)

    private fun saveAndOpenWallpaperPicker() {
        WallpaperRepository.saveState(this, preview.state)
        if (WallpaperRepository.lockEnabled(this)) WallpaperRepository.applyLockScreenAsync(this)
        val component = ComponentName(this, SatelliteWallpaperService::class.java)
        val intent = Intent("android.service.wallpaper.CHANGE_LIVE_WALLPAPER").apply {
            putExtra("android.service.wallpaper.extra.LIVE_WALLPAPER_COMPONENT", component)
        }
        try {
            startActivity(intent)
        } catch (t: Throwable) {
            startActivity(Intent("android.service.wallpaper.LIVE_WALLPAPER_CHOOSER"))
        }
    }

    companion object {
        private const val REQ_PICK = 1001
        private const val REQ_CONFIRM = 1002
    }
}
