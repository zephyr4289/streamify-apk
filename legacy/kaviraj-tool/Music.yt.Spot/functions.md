# 📖 Termux Playlist Audio Downloader — Functions & Usage Reference Guide

This document provides a comprehensive, function-by-function reference of the **`Music.yt.Spot` (`termuxvid`)** codebase, detailing module responsibilities, function signatures, internal workflows, and command-line usage.

---

## 📂 Project Architecture Overview

```text
termuxvid/
├── main.py                     # CLI entrypoint & interactive terminal menu
├── functions.md                # Detailed functions & usages reference guide
├── config.json                 # User configuration settings
├── requirements.txt            # Python dependencies (yt-dlp, mutagen, rich)
├── downloader/                 # Core modular Python package
│   ├── __init__.py             # Package initializer
│   ├── config.py               # Config loader & manager
│   ├── cover_art.py            # iTunes API HD cover art fetcher
│   ├── ffmpeg_tagger.py        # Mutagen native tagger & FFmpeg cropper
│   ├── finder.py               # Exportify JSON auto-discovery engine
│   ├── lyrics.py               # LRCLIB REST API synced lyrics fetcher
│   ├── matcher.py              # Fuzzy matching algorithm & YouTube search
│   ├── progress.py             # Progress state JSON logger & status reporter
│   ├── review_mode.py          # Interactive review mode for low-confidence tracks
│   ├── search_mode.py          # Direct song search & download module
│   ├── spotify_api.py          # Spotify Web API parser & embed scraper
│   ├── spotify_mode.py          # Multi-threaded Spotify playlist download runner
│   ├── utils.py                # System helpers, Android sync, M3U8 generator
│   └── youtube_mode.py        # Universal link & YouTube playlist downloader
├── input/                      # Directory for local Exportify JSON files
├── data/                       # Progress JSON, tracks CSV, failed/review logs
└── output/                     # Final downloaded audio files, artwork & .m3u8 playlists
```

---

## 🛠️ Complete Module & Function Reference

### 1. `main.py` — Entrypoint & CLI Controller

| Function / Block | Description |
| :--- | :--- |
| `interactive_menu()` | Displays a numeric menu interface for users launching `python main.py` without arguments. Handles option routing. |
| `print_usage()` | Displays command-line syntax and usage examples for CLI arguments. |
| `main()` | CLI argument parser routing commands (`spotify`, `search`, `link`, `review`, `clean`, `status`) to their respective modules. |

---

### 2. `downloader/spotify_mode.py` — Batch Playlist Engine

| Function | Description |
| :--- | :--- |
| `prepare_csv(source_input)` | Parses Exportify JSON files or direct Spotify URLs (playlist, album, track) and extracts track titles, artists, albums, duration, and cover URLs into `data/tracks.csv`. |
| `process_single_track(row, index, cfg)` | Core per-track handler: checks local cache, queries YouTube search, evaluates score threshold, downloads stream via `yt-dlp`, crops artwork, fetches LRCLIB lyrics, applies Mutagen metadata, and syncs to Android `/sdcard/Music`. |
| `download_single_spotify_track(row, index)` | Alias wrapper for processing an individual track dictionary. |
| `run_download()` | Multi-threaded runner launching `ThreadPoolExecutor(max_workers=N)`. Integrates `rich.progress` thread-safe progress bars and auto-generates `.m3u8` playlist files on completion. |

---

### 3. `downloader/matcher.py` — Fast Search & Score Matching

| Function | Description |
| :--- | :--- |
| `similarity(title, candidate)` | Calculates a combined title similarity score (0.0 to 1.0) using 60% difflib sequence matching and 40% set word overlap. |
| `artist_match(artists, candidate_title, candidate_channel)` | Evaluates artist name presence in candidate titles and channel names (0 to 40 pts). |
| `bad_candidate(title)` | Detects unwanted audio edits (`slowed`, `reverb`, `nightcore`, `cover`, `remix`, `reaction`, `karaoke`, `shorts`) and applies score penalties. |
| `score_candidate(spotify_title, spotify_artists, yt_title, channel, candidate_duration, target_duration)` | Computes a final match confidence score (0 to 100). Grants a **+20 point bonus** for official YouTube Music `- Topic` channels. |
| `search_youtube(title, artists, count, min_score, use_ytmusic, target_duration_sec)` | Executes a fast, optimized single-pass `yt-dlp` search query (`ytsearch5:...`), reducing process spawn overhead by 75%. Returns sorted candidates. |

---

### 4. `downloader/spotify_api.py` — Spotify Web Resolvers

