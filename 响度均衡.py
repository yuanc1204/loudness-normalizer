# -*- coding: utf-8 -*-
"""
视频音量均衡脚本（按段均衡）
自动把视频按响度切段：小声段整段提升、大声段基本不动，段内自然起伏原样保留，
增益切换藏在说话停顿处，最后整片以两遍线性 loudnorm 统一到目标响度。
视频画面直接复制不重新编码，只处理音频，速度快、画质无损。

用法：
    python 响度均衡.py                      处理本文件夹里的所有视频
    python 响度均衡.py 视频.mp4             处理指定文件（可以多个）
    python 响度均衡.py 某个文件夹           处理该文件夹里的所有视频
    python 响度均衡.py --target -14 ...     自定义目标响度（LUFS）
    python 响度均衡.py --strength 0.95 ...  均衡力度 0~1，越大越平
"""

import argparse
import json
import math
import re
import shutil
import subprocess
import sys
import tempfile
import threading
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

VIDEO_EXTS = {".mp4", ".mkv", ".mov", ".avi", ".flv", ".ts", ".m4v", ".webm", ".wmv", ".mpg", ".mpeg"}
OUTPUT_DIR_NAME = "输出"
SCRIPT_DIR = Path(__file__).resolve().parent

TARGET_LRA = 7.0    # loudnorm 的响度范围参数（线性模式下只作下限用）
TRUE_PEAK = -1.5    # 真峰值上限 dBTP

# 安全限幅器：只拦截分段增益后偶发的过载峰（如小声段里突然的喊叫），不干预正常电平
SEG_SAFETY = "alimiter=limit=0.891:level=false"

# 分段参数
SEG_MIN_LEN = 8.0       # 每段最短秒数
SEG_SPLIT_DB = 6.0      # 响度水平持续偏离多少 dB 视为进入新段
SEG_SUSTAIN = 2.0       # 偏离需要持续多少秒才确认分段
SEG_RAMP = 1.0          # 段边界增益渐变时长（秒）
SEG_MAX_GAIN = 24.0     # 单段最大提升 dB
SEG_MIN_GAIN = -12.0    # 单段最大压低 dB


def run(cmd, **kwargs):
    return subprocess.run(
        cmd, capture_output=True, text=True, encoding="utf-8", errors="replace",
        cwd=SCRIPT_DIR, **kwargs
    )


def check_ffmpeg():
    if shutil.which("ffmpeg") is None or shutil.which("ffprobe") is None:
        print("错误：找不到 ffmpeg/ffprobe，请先安装 ffmpeg 并确保在 PATH 中。")
        sys.exit(1)


def has_audio(path: Path) -> bool:
    r = run([
        "ffprobe", "-v", "error", "-select_streams", "a",
        "-show_entries", "stream=index", "-of", "csv=p=0", str(path),
    ])
    return bool(r.stdout.strip())


def power_mean_db(vals: list[float]) -> float:
    if not vals:
        return -70.0
    return 10 * math.log10(sum(10 ** (v / 10) for v in vals) / len(vals))


def gated_loudness(m_vals: list[float]) -> float:
    """段响度：对 0.1s 粒度的瞬时响度做门限能量平均（仿 BS.1770 门限，忽略静音间隙）。"""
    vals = [m for m in m_vals if m > -70]
    if not vals:
        return -70.0
    m1 = power_mean_db(vals)
    vals2 = [m for m in vals if m > m1 - 10]
    return power_mean_db(vals2 or vals)


