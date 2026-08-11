#include "CatalogController.h"
#include "../services/StreamifyDB.h"
#include <json/json.h>

void CatalogController::getTracks(const drogon::HttpRequestPtr& req,
                                  std::function<void(const drogon::HttpResponsePtr&)>&& callback) {
    auto tracks = StreamifyDB::getInstance().getAllTracks();

    Json::Value arr(Json::arrayValue);
    for (const auto& t : tracks) {
        Json::Value item;
        item["id"] = t.id;
        item["title"] = t.title;
        item["artist"] = t.artist;
        item["album"] = t.album;
        item["bpm"] = t.bpm;
        item["key"] = t.key;
        arr.append(item);
    }

    auto resp = drogon::HttpResponse::newHttpJsonResponse(arr);
    resp->setStatusCode(drogon::k200OK);
    callback(resp);
}

void CatalogController::searchTracks(const drogon::HttpRequestPtr& req,
                                     std::function<void(const drogon::HttpResponsePtr&)>&& callback) {
    const auto& params = req->getParameters();
    auto q_it = params.find("q");
    std::string q = (q_it != params.end()) ? q_it->second : "";

    auto tracks = StreamifyDB::getInstance().searchTracks(q);

    Json::Value arr(Json::arrayValue);
    for (const auto& t : tracks) {
        Json::Value item;
        item["id"] = t.id;
        item["title"] = t.title;
        item["artist"] = t.artist;
        item["album"] = t.album;
        item["bpm"] = t.bpm;
        item["key"] = t.key;
        arr.append(item);
    }

    auto resp = drogon::HttpResponse::newHttpJsonResponse(arr);
    resp->setStatusCode(drogon::k200OK);
    callback(resp);
}
