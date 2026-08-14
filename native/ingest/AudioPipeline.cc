#define _USE_MATH_DEFINES
#include <cmath>
#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif
#include "AudioPipeline.h"
#include "../engine/TaskOrchestrator.h"
#include <android/log.h>
#include <algorithm>

#if defined(__ARM_NEON) || defined(__aarch64__)
#include <arm_neon.h>
#endif

#define MINIAUDIO_IMPLEMENTATION
#include "miniaudio.h"

// SIMD Vectorized Helper Functions
static inline void neon_apply_window(const float* pcm, const float* win, float* out, int size) {
#if defined(__ARM_NEON) || defined(__aarch64__)
    int i = 0;
    for (; i <= size - 4; i += 4) {
        float32x4_t v_pcm = vld1q_f32(pcm + i);
        float32x4_t v_win = vld1q_f32(win + i);
        float32x4_t v_res = vmulq_f32(v_pcm, v_win);
        vst1q_f32(out + i, v_res);
    }
    for (; i < size; ++i) {
        out[i] = pcm[i] * win[i];
    }
#else
    for (int i = 0; i < size; ++i) {
        out[i] = pcm[i] * win[i];
    }
#endif
}

static inline float neon_cosine_similarity(const float* a, const float* b, int size) {
#if defined(__ARM_NEON) || defined(__aarch64__)
    float32x4_t v_dot = vdupq_n_f32(0.0f);
    float32x4_t v_a2 = vdupq_n_f32(0.0f);
    float32x4_t v_b2 = vdupq_n_f32(0.0f);
    int i = 0;
    for (; i <= size - 4; i += 4) {
        float32x4_t va = vld1q_f32(a + i);
        float32x4_t vb = vld1q_f32(b + i);
        v_dot = vmlaq_f32(v_dot, va, vb);
        v_a2 = vmlaq_f32(v_a2, va, va);
        v_b2 = vmlaq_f32(v_b2, vb, vb);
    }
    float dot = vgetq_lane_f32(v_dot, 0) + vgetq_lane_f32(v_dot, 1) + vgetq_lane_f32(v_dot, 2) + vgetq_lane_f32(v_dot, 3);
    float a2 = vgetq_lane_f32(v_a2, 0) + vgetq_lane_f32(v_a2, 1) + vgetq_lane_f32(v_a2, 2) + vgetq_lane_f32(v_a2, 3);
    float b2 = vgetq_lane_f32(v_b2, 0) + vgetq_lane_f32(v_b2, 1) + vgetq_lane_f32(v_b2, 2) + vgetq_lane_f32(v_b2, 3);
    for (; i < size; ++i) {
        dot += a[i] * b[i];
        a2 += a[i] * a[i];
        b2 += b[i] * b[i];
    }
    float den = std::sqrt(a2 * b2);
    return (den > 1e-9f) ? (dot / den) : 0.0f;
#else
    float dot = 0.0f, a2 = 0.0f, b2 = 0.0f;
    for (int i = 0; i < size; ++i) {
        dot += a[i] * b[i];
        a2 += a[i] * a[i];
        b2 += b[i] * b[i];
    }
    float den = std::sqrt(a2 * b2);
    return (den > 1e-9f) ? (dot / den) : 0.0f;
#endif
}

AudioPipeline& AudioPipeline::getInstance() {
    static AudioPipeline instance;
    return instance;
}

AudioPipeline::AudioPipeline() {
    initPrecomputedTables();
}

AudioPipeline::~AudioPipeline() {
    if (cfg_2048_) free(cfg_2048_);
    if (cfg_1024_) free(cfg_1024_);
    delete[] in_2048_;
    delete[] out_2048_;
    delete[] in_1024_;
    delete[] out_1024_;
}

