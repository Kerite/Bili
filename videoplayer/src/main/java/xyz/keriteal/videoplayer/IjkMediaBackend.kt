package xyz.keriteal.videoplayer

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import kotlinx.coroutines.*
import tv.danmaku.ijk.media.player.IMediaPlayer
import tv.danmaku.ijk.media.player.IjkMediaPlayer
import tv.danmaku.ijk.media.player.IjkTimedText
import xyz.keriteal.videoplayer.IMediaBackend.Companion.ERROR_INIT
import xyz.keriteal.videoplayer.IMediaBackend.Companion.STATE_FINISHED
import xyz.keriteal.videoplayer.IMediaBackend.Companion.STATE_IDLE
import xyz.keriteal.videoplayer.IMediaBackend.Companion.STATE_INITIALIZED
import xyz.keriteal.videoplayer.IMediaBackend.Companion.STATE_PAUSED
import xyz.keriteal.videoplayer.IMediaBackend.Companion.STATE_PLAY_COMPLETED
import xyz.keriteal.videoplayer.IMediaBackend.Companion.STATE_PREPARED
import xyz.keriteal.videoplayer.IMediaBackend.Companion.STATE_STARTED
import xyz.keriteal.videoplayer.VideoDataSource.Companion.DATA_SOURCE_ASSETS
import xyz.keriteal.videoplayer.VideoDataSource.Companion.DATA_SOURCE_URL
import xyz.keriteal.videoplayer.utils.logd
import xyz.keriteal.videoplayer.utils.loge
import java.io.IOException
import java.lang.ref.WeakReference

