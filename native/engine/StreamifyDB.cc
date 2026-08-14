#include "StreamifyDB.h"
#include <android/log.h>
#include <random>
#include <sstream>
#include <iomanip>
#include <functional>

static std::string hashPin(const std::string& username, const std::string& pin) {
    std::hash<std::string> hasher;
    size_t val = hasher("streamify_salt_" + username + "_" + pin);
    std::stringstream ss;
    ss << std::hex << val;
    return ss.str();
}

static std::string generateRandomToken() {
    static std::random_device rd;
    static std::mt19937_64 gen(rd());
    static std::uniform_int_distribution<uint64_t> dis;
    std::stringstream ss;
    ss << std::hex << std::setfill('0') << std::setw(16) << dis(gen) << std::setw(16) << dis(gen);
    return ss.str();
}

StreamifyDB& StreamifyDB::getInstance() {
    static StreamifyDB instance;
    return instance;
}

StreamifyDB::~StreamifyDB() {
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    if (shared_db_) {
        sqlite3_close_v2(shared_db_);
        shared_db_ = nullptr;
    }
}

bool StreamifyDB::init(const std::string& db_path) {
    db_path_ = db_path;
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    sqlite3* db = getConnection();
    if (!db) return false;

    const char* schema_init = R"(
        CREATE TABLE IF NOT EXISTS tracks (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            filepath TEXT UNIQUE NOT NULL,
            title TEXT,
            artist TEXT,
            album TEXT DEFAULT 'Single',
            duration_sec INTEGER DEFAULT 180,
            bpm REAL DEFAULT 120.0,
            key TEXT DEFAULT 'C',
            vector_offset INTEGER DEFAULT -1,
            cover_art_path TEXT,
            lyrics_path TEXT,
            source TEXT DEFAULT 'local',
            is_processed INTEGER DEFAULT 0,
            download_quality TEXT,
            created_at DATETIME DEFAULT CURRENT_TIMESTAMP
        );
        CREATE TABLE IF NOT EXISTS users (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            username TEXT UNIQUE NOT NULL,
            pin_hash TEXT NOT NULL,
            created_at DATETIME DEFAULT CURRENT_TIMESTAMP
        );
        CREATE TABLE IF NOT EXISTS user_sessions (
            token TEXT PRIMARY KEY,
            user_id INTEGER NOT NULL,
            created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
            FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
        );
        CREATE TABLE IF NOT EXISTS user_liked_songs (
            user_id INTEGER NOT NULL,
            track_id INTEGER NOT NULL,
            created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (user_id, track_id),
            FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
            FOREIGN KEY (track_id) REFERENCES tracks(id) ON DELETE CASCADE
        );
        CREATE TABLE IF NOT EXISTS user_transitions (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id INTEGER NOT NULL,
            from_track_id INTEGER NOT NULL,
            to_track_id INTEGER NOT NULL,
            event_type TEXT NOT NULL, -- 'play' or 'skip'
            created_at DATETIME DEFAULT CURRENT_TIMESTAMP
        );
        CREATE INDEX IF NOT EXISTS idx_tracks_filepath ON tracks(filepath);
        CREATE INDEX IF NOT EXISTS idx_tracks_title ON tracks(title);
        CREATE INDEX IF NOT EXISTS idx_tracks_artist ON tracks(artist);
        CREATE INDEX IF NOT EXISTS idx_tracks_vector_offset ON tracks(vector_offset);
        CREATE INDEX IF NOT EXISTS idx_transitions_user ON user_transitions(user_id, from_track_id, event_type);
    )";

    char* err = nullptr;
    if (sqlite3_exec(db, schema_init, nullptr, nullptr, &err) != SQLITE_OK) {
        if (err) {
            __android_log_print(ANDROID_LOG_ERROR, "StreamifyNative", "[StreamifyDB] Migration error: %s", err);
            sqlite3_free(err);
        }
    }

    // Ensure default user ID=1 exists
    const char* bootstrap_user = "INSERT OR IGNORE INTO users (id, username, pin_hash) VALUES (1, 'default_user', 'default_hash');";
    sqlite3_exec(db, bootstrap_user, nullptr, nullptr, nullptr);

    return true;
}