| Function | Description |
| :--- | :--- |
| `parse_spotify_url(url)` | Parses Spotify web URLs (`open.spotify.com/playlist/...`) or URIs (`spotify:playlist:...`) into `(item_type, item_id)`. |
| `get_anonymous_spotify_token()` | Fetches an anonymous web client access token from Spotify for API access without user credentials. |
| `fetch_spotify_tracks_via_api(item_type, item_id, token)` | Queries Spotify Public Web API endpoints (`/v1/playlists/.../tracks`, `/v1/albums/.../tracks`, `/v1/tracks/...`) with pagination support up to 1000+ tracks. |
| `fetch_spotify_tracks_via_embed(item_type, item_id)` | Fallback web scraper extracting JSON state from Spotify open embed web pages. |
| `fetch_spotify_metadata_from_url(url)` | Master entrypoint resolving Spotify URLs to structured track list dictionaries. |

---

### 5. `downloader/youtube_mode.py` — Universal Link & Playlist Downloader

| Function | Description |
| :--- | :--- |
| `download_from_link(url)` | Universal router: redirects Spotify URLs to `spotify_mode`, playlists to `download_youtube_playlist`, and single videos to `download_youtube_video`. |
| `download_youtube_playlist(url)` | Downloads complete YouTube / YT Music playlists using `yt-dlp` with `--download-archive` tracking, thumbnail embedding, and auto-generates `.m3u8` playlist files. |
| `download_youtube_video(url)` | Downloads a single YouTube video audio stream natively with thumbnail and metadata tagging. |

---

### 6. `downloader/search_mode.py` — Single Song Quick Search

| Function | Description |
| :--- | :--- |
| `search_and_download_song(query)` | Quick CLI handler for searching and downloading a single track by name with LRCLIB synced lyrics and iTunes HD cover art. |

---

### 7. `downloader/review_mode.py` — Interactive Track Review

| Function | Description |
| :--- | :--- |
| `run_review_mode()` | Interactive CLI loop for inspecting low-confidence tracks logged in `data/review.txt`. Option 1 downloads existing match; Option 2 downloads a user-pasted custom YouTube URL directly. |

---

### 8. `downloader/lyrics.py` — LRCLIB Synced Lyrics Integration

| Function | Description |
| :--- | :--- |
| `clean_artist_name(artist)` | Strips featuring/collaborator suffixes (`feat.`, `ft.`, `&`) to isolate the primary artist name. |
| `fetch_lyrics(title, artist, album, output_audio_path)` | Multi-pass query engine for LRCLIB REST API (`/api/get` and `/api/search`). Saves `.lrc` files next to audio tracks. |
| `_save_lrc(output_audio_path, lyrics_text)` | Writes `.lrc` text file alongside the target audio path. |

---

### 9. `downloader/cover_art.py` — HD Artwork Retriever

| Function | Description |
| :--- | :--- |
| `download_image_bytes(url, timeout)` | Helper fetching raw binary image data via `urllib.request`. |
| `fetch_itunes_cover_art(title, artist)` | Queries iTunes Search API for 1400x1400 / 600x600 HD album artwork. |
| `fetch_high_res_cover(title, artist, preferred_url)` | Downloads direct Spotify album art or falls back to iTunes HD cover search. |

---

### 10. `downloader/ffmpeg_tagger.py` — Mutagen & FFmpeg Metadata Tagger

| Function | Description |
| :--- | :--- |
| `apply_native_metadata(...)` | Applies Title, Artist, Album, Track Number, Embedded Lyrics, and Cover Art to M4A, MP3, FLAC, and Opus files using Mutagen without lossy re-encoding. |
| `apply_spotify_metadata(audio_file, title, artist, album)` | Fallback metadata tagger using FFmpeg stream copy (`-c copy`). |
| `crop_square_artwork(image_path)` | Uses FFmpeg filter `crop='min(iw,ih):min(iw,ih)'` to convert 16:9 YouTube thumbnails to 1:1 square cover art. |

---

### 11. `downloader/finder.py` — Auto-Discovery Engine

| Function | Description |
| :--- | :--- |
| `is_valid_exportify_json(file_path)` | Validates whether a local JSON file contains valid Exportify or Spotify track structures. |
| `discover_playlist_json(provided_path)` | Auto-scans `input/`, project root, and Android `/sdcard/Download` folders for playlist JSON files. |

---

### 12. `downloader/utils.py` — Core System Helpers & M3U8 Generator

