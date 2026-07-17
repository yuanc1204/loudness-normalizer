package com.yc.loudnorm

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.FFmpegKitConfig
import com.antonkarpenko.ffmpegkit.ReturnCode
import java.io.File
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToLong

/** 原视频时间轴上的一段保留范围，左闭右开。 */
data class ClipRange(val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

/**
 * 清理越界范围，并把重叠或首尾相接的片段合并。
 * 编辑器内部暂不合并相邻片段，因为用户还需要选中刚分割出来的某一段。
 */
fun normalizeClipRanges(ranges: List<ClipRange>, durationMs: Long): List<ClipRange> {
    if (durationMs <= 0) return emptyList()
    val sorted = ranges.mapNotNull {
        val start = it.startMs.coerceIn(0L, durationMs)
        val end = it.endMs.coerceIn(0L, durationMs)
        if (end > start) ClipRange(start, end) else null
    }.sortedBy { it.startMs }
    if (sorted.isEmpty()) return emptyList()

    val merged = ArrayList<ClipRange>()
    for (range in sorted) {
        val previous = merged.lastOrNull()
        if (previous != null && range.startMs <= previous.endMs) {
            merged[merged.lastIndex] = ClipRange(previous.startMs, maxOf(previous.endMs, range.endMs))
        } else {
            merged.add(range)
        }
    }
    return merged
}

fun isFullClip(ranges: List<ClipRange>, durationMs: Long): Boolean {
    val normalized = normalizeClipRanges(ranges, durationMs)
    return normalized.size == 1 && normalized[0].startMs == 0L &&
        normalized[0].endMs == durationMs
}

/** 显示可播放、可分割和删除片段的裁剪对话框。 */
fun showClipEditorDialog(
    context: Context,
    uri: Uri,
    fileName: String,
    durationMs: Long,
    initialRanges: List<ClipRange>,
    onApply: (List<ClipRange>) -> Unit,
) {
    val dp = context.resources.displayMetrics.density
    fun Int.dp() = (this * dp).toInt()
    fun rowButton(label: String) = Button(context).apply {
        text = label
        textSize = 13f
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
    }

    var ranges = normalizeClipRanges(initialRanges, durationMs).toMutableList()
    if (ranges.isEmpty()) ranges.add(ClipRange(0L, durationMs))
    val history = ArrayDeque<List<ClipRange>>()
    var selectedIndex = 0
    var currentMs = ranges.first().startMs
    var videoReady = false
    var videoFailed = false

    val video = VideoView(context).apply {
        setBackgroundColor(Color.BLACK)
        contentDescription = "视频裁剪预览"
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.CENTER,
        )
    }
    val stillFrame = ImageView(context).apply {
        setBackgroundColor(Color.BLACK)
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = "当前时间点的视频画面"
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.CENTER,
        )
    }
    val previewStatus = TextView(context).apply {
        text = "正在载入预览…"
        textSize = 12f
        setTextColor(Color.WHITE)
        setBackgroundColor(0x99000000.toInt())
        gravity = Gravity.CENTER
        setPadding(12.dp(), 7.dp(), 12.dp(), 7.dp())
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        )
    }
    val previewBox = FrameLayout(context).apply {
        setBackgroundColor(Color.BLACK)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            220.dp(),
        )
        addView(video)
        addView(stillFrame)
        addView(previewStatus)
    }
    val timeText = TextView(context).apply {
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(0, 6.dp(), 0, 4.dp())
    }
    val timeline = SegmentTimelineView(context).apply {
        contentDescription = "裁剪时间轴，可拖动定位"
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            58.dp(),
        )
    }
    val summary = TextView(context).apply {
        textSize = 12f
        setPadding(0, 5.dp(), 0, 2.dp())
    }
    val play = rowButton("播放")
    val split = rowButton("分割")
    val delete = rowButton("删除选中段")
    val undo = rowButton("撤销")
    val reset = rowButton("全部恢复")

    val primaryControls = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        addView(play, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(split, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(delete, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.35f))
    }
    val secondaryControls = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        addView(undo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(reset, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }
    val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(12.dp(), 0, 12.dp(), 0)
        addView(previewBox)
        addView(timeText)
        addView(
            TextView(context).apply {
                text = "拖动时间轴定位。在不需要范围的两端分别点“分割”，再点蓝色片段并删除。"
                textSize = 12f
                setPadding(0, 2.dp(), 0, 5.dp())
            }
        )
        addView(timeline)
        addView(
            TextView(context).apply {
                text = "绿色＝保留　蓝色＝当前片段　暗红＝已删除"
                textSize = 11f
                gravity = Gravity.CENTER
                setPadding(0, 4.dp(), 0, 0)
            }
        )
        addView(summary)
        addView(primaryControls)
        addView(secondaryControls)
    }

    val handler = Handler(Looper.getMainLooper())
    val frameExecutor = Executors.newSingleThreadExecutor()
    val frameGeneration = AtomicInteger()
    val frameClosed = AtomicBoolean(false)
    var displayedFrame: Bitmap? = null
    var pendingFrameRequest: Runnable? = null
    var pendingFramePositionMs: Long? = null
    var frameInFlight = false
    var lastPlaybackFrameMs = -PREVIEW_FRAME_INTERVAL_MS

    /**
     * 用内置 FFmpeg 解码预览帧，不依赖部分手机会黑屏的系统抽帧器。
     * 始终只处理一个请求；拖动很快时保留最新位置，避免启动大量 FFmpeg 会话。
     */
    fun startNextFrameRequest() {
        if (frameClosed.get() || frameInFlight) return
        val requestedAt = pendingFramePositionMs ?: return
        pendingFramePositionMs = null
        val generation = frameGeneration.get()
        frameInFlight = true
        frameExecutor.execute {
            val output = File(context.cacheDir, "preview_${System.nanoTime()}.jpg")
            val bitmap = try {
                val seconds = String.format(Locale.US, "%.3f", requestedAt / 1000.0)
                val session = FFmpegKit.executeWithArguments(
                    arrayOf(
                        "-hide_banner", "-loglevel", "error", "-y",
                        "-ss", seconds,
                        "-i", FFmpegKitConfig.getSafParameterForRead(context, uri),
                        "-map", "0:v:0",
                        "-frames:v", "1",
                        "-an",
                        "-vf", "scale=720:720:force_original_aspect_ratio=decrease",
                        "-c:v", "mjpeg", "-q:v", "3",
                        "-f", "image2",
                        output.absolutePath,
                    )
                )
                if (ReturnCode.isSuccess(session.returnCode) && output.exists()) {
                    BitmapFactory.decodeFile(output.absolutePath)
                } else null
            } catch (_: Exception) {
                null
            } finally {
                output.delete()
            }
            handler.post {
                frameInFlight = false
                if (frameClosed.get() || generation != frameGeneration.get()) {
                    bitmap?.recycle()
                } else if (bitmap == null) {
                    if (displayedFrame == null) {
                        previewStatus.text = "FFmpeg 无法取得此位置画面"
                        previewStatus.visibility = View.VISIBLE
                    }
                } else {
                    val previous = displayedFrame
                    displayedFrame = bitmap
                    stillFrame.setImageBitmap(bitmap)
                    stillFrame.visibility = View.VISIBLE
                    previewStatus.visibility = View.GONE
                    if (previous !== bitmap) previous?.recycle()
                }
                if (!frameClosed.get() && pendingFramePositionMs != null) {
                    startNextFrameRequest()
                }
            }
        }
    }

    fun requestStillFrame(positionMs: Long) {
        val requestedAt = if (durationMs > 40L) {
            positionMs.coerceIn(0L, durationMs - 40L)
        } else 0L
        frameGeneration.incrementAndGet()
        pendingFramePositionMs = requestedAt
        pendingFrameRequest?.let { handler.removeCallbacks(it) }
        if (displayedFrame == null) {
            previewStatus.text = "FFmpeg 正在生成预览…"
            previewStatus.visibility = View.VISIBLE
        }
        val request = Runnable {
            if (!frameClosed.get()) startNextFrameRequest()
        }
        pendingFrameRequest = request
        handler.postDelayed(request, 80L)
    }

    fun findRangeAt(positionMs: Long): Int {
        val p = positionMs.coerceIn(0L, durationMs)
        return ranges.indexOfFirst { range ->
            p >= range.startMs && (p < range.endMs ||
                (p == durationMs && range.endMs == durationMs))
        }
    }

    fun pushHistory() {
        if (history.size >= 30) history.removeFirst()
        history.addLast(ranges.toList())
    }

    fun seekPreview(positionMs: Long) {
        currentMs = positionMs.coerceIn(0L, durationMs)
        selectedIndex = findRangeAt(currentMs)
        if (videoReady) video.seekTo(currentMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        if (!video.isPlaying) requestStillFrame(currentMs)
    }

    fun refreshUi() {
        timeline.setState(durationMs, ranges, selectedIndex, currentMs)
        val keptMs = ranges.sumOf { it.durationMs }
        val selected = ranges.getOrNull(selectedIndex)
        timeText.text = "${formatClipTime(currentMs)} / ${formatClipTime(durationMs)}"
        summary.text = buildString {
            append("保留 ${ranges.size} 段，共 ${formatClipTime(keptMs)}")
            if (selected != null) {
                append("　当前：${formatClipTime(selected.startMs)}～${formatClipTime(selected.endMs)}")
            } else {
                append("　当前位置已删除")
            }
        }
        val canSplit = selected != null &&
            currentMs - selected.startMs >= MIN_CLIP_PART_MS &&
            selected.endMs - currentMs >= MIN_CLIP_PART_MS
        split.isEnabled = canSplit
        delete.isEnabled = selected != null && ranges.size > 1
        undo.isEnabled = history.isNotEmpty()
        reset.isEnabled = ranges.size != 1 || ranges[0] != ClipRange(0L, durationMs)
        play.isEnabled = !videoFailed
        play.text = when {
            videoFailed -> "无法播放"
            video.isPlaying -> "暂停"
            else -> "播放"
        }
    }

    val updater = object : Runnable {
        override fun run() {
            if (!video.isPlaying) {
                refreshUi()
                return
            }
            val position = video.currentPosition.toLong().coerceIn(0L, durationMs)
            val index = findRangeAt(position)
            if (index >= 0) {
                currentMs = position
                selectedIndex = index
            } else {
                val next = ranges.firstOrNull { it.startMs > position }
                if (next == null) {
                    video.pause()
                    currentMs = ranges.last().endMs
                    selectedIndex = ranges.lastIndex
                } else {
                    currentMs = next.startMs
                    selectedIndex = ranges.indexOf(next)
                    video.seekTo(currentMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                }
            }
            if (!frameInFlight && pendingFramePositionMs == null &&
                (currentMs < lastPlaybackFrameMs ||
                    currentMs - lastPlaybackFrameMs >= PREVIEW_FRAME_INTERVAL_MS)
            ) {
                lastPlaybackFrameMs = currentMs
                requestStillFrame(currentMs)
            }
            refreshUi()
            if (video.isPlaying) handler.postDelayed(this, 100L)
        }
    }

    timeline.onPositionChanged = { position ->
        if (video.isPlaying) {
            video.pause()
            handler.removeCallbacks(updater)
            stillFrame.visibility = View.VISIBLE
        }
        seekPreview(position)
        refreshUi()
    }

    play.setOnClickListener {
        if (!videoReady) {
            Toast.makeText(context, "视频仍在载入，请稍候", Toast.LENGTH_SHORT).show()
            return@setOnClickListener
        }
        if (video.isPlaying) {
            currentMs = video.currentPosition.toLong().coerceIn(0L, durationMs)
            video.pause()
            handler.removeCallbacks(updater)
            stillFrame.visibility = View.VISIBLE
            requestStillFrame(currentMs)
        } else {
            val currentRange = ranges.getOrNull(findRangeAt(currentMs))
            val start = when {
                currentRange == null -> ranges.firstOrNull { it.startMs >= currentMs }?.startMs
                    ?: ranges.first().startMs
                currentMs >= currentRange.endMs - 100L ->
                    ranges.getOrNull(ranges.indexOf(currentRange) + 1)?.startMs
                        ?: ranges.first().startMs
                else -> currentMs
            }
            seekPreview(start)
            video.start()
            stillFrame.visibility = View.VISIBLE
            if (displayedFrame == null) {
                previewStatus.text = "FFmpeg 正在生成预览…"
                previewStatus.visibility = View.VISIBLE
            }
            lastPlaybackFrameMs = start - PREVIEW_FRAME_INTERVAL_MS
            handler.removeCallbacks(updater)
            handler.post(updater)
        }
        refreshUi()
    }

    split.setOnClickListener {
        val index = findRangeAt(currentMs)
        val range = ranges.getOrNull(index)
        if (range == null || currentMs - range.startMs < MIN_CLIP_PART_MS ||
            range.endMs - currentMs < MIN_CLIP_PART_MS
        ) {
            Toast.makeText(context, "请把分割点放在片段内部", Toast.LENGTH_SHORT).show()
            return@setOnClickListener
        }
        pushHistory()
        ranges[index] = ClipRange(range.startMs, currentMs)
        ranges.add(index + 1, ClipRange(currentMs, range.endMs))
        // 默认选中分割点左侧，方便在第二次分割后直接删除中间段。
        selectedIndex = index
        refreshUi()
    }

    delete.setOnClickListener {
        if (selectedIndex !in ranges.indices) {
            Toast.makeText(context, "请先点选要删除的片段", Toast.LENGTH_SHORT).show()
            return@setOnClickListener
        }
        if (ranges.size == 1) {
            Toast.makeText(context, "至少要保留一段；整段不要可在主列表点 ×", Toast.LENGTH_LONG).show()
            return@setOnClickListener
        }
        pushHistory()
        val removedAt = selectedIndex
        ranges.removeAt(removedAt)
        selectedIndex = removedAt.coerceAtMost(ranges.lastIndex)
        val nextPosition = ranges[selectedIndex].startMs
        seekPreview(nextPosition)
        refreshUi()
    }

    undo.setOnClickListener {
        if (history.isEmpty()) return@setOnClickListener
        ranges = history.removeLast().toMutableList()
        selectedIndex = findRangeAt(currentMs)
        if (selectedIndex < 0) {
            selectedIndex = ranges.indexOfFirst { it.startMs > currentMs }
                .let { if (it >= 0) it else ranges.lastIndex }
            seekPreview(ranges[selectedIndex].startMs)
        }
        refreshUi()
    }

    reset.setOnClickListener {
        if (ranges.size == 1 && ranges[0] == ClipRange(0L, durationMs)) return@setOnClickListener
        pushHistory()
        ranges = mutableListOf(ClipRange(0L, durationMs))
        selectedIndex = 0
        seekPreview(0L)
        refreshUi()
    }

    val dialog = AlertDialog.Builder(context)
        .setTitle("裁剪：$fileName")
        .setView(root)
        .setNegativeButton("取消", null)
        .setPositiveButton("完成", null)
        .create()

    video.setOnPreparedListener { player ->
        videoReady = true
        player.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT)
        video.seekTo(currentMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        requestStillFrame(currentMs)
        refreshUi()
    }
    video.setOnCompletionListener {
        currentMs = ranges.last().endMs
        selectedIndex = ranges.lastIndex
        stillFrame.visibility = View.VISIBLE
        requestStillFrame(currentMs)
        refreshUi()
    }
    video.setOnErrorListener { _, _, _ ->
        if (frameClosed.get()) return@setOnErrorListener true
        videoFailed = true
        stillFrame.visibility = View.VISIBLE
        requestStillFrame(currentMs)
        Toast.makeText(context, "动态播放不可用，已切换为逐帧预览", Toast.LENGTH_LONG).show()
        refreshUi()
        true
    }
    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val normalized = normalizeClipRanges(ranges, durationMs)
            if (normalized.isEmpty()) {
                Toast.makeText(context, "至少要保留一段视频", Toast.LENGTH_SHORT).show()
            } else {
                onApply(normalized)
                dialog.dismiss()
            }
        }
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        video.setVideoURI(uri)
        video.requestFocus()
        requestStillFrame(currentMs)
        refreshUi()
    }
    dialog.setOnDismissListener {
        frameClosed.set(true)
        frameGeneration.incrementAndGet()
        handler.removeCallbacks(updater)
        pendingFrameRequest?.let { handler.removeCallbacks(it) }
        video.stopPlayback()
        stillFrame.setImageDrawable(null)
        displayedFrame?.recycle()
        displayedFrame = null
        frameExecutor.shutdown()
    }
    dialog.show()
}

