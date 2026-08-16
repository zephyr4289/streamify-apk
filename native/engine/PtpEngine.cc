#include "PtpEngine.h"
#include <algorithm>
#include <chrono>
#include <ctime>
#include <cmath>

namespace streamify {

PtpEngine& PtpEngine::getInstance() {
    static PtpEngine instance;
    return instance;
}

PtpEngine::PtpEngine() {
    history_.reserve(HISTORY_SIZE);
}

int64_t PtpEngine::processTimestamps(int64_t t0, int64_t t1, int64_t t2, int64_t t3) {
    int64_t rtt = (t3 - t0) - (t2 - t1);
    if (rtt < 0) {
        rtt = 0;
    }

    // Standard IEEE 1588 / Cristian's clock offset formula:
    // Offset = ((t1 - t0) + (t2 - t3)) / 2
    int64_t offset = ((t1 - t0) + (t2 - t3)) / 2;

    last_rtt_nanos_.store(rtt, std::memory_order_relaxed);

    std::lock_guard<std::mutex> lock(history_mutex_);
    if (history_.size() >= HISTORY_SIZE) {
        history_.erase(history_.begin());
    }
    history_.push_back({rtt, offset});

    // Update minimum historical RTT
    int64_t current_min_rtt = min_rtt_nanos_.load(std::memory_order_relaxed);
    if (rtt < current_min_rtt || current_min_rtt == INT64_MAX) {
        min_rtt_nanos_.store(rtt, std::memory_order_relaxed);
        current_min_rtt = rtt;
    }

    // Outlier rejection: only incorporate samples whose RTT is within 1.35x of the minimum RTT
    if (!is_calibrated_.load(std::memory_order_relaxed) || rtt <= static_cast<int64_t>(current_min_rtt * 1.35) || rtt < 10'000'000LL) { // 10ms
        int64_t current_offset = clock_offset_nanos_.load(std::memory_order_relaxed);
        if (!is_calibrated_.load(std::memory_order_relaxed)) {
            clock_offset_nanos_.store(offset, std::memory_order_release);
            is_calibrated_.store(true, std::memory_order_release);
        } else {
            // Exponential Moving Average (EMA) with α = 0.18
            double smoothed = (0.82 * static_cast<double>(current_offset)) + (0.18 * static_cast<double>(offset));
            clock_offset_nanos_.store(static_cast<int64_t>(smoothed), std::memory_order_release);
        }
    }

    return clock_offset_nanos_.load(std::memory_order_acquire);
}

int64_t PtpEngine::getSynchronizedClockMs() const {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    int64_t now_nanos = (static_cast<int64_t>(ts.tv_sec) * 1'000'000'000LL) + static_cast<int64_t>(ts.tv_nsec);
    int64_t offset = clock_offset_nanos_.load(std::memory_order_acquire);
    return (now_nanos + offset) / 1'000'000LL;
}

int64_t PtpEngine::getClockOffsetNanos() const {
    return clock_offset_nanos_.load(std::memory_order_acquire);
}

int64_t PtpEngine::getLastRttNanos() const {
    return last_rtt_nanos_.load(std::memory_order_acquire);
}

void PtpEngine::reset() {
    std::lock_guard<std::mutex> lock(history_mutex_);
    history_.clear();
    clock_offset_nanos_.store(0, std::memory_order_release);
    last_rtt_nanos_.store(0, std::memory_order_release);
    min_rtt_nanos_.store(INT64_MAX, std::memory_order_release);
    is_calibrated_.store(false, std::memory_order_release);
}

} // namespace streamify