def scan_timeline(audio_src: Path) -> list[tuple[float, float, float, float]]:
    """扫描全片响度时间线，返回 [(时间, 瞬时响度M, 短时响度S, 真峰值TP), ...]，约每 0.1s 一个点。"""
    r = run([
        "ffmpeg", "-hide_banner", "-nostats", "-i", str(audio_src),
        "-map", "0:a:0", "-af", "ebur128=peak=true", "-f", "null", "-",
    ])
    pts = []
    for line in r.stderr.splitlines():
        m = re.search(r"t:\s*([\d.]+)\s+TARGET.*?M:\s*(-?[\d.]+|nan)\s+S:\s*(-?[\d.]+|nan)", line)
        if not m:
            continue
        t = float(m.group(1))
        mm = -120.0 if m.group(2) == "nan" else float(m.group(2))
        ss = -120.0 if m.group(3) == "nan" else float(m.group(3))

        tp = -120.0
        m_ftpk = re.search(r"FTPK:\s*([-\d.\s]+?)(?:TPK:|$)", line)
        if m_ftpk:
            peaks = [float(x) for x in m_ftpk.group(1).split() if x != "nan"]
            if peaks:
                tp = max(peaks)
        pts.append((t, mm, ss, tp))
    return pts


def make_segments(pts: list[tuple[float, float, float, float]], target: float,
                  strength: float) -> list[tuple[float, float, float]]:
    """按响度水平分段，返回 [(起点, 终点, 增益dB), ...]。

    以短时响度 S 持续偏离本段参考响度 SEG_SPLIT_DB 达 SEG_SUSTAIN 秒为分段信号，
    边界按方向回溯吸附到响度拐点（说话停顿处），段内增益恒定。
    """
    if not pts:
        return []
    bounds = [0.0]
    seg_start_i = 0
    dev_start = None  # 偏离开始的下标
    cur = None        # 本段参考响度：偏离期间冻结，否则每 1s 用最近 30s 窗口重算
    for i, (t, _, s, _) in enumerate(pts):
        if t - pts[seg_start_i][0] < SEG_MIN_LEN:
            continue
        if dev_start is None and (cur is None or i % 10 == 0):
            lo = max(seg_start_i, i - 300)
            cur = gated_loudness([p[1] for p in pts[lo:i]])
        if s > -120 and cur is not None and abs(s - cur) > SEG_SPLIT_DB:
            if dev_start is None:
                dev_start = i
            elif t - pts[dev_start][0] >= SEG_SUSTAIN:
                # 确认分段。短时响度 S 有 1~3s 检测滞后，真实转折点在 dev_start 之前：
                # 按方向回溯，找瞬时响度 M 变化最陡的位置作为边界
                rising = s > cur
                back = 25 if rising else 45
                lo = max(seg_start_i + 1, dev_start - back)
                hi = min(len(pts) - 2, dev_start + 5)
                if hi > lo:
                    if rising:
                        j = max(range(lo, hi), key=lambda k: pts[k + 1][1] - pts[k][1])
                        snap = j        # 跳升前的低点
                    else:
                        j = max(range(lo, hi), key=lambda k: pts[k][1] - pts[k + 1][1])
                        snap = j + 1    # 跌落后的低点
                else:
                    snap = dev_start
                b_t = pts[snap][0]
                if b_t - bounds[-1] >= SEG_MIN_LEN:
                    bounds.append(b_t)
                    seg_start_i = snap
                dev_start = None
                cur = None
        else:
            dev_start = None
    bounds.append(pts[-1][0] + 1.0)

    segs = []
    for a, b in zip(bounds, bounds[1:]):
        m_vals = [p[1] for p in pts if a <= p[0] < b]
        loud = gated_loudness(m_vals)
        gain = max(SEG_MIN_GAIN, min(SEG_MAX_GAIN, strength * (target - loud)))
        segs.append((a, b, gain))

    # 相邻增益差小于 2dB 的段合并，减少不必要的过渡
    merged = [segs[0]]
    for s in segs[1:]:
        if abs(s[2] - merged[-1][2]) < 2.0:
            merged[-1] = (merged[-1][0], s[1], (merged[-1][2] + s[2]) / 2)
        else:
            merged.append(s)
    return merged


def make_knots(segs: list[tuple[float, float, float]]) -> list[tuple[float, float]]:
    knots: list[tuple[float, float]] = []
    for i, (a, b, g) in enumerate(segs):
        if i == 0:
            knots.append((a, g))
        else:
            knots.append((a + SEG_RAMP / 2, g))
        if i < len(segs) - 1:
            knots.append((b - SEG_RAMP / 2, g))
        else:
            knots.append((b, g))
    return knots


