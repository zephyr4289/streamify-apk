#include <jni.h>
#include <string>

extern "C" JNIEXPORT jstring JNICALL
Java_com_streamify_app_data_NativeBridge_stringFromJNI(JNIEnv* env, jobject /* this */) {
    return env->NewStringUTF("Streamify C++ Core Initialized");
}
