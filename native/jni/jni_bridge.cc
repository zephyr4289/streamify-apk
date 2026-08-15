#include <jni.h>
#include <string>
#include <vector>
#include <ctime>
#include "../engine/StreamifyDB.h"
#include "../engine/TaskOrchestrator.h"
#include "../engine/TelemetryEngine.h"
#include "../engine/ChronosProfiler.h"

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

    jclass localRecClass = env->FindClass("com/streamify/app/data/models/RecommendationNative");
    if (localRecClass) {
        g_recClass = reinterpret_cast<jclass>(env->NewGlobalRef(localRecClass));
        g_recConstructor = env->GetMethodID(g_recClass, "<init>", "(IF)V");
        env->DeleteLocalRef(localRecClass);
    }

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

jobjectArray convertRecList(JNIEnv* env, const std::vector<Recommendation>& recs) {
    jclass recClass = g_recClass ? g_recClass : env->FindClass("com/streamify/app/data/models/RecommendationNative");
    jmethodID constructor = g_recConstructor ? g_recConstructor : env->GetMethodID(recClass, "<init>", "(IF)V");

    jobjectArray resultArray = env->NewObjectArray(recs.size(), recClass, nullptr);
    for (size_t i = 0; i < recs.size(); ++i) {
        jobject obj = env->NewObject(recClass, constructor, recs[i].trackId, recs[i].score);
        env->SetObjectArrayElement(resultArray, i, obj);
        env->DeleteLocalRef(obj);
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
