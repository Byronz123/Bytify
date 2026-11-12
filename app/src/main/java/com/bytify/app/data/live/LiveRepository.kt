package com.bytify.app.data.live

import com.bytify.app.data.db.BytifyDao
import com.bytify.app.data.db.LiveChannelEntity
import com.bytify.app.data.db.LiveFavoriteEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.io.IOException

data class LiveSyncResult(
    val totalChannels: Int,
    val availableChannels: Int,
    val refreshAfterSeconds: Int,
    val generatedAtEpochMs: Long,
    val sourceSyncError: String?,
)

class LiveRepository(
    private val dao: BytifyDao,
    private val api: LiveCatalogApi,
) {
    val isConfigured: Boolean get() = api.isConfigured

    // Katalog kini belasan ribu kanal (dulu 1.815). Pemetaan sebanyak itu
    // dipindah ke Default agar tidak dikerjakan di main thread saat daftar
    // disegarkan — masalah yang sama pernah membuat layar film tersendat.
    val channels: Flow<List<LiveChannel>> = dao.liveChannels()
        .map { rows -> rows.map(LiveChannelEntity::toChannel) }
        .flowOn(Dispatchers.Default)
    val availableChannels: Flow<List<LiveChannel>> = dao.availableLiveChannels()
        .map { rows -> rows.map(LiveChannelEntity::toChannel) }
        .flowOn(Dispatchers.Default)
    val favoriteIds: Flow<Set<String>> = dao.liveFavoriteIds().map { it.toSet() }
    fun channel(id: String): Flow<LiveChannel?> = dao.liveChannel(id).map { it?.toChannel() }

    /**
     * Mengganti snapshot katalog secara transaksional. Respons kosong/tidak valid
     * tidak menghapus cache terakhir, sehingga gangguan upstream tidak membuat UI kosong.
     */
    suspend fun refresh(): LiveSyncResult {
        val catalog = api.fetchCatalog()
        val now = System.currentTimeMillis()
        val valid = catalog.channels.mapNotNull(LiveChannelDto::toChannelOrNull).distinctBy(LiveChannel::id)
        if (valid.isEmpty()) {
            throw IOException("Server Live TV tidak mengirim kanal valid; cache terakhir dipertahankan.")
        }
        dao.replaceLiveChannels(valid.map { it.toEntity(now) })
        return LiveSyncResult(
            totalChannels = valid.size,
            availableChannels = valid.count(LiveChannel::isAvailable),
            refreshAfterSeconds = catalog.refreshAfterSeconds.coerceIn(60, 3600),
            generatedAtEpochMs = catalog.generatedAtEpochMs,
            sourceSyncError = catalog.sourceSyncError,
        )
    }

    suspend fun setFavorite(channelId: String, favorite: Boolean) {
        if (favorite) {
            dao.upsertLiveFavorite(LiveFavoriteEntity(channelId, System.currentTimeMillis()))
        } else {
            dao.deleteLiveFavorite(channelId)
        }
    }
}

private fun LiveChannel.toEntity(updatedAt: Long) = LiveChannelEntity(
    id = id,
    name = name,
    description = description,
    logoUrl = logoUrl,
    category = category,
    country = country,
    language = language,
    streamUrl = streamUrl,
    streamType = streamType,
    isAvailable = isAvailable,
    statusMessage = statusMessage,
    checkedAtEpochMs = checkedAtEpochMs,
    license = license,
    updatedAt = updatedAt,
)

private fun LiveChannelEntity.toChannel() = LiveChannel(
    id = id,
    name = name,
    description = description,
    logoUrl = logoUrl,
    category = category,
    country = country,
    language = language,
    streamUrl = streamUrl,
    streamType = streamType,
    isAvailable = isAvailable,
    statusMessage = statusMessage,
    checkedAtEpochMs = checkedAtEpochMs,
    license = license,
)