sqlite3* StreamifyDB::getConnection() {
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    if (shared_db_ == nullptr) {
        if (sqlite3_open_v2(db_path_.c_str(), &shared_db_, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX, nullptr) != SQLITE_OK) {
            __android_log_print(ANDROID_LOG_ERROR, "StreamifyNative", "[StreamifyDB] Cannot open database");
            return nullptr;
        }
        char* err = nullptr;
        sqlite3_exec(shared_db_, "PRAGMA foreign_keys = ON; PRAGMA journal_mode = WAL; PRAGMA synchronous = NORMAL; PRAGMA busy_timeout = 5000; PRAGMA cache_size = -8000; PRAGMA temp_store = MEMORY;", nullptr, nullptr, &err);
        if (err) sqlite3_free(err);
    }
    return shared_db_;
}

std::optional<StreamifyTrack> StreamifyDB::getTrackById(int track_id) {
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    sqlite3* db = getConnection();
    if (!db) return std::nullopt;

    const char* sql = "SELECT id, filepath, title, artist, album, duration_sec, bpm, key, vector_offset, cover_art_path, lyrics_path, source, is_processed, download_quality FROM tracks WHERE id = ?;";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) != SQLITE_OK) return std::nullopt;

    sqlite3_bind_int(stmt, 1, track_id);

    std::optional<StreamifyTrack> track;
    if (sqlite3_step(stmt) == SQLITE_ROW) {
        StreamifyTrack t;
        t.id = sqlite3_column_int(stmt, 0);
        t.filepath = sqlite3_column_text(stmt, 1) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 1)) : "";
        t.title = sqlite3_column_text(stmt, 2) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 2)) : "";
        t.artist = sqlite3_column_text(stmt, 3) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 3)) : "";
        t.album = sqlite3_column_text(stmt, 4) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 4)) : "Single";
        t.duration_sec = sqlite3_column_int(stmt, 5);
        t.bpm = sqlite3_column_double(stmt, 6);
        t.key = sqlite3_column_text(stmt, 7) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 7)) : "C";
        t.vector_offset = sqlite3_column_int(stmt, 8);
        t.cover_art_path = sqlite3_column_text(stmt, 9) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 9)) : "";
        t.lyrics_path = sqlite3_column_text(stmt, 10) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 10)) : "";
        t.source = sqlite3_column_text(stmt, 11) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 11)) : "";
        t.is_processed = sqlite3_column_int(stmt, 12);
        t.download_quality = sqlite3_column_text(stmt, 13) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 13)) : "";
        track = t;
    }
    sqlite3_finalize(stmt);
    return track;
}

std::optional<StreamifyTrack> StreamifyDB::getTrackByVectorOffset(int offset) {
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    sqlite3* db = getConnection();
    if (!db) return std::nullopt;

    const char* sql = "SELECT id, filepath, title, artist, album, duration_sec, bpm, key, vector_offset, cover_art_path, lyrics_path, source, is_processed, download_quality FROM tracks WHERE vector_offset = ?;";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) != SQLITE_OK) return std::nullopt;

    sqlite3_bind_int(stmt, 1, offset);

    std::optional<StreamifyTrack> track;
    if (sqlite3_step(stmt) == SQLITE_ROW) {
        StreamifyTrack t;
        t.id = sqlite3_column_int(stmt, 0);
        t.filepath = sqlite3_column_text(stmt, 1) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 1)) : "";
        t.title = sqlite3_column_text(stmt, 2) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 2)) : "";
        t.artist = sqlite3_column_text(stmt, 3) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 3)) : "";
        t.album = sqlite3_column_text(stmt, 4) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 4)) : "Single";
        t.duration_sec = sqlite3_column_int(stmt, 5);
        t.bpm = sqlite3_column_double(stmt, 6);
        t.key = sqlite3_column_text(stmt, 7) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 7)) : "C";
        t.vector_offset = sqlite3_column_int(stmt, 8);
        t.cover_art_path = sqlite3_column_text(stmt, 9) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 9)) : "";
        t.lyrics_path = sqlite3_column_text(stmt, 10) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 10)) : "";
        t.source = sqlite3_column_text(stmt, 11) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 11)) : "";
        t.is_processed = sqlite3_column_int(stmt, 12);
        t.download_quality = sqlite3_column_text(stmt, 13) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 13)) : "";
        track = t;
    }
    sqlite3_finalize(stmt);
    return track;
}

