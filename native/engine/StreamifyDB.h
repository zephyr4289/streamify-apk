#ifndef STREAMIFY_DB_H
#define STREAMIFY_DB_H

#include <string>
#include <vector>
#include <optional>
#include "../third_party/sqlite3/sqlite3.h"

struct StreamifyTrack {
    int id;
    std::string filepath;
    std::string title;
    std::string artist;
    std::string album;
    int duration_sec;
    double bpm;
    std::string key;
    int vector_offset;
    std::string cover_art_path;
    std::string lyrics_path;
    std::string source;
    int is_processed;
    std::string download_quality;
};

struct StreamifyUser {
    int id;
    std::string username;
    std::string pin_hash;
};

class StreamifyDB {
public:
    static StreamifyDB& getInstance();

    bool init(const std::string& db_path);
    std::optional<StreamifyTrack> getTrackById(int track_id);
    std::optional<StreamifyTrack> getTrackByVectorOffset(int offset);
    std::vector<StreamifyTrack> getAllTracks();
    std::vector<StreamifyTrack> searchTracks(const std::string& query);
    int insertTrack(const std::string& filepath, const std::string& title, const std::string& artist, const std::string& album, int duration_sec, double bpm);

    // Multi-User Profile & Session Methods
    std::optional<StreamifyUser> registerOrLoginUser(const std::string& username, const std::string& pin);
    std::string createSession(int user_id);
    std::optional<StreamifyUser> validateSession(const std::string& token);
    bool deleteSession(const std::string& token);

    // Per-User Liked Songs Methods
    std::vector<int> getUserLikedTrackIds(int user_id);
    std::vector<StreamifyTrack> getUserLikedTracks(int user_id);
    bool toggleUserLikedTrack(int user_id, int track_id, bool& out_is_liked);

    // Track metadata & vector updates
    bool updateTrackVectorOffset(int track_id, int offset);
    bool updateTrackCoverArt(int track_id, const std::string& cover_art_path);
    bool updateTrackMetadata(int track_id, const std::string& title, const std::string& artist, const std::string& album);

    // Behavioral Transition Methods
    bool insertTransition(int user_id, int from_track_id, int to_track_id, const std::string& type);
    float getTransitionProbability(int user_id, int from_track_id, int to_track_id);
    int getSkipCount(int user_id, int from_track_id, int to_track_id);
    int getTrackTotalSkipCount(int user_id, int track_id);

private:
    StreamifyDB() = default;
    ~StreamifyDB() = default;
    sqlite3* getConnection();

    std::string db_path_{"streamify.db"};
};

#endif // STREAMIFY_DB_H
