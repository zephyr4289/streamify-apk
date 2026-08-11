#ifndef RECOMMEND_CONTROLLER_H
#define RECOMMEND_CONTROLLER_H

#include <drogon/HttpController.h>

class RecommendController : public drogon::HttpController<RecommendController> {
public:
    METHOD_LIST_BEGIN
    ADD_METHOD_TO(RecommendController::getRecommendations, "/api/v1/recommend/next", drogon::Get);
    METHOD_LIST_END

    void getRecommendations(const drogon::HttpRequestPtr& req,
                            std::function<void(const drogon::HttpResponsePtr&)>&& callback);
};

#endif // RECOMMEND_CONTROLLER_H