std::vector<StreamifyTrack> StreamifyDB::getAllTracks() {
    std::vector<StreamifyTrack> tracks;
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    sqlite3* db = getConnection();
    if (!db) return tracks;

    const char* sql = "SELECT id, filepath, title, artist, album, duration_sec, bpm, key, vector_offset, cover_art_path, lyrics_path, source, is_processed, download_quality FROM tracks ORDER BY id ASC;";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) != SQLITE_OK) return tracks;

    while (sqlite3_step(stmt) == SQLITE_ROW) {
        StreamifyTrack t;
        t.id = sqlite3_column_int(stmt, 0);
        t.filepath = sqlite3_column_text(stmt, 1) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 1)) : "";
        t.title = sqlite3_column_text(stmt, 2) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 2)) : "";
        t.artist = sqlite3_column_text(stmt, 3) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 3)) : "";
        t.album = sqlite3_column_text(stmt, 4) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 4)) : "Single";
        t.duration_sec = sqlite3_column_int(stmt, 5);
        t.bpm = sqlite3_column_double(stmt, 6);
        t.key = sqlite3_column_text(stmt, 7) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 7)) : "C";
        t.vector_offset = sqlite3_column_int(stmt, 8);
        t.cover_art_path = sqlite3_column_text(stmt, 9) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 9)) : "";
        t.lyrics_path = sqlite3_column_text(stmt, 10) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 10)) : "";
        t.source = sqlite3_column_text(stmt, 11) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 11)) : "";
        t.is_processed = sqlite3_column_int(stmt, 12);
        t.download_quality = sqlite3_column_text(stmt, 13) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 13)) : "";
        tracks.push_back(t);
    }
    sqlite3_finalize(stmt);
    return tracks;
}

std::vector<StreamifyTrack> StreamifyDB::searchTracks(const std::string& query) {
    std::vector<StreamifyTrack> tracks;
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    sqlite3* db = getConnection();
    if (!db) return tracks;

    const char* sql = "SELECT id, filepath, title, artist, album, duration_sec, bpm, key, vector_offset, cover_art_path, lyrics_path, source, is_processed, download_quality FROM tracks WHERE title LIKE ? OR artist LIKE ?;";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) != SQLITE_OK) return tracks;

    std::string pattern = "%" + query + "%";
    sqlite3_bind_text(stmt, 1, pattern.c_str(), -1, SQLITE_TRANSIENT);
    sqlite3_bind_text(stmt, 2, pattern.c_str(), -1, SQLITE_TRANSIENT);

    while (sqlite3_step(stmt) == SQLITE_ROW) {
        StreamifyTrack t;
        t.id = sqlite3_column_int(stmt, 0);
        t.filepath = sqlite3_column_text(stmt, 1) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 1)) : "";
        t.title = sqlite3_column_text(stmt, 2) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 2)) : "";
        t.artist = sqlite3_column_text(stmt, 3) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 3)) : "";
        t.album = sqlite3_column_text(stmt, 4) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 4)) : "Single";
        t.duration_sec = sqlite3_column_int(stmt, 5);
        t.bpm = sqlite3_column_double(stmt, 6);
        t.key = sqlite3_column_text(stmt, 7) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 7)) : "C";
        t.vector_offset = sqlite3_column_int(stmt, 8);
        t.cover_art_path = sqlite3_column_text(stmt, 9) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 9)) : "";
        t.lyrics_path = sqlite3_column_text(stmt, 10) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 10)) : "";
        t.source = sqlite3_column_text(stmt, 11) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 11)) : "";
        t.is_processed = sqlite3_column_int(stmt, 12);
        t.download_quality = sqlite3_column_text(stmt, 13) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 13)) : "";
        tracks.push_back(t);
    }
    sqlite3_finalize(stmt);
    return tracks;
}

