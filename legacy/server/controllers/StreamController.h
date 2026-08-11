#ifndef STREAM_CONTROLLER_H
#define STREAM_CONTROLLER_H

#include <cstring>
#include <cstdlib>
#include <drogon/HttpController.h>

class StreamController : public drogon::HttpController<StreamController> {
public:
    METHOD_LIST_BEGIN
    ADD_METHOD_TO(StreamController::streamAudio, "/api/v1/stream", drogon::Get);
    METHOD_LIST_END

    void streamAudio(const drogon::HttpRequestPtr& req,
                     std::function<void(const drogon::HttpResponsePtr&)>&& callback);
};

#endif // STREAM_CONTROLLER_H
