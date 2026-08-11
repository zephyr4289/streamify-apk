import csv
import json
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from typing import Optional, Dict, Any, Tuple, Union, List

from downloader.config import load_config
from downloader.cover_art import fetch_high_res_cover
from downloader.finder import discover_playlist_json
from downloader.ffmpeg_tagger import apply_native_metadata, crop_square_artwork
from downloader.lyrics import fetch_lyrics
from downloader.matcher import search_youtube
from downloader.progress import load_progress, save_progress, log_failed, log_review
from downloader.spotify_api import fetch_spotify_metadata_from_url, parse_spotify_url
from downloader.utils import (
    TRACKS_CSV,
    OUTPUT_DIR,
    run_command,
    sanitize_filename,
    print_banner,
    sync_to_android_music,
    find_android_music_dir,
    get_ytdlp_auth_args,
    get_audio_quality_args,
    generate_m3u8_playlist,
    acquire_termux_wake_lock,
    release_termux_wake_lock,
    send_termux_notification,
)

# Rich library optional import for thread-safe UI
try:
    from rich.progress import Progress, SpinnerColumn, TextColumn, BarColumn, TaskProgressColumn
    RICH_AVAILABLE = True
except ImportError:
    RICH_AVAILABLE = False

progress_lock = threading.Lock()


def prepare_csv(source_input: Optional[Union[str, Path]] = None) -> bool:
    """Parses Exportify JSON file or direct Spotify URL and prepares data/tracks.csv."""
    print_banner("Preparing Spotify Playlist CSV")
    tracks_data: List[Dict[str, Any]] = []

    if isinstance(source_input, str) and ("spotify.com" in source_input or "spotify:" in source_input):
        print(f"Resolving Spotify URL: {source_input}")
        name, tracks_data = fetch_spotify_metadata_from_url(source_input)
        if not tracks_data:
            print("ERROR: Could not fetch track metadata from Spotify URL.")
            return False
        print(f" ✓ Successfully loaded: {name or 'Spotify Link'}")
    else:
        source_json = discover_playlist_json(source_input)
        if not source_json:
            print("\nERROR: No valid Exportify playlist JSON found or specified.")
            return False

        try:
            with open(source_json, "r", encoding="utf-8", errors="replace") as f:
                raw_data = json.load(f)
        except Exception as e:
            print(f"ERROR: Could not read JSON file: {e}")
            return False

        raw_tracks = raw_data if isinstance(raw_data, list) else (raw_data.get("items") or raw_data.get("tracks") or []) if isinstance(raw_data, dict) else []

        for track in raw_tracks:
            if not isinstance(track, dict):
                continue
            t_obj = track.get("track") if isinstance(track.get("track"), dict) else track
            title = (t_obj.get("name") or t_obj.get("title") or t_obj.get("track_name") or t_obj.get("Track Name") or "").strip()
            artists_data = t_obj.get("artists") or t_obj.get("artist") or t_obj.get("Artist Name(s)")
            artists = ", ".join((a.get("name") if isinstance(a, dict) else str(a)).strip() for a in artists_data if a) if isinstance(artists_data, list) else str(artists_data or "").strip()
            album_data = t_obj.get("album") or t_obj.get("Album Name")
            album = album_data.get("name", "").strip() if isinstance(album_data, dict) else str(album_data or "").strip()
            images = album_data.get("images") if isinstance(album_data, dict) else []
            cover_url = images[0].get("url", "") if images and isinstance(images, list) else ""
            dur_ms = t_obj.get("duration_ms") or t_obj.get("Duration (ms)") or t_obj.get("duration") or 0
            if title:
                tracks_data.append({"title": title, "artist": artists, "album": album, "duration_sec": int(dur_ms / 1000) if dur_ms else 0, "cover_url": cover_url})

    if not tracks_data:
        print("ERROR: Could not extract valid track items.")
        return False

    with open(TRACKS_CSV, "w", encoding="utf-8", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["index", "title", "artist", "album", "duration_sec", "cover_url"])
        for idx, item in enumerate(tracks_data, 1):
            writer.writerow([idx, item["title"], item["artist"], item.get("album", ""), item.get("duration_sec", 0), item.get("cover_url", "")])

    # Clear stale progress tracking when preparing a new playlist CSV
    from downloader.utils import PROGRESS_FILE
    if PROGRESS_FILE.exists():
        try:
            PROGRESS_FILE.unlink()
        except Exception:
            pass

    print(f"\n✓ Playlist prepared! {len(tracks_data)} tracks written to {TRACKS_CSV}\n")
    return True