| Function | Description |
| :--- | :--- |
| `run_command(cmd, cwd)` | Executes shell commands via `subprocess.run` returning `(code, stdout, stderr)`. |
| `find_downloads_dirs()` | Returns candidate Android / Termux download paths. |
| `get_ytdlp_auth_args()` | Auto-discovers local `cookies.txt` or returns Android VR/Web Creator client User-Agent parameters. |
| `get_audio_quality_args(cfg)` | Returns native audio selection flags (`ba[ext=webm]/ba[ext=m4a]/bestaudio/best`). |
| `normalize(text)` | Normalizes strings for matching by lowercasing and stripping non-alphanumeric symbols. |
| `words(text)` | Converts normalized string into a set of unique words. |
| `sanitize_filename(name)` | Cleans filenames for cross-platform safety (Windows / Linux / Android). |
| `find_android_music_dir()` | Returns candidate Android system Music folder paths (`/sdcard/Music`). |
| `trigger_android_media_scanner(file_path)` | Fires `am broadcast` Android `MEDIA_SCANNER_SCAN_FILE` intent to refresh media stores. |
| `sync_to_android_music(file_path)` | Copies audio, artwork, and `.lrc` files to Android Music folder and triggers scanner. |
| `clean_project_cache(include_output)` | Resets progress JSON, CSV files, logs, temp files, and `__pycache__`. |
| `generate_m3u8_playlist(playlist_name, audio_files)` | Auto-generates standard `.m3u8` playlist files in `output/` for mobile audio players. |

---

### 13. `downloader/progress.py` & `downloader/config.py`

| Function | Description |
| :--- | :--- |
| `load_config()` / `save_config(cfg)` | Reads and writes `config.json` with fallback defaults. |
| `load_progress()` / `save_progress(progress)` | Reads and atomically writes progress tracking state to `data/progress.json`. |
| `log_failed(...)` / `log_review(...)` | Logs failed tracks to `data/failed.txt` and low-confidence tracks to `data/review.txt`. |
| `show_status()` | Displays a progress report of total, completed, failed, and remaining tracks. |

---

## 🎮 Complete Usage Guide & CLI Commands

### 1. Interactive Menu Mode
Simply run the entrypoint script without arguments:
```bash
python main.py
```

### 2. Spotify Playlist Download (Direct URL or Exportify JSON)
Pass a Spotify playlist/album/track URL directly or let it auto-discover local JSON files:
```bash
# Direct Spotify Playlist / Album URL
python main.py spotify "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"

# Auto-discover local JSON in Downloads or input/
python main.py spotify
```

### 3. Song Quick Search
Search for any song by name or artist:
```bash
python main.py search "No Handouts Amantej Hundal"
```

### 4. Universal Link Downloader (YouTube / YT Music / Spotify)
Download playlists, albums, videos, or music videos from YouTube or YouTube Music:
```bash
# YouTube Music Playlist / Album
python main.py link "https://music.youtube.com/playlist?list=..."

# YouTube Video / Music Video
python main.py link "https://www.youtube.com/watch?v=..."
```

### 5. Interactive Review Mode
Inspect and re-download low-confidence tracks:
```bash
python main.py review
```

### 6. Progress Report & Status
Check current download progress:
```bash
python main.py status
```

### 7. Clean Project Cache & Reset Data
Clear temporary files, CSVs, and progress logs:
```bash
# Clean cache, CSVs, and logs
python main.py clean

# Clean cache AND clear output/ directory
python main.py clean --all
```

---

## ⚙️ Configuration Reference (`config.json`)

```json
{
  "max_workers": 10,
  "min_score": 70,
  "ytmusic_priority": true,
  "fetch_lyrics": true,
  "embed_lyrics": true,
  "fetch_high_res_cover": true,
  "square_crop_artwork": true,
  "auto_sync_android_music": true,
  "include_index_in_filename": false,
  "duration_match_threshold_sec": 10,
  "audio_format": "best_native"
}
```

| Key | Default | Explanation |
| :--- | :--- | :--- |
| `max_workers` | `10` | Number of parallel download threads. |
| `min_score` | `70` | Minimum match confidence score threshold (0–100). |
| `ytmusic_priority` | `true` | Prioritizes YouTube Music search results. |
| `fetch_lyrics` | `true` | Downloads synchronized `.lrc` lyrics from LRCLIB. |
| `embed_lyrics` | `true` | Embeds lyrics text natively into audio file tags. |
| `fetch_high_res_cover` | `true` | Queries iTunes API for 1400x1400 HD cover art. |
| `square_crop_artwork` | `true` | Crops 16:9 thumbnails to 1:1 square ratio using FFmpeg. |
| `auto_sync_android_music` | `true` | Copies files to `/sdcard/Music` and triggers MediaScanner. |
| `include_index_in_filename` | `false` | Prefixes track number in filenames (e.g. `001 - Song.m4a`). |
| `audio_format` | `"best_native"` | Selects highest native stream (`opus` / `m4a`) with zero re-encoding. WebM containers are automatically remuxed to native `.opus` audio containers for 100% Android player compatibility. |

