@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.yc.loudnorm

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 使用系统 MediaCodec 和 OpenGL 合成视频画面。音频由 VideoProcessor 独立处理，
 * 避免为响度扫描先生成一份带音频的完整中间视频。
 */
class FastVideoComposer(context: Context) {
    data class Result(val success: Boolean, val error: String? = null)

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var activeTransformer: Transformer? = null

    fun cancel() {
        mainHandler.post {
            activeTransformer?.cancel()
            activeTransformer = null
        }
    }

    fun render(
        inputs: List<File>,
        rangesByInput: List<List<ClipRange>>,
        width: Int,
        height: Int,
        videoBitrate: Int,
        coverPositionMs: Long?,
        output: File,
        isCancelled: () -> Boolean,
        onProgress: (Double) -> Unit,
    ): Result {
        require(inputs.size == rangesByInput.size)
        output.delete()
        val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)

        mainHandler.post {
            if (isCancelled()) {
                done.countDown()
                return@post
            }
            try {
                val clips = buildList {
                    if (coverPositionMs != null) {
                        val coverClip = MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(coverPositionMs)
                            .setEndPositionMs(coverPositionMs + VIDEO_COVER_LEAD_MS)
                            .build()
                        val coverItem = MediaItem.Builder()
                            .setUri(Uri.fromFile(inputs.first()))
                            .setClippingConfiguration(coverClip)
                            .build()
                        val coverPresentation = Presentation.createForWidthAndHeight(
                            width,
                            height,
                            Presentation.LAYOUT_SCALE_TO_FIT,
                        )
                        add(
                            EditedMediaItem.Builder(coverItem)
                                .setRemoveAudio(true)
                                .setEffects(
                                    Effects(
                                        emptyList(),
                                        listOf<Effect>(coverPresentation),
                                    )
                                )
                                .build()
                        )
                    }
                    for ((inputIndex, ranges) in rangesByInput.withIndex()) {
                        for (range in ranges) {
                            val clipping = MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(range.startMs)
                                .setEndPositionMs(range.endMs)
                                .build()
                            val mediaItem = MediaItem.Builder()
                                .setUri(Uri.fromFile(inputs[inputIndex]))
                                .setClippingConfiguration(clipping)
                                .build()
                            val presentation = Presentation.createForWidthAndHeight(
                                width,
                                height,
                                Presentation.LAYOUT_SCALE_TO_FIT,
                            )
                            val effects = Effects(emptyList(), listOf<Effect>(presentation))
                            add(
                                EditedMediaItem.Builder(mediaItem)
                                    .setRemoveAudio(true)
                                    .setEffects(effects)
                                    .build()
                            )
                        }
                    }
                }
                require(clips.isNotEmpty()) { "没有可保留的视频片段" }

                val sequence = EditedMediaItemSequence(clips)
                val composition = Composition.Builder(sequence).build()

                val encoderSettings = VideoEncoderSettings.Builder()
                    .setBitrate(videoBitrate.coerceIn(500_000, 30_000_000))
                    .build()
                val encoderFactory = DefaultEncoderFactory.Builder(appContext)
                    .setRequestedVideoEncoderSettings(encoderSettings)
                    .build()

                lateinit var transformer: Transformer
                transformer = Transformer.Builder(appContext)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setEncoderFactory(encoderFactory)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(
                            composition: Composition,
                            exportResult: ExportResult,
                        ) {
                            if (activeTransformer === transformer) activeTransformer = null
                            onProgress(1.0)
                            done.countDown()
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException,
                        ) {
                            if (activeTransformer === transformer) activeTransformer = null
                            failure.set(exportException)
                            done.countDown()
                        }
                    })
                    .build()
                activeTransformer = transformer
                transformer.start(composition, output.absolutePath)

                val holder = ProgressHolder()
                mainHandler.post(object : Runnable {
                    override fun run() {
                        if (done.count == 0L || activeTransformer !== transformer) return
                        if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                            onProgress(holder.progress / 100.0)
                        }
                        mainHandler.postDelayed(this, 500L)
                    }
                })
            } catch (e: Throwable) {
                activeTransformer = null
                failure.set(e)
                done.countDown()
            }
        }

        while (!done.await(250L, TimeUnit.MILLISECONDS)) {
            if (isCancelled()) {
                mainHandler.post {
                    activeTransformer?.cancel()
                    activeTransformer = null
                    done.countDown()
                }
            }
        }
        if (isCancelled()) {
            output.delete()
            return Result(false, "处理已取消")
        }
        val error = failure.get()
        if (error != null || !output.exists() || output.length() == 0L) {
            output.delete()
            return Result(false, error?.message ?: "硬件合成没有生成有效文件")
        }
        return Result(true)
    }
}
