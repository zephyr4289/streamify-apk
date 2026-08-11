#!/usr/bin/env python3
"""
Top-level script wrapper for preparing Spotify playlist CSV data from Exportify JSON.
"""

import sys
from downloader.spotify_mode import prepare_csv

if __name__ == "__main__":
    path = sys.argv[1] if len(sys.argv) > 1 else None
    success = prepare_csv(path)
    sys.exit(0 if success else 1)
