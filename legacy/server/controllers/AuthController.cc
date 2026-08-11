#include "AuthController.h"
#include "../services/StreamifyDB.h"
#include <json/json.h>
#include <iostream>

static std::string extractToken(const drogon::HttpRequestPtr& req) {
    std::string auth_hdr = req->getHeader("Authorization");
    if (auth_hdr.rfind("Bearer ", 0) == 0) {
        return auth_hdr.substr(7);
    }
    std::string token_hdr = req->getHeader("X-Session-Token");
    if (!token_hdr.empty()) return token_hdr;

    const auto& params = req->getParameters();
    auto token_it = params.find("token");
    if (token_it != params.end()) return token_it->second;

    return "";
}

void AuthController::loginOrRegister(const drogon::HttpRequestPtr& req,
                                      std::function<void(const drogon::HttpResponsePtr&)>&& callback) {
    auto json = req->getJsonObject();
    if (!json || !json->isMember("username") || !json->isMember("pin")) {
        Json::Value err;
        err["error"] = "Username and 4-digit PIN are required";
        auto resp = drogon::HttpResponse::newHttpJsonResponse(err);
        resp->setStatusCode(drogon::k400BadRequest);
        callback(resp);
        return;
    }

    std::string username = (*json)["username"].asString();
    std::string pin = (*json)["pin"].asString();

    if (username.empty() || pin.length() < 4) {
        Json::Value err;
        err["error"] = "Invalid username or 4-digit PIN";
        auto resp = drogon::HttpResponse::newHttpJsonResponse(err);
        resp->setStatusCode(drogon::k400BadRequest);
        callback(resp);
        return;
    }

    auto userOpt = StreamifyDB::getInstance().registerOrLoginUser(username, pin);
    if (!userOpt) {
        Json::Value err;
        err["error"] = "Incorrect PIN for existing profile";
        auto resp = drogon::HttpResponse::newHttpJsonResponse(err);
        resp->setStatusCode(drogon::k401Unauthorized);
        callback(resp);
        return;
    }

    std::string token = StreamifyDB::getInstance().createSession(userOpt->id);
    if (token.empty()) {
        Json::Value err;
        err["error"] = "Failed to create user session";
        auto resp = drogon::HttpResponse::newHttpJsonResponse(err);
        resp->setStatusCode(drogon::k500InternalServerError);
        callback(resp);
        return;
    }

    Json::Value res;
    res["status"] = "success";
    res["token"] = token;
    res["user"]["id"] = userOpt->id;
    res["user"]["username"] = userOpt->username;

    auto resp = drogon::HttpResponse::newHttpJsonResponse(res);
    resp->setStatusCode(drogon::k200OK);
    callback(resp);
}

void AuthController::getCurrentUser(const drogon::HttpRequestPtr& req,
                                     std::function<void(const drogon::HttpResponsePtr&)>&& callback) {
    std::string token = extractToken(req);
    auto userOpt = StreamifyDB::getInstance().validateSession(token);
    if (!userOpt) {
        Json::Value err;
        err["error"] = "Invalid or expired session";
        auto resp = drogon::HttpResponse::newHttpJsonResponse(err);
        resp->setStatusCode(drogon::k401Unauthorized);
        callback(resp);
        return;
    }

    Json::Value res;
    res["user"]["id"] = userOpt->id;
    res["user"]["username"] = userOpt->username;

    auto resp = drogon::HttpResponse::newHttpJsonResponse(res);
    resp->setStatusCode(drogon::k200OK);
    callback(resp);
}

void AuthController::logout(const drogon::HttpRequestPtr& req,
                             std::function<void(const drogon::HttpResponsePtr&)>&& callback) {
    std::string token = extractToken(req);
    StreamifyDB::getInstance().deleteSession(token);

    Json::Value res;
    res["status"] = "logged_out";
    auto resp = drogon::HttpResponse::newHttpJsonResponse(res);
    resp->setStatusCode(drogon::k200OK);
    callback(resp);
}

void AuthController::getLikedTracks(const drogon::HttpRequestPtr& req,
                                    std::function<void(const drogon::HttpResponsePtr&)>&& callback) {
    std::string token = extractToken(req);
    auto userOpt = StreamifyDB::getInstance().validateSession(token);
    if (!userOpt) {
        Json::Value err;
        err["error"] = "Unauthorized";
        auto resp = drogon::HttpResponse::newHttpJsonResponse(err);
        resp->setStatusCode(drogon::k401Unauthorized);
        callback(resp);
        return;
    }

    auto tracks = StreamifyDB::getInstance().getUserLikedTracks(userOpt->id);
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

void AuthController::toggleLikedTrack(const drogon::HttpRequestPtr& req,
                                       std::function<void(const drogon::HttpResponsePtr&)>&& callback) {
    std::string token = extractToken(req);
    auto userOpt = StreamifyDB::getInstance().validateSession(token);
    if (!userOpt) {
        Json::Value err;
        err["error"] = "Unauthorized";
        auto resp = drogon::HttpResponse::newHttpJsonResponse(err);
        resp->setStatusCode(drogon::k401Unauthorized);
        callback(resp);
        return;
    }

    auto json = req->getJsonObject();
    if (!json || !json->isMember("track_id")) {
        Json::Value err;
        err["error"] = "track_id is required";
        auto resp = drogon::HttpResponse::newHttpJsonResponse(err);
        resp->setStatusCode(drogon::k400BadRequest);
        callback(resp);
        return;
    }

    int track_id = (*json)["track_id"].asInt();
    bool is_liked = false;
    StreamifyDB::getInstance().toggleUserLikedTrack(userOpt->id, track_id, is_liked);

    Json::Value res;
    res["track_id"] = track_id;
    res["is_liked"] = is_liked;

    auto resp = drogon::HttpResponse::newHttpJsonResponse(res);
    resp->setStatusCode(drogon::k200OK);
    callback(resp);
}
