#include <jni.h>
#include <string>
#include <vector>
#include <ctime>
#include "../engine/StreamifyDB.h"
#include "../engine/TaskOrchestrator.h"
#include "../engine/TelemetryEngine.h"
#include "../engine/ChronosProfiler.h"
#include "../engine/PtpEngine.h"
#include "../engine/AirDropPhysicsEngine.h"
#include "../dsp/LufsNormalizer.h"
#include "../dsp/LyricAligner.h"

// Cached Global JNI References for zero lookup overhead
static jclass g_trackClass = nullptr;
static jmethodID g_trackConstructor = nullptr;
static jclass g_recClass = nullptr;
static jmethodID g_recConstructor = nullptr;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /* reserved */) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    jclass localTrackClass = env->FindClass("com/streamify/app/data/models/TrackNative");
    if (localTrackClass) {
        g_trackClass = reinterpret_cast<jclass>(env->NewGlobalRef(localTrackClass));
        g_trackConstructor = env->GetMethodID(g_trackClass, "<init>", "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;IFLjava/lang/String;ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;)V");
        env->DeleteLocalRef(localTrackClass);
    }
    if (env->ExceptionCheck()) env->ExceptionClear();

    jclass localRecClass = env->FindClass("com/streamify/app/data/models/RecommendationNative");
    if (localRecClass) {
        g_recClass = reinterpret_cast<jclass>(env->NewGlobalRef(localRecClass));
        g_recConstructor = env->GetMethodID(g_recClass, "<init>", "(IFFF)V");
        if (!g_recConstructor) {
            env->ExceptionClear();
            g_recConstructor = env->GetMethodID(g_recClass, "<init>", "(IF)V");
        }
        env->DeleteLocalRef(localRecClass);
    }
    if (env->ExceptionCheck()) env->ExceptionClear();

    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void* /* reserved */) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) {
        if (g_trackClass) env->DeleteGlobalRef(g_trackClass);
        if (g_recClass) env->DeleteGlobalRef(g_recClass);
    }
}

// Helper to convert C++ Track to Java TrackNative using pre-cached descriptors
jobject convertTrack(JNIEnv* env, jclass trackClass, jmethodID constructor, const StreamifyTrack& t) {
    jstring filepath = env->NewStringUTF(t.filepath.c_str());
    jstring title = env->NewStringUTF(t.title.c_str());
    jstring artist = env->NewStringUTF(t.artist.c_str());
    jstring album = env->NewStringUTF(t.album.c_str());
    jstring key = env->NewStringUTF(t.key.c_str());
    jstring coverArtPath = env->NewStringUTF(t.cover_art_path.c_str());
    jstring lyricsPath = env->NewStringUTF(t.lyrics_path.c_str());
    jstring source = env->NewStringUTF(t.source.c_str());
    jstring downloadQuality = env->NewStringUTF(t.download_quality.c_str());

    jobject trackObj = env->NewObject(trackClass, constructor,
        t.id, filepath, title, artist, album,
        t.duration_sec, static_cast<jfloat>(t.bpm), key, t.vector_offset,
        coverArtPath, lyricsPath, source, t.is_processed, downloadQuality);

    env->DeleteLocalRef(filepath);
    env->DeleteLocalRef(title);
    env->DeleteLocalRef(artist);
    env->DeleteLocalRef(album);
    env->DeleteLocalRef(key);
    env->DeleteLocalRef(coverArtPath);
    env->DeleteLocalRef(lyricsPath);
    env->DeleteLocalRef(source);
    env->DeleteLocalRef(downloadQuality);

    return trackObj;
}

jobjectArray convertTrackList(JNIEnv* env, const std::vector<StreamifyTrack>& tracks) {
    jclass trackClass = g_trackClass ? g_trackClass : env->FindClass("com/streamify/app/data/models/TrackNative");
    jmethodID constructor = g_trackConstructor ? g_trackConstructor : env->GetMethodID(trackClass, "<init>", "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;IFLjava/lang/String;ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;)V");
    
    jobjectArray trackArray = env->NewObjectArray(tracks.size(), trackClass, nullptr);
    for (size_t i = 0; i < tracks.size(); ++i) {
        jobject trackObj = convertTrack(env, trackClass, constructor, tracks[i]);
        env->SetObjectArrayElement(trackArray, i, trackObj);
        env->DeleteLocalRef(trackObj);
    }
    return trackArray;
}

#include "../engine/RecommendEngine.h"
#include "../engine/VectorStore.h"

