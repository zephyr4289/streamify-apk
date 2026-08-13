#ifndef AUDIO_PIPELINE_H
#define AUDIO_PIPELINE_H

#include <string>
#include <vector>

class AudioPipeline {
public:
    static AudioPipeline& getInstance();

    struct TrackMetadata {
        std::string title;
        std::string artist;
        std::string key;
        float bpm;
    };

    bool init(const std::string& model_path = "");
    std::vector<float> processAudio(const std::string& filepath);
    TrackMetadata extractMetadata(const std::string& filepath);
    float extractBPM(const std::string& filepath);
    std::string extractKey(const std::string& filepath);

private:
    AudioPipeline();
    ~AudioPipeline();
    AudioPipeline(const AudioPipeline&) = delete;
    AudioPipeline& operator=(const AudioPipeline&) = delete;
};

#endif // AUDIO_PIPELINE_H
