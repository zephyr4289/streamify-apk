#define _USE_MATH_DEFINES
#include <cmath>
#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif
#include "AudioPipeline.h"
#include <android/log.h>
#include <kiss_fftr.h>

#define MINIAUDIO_IMPLEMENTATION
#include "miniaudio.h"

AudioPipeline& AudioPipeline::getInstance() {
    static AudioPipeline instance;
    return instance;
}

AudioPipeline::AudioPipeline() : env_(ORT_LOGGING_LEVEL_WARNING, "AudioPipeline") {}

AudioPipeline::~AudioPipeline() {
    if (session_) {
        delete session_;
        session_ = nullptr;
    }
}

bool AudioPipeline::init(const std::string& onnx_model_path) {
    try {
        if (session_) {
            delete session_;
            session_ = nullptr;
        }
        Ort::SessionOptions session_options;
        session_options.SetIntraOpNumThreads(1);
        session_options.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_ALL);
        
        session_ = new Ort::Session(env_, onnx_model_path.c_str(), session_options);
        return true;
    } catch (const std::exception& e) {
        __android_log_print(ANDROID_LOG_ERROR, "StreamifyNative", "[AudioPipeline] Failed to init ONNX: %s", e.what());
        return false;
    }
}

std::vector<float> AudioPipeline::processAudio(const std::string& filepath) {
    ma_decoder decoder;
    ma_decoder_config config = ma_decoder_config_init(ma_format_f32, 1, 16000);
    
    if (ma_decoder_init_file(filepath.c_str(), &config, &decoder) != MA_SUCCESS) {
        __android_log_print(ANDROID_LOG_ERROR, "StreamifyNative", "[AudioPipeline] Failed to open audio: %s", filepath.c_str());
        return std::vector<float>(512, 0.0f);
    }
    
    ma_uint64 total_frames = 0;
    ma_decoder_get_length_in_pcm_frames(&decoder, &total_frames);
    if (total_frames == 0) total_frames = 16000 * 30; // Approx 30s buffer if length unknown
    std::vector<float> pcm(total_frames);
    ma_uint64 frames_read = 0;
    ma_decoder_read_pcm_frames(&decoder, pcm.data(), total_frames, &frames_read);
    pcm.resize(frames_read);
    ma_decoder_uninit(&decoder);

    if (pcm.empty()) {
        __android_log_print(ANDROID_LOG_ERROR, "StreamifyNative", "[AudioPipeline] No PCM frames read from: %s", filepath.c_str());
        return {};
    }

    int nfft = 2048;
    int hop_length = 512;
    int num_mels = 64; 
    int required_frames = 96; 
    
    ma_uint64 chunk_len = required_frames * hop_length + nfft;
    std::vector<ma_uint64> starts;
    if (pcm.size() > chunk_len * 3) {
        starts.push_back(pcm.size() * 0.25f);
        starts.push_back(pcm.size() * 0.50f);
        starts.push_back(pcm.size() * 0.75f);
    } else {
        starts.push_back(0); 
    }
    
    kiss_fftr_cfg cfg = kiss_fftr_alloc(nfft, 0, NULL, NULL);
    kiss_fft_scalar* in = new kiss_fft_scalar[nfft];
    kiss_fft_cpx* out = new kiss_fft_cpx[nfft / 2 + 1];
    
    std::vector<double> window(nfft);
    for(int i = 0; i < nfft; ++i) {
        window[i] = 0.5 * (1 - std::cos(2 * M_PI * i / (nfft - 1)));
    }

    std::vector<float> composite_vec(512, 0.0f);
    int chunks_processed = 0;

    for (ma_uint64 chunk_start : starts) {
        if (chunk_start + chunk_len > pcm.size()) {
            chunk_start = (pcm.size() > chunk_len) ? (pcm.size() - chunk_len) : 0;
        }

        std::vector<float> mel_spec(required_frames * num_mels, 0.0f);
        
        for (int f = 0; f < required_frames; ++f) {
            ma_uint64 start = chunk_start + f * hop_length;
            if (start + nfft > pcm.size()) break;
            
            for (int i = 0; i < nfft; ++i) {
                in[i] = pcm[start + i] * window[i];
            }
            
            kiss_fftr(cfg, in, out);
            
            std::vector<double> power(nfft / 2 + 1);
            for (int i = 0; i < nfft / 2 + 1; ++i) {
                power[i] = (out[i].r * out[i].r + out[i].i * out[i].i);
            }
            
            int bins_per_mel = (nfft / 2 + 1) / num_mels;
            for (int m = 0; m < num_mels; ++m) {
                double mel_sum = 0.0;
                for (int k = 0; k < bins_per_mel; ++k) {
                    mel_sum += power[m * bins_per_mel + k];
                }
                mel_spec[f * num_mels + m] = std::log1p(mel_sum);
            }
        }
        
        float sum = 0.0f, sq_sum = 0.0f;
        for (float v : mel_spec) { sum += v; sq_sum += v * v; }
        float mean = sum / mel_spec.size();
        float var = (sq_sum / mel_spec.size()) - (mean * mean);
        float stddev = std::sqrt(std::max(1e-6f, var));
        for (float& v : mel_spec) {
            v = (v - mean) / stddev;
        }
        
        if (!session_) {
            for (int i = 0; i < 512; ++i) {
                composite_vec[i] += mel_spec[i % mel_spec.size()];
            }
            chunks_processed++;
            continue;
        }
        
        try {
            Ort::MemoryInfo memory_info = Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault);
            const char* input_names[] = {"input"};
            const char* output_names[] = {"output"};
            std::vector<int64_t> input_dims = {1, 1, required_frames, num_mels};
            Ort::Value input_tensor_ort = Ort::Value::CreateTensor<float>(
                memory_info, mel_spec.data(), mel_spec.size(),
                input_dims.data(), input_dims.size());
                
            auto output_tensors = session_->Run(
                Ort::RunOptions{nullptr}, input_names, &input_tensor_ort, 1, output_names, 1);
                
            if (output_tensors.empty()) continue;

            auto tensor_info = output_tensors.front().GetTensorTypeAndShapeInfo();
            if (tensor_info.GetElementCount() != 512) {
                __android_log_print(ANDROID_LOG_ERROR, "StreamifyNative", "[AudioPipeline] Invalid ONNX output shape");
                continue;
            }
                
            float* out_arr = output_tensors.front().GetTensorMutableData<float>();
            
            bool valid = true;
            for (int i = 0; i < 512; ++i) {
                if (std::isnan(out_arr[i]) || std::isinf(out_arr[i])) {
                    valid = false;
                    break;
                }
            }
            
            if (valid) {
                for (int i = 0; i < 512; ++i) composite_vec[i] += out_arr[i];
                chunks_processed++;
            }
        } catch (const std::exception& e) {
            __android_log_print(ANDROID_LOG_ERROR, "StreamifyNative", "[AudioPipeline] ONNX Run error: %s", e.what());
        }
    }
    
    free(cfg);
    delete[] in;
    delete[] out;
    
    if (chunks_processed == 0) {
        __android_log_print(ANDROID_LOG_ERROR, "StreamifyNative", "[AudioPipeline] Corrupted audio features detected for track: %s", filepath.c_str());
        return std::vector<float>(); 
    }
    
    float norm = 0.0f;
    for(int i = 0; i < 512; ++i) {
        composite_vec[i] /= chunks_processed;
        norm += composite_vec[i] * composite_vec[i];
    }
    if (norm > 1e-9f) {
        norm = std::sqrt(norm);
        for(int i = 0; i < 512; ++i) composite_vec[i] /= norm;
    }
    
    return composite_vec;
}