int StreamifyDB::insertTrack(const std::string& filepath, const std::string& title, const std::string& artist, const std::string& album, int duration_sec, double bpm) {
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    sqlite3* db = getConnection();
    if (!db) return -1;

    std::string source = "local";
    std::string path_lower = filepath;
    std::transform(path_lower.begin(), path_lower.end(), path_lower.begin(), ::tolower);
    std::string album_lower = album;
    std::transform(album_lower.begin(), album_lower.end(), album_lower.begin(), ::tolower);

    if (filepath.rfind("http://", 0) == 0 || filepath.rfind("https://", 0) == 0 || filepath.rfind("online://", 0) == 0) {
        source = "online";
    } else if (album_lower == "streamify" || path_lower.find("streamify") != std::string::npos) {
        source = "streamify_download";
    }

    const char* sql = "INSERT INTO tracks (filepath, title, artist, album, duration_sec, bpm, source) VALUES (?, ?, ?, ?, ?, ?, ?) "
                      "ON CONFLICT(filepath) DO UPDATE SET title=excluded.title, artist=excluded.artist, album=excluded.album, duration_sec=excluded.duration_sec, bpm=excluded.bpm, source=excluded.source;";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) != SQLITE_OK) return -1;

    sqlite3_bind_text(stmt, 1, filepath.c_str(), -1, SQLITE_TRANSIENT);
    sqlite3_bind_text(stmt, 2, title.c_str(), -1, SQLITE_TRANSIENT);
    sqlite3_bind_text(stmt, 3, artist.c_str(), -1, SQLITE_TRANSIENT);
    sqlite3_bind_text(stmt, 4, album.c_str(), -1, SQLITE_TRANSIENT);
    sqlite3_bind_int(stmt, 5, duration_sec);
    sqlite3_bind_double(stmt, 6, bpm);
    sqlite3_bind_text(stmt, 7, source.c_str(), -1, SQLITE_TRANSIENT);

    sqlite3_step(stmt);
    sqlite3_finalize(stmt);

    int id = -1;
    const char* sel_sql = "SELECT id FROM tracks WHERE filepath = ?;";
    sqlite3_stmt* sel_stmt = nullptr;
    if (sqlite3_prepare_v2(db, sel_sql, -1, &sel_stmt, nullptr) == SQLITE_OK) {
        sqlite3_bind_text(sel_stmt, 1, filepath.c_str(), -1, SQLITE_TRANSIENT);
        if (sqlite3_step(sel_stmt) == SQLITE_ROW) {
            id = sqlite3_column_int(sel_stmt, 0);
        }
        sqlite3_finalize(sel_stmt);
    }
    return id;
}

std::optional<StreamifyUser> StreamifyDB::registerOrLoginUser(const std::string& username, const std::string& pin) {
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    sqlite3* db = getConnection();
    if (!db || username.empty() || pin.empty()) return std::nullopt;

    std::string expected_hash = hashPin(username, pin);
    const char* select_sql = "SELECT id, username, pin_hash FROM users WHERE username = ?;";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, select_sql, -1, &stmt, nullptr) == SQLITE_OK) {
        sqlite3_bind_text(stmt, 1, username.c_str(), -1, SQLITE_TRANSIENT);
        if (sqlite3_step(stmt) == SQLITE_ROW) {
            std::string stored_hash = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 2));
            if (stored_hash == expected_hash) {
                StreamifyUser user;
                user.id = sqlite3_column_int(stmt, 0);
                user.username = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 1));
                user.pin_hash = stored_hash;
                sqlite3_finalize(stmt);
                return user;
            } else {
                sqlite3_finalize(stmt);
                return std::nullopt;
            }
        }
        sqlite3_finalize(stmt);
    }

    const char* insert_sql = "INSERT INTO users (username, pin_hash) VALUES (?, ?);";
    if (sqlite3_prepare_v2(db, insert_sql, -1, &stmt, nullptr) == SQLITE_OK) {
        sqlite3_bind_text(stmt, 1, username.c_str(), -1, SQLITE_TRANSIENT);
        sqlite3_bind_text(stmt, 2, expected_hash.c_str(), -1, SQLITE_TRANSIENT);
        if (sqlite3_step(stmt) == SQLITE_DONE) {
            int new_id = sqlite3_last_insert_rowid(db);
            sqlite3_finalize(stmt);
            return StreamifyUser{new_id, username, expected_hash};
        }
        sqlite3_finalize(stmt);
    }
    return std::nullopt;
}

std::string StreamifyDB::createSession(int user_id) {
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    sqlite3* db = getConnection();
    if (!db) return "";
    std::string token = generateRandomToken();
    const char* sql = "INSERT INTO user_sessions (token, user_id) VALUES (?, ?);";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) == SQLITE_OK) {
        sqlite3_bind_text(stmt, 1, token.c_str(), -1, SQLITE_TRANSIENT);
        sqlite3_bind_int(stmt, 2, user_id);
        if (sqlite3_step(stmt) == SQLITE_DONE) {
            sqlite3_finalize(stmt);
            return token;
        }
        sqlite3_finalize(stmt);
    }
    return "";
}

