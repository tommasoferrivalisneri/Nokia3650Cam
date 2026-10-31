package com.example.nokiacam

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import java.util.Random
import kotlin.math.hypot

/**
 * Look "fotocamera VGA 2003".
 * ATTENZIONE: i parametri sono stime, non misure sul sensore reale del 3650.
 * Vanno tarati a occhio confrontando con foto vere.
 */
object NokiaLook {
    const val OUT_W = 640
    const val OUT_H = 480

    // Risoluzione "effettiva": si scende a questa e si risale a 640x480 (ammorbidisce)
    private const val SOFT_W = 512
    private const val SOFT_H = 384

    private const val SATURATION = 0.80f
    private const val CONTRAST = 0.88f
    private const val BLACK_LIFT = 10f
    private const val NOISE = 6f
    private const val VIGNETTE = 0.25f
    private val TINT = floatArrayOf(1.03f, 1.03f, 0.93f) // leggermente caldo/giallastro

    fun apply(src: Bitmap): Bitmap {
        // 1) crop centrale 4:3
        val target = OUT_W.toFloat() / OUT_H
        val aspect = src.width.toFloat() / src.height
        val crop = if (aspect > target) {
            val w = (src.height * target).toInt()
            Rect((src.width - w) / 2, 0, (src.width + w) / 2, src.height)
        } else {
            val h = (src.width / target).toInt()
            Rect(0, (src.height - h) / 2, src.width, (src.height + h) / 2)
        }

        // 2) giù a 512x384, su a 640x480
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val small = Bitmap.createBitmap(SOFT_W, SOFT_H, Bitmap.Config.ARGB_8888)
        Canvas(small).drawBitmap(src, crop, Rect(0, 0, SOFT_W, SOFT_H), paint)
        val out = Bitmap.createBitmap(OUT_W, OUT_H, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(small, null, Rect(0, 0, OUT_W, OUT_H), paint)
        small.recycle()

        // 3) colore, contrasto, vignettatura, rumore
        val px = IntArray(OUT_W * OUT_H)
        out.getPixels(px, 0, OUT_W, 0, 0, OUT_W, OUT_H)
        val rnd = Random(System.nanoTime())
        val cx = OUT_W / 2f
        val cy = OUT_H / 2f
        val maxD = hypot(cx, cy)
        var i = 0
        for (y in 0 until OUT_H) {
            for (x in 0 until OUT_W) {
                val c = px[i]
                var r = ((c shr 16) and 0xFF).toFloat()
                var g = ((c shr 8) and 0xFF).toFloat()
                var b = (c and 0xFF).toFloat()

                val l = 0.299f * r + 0.587f * g + 0.114f * b
                r = l + (r - l) * SATURATION
                g = l + (g - l) * SATURATION
                b = l + (b - l) * SATURATION

                r = ((r - 128f) * CONTRAST + 128f + BLACK_LIFT) * TINT[0]
                g = ((g - 128f) * CONTRAST + 128f + BLACK_LIFT) * TINT[1]
                b = ((b - 128f) * CONTRAST + 128f + BLACK_LIFT) * TINT[2]

                val d = hypot(x - cx, y - cy) / maxD
                val v = 1f - VIGNETTE * d * d
                val n = rnd.nextGaussian().toFloat() * NOISE
                r = (r + n + rnd.nextGaussian().toFloat() * 2f) * v
                g = (g + n + rnd.nextGaussian().toFloat() * 2f) * v
                b = (b + n + rnd.nextGaussian().toFloat() * 2f) * v

                px[i++] = (0xFF shl 24) or (cl(r) shl 16) or (cl(g) shl 8) or cl(b)
            }
        }
        out.setPixels(px, 0, OUT_W, 0, 0, OUT_W, OUT_H)
        return out
    }

    private fun cl(v: Float): Int = if (v < 0f) 0 else if (v > 255f) 255 else v.toInt()

    /** Approssimazione di colore/contrasto per l'anteprima (non tocca il video registrato). */
    fun previewFilter(): ColorMatrixColorFilter {
        val cm = ColorMatrix().apply { setSaturation(SATURATION) }
        val off = 128f * (1f - CONTRAST) + BLACK_LIFT
        cm.postConcat(
            ColorMatrix(
                floatArrayOf(
                    CONTRAST * TINT[0], 0f, 0f, 0f, off * TINT[0],
                    0f, CONTRAST * TINT[1], 0f, 0f, off * TINT[1],
                    0f, 0f, CONTRAST * TINT[2], 0f, off * TINT[2],
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
        return ColorMatrixColorFilter(cm)
    }
}
