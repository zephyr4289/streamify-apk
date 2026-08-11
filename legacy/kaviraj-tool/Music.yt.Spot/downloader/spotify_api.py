import json
import re
import urllib.parse
import urllib.request
from typing import Optional, List, Dict, Any, Tuple


HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/124.0.0.0 Safari/537.36"
    ),
    "Accept-Language": "en-US,en;q=0.9",
}


def parse_spotify_url(url: str) -> Tuple[Optional[str], Optional[str]]:
    """
    Parses a Spotify URL or URI and returns (item_type, item_id).
    Supports:
    - https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M
    - https://open.spotify.com/album/4aawyAB9vmqN3uQ7FjRGTy
    - https://open.spotify.com/track/0VjLj2DipiyLwoxbeRppwU
    - spotify:playlist:...
    """
    if not url:
        return None, None

    url = url.strip()

    # Handle spotify:type:id
    uri_match = re.match(r"spotify:(playlist|album|track):([a-zA-Z0-9]+)", url)
    if uri_match:
        return uri_match.group(1), uri_match.group(2)

    # Handle https://open.spotify.com/type/id
    url_match = re.search(r"open\.spotify\.com/(playlist|album|track)/([a-zA-Z0-9]+)", url)
    if url_match:
        return url_match.group(1), url_match.group(2)

    return None, None


def get_anonymous_spotify_token() -> Optional[str]:
    """Retrieves an anonymous web client access token from Spotify."""
    try:
        req = urllib.request.Request(
            "https://open.spotify.com/get_access_token?reason=transport&productType=web_player",
            headers=HEADERS,
        )
        with urllib.request.urlopen(req, timeout=10) as resp:
            if resp.status == 200:
                data = json.loads(resp.read().decode("utf-8"))
                return data.get("accessToken")
    except Exception:
        pass
    return None


def fetch_spotify_tracks_via_api(item_type: str, item_id: str, token: str) -> List[Dict[str, Any]]:
    """Fetches track objects directly using Spotify's public Web API and anonymous token."""
    tracks: List[Dict[str, Any]] = []
    headers = {**HEADERS, "Authorization": f"Bearer {token}"}

    if item_type == "track":
        endpoint = f"https://api.spotify.com/v1/tracks/{item_id}"
        try:
            req = urllib.request.Request(endpoint, headers=headers)
            with urllib.request.urlopen(req, timeout=10) as resp:
                if resp.status == 200:
                    t = json.loads(resp.read().decode("utf-8"))
                    parsed = _parse_spotify_track_object(t)
                    if parsed:
                        tracks.append(parsed)
        except Exception:
            pass
        return tracks

    # For playlists and albums, support pagination
    limit = 100
    offset = 0
    base_url = (
        f"https://api.spotify.com/v1/playlists/{item_id}/tracks?limit={limit}"
        if item_type == "playlist"
        else f"https://api.spotify.com/v1/albums/{item_id}/tracks?limit={limit}"
    )

    # Fetch album info first to get cover art and album name if type is album
    album_name = ""
    album_cover = ""
    if item_type == "album":
        try:
            alb_req = urllib.request.Request(f"https://api.spotify.com/v1/albums/{item_id}", headers=headers)
            with urllib.request.urlopen(alb_req, timeout=10) as resp:
                if resp.status == 200:
                    alb_data = json.loads(resp.read().decode("utf-8"))
                    album_name = alb_data.get("name", "")
                    images = alb_data.get("images") or []
                    if images:
                        album_cover = images[0].get("url", "")
        except Exception:
            pass

    while base_url:
        try:
            req = urllib.request.Request(base_url, headers=headers)
            with urllib.request.urlopen(req, timeout=10) as resp:
                if resp.status != 200:
                    break
                data = json.loads(resp.read().decode("utf-8"))
                items = data.get("items") or []

                for item in items:
                    t_obj = item.get("track") if item_type == "playlist" else item
                    if not t_obj:
                        continue
                    parsed = _parse_spotify_track_object(t_obj, fallback_album=album_name, fallback_cover=album_cover)
                    if parsed:
                        tracks.append(parsed)

                base_url = data.get("next")
        except Exception:
            break

    return tracks


def _parse_spotify_track_object(t: Dict[str, Any], fallback_album: str = "", fallback_cover: str = "") -> Optional[Dict[str, Any]]:
    """Normalizes raw Spotify API track object into clean dict."""
    if not isinstance(t, dict) or not t.get("name"):
        return None
    artists = ", ".join(a.get("name", "") for a in (t.get("artists") or []) if isinstance(a, dict) and a.get("name"))
    album_obj = t.get("album") or {}
    cover_url = (album_obj.get("images") or [{}])[0].get("url", "") if album_obj.get("images") else fallback_cover
    duration_ms = t.get("duration_ms") or 0
    return {
        "title": t["name"].strip(),
        "artist": artists.strip(),
        "album": (album_obj.get("name") or fallback_album).strip(),
        "duration_ms": duration_ms,
        "duration_sec": int(duration_ms / 1000) if duration_ms else 0,
        "cover_url": cover_url,
    }


def fetch_spotify_tracks_via_embed(item_type: str, item_id: str) -> List[Dict[str, Any]]:
    """Fallback parser: extracts metadata from Spotify open embed web page."""
    tracks: List[Dict[str, Any]] = []
    try:
        req = urllib.request.Request(f"https://open.spotify.com/embed/{item_type}/{item_id}", headers=HEADERS)
        with urllib.request.urlopen(req, timeout=10) as resp:
            if resp.status == 200:
                html = resp.read().decode("utf-8", errors="replace")
                match = re.search(r'<script id="(?:session|__NEXT_DATA__|resource)" type="application/json">([^<]+)</script>', html)
                if match:
                    data = json.loads(match.group(1).strip())
                    props = data.get("props", {}).get("pageProps", {}) or data
                    entity = props.get("state", {}).get("data", {}).get("entity", {}) or props.get("entity", {})
                    for t in (entity.get("trackList") or entity.get("tracks") or []):
                        if not isinstance(t, dict):
                            continue
                        title = t.get("title") or t.get("name")
                        art = t.get("subtitle") or t.get("artists") or ""
                        artist_str = ", ".join(str(a) for a in art) if isinstance(art, list) else str(art)
                        dur = t.get("duration") or t.get("duration_ms") or 0
                        if title:
                            tracks.append({
                                "title": str(title).strip(),
                                "artist": artist_str.strip(),
                                "album": entity.get("title", "").strip(),
                                "duration_ms": dur,
                                "duration_sec": int(dur / 1000) if dur else 0,
                                "cover_url": entity.get("coverArt", {}).get("sources", [{}])[0].get("url", ""),
                            })
    except Exception:
        pass
    return tracks


def fetch_spotify_metadata_from_url(url: str) -> Tuple[Optional[str], List[Dict[str, Any]]]:
    """Main entrypoint to resolve Spotify URL to structured track list."""
    item_type, item_id = parse_spotify_url(url)
    if not item_type or not item_id:
        return None, []
    token = get_anonymous_spotify_token()
    tracks = fetch_spotify_tracks_via_api(item_type, item_id, token) if token else []
    if not tracks:
        tracks = fetch_spotify_tracks_via_embed(item_type, item_id)
    name = f"Spotify {item_type.capitalize()} ({len(tracks)} tracks)" if tracks else None
    return name, tracks

