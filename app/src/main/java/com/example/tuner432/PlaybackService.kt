package com.example.tuner432

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import java.util.concurrent.Executors

/**
 * Usługa w tle: MediaSession, pitch 432/440, efekt 8D, tytuły (ICY + API ZPR),
 * Bluetooth auto-pauza/wznawianie ORAZ odporne wznawianie streamu (watchdog).
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var injecting: MetadataInjectingPlayer
    private var exoPlayer: ExoPlayer? = null
    private lateinit var audioManager: AudioManager

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pollExecutor = Executors.newSingleThreadExecutor()
    private var currentNpId: Int = -1
    private var currentStation: String = ""

    private var resumeOnReconnect = false
    private var netRetries = 0

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                val p = mediaSession?.player ?: return
                if (p.isPlaying) {
                    p.pause()
                    resumeOnReconnect = true
                }
            }
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) {
            if (added == null) return
            val bt = added.any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
            if (bt && resumeOnReconnect) {
                mediaSession?.player?.play()
                resumeOnReconnect = false
            }
        }
    }

    /** Świeży restart bieżącego streamu - tak jak ręczna zmiana stacji (co DZIAŁA). */
    private fun restartCurrent() {
        val p = exoPlayer ?: return
        try {
            if (p.mediaItemCount == 0) return
            val idx = p.currentMediaItemIndex
            val items = (0 until p.mediaItemCount).map { p.getMediaItemAt(it) }
            p.setMediaItems(items, idx, C.TIME_UNSET)
            p.prepare()
            p.play()
        } catch (_: Exception) {}
    }

    /** Gdy buforowanie wisi za długo (np. ESKA) - wymuś świeży restart. */
    private val bufferingWatchdog = Runnable {
        val p = exoPlayer ?: return@Runnable
        if (p.playWhenReady && p.playbackState == Player.STATE_BUFFERING) {
            restartCurrent()
        }
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            val npId = currentNpId
            if (npId <= 0) return
            pollExecutor.execute {
                val track = NowPlayingProvider.fetch(npId)
                if (track != null) {
                    mainHandler.post {
                        if (currentNpId == npId) {
                            val b = MediaMetadata.Builder()
                                .setTitle(track.title)
                                .setArtist(track.artist)
                                .setStation(currentStation)
                            track.artUrl?.let { b.setArtworkUri(Uri.parse(it)) }
                            injecting.setNowPlaying(b.build())
                        }
                    }
                }
            }
            mainHandler.postDelayed(this, 8_000)
        }
    }

    override fun onCreate() {
        super.onCreate()

        val attrs = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Kamerton432/1.0")
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(mapOf("Icy-MetaData" to "1"))

        val renderers = object : androidx.media3.exoplayer.DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): androidx.media3.exoplayer.audio.AudioSink {
                return androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(EightDAudioProcessor(), HallReverbProcessor()))
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
            }
        }

        val exo = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .setAudioAttributes(attrs, true)
            .build()
        exo.playbackParameters = PlaybackParameters(1f, 432f / 440f)
        exoPlayer = exo

        exo.addListener(object : Player.Listener {
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) = updatePoller(item)
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) stopPoller() else updatePoller(exo.currentMediaItem)
            }
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        netRetries = 0
                        mainHandler.removeCallbacks(bufferingWatchdog)
                    }
                    Player.STATE_BUFFERING -> {
                        mainHandler.removeCallbacks(bufferingWatchdog)
                        mainHandler.postDelayed(bufferingWatchdog, 12_000)
                    }
                    Player.STATE_IDLE -> {
                        resumeOnReconnect = false
                        mainHandler.removeCallbacks(bufferingWatchdog)
                    }
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                if (exo.playWhenReady && error.errorCode in 2000..2999 && netRetries < 30) {
                    netRetries++
                    mainHandler.postDelayed({ restartCurrent() }, 2000)
                }
            }
        })

        injecting = MetadataInjectingPlayer(exo)
        mediaSession = MediaSession.Builder(this, injecting).build()

        registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler)
    }

    private fun updatePoller(item: MediaItem?) {
        val extras = item?.mediaMetadata?.extras
        val npId = extras?.getInt("npId", -1) ?: -1
        currentStation = item?.mediaMetadata?.station?.toString()
            ?: item?.mediaMetadata?.title?.toString() ?: ""
        if (npId > 0) {
            if (currentNpId != npId) {
                currentNpId = npId
                mainHandler.removeCallbacks(pollRunnable)
                mainHandler.post(pollRunnable)
            }
        } else {
            stopPoller()
        }
    }

    private fun stopPoller() {
        currentNpId = -1
        mainHandler.removeCallbacks(pollRunnable)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        stopPoller()
        mainHandler.removeCallbacks(bufferingWatchdog)
        pollExecutor.shutdown()
        try { unregisterReceiver(noisyReceiver) } catch (_: Exception) {}
        if (::audioManager.isInitialized) audioManager.unregisterAudioDeviceCallback(deviceCallback)
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        exoPlayer = null
        super.onDestroy()
    }
}
