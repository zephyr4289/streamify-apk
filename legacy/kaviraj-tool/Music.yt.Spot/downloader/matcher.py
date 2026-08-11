import json
import threading
from difflib import SequenceMatcher
from typing import List, Dict, Tuple, Set, Any, Optional
from downloader.utils import DATA_DIR, normalize, words, run_command

SEARCH_COUNT = 5
MIN_SCORE = 70
SEARCH_CACHE_FILE = DATA_DIR / "search_cache.json"
_search_cache_lock = threading.Lock()


def _load_search_cache() -> Dict[str, Any]:
    if not SEARCH_CACHE_FILE.exists():
        return {}
    try:
        with open(SEARCH_CACHE_FILE, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}


def _save_search_cache(cache: Dict[str, Any]) -> None:
    try:
        tmp = SEARCH_CACHE_FILE.with_suffix(".tmp")
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(cache, f, indent=2, ensure_ascii=False)
        tmp.replace(SEARCH_CACHE_FILE)
    except Exception:
        pass


def search_youtube_entries(query: str, count: int = SEARCH_COUNT, use_ytmusic: bool = False) -> List[Dict[str, Any]]:
    """Fetches YouTube search entries using yt-dlp Python API or subprocess CLI fallback."""
    prefix = "ytmusicsearch" if use_ytmusic else "ytsearch"
    cache_key = f"{prefix}_{query}__{count}"
    with _search_cache_lock:
        cache = _load_search_cache()
        if cache_key in cache:
            return cache[cache_key]

    entries: List[Dict[str, Any]] = []

    # 1. Try native yt_dlp Python API
    try:
        import yt_dlp

        ydl_opts = {
            "extract_flat": True,
            "skip_download": True,
            "quiet": True,
            "no_warnings": True,
            "ignoreerrors": True,
        }
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            res = ydl.extract_info(f"{prefix}{count}:{query}", download=False)
            if res and "entries" in res:
                entries = [e for e in res["entries"] if e and isinstance(e, dict)]
    except Exception:
        entries = []

    # Fallback to ytsearch if ytmusicsearch returned no entries
    if not entries and use_ytmusic:
        return search_youtube_entries(query, count=count, use_ytmusic=False)

    # 2. Subprocess CLI fallback if Python API failed or returned empty
    if not entries:
        cmd = ["yt-dlp", "--flat-playlist", "--dump-single-json", f"{prefix}{count}:{query}"]
        code, stdout, _ = run_command(cmd)
        if code == 0 and stdout.strip():
            try:
                entries = (json.loads(stdout) or {}).get("entries") or []
            except Exception:
                entries = []

    # Store in cache
    if entries:
        with _search_cache_lock:
            c = _load_search_cache()
            c[cache_key] = entries
            _save_search_cache(c)

    return entries


def similarity(title: str, candidate: str) -> float:
    """
    Calculates combined title similarity using fuzzy sequence matching (difflib)
    and set word overlap.
    """
    a_norm = normalize(title)
    b_norm = normalize(candidate)

    if not a_norm or not b_norm:
        return 0.0

    # 1. Fuzzy Sequence Ratio (handles word order, minor spelling differences)
    seq_ratio = SequenceMatcher(None, a_norm, b_norm).ratio()

    # 2. Word Set Overlap Ratio
    w_a = words(title)
    w_b = words(candidate)
    set_ratio = (len(w_a & w_b) / len(w_a)) if w_a else 0.0

    # Weighted average: 60% sequence ratio, 40% set overlap
    return (0.6 * seq_ratio) + (0.4 * set_ratio)


