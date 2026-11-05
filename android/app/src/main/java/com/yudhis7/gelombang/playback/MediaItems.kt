package com.yudhis7.gelombang.playback

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import com.yudhis7.gelombang.R
import com.yudhis7.gelombang.data.AppGraph
import com.yudhis7.gelombang.data.Station

/** Media IDs used by the browse tree that Android Auto and the app share. */
object MediaIds {
    const val ROOT = "root"
    const val FAVORITES = "favorites"
    const val RECENTS = "recents"
    const val REGIONS = "regions"
    const val TRENDING = "trending"
    private const val GROUP = "group:"
    private const val COUNTRY = "country:"
    private const val STATION = "station:"

    fun group(id: String) = GROUP + id
    fun country(code: String) = COUNTRY + code
    fun station(uuid: String) = STATION + uuid

    fun groupId(mediaId: String) = mediaId.removePrefix(GROUP).takeIf { mediaId.startsWith(GROUP) }
    fun countryCode(mediaId: String) = mediaId.removePrefix(COUNTRY).takeIf { mediaId.startsWith(COUNTRY) }
    fun stationUuid(mediaId: String) = mediaId.removePrefix(STATION).takeIf { mediaId.startsWith(STATION) }
}

/** Second line shown under a station name: where it is from. */
fun Station.placeLine(): String {
    val countryName = if (countryCode.isNotEmpty()) AppGraph.regions.name(countryCode) else country
    return listOf(state, countryName).filter { it.isNotBlank() }.distinct().joinToString(", ").ifBlank { if (custom) "Your station" else "Live radio" }
}

fun Station.toMediaItem(context: Context): MediaItem {
    val place = placeLine()
    val metadata = MediaMetadata.Builder()
        .setTitle(name)
        .setDisplayTitle(name)
        .setArtist(place)
        .setSubtitle(place)
        .setStation(name)
        .setGenre(tags.firstOrNull())
        .setArtworkUri(ArtworkProvider.stationUri(context, favicon, name))
        .setIsPlayable(true)
        .setIsBrowsable(false)
        .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
        .build()
    return MediaItem.Builder()
        .setMediaId(MediaIds.station(uuid))
        .setUri(url)
        .setMimeType(if (hls) MimeTypes.APPLICATION_M3U8 else null)
        .setMediaMetadata(metadata)
        .build()
}

fun folderItem(
    mediaId: String,
    title: String,
    subtitle: String? = null,
    artwork: Uri? = null,
    mediaType: Int = MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS,
    extras: Bundle? = null,
): MediaItem = MediaItem.Builder()
    .setMediaId(mediaId)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setArtist(subtitle)
            .setArtworkUri(artwork)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(mediaType)
            .setExtras(extras)
            .build(),
    )
    .build()

/** Icon for a root tab or category, as an android.resource:// URI. */
fun resourceUri(context: Context, resId: Int): Uri =
    Uri.parse("android.resource://${context.packageName}/" + context.resources.getResourceTypeName(resId) + "/" + context.resources.getResourceEntryName(resId))

object TabIcons {
    val favorites = R.drawable.ic_star_filled
    val recents = R.drawable.ic_clock
    val regions = R.drawable.ic_globe
    val trending = R.drawable.ic_trend
}
