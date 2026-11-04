#include <jni.h>

#include <string>

#include "spherestitch/stitch.hpp"

static std::string str(JNIEnv* env, jstring s) {
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string r(c);
    env->ReleaseStringUTFChars(s, c);
    return r;
}

extern "C" JNIEXPORT jstring JNICALL Java_cz_nothrow_spherecam_stitcher_Stitcher_nativeStitch(
    JNIEnv* env, jobject, jstring session, jstring out, jstring report, jint width, jobject listener) {
    std::string sessionPath = str(env, session), outPath = str(env, out), reportPath = str(env, report);
    jmethodID onProgress = env->GetMethodID(env->GetObjectClass(listener), "onProgress", "(ID)Z");
    if (!onProgress) return nullptr;

    spherestitch::Options o;
    o.width = width;
    auto progress = [&](spherestitch::Phase p, double f) {
        bool go = env->CallBooleanMethod(listener, onProgress, static_cast<jint>(p), f);
        // A pending Java exception forbids further JNI calls; cancelling unwinds without any, and it is rethrown on return.
        return go && !env->ExceptionCheck();
    };
    spherestitch::Result r = spherestitch::stitch(sessionPath, outPath, o, progress);
    if (env->ExceptionCheck()) return nullptr;
    try {
        spherestitch::writeReport(r, reportPath);
    } catch (const std::exception&) {
    }
    return env->NewStringUTF(r.ok ? "" : r.error.c_str());
}