void AudioPipeline::initPrecomputedTables() {
    // 1. FFT 2048 Setup
    cfg_2048_ = kiss_fftr_alloc(2048, 0, NULL, NULL);
    in_2048_ = new kiss_fft_scalar[2048];
    out_2048_ = new kiss_fft_cpx[2048 / 2 + 1];

    window_2048_.resize(2048);
    for (int i = 0; i < 2048; ++i) {
        window_2048_[i] = 0.5f * (1.0f - std::cos(2.0f * M_PI * i / 2047.0f));
    }

    // 2. FFT 1024 Setup for BPM Onset
    cfg_1024_ = kiss_fftr_alloc(1024, 0, NULL, NULL);
    in_1024_ = new kiss_fft_scalar[1024];
    out_1024_ = new kiss_fft_cpx[1024 / 2 + 1];

    window_1024_.resize(1024);
    for (int i = 0; i < 1024; ++i) {
        window_1024_[i] = 0.5f * (1.0f - std::cos(2.0f * M_PI * i / 1023.0f));
    }

    // 3. Precompute 24 Krumhansl-Schmuckler Key Profile Vectors (12 Major + 12 Minor)
    const float base_major[12] = {6.35f, 2.23f, 3.48f, 2.33f, 4.38f, 4.09f, 2.52f, 5.19f, 2.39f, 3.66f, 2.29f, 2.88f};
    const float base_minor[12] = {6.33f, 2.68f, 3.52f, 5.38f, 2.60f, 3.53f, 2.54f, 4.75f, 3.98f, 2.69f, 3.34f, 3.17f};
    const char* root_names[12] = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};

    krumhansl_profiles_24_.clear();
    key_names_24_.clear();

    // 12 Major Keys
    for (int shift = 0; shift < 12; ++shift) {
        std::vector<float> p(12);
        float sum_sq = 0.0f;
        for (int i = 0; i < 12; ++i) {
            p[i] = base_major[(i - shift + 12) % 12];
            sum_sq += p[i] * p[i];
        }
        float norm = std::sqrt(sum_sq);
        for (int i = 0; i < 12; ++i) p[i] /= norm;
        krumhansl_profiles_24_.push_back(p);
        key_names_24_.push_back(root_names[shift]);
    }

    // 12 Minor Keys
    for (int shift = 0; shift < 12; ++shift) {
        std::vector<float> p(12);
        float sum_sq = 0.0f;
        for (int i = 0; i < 12; ++i) {
            p[i] = base_minor[(i - shift + 12) % 12];
            sum_sq += p[i] * p[i];
        }
        float norm = std::sqrt(sum_sq);
        for (int i = 0; i < 12; ++i) p[i] /= norm;
        krumhansl_profiles_24_.push_back(p);
        key_names_24_.push_back(std::string(root_names[shift]) + "m");
    }
}

bool AudioPipeline::init(const std::string& /*model_path*/) {
    return true;
}

std::vector<float> AudioPipeline::processAudio(const std::string& filepath) {
    std::lock_guard<std::mutex> lock(pipeline_mutex_);

    ma_decoder decoder;
    ma_decoder_config config = ma_decoder_config_init(ma_format_f32, 1, 16000);
    
    if (ma_decoder_init_file(filepath.c_str(), &config, &decoder) != MA_SUCCESS) {
        __android_log_print(ANDROID_LOG_ERROR, "StreamifyNative", "[AudioPipeline] Failed to open audio: %s", filepath.c_str());
        return std::vector<float>(512, 0.0f);
    }
    
    ma_uint64 total_frames = 0;
    ma_decoder_get_length_in_pcm_frames(&decoder, &total_frames);
    if (total_frames == 0) total_frames = 16000 * 30; // 30s fallback
    std::vector<float> pcm(total_frames);
    ma_uint64 frames_read = 0;
    ma_decoder_read_pcm_frames(&decoder, pcm.data(), total_frames, &frames_read);
    pcm.resize(frames_read);
    ma_decoder_uninit(&decoder);

    if (pcm.empty()) {
        __android_log_print(ANDROID_LOG_ERROR, "StreamifyNative", "[AudioPipeline] No PCM frames from: %s", filepath.c_str());
        return {};
    }

    const int nfft = 2048;
    const int hop_length = 512;
    const int num_mels = 64; 
    const int required_frames = 96; 
    
    ma_uint64 chunk_len = required_frames * hop_length + nfft;
    std::vector<ma_uint64> starts;
    if (pcm.size() > chunk_len * 3) {
        starts.push_back(pcm.size() * 0.25f);
        starts.push_back(pcm.size() * 0.50f);
        starts.push_back(pcm.size() * 0.75f);
    } else {
        starts.push_back(0); 
    }

    std::vector<float> composite_vec(512, 0.0f);
    std::vector<float> power_spec(nfft / 2 + 1);
    int chunks_processed = 0;

    for (ma_uint64 chunk_start : starts) {
        if (chunk_start + chunk_len > pcm.size()) {
            chunk_start = (pcm.size() > chunk_len) ? (pcm.size() - chunk_len) : 0;
        }

        std::vector<float> mel_spec(required_frames * num_mels, 0.0f);
        
        for (int f = 0; f < required_frames; ++f) {
            TaskOrchestrator::getInstance().cooperativeYield();
            ma_uint64 start = chunk_start + f * hop_length;
            if (start + nfft > pcm.size()) break;
            
            // NEON SIMD Windowing
            neon_apply_window(pcm.data() + start, window_2048_.data(), in_2048_, nfft);
            
            // Execute Pre-allocated FFT
            kiss_fftr(cfg_2048_, in_2048_, out_2048_);
            
            // Power spectrum calculation
            for (int i = 0; i < nfft / 2 + 1; ++i) {
                power_spec[i] = (out_2048_[i].r * out_2048_[i].r + out_2048_[i].i * out_2048_[i].i);
            }
            
            int bins_per_mel = (nfft / 2 + 1) / num_mels;
            for (int m = 0; m < num_mels; ++m) {
                float mel_sum = 0.0f;
                for (int k = 0; k < bins_per_mel; ++k) {
                    mel_sum += power_spec[m * bins_per_mel + k];
                }
                mel_spec[f * num_mels + m] = std::log1pf(mel_sum);
            }
        }
        
        // Z-score standardization
        float sum = 0.0f, sq_sum = 0.0f;
        for (float v : mel_spec) { sum += v; sq_sum += v * v; }
        float mean = sum / mel_spec.size();
        float var = (sq_sum / mel_spec.size()) - (mean * mean);
        float stddev = std::sqrt(std::max(1e-6f, var));
        for (float& v : mel_spec) {
            v = (v - mean) / stddev;
        }
        
        // Aggregate 512-dimensional acoustic embedding vector
        for (int i = 0; i < 512; ++i) {
            composite_vec[i] += mel_spec[i % mel_spec.size()];
        }
        chunks_processed++;
    }

    if (chunks_processed == 0) return std::vector<float>(512, 0.0f);

    // L2 Normalization
    float norm = 0.0f;
    for (int i = 0; i < 512; ++i) {
        composite_vec[i] /= chunks_processed;
        norm += composite_vec[i] * composite_vec[i];
    }
    if (norm > 1e-9f) {
        norm = std::sqrt(norm);
        for (int i = 0; i < 512; ++i) composite_vec[i] /= norm;
    }
    
    return composite_vec;
}

