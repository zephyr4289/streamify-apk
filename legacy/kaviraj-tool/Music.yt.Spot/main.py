#!/usr/bin/env python3
"""
Unified CLI Entrypoint for Termux Playlist Audio Downloader.
"""

import sys
import argparse
from downloader.spotify_mode import prepare_csv, run_download
from downloader.search_mode import search_and_download_song
from downloader.youtube_mode import download_from_link
from downloader.progress import show_status, audit_and_fix_mismatched_tracks
from downloader.review_mode import run_review_mode
from downloader.utils import print_banner, clean_project_cache
from downloader.config import load_config, save_config


from downloader.config import load_config, save_config, update_config_key

try:
    from rich.console import Console
    from rich.panel import Panel
    from rich.table import Table
    console = Console()
    RICH_MENU = True
except ImportError:
    Console = None
    Panel = None
    Table = None
    console = None
    RICH_MENU = False


def settings_menu():
    """Interactive Settings Manager for config.json."""
    while True:
        cfg = load_config()
        print_banner("Settings Manager (config.json)")
        print(f"  1. Parallel Download Workers  : {cfg.get('max_workers', 10)}")
        print(f"  2. Minimum Match Score (0-100): {cfg.get('min_score', 70)}")
        print(f"  3. YT Music Priority         : {'[Enabled]' if cfg.get('ytmusic_priority', True) else '[Disabled]'}")
        print(f"  4. Fetch & Embed Lyrics       : {'[Enabled]' if cfg.get('fetch_lyrics', True) else '[Disabled]'}")
        print(f"  5. Fetch HD Cover Art         : {'[Enabled]' if cfg.get('fetch_high_res_cover', True) else '[Disabled]'}")
        print(f"  6. 1:1 Square Crop Artwork    : {'[Enabled]' if cfg.get('square_crop_artwork', True) else '[Disabled]'}")
        print(f"  7. Auto-Sync to Android Music : {'[Enabled]' if cfg.get('auto_sync_android_music', True) else '[Disabled]'}")
        print(f"  8. Audio Format               : {cfg.get('audio_format', 'best_native')}")
        print("  9. Return to Main Menu")
        print("-" * 50)

        choice = input("Select setting to modify [1-9]: ").strip()
        if choice == "1":
            val = input("Enter worker count (1-16) [default 10]: ").strip()
            if val.isdigit() and 1 <= int(val) <= 16:
                update_config_key("max_workers", int(val))
                print(f" ✓ Set max_workers to {val}")
        elif choice == "2":
            val = input("Enter minimum match score (50-95) [default 70]: ").strip()
            if val.isdigit() and 50 <= int(val) <= 95:
                update_config_key("min_score", int(val))
                print(f" ✓ Set min_score to {val}")
        elif choice == "3":
            update_config_key("ytmusic_priority", not cfg.get("ytmusic_priority", True))
            print(" ✓ Toggled YT Music Priority")
        elif choice == "4":
            cur = cfg.get("fetch_lyrics", True)
            update_config_key("fetch_lyrics", not cur)
            update_config_key("embed_lyrics", not cur)
            print(" ✓ Toggled Lyrics Fetching & Embedding")
        elif choice == "5":
            update_config_key("fetch_high_res_cover", not cfg.get("fetch_high_res_cover", True))
            print(" ✓ Toggled HD Cover Art Fetching")
        elif choice == "6":
            update_config_key("square_crop_artwork", not cfg.get("square_crop_artwork", True))
            print(" ✓ Toggled Square Crop Artwork")
        elif choice == "7":
            update_config_key("auto_sync_android_music", not cfg.get("auto_sync_android_music", True))
            print(" ✓ Toggled Android Music Sync")
        elif choice == "8":
            print("\n  a. best_native (Opus / AAC without lossy re-encoding)\n  b. m4a\n  c. opus")
            fmt_c = input("Choose format [a/b/c]: ").strip().lower()
            if fmt_c == "a":
                update_config_key("audio_format", "best_native")
            elif fmt_c == "b":
                update_config_key("audio_format", "m4a")
            elif fmt_c == "c":
                update_config_key("audio_format", "opus")
        elif choice == "9" or choice.lower() == "q":
            break


