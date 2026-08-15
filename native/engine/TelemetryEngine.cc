#include "TelemetryEngine.h"
#include "StreamifyDB.h"
#include <android/log.h>
#include <cmath>

#define LOG_TAG "TelemetryEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

TelemetryEngine& TelemetryEngine::getInstance() {
    static TelemetryEngine instance;
    return instance;
}

TelemetryEngine::TelemetryEngine() {
    startProcessing();
}

TelemetryEngine::~TelemetryEngine() {
    stopProcessing();
}

void TelemetryEngine::startProcessing() {
    if (!isRunning_.exchange(true)) {
        workerThread_ = std::thread(&TelemetryEngine::consumerLoop, this);
    }
}

void TelemetryEngine::stopProcessing() {
    if (isRunning_.exchange(false)) {
        if (workerThread_.joinable()) {
            workerThread_.join();
        }
    }
}

void TelemetryEngine::pushEvent(TelemetryEventType type, int64_t trackId, float value) {
    int64_t now = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::now().time_since_epoch()
    ).count();

    TelemetryEvent event{type, trackId, value, now};
    queue_.push(event);
}

void TelemetryEngine::consumerLoop() {
    while (isRunning_.load(std::memory_order_relaxed)) {
        bool processedAny = false;

        while (auto optEvent = queue_.pop()) {
            processedAny = true;
            const auto& ev = *optEvent;

            switch (ev.type) {
                case TelemetryEventType::SCRUB_SEEK: {
                    int64_t seekMs = static_cast<int64_t>(ev.value);
                    if (ev.trackId > 0 && seekMs > 5000) {
                        // Drop hunting: check if user repeatedly seeks to a cluster within +/- 4000ms
                        bool foundCluster = false;
                        for (auto& cluster : recentSeeks_) {
                            if (cluster.trackId == ev.trackId && std::abs(cluster.seekMs - seekMs) < 4000) {
                                cluster.count++;
                                cluster.seekMs = (cluster.seekMs + seekMs) / 2; // Refine hook center
                                if (cluster.count >= 2) {
                                    StreamifyDB::getInstance().logHookTelemetry(static_cast<int>(ev.trackId), cluster.seekMs, 0, 0);
                                }
                                foundCluster = true;
                                break;
                            }
                        }

                        if (!foundCluster) {
                            recentSeeks_.push_back({ev.trackId, seekMs, 1});
                            if (recentSeeks_.size() > 50) {
                                recentSeeks_.erase(recentSeeks_.begin());
                            }
                            StreamifyDB::getInstance().logHookTelemetry(static_cast<int>(ev.trackId), seekMs, 0, 0);
                        }
                    }
                    break;
                }
                case TelemetryEventType::VOLUME_CHANGE: {
                    if (ev.trackId > 0 && ev.value > 0.85f) {
                        // Volume flare emotional spike
                        StreamifyDB::getInstance().logHookTelemetry(static_cast<int>(ev.trackId), 0, 0, 1);
                        
                        // Adaptive target loudness adjustment: user wants louder output
                        float curLufs = targetLufs_.load(std::memory_order_relaxed);
                        float nextLufs = std::min(-10.0f, curLufs + 1.0f); // Boost target loudness up to -10 LUFS
                        targetLufs_.store(nextLufs, std::memory_order_relaxed);
                    }
                    break;
                }
                case TelemetryEventType::LYRICS_DWELL: {
                    int dwellSec = static_cast<int>(ev.value);
                    if (ev.trackId > 0 && dwellSec > 0) {
                        StreamifyDB::getInstance().logHookTelemetry(static_cast<int>(ev.trackId), 0, dwellSec, 0);
                    }
                    break;
                }
                case TelemetryEventType::PLAY_TRANSITION: {
                    int fromTrack = static_cast<int>(ev.trackId);
                    int toTrack = static_cast<int>(ev.value);
                    if (fromTrack > 0 && toTrack > 0 && fromTrack != toTrack) {
                        StreamifyDB::getInstance().recordTrackCooccurrence(fromTrack, toTrack);
                    }
                    break;
                }
                case TelemetryEventType::HEARTBEAT:
                default:
                    break;
            }
        }

        if (!processedAny) {
            std::this_thread::sleep_for(std::chrono::milliseconds(50));
        }
    }
}

float TelemetryEngine::getDynamicTargetLufs() const {
    return targetLufs_.load(std::memory_order_relaxed);
}