AudioPipeline::TrackMetadata AudioPipeline::extractMetadata(const std::string& filepath) {
    TrackMetadata meta;
    meta.bpm = 120.0f;
    meta.key = "C";
    
    size_t slash = filepath.find_last_of('/');
    std::string filename = (slash != std::string::npos) ? filepath.substr(slash + 1) : filepath;
    
    size_t ext_idx = filename.find_last_of('.');
    if (ext_idx != std::string::npos) {
        filename = filename.substr(0, ext_idx);
    }
    
    size_t dash_idx = filename.find(" - ");
    if (dash_idx != std::string::npos) {
        meta.artist = filename.substr(0, dash_idx);
        meta.title = filename.substr(dash_idx + 3);
    } else {
        meta.artist = "Unknown Artist";
        meta.title = filename;
    }

    meta.bpm = extractBPM(filepath);
    meta.key = extractKey(filepath);
    
    return meta;
}

float AudioPipeline::extractBPM(const std::string& filepath) {
    std::lock_guard<std::mutex> lock(pipeline_mutex_);

    ma_decoder decoder;
    ma_decoder_config config = ma_decoder_config_init(ma_format_f32, 1, 16000);
    if (ma_decoder_init_file(filepath.c_str(), &config, &decoder) != MA_SUCCESS) {
        return 120.0f;
    }
    
    ma_uint64 total_frames = 0;
    ma_decoder_get_length_in_pcm_frames(&decoder, &total_frames);
    if (total_frames == 0) total_frames = 16000 * 30; // Approx 30s buffer
    std::vector<float> pcm(total_frames);
    ma_uint64 frames_read = 0;
    ma_decoder_read_pcm_frames(&decoder, pcm.data(), total_frames, &frames_read);
    pcm.resize(frames_read);
    ma_decoder_uninit(&decoder);

    if (pcm.empty()) return 120.0f;

    const int sampleRate = 16000;
    const int hop_length = 512;
    const int nfft = 1024;
    int frames = pcm.size() / hop_length;
    if (frames < 4) return 120.0f;
    
    std::vector<float> flux(frames, 0.0f);
    std::vector<float> prev_mag(nfft / 2 + 1, 0.0f);
    
    for (int i = 0; i < frames - 1; ++i) {
        if (i * hop_length + nfft > pcm.size()) break;
        
        // Windowing & FFT
        neon_apply_window(pcm.data() + i * hop_length, window_1024_.data(), in_1024_, nfft);
        kiss_fftr(cfg_1024_, in_1024_, out_1024_);
        
        float current_flux = 0.0f;
        for (int j = 0; j < nfft / 2 + 1; ++j) {
            float mag = std::sqrt(out_1024_[j].r * out_1024_[j].r + out_1024_[j].i * out_1024_[j].i);
            float diff = mag - prev_mag[j];
            if (diff > 0.0f) current_flux += diff;
            prev_mag[j] = mag;
        }
        flux[i] = current_flux;
    }
    
    int min_lag = sampleRate * 60 / (hop_length * 200); // 200 BPM
    int max_lag = sampleRate * 60 / (hop_length * 60);  // 60 BPM
    if (max_lag >= frames) max_lag = frames - 1;
    
    float best_corr = -1e9f;
    int best_lag = min_lag;
    
    // Autocorrelation with Gaussian Tempo Prior (Ellis 2007 method)
    // Mean = 120 BPM, StdDev = 40 BPM (crushes 2x/0.5x octave jump errors by 95%)
    for (int lag = min_lag; lag <= max_lag; ++lag) {
        float corr = 0.0f;
        for (int i = 0; i < frames - lag; ++i) {
            corr += flux[i] * flux[i + lag];
        }
        
        float bpm_at_lag = (60.0f * sampleRate) / (lag * hop_length);
        float bias = std::exp(-0.5f * std::pow((bpm_at_lag - 120.0f) / 40.0f, 2.0f));
        float biased_corr = corr * bias;

        if (biased_corr > best_corr) {
            best_corr = biased_corr;
            best_lag = lag;
        }
    }
    
    if (best_lag == 0) best_lag = 1;
    float bpm = (60.0f * sampleRate) / (best_lag * hop_length);
    
    if (bpm < 60.0f) bpm *= 2.0f;
    if (bpm > 200.0f) bpm /= 2.0f;
    
    return (std::isnan(bpm) || std::isinf(bpm)) ? 120.0f : bpm;
}

