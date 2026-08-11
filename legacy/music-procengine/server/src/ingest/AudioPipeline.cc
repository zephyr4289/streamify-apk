#include "AudioPipeline.h"
#include <iostream>
#include <cmath>
#include <fftw3.h>

#define MINIAUDIO_IMPLEMENTATION
#include "miniaudio.h"

AudioPipeline& AudioPipeline::getInstance() {
    static AudioPipeline instance;
    return instance;
}

AudioPipeline::AudioPipeline() : env_(ORT_LOGGING_LEVEL_WARNING, "AudioPipeline") {}

bool AudioPipeline::init(const std::string& onnx_model_path) {
    try {
        Ort::SessionOptions session_options;
        session_options.SetIntraOpNumThreads(1);
        session_options.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_ALL);
        
        session_ = new Ort::Session(env_, onnx_model_path.c_str(), session_options);
        return true;
    } catch (const std::exception& e) {
        std::cerr << "[AudioPipeline] Failed to init ONNX: " << e.what() << std::endl;
        return false;
    }
}

std::vector<float> AudioPipeline::processAudio(const std::string& filepath) {
    // 1. Miniaudio PCM Decoding
    ma_decoder decoder;
    ma_decoder_config config = ma_decoder_config_init(ma_format_f32, 1, 16000);
    
    if (ma_decoder_init_file(filepath.c_str(), &config, &decoder) != MA_SUCCESS) {
        std::cerr << "[AudioPipeline] Failed to open audio: " << filepath << std::endl;
        return std::vector<float>(512, 0.0f);
    }
    
    ma_uint64 total_frames = ma_decoder_get_length_in_pcm_frames(&decoder);
    if (total_frames == 0) total_frames = 16000 * 30; // Approx 30s buffer if length unknown
    std::vector<float> pcm(total_frames);
    ma_uint64 frames_read = 0;
    ma_decoder_read_pcm_frames(&decoder, pcm.data(), total_frames, &frames_read);
    pcm.resize(frames_read);
    ma_decoder_uninit(&decoder);

    if (pcm.empty()) {
        std::cerr << "[AudioPipeline] No PCM frames read from: " << filepath << std::endl;
        return {};
    }

    // 2. FFTW3 DSP Processing (STFT -> Mel-Spectrogram) & ONNX Inference Loop
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
        starts.push_back(0); // Short track, just read from start
    }
    
    double* in = (double*)fftw_malloc(sizeof(double) * nfft);
    fftw_complex* out = (fftw_complex*)fftw_malloc(sizeof(fftw_complex) * (nfft / 2 + 1));
    fftw_plan p = fftw_plan_dft_r2c_1d(nfft, in, out, FFTW_ESTIMATE);
    
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
            fftw_execute(p);
            
            std::vector<double> power(nfft / 2 + 1);
            for (int i = 0; i < nfft / 2 + 1; ++i) {
                power[i] = (out[i][0] * out[i][0] + out[i][1] * out[i][1]);
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
            // Fallback: build embedding from spectral energy distribution when ONNX model is missing
            for (int i = 0; i < 512; ++i) {
                composite_vec[i] += mel_spec[i % mel_spec.size()];
            }
            chunks_processed++;
            continue;
        }
        
        Ort::MemoryInfo memory_info = Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault);
        const char* input_names[] = {"input"};
        const char* output_names[] = {"output"};
        std::vector<int64_t> input_dims = {1, 1, required_frames, num_mels};
        Ort::Value input_tensor_ort = Ort::Value::CreateTensor<float>(
            memory_info, mel_spec.data(), mel_spec.size(),
            input_dims.data(), input_dims.size());
            
        auto output_tensors = session_->Run(
            Ort::RunOptions{nullptr}, input_names, &input_tensor_ort, 1, output_names, 1);
            
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
    }
    
    fftw_destroy_plan(p);
    fftw_free(in);
    fftw_free(out);
    
    if (chunks_processed == 0) {
        std::cerr << "[AudioPipeline] Corrupted audio features detected for track: " << filepath << std::endl;
        return std::vector<float>(); // Return empty vector if failed
    }
    
    // Average and L2 Normalize
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
    
    // Basic filename parsing: "Artist - Title.ext"
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

    // Basic BPM estimation stub using file size to get a deterministic but varied BPM
    FILE* f = fopen(filepath.c_str(), "rb");
    if (f) {
        fseek(f, 0, SEEK_END);
        long size = ftell(f);
        fclose(f);
        
        // Map size deterministically to a BPM range 90-140
        meta.bpm = 90.0 + (size % 500) / 10.0;
        
        // Simple pseudo-random key
        const char* keys[] = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
        meta.key = keys[size % 12];
    }
    
    return meta;
}