std::optional<StreamifyUser> StreamifyDB::validateSession(const std::string& token) {
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    sqlite3* db = getConnection();
    if (!db || token.empty()) return std::nullopt;
    const char* sql = R"(
        SELECT u.id, u.username, u.pin_hash 
        FROM user_sessions s 
        JOIN users u ON s.user_id = u.id 
        WHERE s.token = ?;
    )";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) == SQLITE_OK) {
        sqlite3_bind_text(stmt, 1, token.c_str(), -1, SQLITE_TRANSIENT);
        if (sqlite3_step(stmt) == SQLITE_ROW) {
            StreamifyUser u;
            u.id = sqlite3_column_int(stmt, 0);
            u.username = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 1));
            u.pin_hash = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 2));
            sqlite3_finalize(stmt);
            return u;
        }
        sqlite3_finalize(stmt);
    }
    return std::nullopt;
}

bool StreamifyDB::deleteSession(const std::string& token) {
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    sqlite3* db = getConnection();
    if (!db) return false;
    const char* sql = "DELETE FROM user_sessions WHERE token = ?;";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) == SQLITE_OK) {
        sqlite3_bind_text(stmt, 1, token.c_str(), -1, SQLITE_TRANSIENT);
        bool success = (sqlite3_step(stmt) == SQLITE_DONE);
        sqlite3_finalize(stmt);
        return success;
    }
    return false;
}

std::vector<int> StreamifyDB::getUserLikedTrackIds(int user_id) {
    std::vector<int> ids;
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    sqlite3* db = getConnection();
    if (!db) return ids;
    const char* sql = "SELECT track_id FROM user_liked_songs WHERE user_id = ?;";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) == SQLITE_OK) {
        sqlite3_bind_int(stmt, 1, user_id);
        while (sqlite3_step(stmt) == SQLITE_ROW) {
            ids.push_back(sqlite3_column_int(stmt, 0));
        }
        sqlite3_finalize(stmt);
    }
    return ids;
}

std::vector<StreamifyTrack> StreamifyDB::getUserLikedTracks(int user_id) {
    std::vector<StreamifyTrack> tracks;
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    sqlite3* db = getConnection();
    if (!db) return tracks;
    const char* sql = R"(
        SELECT t.id, t.filepath, t.title, t.artist, t.album, t.duration_sec, t.bpm, t.key, t.vector_offset, t.cover_art_path, t.lyrics_path, t.source, t.is_processed, t.download_quality
        FROM user_liked_songs l 
        JOIN tracks t ON l.track_id = t.id 
        WHERE l.user_id = ? 
        ORDER BY l.created_at DESC;
    )";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) == SQLITE_OK) {
        sqlite3_bind_int(stmt, 1, user_id);
        while (sqlite3_step(stmt) == SQLITE_ROW) {
            StreamifyTrack t;
            t.id = sqlite3_column_int(stmt, 0);
            t.filepath = sqlite3_column_text(stmt, 1) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 1)) : "";
            t.title = sqlite3_column_text(stmt, 2) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 2)) : "";
            t.artist = sqlite3_column_text(stmt, 3) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 3)) : "";
            t.album = sqlite3_column_text(stmt, 4) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 4)) : "Single";
            t.duration_sec = sqlite3_column_int(stmt, 5);
            t.bpm = sqlite3_column_double(stmt, 6);
            t.key = sqlite3_column_text(stmt, 7) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 7)) : "C";
            t.vector_offset = sqlite3_column_int(stmt, 8);
            t.cover_art_path = sqlite3_column_text(stmt, 9) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 9)) : "";
            t.lyrics_path = sqlite3_column_text(stmt, 10) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 10)) : "";
            t.source = sqlite3_column_text(stmt, 11) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 11)) : "";
            t.is_processed = sqlite3_column_int(stmt, 12);
            t.download_quality = sqlite3_column_text(stmt, 13) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 13)) : "";
            tracks.push_back(t);
        }
        sqlite3_finalize(stmt);
    }
    return tracks;
}

