#ifndef AUTH_CONTROLLER_H
#define AUTH_CONTROLLER_H

#include <cstring>
#include <cstdlib>
#include <drogon/HttpController.h>

class AuthController : public drogon::HttpController<AuthController, false> {
public:
    METHOD_LIST_BEGIN
    ADD_METHOD_TO(AuthController::loginOrRegister, "/api/v1/auth/login", drogon::Post);
    ADD_METHOD_TO(AuthController::getCurrentUser, "/api/v1/auth/me", drogon::Get);
    ADD_METHOD_TO(AuthController::logout, "/api/v1/auth/logout", drogon::Post);
    ADD_METHOD_TO(AuthController::getLikedTracks, "/api/v1/user/liked", drogon::Get);
    ADD_METHOD_TO(AuthController::toggleLikedTrack, "/api/v1/user/liked/toggle", drogon::Post);
    METHOD_LIST_END

    void loginOrRegister(const drogon::HttpRequestPtr& req,
                         std::function<void(const drogon::HttpResponsePtr&)>&& callback);

    void getCurrentUser(const drogon::HttpRequestPtr& req,
                        std::function<void(const drogon::HttpResponsePtr&)>&& callback);

    void logout(const drogon::HttpRequestPtr& req,
                std::function<void(const drogon::HttpResponsePtr&)>&& callback);

    void getLikedTracks(const drogon::HttpRequestPtr& req,
                        std::function<void(const drogon::HttpResponsePtr&)>&& callback);

    void toggleLikedTrack(const drogon::HttpRequestPtr& req,
                          std::function<void(const drogon::HttpResponsePtr&)>&& callback);
};

#endif // AUTH_CONTROLLER_H
