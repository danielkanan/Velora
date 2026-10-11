package com.music.velora.ui.player

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.music.velora.data.model.Song
import com.music.velora.playback.PlaybackPosition

private const val SKIP_MS = 10_000L

private fun buildPlayerView(context: android.content.Context): PlayerView =
    PlayerView(context).apply {
        useController = false
        controllerShowTimeoutMs = 0
        controllerAutoShow = false
        setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        subtitleView?.visibility = View.GONE
        setKeepContentOnPlayerReset(true)
    }

/**
 * Renders the session player through [PlayerView], which is how Media3 expects
 * remote [androidx.media3.session.MediaController] instances to receive a surface.
 */
@Composable
fun MusicVideoSurface(
    player: Player?,
    modifier: Modifier = Modifier,
    cornerRadius: Float = 8f,
) {
    val currentPlayer by rememberUpdatedState(player)
    AndroidView(
        modifier = modifier.clip(RoundedCornerShape(cornerRadius.dp)),
        factory = { buildPlayerView(it) },
        update = { view ->
            if (view.player != currentPlayer) {
                view.player = currentPlayer
            }
        },
        onRelease = { it.player = null },
    )
}

@Composable
fun MusicVideoFullscreen(
    song: Song,
    player: Player,
    position: PlaybackPosition,
    durationMs: Long,
    isPlaying: Boolean,
    onDismiss: () -> Unit,
    onPlayPause: () -> Unit,
    onSeekFraction: (Float) -> Unit,
) {
    val scrub = rememberPlayerScrub(song.videoId, position, durationMs)
    val shown: () -> Float = { scrub.shown(position.positionMs, durationMs) }
    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = song.title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            PlayerScrubber(
                shown = shown,
                durationMs = durationMs,
                loading = false,
                mixing = false,
                transitionWindow = null,
                onScrub = { scrub.drag(it) },
                onScrubFinished = { scrub.release(onSeekFraction) },
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        player.seekTo((player.currentPosition - SKIP_MS).coerceAtLeast(0L))
                    },
                ) {
                    Icon(
                        Icons.Rounded.Replay10,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(36.dp),
                    )
                }
                IconButton(onClick = onPlayPause) {
                    Icon(
                        if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(52.dp),
                    )
                }
                IconButton(
                    onClick = {
                        val end = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                        player.seekTo((player.currentPosition + SKIP_MS).coerceAtMost(end))
                    },
                ) {
                    Icon(
                        Icons.Rounded.Forward10,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(36.dp),
                    )
                }
            }
        }

        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(8.dp)
                .background(Color.Black.copy(alpha = 0.45f), CircleShape),
        ) {
            Icon(Icons.Rounded.Close, contentDescription = null, tint = Color.White)
        }
    }
}

@Composable
fun MusicVideoExpandButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Icon(
        imageVector = Icons.Rounded.Fullscreen,
        contentDescription = null,
        tint = Color.White,
        modifier = modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(
                interactionSource = MutableInteractionSource(),
                indication = null,
                onClick = onClick,
            )
            .padding(6.dp),
    )
}
