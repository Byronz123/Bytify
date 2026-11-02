package com.bytify.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.random.Random

val CelShape = RoundedCornerShape(18.dp)

/**
 * Gaya "cel" khas Bytify: isi warna rata, garis tebal, dan bayangan keras
 * yang bergeser (seperti stiker / ilustrasi anime). Bayangan digambar di luar
 * batas, jadi beri ruang [shadow] di kanan-bawah elemen.
 */
fun Modifier.cel(
    fill: Color? = null,
    shape: Shape = CelShape,
    stroke: Dp = 2.dp,
    shadow: Dp = 4.dp,
): Modifier = composed {
    val cel = LocalCel.current
    this
        .drawBehind {
            val outline = shape.createOutline(size, layoutDirection, this)
            val s = shadow.toPx()
            if (s > 0f) translate(s, s) { drawOutline(outline, cel.shadow) }
            if (fill != null) drawOutline(outline, fill)
        }
        .clip(shape)
        .drawWithContent {
            drawContent()
            val outline = shape.createOutline(size, layoutDirection, this)
            drawOutline(outline, cel.line, style = Stroke(width = stroke.toPx() * 2))
        }
}

/**
 * Kilau diagonal seperti pantulan pada botol plastik di ilustrasi: dua pita
 * putih transparan miring di sisi kiri elemen.
 */
fun Modifier.gloss(alpha: Float = 0.35f): Modifier = drawWithContent {
    drawContent()
    val w = size.width
    val h = size.height
    fun band(start: Float, width: Float, a: Float) {
        val path = Path().apply {
            moveTo(w * start, 0f)
            lineTo(w * (start + width), 0f)
            lineTo(w * (start + width) - h * 0.6f, h)
            lineTo(w * start - h * 0.6f, h)
            close()
        }
        drawPath(path, Color.White.copy(alpha = a))
    }
    band(0.22f, 0.12f, alpha)
    band(0.40f, 0.05f, alpha * 0.7f)
}

/** Kilau melengkung di bagian atas (untuk tombol bulat). */
fun Modifier.topShine(alpha: Float = 0.3f): Modifier = drawWithContent {
    drawContent()
    drawCircle(
        brush = Brush.verticalGradient(
            listOf(Color.White.copy(alpha = alpha), Color.Transparent),
            startY = 0f,
            endY = size.height * 0.55f,
        ),
        radius = size.minDimension * 0.42f,
        center = Offset(size.width / 2, size.height * 0.38f),
    )
}

/** Tekstur kertas: noise halus berulang di atas latar. */
@Composable
fun rememberPaperTexture(): ImageBitmap = remember {
    val size = 160
    val random = Random(11)
    val pixels = IntArray(size * size) {
        val v = random.nextInt(256)
        val a = (kotlin.math.abs(v - 128) / 128f * 40).toInt()
        val c = if (v > 128) 255 else 0
        (a shl 24) or (c shl 16) or (c shl 8) or c
    }
    android.graphics.Bitmap.createBitmap(pixels, size, size, android.graphics.Bitmap.Config.ARGB_8888)
        .asImageBitmap()
}

/** Latar bertekstur kertas untuk seluruh aplikasi. */
@Composable
fun PaperBackground(color: Color, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val texture = rememberPaperTexture()
    val brush = remember(texture) { ShaderBrush(ImageShader(texture, TileMode.Repeated, TileMode.Repeated)) }
    Box(
        modifier
            .background(color)
            .drawBehind { drawRect(brush, alpha = 0.55f) },
        content = content,
    )
}

/** Garis bawah sapuan kuas gold di bawah judul (motif palet). */
fun Modifier.brushUnderline(color: Color = BytifyColors.Gold): Modifier = drawBehind {
    val h = size.height
    val top = h * 0.58f
    val path = Path().apply {
        moveTo(0f, top + h * 0.06f)
        cubicTo(size.width * 0.3f, top - h * 0.04f, size.width * 0.7f, top + h * 0.1f, size.width, top)
        lineTo(size.width - h * 0.05f, top + h * 0.34f)
        cubicTo(size.width * 0.6f, top + h * 0.42f, size.width * 0.3f, top + h * 0.3f, h * 0.04f, top + h * 0.38f)
        close()
    }
    drawPath(path, color.copy(alpha = 0.55f))
}

/** Kotak dengan gaya cel untuk konten bebas. */
@Composable
fun CelBox(
    modifier: Modifier = Modifier,
    fill: Color,
    shape: Shape = CelShape,
    shadow: Dp = 4.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.cel(fill, shape, shadow = shadow), content = content)
}