def artist_match(artists: str, candidate_title: str, candidate_channel: str) -> int:
    """Evaluates artist presence in candidate title or channel name."""
    haystack = normalize(f"{candidate_title} {candidate_channel}")
    score = 0

    if not artists:
        return 0

    artist_list = [a.strip() for a in artists.split(",") if a.strip()]

    for artist in artist_list:
        artist_norm = normalize(artist)
        if not artist_norm:
            continue

        if artist_norm in haystack:
            score += 30
        else:
            artist_w = words(artist)
            if artist_w:
                overlap = len(artist_w & words(haystack))
                if overlap >= max(1, len(artist_w) // 2):
                    score += 15

    return min(score, 40)


def bad_candidate(candidate_title: str, target_title: str = "") -> bool:
    """Detects unwanted track variants (slowed, reverb, cover, remix, mix, etc.) unless target title requests it."""
    t = normalize(candidate_title)
    target_norm = normalize(target_title)
    bad_words = [
        "slowed",
        "reverb",
        "sped up",
        "speed up",
        "8d",
        "nightcore",
        "remix",
        "mix",
        "mashup",
        "cover",
        "reaction",
        "karaoke",
        "instrumental",
        "live",
        "shorts",
        "bootleg",
        "rework",
    ]
    for w in bad_words:
        if w in t and w not in target_norm:
            return True
    return False


def score_candidate(
    spotify_title: str,
    spotify_artists: str,
    yt_title: str,
    channel: str,
    candidate_duration: Optional[int] = None,
    target_duration: Optional[int] = None,
) -> int:
    """Scores a YouTube candidate (0 to 100) based on title, artist, topic channel, duration, and strict penalties."""
    score = 0
    score += min(50, int(similarity(spotify_title, yt_title) * 50))

    art_score = artist_match(spotify_artists, yt_title, channel)
    score += art_score

    # Strict Penalty: If artist name is provided but candidate title & channel completely miss the artist
    if spotify_artists and spotify_artists.strip() and art_score == 0:
        score -= 45

    if bad_candidate(yt_title, spotify_title):
        score -= 35

    # Topic Channel Bonus (+20 pts)
    chan_norm = normalize(channel)
    if "topic" in chan_norm or channel.endswith("- Topic"):
        score += 20

    # Duration Match Scoring & Severe Penalty for Large Discrepancies
    if candidate_duration and target_duration and candidate_duration > 0 and target_duration > 0:
        diff = abs(candidate_duration - target_duration)
        rel_diff = diff / max(target_duration, 1)
        if diff <= 5:
            score += 15
        elif diff <= 10:
            score += 5
        elif diff > 60 or rel_diff > 0.3:
            score -= 60
        elif diff > 30 or rel_diff > 0.15:
            score -= 40
        elif diff > 15:
            score -= 20

    if normalize(yt_title).startswith(normalize(spotify_title)):
        score += 10

    return max(0, min(100, score))


def search_youtube(
    title: str,
    artists: str,
    count: int = SEARCH_COUNT,
    min_score: int = MIN_SCORE,
    use_ytmusic: bool = True,
    target_duration_sec: Optional[int] = None,
) -> Tuple[List[Dict[str, Any]], str]:
    """Queries YouTube using multi-pass search strategy with Python API & cache support."""
    queries = []
    if artists:
        queries.append(f"{artists} {title}")
        queries.append(f"{artists} - {title} Topic")
    else:
        queries.append(f"{title} Official Audio")
        queries.append(title)

    all_candidates: List[Dict[str, Any]] = []
    seen_urls: Set[str] = set()

    for query in queries:
        entries = search_youtube_entries(query, count=count, use_ytmusic=use_ytmusic)
        for entry in entries:
            if not entry:
                continue
            yt_title = entry.get("title") or ""
            channel = entry.get("channel") or entry.get("uploader") or ""
            url = entry.get("webpage_url") or (f"https://www.youtube.com/watch?v={entry['id']}" if entry.get("id") else None)
            candidate_duration = entry.get("duration")

            if not yt_title or not url or url in seen_urls:
                continue

            seen_urls.add(url)
            cand_score = score_candidate(title, artists, yt_title, channel, candidate_duration, target_duration_sec)
            all_candidates.append({
                "score": cand_score,
                "title": yt_title,
                "channel": channel,
                "url": url,
                "duration": candidate_duration,
            })

        all_candidates.sort(key=lambda x: x["score"], reverse=True)
        if all_candidates and all_candidates[0]["score"] >= min_score:
            return all_candidates, ""

    return (all_candidates, "") if all_candidates else ([], "No YouTube candidates found")

