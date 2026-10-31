package com.example.nokiacam

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ExifInterface
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Size
import android.view.Surface
import android.view.TextureView
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.min

/** Foto 640x480 JPEG "stile 3650" e video QCIF 176x144 a 10 fps in 3GP (H.263 + AMR-NB). */
class CameraEngine(
    private val ctx: Context,
    private val tv: TextureView,
    private val displayRotation: () -> Int,
    private val listener: Listener
) {
    interface Listener { fun onMessage(text: String) }

    companion object {
        const val FIXED_FOCUS = true            // il 3650 non ha autofocus
        const val FIXED_FOCUS_DIOPTERS = 0.5f   // ~2 m; metti FIXED_FOCUS=false per l'AF normale
        const val VIDEO_FPS = 10
        const val VIDEO_BITRATE = 56_000        // + AMR 12.2k ~ 68 kbps totali
    }

    private val manager = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val worker = Executors.newSingleThreadExecutor()
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var recorder: MediaRecorder? = null
    private var videoFile: File? = null
    private var chars: CameraCharacteristics? = null
    private var previewSize = Size(640, 480)
    private var photoSize = Size(640, 480)
    private var videoSize = Size(176, 144)

    /** Qualità JPEG (Basic/Normal/High). Impostata dalla UI. */
    var jpegQuality = 45
    var isRecording = false
        private set

    // ---------- apertura / chiusura ----------

    @SuppressLint("MissingPermission")
    fun open() {
        if (device != null) return
        startThread()
        val id = manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: run { listener.onMessage("Nessuna camera posteriore"); return }

        val c = manager.getCameraCharacteristics(id)
        chars = c
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        previewSize = largestUpTo(map.getOutputSizes(SurfaceTexture::class.java), 1024)
        photoSize = smallestAtLeast(map.getOutputSizes(ImageFormat.JPEG), 640)
        val vs = map.getOutputSizes(MediaRecorder::class.java) ?: emptyArray()
        videoSize = vs.firstOrNull { it.width == 176 && it.height == 144 }
            ?: vs.filter { is43(it) }.minByOrNull { it.width }
            ?: Size(320, 240)
        if (videoSize.width != 176) {
            listener.onMessage("176x144 non supportato: video ${videoSize.width}x${videoSize.height}")
        }

        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(cam: CameraDevice) { device = cam; startPhotoSession() }
            override fun onDisconnected(cam: CameraDevice) { cam.close(); device = null }
            override fun onError(cam: CameraDevice, error: Int) {
                cam.close(); device = null; listener.onMessage("Errore camera $error")
            }
        }, handler)
    }

    fun close() {
        if (isRecording) stopRecording()
        session?.close(); session = null
        device?.close(); device = null
        reader?.close(); reader = null
        previewSurface?.release(); previewSurface = null
        thread?.quitSafely(); thread = null; handler = null
    }

    private fun startThread() {
        if (thread != null) return
        thread = HandlerThread("cam").also { it.start(); handler = Handler(it.looper) }
    }

    // ---------- sessione foto + anteprima ----------

    private fun startPhotoSession() {
        val cam = device ?: return
        val st = tv.surfaceTexture ?: return
        session?.close(); session = null
        st.setDefaultBufferSize(previewSize.width, previewSize.height)
        previewSurface?.release()
        val preview = Surface(st).also { previewSurface = it }

        reader?.close()
        val r = ImageReader.newInstance(photoSize.width, photoSize.height, ImageFormat.JPEG, 2)
        r.setOnImageAvailableListener({ rd ->
            val img = rd.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val buf = img.planes[0].buffer
                val bytes = ByteArray(buf.remaining()); buf.get(bytes)
                processJpeg(bytes)
            } finally { img.close() }
        }, handler)
        reader = r

        @Suppress("DEPRECATION")
        cam.createCaptureSession(listOf(preview, r.surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                b.addTarget(preview); applyFocus(b, video = false)
                s.setRepeatingRequest(b.build(), null, handler)
            }
            override fun onConfigureFailed(s: CameraCaptureSession) = listener.onMessage("Sessione foto fallita")
        }, handler)
    }

    fun takePhoto() {
        val cam = device ?: return
        val s = session ?: return
        val r = reader ?: return
        if (isRecording) return
        val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        b.addTarget(r.surface)
        b.set(CaptureRequest.JPEG_ORIENTATION, orientation())
        applyFocus(b, video = false)
        s.capture(b.build(), null, handler)
    }

    private fun processJpeg(bytes: ByteArray) = worker.execute {
        try {
            var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@execute
            val rot = when (ExifInterface(ByteArrayInputStream(bytes))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (rot != 0f) {
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot) }, true)
            }
            val styled = NokiaLook.apply(bmp)
            val baos = ByteArrayOutputStream()
            styled.compress(Bitmap.CompressFormat.JPEG, jpegQuality, baos)
            val data = baos.toByteArray()
            val ok = saveMedia("IMG_${stamp()}.jpg", "image/jpeg", false) { it.write(data) }
            listener.onMessage(if (ok) "Foto salvata: ${data.size / 1024} KB" else "Salvataggio foto fallito")
        } catch (e: Exception) {
            listener.onMessage("Errore foto: ${e.message}")
        }
    }

    // ---------- video ----------

    fun startRecording(h263: Boolean = true) {
        val cam = device ?: return
        val st = tv.surfaceTexture ?: return
        session?.close(); session = null

        val file = File(ctx.cacheDir, "rec.3gp").also { videoFile = it }
        val rec = try {
            buildRecorder(file, h263)
        } catch (e: Exception) {
            if (h263) { startRecording(false); return }
            listener.onMessage("Recorder fallito: ${e.message}"); startPhotoSession(); return
        }
        recorder = rec

        st.setDefaultBufferSize(previewSize.width, previewSize.height)
        previewSurface?.release()
        val preview = Surface(st).also { previewSurface = it }
        val codec = if (h263) "H.263" else "H.264"

        @Suppress("DEPRECATION")
        cam.createCaptureSession(listOf(preview, rec.surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                try {
                    val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                    b.addTarget(preview); b.addTarget(rec.surface); applyFocus(b, video = true)
                    s.setRepeatingRequest(b.build(), null, handler)
                    rec.start()
                    isRecording = true
                    listener.onMessage("REC $codec ${videoSize.width}x${videoSize.height}")
                } catch (e: Exception) { fail(rec, h263, e.message) }
            }
            override fun onConfigureFailed(s: CameraCaptureSession) = fail(rec, h263, "sessione non configurabile")
        }, handler)
    }

    private fun fail(rec: MediaRecorder, wasH263: Boolean, why: String?) {
        try { rec.release() } catch (_: Exception) {}
        recorder = null; isRecording = false
        if (wasH263) startRecording(false)
        else { listener.onMessage("Video fallito: $why"); startPhotoSession() }
    }

    private fun buildRecorder(file: File, h263: Boolean): MediaRecorder {
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            r.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
            r.setOutputFile(file.absolutePath)
            r.setVideoEncoder(if (h263) MediaRecorder.VideoEncoder.H263 else MediaRecorder.VideoEncoder.H264)
            r.setVideoSize(videoSize.width, videoSize.height)
            r.setVideoFrameRate(VIDEO_FPS)
            r.setVideoEncodingBitRate(VIDEO_BITRATE)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
            r.setAudioSamplingRate(8000)
            r.setAudioEncodingBitRate(12200)
            r.setAudioChannels(1)
            r.setOrientationHint(orientation())
            r.prepare()
        } catch (e: Exception) { r.release(); throw e }
        return r
    }

    fun stopRecording() {
        val rec = recorder ?: return
        try { session?.stopRepeating(); session?.abortCaptures() } catch (_: Exception) {}
        try { rec.stop() } catch (_: RuntimeException) { /* clip troppo breve */ }
        rec.reset(); rec.release()
        recorder = null; isRecording = false

        val f = videoFile
        if (f != null && f.length() > 0) worker.execute {
            val ok = saveMedia("VID_${stamp()}.3gp", "video/3gpp", true) { out -> f.inputStream().use { it.copyTo(out) } }
            listener.onMessage(if (ok) "Video salvato: ${f.length() / 1024} KB" else "Salvataggio video fallito")
            f.delete()
        }
        startPhotoSession()
    }

    // ---------- utilità ----------

    private fun applyFocus(b: CaptureRequest.Builder, video: Boolean) {
        val minFocus = chars?.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        if (FIXED_FOCUS && minFocus > 0f) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, min(minFocus, FIXED_FOCUS_DIOPTERS))
        } else {
            b.set(
                CaptureRequest.CONTROL_AF_MODE,
                if (video) CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                else CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
        }
    }

    /** Orientamento per JPEG e video (camera posteriore). */
    private fun orientation(): Int {
        val sensor = chars?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val deg = when (displayRotation()) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return (sensor - deg + 360) % 360
    }

    private fun is43(s: Size) = s.width * 3 == s.height * 4

    private fun smallestAtLeast(sizes: Array<Size>, minW: Int): Size =
        sizes.filter { is43(it) && it.width >= minW }.minByOrNull { it.width }
            ?: sizes.maxByOrNull { it.width * it.height }!!

    private fun largestUpTo(sizes: Array<Size>, maxW: Int): Size =
        sizes.filter { is43(it) && it.width <= maxW }.maxByOrNull { it.width }
            ?: sizes.minByOrNull { it.width * it.height }!!

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun saveMedia(name: String, mime: String, isVideo: Boolean, write: (OutputStream) -> Unit): Boolean {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, if (isVideo) "Movies/Nokia3650" else "Pictures/Nokia3650")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, values) ?: return false
        return try {
            resolver.openOutputStream(uri)!!.use { write(it) }
            values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (e: Exception) {
            resolver.delete(uri, null, null); false
        }
    }
}
