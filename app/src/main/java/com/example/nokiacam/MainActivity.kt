package com.example.nokiacam

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView

class MainActivity : Activity(), CameraEngine.Listener {

    private lateinit var tv: TextureView
    private lateinit var status: TextView
    private lateinit var btnMode: Button
    private lateinit var btnQuality: Button
    private lateinit var btnShutter: Button
    private lateinit var engine: CameraEngine

    private val ui = Handler(Looper.getMainLooper())
    private var videoMode = false
    private var recStart = 0L

    // Qualità JPEG: valori iniziali da tarare (obiettivo ~18 / ~45 / ~68 KB a 640x480)
    private val qualities = listOf("BASIC" to 25, "NORMAL" to 45, "HIGH" to 70)
    private var qIdx = 1

    private val tick = object : Runnable {
        override fun run() {
            val s = (SystemClock.elapsedRealtime() - recStart) / 1000
            status.text = "● REC %02d:%02d".format(s / 60, s % 60)
            ui.postDelayed(this, 500)
        }
    }

    @Suppress("DEPRECATION")
    private fun rotation() = windowManager.defaultDisplay.rotation

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        tv = findViewById(R.id.preview)
        status = findViewById(R.id.status)
        btnMode = findViewById(R.id.btnMode)
        btnQuality = findViewById(R.id.btnQuality)
        btnShutter = findViewById(R.id.btnShutter)

        // Anteprima con colore/contrasto approssimati del look
        tv.setLayerType(android.view.View.LAYER_TYPE_HARDWARE,
            Paint().apply { colorFilter = NokiaLook.previewFilter() })

        // Anteprima sempre 4:3 centrata
        val container = findViewById<FrameLayout>(R.id.container)
        container.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val cw = r - l; val ch = b - t
            if (cw == 0 || ch == 0) return@addOnLayoutChangeListener
            var w = cw; var h = w * 3 / 4
            if (h > ch) { h = ch; w = h * 4 / 3 }
            val lp = tv.layoutParams as FrameLayout.LayoutParams
            if (lp.width != w || lp.height != h) {
                lp.width = w; lp.height = h; lp.gravity = Gravity.CENTER
                tv.layoutParams = lp
            }
            tv.setTransform(Matrix().apply {
                if (rotation() == Surface.ROTATION_270) postRotate(180f, w / 2f, h / 2f)
            })
        }

        engine = CameraEngine(this, tv, { rotation() }, this)
        engine.jpegQuality = qualities[qIdx].second

        btnQuality.setOnClickListener {
            qIdx = (qIdx + 1) % qualities.size
            btnQuality.text = qualities[qIdx].first
            engine.jpegQuality = qualities[qIdx].second
        }
        btnMode.setOnClickListener {
            if (engine.isRecording) return@setOnClickListener
            videoMode = !videoMode
            refreshUi()
        }
        btnShutter.setOnClickListener {
            if (!videoMode) {
                engine.takePhoto()
            } else if (engine.isRecording) {
                engine.stopRecording(); ui.removeCallbacks(tick); refreshUi()
            } else {
                engine.startRecording()
                recStart = SystemClock.elapsedRealtime(); ui.post(tick)
                btnShutter.text = "STOP"
            }
        }
        refreshUi()
    }

    private fun refreshUi() {
        btnMode.text = if (videoMode) "MODO: VIDEO" else "MODO: FOTO"
        btnShutter.text = if (videoMode) "REC" else "SCATTA"
        btnQuality.isEnabled = !videoMode
        status.text = if (videoMode) "VIDEO 176x144" else "FOTO 640x480"
    }

    override fun onResume() {
        super.onResume()
        tryOpen()
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        engine.close()
        refreshUi()
        super.onPause()
    }

    private fun tryOpen() {
        val need = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) { requestPermissions(need.toTypedArray(), 1); return }
        if (tv.isAvailable) engine.open()
        else tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) = engine.open()
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        if (res.isNotEmpty() && res.all { it == PackageManager.PERMISSION_GRANTED }) tryOpen()
        else status.text = "Permessi negati"
    }

    override fun onMessage(text: String) {
        runOnUiThread { status.text = text }
    }
}
