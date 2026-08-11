#include <drogon/drogon.h>
#include "services/DatabaseService.h"
#include "src/services/VectorStore.h"
#include "src/ingest/AudioPipeline.h"
#include "src/ingest/DirectoryWatcher.h"
#include <iostream>
#include <fstream>
#include <json/json.h>
#include <thread>
#include <memory>
#include <sys/stat.h>

inline bool fileExists(const std::string& name) {
    struct stat buffer;
    return (stat(name.c_str(), &buffer) == 0);
}

int main(int argc, char* argv[]) {
    std::string config_path = "../config.json";
    if (argc > 1) {
        config_path = argv[1];
    }

    std::string db_path = "music_engine.db";
    std::string bin_path = "vectors.bin";
    std::string faiss_index_path = "index.faiss";
    uint16_t port = 8080;
    size_t num_threads = 4;

    std::ifstream cfg_file(config_path);
    if (cfg_file.is_open()) {
        Json::Value root;
        Json::CharReaderBuilder builder;
        std::string errs;
        if (Json::parseFromStream(builder, cfg_file, &root, &errs)) {
            if (root.isMember("db_path")) db_path = root["db_path"].asString();
            if (root.isMember("vector_bin_path")) bin_path = root["vector_bin_path"].asString();
            if (root.isMember("faiss_index_path")) faiss_index_path = root["faiss_index_path"].asString();
            if (root.isMember("server_port")) port = static_cast<uint16_t>(root["server_port"].asUInt());
            if (root.isMember("server_threads")) num_threads = root["server_threads"].asUInt();
        }
    }

    std::cout << "==========================================" << std::endl;
    std::cout << "  Music Processing & Recommendation Engine" << std::endl;
    std::cout << "==========================================" << std::endl;

    // Initialize Database
    if (!DatabaseService::getInstance().init(db_path)) {
        std::cerr << "Failed to initialize SQLite Database at " << db_path << std::endl;
        return 1;
    }
    std::cout << "[Init] DatabaseService connected: " << db_path << std::endl;

    // Initialize Vector Store
    VectorStore::getInstance().init(bin_path, 512);
    std::cout << "[Init] VectorStore AVX2 engine initialized." << std::endl;

    // Initialize AudioPipeline & DirectoryWatcher
    std::string onnx_model_path = root.get("onnx_model_path", "model.onnx").asString();
    std::unique_ptr<DirectoryWatcher> watcher;

    if (!fileExists(onnx_model_path)) {
        std::cout << "[Notice] ONNX model file '" << onnx_model_path << "' not found.\n"
                  << "AudioPipeline will run in DSP Log-Mel spectral fallback mode for audio feature extraction." << std::endl;
    } else {
        AudioPipeline::getInstance().init(onnx_model_path);
        std::cout << "[Init] AudioPipeline ONNX initialized." << std::endl;
    }

    std::string audio_inbox = root.get("audio_dir", "./audio_inbox").asString();
    watcher = std::make_unique<DirectoryWatcher>(audio_inbox, [](const std::string& filepath) {
        std::cout << "[Ingest] New file detected: " << filepath << std::endl;
        std::thread([filepath]() {
            if (DatabaseService::getInstance().trackExists(filepath)) {
                return;
            }
            auto vec = AudioPipeline::getInstance().processAudio(filepath);
            if (!vec.empty()) {
                int offset = VectorStore::getInstance().addVector(vec);
                if (offset >= 0) {
                    auto meta = AudioPipeline::getInstance().extractMetadata(filepath);
                    int track_id = DatabaseService::getInstance().insertTrack(filepath, meta.title, meta.artist, meta.bpm, meta.key, offset);
                    if (track_id > 0) {
                        std::cout << "[Ingest] Successfully ingested track #" << track_id << ": " << meta.title << std::endl;
                    }
                }
            }
        }).detach();
    });
    watcher->start();

    std::cout << "[Server] Listening on http://0.0.0.0:" << port << std::endl;

    drogon::app()
        .addListener("0.0.0.0", port)
        .setThreadNum(num_threads)
        .run();

    if (watcher) watcher->stop();
    return 0;
}
