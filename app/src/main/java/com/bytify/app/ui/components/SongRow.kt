package com.bytify.app.ui.components

import com.bytify.app.ui.theme.BytifyColors
import com.bytify.app.ui.theme.brushUnderline
import com.bytify.app.ui.theme.cel
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bytify.app.data.model.Song
import com.bytify.app.util.formatDuration

/** Baris lagu: cover, judul, artis • durasi, tombol menu. */
@Composable
fun SongRow(
    song: Song,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    number: Int? = null,
    subtitle: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (number != null) {
            Text(
                "$number",
                style = MaterialTheme.typography.titleSmall,
                color = if (isCurrent) colors.primary else colors.onSurfaceVariant,
                modifier = Modifier.width(30.dp),
            )
        }
        Box(Modifier.size(52.dp)) {
            Artwork(
                song.thumbnailUrl,
                Modifier.fillMaxSize().cel(shape = RoundedCornerShape(10.dp), stroke = 1.5.dp, shadow = 0.dp),
                RoundedCornerShape(10.dp),
            )
            if (isCurrent) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(BytifyColors.Navy.copy(alpha = 0.55f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.GraphicEq, null, tint = BytifyColors.Gold)
                }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                song.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (isCurrent) colors.primary else colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle ?: listOfNotNull(song.artist, song.durationSec?.let(::formatDuration))
                    .joinToString(" • "),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onMore) {
            Icon(Icons.Rounded.MoreVert, "Opsi lagu", tint = colors.onSurfaceVariant)
        }
    }
}

/** Kartu lagu untuk carousel horizontal. */
@Composable
fun SongCard(
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    caption: String? = null,
    title: String = song.title,
) {
    Column(
        modifier
            .width(152.dp)
            .clickable(onClick = onClick),
    ) {
        Artwork(song.thumbnailUrl, Modifier.size(148.dp).cel(shape = RoundedCornerShape(14.dp)), RoundedCornerShape(14.dp))
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            caption ?: song.artist,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Judul bagian dengan aksi opsional di kanan. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 24.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.brushUnderline())
        }
        action?.invoke()
    }
}
