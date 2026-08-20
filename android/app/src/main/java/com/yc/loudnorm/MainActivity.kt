package com.yc.loudnorm

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.TextUtils
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.FFmpegKitConfig
import com.antonkarpenko.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collect
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    private lateinit var btnPick: Button
    private lateinit var btnStart: Button
    private lateinit var tvSelected: TextView
    private lateinit var tvTarget: TextView
    private lateinit var tvStrength: TextView
    private lateinit var tvStage: TextView
    private lateinit var tvLog: TextView
    private lateinit var svLog: ScrollView
    private lateinit var rvFiles: RecyclerView
    private lateinit var sbTarget: SeekBar
    private lateinit var sbStrength: SeekBar
    private lateinit var pbFile: ProgressBar
    private lateinit var cbRepair: CheckBox
    private lateinit var cbConcat: CheckBox

    // inConcat：该视频是否被选入拼接组（勾选的视频合并成一个，按列表顺序）
    private class PickedFile(val uri: Uri, var name: String, val isAudio: Boolean) {
        var inConcat = false
        var thumbnail: Bitmap? = null
        var thumbnailRequested = false
        var durationMs = 0L
        var clipRanges: List<ClipRange>? = null
        var coverPositionMs: Long? = null
        var editRevision = 0
    }

    private val picked = mutableListOf<PickedFile>()
    private var busy = false
    private val settings by lazy { getSharedPreferences("user_settings", MODE_PRIVATE) }
    private var clipEditorLoading = false
    private val cancelRequested = AtomicBoolean(false)
    // 绕开部分系统串行生成视频封面的实现；最多并行三个轻量 FFmpeg 抽帧任务。
    private val thumbnailExecutor = Executors.newFixedThreadPool(
        Runtime.getRuntime().availableProcessors().coerceIn(1, 3)
    )

    // 防止「全选拼接」总开关与每行勾选框互相触发监听造成的循环
    private var syncingConcat = false

    private val pickVideos =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNullOrEmpty()) return@registerForActivityResult
            for (u in uris) {
                if (picked.any { it.uri == u }) continue
                val name = displayName(u) ?: "媒体文件${picked.size + 1}"
                picked.add(PickedFile(u, name, isAudioUri(u, name)))
                try {
                    contentResolver.takePersistableUriPermission(
                        u,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                } catch (_: Exception) {
                }
            }
            refreshFileList()
        }

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val requestAllFilesAccess =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()) {
                startProcessing()
            } else {
                showHiddenStoragePermissionMessage()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnPick = findViewById(R.id.btnPick)
        btnStart = findViewById(R.id.btnStart)
        tvSelected = findViewById(R.id.tvSelected)
        tvTarget = findViewById(R.id.tvTarget)
        tvStrength = findViewById(R.id.tvStrength)
        tvStage = findViewById(R.id.tvStage)
        tvLog = findViewById(R.id.tvLog)
        svLog = findViewById(R.id.svLog)
        rvFiles = findViewById(R.id.rvFiles)
        rvFiles.layoutManager = LinearLayoutManager(this)
        rvFiles.adapter = fileAdapter
        touchHelper.attachToRecyclerView(rvFiles)
        sbTarget = findViewById(R.id.sbTarget)
        sbStrength = findViewById(R.id.sbStrength)
        pbFile = findViewById(R.id.pbFile)
        cbRepair = findViewById(R.id.cbRepair)
        cbConcat = findViewById(R.id.cbConcat)

        sbTarget.progress = settings.getInt("target_progress", 4).coerceIn(0, sbTarget.max)
        sbStrength.progress = settings.getInt("strength_progress", 35).coerceIn(0, sbStrength.max)
        tvTarget.text = "目标响度：${targetLufs().toInt()} LUFS"
        tvStrength.text = "均衡力度：${(strength() * 100).toInt()}%"

        sbTarget.setOnSeekBarChangeListener(simpleSeek {
            tvTarget.text = "目标响度：${targetLufs().toInt()} LUFS"
            settings.edit().putInt("target_progress", sbTarget.progress).apply()
        })
        sbStrength.setOnSeekBarChangeListener(simpleSeek {
            tvStrength.text = "均衡力度：${(strength() * 100).toInt()}%"
            settings.edit().putInt("strength_progress", sbStrength.progress).apply()
        })
        cbRepair.setOnCheckedChangeListener { _, checked ->
            sbTarget.isEnabled = !checked && !busy
            sbStrength.isEnabled = !checked && !busy
            updateStartText()
        }
        // 底部「拼接」是总开关：勾选=全选所有视频进拼接组，取消=全不选
        cbConcat.setOnCheckedChangeListener { _, checked ->
            if (syncingConcat) return@setOnCheckedChangeListener
            picked.filterNot { it.isAudio }.forEach { it.inConcat = checked }
            updateStartText()
            refreshFileList()
        }

        btnPick.setOnClickListener { pickVideos.launch(arrayOf("video/*", "audio/*")) }
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        setHelp(R.id.helpTarget, "目标响度", "成品希望达到的平均听感响度。-16 LUFS 适合大多数视频和课堂录音；数值越接近 0，声音越响。")
        setHelp(R.id.helpStrength, "均衡力度", "控制小声音段向目标响度靠近的程度。85% 能明显改善忽大忽小，同时保留一些自然起伏；100% 更平整。")
        setHelp(R.id.helpConcat, "视频拼接", "仅对视频生效。勾选后，参与拼接的视频会按列表顺序合成一个文件；可拖动调整顺序。音频不会加入视频拼接。")
        setHelp(
            R.id.helpRepair,
            "仅修复播放卡顿",
            "可修复因封装或时间轴异常造成的拖动失败、音画不同步、局部卡顿；如果原始画面数据本身已经损坏，则无法修复。通常几秒完成且画质无损。选择纯音频时不可用。",
        )
        btnStart.setOnClickListener {
            if (busy) {
                ProcessingService.cancel()
                cancelRequested.set(true)
                tvStage.text = "正在取消并清理…"
                updateStartText()
            } else {
                startProcessingWithStorageCheck()
            }
        }

        observeProcessingState()
        handleShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    override fun onDestroy() {
        thumbnailExecutor.shutdownNow()
        super.onDestroy()
    }

    /** 界面只观察服务状态；媒体任务不再持有 Activity 或调用它的方法。 */
    private fun observeProcessingState() {
        lifecycleScope.launch {
            ProcessingService.state.collect { state ->
                if (state.runId == 0L) return@collect
                cancelRequested.set(state.cancelRequested)
                if (state.running) {
                    if (!busy) setUiBusy(true)
                    pbFile.progress = state.progress
                    tvStage.text = state.stage
                    if (tvLog.text.toString() != state.log) {
                        tvLog.text = state.log
                        svLog.post { svLog.fullScroll(View.FOCUS_DOWN) }
                    }
                    updateStartText()
                    return@collect
                }

                val result = state.result ?: return@collect
                if (tvLog.text.toString() != state.log) tvLog.text = state.log
                if (!result.cancelled) {
                    picked.forEach { it.thumbnail?.recycle() }
                    picked.clear()
                }
                setUiBusy(false)
                refreshFileList()
                ProcessingService.acknowledgeResult(state.runId)
            }
        }
    }

    /** 接收从 Telegram 等 App 分享过来的音视频，直接加进待处理列表。 */
    private fun handleShareIntent(intent: Intent?) {
        intent ?: return
        @Suppress("DEPRECATION")
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND ->
                listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            Intent.ACTION_SEND_MULTIPLE ->
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                    ?.filterNotNull() ?: emptyList()
            else -> emptyList()
        }
        if (uris.isEmpty()) return
        if (busy) {
            log("正在处理中，请稍后再分享新文件。")
            return
        }
        for (u in uris) {
            if (picked.any { it.uri == u }) continue
            val name = displayName(u) ?: "分享的媒体${picked.size + 1}"
            picked.add(PickedFile(u, name, isAudioUri(u, name)))
        }
        refreshFileList()
    }

    private fun targetLufs() = -20.0 + sbTarget.progress          // 0..8 → -20..-12
    private fun strength() = (50 + sbStrength.progress) / 100.0   // 0..50 → 0.5..1.0

    /** 拼接组有效需 2 个以上视频；不足按各自单独处理。 */
    private fun willConcat() = picked.count { it.inConcat && !it.isAudio } >= 2

    private fun setHelp(viewId: Int, title: String, message: String) {
        findViewById<TextView>(viewId).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("知道了", null)
                .show()
        }
    }

    private fun updateStartText() {
        if (busy) {
            btnStart.text = if (cancelRequested.get()) "正在取消…" else "取消处理"
            btnStart.isEnabled = !cancelRequested.get()
            return
        }
        val repair = cbRepair.isChecked
        btnStart.text = when {
            willConcat() && repair -> "拼接并修复"
            willConcat() -> "拼接并均衡"
            repair -> "开始修复"
            else -> "开始处理"
        }
    }

    private fun simpleSeek(onChange: () -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) = onChange()
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {}
    }

    private class FileVH(
        row: LinearLayout,
        val handle: TextView,
        val preview: ImageView,
        val name: TextView,
        val cbCat: CheckBox,
        val del: TextView,
    ) : RecyclerView.ViewHolder(row)

    /** 已选文件列表：按住 ≡ 拖动排序（拼接顺序即列表顺序），勾「拼接」入组，✕ 删除。 */
    private val fileAdapter: RecyclerView.Adapter<FileVH> = object : RecyclerView.Adapter<FileVH>() {
        override fun getItemCount() = picked.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileVH {
            val dp = parent.resources.displayMetrics.density
            fun icon(label: String, size: Float) = TextView(parent.context).apply {
                text = label
                textSize = size
                setPadding((10 * dp).toInt(), (6 * dp).toInt(), (10 * dp).toInt(), (6 * dp).toInt())
            }
            val handle = icon("≡", 18f)
            val preview = ImageView(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams((56 * dp).toInt(), (42 * dp).toInt()).apply {
                    marginEnd = (8 * dp).toInt()
                }
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(0xFFE0E0E0.toInt())
                setImageResource(android.R.drawable.ic_media_play)
                contentDescription = "视频预览图"
            }
            val name = TextView(parent.context).apply {
                textSize = 13f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding((4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val cbCat = CheckBox(parent.context).apply {
                text = "拼接"
                textSize = 12f
            }
            val del = icon("✕", 16f)
            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT
                )
                addView(handle)
                addView(preview)
                addView(name)
                addView(cbCat)
                addView(del)
            }
            return FileVH(row, handle, preview, name, cbCat, del)
        }

        override fun onBindViewHolder(h: FileVH, position: Int) {
            val pf = picked[position]
            h.name.text = fileRowText(pf)
            h.name.isEnabled = !busy && !pf.isAudio
            h.name.isClickable = !busy && !pf.isAudio
            h.name.setTextColor(if (pf.isAudio) 0xFF424242.toInt() else 0xFF6750A4.toInt())
            h.name.setBackgroundResource(
                if (pf.isAudio) 0 else android.R.drawable.list_selector_background
            )
            h.name.contentDescription = if (pf.isAudio) pf.name else "点击修改成品文件名：${pf.name}"
            h.name.tooltipText = if (pf.isAudio) null else "点击修改成品文件名"
            h.name.setOnClickListener {
                val current = picked.getOrNull(h.bindingAdapterPosition) ?: return@setOnClickListener
                if (!busy && !current.isAudio) showRenameDialog(current)
            }
            val thumbnail = pf.thumbnail
            if (pf.isAudio) {
                h.preview.setImageResource(android.R.drawable.ic_lock_silent_mode_off)
            } else if (thumbnail != null) {
                h.preview.setImageBitmap(thumbnail)
            } else {
                h.preview.setImageResource(android.R.drawable.ic_media_play)
                requestThumbnail(pf)
            }
            h.preview.isEnabled = !busy && !pf.isAudio
            h.preview.alpha = if (busy || pf.isAudio) 0.5f else 1f
            h.preview.contentDescription = if (pf.isAudio) "音频 ${pf.name}" else "裁剪 ${pf.name}"
            h.preview.setOnClickListener {
                val current = picked.getOrNull(h.bindingAdapterPosition) ?: return@setOnClickListener
                if (!busy && !current.isAudio) showClipEditor(current)
            }
            h.handle.visibility = if (picked.size > 1) View.VISIBLE else View.GONE
            h.handle.setOnTouchListener { v, ev ->
                if (ev.actionMasked == MotionEvent.ACTION_DOWN && !busy) {
                    touchHelper.startDrag(h)
                    v.performClick()
                }
                false
            }
            // 拼接勾选框：单个视频时无意义，隐藏
            h.cbCat.visibility = if (!pf.isAudio && picked.count { !it.isAudio } > 1) View.VISIBLE else View.GONE
            h.cbCat.isEnabled = !busy
            h.cbCat.setOnCheckedChangeListener(null)
            h.cbCat.isChecked = pf.inConcat
            h.cbCat.setOnCheckedChangeListener { _, checked ->
                val p = picked.getOrNull(h.bindingAdapterPosition) ?: return@setOnCheckedChangeListener
                p.inConcat = checked
                syncMasterConcat()
                updateStartText()
            }
            h.del.setOnClickListener {
                val pos = h.bindingAdapterPosition
                if (busy || pos == RecyclerView.NO_POSITION) return@setOnClickListener
                picked.removeAt(pos).thumbnail?.recycle()
                refreshFileList()
            }
        }
    }

    /** 用独立 FFmpeg 线程池并行抽取低分辨率关键帧，系统缩略图仅作为失败兜底。 */
    private fun requestThumbnail(pf: PickedFile) {
        if (pf.isAudio) return
        if (pf.thumbnailRequested) return
        pf.thumbnailRequested = true
        val revision = pf.editRevision
        val hasEdits = pf.clipRanges != null || pf.coverPositionMs != null
        val previewAtMs = pf.coverPositionMs ?: pf.clipRanges?.firstOrNull()?.startMs ?: 0L
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                loadFfmpegThumbnail(pf.uri, previewAtMs, pf.coverPositionMs != null)
            } ?: withContext(Dispatchers.IO) {
                if (hasEdits) loadThumbnailFrame(pf.uri, previewAtMs)
                else loadSystemThumbnail(pf.uri) ?: loadThumbnailFrame(pf.uri, -1L)
            }
            if (revision != pf.editRevision || picked.indexOf(pf) < 0) {
                bitmap?.recycle()
                return@launch
            }
            if (bitmap != null) {
                pf.thumbnail = bitmap
                val position = picked.indexOf(pf)
                if (position >= 0) fileAdapter.notifyItemChanged(position)
            }
        }
    }

    /** 默认只解码关键帧；用户指定封面时准确解码当前帧，避免显示成附近的黑场。 */
    private suspend fun loadFfmpegThumbnail(
        uri: Uri,
        positionMs: Long,
        exactFrame: Boolean,
    ): Bitmap? =
        suspendCancellableCoroutine { continuation ->
            val output = File(cacheDir, "thumb_${System.nanoTime()}.jpg")
            val seconds = String.format(
                java.util.Locale.US,
                "%.3f",
                positionMs.coerceAtLeast(0L) / 1000.0,
            )
            val args = buildList {
                addAll(listOf("-hide_banner", "-loglevel", "error", "-y", "-threads", "1"))
                if (!exactFrame) addAll(listOf("-skip_frame", "nokey"))
                addAll(listOf("-ss", seconds))
                if (exactFrame) add("-accurate_seek")
                addAll(
                    listOf(
                        "-i", FFmpegKitConfig.getSafParameterForRead(this@MainActivity, uri),
                        "-map", "0:v:0",
                        "-frames:v", "1",
                        "-an", "-sn",
                        "-vf", "scale=160:120:force_original_aspect_ratio=increase,crop=160:120",
                        "-c:v", "mjpeg", "-q:v", "5",
                        "-f", "image2", "-update", "1",
                        output.absolutePath,
                    )
                )
            }.toTypedArray()
            FFmpegKit.executeWithArgumentsAsync(args, { session ->
                val bitmap = if (ReturnCode.isSuccess(session.returnCode) && output.exists()) {
                    BitmapFactory.decodeFile(output.absolutePath)
                } else null
                output.delete()
                continuation.resume(bitmap) { bitmap?.recycle() }
            }, thumbnailExecutor)
        }

    /** 让文件提供器直接返回它为系统选择器生成并缓存的小尺寸封面。 */
    private fun loadSystemThumbnail(uri: Uri): Bitmap? = try {
        contentResolver.loadThumbnail(uri, Size(160, 120), null)
    } catch (_: Exception) {
        null
    }

    /** 第三方文件提供器没有封面，或裁剪后需要指定画面时才启动视频解码器。 */
    private fun loadThumbnailFrame(uri: Uri, positionMs: Long): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, uri)
            retriever.getScaledFrameAtTime(
                if (positionMs < 0L) -1L else positionMs * 1000L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                160,
                120,
            )
        } catch (_: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    private fun fileRowText(pf: PickedFile): String {
        if (pf.isAudio) return "${pf.name}\n音频"
        val title = "✎ ${pf.name}"
        val coverHint = if (pf.coverPositionMs != null) " · 已选成品封面" else ""
        val ranges = pf.clipRanges
            ?: return "$title\n点击标题可修改成品名称$coverHint"
        val kept = ranges.sumOf { it.durationMs }
        return "$title\n已裁剪：保留 ${ranges.size} 段 · ${formatShortTime(kept)}$coverHint · 点标题改名"
    }

    private fun showRenameDialog(pf: PickedFile) {
        if (busy || pf.isAudio || picked.indexOf(pf) < 0) return
        val extension = pf.name.substringAfterLast('.', "")
        val currentBase = pf.name.substringBeforeLast('.', pf.name)
        val padding = (20 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            setText(currentBase)
            selectAll()
            setSingleLine(true)
            hint = "成品名称"
            filters = arrayOf(android.text.InputFilter.LengthFilter(100))
            setPadding(padding, padding / 2, padding, padding / 2)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("修改成品名称")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val rawBase = input.text.toString()
                val newBase = rawBase.trim()
                val forbiddenIndex = rawBase.indexOfFirst { it < ' ' || it in "\\/:*?\"<>|" }
                fun showNameError(message: String, index: Int = 0) {
                    input.error = message
                    input.requestFocus()
                    val start = index.coerceIn(0, rawBase.length)
                    val end = (start + 1).coerceAtMost(rawBase.length)
                    input.setSelection(start, end)
                }
                when {
                    newBase.isEmpty() -> showNameError("名称不能为空")
                    forbiddenIndex >= 0 -> {
                        val invalid = rawBase[forbiddenIndex]
                        val label = when (invalid) {
                            '\t' -> "制表符"
                            '\n' -> "换行符"
                            '\r' -> "回车符"
                            else -> invalid.toString()
                        }
                        showNameError("不能包含符号「$label」", forbiddenIndex)
                    }
                    newBase.endsWith('.') ->
                        showNameError("名称不能以符号「.」结尾", rawBase.lastIndexOf('.'))
                    else -> {
                        pf.name = if (extension.isEmpty()) newBase else "$newBase.$extension"
                        val position = picked.indexOf(pf)
                        if (position >= 0) fileAdapter.notifyItemChanged(position)
                        dialog.dismiss()
                    }
                }
            }
        }
        dialog.show()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    }

    private fun showClipEditor(pf: PickedFile) {
        if (pf.isAudio) return
        if (clipEditorLoading) return
        clipEditorLoading = true
        lifecycleScope.launch {
            val duration = try {
                withContext(Dispatchers.IO) { sourceDurationMs(pf) }
            } finally {
                clipEditorLoading = false
            }
            if (busy || picked.indexOf(pf) < 0) return@launch
            if (duration < 500L) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("无法裁剪")
                    .setMessage("没有读到有效的视频时长，或视频太短。")
                    .setPositiveButton("知道了", null)
                    .show()
                return@launch
            }
            val initial = pf.clipRanges ?: listOf(ClipRange(0L, duration))
            showClipEditorDialog(
                this@MainActivity,
                pf.uri,
                pf.name,
                duration,
                initial,
                pf.coverPositionMs,
            ) { ranges, coverPositionMs ->
                val normalized = normalizeClipRanges(ranges, duration)
                pf.clipRanges = if (isFullClip(normalized, duration)) null else normalized
                pf.coverPositionMs = coverPositionMs
                pf.editRevision++
                pf.thumbnail?.recycle()
                pf.thumbnail = null
                pf.thumbnailRequested = false
                val position = picked.indexOf(pf)
                if (position >= 0) fileAdapter.notifyItemChanged(position)
            }
        }
    }

    /** 每行勾选变化后，把底部总开关同步为「是否全部已选」，且不反过来触发它的监听。 */
    private fun syncMasterConcat() {
        val videos = picked.filterNot { it.isAudio }
        val all = videos.size >= 2 && videos.all { it.inConcat }
        if (cbConcat.isChecked != all) {
            syncingConcat = true
            cbConcat.isChecked = all
            syncingConcat = false
        }
    }

    /** 拖动排序：长按整行或按住 ≡ 即可拖。 */
    private val touchHelper: ItemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
    ) {
        override fun onMove(
            rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder,
        ): Boolean {
            if (busy) return false
            val from = vh.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
            val t = picked[from]; picked[from] = picked[to]; picked[to] = t
            fileAdapter.notifyItemMoved(from, to)
            return true
        }

        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}
        override fun isLongPressDragEnabled() = !busy
    })

    @Suppress("NotifyDataSetChanged")
    private fun refreshFileList() {
        // 选择完成后一次性发起全部请求，不再等 RecyclerView 逐行绑定。
        picked.filterNot { it.isAudio }.forEach(::requestThumbnail)
        fileAdapter.notifyDataSetChanged()
        rvFiles.visibility = if (busy || picked.isEmpty()) View.GONE else View.VISIBLE
        // 缩略图列表最多显示三行，更多视频在列表内滚动，给下面的日志区留空间。
        val dp = resources.displayMetrics.density
        rvFiles.layoutParams = rvFiles.layoutParams.apply {
            height = if (picked.size > 3) (132 * dp).toInt()
            else ViewGroup.LayoutParams.WRAP_CONTENT
        }
        val videos = picked.count { !it.isAudio }
        val audios = picked.count { it.isAudio }
        tvSelected.text = when {
            picked.isEmpty() -> "未选择文件"
            videos > 0 && audios > 0 -> "已选择 $videos 个视频、$audios 个音频"
            videos > 0 -> "已选择 $videos 个视频"
            else -> "已选择 $audios 个音频"
        }
        btnStart.isEnabled = if (busy) !cancelRequested.get() else picked.isNotEmpty()
        cbConcat.isEnabled = videos > 1 && !busy
        if (audios > 0 && cbRepair.isChecked) cbRepair.isChecked = false
        cbRepair.isEnabled = !busy && audios == 0
        syncMasterConcat()
        updateStartText()
    }

    private fun log(s: String) = runOnUiThread {
        tvLog.append(s + "\n")
        svLog.post { svLog.fullScroll(View.FOCUS_DOWN) }
    }

    private fun setUiBusy(b: Boolean) {
        busy = b
        if (!b) cancelRequested.set(false)
        btnPick.isEnabled = !b
        btnStart.isEnabled = if (b) !cancelRequested.get() else picked.isNotEmpty()
        sbTarget.isEnabled = !b && !cbRepair.isChecked
        sbStrength.isEnabled = !b && !cbRepair.isChecked
        cbRepair.isEnabled = !b && picked.none { it.isAudio }
        cbConcat.isEnabled = !b && picked.count { !it.isAudio } > 1
        rvFiles.visibility = if (b || picked.isEmpty()) View.GONE else View.VISIBLE
        fileAdapter.notifyDataSetChanged()   // 重新绑定，让每行「拼接」勾选框随忙碌禁用
        pbFile.visibility = if (b) View.VISIBLE else View.INVISIBLE
        if (b) {
            pbFile.progress = 0
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            tvStage.text = ""
        }
        updateStartText()
    }


    private fun startProcessingWithStorageCheck() {
        val customOutputTreeUri = settings.getString("output_tree_uri", null)
        val hideVideos = settings.getBoolean("hide_videos", false)
        if (customOutputTreeUri != null || !hideVideos) {
            startProcessing()
            return
        }
        if (Environment.isExternalStorageManager()) {
            startProcessing()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("允许写入隐藏文件夹")
            .setMessage("Android 不允许相册接口创建点号开头的文件夹。请开启“所有文件访问权限”，才能把成品直接保存到 Movies/.响度均衡。")
            .setNegativeButton("取消", null)
            .setPositiveButton("去开启") { _, _ ->
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName"),
                )
                try {
                    requestAllFilesAccess.launch(intent)
                } catch (_: Exception) {
                    requestAllFilesAccess.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
            .show()
    }

    private fun showHiddenStoragePermissionMessage() {
        AlertDialog.Builder(this)
            .setTitle("没有存储权限")
            .setMessage("未获得权限，无法写入真正的 Movies/.响度均衡 隐藏文件夹。你可以取消勾选“隐藏视频”后保存到普通文件夹。")
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun startProcessing() {
        val required = requiredFreeBytes()
        val available = StatFs(cacheDir.absolutePath).availableBytes
        if (required > 0 && available < required) {
            AlertDialog.Builder(this)
                .setTitle("存储空间不足")
                .setMessage(
                    "本次处理预计至少需要 ${formatBytes(required)} 可用空间，" +
                        "当前约有 ${formatBytes(available)}。请清理空间后再试。"
                )
                .setPositiveButton("知道了", null)
                .show()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val target = targetLufs()
        val strength = strength()
        val repair = cbRepair.isChecked
        val fastMode = settings.getBoolean("fast_mode", true)
        val hideVideos = settings.getBoolean("hide_videos", false)
        val customOutputTreeUri = settings.getString("output_tree_uri", null)
        val request = ProcessingRequest(
            runId = System.currentTimeMillis(),
            target = target,
            strength = strength,
            repair = repair,
            fastMode = fastMode,
            hideVideos = hideVideos,
            customOutputTreeUri = customOutputTreeUri,
            files = picked.map { file ->
                ProcessingInput(
                    uriText = file.uri.toString(),
                    name = file.name,
                    durationMs = sourceDurationMs(file),
                    clipRanges = file.clipRanges?.toList(),
                    coverPositionMs = file.coverPositionMs,
                    inConcat = file.inConcat,
                    isAudio = file.isAudio,
                )
            },
        )
        cancelRequested.set(false)
        setUiBusy(true)
        tvLog.text = ""
        tvStage.text = "准备处理…"
        try {
            ProcessingService.start(this, request)
        } catch (e: Exception) {
            ProcessingService.acknowledgeResult(request.runId)
            log("任务启动失败：${e.message ?: "未知错误"}")
            setUiBusy(false)
            refreshFileList()
        }
    }

    /** 只查询文件大小和磁盘余量，不读取视频内容，通常可在瞬间完成。 */
    private fun requiredFreeBytes(): Long {
        val sizes = picked.associateWith { contentSize(it.uri) }
        val concatBytes = if (willConcat()) {
            picked.filter { it.inConcat }.sumOf { sizes[it] ?: 0L }
        } else 0L
        val singleBytes = picked.filter { !willConcat() || !it.inConcat }.sumOf { sizes[it] ?: 0L }
        if (concatBytes + singleBytes == 0L) return 0L

        // 裁剪过的单视频会先生成一个精确裁剪缓存；兼容拼接在最终封装时会同时保留
        // 输入副本、硬件合成画面和待发布成品，按三份源文件大小保守估算。
        val trimmedSingleBytes = picked.filter {
            (!willConcat() || !it.inConcat) && it.clipRanges != null
        }.sumOf { sizes[it] ?: 0L }
        val estimatedPeak = singleBytes + trimmedSingleBytes + concatBytes * 3
        return (estimatedPeak * 1.15).toLong() + 100L * 1024 * 1024
    }

    private fun contentSize(uri: Uri): Long = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
        } ?: 0L
    } catch (_: Exception) {
        0L
    }

    private fun formatBytes(bytes: Long): String {
        val gb = bytes / (1024.0 * 1024 * 1024)
        return if (gb >= 1) String.format("%.1f GB", gb)
        else String.format("%.0f MB", bytes / (1024.0 * 1024))
    }

    private fun displayName(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

    private fun durationMs(uri: Uri): Long = try {
        MediaMetadataRetriever().use { r ->
            r.setDataSource(this, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0
        }
    } catch (e: Exception) {
        0
    }

    private fun sourceDurationMs(pf: PickedFile): Long {
        if (pf.durationMs > 0) return pf.durationMs
        return durationMs(pf.uri).also { if (it > 0) pf.durationMs = it }
    }

    private fun isAudioUri(uri: Uri, name: String): Boolean {
        val mime = contentResolver.getType(uri)
        if (mime?.startsWith("audio/") == true) return true
        if (mime?.startsWith("video/") == true) return false
        return name.substringAfterLast('.', "").lowercase() in setOf(
            "mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "wma", "amr",
        )
    }

    private fun formatShortTime(ms: Long): String {
        val totalSeconds = ms.coerceAtLeast(0L) / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds / 60L) % 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
        else "%02d:%02d".format(minutes, seconds)
    }

}