class IjkMediaBackend(
    private val mediaOptions: IjkPlayerOptions = IjkPlayerOptions()
) : BaseMediaBackend<IjkPlayerOptions>(mediaOptions) {
    companion object {
        const val TAG = "IjkMediaPlayerInterface"
    }

    private var ijkMediaPlayer: IjkMediaPlayer? = null
    private val mMediaPlayerListener = InnerMediaPlayerListener()
    private var mPrepareJob: Job? = null
    private val subTag = hashCode()
    private var weakSurface: WeakReference<Surface> = WeakReference(null)

    override fun prepare() {
        val mediaOptions = mediaOptions
        prepare(mediaOptions)
    }

    /**
     * @param context context to get asset datasource
     */
    override fun prepare(mediaOptions: IjkPlayerOptions, context: Context?) {
        logd(TAG, subTag, "Prepare MediaPlayer")
        release()
        mPrepareJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                logd(TAG, subTag, "****** Preparing IjkMediaBackend ******")
                if (ijkMediaPlayer != null) {
                    stop()
                    release()
                }
                val tmpIjkMediaPlayer = IjkMediaPlayer().apply {

                    // <editor-fold defaultstate="collapsed" desc="Set Listeners">
                    logd(TAG, subTag, "****** Setting Listeners ******")
                    setOnPreparedListener(mMediaPlayerListener)
                    setOnVideoSizeChangedListener(mMediaPlayerListener)
                    setOnCompletionListener(mMediaPlayerListener)
                    setOnErrorListener(mMediaPlayerListener)
                    setOnInfoListener(mMediaPlayerListener)
                    setOnBufferingUpdateListener(mMediaPlayerListener)
                    setOnSeekCompleteListener(mMediaPlayerListener)
                    setOnTimedTextListener(mMediaPlayerListener)
                    logd(TAG, subTag, "****** Listeners Setting Finished ******")
                    // </editor-fold>

                    setAudioStreamType(AudioManager.STREAM_MUSIC)
                    prepareOptions(mediaOptions)
                }

                // <editor-fold defaultstate="collapsed" desc="Setup DataSource">
                logd(TAG, subTag, "****** Setup DataSource ******")
                val tmpVideoDataSource =
                    getDataSource() ?: throw IllegalStateException("DataSource not set")
                tmpVideoDataSource.headerMap.forEach { (headerKey, value) ->
                    logd(TAG, subTag, "Header \"$headerKey\" = \"$value\"")
                }
                when (tmpVideoDataSource.sourceType) {
                    DATA_SOURCE_URL -> {
                        withContext(Dispatchers.IO) {
                            tmpIjkMediaPlayer.setDataSource(
                                tmpVideoDataSource.currentUrl.toString(),
                                tmpVideoDataSource.headerMap
                            )
                        }
                    }
                    DATA_SOURCE_ASSETS -> {
                        loge(TAG, subTag, "IjkPlayer does not support assets play")
                    }
                }
                logd(TAG, subTag, "****** DataSource Set ******")
                // </editor-fold>

                val surface = weakSurface.get()
                if (surface != null) {
                    tmpIjkMediaPlayer.setSurface(surface)
                }
                tmpIjkMediaPlayer.setScreenOnWhilePlaying(true)
                tmpIjkMediaPlayer.prepareAsync()

                ijkMediaPlayer = tmpIjkMediaPlayer
                mState = STATE_INITIALIZED
                logd(TAG, subTag, "****** IjkMediaBacked Prepared ******")
            } catch (e: Exception) {
                submitError(ERROR_INIT, e)
            }
        }
    }

    override fun start() {
        if (ijkMediaPlayer != null) {
            if (mState in arrayOf(STATE_PREPARED, STATE_PAUSED, STATE_PLAY_COMPLETED)) {
                ijkMediaPlayer!!.start()
                mState = STATE_STARTED
            } else {
                loge(TAG, subTag, "start failed", IllegalStateException("player state is $mState"))
            }
        } else {
            Log.e(TAG, "[${hashCode()}] start failed: ijkMediaPlayer is null")
        }
    }

    override fun pause() {
        Log.d(TAG, "[${hashCode()}] Pause media player")
        if (ijkMediaPlayer == null) {
            loge(TAG, subTag, "Pause failed: ijkMediaPlayer is null")
            return
        }
        if (mState in arrayOf(STATE_PREPARED, STATE_STARTED)) {
            ijkMediaPlayer!!.pause()
            mState = STATE_PAUSED
        }
    }

    override fun resume() {
        if (ijkMediaPlayer == null) {
            loge(TAG, subTag, "Resume failed: ijkMediaPlayer is null")
        }
        if (mState in arrayOf(STATE_PAUSED)) {
            ijkMediaPlayer!!.start()
            mState = STATE_STARTED
        }
    }

    override fun reset() {
        val player = ijkMediaPlayer
        if (player != null) {
            player.reset()
            mState = STATE_IDLE
        }
    }

    /**
     * MediaInterface
     */
    override fun release() {
        if (ijkMediaPlayer != null) {
            Log.d(TAG, "[${hashCode()}] Release MediaPlayer")
            releaseAllListener()
            ijkMediaPlayer!!.release()
        } else {
            Log.d(TAG, "[${hashCode()}] MediaPlayer no need release")
        }
    }

    override fun stop() {
        if (mState in arrayOf(STATE_PREPARED, STATE_STARTED, STATE_PAUSED, STATE_PLAY_COMPLETED)) {
            ijkMediaPlayer!!.stop()
            mState = STATE_PLAY_COMPLETED
        }
    }

    override fun isPlaying(): Boolean {
        return ijkMediaPlayer!!.isPlaying
    }

    override fun seekTo(time: Long) {
        Log.d(TAG, "[${hashCode()}] Seeking to $time")
        ijkMediaPlayer!!.seekTo(time)
    }


    override fun getCurrentPosition(): Long {
        return ijkMediaPlayer!!.currentPosition
    }

    override fun getDuration(): Long {
        return if (ijkMediaPlayer == null) 0 else ijkMediaPlayer!!.duration
    }

    override fun setVolume(leftVolume: Float, rightVolume: Float) {
        ijkMediaPlayer!!.setVolume(leftVolume, rightVolume)
    }

    /**
     * MediaInterface
     */
    override fun setSpeed(speed: Float) {
        ijkMediaPlayer!!.setSpeed(speed)
    }

    override fun setSurface(surface: Surface) {
        weakSurface = WeakReference(surface)
        ijkMediaPlayer?.setSurface(surface)
    }

    override fun setDisplay(surfaceHolder: SurfaceHolder) {
        ijkMediaPlayer!!.setDisplay(surfaceHolder)
    }

    /**
     * TextureView.SurfaceTextureListener.onSurfaceTextureAvailable
     */
