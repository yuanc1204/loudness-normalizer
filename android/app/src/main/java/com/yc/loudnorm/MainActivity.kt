package com.yc.loudnorm

import android.content.ContentValues
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
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
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.FFmpegKitConfig
import com.antonkarpenko.ffmpegkit.FFmpegSession
import com.antonkarpenko.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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

    private data class PickedFile(val uri: Uri, val name: String)

    private val picked = mutableListOf<PickedFile>()
    private var busy = false

    @Volatile
    private var currentDurationMs: Long = 0

    // 扫描阶段进度靠解析 ebur128 日志里的时间戳（-f null 输出没有编码统计）
    @Volatile
    private var scanPhase = false

    @Volatile
    private var lastPct = -1

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

        sbTarget.setOnSeekBarChangeListener(simpleSeek {
            tvTarget.text = "目标响度：${targetLufs().toInt()} LUFS"
        })
        sbStrength.setOnSeekBarChangeListener(simpleSeek {
            tvStrength.text = "均衡力度：${(strength() * 100).toInt()}%"
        })
        cbRepair.setOnCheckedChangeListener { _, checked ->
            sbTarget.isEnabled = !checked && !busy
            sbStrength.isEnabled = !checked && !busy
            updateStartText()
        }
        cbConcat.setOnCheckedChangeListener { _, _ ->
            updateStartText()
            refreshFileList()
        }

        btnPick.setOnClickListener { pickVideos.launch(arrayOf("video/*")) }
        btnStart.setOnClickListener { startProcessing() }

        // 编码阶段的进度（time 为已处理的毫秒数）
        FFmpegKitConfig.enableStatisticsCallback { st ->
            if (!scanPhase) setProgressMs(st.time.toDouble())
        }
        // 扫描阶段的进度：从 ebur128 日志行解析时间戳
        FFmpegKitConfig.enableLogCallback { l ->
            if (scanPhase) {
                val m = tLineRe.find(l.message ?: return@enableLogCallback)
                if (m != null) setProgressMs(m.groupValues[1].toDouble() * 1000)
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

    private fun setProgressMs(ms: Double) {
        val dur = currentDurationMs
        if (dur <= 0) return
        val pct = (ms / dur * 100).toInt().coerceIn(0, 100)
        if (pct != lastPct) {
            lastPct = pct
            runOnUiThread { pbFile.progress = pct }
        }
    }

    private fun targetLufs() = -20.0 + sbTarget.progress          // 0..8 → -20..-12
    private fun strength() = (50 + sbStrength.progress) / 100.0   // 0..50 → 0.5..1.0

    private fun updateStartText() {
        btnStart.text = when {
            cbConcat.isChecked && cbRepair.isChecked -> "开始拼接（不调音量）"
            cbConcat.isChecked -> "拼接并均衡"
            cbRepair.isChecked -> "开始修复"
            else -> "开始处理"
        }
    }

    private fun simpleSeek(onChange: () -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) = onChange()
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {}
    }

    private class FileVH(row: LinearLayout, val handle: TextView, val name: TextView, val del: TextView) :
        RecyclerView.ViewHolder(row)

    /** 已选文件列表：按住 ≡ 拖动排序（拼接顺序即列表顺序），✕ 删除。 */
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
            val name = TextView(parent.context).apply {
                textSize = 13f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val del = icon("✕", 16f)
            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT
                )
                addView(handle)
                addView(name)
                addView(del)
            }
            return FileVH(row, handle, name, del)
        }

        override fun onBindViewHolder(h: FileVH, position: Int) {
            h.name.text = picked[position].name
            h.handle.visibility = if (picked.size > 1) View.VISIBLE else View.GONE
            h.handle.setOnTouchListener { v, ev ->
                if (ev.actionMasked == MotionEvent.ACTION_DOWN && !busy) {
                    touchHelper.startDrag(h)
                    v.performClick()
                }
                false
            }
            h.del.setOnClickListener {
                val pos = h.bindingAdapterPosition
                if (busy || pos == RecyclerView.NO_POSITION) return@setOnClickListener
                picked.removeAt(pos)
                refreshFileList()
            }
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
        // 超过 5 个时列表内滚动，避免挤掉下面的日志区
        val dp = resources.displayMetrics.density
        rvFiles.layoutParams = rvFiles.layoutParams.apply {
            height = if (picked.size > 5) (170 * dp).toInt()
            else ViewGroup.LayoutParams.WRAP_CONTENT
        }
        tvSelected.text = if (picked.isEmpty()) "未选择视频" else "已选择 ${picked.size} 个视频"
        btnStart.isEnabled = picked.isNotEmpty() && !busy
    }

    private fun log(s: String) = runOnUiThread {
        tvLog.append(s + "\n")
        svLog.post { svLog.fullScroll(View.FOCUS_DOWN) }
    }

    private fun stage(s: String) = runOnUiThread {
        tvStage.text = s
        lastPct = -1
        pbFile.progress = 0
    }

    private fun setUiBusy(b: Boolean) {
        busy = b
        btnPick.isEnabled = !b
        btnStart.isEnabled = !b && picked.isNotEmpty()
        sbTarget.isEnabled = !b && !cbRepair.isChecked
        sbStrength.isEnabled = !b && !cbRepair.isChecked
        cbRepair.isEnabled = !b
        cbConcat.isEnabled = !b
        pbFile.visibility = if (b) View.VISIBLE else View.INVISIBLE
        if (b) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            tvStage.text = ""
        }
    }

    private fun startProcessing() {
        val target = targetLufs()
        val strength = strength()
        val repair = cbRepair.isChecked
        val concat = cbConcat.isChecked && picked.size >= 2
        val files = picked.toList()
        setUiBusy(true)
        tvLog.text = ""
        lifecycleScope.launch(Dispatchers.IO) {
            var ok = 0
            val t0 = System.currentTimeMillis()
            if (concat) {
                log("【拼接 ${files.size} 个视频】")
                try {
                    if (concatOne(files, repair, target, strength)) ok++
                } catch (e: Exception) {
                    log("  失败：${e.message}")
                }
                log("")
                log(if (ok > 0) "拼接完成，总耗时 ${elapsed(t0)}。" else "拼接失败。")
                if (ok > 0) log("成品在相册（或文件管理器）的 Movies/响度均衡 文件夹里。")
                withContext(Dispatchers.Main) { setUiBusy(false) }
                return@launch
            }
            for ((idx, pf) in files.withIndex()) {
                log("【${pf.name}】(${idx + 1}/${files.size})")
                val ft0 = System.currentTimeMillis()
                try {
                    val done = if (repair) repairOne(pf.uri, pf.name)
                    else processOne(pf.uri, pf.name, target, strength)
                    if (done) {
                        ok++
                        log("  本视频耗时 ${elapsed(ft0)}")
                    }
                } catch (e: Exception) {
                    log("  失败：${e.message}")
                }
            }
            log("")
            log("全部完成：成功 $ok 个，失败 ${files.size - ok} 个，总耗时 ${elapsed(t0)}。")
            if (ok > 0) log("成品在相册（或文件管理器）的 Movies/响度均衡 文件夹里。")
            withContext(Dispatchers.Main) { setUiBusy(false) }
        }
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

    /**
     * 在相册建条目并执行 ffmpeg 输出：主路径直接写相册（省一遍 IO），
     * 个别机型输出流不可 seek 导致 mp4 封装失败时，自动回退到"先写缓存再复制"。
     */
    private fun encodeToGallery(outName: String, encode: (String) -> FFmpegSession): Boolean {
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
            log("  失败：无法写入相册")
            return false
        }
        try {
            var enc = encode(FFmpegKitConfig.getSafParameterForWrite(this, outUri))
            if (!ReturnCode.isSuccess(enc.returnCode) &&
                enc.allLogsAsString.contains("non seekable")
            ) {
                val tmp = File(cacheDir, "out_${System.currentTimeMillis()}.mp4")
                enc = encode(tmp.absolutePath)
                if (ReturnCode.isSuccess(enc.returnCode) && tmp.exists()) {
                    contentResolver.openOutputStream(outUri)!!.use { os ->
                        tmp.inputStream().use { it.copyTo(os) }
                    }
                }
                tmp.delete()
            }
            if (!ReturnCode.isSuccess(enc.returnCode)) {
                log("  失败：ffmpeg 处理出错：")
                log("  " + enc.allLogsAsString.lines().takeLast(5).joinToString("\n  "))
                contentResolver.delete(outUri, null, null)
                return false
            }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            contentResolver.update(outUri, values, null, null)
            log("  完成 → Movies/响度均衡/$outName")
            return true
        } catch (e: Exception) {
            contentResolver.delete(outUri, null, null)
            throw e
        }
    }

    /** 仅修复播放卡顿：无损重新封装成标准 mp4（分片索引重建），不动音量。 */
    private fun repairOne(uri: Uri, name: String): Boolean {
        currentDurationMs = durationMs(uri)
        stage("修复封装（无损，不重编码）…")
        val base = name.substringBeforeLast('.')
        return encodeToGallery("${base}_修复.mp4") { out ->
            FFmpegKit.executeWithArguments(
                arrayOf(
                    "-hide_banner", "-y",
                    "-i", FFmpegKitConfig.getSafParameterForRead(this, uri),
                    "-map", "0:v?", "-map", "0:a?",
                    "-c", "copy",
                    "-f", "mp4",
                    out,
                )
            )
        }
    }

    /** 无损拼接：把 concat 列表文件里的视频按顺序合并（-c copy），输出到 out。 */
    private fun runConcat(listPath: String, out: String): FFmpegSession =
        FFmpegKit.executeWithArguments(
            arrayOf(
                "-hide_banner", "-y",
                "-f", "concat", "-safe", "0",
                "-i", listPath,
                "-map", "0:v?", "-map", "0:a?",
                "-c", "copy",
                "-avoid_negative_ts", "make_zero",
                "-f", "mp4",
                out,
            )
        )

    /**
     * 拼接所有视频。repair=true 时只无损拼接（拼接本身就是重新封装，顺带修复卡顿）；
     * 否则拼到缓存后再对合并结果做响度均衡。
     * 注意：concat 分段切换时关闭 saf 输入会原生崩溃（saf_close），
     * 所以输入必须先复制成缓存里的真实文件，不能用 saf 协议直读。
     */
    private fun concatOne(
        files: List<PickedFile>, repair: Boolean, target: Double, strength: Double,
    ): Boolean {
        currentDurationMs = files.sumOf { durationMs(it.uri) }
        val outBase = files.first().name.substringBeforeLast('.') + "_等${files.size}个拼接"
        val stamp = System.currentTimeMillis()
        val listFile = File(cacheDir, "concat_$stamp.txt")
        val tmpCopies = mutableListOf<File>()
        try {
            stage("准备中：复制视频到缓存…")
            for ((i, pf) in files.withIndex()) {
                val f = File(cacheDir, "cat_${stamp}_$i.mp4")
                contentResolver.openInputStream(pf.uri)!!.use { ins ->
                    f.outputStream().use { ins.copyTo(it) }
                }
                tmpCopies.add(f)
            }
            listFile.writeText(tmpCopies.joinToString("") { "file '${it.absolutePath}'\n" })

            stage("拼接中（无损，不重编码）…")
            if (repair) {
                return encodeToGallery("$outBase.mp4") { out ->
                    runConcat(listFile.absolutePath, out)
                }
            }
            // 先无损拼到缓存，再走正常均衡流程
            val merged = File(cacheDir, "merged_$stamp.mp4")
            try {
                val s = runConcat(listFile.absolutePath, merged.absolutePath)
                if (!ReturnCode.isSuccess(s.returnCode)) {
                    log("  失败：拼接出错（各视频的编码/分辨率需一致）：")
                    log("  " + s.allLogsAsString.lines().takeLast(5).joinToString("\n  "))
                    return false
                }
                // 输入副本用完即删，给均衡阶段腾缓存空间
                tmpCopies.forEach { it.delete() }
                return processInput({ merged.absolutePath }, "$outBase.mp4", target, strength)
            } finally {
                merged.delete()
            }
        } finally {
            listFile.delete()
            tmpCopies.forEach { it.delete() }
        }
    }

    /** 完整处理一个视频：扫描（含真峰值）→ 本地计算测量值 → 编码 → 存入相册。 */
    private fun processOne(uri: Uri, name: String, target: Double, strength: Double): Boolean {
        currentDurationMs = durationMs(uri)
        return processInput({ FFmpegKitConfig.getSafParameterForRead(this, uri) }, name, target, strength)
    }

    /** 响度均衡主流程。input 每次调用返回一个可用的 ffmpeg 输入（saf 参数或缓存文件路径）。 */
    private fun processInput(
        input: () -> String, name: String, target: Double, strength: Double,
    ): Boolean {
        stage("第 1 步 / 共 2 步：扫描响度…")
        scanPhase = true
        val tScan = System.currentTimeMillis()
        val scan = FFmpegKit.executeWithArguments(
            arrayOf(
                "-hide_banner", "-nostats",
                "-i", input(),
                "-map", "0:a:0", "-af", "ebur128=peak=true", "-f", "null", "-",
            )
        )
        scanPhase = false
        if (!ReturnCode.isSuccess(scan.returnCode)) {
            log("  失败：无法读取音频（文件可能没有声音轨）")
            return false
        }
        log("  扫描用时 ${elapsed(tScan)}")
        val pts = Engine.parseTimeline(scan.allLogsAsString)
        if (pts.isEmpty()) {
            log("  失败：未取得响度数据")
            return false
        }
        val segs = Engine.makeSegments(pts, target, strength)
        val knots = Engine.makeKnots(segs)
        val cmdFile = File(cacheDir, "gain_${System.currentTimeMillis()}.cmd")
        val vol = Engine.writeGainCmds(knots, cmdFile)
        val changed = segs.filter { abs(it.g) >= 0.5 }
        log("  共分 ${segs.size} 段，调整 ${changed.size} 段：")
        for (sg in changed) {
            log(String.format(java.util.Locale.US, "    %.0fs~%.0fs  %+.1f dB", sg.a, sg.b, sg.g))
        }
        // 测量值直接由扫描数据计算，省掉一遍解码
        val measured = Engine.computeMeasured(pts, knots)
        log("  分段调整后响度：${measured.i} LUFS")

        try {
            stage("第 2 步 / 共 2 步：生成并保存（画面直接复制）…")
            val tGen = System.currentTimeMillis()
            val filter = Engine.buildFilter(target, vol, measured)
            val base = name.substringBeforeLast('.')
            val done = encodeToGallery("${base}_均衡.mp4") { out ->
                FFmpegKit.executeWithArguments(
                    arrayOf(
                        "-hide_banner", "-y",
                        "-i", input(),
                        "-map", "0:v?", "-map", "0:a:0",
                        "-c:v", "copy",
                        "-af", filter,
                        "-c:a", "aac", "-b:a", "192k",
                        "-f", "mp4",
                        out,
                    )
                )
            }
            if (done) log("  生成用时 ${elapsed(tGen)}")
            return done
        } finally {
            cmdFile.delete()
        }
    }
}
