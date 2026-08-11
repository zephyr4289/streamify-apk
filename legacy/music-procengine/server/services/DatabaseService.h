#ifndef DATABASE_SERVICE_H
#define DATABASE_SERVICE_H

#include <string>
#include <vector>
#include <map>
#include <optional>
#include <mutex>
#include <sqlite3.h>

struct Track {
    int id;
    std::string filepath;
    std::string title;
    std::string artist;
    double bpm;
    std::string key;
    int vector_offset;
    std::string created_at;
};

struct Transition {
    int from_track_id;
    int to_track_id;
    int count;
};

class DatabaseService {
public:
    static DatabaseService& getInstance();
    
    bool init(const std::string& db_path);
    void close();

    std::optional<Track> getTrackById(int track_id);
    std::optional<Track> getTrackByVectorOffset(int vector_offset);
    std::vector<Track> getAllTracks();
    bool trackExists(const std::string& filepath);
    int insertTrack(const std::string& filepath, const std::string& title, const std::string& artist, double bpm, const std::string& key, int vector_offset);
    
    bool recordPlayEvent(int current_track_id, int previous_track_id);
    std::vector<Transition> getTransitionsFrom(int current_track_id);
    std::map<int, int> getTransitionCountsFrom(int current_track_id);

    bool recordSkipEvent(int current_track_id, int previous_track_id);
    std::map<int, int> getSkipCountsFrom(int current_track_id);

private:
    DatabaseService() = default;
    ~DatabaseService();
    DatabaseService(const DatabaseService&) = delete;
    DatabaseService& operator=(const DatabaseService&) = delete;

    sqlite3* getConnection();

    std::string db_path_;
};

#endif // DATABASE_SERVICE_H
