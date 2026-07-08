package com.yc.loudnorm

import java.io.File
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 响度均衡核心算法，逐函数移植自电脑版 响度均衡.py。
 * 按响度把视频自动分段：小声段整段提升、大声段基本不动，
 * 段内自然起伏保留，增益切换藏在说话停顿处。
 */
object Engine {

    const val TARGET_LRA = 7.0    // loudnorm 的响度范围参数（线性模式下只作下限用）
    const val TRUE_PEAK = -1.5    // 真峰值上限 dBTP

    // 安全限幅器：只拦截分段增益后偶发的过载峰，不干预正常电平
    const val SEG_SAFETY = "alimiter=limit=0.891:level=false"

    // 分段参数（与电脑版一致）
    const val SEG_MIN_LEN = 8.0
    const val SEG_SPLIT_DB = 6.0
    const val SEG_SUSTAIN = 2.0
    const val SEG_RAMP = 1.0
    const val SEG_MAX_GAIN = 24.0
    const val SEG_MIN_GAIN = -12.0

    data class Pt(val t: Double, val m: Double, val s: Double, val tp: Double)
    data class Seg(val a: Double, val b: Double, val g: Double)
    data class Measured(val i: String, val lra: String, val tp: String, val thresh: String)

    private fun powerMeanDb(vals: List<Double>): Double {
        if (vals.isEmpty()) return -70.0
        return 10 * log10(vals.sumOf { 10.0.pow(it / 10) } / vals.size)
    }

    /** 段响度：对 0.1s 粒度的瞬时响度做门限能量平均（仿 BS.1770 门限，忽略静音间隙）。 */
    private fun gatedLoudness(mVals: List<Double>): Double {
        val v = mVals.filter { it > -70 }
        if (v.isEmpty()) return -70.0
        val m1 = powerMeanDb(v)
        val v2 = v.filter { it > m1 - 10 }
        return powerMeanDb(if (v2.isEmpty()) v else v2)
    }

    private val timelineRe = Regex(
        """t:\s*([\d.]+)\s+TARGET.*?M:\s*(-?[\d.]+|nan)\s+S:\s*(-?[\d.]+|nan)(?:.*?FTPK:\s*([^d]*?)dBFS)?"""
    )

    /** 从 ebur128（peak=true）日志解析响度时间线，约每 0.1s 一个点。 */
    fun parseTimeline(log: String): List<Pt> =
        timelineRe.findAll(log).map { mr ->
            val t = mr.groupValues[1].toDouble()
            val m = mr.groupValues[2].let { if (it == "nan") -120.0 else it.toDouble() }
            val s = mr.groupValues[3].let { if (it == "nan") -120.0 else it.toDouble() }
            // FTPK 为该帧各声道的真峰值，取最大；静音显示 -inf
            val tp = mr.groupValues[4].split(" ", "\t")
                .mapNotNull { it.trim().toDoubleOrNull() }
                .maxOrNull() ?: -120.0
            Pt(t, m, s, tp)
        }.toList()

    /** 按响度水平分段。以短时响度 S 持续偏离本段参考响度为分段信号，边界回溯吸附到响度拐点。 */
    fun makeSegments(pts: List<Pt>, target: Double, strength: Double): List<Seg> {
        if (pts.isEmpty()) return emptyList()
        val bounds = mutableListOf(0.0)
        var segStartI = 0
        var devStart = -1   // 偏离开始的下标，-1 表示无
        var cur = Double.NaN // 本段参考响度：偏离期间冻结，否则每 1s 用最近 30s 窗口重算
        for (i in pts.indices) {
            val (t, _, s) = pts[i]
            if (t - pts[segStartI].t < SEG_MIN_LEN) continue
            if (devStart < 0 && (cur.isNaN() || i % 10 == 0)) {
                val lo = max(segStartI, i - 300)
                cur = gatedLoudness(pts.subList(lo, i).map { it.m })
            }
            if (s > -120 && !cur.isNaN() && abs(s - cur) > SEG_SPLIT_DB) {
                if (devStart < 0) {
                    devStart = i
                } else if (t - pts[devStart].t >= SEG_SUSTAIN) {
                    // 确认分段。短时响度有 1~3s 检测滞后，按方向回溯找瞬时响度变化最陡处
                    val rising = s > cur
                    val back = if (rising) 25 else 45
                    val lo = max(segStartI + 1, devStart - back)
                    val hi = min(pts.size - 2, devStart + 5)
                    val snap = if (hi > lo) {
                        if (rising) {
                            (lo until hi).maxBy { k -> pts[k + 1].m - pts[k].m }
                        } else {
                            (lo until hi).maxBy { k -> pts[k].m - pts[k + 1].m } + 1
                        }
                    } else devStart
                    val bT = pts[snap].t
                    if (bT - bounds.last() >= SEG_MIN_LEN) {
                        bounds.add(bT)
                        segStartI = snap
                    }
                    devStart = -1
                    cur = Double.NaN
                }
            } else {
                devStart = -1
            }
        }
        bounds.add(pts.last().t + 1.0)

        val segs = mutableListOf<Seg>()
        for (idx in 0 until bounds.size - 1) {
            val a = bounds[idx]
            val b = bounds[idx + 1]
            val mVals = pts.filter { it.t in a..b && it.t < b }.map { it.m }
            val loud = gatedLoudness(mVals)
            val gain = max(SEG_MIN_GAIN, min(SEG_MAX_GAIN, strength * (target - loud)))
            segs.add(Seg(a, b, gain))
        }

        // 相邻增益差小于 2dB 的段合并
        val merged = mutableListOf(segs[0])
        for (sg in segs.drop(1)) {
            val last = merged.last()
            if (abs(sg.g - last.g) < 2.0) {
                merged[merged.size - 1] = Seg(last.a, sg.b, (last.g + sg.g) / 2)
            } else {
                merged.add(sg)
            }
        }
        return merged
    }

