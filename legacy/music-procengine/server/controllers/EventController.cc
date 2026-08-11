#include "EventController.h"
#include "../services/DatabaseService.h"
#include <json/json.h>

void EventController::recordPlayEvent(const drogon::HttpRequestPtr& req,
                                      std::function<void(const drogon::HttpResponsePtr&)>&& callback) {
    auto jsonPtr = req->getJsonObject();
    if (!jsonPtr) {
        Json::Value errorJson;
        errorJson["error"] = "Invalid JSON payload";
        auto resp = drogon::HttpResponse::newHttpJsonObjectResponse(errorJson);
        resp->setStatusCode(drogon::k400BadRequest);
        callback(resp);
        return;
    }

    if (!jsonPtr->isMember("current_track_id") || !jsonPtr->isMember("previous_track_id")) {
        Json::Value errorJson;
        errorJson["error"] = "Missing required parameters: current_track_id, previous_track_id";
        auto resp = drogon::HttpResponse::newHttpJsonObjectResponse(errorJson);
        resp->setStatusCode(drogon::k400BadRequest);
        callback(resp);
        return;
    }

    int current_track_id = (*jsonPtr)["current_track_id"].asInt();
    int previous_track_id = (*jsonPtr)["previous_track_id"].asInt();

    // Verify track existence
    auto& db = DatabaseService::getInstance();
    auto curTrack = db.getTrackById(current_track_id);
    auto prevTrack = db.getTrackById(previous_track_id);

    if (!curTrack || !prevTrack) {
        Json::Value errorJson;
        errorJson["error"] = "Specified track_id does not exist";
        auto resp = drogon::HttpResponse::newHttpJsonObjectResponse(errorJson);
        resp->setStatusCode(drogon::k404NotFound);
        callback(resp);
        return;
    }

    bool success = db.recordPlayEvent(current_track_id, previous_track_id);
    if (!success) {
        Json::Value errorJson;
        errorJson["error"] = "Failed to record transition event in database";
        auto resp = drogon::HttpResponse::newHttpJsonObjectResponse(errorJson);
        resp->setStatusCode(drogon::k500InternalServerError);
        callback(resp);
        return;
    }

    Json::Value successJson;
    successJson["status"] = "success";
    successJson["current_track_id"] = current_track_id;
    successJson["previous_track_id"] = previous_track_id;
    
    auto resp = drogon::HttpResponse::newHttpJsonObjectResponse(successJson);
    resp->setStatusCode(drogon::k200OK);
    callback(resp);
}

void EventController::recordSkipEvent(const drogon::HttpRequestPtr& req,
                                      std::function<void(const drogon::HttpResponsePtr&)>&& callback) {
    auto jsonPtr = req->getJsonObject();
    if (!jsonPtr) {
        Json::Value errorJson;
        errorJson["error"] = "Invalid JSON payload";
        auto resp = drogon::HttpResponse::newHttpJsonObjectResponse(errorJson);
        resp->setStatusCode(drogon::k400BadRequest);
        callback(resp);
        return;
    }

    if (!jsonPtr->isMember("current_track_id") || !jsonPtr->isMember("previous_track_id")) {
        Json::Value errorJson;
        errorJson["error"] = "Missing required parameters: current_track_id, previous_track_id";
        auto resp = drogon::HttpResponse::newHttpJsonObjectResponse(errorJson);
        resp->setStatusCode(drogon::k400BadRequest);
        callback(resp);
        return;
    }

    int current_track_id = (*jsonPtr)["current_track_id"].asInt();
    int previous_track_id = (*jsonPtr)["previous_track_id"].asInt();

    // Verify track existence
    auto& db = DatabaseService::getInstance();
    auto curTrack = db.getTrackById(current_track_id);
    auto prevTrack = db.getTrackById(previous_track_id);

    if (!curTrack || !prevTrack) {
        Json::Value errorJson;
        errorJson["error"] = "Specified track_id does not exist";
        auto resp = drogon::HttpResponse::newHttpJsonObjectResponse(errorJson);
        resp->setStatusCode(drogon::k404NotFound);
        callback(resp);
        return;
    }

    bool success = db.recordSkipEvent(current_track_id, previous_track_id);
    if (!success) {
        Json::Value errorJson;
        errorJson["error"] = "Failed to record skip event in database";
        auto resp = drogon::HttpResponse::newHttpJsonObjectResponse(errorJson);
        resp->setStatusCode(drogon::k500InternalServerError);
        callback(resp);
        return;
    }

    Json::Value successJson;
    successJson["status"] = "success";
    successJson["current_track_id"] = current_track_id;
    successJson["previous_track_id"] = previous_track_id;
    
    auto resp = drogon::HttpResponse::newHttpJsonObjectResponse(successJson);
    resp->setStatusCode(drogon::k200OK);
    callback(resp);
}
