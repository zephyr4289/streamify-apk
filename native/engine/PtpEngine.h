#ifndef STREAMIFY_PTP_ENGINE_H
#define STREAMIFY_PTP_ENGINE_H

#include <cstdint>
#include <atomic>
#include <vector>
#include <mutex>

namespace streamify {

class PtpEngine {
public:
    static PtpEngine& getInstance();

    int64_t processTimestamps(int64_t t0, int64_t t1, int64_t t2, int64_t t3);
    int64_t getSynchronizedClockMs() const;
    int64_t getClockOffsetNanos() const;
    int64_t getLastRttNanos() const;
    void reset();

private:
    PtpEngine();
    ~PtpEngine() = default;

    PtpEngine(const PtpEngine&) = delete;
    PtpEngine& operator=(const PtpEngine&) = delete;

    std::atomic<int64_t> clock_offset_nanos_{0};
    std::atomic<int64_t> last_rtt_nanos_{0};
    std::atomic<int64_t> min_rtt_nanos_{INT64_MAX};
    std::atomic<bool> is_calibrated_{false};

    struct PtpSample {
        int64_t rtt;
        int64_t offset;
    };

    static constexpr size_t HISTORY_SIZE = 16;
    std::vector<PtpSample> history_;
    std::mutex history_mutex_;
};

} // namespace streamify

#endif // STREAMIFY_PTP_ENGINE_H
