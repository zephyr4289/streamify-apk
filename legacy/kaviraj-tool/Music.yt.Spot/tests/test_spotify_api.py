import pytest
from downloader.spotify_api import parse_spotify_url


def test_parse_spotify_url_playlist():
    item_type, item_id = parse_spotify_url("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=123")
    assert item_type == "playlist"
    assert item_id == "37i9dQZF1DXcBWIGoYBM5M"


def test_parse_spotify_url_album():
    item_type, item_id = parse_spotify_url("https://open.spotify.com/album/4aawyAB9vmqN3uQ7FjRGTy")
    assert item_type == "album"
    assert item_id == "4aawyAB9vmqN3uQ7FjRGTy"


def test_parse_spotify_url_track():
    item_type, item_id = parse_spotify_url("https://open.spotify.com/track/0VjLj2DipiyLwoxbeRppwU")
    assert item_type == "track"
    assert item_id == "0VjLj2DipiyLwoxbeRppwU"


def test_parse_spotify_uri():
    item_type, item_id = parse_spotify_url("spotify:playlist:37i9dQZF1DXcBWIGoYBM5M")
    assert item_type == "playlist"
    assert item_id == "37i9dQZF1DXcBWIGoYBM5M"


def test_parse_invalid_url():
    item_type, item_id = parse_spotify_url("https://youtube.com/watch?v=12345")
    assert item_type is None
    assert item_id is None