bool StreamifyDB::toggleUserLikedTrack(int user_id, int track_id, bool& out_is_liked) {
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    sqlite3* db = getConnection();
    if (!db) return false;
    const char* check_sql = "SELECT 1 FROM user_liked_songs WHERE user_id = ? AND track_id = ?;";
    sqlite3_stmt* stmt = nullptr;
    bool exists = false;
    if (sqlite3_prepare_v2(db, check_sql, -1, &stmt, nullptr) == SQLITE_OK) {
        sqlite3_bind_int(stmt, 1, user_id);
        sqlite3_bind_int(stmt, 2, track_id);
        if (sqlite3_step(stmt) == SQLITE_ROW) {
            exists = true;
        }
        sqlite3_finalize(stmt);
    }

    if (exists) {
        const char* del_sql = "DELETE FROM user_liked_songs WHERE user_id = ? AND track_id = ?;";
        if (sqlite3_prepare_v2(db, del_sql, -1, &stmt, nullptr) == SQLITE_OK) {
            sqlite3_bind_int(stmt, 1, user_id);
            sqlite3_bind_int(stmt, 2, track_id);
            sqlite3_step(stmt);
            sqlite3_finalize(stmt);
        }
        out_is_liked = false;
    } else {
        const char* add_sql = "INSERT INTO user_liked_songs (user_id, track_id) VALUES (?, ?);";
        if (sqlite3_prepare_v2(db, add_sql, -1, &stmt, nullptr) == SQLITE_OK) {
            sqlite3_bind_int(stmt, 1, user_id);
            sqlite3_bind_int(stmt, 2, track_id);
            sqlite3_step(stmt);
            sqlite3_finalize(stmt);
        }
        out_is_liked = true;
    }
    return true;
}

bool StreamifyDB::updateTrackVectorOffset(int track_id, int offset) {
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    sqlite3* db = getConnection();
    if (!db) return false;
    const char* sql = "UPDATE tracks SET vector_offset = ?, is_processed = 1 WHERE id = ?;";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) == SQLITE_OK) {
        sqlite3_bind_int(stmt, 1, offset);
        sqlite3_bind_int(stmt, 2, track_id);
        bool res = (sqlite3_step(stmt) == SQLITE_DONE);
        sqlite3_finalize(stmt);
        return res;
    }
    return false;
}

bool StreamifyDB::updateTrackCoverArt(int track_id, const std::string& cover_art_path) {
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    sqlite3* db = getConnection();
    if (!db) return false;
    const char* sql = "UPDATE tracks SET cover_art_path = ? WHERE id = ?;";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) == SQLITE_OK) {
        sqlite3_bind_text(stmt, 1, cover_art_path.c_str(), -1, SQLITE_TRANSIENT);
        sqlite3_bind_int(stmt, 2, track_id);
        bool res = (sqlite3_step(stmt) == SQLITE_DONE);
        sqlite3_finalize(stmt);
        return res;
    }
    return false;
}

bool StreamifyDB::updateTrackBPM(int track_id, double bpm) {
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    sqlite3* db = getConnection();
    if (!db) return false;
    const char* sql = "UPDATE tracks SET bpm = ? WHERE id = ?;";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) == SQLITE_OK) {
        sqlite3_bind_double(stmt, 1, bpm);
        sqlite3_bind_int(stmt, 2, track_id);
        bool res = (sqlite3_step(stmt) == SQLITE_DONE);
        sqlite3_finalize(stmt);
        return res;
    }
    return false;
}

bool StreamifyDB::updateTrackKey(int track_id, const std::string& key) {
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    sqlite3* db = getConnection();
    if (!db) return false;
    const char* sql = "UPDATE tracks SET key = ? WHERE id = ?;";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) == SQLITE_OK) {
        sqlite3_bind_text(stmt, 1, key.c_str(), -1, SQLITE_TRANSIENT);
        sqlite3_bind_int(stmt, 2, track_id);
        bool res = (sqlite3_step(stmt) == SQLITE_DONE);
        sqlite3_finalize(stmt);
        return res;
    }
    return false;
}

