#ifndef LYRIC_ALIGNER_H
#define LYRIC_ALIGNER_H

#include <vector>
#include <cstdint>
#include <cmath>

class LyricAligner {
public:
    static LyricAligner& getInstance();

    /**
     * Calculates the millisecond drift / intro offset Δτ* between audio PCM and expected text onsets.
     * Uses a 4th-order vocal bandpass filter (300 Hz - 3400 Hz) and 100 Hz KissFFT Wiener-Khinchin cross-correlation.
     * 
     * @param pcm Interleaved or mono float PCM samples
     * @param numSamples Total float samples
     * @param sampleRate Audio sampling rate (e.g. 44100 or 48000)
     * @param channelCount Channels in PCM (1 for mono, 2 for stereo)
     * @param textOnsetsMs Array of expected lyric line / syllable onset timestamps in milliseconds
     * @param onsetCount Number of timestamps in textOnsetsMs
     * @return Calculated optimal time offset in milliseconds (positive means audio leads text, negative means audio lags)
     */
    int32_t calculateDriftMs(
        const float* pcm,
        int numSamples,
        int sampleRate,
        int channelCount,
        const uint32_t* textOnsetsMs,
        int onsetCount
    );

private:
    LyricAligner() = default;
    ~LyricAligner() = default;
    LyricAligner(const LyricAligner&) = delete;
    LyricAligner& operator=(const LyricAligner&) = delete;

    void applyVocalBandpass(const float* input, float* output, int count, float sampleRate);
};

#endif // LYRIC_ALIGNER_H
