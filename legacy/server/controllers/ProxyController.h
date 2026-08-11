#ifndef PROXY_CONTROLLER_H
#define PROXY_CONTROLLER_H

#include <cstring>
#include <cstdlib>
#include <drogon/HttpController.h>

class ProxyController : public drogon::HttpController<ProxyController> {
public:
    METHOD_LIST_BEGIN
    ADD_METHOD_TO(ProxyController::getRecommendations, "/api/v1/recommend/next", drogon::Get);
    ADD_METHOD_TO(ProxyController::recordPlayEvent, "/api/v1/event/play", drogon::Post);
    ADD_METHOD_TO(ProxyController::recordSkipEvent, "/api/v1/event/skip", drogon::Post);
    METHOD_LIST_END

    void getRecommendations(const drogon::HttpRequestPtr& req,
                            std::function<void(const drogon::HttpResponsePtr&)>&& callback);

    void recordPlayEvent(const drogon::HttpRequestPtr& req,
                         std::function<void(const drogon::HttpResponsePtr&)>&& callback);

    void recordSkipEvent(const drogon::HttpRequestPtr& req,
                         std::function<void(const drogon::HttpResponsePtr&)>&& callback);
};

#endif // PROXY_CONTROLLER_H
