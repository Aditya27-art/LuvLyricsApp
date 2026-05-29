package com.lyricflow.app.modules

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.lyricflow.app.services.PlaybackService
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MainPlayerModule : Module() {
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun definition() = ModuleDefinition {
        Name("MainPlayer")

        Events("onPlaybackStatus", "onRemoteCommand")

        OnCreate {
            PlayerBridge.onStatusUpdate = { position, duration, isPlaying, isBuffering, didJustFinish ->
                sendEvent("onPlaybackStatus", mapOf(
                    "position" to position,
                    "duration" to duration,
                    "isPlaying" to isPlaying,
                    "isBuffering" to isBuffering,
                    "didJustFinish" to didJustFinish
                ))
            }
            PlayerBridge.onRemoteCommand = { command ->
                sendEvent("onRemoteCommand", mapOf("command" to command))
            }
        }

        OnDestroy {
            PlayerBridge.onStatusUpdate = null
            PlayerBridge.onRemoteCommand = null
        }

        AsyncFunction("load") { uri: String, metadata: Map<String, String> ->
            val context = appContext.reactContext ?: throw Exception("React context not available")

            // Start foreground service
            val intent = Intent(context, PlaybackService::class.java)
            context.startForegroundService(intent)

            // Block this thread-pool thread until PlaybackService binds the player (up to 2s)
            var retries = 0
            while (PlayerBridge.getPlayer() == null && retries < 100) {
                Thread.sleep(20)
                retries++
            }

            PlayerBridge.getPlayer()?.let { player ->
                val mediaMetadata = MediaMetadata.Builder()
                    .setTitle(metadata["title"])
                    .setArtist(metadata["artist"])
                    .setAlbumTitle(metadata["album"])
                    .apply {
                        metadata["artworkUri"]?.let {
                            if (it.isNotEmpty()) setArtworkUri(Uri.parse(it))
                        }
                    }
                    .build()

                val mediaItem = MediaItem.Builder()
                    .setUri(uri)
                    .setMediaMetadata(mediaMetadata)
                    .build()

                // Dispatch to main thread and block until done so JS can call play() immediately after
                val latch = CountDownLatch(1)
                mainHandler.post {
                    player.setMediaItem(mediaItem)
                    player.prepare()
                    latch.countDown()
                }
                latch.await(5, TimeUnit.SECONDS)
            }
        }

        Function("play") {
            PlayerBridge.getPlayer()?.let { player -> mainHandler.post { player.play() } }
        }

        Function("pause") {
            PlayerBridge.getPlayer()?.let { player -> mainHandler.post { player.pause() } }
        }

        Function("seekTo") { seconds: Double ->
            PlayerBridge.getPlayer()?.let { player ->
                val ms = (seconds * 1000.0).toLong()
                mainHandler.post { player.seekTo(ms) }
            }
        }

        Function("updateMetadata") { metadata: Map<String, String> ->
            PlayerBridge.getPlayer()?.let { player ->
                mainHandler.post {
                    val currentItem = player.currentMediaItem ?: return@post
                    val updatedMetadata = MediaMetadata.Builder()
                        .setTitle(metadata["title"])
                        .setArtist(metadata["artist"])
                        .setAlbumTitle(metadata["album"])
                        .apply {
                            metadata["artworkUri"]?.let {
                                if (it.isNotEmpty()) setArtworkUri(Uri.parse(it))
                            }
                        }
                        .build()
                    val newItem = currentItem.buildUpon().setMediaMetadata(updatedMetadata).build()
                    player.replaceMediaItem(player.currentMediaItemIndex, newItem)
                }
            }
        }

        Function("destroy") {
            val context = appContext.reactContext ?: return@Function null
            val intent = Intent(context, PlaybackService::class.java)
            context.stopService(intent)
        }
    }
}