jobjectArray convertRecList(JNIEnv* env, const std::vector<Recommendation>& recs) {
    jclass recClass = g_recClass ? g_recClass : env->FindClass("com/streamify/app/data/models/RecommendationNative");
    jmethodID constructor = g_recConstructor;
    if (!constructor && recClass) {
        constructor = env->GetMethodID(recClass, "<init>", "(IFFF)V");
        if (!constructor) {
            env->ExceptionClear();
            constructor = env->GetMethodID(recClass, "<init>", "(IF)V");
        }
    }
    if (env->ExceptionCheck()) env->ExceptionClear();

    jobjectArray resultArray = env->NewObjectArray(recs.size(), recClass, nullptr);
    for (size_t i = 0; i < recs.size(); ++i) {
        jobject obj = nullptr;
        if (constructor) {
            obj = env->NewObject(recClass, constructor, recs[i].trackId, recs[i].score, recs[i].vectorScore, recs[i].bpmMatchScore);
        }
        if (obj) {
            env->SetObjectArrayElement(resultArray, i, obj);
            env->DeleteLocalRef(obj);
        }
    }
    return resultArray;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_streamify_app_data_NativeBridge_stringFromJNI(JNIEnv* env, jobject /* this */) {
    return env->NewStringUTF("Streamify C++ Core Initialized");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_streamify_app_data_NativeBridge_initDatabase(JNIEnv* env, jobject /* this */, jstring dbPath) {
    const char* path = env->GetStringUTFChars(dbPath, 0);
    bool success = StreamifyDB::getInstance().init(path);
    env->ReleaseStringUTFChars(dbPath, path);
    return success;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_streamify_app_data_NativeBridge_getAllTracks(JNIEnv* env, jobject /* this */) {
    std::vector<StreamifyTrack> tracks = StreamifyDB::getInstance().getAllTracks();
    return convertTrackList(env, tracks);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_streamify_app_data_NativeBridge_searchTracks(JNIEnv* env, jobject /* this */, jstring query) {
    const char* q = env->GetStringUTFChars(query, 0);
    std::vector<StreamifyTrack> tracks = StreamifyDB::getInstance().searchTracks(q);
    env->ReleaseStringUTFChars(query, q);
    return convertTrackList(env, tracks);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_streamify_app_data_NativeBridge_insertTrack(JNIEnv* env, jobject /* this */,
    jstring filepath, jstring title, jstring artist, jstring album, jint durationSec, jfloat bpm) {
    const char* cFilepath = env->GetStringUTFChars(filepath, 0);
    const char* cTitle = env->GetStringUTFChars(title, 0);
    const char* cArtist = env->GetStringUTFChars(artist, 0);
    const char* cAlbum = env->GetStringUTFChars(album, 0);

    int id = StreamifyDB::getInstance().insertTrack(cFilepath, cTitle, cArtist, cAlbum, durationSec, static_cast<double>(bpm));

    env->ReleaseStringUTFChars(filepath, cFilepath);
    env->ReleaseStringUTFChars(title, cTitle);
    env->ReleaseStringUTFChars(artist, cArtist);
    env->ReleaseStringUTFChars(album, cAlbum);
    return id;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_streamify_app_data_NativeBridge_toggleLike(JNIEnv* env, jobject /* this */, jint userId, jint trackId) {
    bool isLiked = false;
    StreamifyDB::getInstance().toggleUserLikedTrack(userId, trackId, isLiked);
    return isLiked;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_streamify_app_data_NativeBridge_getLikedTracks(JNIEnv* env, jobject /* this */, jint userId) {
    std::vector<StreamifyTrack> tracks = StreamifyDB::getInstance().getUserLikedTracks(userId);
    return convertTrackList(env, tracks);
}

#include "../engine/VectorStore.h"

extern "C" JNIEXPORT jboolean JNICALL
Java_com_streamify_app_data_NativeBridge_initVectorStore(JNIEnv* env, jobject /* this */, jstring binPath) {
    const char* path = env->GetStringUTFChars(binPath, 0);
    bool success = VectorStore::getInstance().init(path);
    env->ReleaseStringUTFChars(binPath, path);
    return success;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_streamify_app_data_NativeBridge_searchSimilarTracks(JNIEnv* env, jobject /* this */, jint trackId, jint topK) {
    std::optional<StreamifyTrack> track = StreamifyDB::getInstance().getTrackById(trackId);
    if (!track.has_value() || track->vector_offset < 0) {
        return env->NewIntArray(0);
    }

    std::vector<SearchResult> results = VectorStore::getInstance().searchNearest(track->vector_offset, topK);
    
    // We only need the track IDs which correspond to the vector offsets (assuming 1:1 mapping in simple case, or we query DB).
    // In legacy, vector_offset == track_id - 1 usually, but here we can just query the DB for tracks with these vector_offsets.
    // For simplicity, let's return the vector_offsets as track IDs directly (assuming vector_offset = trackId).
    // Actually, StreamifyDB stores vector_offset. We should return an array of offsets.
    jintArray resultArray = env->NewIntArray(results.size());
    if (results.size() > 0) {
        jint* elements = env->GetIntArrayElements(resultArray, 0);
        for (size_t i = 0; i < results.size(); ++i) {
            elements[i] = results[i].vector_offset; 
        }
        env->ReleaseIntArrayElements(resultArray, elements, 0);
    }
    return resultArray;
}

#include "../engine/RecommendEngine.h"
#include "../engine/EventTracker.h"

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_streamify_app_data_NativeBridge_getRecommendations(JNIEnv* env, jobject /* this */, jint trackId, jintArray recentHistory, jint userId, jint limit) {
    jsize len = env->GetArrayLength(recentHistory);
    jint* historyElements = env->GetIntArrayElements(recentHistory, 0);
    std::vector<int> history(historyElements, historyElements + len);
    env->ReleaseIntArrayElements(recentHistory, historyElements, 0);

    std::vector<Recommendation> recs = RecommendEngine::getInstance().getNextTracks(trackId, history, limit);
    return convertRecList(env, recs);
}

extern "C" JNIEXPORT void JNICALL
Java_com_streamify_app_data_NativeBridge_logPlayEvent(JNIEnv* env, jobject /* this */, jint fromTrackId, jint toTrackId, jint userId) {
    EventTracker::getInstance().logPlay(fromTrackId, toTrackId, userId);
}

extern "C" JNIEXPORT void JNICALL
Java_com_streamify_app_data_NativeBridge_logSkipEvent(JNIEnv* env, jobject /* this */, jint fromTrackId, jint toTrackId, jint userId) {
    EventTracker::getInstance().logSkip(fromTrackId, toTrackId, userId);
}

#include "../ingest/AudioPipeline.h"

extern "C" JNIEXPORT jboolean JNICALL
Java_com_streamify_app_data_NativeBridge_initAudioPipeline(JNIEnv* env, jobject /* this */, jstring modelPath) {
    const char* path = env->GetStringUTFChars(modelPath, 0);
    bool success = AudioPipeline::getInstance().init(path);
    env->ReleaseStringUTFChars(modelPath, path);
    return success;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_streamify_app_data_NativeBridge_processAudioFile(JNIEnv* env, jobject /* this */, jint trackId, jstring filePath) {
    const char* path = env->GetStringUTFChars(filePath, 0);
    std::string trackPathStr(path);
    TaskOrchestrator::getInstance().notifyAiTaskStarted(trackPathStr);
    
    std::vector<float> vec = AudioPipeline::getInstance().processAudio(path);
    
    if (!vec.empty() && trackId > 0) {
        float bpm = AudioPipeline::getInstance().extractBPM(path);
        std::string key = AudioPipeline::getInstance().extractKey(path);
        StreamifyDB::getInstance().updateTrackBPM(trackId, bpm);
        StreamifyDB::getInstance().updateTrackKey(trackId, key);
    }
    
    env->ReleaseStringUTFChars(filePath, path);
    TaskOrchestrator::getInstance().notifyAiTaskCompleted();

    if (vec.empty()) return -1;

    int offset = VectorStore::getInstance().addVector(vec);
    if (offset >= 0 && trackId > 0) {
        StreamifyDB::getInstance().updateTrackVectorOffset(trackId, offset);
    }
    return offset;
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_streamify_app_data_NativeBridge_extractBPM(JNIEnv* env, jobject /* this */, jint trackId, jstring filePath) {
    const char* path = env->GetStringUTFChars(filePath, 0);
    float bpm = AudioPipeline::getInstance().extractBPM(path);
    if (trackId > 0) {
        StreamifyDB::getInstance().updateTrackBPM(trackId, bpm);
    }
    env->ReleaseStringUTFChars(filePath, path);
    return bpm;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_streamify_app_data_NativeBridge_updateTrackCoverArt(JNIEnv* env, jobject /* this */, jint trackId, jstring coverArtPath) {
    const char* path = env->GetStringUTFChars(coverArtPath, 0);
    bool res = StreamifyDB::getInstance().updateTrackCoverArt(trackId, path);
    env->ReleaseStringUTFChars(coverArtPath, path);
    return res;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_streamify_app_data_NativeBridge_updateTrackMetadata(JNIEnv* env, jobject /* this */, jint trackId, jstring title, jstring artist, jstring album) {
    const char* cTitle = env->GetStringUTFChars(title, 0);
    const char* cArtist = env->GetStringUTFChars(artist, 0);
    const char* cAlbum = env->GetStringUTFChars(album, 0);
    
    bool res = StreamifyDB::getInstance().updateTrackMetadata(trackId, cTitle, cArtist, cAlbum);
    
    env->ReleaseStringUTFChars(title, cTitle);
    env->ReleaseStringUTFChars(artist, cArtist);
    env->ReleaseStringUTFChars(album, cAlbum);
    
    return res;
}

extern "C" JNIEXPORT void JNICALL
Java_com_streamify_app_data_NativeBridge_setHighPriorityActive(JNIEnv* /* env */, jobject /* this */, jboolean active) {
    TaskOrchestrator::getInstance().setHighPriorityActive(active);
}

extern "C" JNIEXPORT void JNICALL
Java_com_streamify_app_data_NativeBridge_setTotalAiTasks(JNIEnv* /* env */, jobject /* this */, jint total) {
    TaskOrchestrator::getInstance().setTotalAiTasks(total);
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_streamify_app_data_NativeBridge_getOrchestratorStatus(JNIEnv* env, jobject /* this */) {
    OrchestratorMetrics m = TaskOrchestrator::getInstance().getMetrics();
    
    jclass statusClass = env->FindClass("com/streamify/app/data/models/OrchestratorStatusNative");
    if (!statusClass) return nullptr;
    
    jmethodID constructor = env->GetMethodID(statusClass, "<init>", "(Ljava/lang/String;Ljava/lang/String;IIIIIZIZZ)V");
    if (!constructor) return nullptr;
    
    jstring stateStr = env->NewStringUTF(m.state.c_str());
    jstring actionStr = env->NewStringUTF(m.currentAction.c_str());
    
    jobject obj = env->NewObject(statusClass, constructor,
        stateStr, actionStr, m.activeAiTasks, m.completedAiTasks, m.totalAiTasks,
        m.cpuCoreBudget, m.activeThreads, m.isThrottled,
        m.cpuTemp, m.isThermallyThrottled, m.isBatterySaver);
        
    env->DeleteLocalRef(stateStr);
    env->DeleteLocalRef(actionStr);
    return obj;
}

extern "C" JNIEXPORT void JNICALL
Java_com_streamify_app_data_NativeBridge_setBatterySaverActive(JNIEnv* /* env */, jobject /* this */, jboolean active) {
    TaskOrchestrator::getInstance().setBatterySaverActive(active);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_streamify_app_data_NativeBridge_upsertStreamedTrack(JNIEnv* env, jobject /* this */,
    jstring filepath, jstring title, jstring artist, jstring album, jint durationSec,
    jstring coverArtPath, jstring lyricsPath, jfloat bpm, jstring key) {
    
    const char* cFilepath = env->GetStringUTFChars(filepath, 0);
    const char* cTitle = env->GetStringUTFChars(title, 0);
    const char* cArtist = env->GetStringUTFChars(artist, 0);
    const char* cAlbum = env->GetStringUTFChars(album, 0);
    const char* cCover = env->GetStringUTFChars(coverArtPath, 0);
    const char* cLyrics = env->GetStringUTFChars(lyricsPath, 0);
    const char* cKey = env->GetStringUTFChars(key, 0);

    int id = StreamifyDB::getInstance().upsertStreamedTrack(
        cFilepath, cTitle, cArtist, cAlbum, durationSec, cCover, cLyrics, static_cast<double>(bpm), cKey
    );

    env->ReleaseStringUTFChars(filepath, cFilepath);
    env->ReleaseStringUTFChars(title, cTitle);
    env->ReleaseStringUTFChars(artist, cArtist);
    env->ReleaseStringUTFChars(album, cAlbum);
    env->ReleaseStringUTFChars(coverArtPath, cCover);
    env->ReleaseStringUTFChars(lyricsPath, cLyrics);
    env->ReleaseStringUTFChars(key, cKey);

    return id;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_streamify_app_data_NativeBridge_recordTrackPlay(JNIEnv* /* env */, jobject /* this */, jint trackId) {
    return StreamifyDB::getInstance().recordTrackPlay(trackId);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_streamify_app_data_NativeBridge_getTopPlayedTracks(JNIEnv* env, jobject /* this */, jint limit) {
    std::vector<StreamifyTrack> tracks = StreamifyDB::getInstance().getTopPlayedTracks(limit);
    return convertTrackList(env, tracks);
}

extern "C" JNIEXPORT void JNICALL
Java_com_streamify_app_data_NativeBridge_updateSessionVector(JNIEnv* /* env */, jobject /* this */, jint trackId, jfloat alpha) {
    RecommendEngine::getInstance().updateSessionVector(trackId, alpha);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_streamify_app_data_NativeBridge_getSessionRecommendations(JNIEnv* env, jobject /* this */, jint limit) {
    std::vector<Recommendation> recs = RecommendEngine::getInstance().getSessionRecommendations(limit);
    return convertRecList(env, recs);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_streamify_app_data_NativeBridge_getLongTermRecommendations(JNIEnv* env, jobject /* this */, jint userId, jint limit) {
    std::vector<Recommendation> recs = RecommendEngine::getInstance().getLongTermRecommendations(userId, limit);
    return convertRecList(env, recs);
}

#include "../dsp/SoftKneeLimiter.h"

static streamify::dsp::SoftKneeLimiter g_limiter(0.90f, 0.15f);

extern "C" JNIEXPORT void JNICALL
Java_com_streamify_app_data_NativeBridge_processLimiterShorts(JNIEnv* env, jobject /* this */, jshortArray buffer, jint length, jfloat threshold, jfloat kneeWidth) {
    if (!buffer || length <= 0) return;
    g_limiter.setParameters(threshold, kneeWidth);
    jshort* pcm = env->GetShortArrayElements(buffer, nullptr);
    if (pcm) {
        g_limiter.processShorts(pcm, length);
        env->ReleaseShortArrayElements(buffer, pcm, 0);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_streamify_app_data_NativeBridge_processLimiterFloats(JNIEnv* env, jobject /* this */, jfloatArray buffer, jint length, jfloat threshold, jfloat kneeWidth) {
    if (!buffer || length <= 0) return;
    g_limiter.setParameters(threshold, kneeWidth);
    jfloat* pcm = env->GetFloatArrayElements(buffer, nullptr);
    if (pcm) {
        g_limiter.processFloats(pcm, length);
        env->ReleaseFloatArrayElements(buffer, pcm, 0);
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_streamify_app_data_NativeBridge_findFuzzyTrackMatch(JNIEnv* env, jobject /* this */, jstring title, jstring artist) {
    const char* cTitle = env->GetStringUTFChars(title, 0);
    const char* cArtist = env->GetStringUTFChars(artist, 0);

    int id = StreamifyDB::getInstance().findFuzzyTrackMatch(cTitle, cArtist);

    env->ReleaseStringUTFChars(title, cTitle);
    env->ReleaseStringUTFChars(artist, cArtist);
    return id;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_streamify_app_data_NativeBridge_getTracksBatch(JNIEnv* env, jobject /* this */, jint offset, jint limit) {
    std::vector<StreamifyTrack> tracks = StreamifyDB::getInstance().getTracksBatch(offset, limit);
    return convertTrackList(env, tracks);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_streamify_app_data_NativeBridge_logEngagementEvent(JNIEnv* /* env */, jobject /* this */, jint trackId, jint durationSec, jfloat completionRatio, jint hourOfDay) {
    return StreamifyDB::getInstance().logEngagement(trackId, durationSec, completionRatio, hourOfDay);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_streamify_app_data_NativeBridge_getCircadianRecommendations(JNIEnv* env, jobject /* this */, jint hourOfDay, jint limit) {
    std::vector<Recommendation> recs = RecommendEngine::getInstance().getCircadianRecommendations(hourOfDay, limit);
    return convertRecList(env, recs);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_streamify_app_data_NativeBridge_getCircadianSlot(JNIEnv* env, jobject /* this */, jint hourOfDay) {
    std::string slot = StreamifyDB::getInstance().getCircadianSlot(hourOfDay);
    return env->NewStringUTF(slot.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_streamify_app_data_NativeBridge_logHookTelemetry(JNIEnv* /* env */, jobject /* this */, jint trackId, jlong favoriteSeekMs, jint lyricsDwellSec, jint volumeFlare) {
    return StreamifyDB::getInstance().logHookTelemetry(trackId, favoriteSeekMs, lyricsDwellSec, volumeFlare);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_streamify_app_data_NativeBridge_recordTrackCooccurrence(JNIEnv* /* env */, jobject /* this */, jint trackAId, jint trackBId) {
    return StreamifyDB::getInstance().recordTrackCooccurrence(trackAId, trackBId);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_streamify_app_data_NativeBridge_getFavoriteSeekMs(JNIEnv* /* env */, jobject /* this */, jint trackId) {
    return StreamifyDB::getInstance().getFavoriteSeekMs(trackId);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_streamify_app_data_NativeBridge_getCooccurrenceRecommendations(JNIEnv* env, jobject /* this */, jint trackId, jint limit) {
    std::vector<int> candidates = StreamifyDB::getInstance().getCooccurrenceCandidates(trackId, limit);
    jintArray result = env->NewIntArray(candidates.size());
    if (result && !candidates.empty()) {
        env->SetIntArrayRegion(result, 0, candidates.size(), candidates.data());
    }
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_streamify_app_data_NativeBridge_pushTelemetryEvent(JNIEnv* /* env */, jobject /* this */, jint type, jlong trackId, jfloat value) {
    TelemetryEngine::getInstance().pushEvent(static_cast<TelemetryEventType>(type), trackId, value);
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_streamify_app_data_NativeBridge_getMarkovProbability(JNIEnv* /* env */, jobject /* this */, jint fromTrackId, jint toTrackId) {
    return StreamifyDB::getInstance().getMarkovProbability(fromTrackId, toTrackId);
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_streamify_app_data_NativeBridge_getSatiationPenalty(JNIEnv* /* env */, jobject /* this */, jint trackId) {
    return ChronosProfiler::getInstance().calculateSatiationPenalty(trackId, std::time(nullptr) * 1000LL);
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_streamify_app_data_NativeBridge_get2ndOrderMarkovProbability(JNIEnv* /* env */, jobject /* this */, jint trackA, jint trackB, jint trackC, jfloat alpha) {
    return StreamifyDB::getInstance().get2ndOrderMarkovProbability(trackA, trackB, trackC, alpha);
}

extern "C" JNIEXPORT void JNICALL
Java_com_streamify_app_data_NativeBridge_processLufsNormalizerFloats(JNIEnv* env, jobject /* this */, jfloatArray buffer, jint length, jfloat targetLufs) {
    if (!buffer || length <= 0) return;
    jfloat* pcm = env->GetFloatArrayElements(buffer, nullptr);
    if (pcm) {
        LufsNormalizer::getInstance().processFloats(pcm, length, targetLufs);
        env->ReleaseFloatArrayElements(buffer, pcm, 0);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_streamify_app_data_NativeBridge_processLufsNormalizerShorts(JNIEnv* env, jobject /* this */, jshortArray buffer, jint length, jfloat targetLufs) {
    if (!buffer || length <= 0) return;
    jshort* pcm = env->GetShortArrayElements(buffer, nullptr);
    if (pcm) {
        LufsNormalizer::getInstance().processShorts(pcm, length, targetLufs);
        env->ReleaseShortArrayElements(buffer, pcm, 0);
    }
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_streamify_app_data_NativeBridge_getDynamicTargetLufs(JNIEnv* /* env */, jobject /* this */) {
    return TelemetryEngine::getInstance().getDynamicTargetLufs();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_streamify_app_data_NativeBridge_generateProofOfCompute(JNIEnv* env, jobject /* this */, jfloatArray buffer, jint length, jstring nonce) {
    if (!buffer || length <= 0) return env->NewStringUTF("");
    jfloat* pcm = env->GetFloatArrayElements(buffer, nullptr);
    if (!pcm) return env->NewStringUTF("");

    const char* nonceStr = env->GetStringUTFChars(nonce, nullptr);
    std::string nonceCpp(nonceStr ? nonceStr : "");
    if (nonceStr) env->ReleaseStringUTFChars(nonce, nonceStr);

    std::string proof = TelemetryEngine::getInstance().generateProofOfCompute(pcm, length, nonceCpp);
    env->ReleaseFloatArrayElements(buffer, pcm, 0);
    return env->NewStringUTF(proof.c_str());
}

// ═══════════════════════════════════════════════════════════════
// HYBRID ASYMMETRIC RECOMMENDATION ENGINE JNI EXPORTS
// ═══════════════════════════════════════════════════════════════

extern "C" JNIEXPORT jfloat JNICALL
Java_com_streamify_app_data_NativeBridge_getTargetBpmForTimeSlot(JNIEnv* /* env */, jobject /* this */, jint slotOrdinal) {
    return RecommendEngine::getInstance().getTargetBpmForTimeSlot(slotOrdinal);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_streamify_app_data_NativeBridge_getVectorRecommendations(
    JNIEnv* env,
    jobject /* this */,
    jint currentTrackId,
    jfloat timeWeight,
    jfloat deviceWeight,
    jfloat bpmTarget,
    jint limit
) {
    auto& db = StreamifyDB::getInstance();
    auto& recEngine = RecommendEngine::getInstance();
    auto& vecStore = VectorStore::getInstance();

    std::vector<float> baseVec = db.getTrackEmbedding(currentTrackId);
    if (baseVec.empty()) {
        auto optTrack = db.getTrackById(currentTrackId);
        if (optTrack && optTrack->vector_offset >= 0) {
            baseVec = vecStore.getVectorAt(optTrack->vector_offset);
        }
    }

    if (baseVec.empty()) {
        auto all = db.getAllTracks();
        std::vector<Recommendation> fallback;
        for (const auto& t : all) {
            if (t.id != currentTrackId) {
                Recommendation r;
                r.trackId = t.id;
                r.score = 1.0f;
                r.vectorScore = 0.5f;
                r.bpmMatchScore = 0.5f;
                fallback.push_back(r);
                if (fallback.size() >= static_cast<size_t>(limit)) break;
            }
        }
        return convertRecList(env, fallback);
    }

    // 1. Compute Contextual Vector
    std::vector<float> ctxVec = recEngine.computeContextualVector(baseVec, timeWeight, deviceWeight);

    // 2. K-Means: Find 2 closest clusters
    std::vector<int> topClusters = recEngine.findClosestClusters(ctxVec, 2);

    // 3. Get candidate tracks in those clusters
    std::vector<int> candidates = db.getTracksInClusters(topClusters, 100);
    if (candidates.size() < 10) {
        auto all = db.getAllTracks();
        for (const auto& t : all) {
            if (t.id != currentTrackId) candidates.push_back(t.id);
        }
    }

    // 4. NEON SIMD Ranking + Ellis-Gaussian BPM + Satiation
    auto scoredRecs = recEngine.rankHybridCandidates(ctxVec, candidates, bpmTarget, 0.20f, limit);
    return convertRecList(env, scoredRecs);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_streamify_app_data_NativeBridge_updateTrackEmbedding(JNIEnv* env, jobject /* this */, jint trackId, jfloatArray embedding) {
    if (!embedding) return JNI_FALSE;
    jsize len = env->GetArrayLength(embedding);
    if (len < 512) return JNI_FALSE;

    jfloat* ptr = env->GetFloatArrayElements(embedding, nullptr);
    if (!ptr) return JNI_FALSE;

    bool ok = StreamifyDB::getInstance().updateTrackEmbedding(trackId, ptr);
    env->ReleaseFloatArrayElements(embedding, ptr, 0);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_streamify_app_data_NativeBridge_getTrackEmbedding(JNIEnv* env, jobject /* this */, jint trackId) {
    std::vector<float> vec = StreamifyDB::getInstance().getTrackEmbedding(trackId);
    if (vec.size() < 512) return nullptr;

    jfloatArray result = env->NewFloatArray(512);
    env->SetFloatArrayRegion(result, 0, 512, vec.data());
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_streamify_app_data_NativeBridge_cacheSimilarTracks(
    JNIEnv* env,
    jobject /* this */,
    jint trackId,
    jobjectArray titles,
    jobjectArray artists,
    jobjectArray mbids,
    jfloatArray weights
) {
    if (!titles || !artists || !weights) return JNI_FALSE;
    jsize count = env->GetArrayLength(titles);
    if (count <= 0) return JNI_FALSE;

    std::vector<std::string> titleVec(count);
    std::vector<std::string> artistVec(count);
    std::vector<std::string> mbidVec(count);
    std::vector<float> weightVec(count);

    jfloat* wPtr = env->GetFloatArrayElements(weights, nullptr);
    for (jsize i = 0; i < count; ++i) {
        jstring tStr = static_cast<jstring>(env->GetObjectArrayElement(titles, i));
        jstring aStr = static_cast<jstring>(env->GetObjectArrayElement(artists, i));
        jstring mStr = mbids ? static_cast<jstring>(env->GetObjectArrayElement(mbids, i)) : nullptr;

        const char* tChars = tStr ? env->GetStringUTFChars(tStr, nullptr) : "";
        const char* aChars = aStr ? env->GetStringUTFChars(aStr, nullptr) : "";
        const char* mChars = mStr ? env->GetStringUTFChars(mStr, nullptr) : "";

        titleVec[i] = tChars ? tChars : "";
        artistVec[i] = aChars ? aChars : "";
        mbidVec[i] = mChars ? mChars : "";
        weightVec[i] = wPtr ? wPtr[i] : 0.0f;

        if (tStr && tChars) env->ReleaseStringUTFChars(tStr, tChars);
        if (aStr && aChars) env->ReleaseStringUTFChars(aStr, aChars);
        if (mStr && mChars) env->ReleaseStringUTFChars(mStr, mChars);

        if (tStr) env->DeleteLocalRef(tStr);
        if (aStr) env->DeleteLocalRef(aStr);
        if (mStr) env->DeleteLocalRef(mStr);
    }
    if (wPtr) env->ReleaseFloatArrayElements(weights, wPtr, 0);

    bool ok = StreamifyDB::getInstance().cacheSimilarTracks(trackId, titleVec, artistVec, mbidVec, weightVec);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// Project Pulse: Sub-15ms Precision Time Protocol (IEEE 1588)
extern "C" JNIEXPORT jlong JNICALL
Java_com_streamify_app_data_NativeBridge_processPtpTimestamps(
    JNIEnv* /* env */,
    jobject /* this */,
    jlong t0,
    jlong t1,
    jlong t2,
    jlong t3
) {
    return static_cast<jlong>(streamify::PtpEngine::getInstance().processTimestamps(t0, t1, t2, t3));
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_streamify_app_data_NativeBridge_getSynchronizedClockMs(
    JNIEnv* /* env */,
    jobject /* this */
) {
    return static_cast<jlong>(streamify::PtpEngine::getInstance().getSynchronizedClockMs());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_streamify_app_data_NativeBridge_getPtpClockOffsetNanos(
    JNIEnv* /* env */,
    jobject /* this */
) {
    return static_cast<jlong>(streamify::PtpEngine::getInstance().getClockOffsetNanos());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_streamify_app_data_NativeBridge_getPtpRttNanos(
    JNIEnv* /* env */,
    jobject /* this */
) {
    return static_cast<jlong>(streamify::PtpEngine::getInstance().getLastRttNanos());
}

extern "C" JNIEXPORT void JNICALL
Java_com_streamify_app_data_NativeBridge_resetPtpState(
    JNIEnv* /* env */,
    jobject /* this */
) {
    streamify::PtpEngine::getInstance().reset();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_streamify_app_data_NativeBridge_getZhipuKey(
    JNIEnv* env,
    jobject /* this */,
    jint index
) {
    static const char* ZHIPU_KEYS[] = {
        "57bd4b727f404046b17204dc95a657e8.IMJI6yrCLcZDBl1y",
        "0758ad943a784d728f17cd5d98b5330d.sn0EnMWZ1kLCleir",
        "29a71f29e0bf45cbb0e61ae1fcdb0127.BbgwmouOPz7JQWyp",
        "2f444b74e35c4d7ebae62471309b8b9e.5OzYzb9uP9v0uNzz",
        "85aa0d0ac2f845579dfc58ae355d855d.yQrUOUVlGG0Xe2q2"
    };
    int safeIdx = (index >= 0 && index < 5) ? index : (abs(index) % 5);
    return env->NewStringUTF(ZHIPU_KEYS[safeIdx]);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_streamify_app_data_NativeBridge_nukeLocalDatabase(JNIEnv* env, jobject /* this */) {
    bool success = StreamifyDB::getInstance().nukeDatabase();
    return success ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_streamify_app_data_NativeBridge_stepAirDropPhysics(
    JNIEnv* env,
    jobject /* this */,
    jfloatArray inOutBuffer,
    jfloat targetX,
    jfloat targetY,
    jfloat initialDist,
    jfloat dt
) {
    if (!inOutBuffer) return;
    jsize len = env->GetArrayLength(inOutBuffer);
    if (len < 13) return;

    jfloat* buf = static_cast<jfloat*>(env->GetPrimitiveArrayCritical(inOutBuffer, nullptr));
    if (!buf) return;

    streamify::AirDropState state;
    state.x = buf[0];
    state.y = buf[1];
    state.z = buf[2];
    state.vx = buf[3];
    state.vy = buf[4];
    state.vz = buf[5];
    state.stretch_parallel = buf[6];
    state.stretch_perp = buf[7];
    state.rotation_rad = buf[8];
    state.pitch_deg = buf[9];
    state.roll_deg = buf[10];
    state.impact_progress = buf[11];
    state.is_docked = (buf[12] > 0.5f);
    state.is_ready_to_dock = (len >= 14) ? (buf[13] > 0.5f) : true;

    streamify::TargetDock target;
    target.x = targetX;
    target.y = targetY;
    target.initial_dist = initialDist;

    streamify::AirDropPhysicsEngine::stepRK4(state, target, dt);

    buf[0] = state.x;
    buf[1] = state.y;
    buf[2] = state.z;
    buf[3] = state.vx;
    buf[4] = state.vy;
    buf[5] = state.vz;
    buf[6] = state.stretch_parallel;
    buf[7] = state.stretch_perp;
    buf[8] = state.rotation_rad;
    buf[9] = state.pitch_deg;
    buf[10] = state.roll_deg;
    buf[11] = state.impact_progress;
    buf[12] = state.is_docked ? 1.0f : 0.0f;
    if (len >= 14) {
        buf[13] = state.is_ready_to_dock ? 1.0f : 0.0f;
    }

    env->ReleasePrimitiveArrayCritical(inOutBuffer, buf, 0);
}

// ═══════════════════════════════════════════════════════════════
// PROJECT NEXUS: CLOSED-LOOP EDGE MESH COMPUTE JNI EXPORTS
// ═══════════════════════════════════════════════════════════════

extern "C" JNIEXPORT void JNICALL
Java_com_streamify_app_data_NativeBridge_pinToLittleCores(JNIEnv* /* env */, jobject /* this */) {
    TaskOrchestrator::getInstance().pinThreadToLittleCores();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_streamify_app_data_NativeBridge_analyzePcmAcousticDNA(
    JNIEnv* env,
    jobject /* this */,
    jobject directByteBuffer,
    jint byteCount,
    jint sampleRate,
    jint channelCount,
    jfloatArray outResults
) {
    if (!directByteBuffer || byteCount <= 0 || !outResults) {
        return env->NewStringUTF("8B");
    }

    void* rawPtr = env->GetDirectBufferAddress(directByteBuffer);
    if (!rawPtr) {
        return env->NewStringUTF("8B");
    }

    jfloat* results = env->GetFloatArrayElements(outResults, nullptr);
    if (!results) {
        return env->NewStringUTF("8B");
    }

    int numFloats = byteCount / sizeof(float);
    const float* pcm = reinterpret_cast<const float*>(rawPtr);

    // If stereo or multi-channel, downmix to mono in temporary stack/heap buffer
    std::vector<float> monoPcm;
    if (channelCount > 1 && numFloats > 0) {
        int monoFrames = numFloats / channelCount;
        monoPcm.resize(monoFrames);
        for (int i = 0; i < monoFrames; ++i) {
            float sum = 0.0f;
            for (int ch = 0; ch < channelCount; ++ch) {
                sum += pcm[i * channelCount + ch];
            }
            monoPcm[i] = sum / static_cast<float>(channelCount);
        }
        pcm = monoPcm.data();
        numFloats = monoFrames;
    }

    std::string camelotKey = AudioPipeline::getInstance().extractAcousticDNAFromPcm(
        pcm,
        numFloats,
        sampleRate > 0 ? sampleRate : 44100,
        results
    );

    env->ReleaseFloatArrayElements(outResults, results, 0);
    return env->NewStringUTF(camelotKey.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_streamify_app_data_NativeBridge_calculateLyricDrift(
    JNIEnv* env,
    jobject /* this */,
    jobject directPcmBuffer,
    jint pcmByteCount,
    jlongArray textOnsetsMs,
    jint onsetCount,
    jint sampleRate,
    jint channelCount
) {
    if (!directPcmBuffer || pcmByteCount <= 0 || !textOnsetsMs || onsetCount <= 0) {
        return 0;
    }

    void* rawPcm = env->GetDirectBufferAddress(directPcmBuffer);
    if (!rawPcm) {
        return 0;
    }

    jlong* onsets = env->GetLongArrayElements(textOnsetsMs, nullptr);
    if (!onsets) {
        return 0;
    }

    std::vector<uint32_t> onsetsU32(onsetCount);
    for (int i = 0; i < onsetCount; ++i) {
        onsetsU32[i] = static_cast<uint32_t>(std::max<jlong>(0, onsets[i]));
    }
    env->ReleaseLongArrayElements(textOnsetsMs, onsets, JNI_ABORT);

    int numFloats = pcmByteCount / sizeof(float);
    const float* pcm = reinterpret_cast<const float*>(rawPcm);

    int32_t drift = LyricAligner::getInstance().calculateDriftMs(
        pcm,
        numFloats,
        sampleRate > 0 ? sampleRate : 44100,
        channelCount > 0 ? channelCount : 2,
        onsetsU32.data(),
        onsetCount
    );

    return drift;
}

// ═══════════════════════════════════════════════════════════════
// PROJECT TITAN: HIGH-PERFORMANCE RUST CORE ENGINE JNI EXPORTS
// ═══════════════════════════════════════════════════════════════

#include <dlfcn.h>

static void* get_rust_core_handle() {
    static void* handle = nullptr;
    static bool initialized = false;
    if (!initialized) {
        handle = dlopen("libstreamify_core_rs.so", RTLD_NOW | RTLD_GLOBAL);
        if (!handle) {
            handle = dlopen("libstreamify_core.so", RTLD_NOW | RTLD_GLOBAL);
        }
        initialized = true;
    }
    return handle;
}

template <typename Func>
static Func get_rust_symbol(const char* name) {
    void* h = get_rust_core_handle();
    if (!h) return nullptr;
    return reinterpret_cast<Func>(dlsym(h, name));
}

typedef void (*RustFreeStringFn)(char*);

extern "C" JNIEXPORT jstring JNICALL
Java_com_streamify_app_data_NativeBridge_rustFuzzyRankCandidates(
    JNIEnv* env,
    jobject /* this */,
    jstring query,
    jstring candidatesJson
) {
    if (!query || !candidatesJson) return nullptr;
    typedef char* (*RustFn)(const char*, const char*);
    auto fn = get_rust_symbol<RustFn>("rust_fuzzy_rank_candidates");
    if (!fn) return nullptr;

    const char* q = env->GetStringUTFChars(query, nullptr);
    const char* c = env->GetStringUTFChars(candidatesJson, nullptr);

    char* res = fn(q, c);

    env->ReleaseStringUTFChars(query, q);
    env->ReleaseStringUTFChars(candidatesJson, c);

    if (!res) return nullptr;
    jstring outStr = env->NewStringUTF(res);
    auto free_fn = get_rust_symbol<RustFreeStringFn>("rust_free_string");
    if (free_fn) free_fn(res);
    return outStr;
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_streamify_app_data_NativeBridge_rustCalculateSimilarity(
    JNIEnv* env,
    jobject /* this */,
    jstring s1,
    jstring s2
) {
    if (!s1 || !s2) return 0.0f;
    typedef float (*RustFn)(const char*, const char*);
    auto fn = get_rust_symbol<RustFn>("rust_calculate_string_similarity");
    if (!fn) return 0.0f;

    const char* c1 = env->GetStringUTFChars(s1, nullptr);
    const char* c2 = env->GetStringUTFChars(s2, nullptr);

    float sim = fn(c1, c2);

    env->ReleaseStringUTFChars(s1, c1);
    env->ReleaseStringUTFChars(s2, c2);
    return sim;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_streamify_app_data_NativeBridge_rustParseYouTubePlaylist(
    JNIEnv* env,
    jobject /* this */,
    jbyteArray jsonBytes
) {
    if (!jsonBytes) return nullptr;
    jsize len = env->GetArrayLength(jsonBytes);
    if (len <= 0) return nullptr;

    typedef char* (*RustFn)(const uint8_t*, size_t);
    auto fn = get_rust_symbol<RustFn>("rust_parse_youtube_playlist");
    if (!fn) return nullptr;

    jbyte* bytes = env->GetByteArrayElements(jsonBytes, nullptr);
    char* res = fn(reinterpret_cast<const uint8_t*>(bytes), static_cast<size_t>(len));
    env->ReleaseByteArrayElements(jsonBytes, bytes, JNI_ABORT);

    if (!res) return nullptr;
    jstring outStr = env->NewStringUTF(res);
    auto free_fn = get_rust_symbol<RustFreeStringFn>("rust_free_string");
    if (free_fn) free_fn(res);
    return outStr;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_streamify_app_data_NativeBridge_rustComputeFftSpectrum(
    JNIEnv* env,
    jobject /* this */,
    jfloatArray pcmFloats,
    jint barCount,
    jfloatArray outBars
) {
    if (!pcmFloats || !outBars || barCount <= 0) return -1;
    jsize pcmLen = env->GetArrayLength(pcmFloats);
    jsize outLen = env->GetArrayLength(outBars);
    if (pcmLen <= 0 || outLen < barCount) return -1;

    typedef int32_t (*RustFn)(const float*, size_t, size_t, float*);
    auto fn = get_rust_symbol<RustFn>("rust_compute_fft_spectrum");
    if (!fn) return -1;

    jfloat* pcm = env->GetFloatArrayElements(pcmFloats, nullptr);
    jfloat* bars = env->GetFloatArrayElements(outBars, nullptr);

    int32_t code = fn(pcm, pcmLen, barCount, bars);

    env->ReleaseFloatArrayElements(pcmFloats, pcm, JNI_ABORT);
    env->ReleaseFloatArrayElements(outBars, bars, 0);
    return code;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_streamify_app_data_NativeBridge_rustProcessEqualizerFrame(
    JNIEnv* env,
    jobject /* this */,
    jfloatArray pcmFloats,
    jint channels,
    jfloatArray gains
) {
    if (!pcmFloats || channels <= 0) return -1;
    jsize pcmLen = env->GetArrayLength(pcmFloats);
    if (pcmLen <= 0) return -1;

    typedef int32_t (*RustFn)(float*, size_t, size_t, const float*);
    auto fn = get_rust_symbol<RustFn>("rust_process_equalizer_frame");
    if (!fn) return -1;

    jfloat* pcm = env->GetFloatArrayElements(pcmFloats, nullptr);
    jfloat* g = gains ? env->GetFloatArrayElements(gains, nullptr) : nullptr;

    int32_t code = fn(pcm, pcmLen, channels, g);

    env->ReleaseFloatArrayElements(pcmFloats, pcm, 0);
    if (gains && g) {
        env->ReleaseFloatArrayElements(gains, g, JNI_ABORT);
    }
    return code;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_streamify_app_data_NativeBridge_rustDownloadStreamDirect(
    JNIEnv* env,
    jobject /* this */,
    jstring streamUrl,
    jstring destPath
) {
    if (!streamUrl || !destPath) return nullptr;
    typedef char* (*RustFn)(const char*, const char*);
    auto fn = get_rust_symbol<RustFn>("rust_download_stream_direct");
    if (!fn) return nullptr;

    const char* url = env->GetStringUTFChars(streamUrl, nullptr);
    const char* path = env->GetStringUTFChars(destPath, nullptr);

    char* res = fn(url, path);

    env->ReleaseStringUTFChars(streamUrl, url);
    env->ReleaseStringUTFChars(destPath, path);

    if (!res) return nullptr;
    jstring outStr = env->NewStringUTF(res);
    auto free_fn = get_rust_symbol<RustFreeStringFn>("rust_free_string");
    if (free_fn) free_fn(res);
    return outStr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_streamify_app_data_NativeBridge_rustScoreAndRankRadioCandidates(
    JNIEnv* env,
    jobject /* this */,
    jstring candidatesJson,
    jfloat seedBpm,
    jstring seedKey,
    jint seedDurSec,
    jstring seedSig,
    jstring queueJson
) {
    if (!candidatesJson) return nullptr;
    typedef char* (*RustFn)(const char*, float, const char*, int32_t, const char*, const char*);
    auto fn = get_rust_symbol<RustFn>("rust_score_and_rank_radio_candidates");
    if (!fn) return nullptr;

    const char* cJson = env->GetStringUTFChars(candidatesJson, nullptr);
    const char* sKey = seedKey ? env->GetStringUTFChars(seedKey, nullptr) : "";
    const char* sSig = seedSig ? env->GetStringUTFChars(seedSig, nullptr) : "";
    const char* qJson = queueJson ? env->GetStringUTFChars(queueJson, nullptr) : "[]";

    char* res = fn(cJson, seedBpm, sKey, seedDurSec, sSig, qJson);

    env->ReleaseStringUTFChars(candidatesJson, cJson);
    if (seedKey) env->ReleaseStringUTFChars(seedKey, sKey);
    if (seedSig) env->ReleaseStringUTFChars(seedSig, sSig);
    if (queueJson) env->ReleaseStringUTFChars(queueJson, qJson);

    if (!res) return nullptr;
    jstring outStr = env->NewStringUTF(res);
    auto free_fn = get_rust_symbol<RustFreeStringFn>("rust_free_string");
    if (free_fn) free_fn(res);
    return outStr;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_streamify_app_data_NativeBridge_rustProcessCrossfadePcm(
    JNIEnv* env,
    jobject /* this */,
    jfloatArray outgoingBuf,
    jfloatArray incomingBuf,
    jfloatArray mixedBuf,
    jfloat progress
) {
    if (!outgoingBuf || !incomingBuf || !mixedBuf) return -1;
    jsize len = env->GetArrayLength(mixedBuf);
    if (len <= 0) return -1;

    typedef int32_t (*RustFn)(const float*, const float*, float*, size_t, float);
    auto fn = get_rust_symbol<RustFn>("rust_process_crossfade_pcm");
    if (!fn) return -1;

    jfloat* out = env->GetFloatArrayElements(outgoingBuf, nullptr);
    jfloat* in = env->GetFloatArrayElements(incomingBuf, nullptr);
    jfloat* mixed = env->GetFloatArrayElements(mixedBuf, nullptr);

    int32_t code = fn(out, in, mixed, static_cast<size_t>(len), progress);

    env->ReleaseFloatArrayElements(outgoingBuf, out, JNI_ABORT);
    env->ReleaseFloatArrayElements(incomingBuf, in, JNI_ABORT);
    env->ReleaseFloatArrayElements(mixedBuf, mixed, 0);
    return code;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_streamify_app_data_NativeBridge_rustEncryptVaultFile(
    JNIEnv* env,
    jobject /* this */,
    jstring srcPath,
    jstring destPath,
    jbyteArray masterKey
) {
    if (!srcPath || !destPath || !masterKey) return -1;
    typedef int32_t (*RustFn)(const char*, const char*, const uint8_t*, size_t);
    auto fn = get_rust_symbol<RustFn>("rust_encrypt_vault_file");
    if (!fn) return -1;

    const char* src = env->GetStringUTFChars(srcPath, nullptr);
    const char* dest = env->GetStringUTFChars(destPath, nullptr);
    jsize keyLen = env->GetArrayLength(masterKey);
    jbyte* key = env->GetByteArrayElements(masterKey, nullptr);

    int32_t code = fn(src, dest, reinterpret_cast<const uint8_t*>(key), static_cast<size_t>(keyLen));

    env->ReleaseStringUTFChars(srcPath, src);
    env->ReleaseStringUTFChars(destPath, dest);
    env->ReleaseByteArrayElements(masterKey, key, JNI_ABORT);
    return code;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_streamify_app_data_NativeBridge_rustDecryptVaultFile(
    JNIEnv* env,
    jobject /* this */,
    jstring srcPath,
    jstring destPath,
    jbyteArray masterKey
) {
    if (!srcPath || !destPath || !masterKey) return -1;
    typedef int32_t (*RustFn)(const char*, const char*, const uint8_t*, size_t);
    auto fn = get_rust_symbol<RustFn>("rust_decrypt_vault_file");
    if (!fn) return -1;

    const char* src = env->GetStringUTFChars(srcPath, nullptr);
    const char* dest = env->GetStringUTFChars(destPath, nullptr);
    jsize keyLen = env->GetArrayLength(masterKey);
    jbyte* key = env->GetByteArrayElements(masterKey, nullptr);

    int32_t code = fn(src, dest, reinterpret_cast<const uint8_t*>(key), static_cast<size_t>(keyLen));

    env->ReleaseStringUTFChars(srcPath, src);
    env->ReleaseStringUTFChars(destPath, dest);
    env->ReleaseByteArrayElements(masterKey, key, JNI_ABORT);
    return code;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_streamify_app_data_NativeBridge_rustParseBackupCsv(
    JNIEnv* env,
    jobject /* this */,
    jstring csvContent
) {
    if (!csvContent) return nullptr;
    typedef char* (*RustFn)(const char*);
    auto fn = get_rust_symbol<RustFn>("rust_parse_backup_csv");
    if (!fn) return nullptr;

    const char* csv = env->GetStringUTFChars(csvContent, nullptr);
    char* res = fn(csv);
    env->ReleaseStringUTFChars(csvContent, csv);

    if (!res) return nullptr;
    jstring outStr = env->NewStringUTF(res);
    auto free_fn = get_rust_symbol<RustFreeStringFn>("rust_free_string");
    if (free_fn) free_fn(res);
    return outStr;
}

