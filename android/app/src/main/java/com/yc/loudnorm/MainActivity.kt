package com.yc.loudnorm

import android.content.ContentValues
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.FFmpegKitConfig
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
    private lateinit var svFiles: ScrollView
    private lateinit var llFiles: LinearLayout
    private lateinit var sbTarget: SeekBar
    private lateinit var sbStrength: SeekBar
    private lateinit var pbFile: ProgressBar

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
        svFiles = findViewById(R.id.svFiles)
        llFiles = findViewById(R.id.llFiles)
        sbTarget = findViewById(R.id.sbTarget)
        sbStrength = findViewById(R.id.sbStrength)
        pbFile = findViewById(R.id.pbFile)

        sbTarget.setOnSeekBarChangeListener(simpleSeek {
            tvTarget.text = "目标响度：${targetLufs().toInt()} LUFS"
        })
        sbStrength.setOnSeekBarChangeListener(simpleSeek {
            tvStrength.text = "均衡力度：${(strength() * 100).toInt()}%"
        })

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

    private fun simpleSeek(onChange: () -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) = onChange()
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {}
    }

    /** 已选文件列表：文件名 + 删除按钮。 */
    private fun refreshFileList() {
        llFiles.removeAllViews()
        val dp = resources.displayMetrics.density
        for (pf in picked.toList()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val tv = TextView(this).apply {
                text = pf.name
                textSize = 13f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val del = TextView(this).apply {
                text = "✕"
                textSize = 16f
                setPadding((12 * dp).toInt(), (4 * dp).toInt(), (12 * dp).toInt(), (4 * dp).toInt())
                setOnClickListener {
                    if (busy) return@setOnClickListener
                    picked.remove(pf)
                    refreshFileList()
                }
            }
            row.addView(tv)
            row.addView(del)
            llFiles.addView(row)
        }
        // 超过 5 个时列表内滚动，避免挤掉下面的日志区
        svFiles.layoutParams = svFiles.layoutParams.apply {
            height = if (picked.size > 5) (170 * dp).toInt()
            else LinearLayout.LayoutParams.WRAP_CONTENT
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
        sbTarget.isEnabled = !b
        sbStrength.isEnabled = !b
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
        val files = picked.toList()
        setUiBusy(true)
        tvLog.text = ""
        lifecycleScope.launch(Dispatchers.IO) {
            var ok = 0
            val t0 = System.currentTimeMillis()
            for ((idx, pf) in files.withIndex()) {
                log("【${pf.name}】(${idx + 1}/${files.size})")
                val ft0 = System.currentTimeMillis()
                try {
                    if (processOne(pf.uri, pf.name, target, strength)) {
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

    /** 完整处理一个视频：扫描（含真峰值）→ 本地计算测量值 → 编码 → 存入相册。 */
    private fun processOne(uri: Uri, name: String, target: Double, strength: Double): Boolean {
        currentDurationMs = durationMs(uri)

        stage("第 1 步 / 共 3 步：扫描响度…")
        scanPhase = true
        val scan = FFmpegKit.executeWithArguments(
            arrayOf(
                "-hide_banner", "-nostats",
                "-i", FFmpegKitConfig.getSafParameterForRead(this, uri),
                "-map", "0:a:0", "-af", "ebur128=peak=true", "-f", "null", "-",
            )
        )
        scanPhase = false
        if (!ReturnCode.isSuccess(scan.returnCode)) {
            log("  失败：无法读取音频（文件可能没有声音轨）")
            return false
        }
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
            stage("第 2 步 / 共 3 步：生成视频（画面直接复制）…")
            val outTmp = File(cacheDir, "out_${System.currentTimeMillis()}.mp4")
            val enc = FFmpegKit.executeWithArguments(
                arrayOf(
                    "-hide_banner", "-y",
                    "-i", FFmpegKitConfig.getSafParameterForRead(this, uri),
                    "-map", "0:v?", "-map", "0:a:0",
                    "-c:v", "copy",
                    "-af", Engine.buildFilter(target, vol, measured),
                    "-c:a", "aac", "-b:a", "192k",
                    outTmp.absolutePath,
                )
            )
            if (!ReturnCode.isSuccess(enc.returnCode) || !outTmp.exists()) {
                log("  失败：ffmpeg 处理出错：")
                log("  " + enc.allLogsAsString.lines().takeLast(5).joinToString("\n  "))
                outTmp.delete()
                return false
            }

            stage("第 3 步 / 共 3 步：保存到相册…")
            val base = name.substringBeforeLast('.')
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "${base}_均衡.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/响度均衡")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val outUri = contentResolver.insert(
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values
            )
            if (outUri == null) {
                log("  失败：无法写入相册")
                outTmp.delete()
                return false
            }
            contentResolver.openOutputStream(outUri)!!.use { os ->
                outTmp.inputStream().use { it.copyTo(os) }
            }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            contentResolver.update(outUri, values, null, null)
            outTmp.delete()

            log("  完成 → Movies/响度均衡/${base}_均衡.mp4")
            return true
        } finally {
            cmdFile.delete()
        }
    }
}
