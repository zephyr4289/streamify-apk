# 🎵 Termux Playlist Audio Downloader

A high-performance, multi-threaded Termux / Android CLI tool for downloading audio from **Spotify playlists (Exportify JSON)**, **YouTube / YT Music playlists & albums**, **music videos**, and **songs by name** using `yt-dlp` and `FFmpeg`.

---

## ⚡ Key Features

- 🎧 **Native Audio Quality**: Preserves original AAC (`.m4a`) or Opus (`.webm`) streams with zero lossy re-encoding.
- ⚡ **8x Parallel Downloads**: Multi-threaded download engine (`max_workers=8`) for high-speed downloads.
- 🖼️ **1:1 Square Album Art**: Automatically crops 16:9 YouTube thumbnails into 1:1 square cover art.
- 🎤 **Synced Lyrics (`.lrc`)**: Fetches synchronized lyrics automatically via LRCLIB.
- 📲 **Android Auto-Sync**: Copies downloaded tracks directly to `/sdcard/Music` and triggers the media scanner.
- 📁 **Zero-Click Auto-Discovery**: Automatically detects Exportify JSON files and `cookies.txt` from your `Downloads` folder.
- 🤖 **Multi-Client Bot Bypass**: Automatically bypasses YouTube bot challenges using multi-client API fallbacks.

---

## 🚀 Quick Start (Termux)

### 📥 First-Time Installation
```bash
# 1. Install prerequisites & setup storage
pkg update && pkg upgrade -y
pkg install python ffmpeg git -y
termux-setup-storage

# 2. Clone repository & install dependencies
git clone https://github.com/Zoro-15/Music.yt.Spot.git
cd Music.yt.Spot
python -m pip install -U -r requirements.txt mutagen yt-dlp rich

# 3. Launch interactive menu
python main.py
```

### ⚡ Launch in New Termux Session (Already Installed)
```bash
cd ~/Music.yt.Spot && python main.py
```

> **Updating Existing App**: Run `cd ~/Music.yt.Spot && git pull && python -m pip install -U -r requirements.txt` anytime to fetch the latest updates!

---

## 🎮 Usage Guide

### 1. Spotify Playlist Mode (Exportify JSON)
1. Export your Spotify playlist as a **JSON** file using [Exportify](https://exportify.madebyruuen.com/).
2. Leave the `.json` file in your phone's `Downloads` folder (or place it in `input/`).
3. Run `python main.py`, select option `1`, choose your playlist, and confirm download!

### 2. Search & Download Song by Name
```bash
python main.py search "Song Name Artist"
```

### 3. Universal Link Downloader (YT / YT Music)
Supports YouTube Playlists, YT Music Playlists, Albums, and Videos:
```bash
python main.py link "https://music.youtube.com/playlist?list=..."
```

### 4. Utility Commands
- **Audit & Clean Wrong Songs in Folder**: `python main.py audit`
- **Review Low-Confidence Tracks**: `python main.py review`
- **Clean Cache & CSV Logs**: `python main.py clean`
- **Clean Output Directory**: `python main.py clean --all`

---

## 💡 Troubleshooting & Tips

| Issue | Quick Solution |
| :--- | :--- |
| **Missing Mutagen Module** | Run `python -m pip install -U mutagen` to install native audio tagging dependencies. |
| **YouTube Bot Check** | Reconnect **Cloudflare WARP (1.1.1.1)** or toggle Airplane Mode / Mobile Data to refresh your IP. |
| **Cookies Support (Optional)** | Export `cookies.txt` to your phone's `Downloads` folder — the script auto-detects and uses it! |
| **Prevent Termux Sleep** | Automatically handled! Or run `termux-wake-lock` manually before downloading large playlists. |

---

## ⚙️ Configuration (`config.json`)

```json
{
  "max_workers": 8,
  "min_score": 70,
  "ytmusic_priority": true,
  "fetch_lyrics": true,
  "square_crop_artwork": true,
  "auto_sync_android_music": true,
  "include_index_in_filename": false
}
```
