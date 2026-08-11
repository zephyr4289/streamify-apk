from downloader.config import load_config
from downloader.cover_art import fetch_high_res_cover
from downloader.ffmpeg_tagger import apply_native_metadata, crop_square_artwork
from downloader.lyrics import fetch_lyrics
from downloader.matcher import search_youtube
from downloader.utils import (
    OUTPUT_DIR,
    run_command,
    sanitize_filename,
    print_banner,
    sync_to_android_music,
    get_ytdlp_auth_args,
    get_audio_quality_args,
    acquire_termux_wake_lock,
    release_termux_wake_lock,
)


def search_and_download_song(query: str) -> bool:
    """Searches YouTube / YouTube Music by song name and downloads audio with metadata and lyrics."""
    if not query or not query.strip():
        print("ERROR: Please provide a song name or search query.")
        return False

    if load_config().get("termux_wake_lock", True):
        acquire_termux_wake_lock()

    try:
        return _search_and_download_song_impl(query)
    finally:
        if load_config().get("termux_wake_lock", True):
            release_termux_wake_lock()


def _search_and_download_song_impl(query: str) -> bool:
    query = query.strip()
    print_banner(f"Searching Song: '{query}'")
    cfg = load_config()

    candidates, error = search_youtube(title=query, artists="", min_score=cfg.get("min_score", 50), use_ytmusic=cfg.get("ytmusic_priority", True))
    if error or not candidates:
        print(f"✖ Search failed: {error or 'No candidates found'}")
        return False

    best = candidates[0]
    print(f"Match: {best['title']} | Channel: {best['channel']} (Score: {best['score']})\n")

    safe_title = sanitize_filename(best["title"])
    output_template = str(OUTPUT_DIR / f"{safe_title}.%(ext)s")

    cmd = ["yt-dlp", "--no-playlist", "--retries", "5", "--fragment-retries", "5", "--retry-sleep", "2", "--socket-timeout", "30", "--continue"] + get_audio_quality_args(cfg) + ["--write-thumbnail", "--convert-thumbnails", "jpg"] + get_ytdlp_auth_args() + ["-o", output_template, best["url"]]
    code, stdout, stderr = run_command(cmd)


    if code != 0:
        print(f"✖ Download failed: {stderr[-1000:] if stderr else 'Unknown error'}")
        return False

    downloaded = list(OUTPUT_DIR.glob(f"{safe_title}.*"))
    from downloader.utils import process_and_finalize_audio

    ok, res, msg = process_and_finalize_audio(
        downloaded_files=downloaded,
        title=best["title"],
        artist=best["channel"],
        album="Single Search",
        target_duration_sec=best.get("duration"),
        cfg=cfg,
    )
    if not ok:
        print(f"✖ Post-processing failed: {msg}")
        return False

    print_banner(f"✓ SONG DOWNLOAD COMPLETE: {best['title']}")
    return True



