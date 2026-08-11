import pytest
from downloader.matcher import similarity, artist_match, bad_candidate, score_candidate


def test_similarity():
    assert similarity("Blinding Lights", "Blinding Lights") == 1.0
    assert similarity("Starboy", "Starboy (Official Music Video)") > 0.6
    assert similarity("Song A", "Completely Different B") < 0.3


def test_artist_match():
    assert artist_match("The Weeknd", "Starboy", "The Weeknd - Topic") >= 30
    assert artist_match("Post Malone, Swae Lee", "Sunflower (Spider-Man)", "PostMaloneVEVO") >= 15
    assert artist_match("Unknown Artist", "Random Song", "Random Channel") == 0


def test_bad_candidate():
    assert bad_candidate("Song Name (Slowed + Reverb)") is True
    assert bad_candidate("Song Name (Speed Up)") is True
    assert bad_candidate("Song Name 8D Audio") is True
    assert bad_candidate("Official Music Video") is False


def test_score_candidate():
    # Official topic channel match with good title & duration
    score = score_candidate(
        spotify_title="Unbothered",
        spotify_artists="Navaan Sandhu",
        yt_title="Unbothered",
        channel="Navaan Sandhu - Topic",
        candidate_duration=293,
        target_duration=293,
    )
    assert score >= 85

    # Wrong artist penalty (Jineewells instead of Navaan Sandhu)
    wrong_artist_score = score_candidate(
        spotify_title="Unbothered",
        spotify_artists="Navaan Sandhu",
        yt_title="Unbothered",
        channel="Jineewells - Topic",
        candidate_duration=152,
        target_duration=293,
    )
    assert wrong_artist_score < 40  # Must fail 70 threshold!
