package com.yc.loudnorm

import android.content.ContentValues
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.FFmpegKitConfig
import com.antonkarpenko.ffmpegkit.FFmpegSession
import com.antonkarpenko.ffmpegkit.ReturnCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/** 不依赖 Activity 的单个输入快照，可安全交给前台服务持有。 */
data class ProcessingInput(
    val uriText: String,
    val name: String,
    val durationMs: Long,
    val clipRanges: List<ClipRange>?,
    val inConcat: Boolean,
) {
    val uri: Uri get() = Uri.parse(uriText)
}

/** 一批后台任务的完整参数。Intent 只携带这份轻量 JSON，不再携带 Activity 闭包。 */
data class ProcessingRequest(
    val runId: Long,
    val target: Double,
    val strength: Double,
    val repair: Boolean,
    val fastMode: Boolean,
    val files: List<ProcessingInput>,
) {
    fun toJson(): String = JSONObject().apply {
        put("runId", runId)
        put("target", target)
        put("strength", strength)
        put("repair", repair)
        put("fastMode", fastMode)
        put("files", JSONArray().apply {
            for (file in files) {
                put(JSONObject().apply {
                    put("uri", file.uriText)
                    put("name", file.name)
                    put("durationMs", file.durationMs)
                    put("inConcat", file.inConcat)
                    file.clipRanges?.let { ranges ->
                        put("clipRanges", JSONArray().apply {
                            ranges.forEach { range ->
                                put(JSONArray().put(range.startMs).put(range.endMs))
                            }
                        })
                    }
                })
            }
        })
    }.toString()

    companion object {
        fun fromJson(text: String?): ProcessingRequest? {
            if (text.isNullOrBlank()) return null
            return try {
                val root = JSONObject(text)
                val array = root.getJSONArray("files")
                val files = ArrayList<ProcessingInput>(array.length())
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val ranges = item.optJSONArray("clipRanges")?.let { encoded ->
                        buildList {
                            for (j in 0 until encoded.length()) {
                                val pair = encoded.getJSONArray(j)
                                add(ClipRange(pair.getLong(0), pair.getLong(1)))
                            }
                        }
                    }
                    files.add(
                        ProcessingInput(
                            uriText = item.getString("uri"),
                            name = item.getString("name"),
                            durationMs = item.optLong("durationMs", 0L),
                            clipRanges = ranges,
                            inConcat = item.optBoolean("inConcat", false),
                        )
                    )
                }
                ProcessingRequest(
                    runId = root.getLong("runId"),
                    target = root.getDouble("target"),
                    strength = root.getDouble("strength"),
                    repair = root.getBoolean("repair"),
                    fastMode = root.optBoolean("fastMode", false),
                    files = files,
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

data class BatchResult(
    val success: Boolean,
    val notificationText: String,
    val cancelled: Boolean = false,
)

interface ProcessingCallbacks {
    fun log(text: String)
    fun stage(text: String)
    fun progress(percent: Int)
}

/**
 * 只依赖 application Context 的媒体处理器。前台服务拥有它的生命周期，
 * Activity 退出或重建不会再让正在运行的任务持有旧界面。
 */
class VideoProcessor(context: Context, private val callbacks: ProcessingCallbacks) {
    private val appContext = context.applicationContext
    private val contentResolver = appContext.contentResolver
    private val cacheDir = appContext.cacheDir
    private val cancelRequested = AtomicBoolean(false)
    private val activeSessionIds = ConcurrentHashMap.newKeySet<Long>()
    private val preciseVideoEncodeLock = java.util.concurrent.Semaphore(1)
    private val durationCache = ConcurrentHashMap<String, Long>()

    private class ProcessingCanceledException : RuntimeException()

    private class JobUi(
        val log: (String) -> Unit,
        val stage: (String) -> Unit,
        val progress: (Double) -> Unit,
    )

    private class Job(val title: String, val weightMs: Long, val run: (JobUi) -> Boolean)

    fun cancel() {
        if (!cancelRequested.compareAndSet(false, true)) return
        callbacks.stage("正在取消并清理…")
        activeSessionIds.toList().forEach(FFmpegKit::cancel)
    }

    suspend fun process(request: ProcessingRequest): BatchResult = coroutineScope {
        val t0 = System.currentTimeMillis()
        val doConcat = request.files.count { it.inConcat } >= 2
        val concatFiles = if (doConcat) request.files.filter { it.inConcat } else emptyList()
        val singleFiles = if (doConcat) request.files.filter { !it.inConcat } else request.files

        val jobs = ArrayList<Job>()
        if (doConcat) {
            val weight = concatFiles.sumOf { effectiveDurationMs(it).coerceAtLeast(1L) }
            jobs.add(Job("拼接 ${concatFiles.size} 个视频", weight) { ui ->
                concatOne(
                    concatFiles, request.repair, request.target, request.strength,
                    request.fastMode, ui,
                )
            })
        }
        for (file in singleFiles) {
            val weight = effectiveDurationMs(file).coerceAtLeast(1L)
            jobs.add(Job(file.name, weight) { ui ->
                if (request.repair) repairOne(file, request.fastMode, ui)
                else processOne(file, request.target, request.strength, request.fastMode, ui)
            })
        }
        if (jobs.isEmpty()) return@coroutineScope BatchResult(false, "没有可处理的视频")

        val totalDuration = jobs.sumOf { it.weightMs }.coerceAtLeast(1L).toDouble()
        val fractions = DoubleArray(jobs.size)
        val progressLock = Any()
        val resultLogLock = Any()
        var resultBlocks = 0
        var highestOverall = 0
        fun updateOverall(index: Int, fraction: Double) {
            val percent = synchronized(progressLock) {
                fractions[index] = maxOf(fractions[index], fraction.coerceIn(0.0, 1.0))
                val calculated = (
                    jobs.indices.sumOf { fractions[it] * jobs[it].weightMs } / totalDuration * 100
                ).toInt()
                highestOverall = maxOf(highestOverall, calculated)
                highestOverall
            }
            callbacks.progress(percent)
        }

        val single = jobs.size == 1
        if (!single) callbacks.stage("并行处理中（最多 3 个同时）…")
        val semaphore = Semaphore(3)
        val results = jobs.mapIndexed { index, job ->
            async {
                semaphore.withPermit {
                    val buffer = StringBuilder()
                    val ui = JobUi(
                        log = if (single) callbacks::log
                        else { text -> synchronized(buffer) { buffer.appendLine(text); Unit } },
                        stage = if (single) callbacks::stage else { _ -> },
                        progress = { fraction -> updateOverall(index, fraction) },
                    )
                    if (single) callbacks.log("【${job.title}】")
                    val startedAt = System.currentTimeMillis()
                    val succeeded = try {
                        throwIfCancelled()
                        val result = job.run(ui)
                        if (result) ui.log("  耗时 ${elapsed(startedAt)}")
                        result
                    } catch (_: ProcessingCanceledException) {
                        false
                    } catch (e: Exception) {
                        ui.log("  失败：${e.message ?: "未知错误"}")
                        false
                    }
                    if (!cancelRequested.get()) updateOverall(index, 1.0)
                    if (!single && !cancelRequested.get()) {
                        synchronized(resultLogLock) {
                            if (resultBlocks > 0) callbacks.log("")
                            resultBlocks++
                            callbacks.log("【${job.title}】(${index + 1}/${jobs.size})")
                            callbacks.log(buffer.toString().trimEnd())
                        }
                    }
                    succeeded
                }
            }
        }.awaitAll()

        if (cancelRequested.get()) {
            callbacks.log("")
            callbacks.log("已取消处理，未完成的成品和缓存已删除。")
            BatchResult(false, "处理已取消", cancelled = true)
        } else {
            val ok = results.count { it }
            callbacks.log("")
            callbacks.log("全部完成：成功 $ok 个，失败 ${jobs.size - ok} 个，总耗时 ${elapsed(t0)}。")
            if (ok > 0) callbacks.log("成品在相册（或文件管理器）的 Movies/响度均衡 文件夹里。")
            BatchResult(ok > 0, "成功 $ok 个，失败 ${jobs.size - ok} 个")
        }
    }

    private fun throwIfCancelled() {
        if (cancelRequested.get()) throw ProcessingCanceledException()
    }

    private fun copyWithCancellation(input: java.io.InputStream, output: java.io.OutputStream) {
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            throwIfCancelled()
            val count = input.read(buffer)
            if (count < 0) return
            output.write(buffer, 0, count)
        }
    }

    private fun runFFmpeg(
        args: Array<String>,
        onLogLine: ((String) -> Unit)? = null,
        onTimeMs: ((Double) -> Unit)? = null,
    ): FFmpegSession {
        throwIfCancelled()
        val latch = CountDownLatch(1)
        val session = FFmpegKit.executeWithArgumentsAsync(
            args,
            { latch.countDown() },
            { log -> onLogLine?.invoke(log.message ?: "") },
            { statistics -> onTimeMs?.invoke(statistics.time.toDouble()) },
        )
        activeSessionIds.add(session.sessionId)
        if (cancelRequested.get()) FFmpegKit.cancel(session.sessionId)
        try {
            latch.await()
        } finally {
            activeSessionIds.remove(session.sessionId)
        }
        throwIfCancelled()
        return session
    }

    private fun contentSize(uri: Uri): Long = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L
        } ?: 0L
    } catch (_: Exception) {
        0L
    }

    private fun durationMs(uri: Uri): Long = try {
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(appContext, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
        }
    } catch (_: Exception) {
        0L
    }

    private fun sourceDurationMs(file: ProcessingInput): Long {
        if (file.durationMs > 0L) return file.durationMs
        return durationCache.getOrPut(file.uriText) { durationMs(file.uri) }
    }

    private fun clipRangesFor(file: ProcessingInput): List<ClipRange> {
        val duration = sourceDurationMs(file)
        return file.clipRanges ?: if (duration > 0L) listOf(ClipRange(0L, duration)) else emptyList()
    }

    private fun effectiveDurationMs(file: ProcessingInput): Long =
        file.clipRanges?.sumOf { it.durationMs } ?: sourceDurationMs(file)

    private fun elapsed(since: Long): String {
        val seconds = (System.currentTimeMillis() - since) / 1000L
        return if (seconds >= 60L) "${seconds / 60L} 分 ${seconds % 60L} 秒" else "$seconds 秒"
    }

    private fun encodeToGallery(
        outName: String,
        logFn: (String) -> Unit,
        finalName: (() -> String?)? = null,
        encode: (String) -> FFmpegSession,
    ): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, outName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/响度均衡")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val outUri = contentResolver.insert(
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values
        )
        if (outUri == null) {
            logFn("  失败：无法写入相册")
            return false
        }
        try {
            var session = encode(FFmpegKitConfig.getSafParameterForWrite(appContext, outUri))
            if (!ReturnCode.isSuccess(session.returnCode) &&
                session.getAllLogsAsString(2000).contains("non seekable")
            ) {
                val temp = File(cacheDir, "out_${System.nanoTime()}.mp4")
                try {
                    session = encode(temp.absolutePath)
                    if (ReturnCode.isSuccess(session.returnCode) && temp.exists()) {
                        contentResolver.openOutputStream(outUri)!!.use { output ->
                            temp.inputStream().use { copyWithCancellation(it, output) }
                        }
                    }
                } finally {
                    temp.delete()
                }
            }
            if (!ReturnCode.isSuccess(session.returnCode)) {
                logFn("  失败：ffmpeg 处理出错：")
                logFn("  " + session.getAllLogsAsString(2000).lines().takeLast(5).joinToString("\n  "))
                contentResolver.delete(outUri, null, null)
                return false
            }
            values.clear()
            finalName?.invoke()?.let { values.put(MediaStore.Video.Media.DISPLAY_NAME, it) }
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            contentResolver.update(outUri, values, null, null)
            return true
        } catch (e: Exception) {
            contentResolver.delete(outUri, null, null)
            throw e
        }
    }

    private data class CanvasSpec(val width: Int, val height: Int)

    private fun buildPreciseClipFilter(
        rangesByInput: List<List<ClipRange>>,
        audioByInput: List<Boolean>,
        pixelFormat: String,
        canvas: CanvasSpec?,
        normalizeAudio: Boolean,
    ): String {
        require(rangesByInput.size == audioByInput.size)
        val filters = ArrayList<String>()
        val concatInputs = StringBuilder()
        val outputHasAudio = audioByInput.any { it }
        var segment = 0
        for ((inputIndex, ranges) in rangesByInput.withIndex()) {
            for (range in ranges) {
                val start = String.format(java.util.Locale.US, "%.3f", range.startMs / 1000.0)
                val end = String.format(java.util.Locale.US, "%.3f", range.endMs / 1000.0)
                val videoFilter = buildString {
                    append("[$inputIndex:v:0]setpts=PTS-STARTPTS,trim=start=$start:end=$end,")
                    append("setpts=PTS-STARTPTS")
                    if (canvas != null) {
                        append(",scale=${canvas.width}:${canvas.height}:")
                        append("force_original_aspect_ratio=decrease:force_divisible_by=2")
                        append(",pad=${canvas.width}:${canvas.height}:")
                        append("(ow-iw)/2:(oh-ih)/2:color=black,setsar=1")
                    }
                    append(",format=$pixelFormat[v$segment]")
                }
                filters.add(videoFilter)
                concatInputs.append("[v$segment]")
                if (outputHasAudio) {
                    val audioFilter = if (audioByInput[inputIndex]) {
                        buildString {
                            append("[$inputIndex:a:0]asetpts=PTS-STARTPTS,")
                            append("atrim=start=$start:end=$end,asetpts=PTS-STARTPTS,")
                            if (normalizeAudio) {
                                append("aresample=48000:async=1:first_pts=0,")
                                append(
                                    "aformat=sample_fmts=fltp:sample_rates=48000:" +
                                        "channel_layouts=stereo"
                                )
                            } else {
                                append("aresample=async=1:first_pts=0")
                            }
                            append("[a$segment]")
                        }
                    } else {
                        val duration = String.format(
                            java.util.Locale.US, "%.3f", range.durationMs / 1000.0
                        )
                        "anullsrc=r=48000:cl=stereo,atrim=duration=$duration," +
                            "asetpts=PTS-STARTPTS[a$segment]"
                    }
                    filters.add(audioFilter)
                    concatInputs.append("[a$segment]")
                }
                segment++
            }
        }
        require(segment > 0) { "没有可保留的裁剪片段" }
        if (segment == 1) {
            filters.add("[v0]format=$pixelFormat[vout]")
            if (outputHasAudio) filters.add("[a0]anull[aout]")
        } else {
            if (outputHasAudio) {
                filters.add("${concatInputs}concat=n=$segment:v=1:a=1[vcat][aout]")
            } else {
                filters.add("${concatInputs}concat=n=$segment:v=1:a=0[vcat]")
            }
            filters.add("[vcat]format=$pixelFormat[vout]")
        }
        return filters.joinToString(";")
    }

    private fun aacArgs(fastMode: Boolean): List<String> =
        if (fastMode) listOf("-c:a", "aac", "-b:a", "192k", "-aac_coder", "fast")
        else listOf("-c:a", "aac", "-b:a", "192k")

    private fun preciseClipArgs(
        inputProviders: List<() -> String>,
        rangesByInput: List<List<ClipRange>>,
        audioByInput: List<Boolean>,
        videoBitrate: Int,
        output: File,
        hardware: Boolean,
        fastMode: Boolean,
        canvas: CanvasSpec?,
        normalizeAudio: Boolean,
    ): Array<String> {
        val outputHasAudio = audioByInput.any { it }
        val pixelFormat = if (hardware) "nv12" else "yuv420p"
        val args = ArrayList<String>()
        args.addAll(listOf("-hide_banner", "-y"))
        for (provider in inputProviders) args.addAll(listOf("-i", provider()))
        args.addAll(
            listOf(
                "-filter_complex", buildPreciseClipFilter(
                    rangesByInput, audioByInput, pixelFormat, canvas, normalizeAudio,
                ),
                "-map", "[vout]",
            )
        )
        if (outputHasAudio) args.addAll(listOf("-map", "[aout]"))
        if (hardware) {
            args.addAll(
                listOf(
                    "-c:v", "h264_mediacodec",
                    "-pix_fmt", "nv12",
                    "-b:v", videoBitrate.coerceIn(2_000_000, 30_000_000).toString(),
                    "-bitrate_mode", "vbr",
                    "-bf", "0",
                    "-g", "60",
                )
            )
        } else {
            args.addAll(
                listOf(
                    "-c:v", "mpeg4",
                    "-pix_fmt", "yuv420p",
                    "-q:v", "3",
                    "-g", "60",
                )
            )
        }
        if (outputHasAudio) args.addAll(aacArgs(fastMode)) else args.add("-an")
        args.addAll(
            listOf(
                "-map_metadata", "-1",
                "-map_chapters", "-1",
                "-avoid_negative_ts", "make_zero",
                "-max_muxing_queue_size", "2048",
                "-movflags", "+faststart",
                "-f", "mp4",
                output.absolutePath,
            )
        )
        return args.toTypedArray()
    }

    private fun runPreciseClip(
        inputProviders: List<() -> String>,
        rangesByInput: List<List<ClipRange>>,
        audioByInput: List<Boolean>,
        videoBitrate: Int,
        output: File,
        fastMode: Boolean,
        logFn: (String) -> Unit,
        onTimeMs: ((Double) -> Unit)?,
        canvas: CanvasSpec? = null,
        normalizeAudio: Boolean = false,
    ): FFmpegSession {
        require(inputProviders.size == rangesByInput.size && inputProviders.size == audioByInput.size)
        preciseVideoEncodeLock.acquire()
        try {
            output.delete()
            val hardware = runFFmpeg(
                preciseClipArgs(
                    inputProviders, rangesByInput, audioByInput, videoBitrate, output,
                    hardware = true, fastMode = fastMode, canvas = canvas,
                    normalizeAudio = normalizeAudio,
                ),
                onTimeMs = onTimeMs,
            )
            if (ReturnCode.isSuccess(hardware.returnCode)) return hardware

            output.delete()
            logFn("  当前设备的硬件裁剪编码不可用，自动改用兼容模式…")
            return runFFmpeg(
                preciseClipArgs(
                    inputProviders, rangesByInput, audioByInput, videoBitrate, output,
                    hardware = false, fastMode = fastMode, canvas = canvas,
                    normalizeAudio = normalizeAudio,
                ),
                onTimeMs = onTimeMs,
            )
        } finally {
            preciseVideoEncodeLock.release()
        }
    }

    private fun createTrimmedInput(
        file: ProcessingInput,
        fastMode: Boolean,
        ui: JobUi,
        onFraction: (Double) -> Unit,
    ): File? {
        val ranges = file.clipRanges ?: return null
        val info = probeVideo(file.uri)
        if (info == null) {
            ui.log("  失败：无法识别视频编码参数，不能裁剪")
            return null
        }
        val keptDuration = ranges.sumOf { it.durationMs }.coerceAtLeast(1L).toDouble()
        val output = File(cacheDir, "trim_${System.nanoTime()}.mp4")
        ui.stage("裁剪中：生成保留片段…")
        val session = try {
            runPreciseClip(
                listOf { FFmpegKitConfig.getSafParameterForRead(appContext, file.uri) },
                listOf(ranges),
                listOf(info.audioMime != null),
                info.bitrate,
                output,
                fastMode,
                ui.log,
                onTimeMs = { ms -> onFraction((ms / keptDuration).coerceIn(0.0, 1.0)) },
            )
        } catch (e: Exception) {
            output.delete()
            throw e
        }
        if (!ReturnCode.isSuccess(session.returnCode) || !output.exists()) {
            ui.log("  失败：裁剪视频时出错：")
            ui.log("  " + session.getAllLogsAsString(2000).lines().takeLast(6).joinToString("\n  "))
            output.delete()
            return null
        }
        onFraction(1.0)
        return output
    }

    private fun savePreparedVideo(
        input: File,
        outName: String,
        durationMs: Long,
        ui: JobUi,
    ): Boolean {
        val duration = durationMs.coerceAtLeast(1L).toDouble()
        ui.stage("保存裁剪成品…")
        return encodeToGallery(outName, ui.log) { output ->
            runFFmpeg(
                arrayOf(
                    "-hide_banner", "-y",
                    "-i", input.absolutePath,
                    "-map", "0:v?", "-map", "0:a?",
                    "-c", "copy",
                    "-f", "mp4",
                    output,
                ),
                onTimeMs = { ms -> ui.progress(ms / duration) },
            )
        }
    }

    private fun repairOne(file: ProcessingInput, fastMode: Boolean, ui: JobUi): Boolean {
        val duration = effectiveDurationMs(file).coerceAtLeast(1L)
        val base = file.name.substringBeforeLast('.')
        if (file.clipRanges == null) {
            ui.stage("修复封装（无损，不重编码）…")
            return encodeToGallery("${base}_修复.mp4", ui.log) { output ->
                runFFmpeg(
                    arrayOf(
                        "-hide_banner", "-y",
                        "-i", FFmpegKitConfig.getSafParameterForRead(appContext, file.uri),
                        "-map", "0:v?", "-map", "0:a?",
                        "-c", "copy",
                        "-f", "mp4",
                        output,
                    ),
                    onTimeMs = { ms -> ui.progress(ms / duration) },
                )
            }
        }

        val trimmed = createTrimmedInput(file, fastMode, ui) { fraction -> ui.progress(0.8 * fraction) }
            ?: return false
        return try {
            val saveUi = JobUi(ui.log, ui.stage) { fraction -> ui.progress(0.8 + 0.2 * fraction) }
            savePreparedVideo(trimmed, "${base}_修复.mp4", duration, saveUi)
        } finally {
            trimmed.delete()
        }
    }

    private fun runConcat(
        listPath: String,
        output: String,
        onTimeMs: ((Double) -> Unit)?,
    ): FFmpegSession = runFFmpeg(
        arrayOf(
            "-hide_banner", "-y",
            "-f", "concat", "-safe", "0",
            "-i", listPath,
            "-map", "0:v?", "-map", "0:a?",
            "-c", "copy",
            "-avoid_negative_ts", "make_zero",
            "-f", "mp4",
            output,
        ),
        onTimeMs = onTimeMs,
    )

    private class VidInfo(
        val mime: String,
        val width: Int,
        val height: Int,
        val rotation: Int,
        val codecData: ByteArray?,
        val bitrate: Int,
        val audioMime: String?,
        val audioRate: Int,
        val audioChannels: Int,
    ) {
        fun desc(): String {
            val video = "${mime.removePrefix("video/")} ${width}x$height" +
                if (rotation != 0) " 旋转${rotation}°" else ""
            val audio = audioMime?.let {
                "，音频 ${it.removePrefix("audio/")} ${audioRate}Hz ${audioChannels}声道"
            } ?: "，无音轨"
            return video + audio
        }
    }

    private fun probeVideo(uri: Uri): VidInfo? = try {
        contentResolver.openFileDescriptor(uri, "r")!!.use { descriptor ->
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(descriptor.fileDescriptor)
                var mime = ""
                var width = 0
                var height = 0
                var rotation = 0
                var bitrate = 8_000_000
                var codecData: ByteArray? = null
                var audioMime: String? = null
                var audioRate = 0
                var audioChannels = 0
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val trackMime = format.getString(MediaFormat.KEY_MIME) ?: continue
                    if (trackMime.startsWith("video/") && mime.isEmpty()) {
                        mime = trackMime
                        width = format.getInteger(MediaFormat.KEY_WIDTH)
                        height = format.getInteger(MediaFormat.KEY_HEIGHT)
                        rotation = try {
                            format.getInteger(MediaFormat.KEY_ROTATION)
                        } catch (_: Exception) {
                            0
                        }
                        bitrate = try {
                            format.getInteger(MediaFormat.KEY_BIT_RATE)
                        } catch (_: Exception) {
                            bitrate
                        }
                        codecData = try {
                            format.getByteBuffer("csd-0")?.let { buffer ->
                                ByteArray(buffer.remaining()).also { buffer.get(it) }
                            }
                        } catch (_: Exception) {
                            null
                        }
                    } else if (trackMime.startsWith("audio/") && audioMime == null) {
                        audioMime = trackMime
                        audioRate = try {
                            format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        } catch (_: Exception) {
                            0
                        }
                        audioChannels = try {
                            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        } catch (_: Exception) {
                            0
                        }
                    }
                }
                if (mime.isEmpty()) null
                else VidInfo(
                    mime, width, height, rotation, codecData, bitrate,
                    audioMime, audioRate, audioChannels,
                )
            } finally {
                extractor.release()
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun sameParams(first: VidInfo, second: VidInfo): Boolean =
        first.mime == second.mime &&
            first.width == second.width &&
            first.height == second.height &&
            first.rotation == second.rotation &&
            ((first.codecData == null && second.codecData == null) ||
                (first.codecData != null && second.codecData != null &&
                    first.codecData.contentEquals(second.codecData))) &&
            first.audioMime == second.audioMime &&
            first.audioRate == second.audioRate &&
            first.audioChannels == second.audioChannels

    /** 以第一个视频的显示方向作为项目画布，并确保编码器拿到偶数尺寸。 */
    private fun canvasFor(info: VidInfo): CanvasSpec {
        val quarterTurn = ((info.rotation % 180) + 180) % 180 != 0
        val displayWidth = if (quarterTurn) info.height else info.width
        val displayHeight = if (quarterTurn) info.width else info.height
        fun even(value: Int) = (value.coerceAtLeast(2) / 2) * 2
        return CanvasSpec(even(displayWidth), even(displayHeight))
    }

    private fun concatOne(
        files: List<ProcessingInput>,
        repair: Boolean,
        target: Double,
        strength: Double,
        fastMode: Boolean,
        ui: JobUi,
    ): Boolean {
        val totalDuration = files.sumOf { effectiveDurationMs(it) }.coerceAtLeast(1L).toDouble()
        val hasClipEdits = files.any { it.clipRanges != null }
        val outBase = files.first().name.substringBeforeLast('.') + "_等${files.size}个拼接"
        val stamp = System.currentTimeMillis()
        val listFile = File(cacheDir, "concat_$stamp.txt")
        val tempCopies = mutableListOf<File>()
        try {
            val infos = files.map { probeVideo(it.uri) }
            if (infos.any { it == null }) {
                ui.log("  失败：无法识别视频编码参数")
                for ((index, file) in files.withIndex()) {
                    if (infos[index] == null) ui.log("    ${file.name}：无法识别")
                }
                return false
            }
            val resolvedInfos = infos.filterNotNull()
            val reference = resolvedInfos.first()
            val canLosslessConcat = resolvedInfos.all { sameParams(reference, it) }
            val needsRender = hasClipEdits || !canLosslessConcat
            val canvas = if (canLosslessConcat) null else canvasFor(reference)
            if (!canLosslessConcat) {
                ui.log(
                    "  检测到分辨率或编码参数不同，将统一为 " +
                        "${canvas!!.width}x${canvas.height} 后兼容拼接。"
                )
                ui.log("  画面会等比缩放，空余位置补黑边，不会拉伸或裁切。")
                for ((index, file) in files.withIndex()) {
                    ui.log("    ${file.name}：${resolvedInfos[index].desc()}")
                }
            }

            ui.stage("准备中：复制视频到缓存…")
            val copyWeight = if (repair) 0.10 else 0.05
            val copyTotal = files.sumOf { contentSize(it.uri) }
            var copiedBytes = 0L
            val copyBuffer = ByteArray(1024 * 1024)
            for ((index, file) in files.withIndex()) {
                val temp = File(cacheDir, "cat_${stamp}_$index.mp4")
                tempCopies.add(temp)
                contentResolver.openInputStream(file.uri)!!.use { input ->
                    temp.outputStream().buffered().use { output ->
                        while (true) {
                            throwIfCancelled()
                            val count = input.read(copyBuffer)
                            if (count < 0) break
                            output.write(copyBuffer, 0, count)
                            copiedBytes += count
                            if (copyTotal > 0L) {
                                ui.progress(copyWeight * copiedBytes / copyTotal)
                            }
                        }
                    }
                }
            }

            if (needsRender) {
                val merged = File(cacheDir, "merged_clip_$stamp.mp4")
                try {
                    ui.stage(
                        if (canLosslessConcat) "裁剪并拼接保留片段…"
                        else "统一画面尺寸并兼容拼接…"
                    )
                    val clipWeight = if (repair) 0.75 else 0.30
                    val session = runPreciseClip(
                        tempCopies.map { file -> ({ file.absolutePath }) },
                        files.map(::clipRangesFor),
                        resolvedInfos.map { it.audioMime != null },
                        resolvedInfos.maxOf { it.bitrate },
                        merged,
                        fastMode,
                        ui.log,
                        onTimeMs = { ms ->
                            ui.progress(copyWeight + clipWeight * ms / totalDuration)
                        },
                        canvas = canvas,
                        normalizeAudio = !canLosslessConcat,
                    )
                    if (!ReturnCode.isSuccess(session.returnCode) || !merged.exists()) {
                        ui.log("  失败：兼容拼接时出错：")
                        ui.log(
                            "  " + session.getAllLogsAsString(2000).lines()
                                .takeLast(6).joinToString("\n  ")
                        )
                        return false
                    }
                    tempCopies.forEach { it.delete() }
                    val processedBase = copyWeight + clipWeight
                    val subUi = JobUi(ui.log, ui.stage) { fraction ->
                        ui.progress(processedBase + (1.0 - processedBase) * fraction)
                    }
                    return if (repair) {
                        savePreparedVideo(merged, "$outBase.mp4", totalDuration.toLong(), subUi)
                    } else {
                        processInput(
                            { merged.absolutePath }, "$outBase.mp4", totalDuration.toLong(),
                            target, strength, fastMode, subUi,
                        )
                    }
                } finally {
                    merged.delete()
                }
            }

            listFile.writeText(tempCopies.joinToString("") { "file '${it.absolutePath}'\n" })
            ui.stage("拼接中（无损，不重新编码）…")
            if (repair) {
                return encodeToGallery("$outBase.mp4", ui.log) { output ->
                    runConcat(listFile.absolutePath, output) { ms ->
                        ui.progress(copyWeight + (1.0 - copyWeight) * ms / totalDuration)
                    }
                }
            }

            val merged = File(cacheDir, "merged_$stamp.mp4")
            try {
                val session = runConcat(listFile.absolutePath, merged.absolutePath) { ms ->
                    ui.progress(copyWeight + 0.15 * ms / totalDuration)
                }
                if (!ReturnCode.isSuccess(session.returnCode)) {
                    ui.log("  失败：拼接出错：")
                    ui.log("  " + session.getAllLogsAsString(2000).lines().takeLast(6).joinToString("\n  "))
                    return false
                }
                tempCopies.forEach { it.delete() }
                val processedBase = copyWeight + 0.15
                val subUi = JobUi(ui.log, ui.stage) { fraction ->
                    ui.progress(processedBase + (1.0 - processedBase) * fraction)
                }
                return processInput(
                    { merged.absolutePath }, "$outBase.mp4", totalDuration.toLong(),
                    target, strength, fastMode, subUi,
                )
            } finally {
                merged.delete()
            }
        } finally {
            listFile.delete()
            tempCopies.forEach { it.delete() }
        }
    }

    private fun processOne(
        file: ProcessingInput,
        target: Double,
        strength: Double,
        fastMode: Boolean,
        ui: JobUi,
    ): Boolean {
        if (file.clipRanges == null) {
            return processInput(
                { FFmpegKitConfig.getSafParameterForRead(appContext, file.uri) },
                file.name,
                sourceDurationMs(file),
                target,
                strength,
                fastMode,
                ui,
            )
        }

        val trimWeight = 0.35
        val trimmed = createTrimmedInput(file, fastMode, ui) { fraction ->
            ui.progress(trimWeight * fraction)
        } ?: return false
        return try {
            val subUi = JobUi(ui.log, ui.stage) { fraction ->
                ui.progress(trimWeight + (1.0 - trimWeight) * fraction)
            }
            processInput(
                { trimmed.absolutePath },
                file.name,
                effectiveDurationMs(file),
                target,
                strength,
                fastMode,
                subUi,
            )
        } finally {
            trimmed.delete()
        }
    }

    private fun processInput(
        input: () -> String,
        name: String,
        durationMs: Long,
        target: Double,
        strength: Double,
        fastMode: Boolean,
        ui: JobUi,
    ): Boolean {
        val duration = durationMs.coerceAtLeast(1L).toDouble()
        ui.stage("第 1 步 / 共 2 步：扫描响度…")
        val metadataFile = File(cacheDir, "scan_${System.nanoTime()}.txt")
        val metadataPath = metadataFile.absolutePath.replace("\\", "/").replace(":", "\\:")
        val points = try {
            val scan = runFFmpeg(
                arrayOf(
                    "-hide_banner", "-nostats",
                    "-i", input(),
                    "-map", "0:a:0",
                    "-af", "ebur128=peak=true:metadata=1,ametadata=mode=print:file='$metadataPath'",
                    "-f", "null", "-",
                ),
                onLogLine = { line ->
                    val match = TIME_LINE_REGEX.find(line)
                    if (match != null) {
                        ui.progress(0.2 * match.groupValues[1].toDouble() * 1000.0 / duration)
                    }
                },
            )
            if (!ReturnCode.isSuccess(scan.returnCode)) {
                ui.log("  失败：无法读取音频（文件可能没有声音轨）")
                return false
            }
            if (!metadataFile.exists()) emptyList() else Engine.parseMetadata(metadataFile.readText())
        } finally {
            metadataFile.delete()
        }
        if (points.isEmpty()) {
            ui.log("  失败：未取得响度数据")
            return false
        }

        val segments = Engine.makeSegments(points, target, strength)
        val knots = Engine.makeKnots(segments)
        val commandFile = File(cacheDir, "gain_${System.nanoTime()}.cmd")
        val volume = Engine.writeGainCmds(knots, commandFile)
        val changed = segments.filter { abs(it.g) >= 0.5 }
        ui.log("  共分 ${segments.size} 段，调整 ${changed.size} 段：")
        for (segment in changed) {
            ui.log(
                String.format(
                    java.util.Locale.US,
                    "    %.0fs~%.0fs  %+.1f dB",
                    segment.a,
                    segment.b,
                    segment.g,
                )
            )
        }
        val measured = Engine.computeMeasured(points, knots)

        val finalMetadata = File(cacheDir, "final_${System.nanoTime()}.txt")
        val finalPath = finalMetadata.absolutePath.replace("\\", "/").replace(":", "\\:")
        try {
            ui.stage("第 2 步 / 共 2 步：生成并保存（画面直接复制）…")
            val generatedAt = System.currentTimeMillis()
            val filter = Engine.buildFilter(target, volume, measured) +
                ",ebur128=metadata=1,ametadata=mode=print:file='$finalPath'"
            val base = cleanOutputBase(name)
            var finalLoudness: Double? = null
            val done = encodeToGallery(
                outName = "${base}_处理中.mp4",
                logFn = ui.log,
                finalName = {
                    finalLoudness = readFinalLoudness(finalMetadata)
                    finalLoudness?.let {
                        String.format(java.util.Locale.US, "%s_%.2fLUFS.mp4", base, it)
                    } ?: "${base}_响度未知.mp4"
                },
            ) { output ->
                val args = ArrayList<String>()
                args.addAll(
                    listOf(
                        "-hide_banner", "-y",
                        "-i", input(),
                        "-map", "0:v?", "-map", "0:a:0",
                        "-c:v", "copy",
                        "-af", filter,
                    )
                )
                args.addAll(aacArgs(fastMode))
                args.addAll(listOf("-f", "mp4", output))
                runFFmpeg(
                    args.toTypedArray(),
                    onTimeMs = { ms -> ui.progress(0.2 + 0.8 * ms / duration) },
                )
            }
            if (done) {
                finalLoudness?.let {
                    ui.log(String.format(java.util.Locale.US, "  均衡后最终响度：%.2f LUFS", it))
                }
                ui.log("  生成用时 ${elapsed(generatedAt)}")
            }
            return done
        } finally {
            commandFile.delete()
            finalMetadata.delete()
        }
    }

    private fun cleanOutputBase(name: String): String {
        val suffix = Regex(
            "_(?:均衡|[+-]?\\d+(?:\\.\\d+)?LUFS|响度未知)$",
            RegexOption.IGNORE_CASE,
        )
        var base = name.substringBeforeLast('.')
        while (true) {
            val cleaned = base.replace(suffix, "")
            if (cleaned == base) return base
            base = cleaned
        }
    }

    private fun readFinalLoudness(file: File): Double? {
        if (!file.exists()) return null
        var last: Double? = null
        file.forEachLine { line ->
            if (line.startsWith("lavfi.r128.I=")) {
                line.substring(13).trim().toDoubleOrNull()
                    ?.let { if (it.isFinite() && it > -70.0) last = it }
            }
        }
        return last
    }

    companion object {
        private val TIME_LINE_REGEX = Regex("""t:\s*([\d.]+)\s+TARGET""")
    }
}
