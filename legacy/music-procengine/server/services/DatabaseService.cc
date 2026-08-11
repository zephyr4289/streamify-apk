#include "DatabaseService.h"
#include <iostream>

DatabaseService& DatabaseService::getInstance() {
    static DatabaseService instance;
    return instance;
}

DatabaseService::~DatabaseService() {
    close();
}

bool DatabaseService::init(const std::string& db_path) {
    db_path_ = db_path;
    return true; // Actual connection happens in getConnection per thread
}

sqlite3* DatabaseService::getConnection() {
    thread_local sqlite3* tls_db = nullptr;
    if (tls_db == nullptr) {
        if (sqlite3_open(db_path_.c_str(), &tls_db) != SQLITE_OK) {
            std::cerr << "[DatabaseService] Cannot open database in thread: " << sqlite3_errmsg(tls_db) << std::endl;
            if (tls_db) {
                sqlite3_close(tls_db);
                tls_db = nullptr;
            }
            return nullptr;
        }
        // Enable foreign keys, WAL mode, and busy timeout for high concurrency performance
        char* err_msg = nullptr;
        sqlite3_exec(tls_db, "PRAGMA foreign_keys = ON; PRAGMA journal_mode = WAL; PRAGMA busy_timeout = 5000;", nullptr, nullptr, &err_msg);
        if (err_msg) {
            sqlite3_free(err_msg);
        }
    }
    return tls_db;
}

void DatabaseService::close() {
    // No-op for thread_local connections since they will be cleaned up on thread exit or OS level
}

std::optional<Track> DatabaseService::getTrackById(int track_id) {
    sqlite3* db = getConnection();
    if (!db) return std::nullopt;

    const char* sql = "SELECT id, filepath, title, artist, bpm, key, vector_offset, created_at FROM tracks WHERE id = ?;";
    sqlite3_stmt* stmt = nullptr;

    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) != SQLITE_OK) {
        return std::nullopt;
    }

    sqlite3_bind_int(stmt, 1, track_id);

    std::optional<Track> track;
    if (sqlite3_step(stmt) == SQLITE_ROW) {
        Track t;
        t.id = sqlite3_column_int(stmt, 0);
        t.filepath = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 1));
        t.title = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 2));
        t.artist = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 3));
        t.bpm = sqlite3_column_double(stmt, 4);
        t.key = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 5));
        t.vector_offset = sqlite3_column_int(stmt, 6);
        t.created_at = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 7));
        track = t;
    }

    sqlite3_finalize(stmt);
    return track;
}

std::optional<Track> DatabaseService::getTrackByVectorOffset(int vector_offset) {
    sqlite3* db = getConnection();
    if (!db) return std::nullopt;

    const char* sql = "SELECT id, filepath, title, artist, bpm, key, vector_offset, created_at FROM tracks WHERE vector_offset = ?;";
    sqlite3_stmt* stmt = nullptr;

    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) != SQLITE_OK) {
        return std::nullopt;
    }

    sqlite3_bind_int(stmt, 1, vector_offset);

    std::optional<Track> track;
    if (sqlite3_step(stmt) == SQLITE_ROW) {
        Track t;
        t.id = sqlite3_column_int(stmt, 0);
        t.filepath = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 1));
        t.title = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 2));
        t.artist = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 3));
        t.bpm = sqlite3_column_double(stmt, 4);
        t.key = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 5));
        t.vector_offset = sqlite3_column_int(stmt, 6);
        t.created_at = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 7));
        track = t;
    }

    sqlite3_finalize(stmt);
    return track;
}

std::vector<Track> DatabaseService::getAllTracks() {
    std::vector<Track> tracks;
    sqlite3* db = getConnection();
    if (!db) return tracks;

    const char* sql = "SELECT id, filepath, title, artist, bpm, key, vector_offset, created_at FROM tracks ORDER BY id ASC;";
    sqlite3_stmt* stmt = nullptr;

    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) != SQLITE_OK) {
        return tracks;
    }

    while (sqlite3_step(stmt) == SQLITE_ROW) {
        Track t;
        t.id = sqlite3_column_int(stmt, 0);
        t.filepath = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 1));
        t.title = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 2));
        t.artist = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 3));
        t.bpm = sqlite3_column_double(stmt, 4);
        t.key = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 5));
        t.vector_offset = sqlite3_column_int(stmt, 6);
        t.created_at = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 7));
        tracks.push_back(t);
    }

    sqlite3_finalize(stmt);
    return tracks;
}

bool DatabaseService::trackExists(const std::string& filepath) {
    sqlite3* db = getConnection();
    if (!db) return false;
    const char* sql = "SELECT id FROM tracks WHERE filepath = ? LIMIT 1;";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) != SQLITE_OK) return false;
    sqlite3_bind_text(stmt, 1, filepath.c_str(), -1, SQLITE_TRANSIENT);
    bool exists = (sqlite3_step(stmt) == SQLITE_ROW);
    sqlite3_finalize(stmt);
    return exists;
}

