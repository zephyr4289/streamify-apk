#include "DownloadController.h"
#include <cstdio>
#include <memory>
#include <stdexcept>
#include <string>
#include <array>
#include <thread>
#include <future>
#include <json/json.h>

static std::string execCommand(const std::string& cmd) {
    std::array<char, 256> buffer;
    std::string result;
    std::unique_ptr<FILE, decltype(&pclose)> pipe(popen(cmd.c_str(), "r"), pclose);
    if (!pipe) {
        return "";
    }
    while (fgets(buffer.data(), static_cast<int>(buffer.size()), pipe.get()) != nullptr) {
        result += buffer.data();
    }
    return result;
}

void DownloadController::downloadTrack(const drogon::HttpRequestPtr& req,
                                       std::function<void(const std::shared_ptr<drogon::HttpResponse>&)>&& callback) {
    auto json = req->getJsonObject();
    if (!json || !json->isMember("query")) {
        Json::Value err;
        err["error"] = "query parameter is required";
        auto resp = drogon::HttpResponse::newHttpJsonResponse(err);
        resp->setStatusCode(drogon::k400BadRequest);
        callback(resp);
        return;
    }

    std::string query = (*json)["query"].asString();
    if (query.empty()) {
        Json::Value err;
        err["error"] = "query cannot be empty";
        auto resp = drogon::HttpResponse::newHttpJsonResponse(err);
        resp->setStatusCode(drogon::k400BadRequest);
        callback(resp);
        return;
    }

    // Run python3 scripts/download_track.py asynchronously off the main reactor loop
    std::thread([query, cb = std::move(callback)]() mutable {
        std::string escapedQuery = query;
        // Basic shell escaping single quotes
        size_t pos = 0;
        while ((pos = escapedQuery.find("'", pos)) != std::string::npos) {
            escapedQuery.replace(pos, 1, "'\\''");
            pos += 4;
        }

        std::string cmd = "python3 scripts/download_track.py '" + escapedQuery + "' streamify.db 2>&1";
        std::string output = execCommand(cmd);

        Json::CharReaderBuilder builder;
        Json::Value root;
        std::string errs;
        std::unique_ptr<Json::CharReader> reader(builder.newCharReader());

        if (reader->parse(output.c_str(), output.c_str() + output.size(), &root, &errs) && root.isMember("status") && root["status"].asString() == "success") {
            auto resp = drogon::HttpResponse::newHttpJsonResponse(root);
            resp->setStatusCode(drogon::k200OK);
            cb(resp);
        } else {
            Json::Value err;
            err["error"] = "Download failed";
            err["details"] = output;
            auto resp = drogon::HttpResponse::newHttpJsonResponse(err);
            resp->setStatusCode(drogon::k500InternalServerError);
            cb(resp);
        }
    }).detach();
}