def gain_at(knots: list[tuple[float, float]], t: float) -> float:
    if t <= knots[0][0]:
        return knots[0][1]
    for j in range(len(knots) - 1):
        t0, g0 = knots[j]
        t1, g1 = knots[j + 1]
        if t <= t1:
            return g0 if t1 - t0 <= 0 else g0 + (g1 - g0) * (t - t0) / (t1 - t0)
    return knots[-1][1]


def compute_measured(pts: list[tuple[float, float, float, float]],
                     segs: list[tuple[float, float, float]]) -> dict:
    """根据扫描 pts 数据与分段增益在内存中直接算 loudnorm 所需的测量参数，免跑第二遍 FFmpeg Pass。"""
    if not pts or not segs:
        return {"input_i": "-16.00", "input_lra": "7.00", "input_tp": "-1.50", "input_thresh": "-26.00"}
    knots = make_knots(segs)
    gains = [gain_at(knots, p[0]) for p in pts]

    i_vals = [p[1] + g for p, g in zip(pts, gains)]
    i_loud = max(-99.0, min(0.0, gated_loudness(i_vals)))

    s_shifts = [p[2] + g for p, g in zip(pts, gains) if p[2] > -110]
    v = [s for s in s_shifts if s > -70]
    if len(v) < 2:
        lra = 0.0
    else:
        gate = power_mean_db(v) - 20
        kept = sorted([s for s in v if s >= gate])
        if len(kept) < 2:
            lra = 0.0
        else:
            def pct(p: float) -> float:
                x = p * (len(kept) - 1)
                i0 = int(x)
                if i0 + 1 >= len(kept):
                    return kept[i0]
                return kept[i0] + (kept[i0 + 1] - kept[i0]) * (x - i0)
            lra = pct(0.95) - pct(0.10)
    lra_c = max(0.0, min(99.0, lra))

    tp_vals = [p[3] + g for p, g in zip(pts, gains)]
    max_tp = max(tp_vals) if tp_vals else -120.0
    tp_c = max(-99.0, min(-1.0, max_tp))

    thresh = max(-99.0, min(0.0, i_loud - 10.0))
    return {
        "input_i": f"{i_loud:.2f}",
        "input_lra": f"{lra_c:.2f}",
        "input_tp": f"{tp_c:.2f}",
        "input_thresh": f"{thresh:.2f}"
    }


def write_gain_cmds(segs: list[tuple[float, float, float]], cmd_path: Path) -> str:
    """把分段增益写成 asendcmd 命令文件，返回对应的滤镜串。

    ffmpeg 表达式解析器有约 100 个运算符的硬上限，长视频段数多时表达式必炸；
    sendcmd 命令文件没有长度限制。段内增益恒定，边界处 SEG_RAMP 秒内
    每 0.05s 一步渐变（步长 ≤1.2dB，且边界都吸附在停顿处，听不出台阶）。
    """
    knots = make_knots(segs)

    def lin(db: float) -> str:
        return f"{10 ** (db / 20):.6f}"

    cmds = []
    for (t0, g0), (t1, g1) in zip(knots, knots[1:]):
        if t1 - t0 <= 0.01 or abs(g1 - g0) < 0.01:
            continue
        steps = max(2, round((t1 - t0) / 0.05))
        for k in range(1, steps + 1):
            tt = t0 + (t1 - t0) * k / steps
            gg = g0 + (g1 - g0) * k / steps
            cmds.append(f"{tt:.3f} volume volume {lin(gg)};")
    cmd_path.write_text("\n".join(cmds) or "0.01 volume volume 1.0;", encoding="ascii")
    # Windows 路径的冒号会被滤镜参数解析器当分隔符，必须转义成 \:
    p = str(cmd_path).replace("\\", "/").replace(":", "\\:")
    return f"asendcmd=f='{p}',volume=volume={lin(knots[0][1])}"


