package com.bytify.app.util

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.bytify.app.R
import com.bytify.app.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Kartu berbagi bergaya Bytify (1080x1350): cover, judul, dan — opsional —
 * potongan lirik. Dibagikan sebagai gambar PNG.
 */
object ShareCard {
    private const val W = 1080
    private const val H = 1350
    private const val PAD = 84f

    suspend fun share(context: Context, song: Song, lyricLines: List<String> = emptyList()) {
        val file = withContext(Dispatchers.Default) {
            val cover = loadCover(context, song.thumbnailUrl)
            val bitmap = render(context, song, lyricLines, cover)
            val dir = File(context.cacheDir, "shared").apply { mkdirs() }
            File(dir, "bytify-${song.id.hashCode()}.png").also { out ->
                out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            shareUrl(song.id)?.let { putExtra(Intent.EXTRA_TEXT, "${song.title} – ${song.artist}\n$it") }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, if (lyricLines.isEmpty()) "Bagikan lagu" else "Bagikan lirik")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private suspend fun loadCover(context: Context, url: String?): Bitmap? {
        if (url.isNullOrBlank()) return null
        val request = ImageRequest.Builder(context).data(url).size(720).allowHardware(false).build()
        return ((context.imageLoader.execute(request) as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
    }

    private fun render(context: Context, song: Song, lines: List<String>, cover: Bitmap?): Bitmap {
        val bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val base = ResourcesCompat.getFont(context, R.font.plus_jakarta_sans) ?: Typeface.DEFAULT
        fun font(weight: Int) =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Typeface.create(base, weight, false)
            else Typeface.create(base, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)

        // Latar: warna dominan cover → hitam.
        val tint = cover?.let { Palette.from(it).generate().let { p -> p.darkVibrantSwatch ?: p.dominantSwatch }?.rgb }
            ?: 0xFF113D24.toInt()
        canvas.drawPaint(Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, H.toFloat(), tint, 0xFF0C100E.toInt(), Shader.TileMode.CLAMP)
        })

        val lyricsMode = lines.isNotEmpty()
        val coverSize = if (lyricsMode) 220f else 700f
        val coverLeft = if (lyricsMode) PAD else (W - coverSize) / 2
        val coverTop = if (lyricsMode) PAD + 40f else 110f
        drawCover(canvas, cover, RectF(coverLeft, coverTop, coverLeft + coverSize, coverTop + coverSize))

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); typeface = font(800) }
        val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt(); typeface = font(500) }

        if (lyricsMode) {
            val textLeft = coverLeft + coverSize + 40f
            val textWidth = (W - PAD - textLeft).toInt()
            titlePaint.textSize = 52f
            artistPaint.textSize = 38f
            val title = layout(song.title, titlePaint, textWidth, maxLines = 2)
            canvas.save(); canvas.translate(textLeft, coverTop + 30f); title.draw(canvas); canvas.restore()
            val artist = layout(song.artist, artistPaint, textWidth, maxLines = 1)
            canvas.save(); canvas.translate(textLeft, coverTop + 40f + title.height); artist.draw(canvas); canvas.restore()

            val lyricPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt(); typeface = font(800); textSize = 68f
            }
            val text = lines.joinToString("\n")
            var lyric = layout(text, lyricPaint, (W - PAD * 2).toInt(), maxLines = 9)
            while (lyric.height > H - 560 && lyricPaint.textSize > 40f) {
                lyricPaint.textSize -= 4f
                lyric = layout(text, lyricPaint, (W - PAD * 2).toInt(), maxLines = 9)
            }
            canvas.save(); canvas.translate(PAD, coverTop + coverSize + 90f); lyric.draw(canvas); canvas.restore()
        } else {
            titlePaint.textSize = 64f
            artistPaint.textSize = 42f
            val width = (W - PAD * 2).toInt()
            val title = layout(song.title, titlePaint, width, maxLines = 2, center = true)
            val top = coverTop + coverSize + 70f
            canvas.save(); canvas.translate(PAD, top); title.draw(canvas); canvas.restore()
            val artist = layout(song.artist, artistPaint, width, maxLines = 1, center = true)
            canvas.save(); canvas.translate(PAD, top + title.height + 14f); artist.draw(canvas); canvas.restore()
        }

        drawBrand(canvas, font(800))
        return bitmap
    }

    private fun drawCover(canvas: Canvas, cover: Bitmap?, rect: RectF) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        if (cover == null) {
            paint.color = 0xFF1F2621.toInt()
        } else {
            paint.shader = BitmapShader(cover, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                val scale = maxOf(rect.width() / cover.width, rect.height() / cover.height)
                setLocalMatrix(Matrix().apply {
                    postScale(scale, scale)
                    postTranslate(
                        rect.left + (rect.width() - cover.width * scale) / 2,
                        rect.top + (rect.height() - cover.height * scale) / 2,
                    )
                })
            }
        }
        canvas.drawRoundRect(rect, 36f, 36f, paint)
    }

    /** Penanda "Bytify" + bar equalizer hijau di pojok bawah. */
    private fun drawBrand(canvas: Canvas, typeface: Typeface) {
        val green = 0xFF1ED760.toInt()
        val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = green }
        val baseY = H - PAD
        listOf(0.5f, 1f, 0.75f, 0.4f).forEachIndexed { i, h ->
            val x = PAD + i * 18f
            canvas.drawRoundRect(RectF(x, baseY - 44f * h, x + 11f, baseY), 6f, 6f, bar)
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt(); textSize = 44f; this.typeface = typeface
        }
        canvas.drawText("Bytify", PAD + 86f, baseY - 4f, text)
    }

    private fun layout(text: String, paint: TextPaint, width: Int, maxLines: Int, center: Boolean = false): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(if (center) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.12f)
            .setMaxLines(maxLines)
            .setEllipsize(android.text.TextUtils.TruncateAt.END)
            .build()
}
