#ifndef TELEMETRY_ENGINE_H
#define TELEMETRY_ENGINE_H

#include <atomic>
#include <array>
#include <optional>
#include <thread>
#include <chrono>
#include <vector>
#include <cstdint>

enum class TelemetryEventType : int {
    SCRUB_SEEK = 1,
    VOLUME_CHANGE = 2,
    LYRICS_DWELL = 3,
    PLAY_TRANSITION = 4,
    HEARTBEAT = 5
};

struct TelemetryEvent {
    TelemetryEventType type{TelemetryEventType::HEARTBEAT};
    int64_t trackId{0};
    float value{0.0f}; // e.g. seek progress (ms), volume level (0.0-1.0), dwell seconds
    int64_t timestampMs{0};
};

// C++20 Lock-Free SPSC Ring Buffer (Capacity: 1024 events)
class LockFreeTelemetryQueue {
private:
    static constexpr size_t BUFFER_CAPACITY = 1024;
    std::array<TelemetryEvent, BUFFER_CAPACITY> buffer_;
    std::atomic<size_t> head_{0};
    std::atomic<size_t> tail_{0};

public:
    LockFreeTelemetryQueue() = default;

    bool push(const TelemetryEvent& event) {
        size_t current_head = head_.load(std::memory_order_relaxed);
        size_t next_head = (current_head + 1) % BUFFER_CAPACITY;
        if (next_head == tail_.load(std::memory_order_acquire)) {
            return false; // Queue full - avoid blocking
        }
        buffer_[current_head] = event;
        head_.store(next_head, std::memory_order_release);
        return true;
    }

    std::optional<TelemetryEvent> pop() {
        size_t current_tail = tail_.load(std::memory_order_relaxed);
        if (current_tail == head_.load(std::memory_order_acquire)) {
            return std::nullopt; // Queue empty
        }
        TelemetryEvent event = buffer_[current_tail];
        tail_.store((current_tail + 1) % BUFFER_CAPACITY, std::memory_order_release);
        return event;
    }

    bool isEmpty() const {
        return head_.load(std::memory_order_relaxed) == tail_.load(std::memory_order_relaxed);
    }
};

class TelemetryEngine {
public:
    static TelemetryEngine& getInstance();

    void pushEvent(TelemetryEventType type, int64_t trackId, float value);
    void startProcessing();
    void stopProcessing();

private:
    TelemetryEngine();
    ~TelemetryEngine();
    TelemetryEngine(const TelemetryEngine&) = delete;
    TelemetryEngine& operator=(const TelemetryEngine&) = delete;

    void consumerLoop();

    LockFreeTelemetryQueue queue_;
    std::atomic<bool> isRunning_{false};
    std::thread workerThread_;

    // Drop hunting memory buffer per track
    struct SeekCluster {
        int64_t trackId{0};
        int64_t seekMs{0};
        int count{0};
    };
    std::vector<SeekCluster> recentSeeks_;
};

#endif // TELEMETRY_ENGINE_H