def process_single_track(row: Dict[str, str], index: int, cfg: Dict[str, Any]) -> Tuple[str, Union[Dict[str, Any], str]]:
    """Processes YouTube search, score matching, yt-dlp download, Mutagen tagging, and Android sync."""
    title, artists, album = row["title"], row["artist"], row.get("album", "")
    target_duration_sec, cover_url = int(row.get("duration_sec") or 0), row.get("cover_url", "")
    min_score, use_ytmusic = cfg.get("min_score", 70), cfg.get("ytmusic_priority", True)

    safe_title = sanitize_filename(title)
    filename_with_idx = f"{index:03d} - {safe_title}"
    music_dir = find_android_music_dir()

    # Check if this track already exists in OUTPUT_DIR or Android system Music directory
    search_dirs = [OUTPUT_DIR]
    if music_dir and music_dir.exists():
        search_dirs.append(music_dir)

    for d in search_dirs:
        for ext in [".m4a", ".opus", ".mp3", ".aac", ".flac"]:
            existing = d / f"{filename_with_idx}{ext}"
            if not existing.exists():
                existing = d / f"{safe_title}{ext}"
            if existing.exists() and existing.is_file() and existing.stat().st_size > 1000:
                if d == OUTPUT_DIR and cfg.get("auto_sync_android_music", True):
                    sync_to_android_music(existing)
                return "success", {"title": title, "channel": "Local Disk", "score": 100}




    candidates, error = search_youtube(title, artists, min_score=min_score, use_ytmusic=use_ytmusic, target_duration_sec=target_duration_sec)
    if error or not candidates:
        return "failed", error or "No candidates"

    best = candidates[0]
    if best["score"] < min_score:
        with progress_lock:
            log_review(index, title, artists, best["score"], best["title"], best["url"])

    filename = f"{index:03d} - {safe_title}" if cfg.get("include_index_in_filename", False) else safe_title
    output_template = str(OUTPUT_DIR / f"{filename}.%(ext)s")
    audio_args = get_audio_quality_args(cfg)

    cmd = ["yt-dlp", "--no-playlist", "--retries", "5", "--fragment-retries", "5", "--retry-sleep", "2", "--socket-timeout", "30", "--continue"] + audio_args + ["--write-thumbnail", "--convert-thumbnails", "jpg"] + get_ytdlp_auth_args() + ["-o", output_template, best["url"]]
    code, stdout, stderr = run_command(cmd)

    if code != 0:
        # Smart Fallback Retry: Use alternate player client flags (ios,web) if bot check triggered
        fallback_cmd = ["yt-dlp", "--no-playlist", "--retries", "5", "--fragment-retries", "5", "--retry-sleep", "2", "--socket-timeout", "30", "--continue"] + audio_args + ["--write-thumbnail", "--convert-thumbnails", "jpg", "--extractor-args", "youtube:player_client=ios,web"] + ["-o", output_template, best["url"]]
        code, stdout, stderr = run_command(fallback_cmd)

    if code != 0:
        err_reason = "Unknown error"
        if stderr and stderr.strip():
            lines = [l.strip() for l in stderr.strip().splitlines() if l.strip()]
            err_lines = [l for l in lines if "ERROR:" in l or "HTTP Error" in l or "WARNING:" in l]
            err_reason = err_lines[-1] if err_lines else lines[-1]
        return "failed", err_reason

    downloaded = list(OUTPUT_DIR.glob(f"{filename}.*"))
    from downloader.utils import process_and_finalize_audio

    ok, res, msg = process_and_finalize_audio(
        downloaded_files=downloaded,
        title=title,
        artist=artists,
        album=album,
        target_duration_sec=target_duration_sec,
        cover_url=cover_url,
        track_number=index,
        cfg=cfg,
    )
    if not ok:
        return "failed", msg

    return "success", best




def download_single_spotify_track(row: Dict[str, str], index: int) -> Tuple[str, Union[Dict[str, Any], str]]:
    """Wrapper alias for processing a single Spotify track entry."""
    return process_single_track(row, index, load_config())


def run_download() -> None:
    """Main multi-threaded download runner for Spotify playlist tracks."""
    cfg = load_config()
    use_wake_lock = cfg.get("termux_wake_lock", True)

    if use_wake_lock:
        acquire_termux_wake_lock()

    try:
        _run_download_impl(cfg)
    finally:
        if use_wake_lock:
            release_termux_wake_lock()


