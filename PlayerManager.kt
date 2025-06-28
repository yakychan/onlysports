package com.tararira.onlysports.player

import android.content.Context
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManagerProvider
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.smoothstreaming.SsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.util.EventLogger
import com.tararira.onlysports.data.model.ChannelSample
import java.util.Locale

@OptIn(UnstableApi::class)
class PlayerManager(
    private val context: Context,
    private val channelSamples: List<ChannelSample>,
    private val onError: (String?) -> Unit,
    private val onPlaybackError: (String?) -> Unit
) : Player.Listener {

    val exoPlayer: ExoPlayer
    private var currentUriIndex = 0
    private var hasReportedFatalError = false
    private val logTag = "PlayerManager"
    private val defaultUserAgent = Util.getUserAgent(context, "OnlySportsApp")
    private val trackSelector: DefaultTrackSelector
    private var currentSubtitleTrackIndexInternal = -1

    init {
        trackSelector = DefaultTrackSelector(context)
        exoPlayer = ExoPlayer.Builder(context)
            .setTrackSelector(trackSelector)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .build()

        exoPlayer.addListener(this)
        exoPlayer.addAnalyticsListener(EventLogger(logTag))

        if (channelSamples.isNotEmpty()) {
            preparePlayer()
        } else {
            if (!hasReportedFatalError) {
                onError("No hay fuentes de video disponibles para este canal.")
                hasReportedFatalError = true
            }
        }
    }

    private fun preparePlayer() {
        if (hasReportedFatalError) {
            return
        }
        if (currentUriIndex < 0 || currentUriIndex >= channelSamples.size) {
            if (!hasReportedFatalError) {
                val channelName = channelSamples.firstOrNull()?.name ?: "este canal"
                onError("Error interno: Índice de fuente inválido para '$channelName'.")
                hasReportedFatalError = true
            }
            return
        }
        currentSubtitleTrackIndexInternal = -1
        onPlaybackError(null)

        val currentSample = channelSamples[currentUriIndex]
        val currentUri = currentSample.uri

        try {
            val mediaSource = buildMediaSource(currentSample)
            exoPlayer.stop()
            exoPlayer.setMediaSource(mediaSource, true)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        } catch (e: Exception) {
            handlePlayerError("Error preparando fuente (${e.javaClass.simpleName})")
        }
    }

    private fun buildMediaSource(sample: ChannelSample): MediaSource {
        val mediaItemBuilder = MediaItem.Builder().setUri(sample.uri)

        val specificDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)

        val userAgentToUse = sample.userAgent?.takeIf { it.isNotBlank() } ?: defaultUserAgent
        specificDataSourceFactory.setUserAgent(userAgentToUse)

        val requestHeaders = mutableMapOf<String, String>()
        sample.referer?.takeIf { it.isNotBlank() }?.let { requestHeaders["Referer"] = it }

        if (requestHeaders.isNotEmpty()) {
            specificDataSourceFactory.setDefaultRequestProperties(requestHeaders)
        }

        var drmSessionManagerProvider: DrmSessionManagerProvider? = null
        if (sample.drmScheme != null && sample.drmLicenseUri != null) {
            val drmSchemeUuid = when (sample.drmScheme.lowercase()) {
                "widevine" -> C.WIDEVINE_UUID
                "clearkey" -> C.CLEARKEY_UUID
                else -> null
            }
            if (drmSchemeUuid != null) {
                val drmHttpDataSourceFactory = DefaultHttpDataSource.Factory().setUserAgent(userAgentToUse)
                if(requestHeaders.isNotEmpty()) drmHttpDataSourceFactory.setDefaultRequestProperties(requestHeaders)

                mediaItemBuilder.setDrmConfiguration(
                    MediaItem.DrmConfiguration.Builder(drmSchemeUuid)
                        .setLicenseUri(sample.drmLicenseUri)
                        .build()
                )
                val provider = DefaultDrmSessionManagerProvider()
                provider.setDrmHttpDataSourceFactory(drmHttpDataSourceFactory)
                drmSessionManagerProvider = provider
            }
        }

        val contentType = Util.inferContentType(sample.uri)

        val mediaSourceFactory: MediaSource.Factory = when (contentType) {
            C.CONTENT_TYPE_DASH -> DashMediaSource.Factory(specificDataSourceFactory)
            C.CONTENT_TYPE_HLS -> HlsMediaSource.Factory(specificDataSourceFactory)
            C.CONTENT_TYPE_SS -> SsMediaSource.Factory(specificDataSourceFactory)
            else -> ProgressiveMediaSource.Factory(specificDataSourceFactory)
        }

        drmSessionManagerProvider?.let {
            mediaSourceFactory.setDrmSessionManagerProvider(it)
        }
        return mediaSourceFactory.createMediaSource(mediaItemBuilder.build())
    }

    override fun onPlayerError(error: PlaybackException) {
        val errorCodeName = error.errorCodeName
        handlePlayerError("Error $errorCodeName")
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        when (playbackState) {
            Player.STATE_READY -> {
                onPlaybackError(null)
            }
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
    }

    private fun handlePlayerError(baseErrorMessage: String) {
        if (hasReportedFatalError) return

        currentUriIndex++
        if (currentUriIndex < channelSamples.size) {
            val nextSourceMsg = "Fuente ${currentUriIndex}/${channelSamples.size} falló ($baseErrorMessage). Intentando fuente ${currentUriIndex + 1}..."
            onPlaybackError(nextSourceMsg)
            preparePlayer()
        } else {
            if (!hasReportedFatalError) {
                val channelName = channelSamples.firstOrNull()?.name ?: "el canal"
                val finalFatalErrorMsg = "No se pudo reproducir '$channelName' tras varios intentos."
                onPlaybackError(null)
                onError(finalFatalErrorMsg)
                hasReportedFatalError = true
            }
        }
    }

    fun switchToNextSource() {
        if (channelSamples.size <= 1) {
            Toast.makeText(context, "No hay otras fuentes para este canal", Toast.LENGTH_SHORT).show()
            return
        }
        currentUriIndex = (currentUriIndex + 1) % channelSamples.size
        onPlaybackError("Cambiando a fuente ${currentUriIndex + 1} de ${channelSamples.size}...")
        preparePlayer()
    }

    fun cycleAudioTrack(forward: Boolean) {
        if (!exoPlayer.isCommandAvailable(Player.COMMAND_GET_TRACKS)) {
            return
        }
        val currentTracks = exoPlayer.currentTracks
        val availableAudioTracks = mutableListOf<Pair<Tracks.Group, Int>>()
        for (group in currentTracks.groups) {
            if (group.type == C.TRACK_TYPE_AUDIO) {
                for (i in 0 until group.length) {
                    if (group.isTrackSupported(i)) {
                        availableAudioTracks.add(Pair(group, i))
                    }
                }
            }
        }

        if (availableAudioTracks.size <= 1) {
            Toast.makeText(context, "No hay otras pistas de audio disponibles", Toast.LENGTH_SHORT).show()
            return
        }

        var currentSelectedListIndex = -1
        for (i in availableAudioTracks.indices) {
            val (group, trackIndex) = availableAudioTracks[i]
            if (group.isTrackSelected(trackIndex)) {
                currentSelectedListIndex = i
                break
            }
        }
        if (currentSelectedListIndex == -1) currentSelectedListIndex = 0

        val nextIndex = if (forward) {
            (currentSelectedListIndex + 1) % availableAudioTracks.size
        } else {
            (currentSelectedListIndex - 1 + availableAudioTracks.size) % availableAudioTracks.size
        }

        val (nextGroup, nextTrackIndexInGroup) = availableAudioTracks[nextIndex]
        val trackOverride = TrackSelectionOverride(nextGroup.mediaTrackGroup, nextTrackIndexInGroup)

        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
            .buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .addOverride(trackOverride)
            .build()

        val selectedFormat = nextGroup.getTrackFormat(nextTrackIndexInGroup)
        val language = selectedFormat.language?.let { Locale(it).displayLanguage } ?: "Desconocido"
        val label = selectedFormat.label ?: "Pista ${nextIndex + 1}"
        val trackInfo = if (selectedFormat.label != null) label else "$language (${selectedFormat.channelCount}ch)"

        Toast.makeText(context, "Audio: $trackInfo", Toast.LENGTH_SHORT).show()
    }

    fun cycleSubtitleTrack() {
        if (!exoPlayer.isCommandAvailable(Player.COMMAND_GET_TRACKS) ||
            !exoPlayer.isCommandAvailable(Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS)) {
            Toast.makeText(context, "No se puede cambiar los subtítulos.", Toast.LENGTH_SHORT).show()
            return
        }

        val currentTracks = exoPlayer.currentTracks
        var textGroup: Tracks.Group? = null

        for (group in currentTracks.groups) {
            if (group.type == C.TRACK_TYPE_TEXT && group.isSupported) {
                textGroup = group
                break
            }
        }

        if (textGroup == null || textGroup.mediaTrackGroup.length == 0) {
            Toast.makeText(context, "Subtítulos no disponibles", Toast.LENGTH_SHORT).show()
            currentSubtitleTrackIndexInternal = -1
            val paramsBuilder = trackSelector.parameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            exoPlayer.trackSelectionParameters = paramsBuilder.build()
            return
        }

        val numberOfAvailableTextTracks = textGroup.mediaTrackGroup.length
        currentSubtitleTrackIndexInternal++

        val newParametersBuilder = trackSelector.parameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)

        if (currentSubtitleTrackIndexInternal >= numberOfAvailableTextTracks) {
            currentSubtitleTrackIndexInternal = -1
        }

        if (currentSubtitleTrackIndexInternal == -1) {
            newParametersBuilder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            Toast.makeText(context, "Subtítulos: Desactivados", Toast.LENGTH_SHORT).show()
        } else {
            newParametersBuilder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            val trackOverride = TrackSelectionOverride(textGroup.mediaTrackGroup, currentSubtitleTrackIndexInternal)
            newParametersBuilder.addOverride(trackOverride)

            val selectedFormat = textGroup.getTrackFormat(currentSubtitleTrackIndexInternal)
            val language = selectedFormat.language?.let { Locale(it).displayLanguage } ?: "Desconocido"
            val label = selectedFormat.label ?: "Pista ${currentSubtitleTrackIndexInternal + 1}"
            val trackInfo = if (selectedFormat.label != null) label else language
            Toast.makeText(context, "Subtítulos: $trackInfo", Toast.LENGTH_SHORT).show()
        }

        exoPlayer.trackSelectionParameters = newParametersBuilder.build()
    }


    private fun playbackStateToStringHelper(state: Int): String {
        return when (state) {
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> "READY"
            Player.STATE_ENDED -> "ENDED"
            else -> "UNKNOWN ($state)"
        }
    }

    fun releasePlayer() {
        try {
            exoPlayer.removeListener(this)
            exoPlayer.release()
        } catch (e: Exception) {
            Log.e(logTag, "Exception during player release", e)
        }
    }
}