AudioPipeline::TrackMetadata AudioPipeline::extractMetadata(const std::string& filepath) {
    TrackMetadata meta;
    meta.bpm = 120.0;
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

    FILE* f = fopen(filepath.c_str(), "rb");
    if (f) {
        fseek(f, 0, SEEK_END);
        long size = ftell(f);
        fclose(f);
        
        meta.bpm = extractBPM(filepath);
        meta.key = extractKey(filepath);
    }
    
    return meta;
}

float AudioPipeline::extractBPM(const std::string& filepath) {
    ma_decoder decoder;
    ma_decoder_config config = ma_decoder_config_init(ma_format_f32, 1, 16000);
    if (ma_decoder_init_file(filepath.c_str(), &config, &decoder) != MA_SUCCESS) {
        return 120.0f;
    }
    
    ma_uint64 total_frames = 0;
    ma_decoder_get_length_in_pcm_frames(&decoder, &total_frames);
    if (total_frames == 0) total_frames = 16000 * 30; // Approx 30s buffer if length unknown
    std::vector<float> pcm(total_frames);
    ma_uint64 frames_read = 0;
    ma_decoder_read_pcm_frames(&decoder, pcm.data(), total_frames, &frames_read);
    pcm.resize(frames_read);
    ma_decoder_uninit(&decoder);

    if (pcm.empty()) return 120.0f;

    int sampleRate = 16000;
    int hop_length = 512;
    int nfft = 1024;
    int frames = pcm.size() / hop_length;
    if (frames < 2) return 120.0f;
    
    kiss_fftr_cfg cfg = kiss_fftr_alloc(nfft, 0, NULL, NULL);
    kiss_fft_scalar* in = new kiss_fft_scalar[nfft];
    kiss_fft_cpx* out = new kiss_fft_cpx[nfft / 2 + 1];
    
    std::vector<float> flux(frames, 0.0f);
    std::vector<float> prev_mag(nfft / 2 + 1, 0.0f);
    
    for (int i = 0; i < frames - 1; ++i) {
        if (i * hop_length + nfft > pcm.size()) break;
        for (int j = 0; j < nfft; ++j) {
            in[j] = pcm[i * hop_length + j];
        }
        kiss_fftr(cfg, in, out);
        float current_flux = 0.0f;
        for (int j = 0; j < nfft / 2 + 1; ++j) {
            float mag = std::sqrt(out[j].r * out[j].r + out[j].i * out[j].i);
            float diff = mag - prev_mag[j];
            if (diff > 0) current_flux += diff;
            prev_mag[j] = mag;
        }
        flux[i] = current_flux;
    }
    
    free(cfg);
    delete[] in;
    delete[] out;
    
    int min_lag = sampleRate * 60 / (hop_length * 200); // 200 BPM
    int max_lag = sampleRate * 60 / (hop_length * 60);  // 60 BPM
    
    if (max_lag >= frames) max_lag = frames - 1;
    
    float best_corr = 0.0f;
    int best_lag = min_lag;
    
    for (int lag = min_lag; lag <= max_lag; ++lag) {
        float corr = 0.0f;
        for (int i = 0; i < frames - lag; ++i) {
            corr += flux[i] * flux[i + lag];
        }
        if (corr > best_corr) {
            best_corr = corr;
            best_lag = lag;
        }
    }
    
    if (best_lag == 0) best_lag = 1;
    float bpm = 60.0f * sampleRate / (best_lag * hop_length);
    if (bpm < 60.0f) bpm *= 2.0f;
    if (bpm > 200.0f) bpm /= 2.0f;
    
    return std::isnan(bpm) || std::isinf(bpm) ? 120.0f : bpm;
}

