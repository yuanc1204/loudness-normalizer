package com.yc.loudnorm

import android.Manifest
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.StatFs
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
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
import com.antonkarpenko.ffmpegkit.FFmpegSession
import com.antonkarpenko.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlin.math.abs

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
    private class PickedFile(val uri: Uri, val name: String) {
        var inConcat = false
        var thumbnail: Bitmap? = null
        var thumbnailRequested = false
        var durationMs = 0L
        var clipRanges: List<ClipRange>? = null
        var editRevision = 0
    }

    private val picked = mutableListOf<PickedFile>()
    private var busy = false
    private val settings by lazy { getSharedPreferences("user_settings", MODE_PRIVATE) }
    private var clipEditorLoading = false

    // 大多数手机只能稳定同时开启一个硬件视频编码器；裁剪任务在这里串行，其他任务仍可并行。
    private val preciseVideoEncodeLock = java.util.concurrent.Semaphore(1)

    @Volatile
    private var notificationStage = "准备处理…"

    // 防止「全选拼接」总开关与每行勾选框互相触发监听造成的循环
    private var syncingConcat = false

    @Volatile
    private var lastPct = -1

    // 扫描阶段进度靠解析 ebur128 日志里的时间戳（-f null 输出没有编码统计）
    private val tLineRe = Regex("""t:\s*([\d.]+)\s+TARGET""")

    private val pickVideos =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNullOrEmpty()) return@registerForActivityResult
            for (u in uris) {
                if (picked.any { it.uri == u }) continue
                picked.add(PickedFile(u, displayName(u) ?: "视频${picked.size + 1}"))
            }
            refreshFileList()
        }

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

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
            picked.forEach { it.inConcat = checked }
            updateStartText()
            refreshFileList()
        }

        btnPick.setOnClickListener { pickVideos.launch(arrayOf("video/*")) }
        btnStart.setOnClickListener { startProcessing() }

        if (ProcessingService.isRunning) {
            setUiBusy(true)
            tvStage.text = "正在后台处理，请在通知栏查看进度"
            lifecycleScope.launch {
                while (ProcessingService.isRunning) delay(500)
                if (busy) setUiBusy(false)
            }
        }

        handleShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    /** 接收从 Telegram 等 App 分享过来的视频，直接加进待处理列表。 */
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
            log("正在处理中，请稍后再分享新视频。")
            return
        }
        for (u in uris) {
            if (picked.any { it.uri == u }) continue
            picked.add(PickedFile(u, displayName(u) ?: "分享的视频${picked.size + 1}"))
        }
        refreshFileList()
    }

    private fun postPct(pct: Int) {
        val p = pct.coerceIn(0, 100)
        if (p > lastPct) {
            lastPct = p
            runOnUiThread { pbFile.progress = p }
            ProcessingService.update(p, notificationStage)
        }
    }

    /** 单个任务的输出通道：日志、阶段文字、整体进度（0..1）。并行时各任务互不干扰。 */
    private class JobUi(
        val log: (String) -> Unit,
        val stage: (String) -> Unit,
        val progress: (Double) -> Unit,
    )

    /**
     * 同步执行 ffmpeg，但走会话级回调：并行跑多个会话时，
     * 各自的日志行和编码统计只回到自己的任务，不会像全局回调那样串台。
     */
    private fun runFFmpeg(
        args: Array<String>,
        onLogLine: ((String) -> Unit)? = null,
        onTimeMs: ((Double) -> Unit)? = null,
    ): FFmpegSession {
        val latch = CountDownLatch(1)
        val session = FFmpegKit.executeWithArgumentsAsync(
            args,
            { latch.countDown() },
            { l -> onLogLine?.invoke(l.message ?: "") },
            { st -> onTimeMs?.invoke(st.time.toDouble()) },
        )
        latch.await()
        return session
    }

    private fun targetLufs() = -20.0 + sbTarget.progress          // 0..8 → -20..-12
    private fun strength() = (50 + sbStrength.progress) / 100.0   // 0..50 → 0.5..1.0

    /** 拼接组有效需 2 个以上视频；不足按各自单独处理。 */
    private fun willConcat() = picked.count { it.inConcat } >= 2

    private fun updateStartText() {
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
            val thumbnail = pf.thumbnail
            if (thumbnail != null) {
                h.preview.setImageBitmap(thumbnail)
            } else {
                h.preview.setImageResource(android.R.drawable.ic_media_play)
                requestThumbnail(pf)
            }
            h.preview.isEnabled = !busy
            h.preview.alpha = if (busy) 0.5f else 1f
            h.preview.contentDescription = "裁剪 ${pf.name}"
            h.preview.setOnClickListener {
                val current = picked.getOrNull(h.bindingAdapterPosition) ?: return@setOnClickListener
                if (!busy) showClipEditor(current)
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
            h.cbCat.visibility = if (picked.size > 1) View.VISIBLE else View.GONE
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

    /** 在后台读取视频画面并生成小尺寸预览，避免选择多个视频时阻塞界面。 */
    private fun requestThumbnail(pf: PickedFile) {
        if (pf.thumbnailRequested) return
        pf.thumbnailRequested = true
        val revision = pf.editRevision
        val previewAtMs = pf.clipRanges?.firstOrNull()?.startMs ?: 0L
        lifecycleScope.launch {
            val (bitmap, foundDurationMs) = withContext(Dispatchers.IO) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(this@MainActivity, pf.uri)
                    val duration = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_DURATION
                    )?.toLongOrNull() ?: 0L
                    Pair(
                        retriever.getScaledFrameAtTime(
                            previewAtMs * 1000L,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            160,
                            120,
                        ),
                        duration,
                    )
                } catch (_: Exception) {
                    Pair(null, 0L)
                } finally {
                    retriever.release()
                }
            }
            if (revision != pf.editRevision || picked.indexOf(pf) < 0) {
                bitmap?.recycle()
                return@launch
            }
            if (foundDurationMs > 0) pf.durationMs = foundDurationMs
            if (bitmap != null) {
                pf.thumbnail = bitmap
                val position = picked.indexOf(pf)
                if (position >= 0) fileAdapter.notifyItemChanged(position)
            }
        }
    }

    private fun fileRowText(pf: PickedFile): String {
        val ranges = pf.clipRanges ?: return pf.name
        val kept = ranges.sumOf { it.durationMs }
        return "${pf.name}\n已裁剪：保留 ${ranges.size} 段 · ${formatShortTime(kept)}"
    }

    private fun showClipEditor(pf: PickedFile) {
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
            showClipEditorDialog(this@MainActivity, pf.uri, pf.name, duration, initial) { ranges ->
                val normalized = normalizeClipRanges(ranges, duration)
                pf.clipRanges = if (isFullClip(normalized, duration)) null else normalized
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
        val all = picked.isNotEmpty() && picked.all { it.inConcat }
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
        fileAdapter.notifyDataSetChanged()
        rvFiles.visibility = if (busy || picked.isEmpty()) View.GONE else View.VISIBLE
        // 缩略图列表最多显示三行，更多视频在列表内滚动，给下面的日志区留空间。
        val dp = resources.displayMetrics.density
        rvFiles.layoutParams = rvFiles.layoutParams.apply {
            height = if (picked.size > 3) (132 * dp).toInt()
            else ViewGroup.LayoutParams.WRAP_CONTENT
        }
        tvSelected.text = if (picked.isEmpty()) "未选择视频" else "已选择 ${picked.size} 个视频"
        btnStart.isEnabled = picked.isNotEmpty() && !busy
        cbConcat.isEnabled = picked.size > 1 && !busy
        syncMasterConcat()
        updateStartText()
    }

    private fun log(s: String) = runOnUiThread {
        tvLog.append(s + "\n")
        svLog.post { svLog.fullScroll(View.FOCUS_DOWN) }
    }

    private fun stage(s: String) {
        notificationStage = s
        ProcessingService.update(lastPct.coerceAtLeast(0), s)
        runOnUiThread { tvStage.text = s }
    }

    private fun setUiBusy(b: Boolean) {
        busy = b
        btnPick.isEnabled = !b
        btnStart.isEnabled = !b && picked.isNotEmpty()
        sbTarget.isEnabled = !b && !cbRepair.isChecked
        sbStrength.isEnabled = !b && !cbRepair.isChecked
        cbRepair.isEnabled = !b
        cbConcat.isEnabled = !b && picked.size > 1
        rvFiles.visibility = if (b || picked.isEmpty()) View.GONE else View.VISIBLE
        fileAdapter.notifyDataSetChanged()   // 重新绑定，让每行「拼接」勾选框随忙碌禁用
        pbFile.visibility = if (b) View.VISIBLE else View.INVISIBLE
        if (b) {
            lastPct = -1
            pbFile.progress = 0
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            tvStage.text = ""
        }
    }

    /** 一个并行任务：一段拼接组或一个单独视频。weightMs 用于进度按时长加权。 */
    private class Job(val title: String, val weightMs: Long, val run: (JobUi) -> Boolean)

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
        val files = picked.toList()
        setUiBusy(true)
        tvLog.text = ""
        notificationStage = "准备处理…"

        // 勾了「拼接」的视频（≥2 个才成组）合并为一个任务，其余各自单独一个任务；
        // 拼接组和单独视频一起进并行队列。全局「仅修复」决定整批是均衡还是仅修复。
        val doConcat = files.count { it.inConcat } >= 2
        val concatFiles = if (doConcat) files.filter { it.inConcat } else emptyList()
        val singleFiles = if (doConcat) files.filter { !it.inConcat } else files

        val jobs = ArrayList<Job>()
        if (doConcat) {
            val w = concatFiles.sumOf { effectiveDurationMs(it).coerceAtLeast(1L) }
            jobs.add(Job("拼接 ${concatFiles.size} 个视频", w) { ui ->
                concatOne(concatFiles, repair, target, strength, ui)
            })
        }
        for (pf in singleFiles) {
            val w = effectiveDurationMs(pf).coerceAtLeast(1L)
            jobs.add(Job(pf.name, w) { ui ->
                if (repair) repairOne(pf, ui)
                else processOne(pf, target, strength, ui)
            })
        }

        ProcessingService.start(this) {
            try {
            val t0 = System.currentTimeMillis()
            // 进度条显示按时长加权的总进度；日志按任务缓冲、完成后整块输出，避免交错
            val totalDur = jobs.sumOf { it.weightMs }.coerceAtLeast(1L).toDouble()
            val fracs = DoubleArray(jobs.size)
            val progressLock = Any()
            val resultLogLock = Any()
            var resultBlocks = 0
            var highestOverall = 0
            fun updateOverall(index: Int, fraction: Double) {
                val pct = synchronized(progressLock) {
                    fracs[index] = maxOf(fracs[index], fraction.coerceIn(0.0, 1.0))
                    val calculated = (
                        jobs.indices.sumOf { fracs[it] * jobs[it].weightMs } / totalDur * 100
                    ).toInt()
                    highestOverall = maxOf(highestOverall, calculated)
                    highestOverall
                }
                postPct(pct)
            }
            val single = jobs.size == 1
            if (!single) stage("并行处理中（最多 3 个同时）…")

            val sem = Semaphore(3)
            val results = jobs.mapIndexed { i, job ->
                async {
                    sem.withPermit {
                        val buf = StringBuilder()
                        val ui = JobUi(
                            log = if (single) ::log
                            else { s -> synchronized(buf) { buf.appendLine(s); Unit } },
                            stage = if (single) ::stage else { _ -> },
                            progress = { x -> updateOverall(i, x) },
                        )
                        if (single) log("【${job.title}】")
                        val ft0 = System.currentTimeMillis()
                        val okOne = try {
                            val r = job.run(ui)
                            if (r) ui.log("  耗时 ${elapsed(ft0)}")
                            r
                        } catch (e: Exception) {
                            ui.log("  失败：${e.message}")
                            false
                        }
                        updateOverall(i, 1.0)
                        if (!single) {
                            synchronized(resultLogLock) {
                                if (resultBlocks > 0) log("")
                                resultBlocks++
                                log("【${job.title}】(${i + 1}/${jobs.size})")
                                log(buf.toString().trimEnd())
                            }
                        }
                        okOne
                    }
                }
            }.awaitAll()

            val ok = results.count { it }
            log("")
            log("全部完成：成功 $ok 个，失败 ${jobs.size - ok} 个，总耗时 ${elapsed(t0)}。")
            if (ok > 0) log("成品在相册（或文件管理器）的 Movies/响度均衡 文件夹里。")
            ProcessingService.TaskResult(ok > 0, "成功 $ok 个，失败 ${jobs.size - ok} 个")
            } finally {
                withContext(Dispatchers.Main) {
                    picked.forEach { it.thumbnail?.recycle() }
                    picked.clear()
                    setUiBusy(false)
                    refreshFileList()
                }
            }
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

        // 裁剪过的单视频会先生成一个精确裁剪缓存；拼接在峰值时需容纳输入副本和合并文件。
        val trimmedSingleBytes = picked.filter {
            (!willConcat() || !it.inConcat) && it.clipRanges != null
        }.sumOf { sizes[it] ?: 0L }
        val estimatedPeak = singleBytes + trimmedSingleBytes + concatBytes * 2
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

    private fun elapsed(since: Long): String {
        val s = (System.currentTimeMillis() - since) / 1000
        return if (s >= 60) "${s / 60} 分 ${s % 60} 秒" else "$s 秒"
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

    private fun clipRangesFor(pf: PickedFile): List<ClipRange> {
        val duration = sourceDurationMs(pf)
        return pf.clipRanges ?: if (duration > 0) listOf(ClipRange(0L, duration)) else emptyList()
    }

    private fun effectiveDurationMs(pf: PickedFile): Long =
        pf.clipRanges?.sumOf { it.durationMs } ?: sourceDurationMs(pf)

    private fun formatShortTime(ms: Long): String {
        val totalSeconds = ms.coerceAtLeast(0L) / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds / 60L) % 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
        else "%02d:%02d".format(minutes, seconds)
    }

    /**
     * 在相册建条目并执行 ffmpeg 输出：主路径直接写相册（省一遍 IO），
     * 个别机型输出流不可 seek 导致 mp4 封装失败时，自动回退到"先写缓存再复制"。
     */
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
            var enc = encode(FFmpegKitConfig.getSafParameterForWrite(this, outUri))
            if (!ReturnCode.isSuccess(enc.returnCode) &&
                enc.getAllLogsAsString(2000).contains("non seekable")
            ) {
                val tmp = File(cacheDir, "out_${System.nanoTime()}.mp4")
                enc = encode(tmp.absolutePath)
                if (ReturnCode.isSuccess(enc.returnCode) && tmp.exists()) {
                    contentResolver.openOutputStream(outUri)!!.use { os ->
                        tmp.inputStream().use { it.copyTo(os) }
                    }
                }
                tmp.delete()
            }
            if (!ReturnCode.isSuccess(enc.returnCode)) {
                logFn("  失败：ffmpeg 处理出错：")
                logFn("  " + enc.getAllLogsAsString(2000).lines().takeLast(5).joinToString("\n  "))
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

    /** 为精确裁剪构造 trim/atrim + concat 滤镜；每个输出片段都从零时间戳开始。 */
    private fun buildPreciseClipFilter(
        rangesByInput: List<List<ClipRange>>, hasAudio: Boolean, pixelFormat: String,
    ): String {
        val filters = ArrayList<String>()
        val concatInputs = StringBuilder()
        var segment = 0
        for ((inputIndex, ranges) in rangesByInput.withIndex()) {
            for (range in ranges) {
                val start = String.format(java.util.Locale.US, "%.3f", range.startMs / 1000.0)
                val end = String.format(java.util.Locale.US, "%.3f", range.endMs / 1000.0)
                filters.add(
                    "[$inputIndex:v:0]setpts=PTS-STARTPTS,trim=start=$start:end=$end," +
                        "setpts=PTS-STARTPTS[v$segment]"
                )
                concatInputs.append("[v$segment]")
                if (hasAudio) {
                    filters.add(
                        "[$inputIndex:a:0]asetpts=PTS-STARTPTS,atrim=start=$start:end=$end," +
                            "asetpts=PTS-STARTPTS,aresample=async=1:first_pts=0[a$segment]"
                    )
                    concatInputs.append("[a$segment]")
                }
                segment++
            }
        }
        require(segment > 0) { "没有可保留的裁剪片段" }
        if (segment == 1) {
            filters.add("[v0]format=$pixelFormat[vout]")
            if (hasAudio) filters.add("[a0]anull[aout]")
        } else {
            if (hasAudio) {
                filters.add("${concatInputs}concat=n=$segment:v=1:a=1[vcat][aout]")
            } else {
                filters.add("${concatInputs}concat=n=$segment:v=1:a=0[vcat]")
            }
            filters.add("[vcat]format=$pixelFormat[vout]")
        }
        return filters.joinToString(";")
    }

    private fun preciseClipArgs(
        inputProviders: List<() -> String>, rangesByInput: List<List<ClipRange>>,
        hasAudio: Boolean, videoBitrate: Int, output: File, hardware: Boolean,
    ): Array<String> {
        val pixelFormat = if (hardware) "nv12" else "yuv420p"
        val args = ArrayList<String>()
        args.addAll(listOf("-hide_banner", "-y"))
        for (provider in inputProviders) args.addAll(listOf("-i", provider()))
        args.addAll(
            listOf(
                "-filter_complex", buildPreciseClipFilter(rangesByInput, hasAudio, pixelFormat),
                "-map", "[vout]",
            )
        )
        if (hasAudio) args.addAll(listOf("-map", "[aout]"))
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
            // 极少数设备无法启动硬件编码器时仍保证裁剪可用。
            args.addAll(
                listOf(
                    "-c:v", "mpeg4",
                    "-pix_fmt", "yuv420p",
                    "-q:v", "3",
                    "-g", "60",
                )
            )
        }
        if (hasAudio) args.addAll(listOf("-c:a", "aac", "-b:a", "192k"))
        else args.add("-an")
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

    /**
     * 任意分割点都要准确生效，因此裁剪过的视频在这里重新编码一次画面。
     * 优先使用 Android 硬件 H.264；设备不支持时自动回退到内置兼容编码器。
     */
    private fun runPreciseClip(
        inputProviders: List<() -> String>, rangesByInput: List<List<ClipRange>>,
        hasAudio: Boolean, videoBitrate: Int, output: File,
        logFn: (String) -> Unit, onTimeMs: ((Double) -> Unit)?,
    ): FFmpegSession {
        require(inputProviders.size == rangesByInput.size)
        preciseVideoEncodeLock.acquire()
        try {
            output.delete()
            val hardware = runFFmpeg(
                preciseClipArgs(
                    inputProviders, rangesByInput, hasAudio, videoBitrate, output, hardware = true
                ),
                onTimeMs = onTimeMs,
            )
            if (ReturnCode.isSuccess(hardware.returnCode)) return hardware

            output.delete()
            logFn("  当前设备的硬件裁剪编码不可用，自动改用兼容模式…")
            return runFFmpeg(
                preciseClipArgs(
                    inputProviders, rangesByInput, hasAudio, videoBitrate, output, hardware = false
                ),
                onTimeMs = onTimeMs,
            )
        } finally {
            preciseVideoEncodeLock.release()
        }
    }

    /** 把一个已编辑视频的所有保留片段精确合成为缓存输入。 */
    private fun createTrimmedInput(
        pf: PickedFile, ui: JobUi, onFraction: (Double) -> Unit,
    ): File? {
        val ranges = pf.clipRanges ?: return null
        val info = probeVideo(pf.uri)
        if (info == null) {
            ui.log("  失败：无法识别视频编码参数，不能裁剪")
            return null
        }
        val keptDuration = ranges.sumOf { it.durationMs }.coerceAtLeast(1L).toDouble()
        val output = File(cacheDir, "trim_${System.nanoTime()}.mp4")
        ui.stage("裁剪中：生成保留片段…")
        val session = runPreciseClip(
            listOf { FFmpegKitConfig.getSafParameterForRead(this, pf.uri) },
            listOf(ranges),
            info.aMime != null,
            info.bitrate,
            output,
            ui.log,
            onTimeMs = { ms -> onFraction((ms / keptDuration).coerceIn(0.0, 1.0)) },
        )
        if (!ReturnCode.isSuccess(session.returnCode) || !output.exists()) {
            ui.log("  失败：裁剪视频时出错：")
            ui.log("  " + session.getAllLogsAsString(2000).lines().takeLast(6).joinToString("\n  "))
            output.delete()
            return null
        }
        onFraction(1.0)
        return output
    }

    /** 把已生成的缓存 mp4 无损写入相册。 */
    private fun savePreparedVideo(input: File, outName: String, durMs: Long, ui: JobUi): Boolean {
        val dur = durMs.coerceAtLeast(1L).toDouble()
        ui.stage("保存裁剪成品…")
        return encodeToGallery(outName, ui.log) { out ->
            runFFmpeg(
                arrayOf(
                    "-hide_banner", "-y",
                    "-i", input.absolutePath,
                    "-map", "0:v?", "-map", "0:a?",
                    "-c", "copy",
                    "-f", "mp4",
                    out,
                ),
                onTimeMs = { ms -> ui.progress(ms / dur) },
            )
        }
    }

    /** 仅修复播放卡顿；若设置了裁剪，先精确生成保留片段再重新封装。 */
    private fun repairOne(pf: PickedFile, ui: JobUi): Boolean {
        val dur = effectiveDurationMs(pf).coerceAtLeast(1L)
        val base = pf.name.substringBeforeLast('.')
        if (pf.clipRanges == null) {
            ui.stage("修复封装（无损，不重编码）…")
            return encodeToGallery("${base}_修复.mp4", ui.log) { out ->
                runFFmpeg(
                    arrayOf(
                        "-hide_banner", "-y",
                        "-i", FFmpegKitConfig.getSafParameterForRead(this, pf.uri),
                        "-map", "0:v?", "-map", "0:a?",
                        "-c", "copy",
                        "-f", "mp4",
                        out,
                    ),
                    onTimeMs = { ms -> ui.progress(ms / dur) },
                )
            }
        }

        val trimmed = createTrimmedInput(pf, ui) { x -> ui.progress(0.8 * x) } ?: return false
        return try {
            val saveUi = JobUi(ui.log, ui.stage) { x -> ui.progress(0.8 + 0.2 * x) }
            savePreparedVideo(trimmed, "${base}_修复.mp4", dur, saveUi)
        } finally {
            trimmed.delete()
        }
    }

    /** 无损拼接：把 concat 列表文件里的视频按顺序合并（-c copy），输出到 out。 */
    private fun runConcat(
        listPath: String, out: String, onTimeMs: ((Double) -> Unit)?,
    ): FFmpegSession =
        runFFmpeg(
            arrayOf(
                "-hide_banner", "-y",
                "-f", "concat", "-safe", "0",
                "-i", listPath,
                "-map", "0:v?", "-map", "0:a?",
                "-c", "copy",
                "-avoid_negative_ts", "make_zero",
                "-f", "mp4",
                out,
            ),
            onTimeMs = onTimeMs,
        )

    /** 视频关键参数，用于判断能否无损拼接。 */
    private class VidInfo(
        val mime: String, val w: Int, val h: Int, val rot: Int, val csd: ByteArray?,
        val bitrate: Int, val aMime: String?, val aRate: Int, val aCh: Int,
    ) {
        fun desc(): String {
            val v = "${mime.removePrefix("video/")} ${w}x${h}" + (if (rot != 0) " 旋转${rot}°" else "")
            val a = aMime?.let { "，音频 ${it.removePrefix("audio/")} ${aRate}Hz ${aCh}声道" } ?: "，无音轨"
            return v + a
        }
    }

    private fun probeVideo(uri: Uri): VidInfo? = try {
        contentResolver.openFileDescriptor(uri, "r")!!.use { pfd ->
            val ex = MediaExtractor()
            try {
                ex.setDataSource(pfd.fileDescriptor)
                var mime = ""; var w = 0; var h = 0; var rot = 0
                var bitrate = 8_000_000
                var csd: ByteArray? = null
                var aMime: String? = null; var aRate = 0; var aCh = 0
                for (i in 0 until ex.trackCount) {
                    val f = ex.getTrackFormat(i)
                    val m = f.getString(MediaFormat.KEY_MIME) ?: continue
                    if (m.startsWith("video/") && mime.isEmpty()) {
                        mime = m
                        w = f.getInteger(MediaFormat.KEY_WIDTH)
                        h = f.getInteger(MediaFormat.KEY_HEIGHT)
                        rot = try { f.getInteger(MediaFormat.KEY_ROTATION) } catch (e: Exception) { 0 }
                        bitrate = try { f.getInteger(MediaFormat.KEY_BIT_RATE) } catch (e: Exception) { bitrate }
                        csd = try {
                            f.getByteBuffer("csd-0")?.let { b ->
                                ByteArray(b.remaining()).also { b.get(it) }
                            }
                        } catch (e: Exception) { null }
                    } else if (m.startsWith("audio/") && aMime == null) {
                        aMime = m
                        aRate = try { f.getInteger(MediaFormat.KEY_SAMPLE_RATE) } catch (e: Exception) { 0 }
                        aCh = try { f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) } catch (e: Exception) { 0 }
                    }
                }
                if (mime.isEmpty()) null
                else VidInfo(mime, w, h, rot, csd, bitrate, aMime, aRate, aCh)
            } finally {
                ex.release()
            }
        }
    } catch (e: Exception) {
        null
    }

    /** 无损拼接要求：编码、分辨率、旋转、编码头（SPS/PPS）、音频参数全部一致。 */
    private fun sameParams(a: VidInfo, b: VidInfo): Boolean =
        a.mime == b.mime && a.w == b.w && a.h == b.h && a.rot == b.rot &&
            ((a.csd == null && b.csd == null) ||
                (a.csd != null && b.csd != null && a.csd.contentEquals(b.csd))) &&
            a.aMime == b.aMime && a.aRate == b.aRate && a.aCh == b.aCh

    /**
     * 拼接所有视频。没有裁剪时仍使用无损 -c copy；任一视频裁剪过时，
     * 把所有保留片段一次性精确合并并编码，避免分割点受关键帧位置限制。
     * repair=true 时只拼接不调音量；否则再对合并结果做响度均衡。
     * 各视频编码/分辨率/编码头/音频参数必须一致，否则 -c copy 会产出时长错乱、
     * 后段无法播放的坏文件——故先探测参数，不一致直接报错跳过，不生成坏文件。
     * 注意：concat 分段切换时关闭 saf 输入会原生崩溃（saf_close），
     * 所以输入必须先复制成缓存里的真实文件，不能用 saf 协议直读。
     */
    private fun concatOne(
        files: List<PickedFile>, repair: Boolean, target: Double, strength: Double, ui: JobUi,
    ): Boolean {
        val totalDur = files.sumOf { effectiveDurationMs(it) }.coerceAtLeast(1L).toDouble()
        val hasClipEdits = files.any { it.clipRanges != null }
        val outBase = files.first().name.substringBeforeLast('.') + "_等${files.size}个拼接"
        val stamp = System.currentTimeMillis()
        val listFile = File(cacheDir, "concat_$stamp.txt")
        val tmpCopies = mutableListOf<File>()
        try {
            // 先探测各视频参数：不一致就直接报错，避免生成坏文件、也不浪费复制时间
            val infos = files.map { probeVideo(it.uri) }
            val ref = infos.firstOrNull { it != null }
            if (ref == null) {
                ui.log("  失败：无法识别视频编码参数")
                return false
            }
            if (infos.any { it == null || !sameParams(ref, it) }) {
                ui.log("  失败：这些视频的编码参数不一致，无法拼接。")
                ui.log("  （拼接要求编码、分辨率、旋转、音频参数完全相同，一般同一来源的视频才满足）")
                for ((i, pf) in files.withIndex()) {
                    ui.log("    ${pf.name}：${infos[i]?.desc() ?: "无法识别"}")
                }
                return false
            }

            ui.stage("准备中：复制视频到缓存…")
            val copyWeight = if (repair) 0.10 else 0.05
            val copyTotal = files.sumOf { contentSize(it.uri) }
            var copiedBytes = 0L
            val copyBuffer = ByteArray(1024 * 1024)
            for ((i, pf) in files.withIndex()) {
                val f = File(cacheDir, "cat_${stamp}_$i.mp4")
                contentResolver.openInputStream(pf.uri)!!.use { ins ->
                    f.outputStream().buffered().use { out ->
                        while (true) {
                            val n = ins.read(copyBuffer)
                            if (n < 0) break
                            out.write(copyBuffer, 0, n)
                            copiedBytes += n
                            if (copyTotal > 0) {
                                ui.progress(copyWeight * copiedBytes / copyTotal)
                            }
                        }
                    }
                }
                tmpCopies.add(f)
            }

            if (hasClipEdits) {
                val merged = File(cacheDir, "merged_clip_$stamp.mp4")
                try {
                    ui.stage("裁剪并拼接保留片段…")
                    val clipWeight = if (repair) 0.75 else 0.30
                    val session = runPreciseClip(
                        tmpCopies.map { file -> ({ file.absolutePath }) },
                        files.map { clipRangesFor(it) },
                        ref.aMime != null,
                        ref.bitrate,
                        merged,
                        ui.log,
                        onTimeMs = { ms ->
                            ui.progress(copyWeight + clipWeight * ms / totalDur)
                        },
                    )
                    if (!ReturnCode.isSuccess(session.returnCode) || !merged.exists()) {
                        ui.log("  失败：裁剪并拼接时出错：")
                        ui.log(
                            "  " + session.getAllLogsAsString(2000).lines()
                                .takeLast(6).joinToString("\n  ")
                        )
                        return false
                    }
                    tmpCopies.forEach { it.delete() }
                    val processedBase = copyWeight + clipWeight
                    val sub = JobUi(ui.log, ui.stage) { x ->
                        ui.progress(processedBase + (1 - processedBase) * x)
                    }
                    return if (repair) {
                        savePreparedVideo(merged, "$outBase.mp4", totalDur.toLong(), sub)
                    } else {
                        processInput(
                            { merged.absolutePath }, "$outBase.mp4", totalDur.toLong(),
                            target, strength, sub,
                        )
                    }
                } finally {
                    merged.delete()
                }
            }

            listFile.writeText(tmpCopies.joinToString("") { "file '${it.absolutePath}'\n" })

            ui.stage("拼接中（无损，不重编码）…")
            if (repair) {
                return encodeToGallery("$outBase.mp4", ui.log) { out ->
                    runConcat(listFile.absolutePath, out) { ms ->
                        ui.progress(copyWeight + (1 - copyWeight) * ms / totalDur)
                    }
                }
            }
            // 复制占前 5%，无损拼接占 15%，响度均衡占后 80%。
            val merged = File(cacheDir, "merged_$stamp.mp4")
            try {
                val s = runConcat(listFile.absolutePath, merged.absolutePath) { ms ->
                    ui.progress(copyWeight + 0.15 * ms / totalDur)
                }
                if (!ReturnCode.isSuccess(s.returnCode)) {
                    ui.log("  失败：拼接出错：")
                    ui.log("  " + s.getAllLogsAsString(2000).lines().takeLast(6).joinToString("\n  "))
                    return false
                }
                // 输入副本用完即删，给均衡阶段腾缓存空间
                tmpCopies.forEach { it.delete() }
                val processedBase = copyWeight + 0.15
                val sub = JobUi(ui.log, ui.stage) { x ->
                    ui.progress(processedBase + (1 - processedBase) * x)
                }
                return processInput(
                    { merged.absolutePath }, "$outBase.mp4", totalDur.toLong(), target, strength, sub
                )
            } finally {
                merged.delete()
            }
        } finally {
            listFile.delete()
            tmpCopies.forEach { it.delete() }
        }
    }

    /** 完整处理一个视频：可选精确裁剪 → 扫描响度 → 计算增益 → 生成并存入相册。 */
    private fun processOne(
        pf: PickedFile, target: Double, strength: Double, ui: JobUi,
    ): Boolean {
        if (pf.clipRanges == null) {
            return processInput(
                { FFmpegKitConfig.getSafParameterForRead(this, pf.uri) },
                pf.name, sourceDurationMs(pf), target, strength, ui,
            )
        }

        val trimWeight = 0.35
        val trimmed = createTrimmedInput(pf, ui) { x -> ui.progress(trimWeight * x) }
            ?: return false
        return try {
            val sub = JobUi(ui.log, ui.stage) { x ->
                ui.progress(trimWeight + (1 - trimWeight) * x)
            }
            processInput(
                { trimmed.absolutePath }, pf.name, effectiveDurationMs(pf),
                target, strength, sub,
            )
        } finally {
            trimmed.delete()
        }
    }

    /**
     * 响度均衡主流程。input 每次调用返回一个可用的 ffmpeg 输入（saf 参数或缓存文件路径）。
     * 进度：扫描占前 20%，编码占后 80%（与两阶段的实际耗时比例大致相符）。
     */
    private fun processInput(
        input: () -> String, name: String, durMs: Long,
        target: Double, strength: Double, ui: JobUi,
    ): Boolean {
        val dur = durMs.coerceAtLeast(1L).toDouble()
        ui.stage("第 1 步 / 共 2 步：扫描响度…")
        // 扫描数据经 ametadata 写进文件再解析（日志只用来估进度，丢行也无所谓）
        val metaFile = File(cacheDir, "scan_${System.nanoTime()}.txt")
        val metaPath = metaFile.absolutePath.replace("\\", "/").replace(":", "\\:")
        val pts = try {
            val scan = runFFmpeg(
                arrayOf(
                    "-hide_banner", "-nostats",
                    "-i", input(),
                    "-map", "0:a:0",
                    "-af", "ebur128=peak=true:metadata=1,ametadata=mode=print:file='$metaPath'",
                    "-f", "null", "-",
                ),
                onLogLine = { line ->
                    val m = tLineRe.find(line)
                    if (m != null) ui.progress(0.2 * m.groupValues[1].toDouble() * 1000 / dur)
                },
            )
            if (!ReturnCode.isSuccess(scan.returnCode)) {
                ui.log("  失败：无法读取音频（文件可能没有声音轨）")
                return false
            }
            if (!metaFile.exists()) emptyList() else Engine.parseMetadata(metaFile.readText())
        } finally {
            metaFile.delete()
        }
        if (pts.isEmpty()) {
            ui.log("  失败：未取得响度数据")
            return false
        }
        val segs = Engine.makeSegments(pts, target, strength)
        val knots = Engine.makeKnots(segs)
        val cmdFile = File(cacheDir, "gain_${System.nanoTime()}.cmd")
        val vol = Engine.writeGainCmds(knots, cmdFile)
        val changed = segs.filter { abs(it.g) >= 0.5 }
        ui.log("  共分 ${segs.size} 段，调整 ${changed.size} 段：")
        for (sg in changed) {
            ui.log(String.format(java.util.Locale.US, "    %.0fs~%.0fs  %+.1f dB", sg.a, sg.b, sg.g))
        }
        // 测量值直接由扫描数据计算，省掉一遍解码
        val measured = Engine.computeMeasured(pts, knots)

        // 在生成链末尾挂个 ebur128 量成品响度，随生成一起算（几乎零成本），
        // 读它写出的最后一个 I 值——就是这个视频最终的实测响度
        val finalMeta = File(cacheDir, "final_${System.nanoTime()}.txt")
        val finalPath = finalMeta.absolutePath.replace("\\", "/").replace(":", "\\:")
        try {
            ui.stage("第 2 步 / 共 2 步：生成并保存（画面直接复制）…")
            val tGen = System.currentTimeMillis()
            val filter = Engine.buildFilter(target, vol, measured) +
                ",ebur128=metadata=1,ametadata=mode=print:file='$finalPath'"
            val base = cleanOutputBase(name)
            var finalLoudness: Double? = null
            val done = encodeToGallery(
                outName = "${base}_处理中.mp4",
                logFn = ui.log,
                finalName = {
                    finalLoudness = readFinalLoudness(finalMeta)
                    finalLoudness?.let {
                        String.format(java.util.Locale.US, "%s_%.2fLUFS.mp4", base, it)
                    } ?: "${base}_响度未知.mp4"
                },
            ) { out ->
                runFFmpeg(
                    arrayOf(
                        "-hide_banner", "-y",
                        "-i", input(),
                        "-map", "0:v?", "-map", "0:a:0",
                        "-c:v", "copy",
                        "-af", filter,
                        "-c:a", "aac", "-b:a", "192k",
                        "-f", "mp4",
                        out,
                    ),
                    onTimeMs = { ms -> ui.progress(0.2 + 0.8 * ms / dur) },
                )
            }
            if (done) {
                finalLoudness?.let {
                    ui.log(String.format(java.util.Locale.US, "  均衡后最终响度：%.2f LUFS", it))
                }
                ui.log("  生成用时 ${elapsed(tGen)}")
            }
            return done
        } finally {
            cmdFile.delete()
            finalMeta.delete()
        }
    }

    /** 避免重复处理后文件名不断叠加“_均衡”或旧的响度后缀。 */
    private fun cleanOutputBase(name: String): String {
        val suffix = Regex("_(?:均衡|[+-]?\\d+(?:\\.\\d+)?LUFS|响度未知)$", RegexOption.IGNORE_CASE)
        var base = name.substringBeforeLast('.')
        while (true) {
            val cleaned = base.replace(suffix, "")
            if (cleaned == base) return base
            base = cleaned
        }
    }

    /** 从生成链末尾 ebur128 写出的 metadata 文件里取最后一个积分响度 I。 */
    private fun readFinalLoudness(f: File): Double? {
        if (!f.exists()) return null
        var last: Double? = null
        f.forEachLine { line ->
            if (line.startsWith("lavfi.r128.I=")) {
                line.substring(13).trim().toDoubleOrNull()
                    ?.let { if (it.isFinite() && it > -70) last = it }
            }
        }
        return last
    }
}
