#include "DirectoryWatcher.h"
#include <iostream>
#include <sys/inotify.h>
#include <unistd.h>
#include <limits.h>
#include <filesystem>
#include "../services/DatabaseService.h"

#define EVENT_SIZE  ( sizeof (struct inotify_event) )
#define BUF_LEN     ( 1024 * ( EVENT_SIZE + 16 ) )

static bool ends_with(const std::string& str, const std::string& suffix) {
    return str.size() >= suffix.size() && 0 == str.compare(str.size()-suffix.size(), suffix.size(), suffix);
}

DirectoryWatcher::DirectoryWatcher(const std::string& directory, Callback callback)
    : directory_(directory), callback_(callback) {
}

DirectoryWatcher::~DirectoryWatcher() {
    stop();
}

void DirectoryWatcher::start() {
    if (running_) return;
    running_ = true;
    
    std::cout << "[DirectoryWatcher] Reconciling existing files in " << directory_ << std::endl;
    if (std::filesystem::exists(directory_)) {
        for (const auto& entry : std::filesystem::recursive_directory_iterator(directory_)) {
            if (entry.is_regular_file()) {
                std::string filename = entry.path().filename().string();
                if (ends_with(filename, ".mp3") || ends_with(filename, ".flac") || ends_with(filename, ".wav")) {
                    std::string full_path = entry.path().string();
                    if (!DatabaseService::getInstance().trackExists(full_path)) {
                        callback_(full_path);
                    }
                }
            }
        }
    }
    
    worker_thread_ = std::thread(&DirectoryWatcher::watchLoop, this);
}

void DirectoryWatcher::stop() {
    if (!running_) return;
    running_ = false;
    
    if (inotify_fd_ != -1) {
        close(inotify_fd_); // Interrupts read
        inotify_fd_ = -1;
    }
    
    if (worker_thread_.joinable()) {
        worker_thread_.join();
    }
}

void DirectoryWatcher::watchLoop() {
    inotify_fd_ = inotify_init();
    if (inotify_fd_ < 0) {
        std::cerr << "[DirectoryWatcher] inotify_init failed" << std::endl;
        return;
    }

    std::vector<int> watch_descriptors;
    auto add_watch_dir = [this, &watch_descriptors](const std::string& dir) {
        int wd = inotify_add_watch(inotify_fd_, dir.c_str(), IN_CREATE | IN_MOVED_TO);
        if (wd != -1) {
            watch_descriptors.push_back(wd);
        }
    };

    add_watch_dir(directory_);
    if (std::filesystem::exists(directory_)) {
        try {
            for (const auto& entry : std::filesystem::recursive_directory_iterator(directory_)) {
                if (entry.is_directory()) {
                    add_watch_dir(entry.path().string());
                }
            }
        } catch (...) {}
    }

    char buffer[BUF_LEN];
    std::cout << "[DirectoryWatcher] Listening recursively on " << directory_ << " for new audio files..." << std::endl;

    while (running_) {
        int length = read(inotify_fd_, buffer, BUF_LEN);
        
        if (length < 0) {
            if (running_) {
                std::cerr << "[DirectoryWatcher] read error" << std::endl;
            }
            break;
        }

        int i = 0;
        while (i < length) {
            struct inotify_event* event = (struct inotify_event*)&buffer[i];
            if (event->len) {
                if (event->mask & IN_ISDIR) {
                    std::string new_dir = directory_ + "/" + event->name;
                    add_watch_dir(new_dir);
                } else {
                    std::string filename = event->name;
                    if (ends_with(filename, ".mp3") || ends_with(filename, ".flac") || ends_with(filename, ".wav")) {
                        std::string full_path = directory_ + "/" + filename;
                        callback_(full_path);
                    }
                }
            }
            i += EVENT_SIZE + event->len;
        }
    }

    if (inotify_fd_ != -1) {
        for (int wd : watch_descriptors) {
            inotify_rm_watch(inotify_fd_, wd);
        }
        close(inotify_fd_);
        inotify_fd_ = -1;
    }
}