std::string AudioPipeline::extractKey(const std::string& filepath) {
    ma_decoder decoder;
    ma_decoder_config config = ma_decoder_config_init(ma_format_f32, 1, 16000);
    if (ma_decoder_init_file(filepath.c_str(), &config, &decoder) != MA_SUCCESS) {
        return "C";
    }
    
    ma_uint64 sampleRate = 16000;
    ma_uint64 frames = sampleRate * 8; // Analyze up to 8 seconds
    std::vector<float> pcm(frames);
    ma_uint64 read_frames = 0;
    ma_decoder_read_pcm_frames(&decoder, pcm.data(), frames, &read_frames);
    ma_decoder_uninit(&decoder);
    
    if (read_frames < 2048) return "C";
    pcm.resize(read_frames);

    int nfft = 2048;
    int hop_length = 1024;
    int num_frames = (pcm.size() - nfft) / hop_length;
    if (num_frames <= 0) return "C";

    kiss_fftr_cfg cfg = kiss_fftr_alloc(nfft, 0, NULL, NULL);
    kiss_fft_scalar* in = new kiss_fft_scalar[nfft];
    kiss_fft_cpx* out = new kiss_fft_cpx[nfft / 2 + 1];

    std::vector<double> window(nfft);
    for (int i = 0; i < nfft; ++i) {
        window[i] = 0.5 * (1.0 - std::cos(2.0 * M_PI * i / (nfft - 1)));
    }

    std::vector<double> chroma(12, 0.0);

    for (int f = 0; f < num_frames; ++f) {
        int start = f * hop_length;
        for (int i = 0; i < nfft; ++i) {
            in[i] = pcm[start + i] * window[i];
        }

        kiss_fftr(cfg, in, out);

        for (int k = 1; k < nfft / 2; ++k) {
            double freq = static_cast<double>(k) * sampleRate / nfft;
            if (freq < 65.0 || freq > 2000.0) continue; // C2 to B6 focus range

            double power = (out[k].r * out[k].r + out[k].i * out[k].i);
            double midi = 69.0 + 12.0 * std::log2(freq / 440.0);
            int pitch_class = (static_cast<int>(std::round(midi)) % 12 + 12) % 12;
            chroma[pitch_class] += power;
        }
    }

    free(cfg);
    delete[] in;
    delete[] out;

    // Normalize chroma vector
    double chroma_sum = 0.0;
    for (int i = 0; i < 12; ++i) chroma_sum += chroma[i];
    if (chroma_sum > 1e-9) {
        for (int i = 0; i < 12; ++i) chroma[i] /= chroma_sum;
    }

    // Krumhansl-Schmuckler Key Profiles
    const double major_profile[12] = {6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88};
    const double minor_profile[12] = {6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17};

    const char* key_names[12] = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};

    double best_corr = -2.0;
    std::string best_key = "C";

    for (int shift = 0; shift < 12; ++shift) {
        // Test Major Key
        double sum_c = 0.0, sum_p = 0.0, sum_cp = 0.0, sum_c2 = 0.0, sum_p2 = 0.0;
        for (int i = 0; i < 12; ++i) {
            double c = chroma[(i + shift) % 12];
            double p = major_profile[i];
            sum_c += c; sum_p += p;
            sum_cp += c * p;
            sum_c2 += c * c; sum_p2 += p * p;
        }
        double num = 12.0 * sum_cp - sum_c * sum_p;
        double den = std::sqrt((12.0 * sum_c2 - sum_c * sum_c) * (12.0 * sum_p2 - sum_p * sum_p));
        double corr_maj = (den > 1e-9) ? (num / den) : 0.0;

        if (corr_maj > best_corr) {
            best_corr = corr_maj;
            best_key = std::string(key_names[shift]);
        }

        // Test Minor Key
        sum_c = 0.0; sum_p = 0.0; sum_cp = 0.0; sum_c2 = 0.0; sum_p2 = 0.0;
        for (int i = 0; i < 12; ++i) {
            double c = chroma[(i + shift) % 12];
            double p = minor_profile[i];
            sum_c += c; sum_p += p;
            sum_cp += c * p;
            sum_c2 += c * c; sum_p2 += p * p;
        }
        num = 12.0 * sum_cp - sum_c * sum_p;
        den = std::sqrt((12.0 * sum_c2 - sum_c * sum_c) * (12.0 * sum_p2 - sum_p * sum_p));
        double corr_min = (den > 1e-9) ? (num / den) : 0.0;

        if (corr_min > best_corr) {
            best_corr = corr_min;
            best_key = std::string(key_names[shift]) + "m";
        }
    }

    return best_key;
}

