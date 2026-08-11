#ifndef DIRECTORY_WATCHER_H
#define DIRECTORY_WATCHER_H

#include <string>
#include <thread>
#include <atomic>
#include <functional>

class DirectoryWatcher {
public:
    using Callback = std::function<void(const std::string&)>;

    DirectoryWatcher(const std::string& directory, Callback callback);
    ~DirectoryWatcher();

    void start();
    void stop();

private:
    void watchLoop();

    std::string directory_;
    Callback callback_;
    std::thread worker_thread_;
    std::atomic<bool> running_{false};
    int inotify_fd_{-1};
};

#endif // DIRECTORY_WATCHER_H
