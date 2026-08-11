#ifndef CATALOG_CONTROLLER_H
#define CATALOG_CONTROLLER_H

#include <cstring>
#include <cstdlib>
#include <drogon/HttpController.h>

class CatalogController : public drogon::HttpController<CatalogController> {
public:
    METHOD_LIST_BEGIN
    ADD_METHOD_TO(CatalogController::getTracks, "/api/v1/tracks", drogon::Get);
    ADD_METHOD_TO(CatalogController::searchTracks, "/api/v1/tracks/search", drogon::Get);
    METHOD_LIST_END

    void getTracks(const drogon::HttpRequestPtr& req,
                   std::function<void(const drogon::HttpResponsePtr&)>&& callback);

    void searchTracks(const drogon::HttpRequestPtr& req,
                      std::function<void(const drogon::HttpResponsePtr&)>&& callback);
};

#endif // CATALOG_CONTROLLER_H