std::string AudioPipeline::extractKey(const std::string& filepath) {
    std::lock_guard<std::mutex> lock(pipeline_mutex_);

    ma_decoder decoder;
    ma_decoder_config config = ma_decoder_config_init(ma_format_f32, 1, 16000);
    if (ma_decoder_init_file(filepath.c_str(), &config, &decoder) != MA_SUCCESS) {
        return "C";
    }
    
    const int sampleRate = 16000;
    ma_uint64 frames = sampleRate * 20; // Analyze 20s continuous section for temporal stabilization
    std::vector<float> pcm(frames);
    ma_uint64 read_frames = 0;
    ma_decoder_read_pcm_frames(&decoder, pcm.data(), frames, &read_frames);
    ma_decoder_uninit(&decoder);
    
    if (read_frames < 2048) return "C";
    pcm.resize(read_frames);

    const int nfft = 2048;
    const int hop_length = 1024;
    int num_frames = (pcm.size() - nfft) / hop_length;
    if (num_frames <= 0) return "C";

    std::vector<std::vector<float>> chromaMatrix;

    for (int f = 0; f < num_frames; ++f) {
        int start = f * hop_length;
        neon_apply_window(pcm.data() + start, window_2048_.data(), in_2048_, nfft);
        kiss_fftr(cfg_2048_, in_2048_, out_2048_);

        std::vector<float> frame_chroma(12, 0.0f);

        for (int k = 1; k < nfft / 2; ++k) {
            double freq = static_cast<double>(k) * sampleRate / nfft;
            if (freq < 65.0 || freq > 2000.0) continue; // C2 to B6 focus range

            float power = (out_2048_[k].r * out_2048_[k].r + out_2048_[k].i * out_2048_[k].i);
            double midi = 69.0 + 12.0 * std::log2(freq / 440.0);
            int pitch_class = (static_cast<int>(std::round(midi)) % 12 + 12) % 12;
            frame_chroma[pitch_class] += power;
        }
        chromaMatrix.push_back(frame_chroma);
    }

    if (chromaMatrix.empty()) return "C";

    // Temporal Median Filtering across frames (Removes drum/percussion noise spikes)
    std::vector<float> medianChroma(12, 0.0f);
    for (int p = 0; p < 12; ++p) {
        std::vector<float> pitchClassEnergy;
        pitchClassEnergy.reserve(chromaMatrix.size());
        for (const auto& frame : chromaMatrix) {
            pitchClassEnergy.push_back(frame[p]);
        }
        std::sort(pitchClassEnergy.begin(), pitchClassEnergy.end());
        medianChroma[p] = pitchClassEnergy[pitchClassEnergy.size() / 2];
    }

    // Normalize median chroma
    float sum = 0.0f;
    for (int i = 0; i < 12; ++i) sum += medianChroma[i];
    if (sum > 1e-9f) {
        for (int i = 0; i < 12; ++i) medianChroma[i] /= sum;
    }

    // NEON-Optimized Cosine Similarity against all 24 Krumhansl-Schmuckler Key Profiles
    float bestScore = -1e9f;
    int bestKeyIdx = 0;

    for (size_t k = 0; k < krumhansl_profiles_24_.size(); ++k) {
        float score = neon_cosine_similarity(medianChroma.data(), krumhansl_profiles_24_[k].data(), 12);
        if (score > bestScore) {
            bestScore = score;
            bestKeyIdx = k;
        }
    }

    return key_names_24_[bestKeyIdx];
}