def interactive_menu():
    """Displays an interactive terminal menu for users running 'python main.py'."""
    while True:
        if RICH_MENU and console:
            console.print(Panel.fit(
                "[bold cyan]🎵 Termux Playlist Audio Downloader[/bold cyan]\n"
                "[dim]High-Performance Multi-Threaded CLI Audio Tool[/dim]",
                border_style="cyan"
            ))
        else:
            print_banner("Termux Playlist Audio Downloader")

        print("  1. Spotify Playlist / Album / Track (Direct URL or Exportify JSON)")
        print("  2. Search & Download Song by Name")
        print("  3. Download from Universal Link (YouTube, YT Music, or Spotify)")
        print("  4. View Spotify Download Status")
        print("  5. Review Low-Confidence / Failed Tracks")
        print("  6. Audit & Fix Mismatched / Failed Tracks (Auto-clean & Re-download)")
        print("  7. Clean / Reset Cache, CSV & Logs")
        print("  8. Manage Settings (config.json)")
        print("  9. Exit")
        print("-" * 50)

        choice = input("Select an option [1-9]: ").strip()

        if choice == "1":
            url_or_json = input("\nEnter Spotify URL or press Enter to auto-discover local JSON: ").strip()
            if prepare_csv(url_or_json if url_or_json else None):
                start = input("\nStart downloading playlist tracks now? [Y/n]: ").strip()
                if start.lower() != "n":
                    run_download()
            input("\nPress Enter to return to menu...")
        elif choice == "2":
            query = input("\nEnter Song Name or Search Query: ").strip()
            if query:
                search_and_download_song(query)
            input("\nPress Enter to return to menu...")
        elif choice == "3":
            url = input("\nEnter YouTube / YT Music / Spotify URL: ").strip()
            if url:
                download_from_link(url)
            input("\nPress Enter to return to menu...")
        elif choice == "4":
            show_status()
            input("\nPress Enter to return to menu...")
        elif choice == "5":
            run_review_mode()
            input("\nPress Enter to return to menu...")
        elif choice == "6":
            removed = audit_and_fix_mismatched_tracks(force_delete=True)
            if removed > 0:
                re_dn = input("\nRe-download corrected tracks now? [Y/n]: ").strip().lower()
                if re_dn != "n":
                    run_download()
            input("\nPress Enter to return to menu...")
        elif choice == "7":
            confirm = input("Reset CSV, progress logs, and cache? [y/N]: ").strip().lower()
            if confirm == "y":
                inc_out = input("Also clear all downloaded audio files in output/ folder? [y/N]: ").strip().lower() == "y"
                clean_project_cache(include_output=inc_out)
            input("\nPress Enter to return to menu...")
        elif choice == "8":
            settings_menu()
        elif choice == "9" or choice.lower() in ["exit", "q"]:
            print("\nGoodbye!")
            break
        elif choice.startswith(("http://", "https://", "spotify:")):
            download_from_link(choice)
            input("\nPress Enter to return to menu...")
        else:
            print("\nInvalid choice. Please enter a number between 1 and 9.")


def main():
    if len(sys.argv) == 1:
        interactive_menu()
        return

    parser = argparse.ArgumentParser(
        description="Termux Playlist Audio Downloader — High performance CLI audio tool.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )

    subparsers = parser.add_subparsers(dest="command", help="Available Commands")

    # spotify subcommand
    sp_parser = subparsers.add_parser("spotify", aliases=["sp"], help="Download Spotify playlist / album / track")
    sp_parser.add_argument("source", nargs="?", default=None, help="Spotify URL or path to Exportify JSON file")
    sp_parser.add_argument("-w", "--workers", type=int, default=None, help="Override parallel download thread count")

    # search subcommand
    search_parser = subparsers.add_parser("search", help="Search song by name and download audio")
    search_parser.add_argument("query", nargs="+", help="Song title or artist query")

    # link subcommand
    link_parser = subparsers.add_parser("link", aliases=["youtube", "video", "url"], help="Download from YouTube / YT Music / Spotify URL")
    link_parser.add_argument("url", help="Media URL to download")

    # review subcommand
    subparsers.add_parser("review", help="Interactively review low-confidence track matches")

    # audit subcommand
    subparsers.add_parser("audit", help="Audit folder for duration mismatches and auto-remove wrong songs")

    # clean subcommand
    clean_parser = subparsers.add_parser("clean", help="Clean cache, logs, and temporary files")
    clean_parser.add_argument("-a", "--all", action="store_true", help="Also clear output directory")

    # status subcommand
    subparsers.add_parser("status", help="Display download progress status report")

    args = parser.parse_args()

    cmd = args.command.lower() if args.command else None

    if cmd in ["spotify", "sp"]:
        if getattr(args, "workers", None):
            cfg = load_config()
            cfg["max_workers"] = args.workers
            save_config(cfg)
        if prepare_csv(args.source):
            run_download()
    elif cmd == "search":
        q = " ".join(args.query) if isinstance(args.query, list) else args.query
        search_and_download_song(q)
    elif cmd in ["link", "youtube", "video", "url"]:
        download_from_link(args.url)
    elif cmd == "review":
        run_review_mode()
    elif cmd == "audit":
        audit_and_fix_mismatched_tracks(force_delete=True)
    elif cmd == "clean":
        clean_project_cache(include_output=args.all)
    elif cmd == "status":
        show_status()
    else:
        interactive_menu()


if __name__ == "__main__":
    main()
