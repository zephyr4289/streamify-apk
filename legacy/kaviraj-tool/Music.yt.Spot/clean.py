#!/usr/bin/env python3
"""
Top-level script wrapper to reset/clean previous CSV data, progress logs, caches, and temporary files.
"""

import sys
from downloader.utils import clean_project_cache

if __name__ == "__main__":
    include_output = "--all" in sys.argv or "-a" in sys.argv
    clean_project_cache(include_output=include_output)