int DatabaseService::insertTrack(const std::string& filepath, const std::string& title, const std::string& artist, double bpm, const std::string& key, int vector_offset) {
    sqlite3* db = getConnection();
    if (!db) return -1;
    const char* sql = "INSERT INTO tracks (filepath, title, artist, bpm, key, vector_offset) VALUES (?, ?, ?, ?, ?, ?);";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) != SQLITE_OK) return -1;
    sqlite3_bind_text(stmt, 1, filepath.c_str(), -1, SQLITE_TRANSIENT);
    sqlite3_bind_text(stmt, 2, title.c_str(), -1, SQLITE_TRANSIENT);
    sqlite3_bind_text(stmt, 3, artist.c_str(), -1, SQLITE_TRANSIENT);
    sqlite3_bind_double(stmt, 4, bpm);
    sqlite3_bind_text(stmt, 5, key.c_str(), -1, SQLITE_TRANSIENT);
    sqlite3_bind_int(stmt, 6, vector_offset);
    if (sqlite3_step(stmt) != SQLITE_DONE) {
        sqlite3_finalize(stmt);
        return -1;
    }
    int id = sqlite3_last_insert_rowid(db);
    sqlite3_finalize(stmt);
    return id;
}

bool DatabaseService::recordPlayEvent(int current_track_id, int previous_track_id) {
    sqlite3* db = getConnection();
    if (!db) return false;

    const char* sql = R"(
        INSERT INTO transitions (from_track_id, to_track_id, count)
        VALUES (?, ?, 1)
        ON CONFLICT(from_track_id, to_track_id) DO UPDATE SET count = count + 1;
    )";

    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) != SQLITE_OK) {
        return false;
    }

    sqlite3_bind_int(stmt, 1, previous_track_id);
    sqlite3_bind_int(stmt, 2, current_track_id);

    int rc = sqlite3_step(stmt);
    sqlite3_finalize(stmt);

    return (rc == SQLITE_DONE);
}

std::vector<Transition> DatabaseService::getTransitionsFrom(int current_track_id) {
    std::vector<Transition> transitions;
    sqlite3* db = getConnection();
    if (!db) return transitions;

    const char* sql = "SELECT from_track_id, to_track_id, count FROM transitions WHERE from_track_id = ?;";
    sqlite3_stmt* stmt = nullptr;

    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) != SQLITE_OK) {
        return transitions;
    }

    sqlite3_bind_int(stmt, 1, current_track_id);

    while (sqlite3_step(stmt) == SQLITE_ROW) {
        Transition t;
        t.from_track_id = sqlite3_column_int(stmt, 0);
        t.to_track_id = sqlite3_column_int(stmt, 1);
        t.count = sqlite3_column_int(stmt, 2);
        transitions.push_back(t);
    }

    sqlite3_finalize(stmt);
    return transitions;
}

std::map<int, int> DatabaseService::getTransitionCountsFrom(int current_track_id) {
    std::map<int, int> counts;
    auto transitions = getTransitionsFrom(current_track_id);
    for (const auto& tr : transitions) {
        counts[tr.to_track_id] = tr.count;
    }
    return counts;
}

bool DatabaseService::recordSkipEvent(int current_track_id, int previous_track_id) {
    sqlite3* db = getConnection();
    if (!db) return false;

    const char* sql = R"(
        INSERT INTO skips (from_track_id, to_track_id, count)
        VALUES (?, ?, 1)
        ON CONFLICT(from_track_id, to_track_id) DO UPDATE SET count = count + 1;
    )";

    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) != SQLITE_OK) {
        return false;
    }

    sqlite3_bind_int(stmt, 1, previous_track_id);
    sqlite3_bind_int(stmt, 2, current_track_id);

    int rc = sqlite3_step(stmt);
    sqlite3_finalize(stmt);

    return (rc == SQLITE_DONE);
}

std::map<int, int> DatabaseService::getSkipCountsFrom(int current_track_id) {
    std::map<int, int> counts;
    sqlite3* db = getConnection();
    if (!db) return counts;

    const char* sql = "SELECT to_track_id, count FROM skips WHERE from_track_id = ?;";
    sqlite3_stmt* stmt = nullptr;

    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) != SQLITE_OK) {
        return counts;
    }

    sqlite3_bind_int(stmt, 1, current_track_id);

    while (sqlite3_step(stmt) == SQLITE_ROW) {
        counts[sqlite3_column_int(stmt, 0)] = sqlite3_column_int(stmt, 1);
    }

    sqlite3_finalize(stmt);
    return counts;
}