def build_filter(target: float, vol: str, measured: dict | None = None) -> str:
    if measured:
        # 第二遍强制线性（全程恒定增益）：动态模式的 loudnorm 会把说话间隙的底噪
        # 重新抬响；逐段的调整已由分段增益完成，这里只负责整体响度
        lra_target = max(TARGET_LRA, min(50.0, float(measured["input_lra"]) + 1))
        loudnorm = (
            f"loudnorm=I={target}:LRA={lra_target}:TP={TRUE_PEAK}:linear=true"
            f":measured_I={measured['input_i']}"
            f":measured_LRA={measured['input_lra']}"
            f":measured_TP={measured['input_tp']}"
            f":measured_thresh={measured['input_thresh']}"
        )
    else:
        loudnorm = f"loudnorm=I={target}:LRA={TARGET_LRA}:TP={TRUE_PEAK}:print_format=json"
    return ",".join((vol, SEG_SAFETY, loudnorm))


PRINT_LOCK = threading.Lock()


def announce(msg: str):
    with PRINT_LOCK:
        print(msg, flush=True)


def claim_out_path(path: Path, out_dir: Path, claimed: set[str], name_lock: threading.Lock) -> Path:
    # avi/flv/wmv 等旧容器对 AAC 支持不好，统一转存为 mp4
    suffix = path.suffix if path.suffix.lower() in {".mp4", ".mkv", ".mov", ".m4v", ".ts"} else ".mp4"
    # 输出目录是固定的，不同文件夹里的同名视频加序号避免互相覆盖；
    # 并行时用 claimed 集合防止两个任务同时选中同一个名字
    with name_lock:
        out_path = out_dir / (path.stem + suffix)
        n = 1
        while out_path.exists() or str(out_path) in claimed:
            n += 1
            out_path = out_dir / f"{path.stem}_{n}{suffix}"
        claimed.add(str(out_path))
    return out_path


def process(path: Path, out_dir: Path, target: float, strength: float,
            claimed: set[str], name_lock: threading.Lock) -> tuple[bool, str]:
    """处理一个视频，返回 (是否成功, 汇总日志)。并行运行，日志攒齐后一次性打印。"""
    lines = [f"\n【{path.name}】"]
    announce(f">> 开始：{path.name}")

    if not has_audio(path):
        lines.append("  跳过：这个文件没有音频。")
        return False, "\n".join(lines)

    with tempfile.TemporaryDirectory(prefix="loudnorm_") as td:
        pts = scan_timeline(path)
        if not pts:
            lines.append("  失败：无法扫描响度时间线。")
            return False, "\n".join(lines)
        segs = make_segments(pts, target, strength)
        vol = write_gain_cmds(segs, Path(td) / "gain.cmd")
        changed = [(a, b, g) for a, b, g in segs if abs(g) >= 0.5]
        lines.append(f"  共分 {len(segs)} 段，调整了 {len(changed)} 段：")
        row = []
        for a, b, g in changed:
            row.append(f"{f'{a:.0f}s~{b:.0f}s':<13} {'+' if g >= 0 else ''}{g:.1f}dB")
            if len(row) == 3:
                lines.append("    " + "   ".join(row))
                row = []
        if row:
            lines.append("    " + "   ".join(row))

        # 内存高性能推算响度参数，直接省去原本全长第二遍 FFmpeg 测量 pass
        measured = compute_measured(pts, segs)
        lines.append(f"  分段调整推算响度：{measured['input_i']} LUFS，波动范围：{measured['input_lra']} LU")

        out_path = claim_out_path(path, out_dir, claimed, name_lock)
        with tempfile.NamedTemporaryFile(
                prefix=f".{out_path.stem}_", suffix=f".partial{out_path.suffix}",
                dir=out_dir, delete=False) as tmp:
            tmp_path = Path(tmp.name)
        try:
            cmd = [
                "ffmpeg", "-hide_banner", "-nostats", "-y", "-i", str(path),
                "-map", "0:v?", "-map", "0:a:0",
                "-c:v", "copy",
                "-af", build_filter(target, vol, measured),
                "-c:a", "aac", "-b:a", "192k",
                str(tmp_path),
            ]
            proc = subprocess.Popen(
                cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                text=True, encoding="utf-8", errors="replace", cwd=SCRIPT_DIR
            )
            total_dur = pts[-1][0] if pts else 1.0
            last_pct = -10
            stderr_lines = []
            if proc.stderr:
                for line in proc.stderr:
                    stderr_lines.append(line)
                    m_time = re.search(r"time=\s*(\d+):(\d+):([\d.]+)", line)
                    if m_time and total_dur > 0:
                        cur_s = int(m_time.group(1)) * 3600 + int(m_time.group(2)) * 60 + float(m_time.group(3))
                        pct = min(100, int(cur_s / total_dur * 100))
                        m_spd = re.search(r"speed=\s*([\d.e+]+)x", line)
                        spd = m_spd.group(1) + "x" if m_spd else ""
                        if pct >= last_pct + 25:
                            last_pct = pct
                            announce(f"  进度：{path.name} → {pct}% ({spd})".strip())
            proc.wait()
            if proc.returncode != 0 or not tmp_path.exists() or tmp_path.stat().st_size == 0:
                tail = "\n    ".join(ln.strip() for ln in stderr_lines[-5:] if ln.strip())
                lines.append(f"  失败：ffmpeg 处理出错：\n    {tail}")
                return False, "\n".join(lines)
            tmp_path.replace(out_path)
        except OSError as e:
            lines.append(f"  失败：无法保存成品：{e}")
            return False, "\n".join(lines)
        finally:
            tmp_path.unlink(missing_ok=True)

    lines.append(f"  完成 → {out_path}")
    return True, "\n".join(lines)


