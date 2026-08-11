#!/usr/bin/env python3
import sys
import os
import json
import subprocess
import glob
import sqlite3

def run_download(query: str, db_path: str = "streamify.db", inbox_dir: str = "audio_inbox"):
    os.makedirs(inbox_dir, exist_ok=True)
    
    # 1. Capture existing files in inbox
    before_files = set(glob.glob(os.path.join(inbox_dir, "*")))
    
    # 2. Run yt-dlp search and download
    output_template = os.path.join(inbox_dir, "%(artist,uploader)s - %(title)s.%(ext)s")
    cmd = [
        "nice", "-n", "19",
        "yt-dlp",
        "--no-playlist",
        "--extract-audio",
        "--audio-format", "mp3",
        "--audio-quality", "0",
        "--add-metadata",
        "--embed-thumbnail",
        "-o", output_template,
        f"ytsearch1:{query}"
    ]
    
    try:
        res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=True)
    except subprocess.CalledProcessError as e:
        print(json.dumps({"status": "error", "error": f"yt-dlp failed: {e.stderr or e.stdout}"}))
        sys.exit(1)
        
    # 3. Find the newly downloaded file
    after_files = set(glob.glob(os.path.join(inbox_dir, "*")))
    new_files = list(after_files - before_files)
    
    target_file = None
    if new_files:
        target_file = new_files[0]
    else:
        # Fallback: search for most recently modified file in inbox_dir
        all_inbox_files = glob.glob(os.path.join(inbox_dir, "*.mp3")) + glob.glob(os.path.join(inbox_dir, "*.m4a"))
        if all_inbox_files:
            target_file = max(all_inbox_files, key=os.path.getmtime)

    if not target_file or not os.path.exists(target_file):
        print(json.dumps({"status": "error", "error": "No file downloaded."}))
        sys.exit(1)

    # Make path clean & relative
    rel_path = os.path.relpath(target_file, os.getcwd())
    
    # Parse basic title & artist from filename
    filename = os.path.splitext(os.path.basename(target_file))[0]
    parts = filename.split(" - ", 1)
    if len(parts) == 2:
        artist, title = parts[0].strip(), parts[1].strip()
    else:
        artist, title = "Unknown Artist", filename.strip()

    duration_sec = 180
    # Probe duration with ffprobe if available
    try:
        probe_cmd = [
            "ffprobe", "-v", "error", "-show_entries", "format=duration",
            "-of", "default=noprint_wrappers=1:nokey=1", target_file
        ]
        duration_out = subprocess.check_output(probe_cmd, text=True).strip()
        if duration_out:
            duration_sec = int(float(duration_out))
    except Exception:
        pass

    # 4. Insert or update in SQLite database
    conn = sqlite3.connect(db_path)
    cursor = conn.cursor()
    
    cursor.execute("SELECT id, title, artist, duration_sec FROM tracks WHERE filepath = ?", (rel_path,))
    row = cursor.fetchone()
    
    if row:
        track_id = row[0]
    else:
        cursor.execute(
            "INSERT INTO tracks (filepath, title, artist, album, duration_sec) VALUES (?, ?, ?, ?, ?)",
            (rel_path, title, artist, "Single", duration_sec)
        )
        conn.commit()
        track_id = cursor.lastrowid
        
    conn.close()

    print(json.dumps({
        "status": "success",
        "track": {
            "id": track_id,
            "title": title,
            "artist": artist,
            "album": "Single",
            "duration_sec": duration_sec,
            "filepath": rel_path
        }
    }))

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(json.dumps({"status": "error", "error": "Query required"}))
        sys.exit(1)
        
    query_str = sys.argv[1]
    db_file = sys.argv[2] if len(sys.argv) > 2 else "streamify.db"
    run_download(query_str, db_file)
