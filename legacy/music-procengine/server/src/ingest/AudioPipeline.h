#ifndef AUDIO_PIPELINE_H
#define AUDIO_PIPELINE_H

#include <string>
#include <vector>
#include <onnxruntime_cxx_api.h>

class AudioPipeline {
public:
    static AudioPipeline& getInstance();
    
    bool init(const std::string& onnx_model_path);
    std::vector<float> processAudio(const std::string& filepath);

    struct TrackMetadata {
        std::string title;
        std::string artist;
        double bpm;
        std::string key;
    };

    TrackMetadata extractMetadata(const std::string& filepath);

private:
    AudioPipeline();
    ~AudioPipeline() = default;
    AudioPipeline(const AudioPipeline&) = delete;
    AudioPipeline& operator=(const AudioPipeline&) = delete;

    Ort::Env env_;
    Ort::Session* session_{nullptr};
};

#endif // AUDIO_PIPELINE_H
