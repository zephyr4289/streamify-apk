import pytest
from pathlib import Path
from downloader.ffmpeg_tagger import crop_square_artwork, apply_native_metadata


def test_tagger_non_existent_file():
    fake_path = Path("non_existent_audio_file.mp3")
    success, msg = apply_native_metadata(fake_path, "Title", "Artist", "Album")
    assert success is False
    assert "file does not exist" in msg.lower()


def test_crop_non_existent_artwork():
    fake_img = Path("non_existent_art.jpg")
    success, msg = crop_square_artwork(fake_img)
    assert success is False
    assert "not found" in msg.lower()
