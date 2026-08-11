.PHONY: all build init-db ingest test clean help

BUILD_DIR = build
PYTHON = python3

all: init-db build

init-db:
	@echo "==> Initializing SQLite Database..."
	$(PYTHON) scripts/init_db.py music_engine.db

build:
	@echo "==> Compiling Drogon Recommendation C++ Server..."
	mkdir -p $(BUILD_DIR)
	cd $(BUILD_DIR) && cmake .. && cmake --build . -j$$(nproc 2>/dev/null || echo 2)

run-server: build
	@echo "==> Starting Recommendation API Server..."
	./$(BUILD_DIR)/server/audio_recommendation_server config.json

ingest:
	@echo "==> Native Audio Ingestion is built into the C++ server via DirectoryWatcher."
	@echo "==> Start the server with 'make run-server' and place audio files in ./audio_inbox."

test:
	@echo "==> Running Self-Tests..."
	$(PYTHON) scripts/test_api.py

clean:
	@echo "==> Cleaning build artifacts..."
	rm -rf $(BUILD_DIR) *.db *.faiss *.bin

help:
	@echo "Available targets:"
	@echo "  make init-db     - Initialize SQLite database tables"
	@echo "  make build       - Build C++ Drogon recommendation server"
	@echo "  make run-server  - Run C++ Drogon recommendation server"
	@echo "  make ingest      - Start Python audio ingestion worker"
	@echo "  make test        - Run test suite"
	@echo "  make clean       - Remove build artifacts and temporary files"