def _run_download_impl(cfg: Dict[str, Any]) -> None:
    if not TRACKS_CSV.exists() and not prepare_csv():
        print("Aborting download.")
        return

    max_workers = cfg.get("max_workers", 10)

    tracks = []
    with open(TRACKS_CSV, "r", encoding="utf-8", newline="") as f:
        for row in csv.DictReader(f):
            tracks.append(row)

    progress = load_progress()
    pending = [r for r in tracks if progress.get(str(r["index"]), {}).get("status") != "success"]

    if not pending:
        print("\nAll tracks are already completed!")
        all_audios = list(OUTPUT_DIR.glob("*.*"))
        generate_m3u8_playlist("Spotify Playlist", all_audios)
        print_banner("PLAYLIST PROCESSING COMPLETE")
        return

    print_banner(f"Downloading {len(pending)} Tracks ({max_workers}x Threads)")

    if RICH_AVAILABLE:
        with Progress(SpinnerColumn(), TextColumn("[progress.description]{task.description}"), BarColumn(), TaskProgressColumn()) as prg:
            task = prg.add_task("Downloading tracks...", total=len(pending))

            def worker_task(row: Dict[str, str]) -> None:
                idx = int(row["index"])
                status, result = process_single_track(row, idx, cfg)
                with progress_lock:
                    key = str(idx)
                    if status == "success":
                        progress[key] = {"title": row["title"], "artist": row["artist"], "album": row.get("album", ""), "status": "success"}
                        if isinstance(result, dict):
                            progress[key].update({"youtube_title": result.get("title"), "youtube_channel": result.get("channel"), "youtube_url": result.get("url"), "score": result.get("score")})
                    elif status == "failed":
                        reason_str = str(result)
                        progress[key] = {"title": row["title"], "artist": row["artist"], "album": row.get("album", ""), "status": "failed", "reason": reason_str}
                        log_failed(idx, row["title"], row["artist"], reason_str)
                        prg.console.print(f"[bold red]✖ [{idx:03d}] FAILED:[/bold red] '{row['title']}' by {row['artist']} — [red]{reason_str}[/red]")
                    elif status == "review" and isinstance(result, dict):
                        progress[key] = {"title": row["title"], "artist": row["artist"], "album": row.get("album", ""), "status": "review", "youtube_title": result.get("title"), "youtube_channel": result.get("channel"), "youtube_url": result.get("url"), "score": result.get("score")}
                        log_review(idx, row["title"], row["artist"], result.get("score"), result.get("title"), result.get("url"))
                        prg.console.print(f"[bold yellow]⚠ [{idx:03d}] REVIEW (Score {result.get('score')}):[/bold yellow] '{row['title']}' — Matched: '{result.get('title')}'")

                    save_progress(progress)
                    prg.advance(task)

            with ThreadPoolExecutor(max_workers=max_workers) as executor:
                futures = [executor.submit(worker_task, r) for r in pending]
                for f in as_completed(futures):
                    try:
                        f.result()
                    except Exception:
                        pass
    else:
        def worker_task(row: Dict[str, str]) -> None:
            idx = int(row["index"])
            status, result = process_single_track(row, idx, cfg)
            with progress_lock:
                key = str(idx)
                if status == "success":
                    progress[key] = {"title": row["title"], "artist": row["artist"], "album": row.get("album", ""), "status": "success"}
                    if isinstance(result, dict):
                        progress[key].update({"youtube_title": result.get("title"), "youtube_channel": result.get("channel"), "youtube_url": result.get("url"), "score": result.get("score")})
                    print(f"[{idx:03d}] SUCCESS: '{row['title']}'")
                elif status == "failed":
                    reason_str = str(result)
                    progress[key] = {"title": row["title"], "artist": row["artist"], "album": row.get("album", ""), "status": "failed", "reason": reason_str}
                    log_failed(idx, row["title"], row["artist"], reason_str)
                    print(f"[{idx:03d}] FAILED: '{row['title']}' — Reason: {reason_str}")
                elif status == "review" and isinstance(result, dict):
                    progress[key] = {"title": row["title"], "artist": row["artist"], "album": row.get("album", ""), "status": "review", "youtube_title": result.get("title"), "youtube_channel": result.get("channel"), "youtube_url": result.get("url"), "score": result.get("score")}
                    log_review(idx, row["title"], row["artist"], result.get("score"), result.get("title"), result.get("url"))
                    print(f"[{idx:03d}] REVIEW (Score {result.get('score')}): '{row['title']}' — Matched: '{result.get('title')}'")

                save_progress(progress)

        with ThreadPoolExecutor(max_workers=max_workers) as executor:
            futures = [executor.submit(worker_task, r) for r in pending]
            for f in as_completed(futures):
                try:
                    f.result()
                except Exception:
                    pass

    # Auto-generate .m3u8 playlist file
    all_audios = list(OUTPUT_DIR.glob("*.*"))
    generate_m3u8_playlist("Spotify Playlist", all_audios)
    print_banner("PLAYLIST PROCESSING COMPLETE")

    # Post-download failure summary report
    failed_items = {k: v for k, v in progress.items() if v.get("status") == "failed"}
    review_items = {k: v for k, v in progress.items() if v.get("status") == "review"}
    if failed_items or review_items:
        print("\n" + "=" * 65)
        print(f" DOWNLOAD SUMMARY: {len(pending) - len(failed_items) - len(review_items)} Succeeded | {len(review_items)} Low Confidence | {len(failed_items)} Failed")
        print("=" * 65)

        if failed_items:
            print("\n  Failure Reasons Breakdown:")
            reasons_summary: Dict[str, int] = {}
            for item in failed_items.values():
                r = item.get("reason", "Unknown failure")
                reasons_summary[r] = reasons_summary.get(r, 0) + 1
            for r_text, count in reasons_summary.items():
                print(f"   • {count} track(s): {r_text}")
            print("\n  👉 Full failure details saved to: data/failed.txt")
        if review_items:
            print("  👉 Low-confidence tracks saved to: data/review.txt (run 'python main.py review')")

    if cfg.get("termux_notifications", True):
        send_termux_notification("Music.yt.Spot", f"Playlist download finished! ({len(pending) - len(failed_items)} succeeded, {len(failed_items)} failed)")