bool StreamifyDB::updateTrackMetadata(int track_id, const std::string& title, const std::string& artist, const std::string& album) {
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    sqlite3* db = getConnection();
    if (!db) return false;
    const char* sql = "UPDATE tracks SET title = ?, artist = ?, album = ? WHERE id = ?;";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) == SQLITE_OK) {
        sqlite3_bind_text(stmt, 1, title.c_str(), -1, SQLITE_TRANSIENT);
        sqlite3_bind_text(stmt, 2, artist.c_str(), -1, SQLITE_TRANSIENT);
        sqlite3_bind_text(stmt, 3, album.c_str(), -1, SQLITE_TRANSIENT);
        sqlite3_bind_int(stmt, 4, track_id);
        bool res = (sqlite3_step(stmt) == SQLITE_DONE);
        sqlite3_finalize(stmt);
        return res;
    }
    return false;
}

bool StreamifyDB::insertTransition(int user_id, int from_track_id, int to_track_id, const std::string& type) {
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    sqlite3* db = getConnection();
    if (!db) return false;
    const char* sql = "INSERT INTO user_transitions (user_id, from_track_id, to_track_id, event_type) VALUES (?, ?, ?, ?);";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) == SQLITE_OK) {
        sqlite3_bind_int(stmt, 1, user_id);
        sqlite3_bind_int(stmt, 2, from_track_id);
        sqlite3_bind_int(stmt, 3, to_track_id);
        sqlite3_bind_text(stmt, 4, type.c_str(), -1, SQLITE_TRANSIENT);
        bool res = (sqlite3_step(stmt) == SQLITE_DONE);
        sqlite3_finalize(stmt);
        return res;
    }
    return false;
}

float StreamifyDB::getTransitionProbability(int user_id, int from_track_id, int to_track_id) {
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    sqlite3* db = getConnection();
    if (!db) return 0.0f;
    
    int transitionCount = 0;
    int totalFromCount = 0;

    const char* sqlCount = "SELECT COUNT(*) FROM user_transitions WHERE user_id = ? AND from_track_id = ? AND to_track_id = ? AND event_type = 'play';";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, sqlCount, -1, &stmt, nullptr) == SQLITE_OK) {
        sqlite3_bind_int(stmt, 1, user_id);
        sqlite3_bind_int(stmt, 2, from_track_id);
        sqlite3_bind_int(stmt, 3, to_track_id);
        if (sqlite3_step(stmt) == SQLITE_ROW) transitionCount = sqlite3_column_int(stmt, 0);
        sqlite3_finalize(stmt);
    }

    const char* sqlTotal = "SELECT COUNT(*) FROM user_transitions WHERE user_id = ? AND from_track_id = ? AND event_type = 'play';";
    if (sqlite3_prepare_v2(db, sqlTotal, -1, &stmt, nullptr) == SQLITE_OK) {
        sqlite3_bind_int(stmt, 1, user_id);
        sqlite3_bind_int(stmt, 2, from_track_id);
        if (sqlite3_step(stmt) == SQLITE_ROW) totalFromCount = sqlite3_column_int(stmt, 0);
        sqlite3_finalize(stmt);
    }

    return (totalFromCount > 0) ? (static_cast<float>(transitionCount) / static_cast<float>(totalFromCount)) : 0.0f;
}

int StreamifyDB::getSkipCount(int user_id, int from_track_id, int to_track_id) {
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    sqlite3* db = getConnection();
    if (!db) return 0;

    int skipCount = 0;
    const char* sql = "SELECT COUNT(*) FROM user_transitions WHERE user_id = ? AND from_track_id = ? AND to_track_id = ? AND event_type = 'skip';";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) == SQLITE_OK) {
        sqlite3_bind_int(stmt, 1, user_id);
        sqlite3_bind_int(stmt, 2, from_track_id);
        sqlite3_bind_int(stmt, 3, to_track_id);
        if (sqlite3_step(stmt) == SQLITE_ROW) skipCount = sqlite3_column_int(stmt, 0);
        sqlite3_finalize(stmt);
    }
    return skipCount;
}

int StreamifyDB::getTrackTotalSkipCount(int user_id, int track_id) {
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    sqlite3* db = getConnection();
    if (!db) return 0;

    int skipCount = 0;
    const char* sql = "SELECT COUNT(*) FROM user_transitions WHERE user_id = ? AND to_track_id = ? AND event_type = 'skip';";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) == SQLITE_OK) {
        sqlite3_bind_int(stmt, 1, user_id);
        sqlite3_bind_int(stmt, 2, track_id);
        if (sqlite3_step(stmt) == SQLITE_ROW) skipCount = sqlite3_column_int(stmt, 0);
        sqlite3_finalize(stmt);
    }
    return skipCount;
}
