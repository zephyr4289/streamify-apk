#include <jni.h>
#include <string>
#include <vector>
#include "../engine/StreamifyDB.h"

// Helper to convert C++ Track to Java TrackNative
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
    jclass trackClass = env->FindClass("com/streamify/app/data/models/TrackNative");
    jmethodID constructor = env->GetMethodID(trackClass, "<init>", "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;IFLjava/lang/String;ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;)V");
    
    jobjectArray trackArray = env->NewObjectArray(tracks.size(), trackClass, nullptr);
    for (size_t i = 0; i < tracks.size(); ++i) {
        jobject trackObj = convertTrack(env, trackClass, constructor, tracks[i]);
        env->SetObjectArrayElement(trackArray, i, trackObj);
        env->DeleteLocalRef(trackObj);
    }
    return trackArray;
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
