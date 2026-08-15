#ifndef LUFS_NORMALIZER_H
#define LUFS_NORMALIZER_H

#include <vector>
#include <cstdint>

class LufsNormalizer {
public:
    static LufsNormalizer& getInstance();

    void processFloats(float* pcm, int length, float targetLufs = -14.0f);
    void processShorts(int16_t* pcm, int length, float targetLufs = -14.0f);

private:
    LufsNormalizer() = default;
    ~LufsNormalizer() = default;
    LufsNormalizer(const LufsNormalizer&) = delete;
    LufsNormalizer& operator=(const LufsNormalizer&) = delete;
};

#endif // LUFS_NORMALIZER_H