def collect_videos(args_paths: list[str], script_dir: Path) -> list[Path]:
    videos: list[Path] = []
    if not args_paths:
        sources = [script_dir]
    else:
        sources = [Path(p) for p in args_paths]
    for src in sources:
        if src.is_dir():
            videos += sorted(
                p.resolve() for p in src.iterdir()
                if p.suffix.lower() in VIDEO_EXTS and p.parent.name != OUTPUT_DIR_NAME
            )
        elif src.is_file():
            # ffmpeg 以脚本目录为工作目录运行，输入路径必须转成绝对路径
            videos.append(src.resolve())
        else:
            print(f"警告：找不到 {src}，已跳过。")
    return videos


def main():
    import os
    parser = argparse.ArgumentParser(description="视频音量均衡：按段提升小声部分，大声段和段内动态保持自然。")
    parser.add_argument("paths", nargs="*", help="视频文件或文件夹，不填则处理脚本所在文件夹")
    parser.add_argument("--target", type=float, default=-16.0, help="目标响度 LUFS（默认 -16）")
    parser.add_argument("--strength", type=float, default=0.85,
                        help="均衡强度 0~1（默认 0.85，1=每段完全拉到目标响度）")
    default_jobs = max(1, min(4, os.cpu_count() or 3))
    parser.add_argument("--jobs", type=int, default=default_jobs, help=f"同时处理几个视频（默认 {default_jobs}）")
    args = parser.parse_args()

    check_ffmpeg()

    videos = collect_videos(args.paths, SCRIPT_DIR)
    if not videos:
        print("没有找到要处理的视频文件。把视频放进这个文件夹，或把文件拖到 响度均衡.bat 上。")
        return

    jobs = max(1, min(args.jobs, len(videos)))
    print(f"共 {len(videos)} 个视频，并行 {jobs} 个，目标响度：{args.target} LUFS，"
          f"强度：{args.strength}")

    out_dir = SCRIPT_DIR / OUTPUT_DIR_NAME
    out_dir.mkdir(exist_ok=True)
    claimed: set[str] = set()
    name_lock = threading.Lock()
    ok = 0
    with ThreadPoolExecutor(max_workers=jobs) as pool:
        futures = [
            pool.submit(process, v, out_dir, args.target, args.strength, claimed, name_lock)
            for v in videos
        ]
        for fut in as_completed(futures):
            success, report = fut.result()
            announce(report)
            ok += success

    print(f"\n全部完成：成功 {ok} 个，失败/跳过 {len(videos) - ok} 个。输出在：{out_dir}")


if __name__ == "__main__":
    main()

