#!/usr/bin/env python3
import os
import sys
import json
import sqlite3
import random
import urllib.request
import urllib.parse

def generate_random_vector(dim=512, seed=42):
    rng = random.Random(seed)
    return [rng.gauss(0, 1) for _ in range(dim)]

def test_sqlite_and_vectors():
    print("Testing direct SQLite database and vector file operations...")
    db_path = "music_engine.db"
    if not os.path.exists(db_path):
        print(f"Database file '{db_path}' does not exist yet. Run 'make init-db' first.")
        return

    conn = sqlite3.connect(db_path)
    cursor = conn.cursor()

    cursor.execute("SELECT count(*) FROM tracks;")
    track_count = cursor.fetchone()[0]

    if track_count < 3:
        for i in range(1, 4):
            path = os.path.abspath(f"audio_inbox/sample_track_{i}.wav")
            cursor.execute("SELECT id FROM tracks WHERE filepath = ?;", (path,))
            if not cursor.fetchone():
                offset = i - 1
                cursor.execute(
                    "INSERT INTO tracks (filepath, title, artist, bpm, key, vector_offset) VALUES (?, ?, ?, ?, ?, ?);",
                    (path, f"Sample Track {i}", f"Artist {i}", 120.0 + (i * 5), "C Major", offset)
                )
                print(f"Created sample track: Sample Track {i} at offset {offset}")
        conn.commit()

    # Record sample transition (Track 1 -> Track 2)
    cursor.execute(
        "INSERT INTO transitions (from_track_id, to_track_id, count) VALUES (1, 2, 1) "
        "ON CONFLICT(from_track_id, to_track_id) DO UPDATE SET count = count + 1;"
    )
    conn.commit()
    print("Recorded transition from Track 1 to Track 2.")
    
    cursor.execute("SELECT count(*) FROM tracks;")
    total_tracks = cursor.fetchone()[0]
    conn.close()
    print(f"Total tracks in SQLite: {total_tracks}")

def test_http_api(base_url="http://127.0.0.1:8080"):
    print(f"Testing Drogon HTTP REST API endpoints at {base_url}...")
    
    # 1. POST /api/v1/event/play
    try:
        url = f"{base_url}/api/v1/event/play"
        payload = json.dumps({"current_track_id": 2, "previous_track_id": 1}).encode("utf-8")
        req = urllib.request.Request(url, data=payload, headers={"Content-Type": "application/json"}, method="POST")
        with urllib.request.urlopen(req) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            print("POST /api/v1/event/play response:", data)
    except Exception as e:
        print(f"POST event failed (Server might not be running yet): {e}")

    # 2. GET /api/v1/recommend/next
    try:
        params = urllib.parse.urlencode({"current_track_id": 1, "limit": 3})
        url = f"{base_url}/api/v1/recommend/next?{params}"
        with urllib.request.urlopen(url) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            print("GET /api/v1/recommend/next response:", json.dumps(data, indent=2))
    except Exception as e:
        print(f"GET recommend failed (Server might not be running yet): {e}")

if __name__ == "__main__":
    test_sqlite_and_vectors()
    if "--api" in sys.argv:
        test_http_api()
