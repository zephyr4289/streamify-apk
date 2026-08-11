import pytest
from downloader.utils import normalize, words, sanitize_filename


def test_normalize_basic():
    assert normalize("  Hello  World!  ") == "hello world"
    assert normalize("Song - Name (Official Audio)") == "song name official audio"


def test_normalize_diacritics():
    assert normalize("Mötley Crüe") == "mötley crüe"
    assert normalize("Señorita") == "señorita"
    assert normalize("Beyoncé") == "beyoncé"


def test_normalize_multilingual():
    assert normalize("ਨਵਾਂ ਸੰਧੂ") == "ਨਵਾਂ ਸੰਧੂ"
    assert normalize("अरिजीत सिंह") == "अरिजीत सिंह"
    assert normalize("YOASOBI - 夜に駆ける") == "yoasobi 夜に駆ける"


def test_words():
    assert words("Blinding Lights (Official Video)") == {"blinding", "lights", "official", "video"}
    assert words("") == set()


def test_sanitize_filename():
    assert sanitize_filename("Artist / Track : Name?") == "Artist _ Track _ Name_"
    assert sanitize_filename("  Leading and trailing.  ") == "Leading and trailing"
    assert sanitize_filename("") == "unnamed_track"
