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


def scan_timeline(audio_src: Path) -> list[tuple[float, float, float]]:
    """扫描全片响度时间线，返回 [(时间, 瞬时响度M, 短时响度S), ...]，约每 0.1s 一个点。"""
    r = run([
        "ffmpeg", "-hide_banner", "-nostats", "-i", str(audio_src),
        "-map", "0:a:0", "-af", "ebur128", "-f", "null", "-",
    ])
    pts = []
    for m in re.finditer(
            r"t:\s*([\d.]+)\s+TARGET.*?M:\s*(-?[\d.]+|nan)\s+S:\s*(-?[\d.]+|nan)", r.stderr):
        t = float(m.group(1))
        mm = -120.0 if m.group(2) == "nan" else float(m.group(2))
        ss = -120.0 if m.group(3) == "nan" else float(m.group(3))
        pts.append((t, mm, ss))
    return pts


def make_segments(pts: list[tuple[float, float, float]], target: float,
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
    for i, (t, _, s) in enumerate(pts):
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


def write_gain_cmds(segs: list[tuple[float, float, float]], cmd_path: Path) -> str:
    """把分段增益写成 asendcmd 命令文件，返回对应的滤镜串。

    ffmpeg 表达式解析器有约 100 个运算符的硬上限，长视频段数多时表达式必炸；
    sendcmd 命令文件没有长度限制。段内增益恒定，边界处 SEG_RAMP 秒内
    每 0.05s 一步渐变（步长 ≤1.2dB，且边界都吸附在停顿处，听不出台阶）。
    """
    # 折线节点：每个边界前后各一个点，段内平直
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


def measure(path: Path, target: float, vol: str) -> tuple[dict | None, str]:
    """第一遍：跑完整滤镜链测量响度，解析 loudnorm 输出的 JSON。失败时返回 (None, 错误摘要)。"""
    r = run([
        "ffmpeg", "-hide_banner", "-nostats", "-i", str(path),
        "-map", "0:a:0", "-af", build_filter(target, vol),
        "-f", "null", "-",
    ])
    # loudnorm 的 JSON 打印在 stderr 末尾
    m = re.search(r"\{[^{}]*\"input_i\"[^{}]*\}", r.stderr, re.DOTALL)
    if not m:
        tail = "\n    ".join(
            ln for ln in r.stderr.strip().splitlines()[-5:] if len(ln) < 300)
        return None, tail
    return json.loads(m.group(0)), ""


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

        measured, err = measure(path, target, vol)
        if measured is None:
            lines.append(f"  失败：无法分析响度：\n    {err}")
            return False, "\n".join(lines)
        lines.append(f"  分段调整后响度：{measured['input_i']} LUFS，波动范围：{measured['input_lra']} LU")

        out_path = claim_out_path(path, out_dir, claimed, name_lock)
        # 不直接写最终文件名：ffmpeg 中途失败时可能留下一个能看到但无法播放的半成品。
        # 临时文件放在同一目录，成功后用原子替换完成落盘；任何失败都会进入 finally 清理。
        with tempfile.NamedTemporaryFile(
                prefix=f".{out_path.stem}_", suffix=f".partial{out_path.suffix}",
                dir=out_dir, delete=False) as tmp:
            tmp_path = Path(tmp.name)
        try:
            r = run([
                "ffmpeg", "-hide_banner", "-nostats", "-y", "-i", str(path),
                "-map", "0:v?", "-map", "0:a:0",
                "-c:v", "copy",
                "-af", build_filter(target, vol, measured),
                "-c:a", "aac", "-b:a", "192k",
                str(tmp_path),
            ])
            if r.returncode != 0 or not tmp_path.exists() or tmp_path.stat().st_size == 0:
                tail = "\n    ".join(r.stderr.strip().splitlines()[-5:])
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
    parser = argparse.ArgumentParser(description="视频音量均衡：按段提升小声部分，大声段和段内动态保持自然。")
    parser.add_argument("paths", nargs="*", help="视频文件或文件夹，不填则处理脚本所在文件夹")
    parser.add_argument("--target", type=float, default=-16.0, help="目标响度 LUFS（默认 -16）")
    parser.add_argument("--strength", type=float, default=0.85,
                        help="均衡强度 0~1（默认 0.85，1=每段完全拉到目标响度）")
    parser.add_argument("--jobs", type=int, default=3, help="同时处理几个视频（默认 3）")
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