private const val MIN_CLIP_PART_MS = 250L
private const val PREVIEW_FRAME_INTERVAL_MS = 400L

private fun formatClipTime(ms: Long): String {
    val safe = ms.coerceAtLeast(0L)
    val hours = safe / 3_600_000L
    val minutes = (safe / 60_000L) % 60L
    val seconds = (safe / 1_000L) % 60L
    val tenth = ((safe % 1_000L) / 100.0).roundToLong().coerceAtMost(9L)
    return if (hours > 0) "%d:%02d:%02d.%d".format(hours, minutes, seconds, tenth)
    else "%02d:%02d.%d".format(minutes, seconds, tenth)
}

/** 可直接拖动的片段时间轴；空隙即已删除范围。 */
private class SegmentTimelineView(context: Context) : View(context) {
    var onPositionChanged: ((Long) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private var durationMs = 1L
    private var ranges: List<ClipRange> = emptyList()
    private var selectedIndex = -1
    private var positionMs = 0L

    fun setState(
        durationMs: Long,
        ranges: List<ClipRange>,
        selectedIndex: Int,
        positionMs: Long,
    ) {
        this.durationMs = durationMs.coerceAtLeast(1L)
        this.ranges = ranges.toList()
        this.selectedIndex = selectedIndex
        this.positionMs = positionMs.coerceIn(0L, this.durationMs)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val top = 7f * density
        val bottom = height - 7f * density
        val radius = 4f * density

        paint.style = Paint.Style.FILL
        paint.color = 0xFF6D2E2E.toInt()
        canvas.drawRoundRect(0f, top, width.toFloat(), bottom, radius, radius, paint)

        for ((index, range) in ranges.withIndex()) {
            val left = width * range.startMs.toFloat() / durationMs
            val right = width * range.endMs.toFloat() / durationMs
            paint.color = if (index == selectedIndex) 0xFF1976D2.toInt() else 0xFF43A047.toInt()
            canvas.drawRoundRect(left, top, right, bottom, radius, radius, paint)

            if (right - left >= 28f * density) {
                paint.color = Color.WHITE
                paint.textAlign = Paint.Align.CENTER
                paint.textSize = 12f * density
                val baseline = (top + bottom) / 2f - (paint.descent() + paint.ascent()) / 2f
                canvas.drawText("${index + 1}", (left + right) / 2f, baseline, paint)
            }
        }

        val x = width * positionMs.toFloat() / durationMs
        paint.color = 0xFFFFC107.toInt()
        paint.strokeWidth = 3f * density
        canvas.drawLine(x, 0f, x, height.toFloat(), paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(x, 5f * density, 5f * density, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || width <= 0) return false
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                updatePosition(event.x)
                true
            }
            MotionEvent.ACTION_UP -> {
                updatePosition(event.x)
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                true
            }
            else -> false
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updatePosition(x: Float) {
        val fraction = (x / width).coerceIn(0f, 1f)
        onPositionChanged?.invoke((fraction * durationMs).roundToLong())
    }
}
