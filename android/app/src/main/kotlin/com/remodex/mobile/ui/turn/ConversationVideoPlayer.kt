package com.remodex.mobile.ui.turn

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.remodex.mobile.ui.theme.RemodexPreviewDialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.ui.PlayerView
import com.remodex.mobile.R
import com.remodex.mobile.core.model.ConversationMediaType
import com.remodex.mobile.services.*
import com.remodex.mobile.ui.LocalCodexRepository
import kotlinx.coroutines.CancellationException

private data class VideoPlaybackPlan(val uri: Uri, val reader: VideoRangeReader? = null)

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun ConversationVideoPlayer(source: String, cwd: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val repository = LocalCodexRepository.current
    val localDownloadPath = RepoMarkdownFileLink.previewPath(source)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var showDownload by remember(source,cwd) { mutableStateOf(false) }
    var attempt by remember(source,cwd) { mutableIntStateOf(0) }
    var resumePosition by remember(source,cwd) { mutableLongStateOf(0) }
    var plan by remember(source,cwd,attempt) { mutableStateOf<VideoPlaybackPlan?>(null) }
    var failure by remember(source,cwd,attempt) { mutableStateOf<Throwable?>(null) }
    fun fail(stage: String, error: Throwable) {
        failure = error
        recordFileTransferError(context,stage,source.substringBefore('?'),0,error)
    }
    LaunchedEffect(source,cwd,attempt) {
        try {
            val normalized = source.trim()
            if (normalized.startsWith("https://",true) || normalized.startsWith("http://",true)) plan = VideoPlaybackPlan(Uri.parse(normalized))
            else {
                val local = conversationImageSource(normalized) as? ConversationImageSource.Workspace
                    ?: throw FileTransferFailure("video_prepare","unsupported_source","Unsupported video source")
                val boundCwd = RepoMarkdownFileLink.previewWorkspaceCwd(local.path) ?: cwd
                val service = WorkspaceFileTransferService(repository)
                val info = service.info(local.path,boundCwd)
                val ticket = service.videoStream(info,boundCwd)
                plan = VideoPlaybackPlan(Uri.parse("remodex-video://playback"),VideoRangeReader(ticket,renew = {service.videoStream(info,boundCwd)}))
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { fail("video_prepare",error) }
    }
    val currentPlan = plan
    val player = remember(currentPlan) {
        if (currentPlan == null) null else ExoPlayer.Builder(context)
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(5000,15000,500,1000).setTargetBufferBytes(8*1024*1024).setPrioritizeTimeOverSizeThresholds(false).build())
            .build()
    }
    DisposableEffect(player,currentPlan,lifecycle) {
        var disposed = false
        val listener = object: Player.Listener {

            override fun onPlayerError(error: PlaybackException) {
                if (disposed) return
                resumePosition = player?.currentPosition ?: resumePosition
                fail("video_playback",FileTransferFailure("video_playback","media3_${error.errorCode}",error.errorCodeName,error))
            }
        }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) player?.pause() }
        lifecycle.addObserver(observer)
        if (player != null && currentPlan != null) {
            player.addListener(listener)
            player.setAudioAttributes(AudioAttributes.DEFAULT,true)
            player.setHandleAudioBecomingNoisy(true)
            val item = MediaItem.fromUri(currentPlan.uri)
            if (currentPlan.reader != null) {
                val media = ProgressiveMediaSource.Factory(DataSource.Factory {EncryptedVideoDataSource(currentPlan.reader)})
                    .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(0)).createMediaSource(item)
                player.setMediaSource(media)
            } else player.setMediaItem(item)
            player.seekTo(resumePosition)
            player.prepare()
            player.playWhenReady = true
        }
        onDispose {
            disposed = true
            lifecycle.removeObserver(observer)
            player?.removeListener(listener)
            currentPlan?.reader?.close()
            player?.release()
        }
    }
    RemodexPreviewDialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false), systemBarBackground = Color.Black) {
        Surface(Modifier.fillMaxSize(),color = Color.Black) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),verticalAlignment = Alignment.CenterVertically) {
                    Text(ConversationMediaType.filename(source),Modifier.weight(1f),color = Color.White,maxLines = 1)
                    if (localDownloadPath != null) IconButton(onClick = {player?.pause();showDownload = true}) {
                        Icon(Icons.Default.Download,stringResource(R.string.file_download_action),tint = Color.White)
                    }
                    CompositionLocalProvider(LocalContentColor provides Color.White) {
                        MediaFileActions(source, cwd, shareOnly = localDownloadPath != null, beforeAction = { player?.pause() })
                    }
                    TextButton(onClick = onDismiss) {Text(stringResource(R.string.turn_preview_close),color = Color.White)}
                }
                Box(Modifier.fillMaxWidth().weight(1f),contentAlignment = Alignment.Center) {
                    if (player != null && failure == null) AndroidView(
                        factory = { PlayerView(it).apply {
                            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                            setEnableComposeSurfaceSyncWorkaround(true)
                            keepScreenOn = true
                        } },
                        update = {it.player = player},
                        onRelease = {it.player = null;it.keepScreenOn = false},
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (failure != null) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.conversation_video_play_failed),color = Color.White)
                        Text((failure as? FileTransferFailure)?.code ?: "unknown",color = Color.LightGray)
                        TextButton(onClick = { attempt++ }) {Text(stringResource(R.string.workspace_text_preview_retry))}
                    } else if (plan == null) CircularProgressIndicator()
                }
            }
        }
    }
    if (showDownload && localDownloadPath != null) {
        val downloadService = remember(repository) {WorkspaceTextFileService(repository)}
        WorkspaceTextFilePreviewDialog(WorkspaceTextFilePreviewRequest(localDownloadPath,cwd,downloadOnly = true),downloadService,onDismiss = {showDownload = false})
    }

}
