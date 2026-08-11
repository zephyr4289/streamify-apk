from downloader.config import load_config
from downloader.ffmpeg_tagger import crop_square_artwork
from downloader.utils import (
    OUTPUT_DIR,
    DATA_DIR,
    run_command,
    print_banner,
    sync_to_android_music,
    get_ytdlp_auth_args,
    get_audio_quality_args,
    generate_m3u8_playlist,
)


def download_from_link(url: str) -> bool:
    """Universal link downloader for Spotify, YouTube, and YT Music."""
    if not url or not url.strip():
        print("ERROR: Please provide a valid URL.")
        return False
    url = url.strip()
    if "spotify.com" in url or "spotify:" in url:
        from downloader.spotify_mode import prepare_csv, run_download
        print_banner("Spotify Direct URL Downloader Mode")
        if prepare_csv(url):
            run_download()
            return True
        return False

    return download_youtube_playlist(url) if ("list=" in url or "/playlist" in url or "/album" in url) else download_youtube_video(url)


def download_youtube_playlist(url: str) -> bool:
    """Downloads a complete YouTube or YT Music playlist/album directly using yt-dlp with live progress & cover art embedding."""
    import json
    from downloader.utils import process_and_finalize_audio

    cfg = load_config()
    print_banner("Playlist / Album Downloader Mode")
    archive_file = DATA_DIR / "downloaded_archive.txt"
    output_template = str(OUTPUT_DIR / "%(title)s.%(ext)s")

    raw_url = url.strip()
    www_url = raw_url.replace("music.youtube.com", "www.youtube.com")

    # Extract album / playlist entry list first using flat-playlist for real-time track-by-track progress & tagging
    video_entries = []
    playlist_title = "YouTube Playlist"

    for target_u in [www_url, raw_url]:
        flat_cmd = ["yt-dlp", "--flat-playlist", "--dump-single-json", target_u]
        f_code, f_stdout, _ = run_command(flat_cmd)
        if f_code == 0 and f_stdout.strip():
            try:
                data = json.loads(f_stdout) or {}
                playlist_title = data.get("title") or "YouTube Playlist"
                raw_entries = data.get("entries") or []
                for e in raw_entries:
                    if e and isinstance(e, dict) and e.get("id"):
                        video_entries.append({
                            "id": e["id"],
                            "title": e.get("title") or "Track",
                            "uploader": e.get("uploader") or e.get("channel") or "",
                            "url": f"https://www.youtube.com/watch?v={e['id']}",
                        })
                if video_entries:
                    break
            except Exception:
                pass

    if video_entries:
        from downloader.utils import find_android_music_dir, sanitize_filename
        total = len(video_entries)
        print(f"\nDownloading {total} Tracks for Album: '{playlist_title}'")
        print("=" * 60)
        success_count = 0

        music_dir = find_android_music_dir()
        search_dirs = [OUTPUT_DIR]
        if music_dir and music_dir.exists() and music_dir.is_dir():
            search_dirs.append(music_dir)

        for idx, entry in enumerate(video_entries, 1):
            t_title = entry["title"]
            t_artist = entry["uploader"] or playlist_title
            t_url = entry["url"]
            print(f" [{idx:03d}/{total:03d}] Downloading: '{t_title}'...", end="", flush=True)

            safe_t_title = sanitize_filename(t_title)
            already_exists = False
            for d in search_dirs:
                for ext in [".m4a", ".opus", ".mp3", ".aac", ".flac"]:
                    p1 = d / f"{safe_t_title}{ext}"
                    p2 = d / f"{idx:03d} - {safe_t_title}{ext}"
                    if (p1.exists() and p1.is_file() and p1.stat().st_size > 1000) or (p2.exists() and p2.is_file() and p2.stat().st_size > 1000):
                        already_exists = True
                        break
                if already_exists:
                    break

            if already_exists:
                print(" ⚡ Already Exists (Skipped)")
                success_count += 1
                continue

            before_files = set(OUTPUT_DIR.glob("*.*"))
            t_template = str(OUTPUT_DIR / "%(title)s.%(ext)s")
            v_cmd = ["yt-dlp", "--no-playlist", "--retries", "3", "--socket-timeout", "20"] + get_audio_quality_args(cfg) + ["--write-thumbnail", "--convert-thumbnails", "jpg"] + get_ytdlp_auth_args() + ["-o", t_template, t_url]

            v_code, _, v_stderr = run_command(v_cmd)

            # Retry 2: Automatic fallback with alternate player_client flags if primary download encountered issues
            if v_code != 0:
                fallback_v_cmd = ["yt-dlp", "--no-playlist", "--retries", "3", "--socket-timeout", "20", "--extractor-args", "youtube:player_client=ios,web"] + get_audio_quality_args(cfg) + ["--write-thumbnail", "--convert-thumbnails", "jpg"] + get_ytdlp_auth_args() + ["-o", t_template, t_url]
                v_code, _, v_stderr = run_command(fallback_v_cmd)

            if v_code == 0:
                downloaded = list(set(OUTPUT_DIR.glob("*.*")) - before_files)
                ok, _, msg = process_and_finalize_audio(
                    downloaded_files=downloaded,
                    title=t_title,
                    artist=t_artist,
                    album=playlist_title,
                    cfg=cfg,
                )
                if ok:
                    print(" ✓ Done")
                    success_count += 1
                else:
                    print(f" ⚠ Processed ({msg})")
            else:
                err_msg = "Unknown error"
                if v_stderr and v_stderr.strip():
                    err_lines = [l.strip() for l in v_stderr.split("\n") if "ERROR:" in l or "HTTP Error" in l or "Sign in" in l or "Unavailable" in l]
                    err_msg = err_lines[-1] if err_lines else v_stderr.strip().split("\n")[-1]
                print(f" ✖ Failed — Reason: {err_msg}")

        generate_m3u8_playlist(playlist_title, list(OUTPUT_DIR.glob("*.*")))
        print_banner(f"✓ ALBUM DOWNLOAD COMPLETE ({success_count}/{total} tracks processed)")
        return success_count > 0

    # Fallback to direct yt-dlp playlist command if flat-playlist JSON was unavailable
    cmd = [
        "yt-dlp",
        "--yes-playlist",
        "--download-archive", str(archive_file),
        "--retries", "5",
        "--fragment-retries", "5",
        "--retry-sleep", "2",
        "--socket-timeout", "30",
        "--continue",
    ] + get_audio_quality_args(cfg) + ["--write-thumbnail", "--convert-thumbnails", "jpg"] + get_ytdlp_auth_args() + ["-o", output_template, www_url]

    before_files = set(OUTPUT_DIR.glob("*.*"))
    code, stdout, stderr = run_command(cmd)

    if code == 0:
        new_files = list(set(OUTPUT_DIR.glob("*.*")) - before_files)
        process_and_finalize_audio(
            downloaded_files=new_files,
            title="Playlist Track",
            artist="YouTube Downloader",
            album=playlist_title,
            cfg=cfg,
        )
        print_banner("PLAYLIST / ALBUM DOWNLOAD COMPLETE")
        return True

    print(f"\nERROR: Download encountered issues: {stderr[-1000:] if stderr else 'Unknown failure'}")
    return False


def download_youtube_video(url: str) -> bool:
    """Downloads a single YouTube or YT Music video audio file natively."""
    cfg = load_config()
    print_banner("Single Audio / Music Video Downloader Mode")
    output_template = str(OUTPUT_DIR / "%(title)s.%(ext)s")
    before_files = set(OUTPUT_DIR.glob("*.*"))

    cmd = ["yt-dlp", "--no-playlist", "--retries", "5", "--fragment-retries", "5", "--retry-sleep", "2", "--socket-timeout", "30", "--continue"] + get_audio_quality_args(cfg) + ["--write-thumbnail", "--convert-thumbnails", "jpg"] + get_ytdlp_auth_args() + ["-o", output_template, url]

    code, stdout, stderr = run_command(cmd)

    if code == 0:
        new_files = list(set(OUTPUT_DIR.glob("*.*")) - before_files)
        from downloader.utils import process_and_finalize_audio
        process_and_finalize_audio(
            downloaded_files=new_files,
            title="Downloaded Track",
            artist="YouTube Downloader",
            cfg=cfg,
        )
        print_banner("AUDIO DOWNLOAD COMPLETE")
        return True
    print(f"\nERROR: Download encountered issues: {stderr[-1000:] if stderr else 'Unknown failure'}")
    return False



