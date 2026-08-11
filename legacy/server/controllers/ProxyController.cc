#include "ProxyController.h"
#include "../services/StreamifyDB.h"
#include <drogon/HttpClient.h>
#include <json/json.h>
#include <iostream>

static int getAuthenticatedUserId(const drogon::HttpRequestPtr& req) {
    std::string authHeader = req->getHeader("Authorization");
    if (authHeader.rfind("Bearer ", 0) == 0) {
        std::string token = authHeader.substr(7);
        auto userOpt = StreamifyDB::getInstance().validateSession(token);
        if (userOpt) {
            return userOpt->id;
        }
    }
    return 1; // Default guest user
}

void ProxyController::getRecommendations(const drogon::HttpRequestPtr& req,
                                          std::function<void(const drogon::HttpResponsePtr&)>&& callback) {
    int authUserId = getAuthenticatedUserId(req);
    auto client = drogon::HttpClient::newHttpClient("http://127.0.0.1:8080");
    auto proxyReq = drogon::HttpRequest::newHttpRequest();
    proxyReq->setPath("/api/v1/recommend/next");
    
    // Copy query parameters, explicitly enforcing validated authUserId
    for (const auto& [k, v] : req->getParameters()) {
        if (k != "user_id") {
            proxyReq->setParameter(k, v);
        }
    }
    proxyReq->setParameter("user_id", std::to_string(authUserId));

    client->sendRequest(proxyReq, [callback](drogon::ReqResult result, const drogon::HttpResponsePtr& resp) {
        if (result == drogon::ReqResult::Ok && resp) {
            callback(resp);
        } else {
            auto tracks = StreamifyDB::getInstance().getAllTracks();
            Json::Value arr(Json::arrayValue);
            for (size_t i = 0; i < tracks.size() && i < 5; ++i) {
                Json::Value item;
                item["id"] = tracks[i].id;
                item["title"] = tracks[i].title;
                item["artist"] = tracks[i].artist;
                item["score"] = 0.95 - (i * 0.05);
                arr.append(item);
            }
            auto fallbackResp = drogon::HttpResponse::newHttpJsonResponse(arr);
            fallbackResp->setStatusCode(drogon::k200OK);
            callback(fallbackResp);
        }
    });
}

void ProxyController::recordPlayEvent(const drogon::HttpRequestPtr& req,
                                       std::function<void(const drogon::HttpResponsePtr&)>&& callback) {
    int authUserId = getAuthenticatedUserId(req);
    auto jsonPtr = req->getJsonObject();
    Json::Value payload = jsonPtr ? *jsonPtr : Json::Value(Json::objectValue);
    payload["user_id"] = authUserId; // Enforce validated user_id

    auto client = drogon::HttpClient::newHttpClient("http://127.0.0.1:8080");
    auto proxyReq = drogon::HttpRequest::newHttpJsonRequest(payload);
    proxyReq->setMethod(drogon::Post);
    proxyReq->setPath("/api/v1/event/play");

    client->sendRequest(proxyReq, [callback](drogon::ReqResult result, const drogon::HttpResponsePtr& resp) {
        if (result == drogon::ReqResult::Ok && resp) {
            callback(resp);
        } else {
            Json::Value res;
            res["status"] = "acknowledged";
            auto fallbackResp = drogon::HttpResponse::newHttpJsonResponse(res);
            fallbackResp->setStatusCode(drogon::k200OK);
            callback(fallbackResp);
        }
    });
}

void ProxyController::recordSkipEvent(const drogon::HttpRequestPtr& req,
                                       std::function<void(const drogon::HttpResponsePtr&)>&& callback) {
    int authUserId = getAuthenticatedUserId(req);
    auto jsonPtr = req->getJsonObject();
    Json::Value payload = jsonPtr ? *jsonPtr : Json::Value(Json::objectValue);
    payload["user_id"] = authUserId; // Enforce validated user_id

    auto client = drogon::HttpClient::newHttpClient("http://127.0.0.1:8080");
    auto proxyReq = drogon::HttpRequest::newHttpJsonRequest(payload);
    proxyReq->setMethod(drogon::Post);
    proxyReq->setPath("/api/v1/event/skip");

    client->sendRequest(proxyReq, [callback](drogon::ReqResult result, const drogon::HttpResponsePtr& resp) {
        if (result == drogon::ReqResult::Ok && resp) {
            callback(resp);
        } else {
            Json::Value res;
            res["status"] = "acknowledged";
            auto fallbackResp = drogon::HttpResponse::newHttpJsonResponse(res);
            fallbackResp->setStatusCode(drogon::k200OK);
            callback(fallbackResp);
        }
    });
}

