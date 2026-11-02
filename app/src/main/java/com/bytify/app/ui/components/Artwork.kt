package com.bytify.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.palette.graphics.Palette
import coil.compose.SubcomposeAsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import android.graphics.drawable.BitmapDrawable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Cover lagu dengan placeholder bertema. */
@Composable
fun Artwork(
    url: String?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(10.dp),
) {
    Box(
        modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isNullOrBlank()) {
            PlaceholderIcon()
        } else {
            SubcomposeAsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                error = { PlaceholderIcon() },
            )
        }
    }
}

@Composable
private fun PlaceholderIcon() {
    Icon(
        Icons.Rounded.MusicNote,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
    )
}

/** Kolase 2x2 dari beberapa cover (playlist otomatis & buatan pengguna). */
@Composable
fun CollageArtwork(
    urls: List<String?>,
    modifier: Modifier = Modifier,
    size: Dp,
    shape: Shape = RoundedCornerShape(12.dp),
) {
    val distinct = urls.filterNotNull().distinct()
    if (distinct.size < 4) {
        Artwork(distinct.firstOrNull(), modifier.size(size), shape)
        return
    }
    Column(modifier.size(size).clip(shape)) {
        for (row in 0..1) {
            Row {
                for (col in 0..1) {
                    Artwork(
                        distinct[row * 2 + col],
                        Modifier.size(size / 2),
                        RoundedCornerShape(0.dp),
                    )
                }
            }
        }
    }
}

/** Warna dominan cover (gelap) untuk latar player & kartu dinamis. */
@Composable
fun rememberDominantColor(url: String?, fallback: Color): Color {
    val context = LocalContext.current
    var color by remember(url) { mutableStateOf(fallback) }
    LaunchedEffect(url) {
        if (url.isNullOrBlank()) return@LaunchedEffect
        val request = ImageRequest.Builder(context).data(url).allowHardware(false).size(128).build()
        val result = context.imageLoader.execute(request) as? SuccessResult ?: return@LaunchedEffect
        val bitmap = (result.drawable as? BitmapDrawable)?.bitmap ?: return@LaunchedEffect
        val palette = withContext(Dispatchers.Default) { Palette.from(bitmap).generate() }
        val swatch = palette.darkVibrantSwatch ?: palette.dominantSwatch ?: palette.darkMutedSwatch
        swatch?.let { color = Color(it.rgb) }
    }
    return color
}