    /** 分段增益 → 折线节点（每个边界前后各一个点，段内平直）。 */
    fun makeKnots(segs: List<Seg>): List<Pair<Double, Double>> {
        val knots = mutableListOf<Pair<Double, Double>>()
        for ((i, seg) in segs.withIndex()) {
            if (i == 0) knots.add(seg.a to seg.g) else knots.add(seg.a + SEG_RAMP / 2 to seg.g)
            if (i < segs.size - 1) knots.add(seg.b - SEG_RAMP / 2 to seg.g) else knots.add(seg.b to seg.g)
        }
        return knots
    }

    /** 折线在 t 时刻的增益 dB。 */
    private fun gainAt(knots: List<Pair<Double, Double>>, t: Double): Double {
        if (t <= knots[0].first) return knots[0].second
        for (j in 0 until knots.size - 1) {
            val (t0, g0) = knots[j]
            val (t1, g1) = knots[j + 1]
            if (t <= t1) {
                return if (t1 - t0 <= 0.0) g0 else g0 + (g1 - g0) * (t - t0) / (t1 - t0)
            }
        }
        return knots.last().second
    }

    /**
     * 直接从扫描数据算出 loudnorm 需要的四个测量值，省掉整整一遍解码。
     * I 按 BS.1770 门限能量平均，LRA 按 EBU Tech 3342（P95-P10），
     * 真峰值取逐帧 FTPK+增益的最大值并按限幅器上限封顶。与真测相比误差约 ±0.5 dB。
     */
    fun computeMeasured(pts: List<Pt>, knots: List<Pair<Double, Double>>): Measured {
        val gains = pts.map { gainAt(knots, it.t) }
        val i = gatedLoudness(pts.mapIndexed { k, p -> p.m + gains[k] })
        val sShift = pts.mapIndexedNotNull { k, p -> if (p.s > -110) p.s + gains[k] else null }
        val v = sShift.filter { it > -70 }
        val lra = if (v.size < 2) 0.0 else {
            val gate = powerMeanDb(v) - 20
            val kept = v.filter { it >= gate }.sorted()
            if (kept.size < 2) 0.0 else {
                fun pct(p: Double): Double {
                    val x = p * (kept.size - 1)
                    val i0 = x.toInt()
                    return if (i0 + 1 >= kept.size) kept[i0]
                    else kept[i0] + (kept[i0 + 1] - kept[i0]) * (x - i0)
                }
                pct(0.95) - pct(0.10)
            }
        }
        val tp = min(pts.mapIndexed { k, p -> p.tp + gains[k] }.max(), -1.0)
        fun f(d: Double) = String.format(java.util.Locale.US, "%.2f", d)
        return Measured(f(i), f(lra), f(tp), f(i - 10))
    }

    /**
     * 把分段增益写成 asendcmd 命令文件，返回对应的滤镜串。
     * ffmpeg 表达式解析器有约 100 个运算符的硬上限，段多时必须走命令文件。
     */
    fun writeGainCmds(knots: List<Pair<Double, Double>>, cmdFile: File): String {
        fun lin(db: Double) = String.format(java.util.Locale.US, "%.6f", 10.0.pow(db / 20))

        val cmds = StringBuilder()
        for (j in 0 until knots.size - 1) {
            val (t0, g0) = knots[j]
            val (t1, g1) = knots[j + 1]
            if (t1 - t0 <= 0.01 || abs(g1 - g0) < 0.01) continue
            val steps = max(2, ((t1 - t0) / 0.05).roundToInt())
            for (k in 1..steps) {
                val tt = t0 + (t1 - t0) * k / steps
                val gg = g0 + (g1 - g0) * k / steps
                cmds.append(String.format(java.util.Locale.US, "%.3f volume volume %s;\n", tt, lin(gg)))
            }
        }
        cmdFile.writeText(cmds.ifEmpty { StringBuilder("0.01 volume volume 1.0;") }.toString())
        val p = cmdFile.absolutePath.replace("\\", "/").replace(":", "\\:")
        return "asendcmd=f='$p',volume=volume=${lin(knots[0].second)}"
    }

    /** 组装完整滤镜链：分段增益 → 安全限幅 → loudnorm（线性模式，恒定增益）。 */
    fun buildFilter(target: Double, vol: String, measured: Measured): String {
        val lraTarget = max(TARGET_LRA, min(50.0, measured.lra.toDouble() + 1))
        val loudnorm = "loudnorm=I=$target:LRA=$lraTarget:TP=$TRUE_PEAK:linear=true" +
            ":measured_I=${measured.i}:measured_LRA=${measured.lra}" +
            ":measured_TP=${measured.tp}:measured_thresh=${measured.thresh}"
        return "$vol,$SEG_SAFETY,$loudnorm"
    }
}