//    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
//
//        if (SAVED_SURFACE == null) {
//            SAVED_SURFACE = surface
//            prepare()
//        } else {
//            videoView.textureView?.setSurfaceTexture(SAVED_SURFACE!!)
//        }
//    }
//
//    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
//
//    }
//
//    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
//        return false
//    }
//
//    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
//
//    }

    // <editor-fold defaultstate="collapsed" desc="Listener for IjkMediaPlayer">
    private inner class InnerMediaPlayerListener : IMediaPlayer.OnPreparedListener,
        IMediaPlayer.OnVideoSizeChangedListener, IMediaPlayer.OnCompletionListener,
        IMediaPlayer.OnErrorListener, IMediaPlayer.OnInfoListener,
        IMediaPlayer.OnBufferingUpdateListener, IMediaPlayer.OnSeekCompleteListener,
        IMediaPlayer.OnTimedTextListener {
        override fun onPrepared(iMediaPlayer: IMediaPlayer) {
            Log.e(TAG, "[$subTag] MediaInterface prepared")
            mState = STATE_PREPARED
        }

        override fun onVideoSizeChanged(
            mediaPlayer: IMediaPlayer, width: Int, height: Int, sar_num: Int, sar_den: Int
        ) {
            submitSize(width, height)
        }

        override fun onCompletion(mp: IMediaPlayer) {
            mState = STATE_FINISHED
        }

        override fun onError(mp: IMediaPlayer, what: Int, extra: Int): Boolean {
            when (what) {
                MediaPlayer.MEDIA_ERROR_IO ->
                    notifyFrameworkError(what, extra, IOException())
                MediaPlayer.MEDIA_ERROR_TIMED_OUT ->
                    notifyFrameworkError(what, extra)
                else ->
                    notifyFrameworkError(what, extra)
            }
            return true
        }

        override fun onInfo(mp: IMediaPlayer, what: Int, extra: Int): Boolean {
            when (what) {
                MediaPlayer.MEDIA_INFO_NOT_SEEKABLE -> {
                    logd(TAG, subTag, "Info: MEDIA_INFO_NOT_SEEKABLE")
                    submitInfo(what)
                }
                MediaPlayer.MEDIA_INFO_BAD_INTERLEAVING -> {
                    logd(TAG, subTag, "Info: MEDIA_INFO_BAD_INTERLEAVING")
                    submitInfo(what)
                }
                MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START -> {
                    logd(TAG, subTag, "Info: MEDIA_INFO_VIDEO_RENDERING_START")
                    submitInfo(what)
                }
                MediaPlayer.MEDIA_INFO_BUFFERING_START -> {
                    logd(TAG, subTag, "Info: MEDIA_INFO_BUFFERING_START")
                    submitInfo(what)
                }
                MediaPlayer.MEDIA_INFO_UNSUPPORTED_SUBTITLE -> {
                    logd(TAG, subTag, "Info: MEDIA_INFO_UNSUPPORTED_SUBTITLE")
                    submitInfo(what)
                }
                MediaPlayer.MEDIA_INFO_UNKNOWN -> {
                    logd(TAG, subTag, "Info: Unknown | code: $what | extra: $extra")
                    submitInfo(what)
                }
            }
            submitInfo(what)
            return true
        }

        override fun onBufferingUpdate(mp: IMediaPlayer, percent: Int) {
            notifyBufferingUpdate(percent)
        }

        override fun onSeekComplete(mp: IMediaPlayer) {
            notifySeek()
        }

        override fun onTimedText(mp: IMediaPlayer, text: IjkTimedText?) {

        }
    }
    // </editor-fold>

    private fun IjkMediaPlayer.setupHeaders(headerMap: Map<String, String>) {
        for ((key, value) in headerMap) {
            this.setOption(
                IjkMediaPlayer.OPT_CATEGORY_FORMAT,
                key, value
            )
        }
    }

    private fun IjkMediaPlayer.prepareOptions(mediaOptions: IjkPlayerOptions) {
        ////1????????? 0?????????
        setOption(
            IjkMediaPlayer.OPT_CATEGORY_PLAYER,
            "mediacodec",
            when (mediaOptions.mediaCodec) {
                MediaOptions.MediaCodec.SOFTWARE -> 0
                MediaOptions.MediaCodec.HARDWARE -> 1
            }
        )
        setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", 1)
        setOption(
            IjkMediaPlayer.OPT_CATEGORY_PLAYER,
            "mediacodec-handle-resolution-change",
            1
        )
        //??????opensles????????????java????????????native???
        setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "opensles", 0)
        //????????????
        setOption(
            IjkMediaPlayer.OPT_CATEGORY_PLAYER,
            "overlay-format",
            IjkMediaPlayer.SDL_FCC_RV32.toLong()
        )
        //???????????????-1~120??????CPU?????????????????????????????????????????????????????????
        setOption(
            IjkMediaPlayer.OPT_CATEGORY_PLAYER,
            "framedrop",
            mediaOptions.frameDrop
        )
        //0?????????????????????,1?????????????????????????????????????????????
        setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 0)
        ////????????????
        setOption(
            IjkMediaPlayer.OPT_CATEGORY_FORMAT,
            "http-detect-range-support",
            0
        )
        //??????????????????????????????: 0?????????????????????????????????????????????48?????????????????????????????????????????????
        setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "skip_loop_filter", 48)
        //??????????????????,??????kb
        setOption(
            IjkMediaPlayer.OPT_CATEGORY_PLAYER,
            "max-buffer-size",
            (1024 * 1024).toLong()
        )
        //???????????????SeekTo?????????????????????????????????????????????????????????????????????????????????????????????????????????FFMPEG???????????????????????????????????????seek?????????????????????????????????????????????????????????????????????i ????????????
        setOption(
            IjkMediaPlayer.OPT_CATEGORY_PLAYER,
            "enable-accurate-seek",
            1
        )
        //????????????
        setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect", 1)
        //http?????????https
        setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "dns_cache_clear", 1)
        //??????seekTo????????????seek????????????????????????
        setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "fflags", "fastseek")
        //1?????????????????? 0?????????????????????
        setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "soundtouch", 1)
        //??????????????????Size????????????1M, ??????????????????????????????
        setOption(
            IjkMediaPlayer.OPT_CATEGORY_FORMAT,
            "probesize",
            (1024 * 10).toLong()
        )
    }
}