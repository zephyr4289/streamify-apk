#include <cstring>
#include <cstdlib>
#include <drogon/drogon.h>
#include "services/StreamifyDB.h"
#include <iostream>
#include <fstream>
#include <json/json.h>

int main(int argc, char* argv[]) {
    std::string config_path = "config.json";
    if (argc > 1) {
        config_path = argv[1];
    }

    std::string db_path = "streamify.db";
    std::string web_root = "./web";
    uint16_t port = 8888;
    size_t threads = 4;

    std::ifstream cfg(config_path);
    if (cfg.is_open()) {
        Json::Value root;
        Json::CharReaderBuilder builder;
        std::string errs;
        if (Json::parseFromStream(builder, cfg, &root, &errs)) {
            if (root.isMember("db_path")) db_path = root["db_path"].asString();
            if (root.isMember("server_port")) port = static_cast<uint16_t>(root["server_port"].asUInt());
            if (root.isMember("server_threads")) threads = root["server_threads"].asUInt();
            if (root.isMember("web_root")) web_root = root["web_root"].asString();
        }
    }

    std::cout << "==================================================" << std::endl;
    std::cout << "  STREAMIFY - High-Performance Bare-Metal Spotify" << std::endl;
    std::cout << "==================================================" << std::endl;

    if (!StreamifyDB::getInstance().init(db_path)) {
        std::cerr << "[Error] Failed to initialize Streamify DB: " << db_path << std::endl;
        return 1;
    }
    std::cout << "[Init] Streamify DB initialized at: " << db_path << std::endl;
    std::cout << "[Init] Serving Spotify Web UI from: " << web_root << std::endl;
    std::cout << "[Server] Listening on http://0.0.0.0:" << port << std::endl;

    // Cluster Microservice Health & Service Discovery Endpoint
    drogon::app().registerHandler(
        "/api/v1/health",
        [](const drogon::HttpRequestPtr& req,
           std::function<void(const std::shared_ptr<drogon::HttpResponse>&)>&& callback) {
            Json::Value res;
            res["service"] = "streamify";
            res["name"] = "Streamify Music Engine";
            res["type"] = "audio_spotify";
            res["status"] = "online";
            res["version"] = "1.0.0";
            res["cluster_ready"] = true;

            Json::Value endpoints;
            endpoints["auth"] = "/api/v1/auth";
            endpoints["stream"] = "/api/v1/stream";
            endpoints["catalog"] = "/api/v1/tracks";
            endpoints["recommendations"] = "/api/v1/recommend/next";
            endpoints["downloader"] = "/api/v1/download";
            endpoints["events"] = "/api/v1/event";
            res["endpoints"] = endpoints;

            auto resp = drogon::HttpResponse::newHttpJsonResponse(res);
            resp->setStatusCode(drogon::k200OK);
            callback(resp);
        },
        {drogon::Get}
    );

    drogon::app()
        .setDocumentRoot(web_root)
        .addListener("0.0.0.0", port)
        .setThreadNum(threads)
        .run();

    return 0;
}
