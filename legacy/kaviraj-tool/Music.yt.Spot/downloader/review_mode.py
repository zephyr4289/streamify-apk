from typing import List, Dict
from downloader.utils import print_banner, REVIEW_FILE
from downloader.progress import load_progress, save_progress
from downloader.spotify_mode import download_single_spotify_track
from downloader.youtube_mode import download_youtube_video


def run_review_mode() -> None:
    """Interactive terminal interface for reviewing low-confidence or failed tracks."""
    print_banner("Interactive Track Review Mode")
    if not REVIEW_FILE.exists() or REVIEW_FILE.stat().st_size == 0:
        print("No low-confidence tracks currently in data/review.txt.")
        return

    items: List[Dict[str, str]] = []
    with open(REVIEW_FILE, "r", encoding="utf-8") as f:
        for line in f:
            parts = line.strip().split("\t")
            if len(parts) >= 6:
                items.append({"index": parts[0], "title": parts[1], "artist": parts[2], "score": parts[3], "yt_title": parts[4], "url": parts[5]})

    if not items:
        print("Review file is empty.")
        return

    print(f"Found {len(items)} tracks needing review:\n")
    for idx, item in enumerate(items, 1):
        print(f" [{idx:02d}] Track #{item['index']} — '{item['title']}' by {item['artist']}\n      Matched: {item['yt_title']} (Score: {item['score']})\n      URL    : {item['url']}\n")

    choice = input("Enter track number to process (or 'q' to quit): ").strip()
    if choice.lower() == "q" or not choice.isdigit():
        return

    sel_idx = int(choice) - 1
    if not (0 <= sel_idx < len(items)):
        print("Invalid selection.")
        return

    selected = items[sel_idx]
    print(f"\nProcessing Track #{selected['index']}: '{selected['title']}'\n 1. Accept current match and download\n 2. Enter custom YouTube URL\n 3. Cancel")
    action = input("\nChoose action [1-3]: ").strip()

    progress = load_progress()
    key = str(selected["index"])

    if action == "1":
        override_track = {"title": selected["title"], "artist": selected["artist"], "album": ""}
        status, result = download_single_spotify_track(override_track, int(selected["index"]))
        if status == "success":
            progress[key] = {"title": selected["title"], "artist": selected["artist"], "album": "", "status": "success", "youtube_url": selected["url"]}
            save_progress(progress)
            print("Track marked as SUCCESS.")
    elif action == "2":
        custom_url = input("\nPaste custom YouTube URL: ").strip()
        if custom_url:
            if download_youtube_video(custom_url):
                progress[key] = {"title": selected["title"], "artist": selected["artist"], "album": "", "status": "success", "youtube_url": custom_url}
                save_progress(progress)
                print("Custom track downloaded successfully!")


