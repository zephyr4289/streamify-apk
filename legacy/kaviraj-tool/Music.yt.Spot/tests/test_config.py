import pytest
import json
from pathlib import Path
from downloader.config import load_config, save_config, update_config_key, DEFAULT_CONFIG, CONFIG_FILE


def test_load_config_defaults(tmp_path, monkeypatch):
    test_cfg_file = tmp_path / "config.json"
    monkeypatch.setattr("downloader.config.CONFIG_FILE", test_cfg_file)
    cfg = load_config()
    assert cfg["max_workers"] == 10
    assert cfg["audio_format"] == "best_native"
    assert test_cfg_file.exists()


def test_update_config_key(tmp_path, monkeypatch):
    test_cfg_file = tmp_path / "config.json"
    monkeypatch.setattr("downloader.config.CONFIG_FILE", test_cfg_file)
    update_config_key("max_workers", 5)
    cfg = load_config()
    assert cfg["max_workers"] == 5
