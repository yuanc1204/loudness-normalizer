# -*- coding: utf-8 -*-
"""
视频音量均衡脚本
把视频里忽高忽低的声音变均衡：只把大声段压下去、小声段保持不动，
最后整片以恒定增益统一到目标响度——环境音不会被逐段放大，听感自然。
视频画面直接复制不重新编码，只处理音频，速度快、画质无损。

用法：
    python 响度均衡.py                      处理本文件夹里的所有视频
    python 响度均衡.py 视频.mp4             处理指定文件（可以多个）
    python 响度均衡.py 某个文件夹           处理该文件夹里的所有视频
    python 响度均衡.py --target -14 ...     自定义目标响度（LUFS）
"""

import argparse
import json
import re
import shutil
import subprocess
import sys
import threading
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

VIDEO_EXTS = {".mp4", ".mkv", ".mov", ".avi", ".flv", ".ts", ".m4v", ".webm", ".wmv", ".mpg", ".mpeg"}
OUTPUT_DIR_NAME = "输出"
SCRIPT_DIR = Path(__file__).resolve().parent


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


# 降噪放在压缩之前：先在原始电平把稳定底噪滤掉
DENOISE_FILTERS = {
    "off": "",
    "mid": "highpass=f=70,afftdn=nr=12:tn=1",
    "high": "highpass=f=80,afftdn=nr=20:tn=1,afftdn=nr=12:tn=1",
}

# 只压大声、不抬小声：增益只会往下动，环境音永远不会被逐段抬响，
# 小声段与环境音的音量差整体由 loudnorm 的恒定增益统一提升
# 两级压缩：慢速级抹段落差（阈值 -35dB≈0.0178），快速级压瞬时峰（-25dB≈0.0562）
LEVELER = ("acompressor=threshold=0.0178:ratio=8:attack=50:release=800:knee=6,"
           "acompressor=threshold=0.0562:ratio=4:attack=5:release=250:knee=4")


def build_filter(target: float, lra: float, tp: float, denoise: str,
                 measured: dict | None = None) -> str:
    if measured:
        # 第二遍强制线性（全程恒定增益）：动态模式的 loudnorm 会把说话间隙的底噪
        # 重新抬响；逐段的动态处理已由压缩器完成，这里只负责整体响度
        lra_target = max(lra, min(50.0, float(measured["input_lra"]) + 1))
        loudnorm = (
            f"loudnorm=I={target}:LRA={lra_target}:TP={tp}:linear=true"
            f":measured_I={measured['input_i']}"
            f":measured_LRA={measured['input_lra']}"
            f":measured_TP={measured['input_tp']}"
            f":measured_thresh={measured['input_thresh']}"
        )
    else:
        loudnorm = f"loudnorm=I={target}:LRA={lra}:TP={tp}:print_format=json"
    dn = DENOISE_FILTERS[denoise]
    return ",".join(p for p in (dn, LEVELER, loudnorm) if p)


def measure(path: Path, target: float, lra: float, tp: float, denoise: str) -> dict | None:
    """第一遍：跑完整滤镜链测量响度，解析 loudnorm 输出的 JSON。"""
    r = run([
        "ffmpeg", "-hide_banner", "-nostats", "-i", str(path),
        "-map", "0:a:0", "-af", build_filter(target, lra, tp, denoise),
        "-f", "null", "-",
    ])
    # loudnorm 的 JSON 打印在 stderr 末尾
    m = re.search(r"\{[^{}]*\"input_i\"[^{}]*\}", r.stderr, re.DOTALL)
    if not m:
        return None
    return json.loads(m.group(0))


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


def process(path: Path, out_dir: Path, target: float, lra: float, tp: float,
            denoise: str, claimed: set[str],
            name_lock: threading.Lock) -> tuple[bool, str]:
    """处理一个视频，返回 (是否成功, 汇总日志)。并行运行，日志攒齐后一次性打印。"""
    lines = [f"\n【{path.name}】"]
    announce(f">> 开始：{path.name}")

    if not has_audio(path):
        lines.append("  跳过：这个文件没有音频。")
        return False, "\n".join(lines)

    measured = measure(path, target, lra, tp, denoise)
    if measured is None:
        lines.append("  失败：无法分析响度，文件可能已损坏。")
        return False, "\n".join(lines)
    lines.append(f"  压制大声段后响度：{measured['input_i']} LUFS，波动范围：{measured['input_lra']} LU")

    out_path = claim_out_path(path, out_dir, claimed, name_lock)
    r = run([
        "ffmpeg", "-hide_banner", "-nostats", "-y", "-i", str(path),
        "-map", "0:v?", "-map", "0:a:0",
        "-c:v", "copy",
        "-af", build_filter(target, lra, tp, denoise, measured),
        "-c:a", "aac", "-b:a", "192k",
        str(out_path),
    ])
    if r.returncode != 0 or not out_path.exists():
        tail = "\n    ".join(r.stderr.strip().splitlines()[-5:])
        lines.append(f"  失败：ffmpeg 处理出错：\n    {tail}")
        return False, "\n".join(lines)

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
    parser = argparse.ArgumentParser(description="视频音量均衡：压平大声段，环境音不会被逐段放大。")
    parser.add_argument("paths", nargs="*", help="视频文件或文件夹，不填则处理脚本所在文件夹")
    parser.add_argument("--target", type=float, default=-16.0, help="目标响度 LUFS（默认 -16）")
    parser.add_argument("--lra", type=float, default=7.0, help="允许的响度波动范围 LU（默认 7）")
    parser.add_argument("--tp", type=float, default=-1.5, help="真峰值上限 dBTP（默认 -1.5）")
    parser.add_argument("--denoise", choices=["off", "mid", "high"], default="mid",
                        help="降噪强度：off=不降噪，mid=标准（默认），high=强力（噪声大的录音用）")
    parser.add_argument("--jobs", type=int, default=3, help="同时处理几个视频（默认 3）")
    args = parser.parse_args()

    check_ffmpeg()

    videos = collect_videos(args.paths, SCRIPT_DIR)
    if not videos:
        print("没有找到要处理的视频文件。把视频放进这个文件夹，或把文件拖到 响度均衡.bat 上。")
        return

    jobs = max(1, min(args.jobs, len(videos)))
    print(f"共 {len(videos)} 个视频，并行 {jobs} 个，目标响度：{args.target} LUFS，"
          f"降噪：{args.denoise}")

    out_dir = SCRIPT_DIR / OUTPUT_DIR_NAME
    out_dir.mkdir(exist_ok=True)
    claimed: set[str] = set()
    name_lock = threading.Lock()
    ok = 0
    with ThreadPoolExecutor(max_workers=jobs) as pool:
        futures = [
            pool.submit(process, v, out_dir, args.target, args.lra, args.tp,
                        args.denoise, claimed, name_lock)
            for v in videos
        ]
        for fut in as_completed(futures):
            success, report = fut.result()
            announce(report)
            ok += success

    print(f"\n全部完成：成功 {ok} 个，失败/跳过 {len(videos) - ok} 个。输出在：{out_dir}")


if __name__ == "__main__":
    main()
