#pragma once

#include <cstring>
#include <cstdlib>
#include <drogon/HttpController.h>

class DownloadController : public drogon::HttpController<DownloadController, false> {
public:
    METHOD_LIST_BEGIN
    ADD_METHOD_TO(DownloadController::downloadTrack, "/api/v1/download", drogon::Post);
    METHOD_LIST_END

    void downloadTrack(const drogon::HttpRequestPtr& req,
                       std::function<void(const std::shared_ptr<drogon::HttpResponse>&)>&& callback);
};
