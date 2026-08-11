import json
from pathlib import Path
from typing import Dict, Any
from downloader.utils import BASE_DIR

CONFIG_FILE = BASE_DIR / "config.json"

DEFAULT_CONFIG: Dict[str, Any] = {
    "max_workers": 10,
    "min_score": 70,
    "ytmusic_priority": True,
    "fetch_lyrics": True,
    "embed_lyrics": True,
    "fetch_high_res_cover": True,
    "square_crop_artwork": True,
    "auto_sync_android_music": True,
    "termux_wake_lock": True,
    "termux_notifications": True,
    "search_cache_enabled": True,
    "output_template": "%(title)s.%(ext)s",
    "include_index_in_filename": False,
    "duration_match_threshold_sec": 10,
    "audio_format": "best_native",
}


def load_config() -> Dict[str, Any]:
    """Loads configuration settings from config.json or initializes default file."""
    if not CONFIG_FILE.exists():
        save_config(DEFAULT_CONFIG)
        return DEFAULT_CONFIG.copy()

    try:
        with open(CONFIG_FILE, "r", encoding="utf-8") as f:
            user_cfg = json.load(f)
        # Merge with defaults to ensure missing keys are populated
        merged = DEFAULT_CONFIG.copy()
        merged.update(user_cfg)
        return merged
    except Exception as e:
        print(f"WARNING: Could not parse config.json ({e}). Using defaults.")
        return DEFAULT_CONFIG.copy()


def save_config(cfg: Dict[str, Any]) -> None:
    """Saves configuration dict to config.json."""
    try:
        with open(CONFIG_FILE, "w", encoding="utf-8") as f:
            json.dump(cfg, f, indent=2)
    except Exception as e:
        print(f"WARNING: Could not write config.json: {e}")


def update_config_key(key: str, val: Any) -> None:
    """Helper to update a single configuration key in config.json."""
    cfg = load_config()
    cfg[key] = val
    save_config(cfg)


