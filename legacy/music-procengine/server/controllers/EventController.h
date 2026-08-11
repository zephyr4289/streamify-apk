#ifndef EVENT_CONTROLLER_H
#define EVENT_CONTROLLER_H

#include <drogon/HttpController.h>

class EventController : public drogon::HttpController<EventController> {
public:
    METHOD_LIST_BEGIN
    ADD_METHOD_TO(EventController::recordPlayEvent, "/api/v1/event/play", drogon::Post);
    ADD_METHOD_TO(EventController::recordSkipEvent, "/api/v1/event/skip", drogon::Post);
    METHOD_LIST_END

    void recordPlayEvent(const drogon::HttpRequestPtr& req,
                         std::function<void(const drogon::HttpResponsePtr&)>&& callback);

    void recordSkipEvent(const drogon::HttpRequestPtr& req,
                         std::function<void(const drogon::HttpResponsePtr&)>&& callback);
};

#endif // EVENT_CONTROLLER_H
