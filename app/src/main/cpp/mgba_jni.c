// JNI bridge between the Kotlin app and mGBA's core library (external/mgba).
//
// Design notes (see also app/src/main/java/com/kino/gbaemu/core/MgbaCore.kt):
//  - The emulator runs on mGBA's own mCoreThread, which repeatedly calls
//    core->runLoop(). Real-time pacing is achieved through mCoreSync: with
//    core->opts.videoSync enabled, the CPU thread blocks at every vblank
//    until a consumer calls mCoreSyncWaitFrameStart()/WaitFrameEnd() once
//    per displayed frame. That consumer is a dedicated render thread on the
//    Kotlin side, ticking at the GBA's native ~59.7275 Hz.
//  - The video buffer is the raw memory of a direct java.nio.ByteBuffer
//    allocated in Kotlin, so the core renders straight into memory the UI
//    thread can blit from with zero extra copies. mGBA's default pixel
//    format (mCOLOR_XBGR8, byte order R,G,B,X in memory) is byte-identical
//    to Android's Bitmap.Config.ARGB_8888, so a Bitmap can wrap it directly.
//  - Audio leaves the core one stereo sample at a time via an mAVStream
//    callback (postAudioFrame), which is the simplest correct hook that
//    does not require reimplementing mGBA's blip_buf resampling. Samples
//    are pushed into a lock-free ring buffer that a Kotlin AudioTrack
//    thread drains.
//  - Save-state (de)serialization is exposed as raw byte blobs; the Kotlin
//    layer owns slot files, thumbnails and naming directly via explicit
//    VFileOpen() paths, rather than mGBA's own directory-based slot helpers
//    (mCoreSaveState/mCoreAutoloadSave and friends), which need a configured
//    mDirectorySet we never set up.
//  - ENABLE_DIRECTORIES must still be defined here (see CMakeLists.txt) even
//    though we never call those directory helpers: mGBA's build always
//    turns it on together with ENABLE_VFS, which changes struct mCore's
//    layout (an extra "dirs" member). Compiling this file without it is a
//    real ABI mismatch with the actual mgba.a, not just a missing symbol.

#include <jni.h>

#include <android/log.h>
#include <fcntl.h>
#include <limits.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include <mgba/core/cheats.h>
#include <mgba/core/config.h>
#include <mgba/core/core.h>
#include <mgba/core/interface.h>
#include <mgba/core/sync.h>
#include <mgba/core/thread.h>
#include <mgba/gba/core.h>
#include <mgba-util/vfs.h>

#include "ring_buffer.h"

#define LOG_TAG "KinoGBA-Native"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

#define GBA_WIDTH 240
#define GBA_HEIGHT 160
#define AUDIO_RING_CAPACITY_FRAMES 16384
#define REWIND_BUFFER_CAPACITY 600
#define REWIND_BUFFER_INTERVAL 6

typedef struct {
    struct mCore *core;
    struct mCoreThread thread;
    struct mAVStream avStream;
    RingBuffer audioRing;
    jobject videoBufferRef;
    int threadStarted;
    char *logPath;
} EmuContext;

// mAVStream callback: invoked from the emulation thread once per output
// audio sample. Kept branch-free and allocation-free.
static void onPostAudioFrame(struct mAVStream *stream, int16_t left, int16_t right) {
    EmuContext *ctx = (EmuContext *) ((char *) stream - offsetof(EmuContext, avStream));
    ring_buffer_push(&ctx->audioRing, left, right);
}

// A native crash (SIGSEGV/abort) kills the process instantly, before any
// Java exception handler or logcat buffer can be inspected by someone
// without adb access to the device. This writes a plain-text breadcrumb
// trail to a file the app itself can read back and show/share from its own
// UI, using raw write()+fsync() (not buffered stdio) so the last line
// written is durable even if the very next native call segfaults.
static void logBreadcrumb(EmuContext *ctx, const char *msg) {
    if (!ctx || !ctx->logPath) {
        return;
    }
    int fd = open(ctx->logPath, O_WRONLY | O_CREAT | O_APPEND, 0644);
    if (fd < 0) {
        return;
    }
    time_t now = time(NULL);
    char line[320];
    int len = snprintf(line, sizeof(line), "[%ld] %s\n", (long) now, msg);
    if (len > 0) {
        write(fd, line, (size_t) (len < (int) sizeof(line) ? len : (int) sizeof(line) - 1));
    }
    fsync(fd);
    close(fd);
}

JNIEXPORT jlong JNICALL
Java_com_kino_gbaemu_core_MgbaCore_nativeCreate(JNIEnv *env, jobject thiz, jobject videoBuffer, jstring logPath) {
    (void) thiz;

    // The EmuContext (and therefore ctx->logPath) doesn't exist yet, so the
    // very first breadcrumbs are written through a temporary path pulled
    // straight from the jstring argument.
    const char *logPathC = logPath ? (*env)->GetStringUTFChars(env, logPath, NULL) : NULL;

    void *videoPtr = (*env)->GetDirectBufferAddress(env, videoBuffer);
    jlong videoCapacity = (*env)->GetDirectBufferCapacity(env, videoBuffer);
    if (!videoPtr || videoCapacity < (jlong) (GBA_WIDTH * GBA_HEIGHT * 4)) {
        LOGE("Video buffer must be a direct ByteBuffer of at least %d bytes", GBA_WIDTH * GBA_HEIGHT * 4);
        if (logPathC) (*env)->ReleaseStringUTFChars(env, logPath, logPathC);
        return 0;
    }

    EmuContext *ctx = calloc(1, sizeof(EmuContext));
    if (!ctx) {
        if (logPathC) (*env)->ReleaseStringUTFChars(env, logPath, logPathC);
        return 0;
    }
    ctx->logPath = logPathC ? strdup(logPathC) : NULL;
    if (logPathC) (*env)->ReleaseStringUTFChars(env, logPath, logPathC);

    logBreadcrumb(ctx, "nativeCreate: begin");

    ctx->core = GBACoreCreate();
    if (!ctx->core) {
        LOGE("GBACoreCreate failed");
        logBreadcrumb(ctx, "nativeCreate: GBACoreCreate FAILED");
        free(ctx->logPath);
        free(ctx);
        return 0;
    }
    logBreadcrumb(ctx, "nativeCreate: GBACoreCreate ok");

    mCoreInitConfig(ctx->core, "kinogba");
    logBreadcrumb(ctx, "nativeCreate: mCoreInitConfig ok");

    if (!ctx->core->init(ctx->core)) {
        LOGE("core->init failed");
        logBreadcrumb(ctx, "nativeCreate: core->init FAILED");
        ctx->core->deinit(ctx->core);
        free(ctx->logPath);
        free(ctx);
        return 0;
    }
    logBreadcrumb(ctx, "nativeCreate: core->init ok");

    if (!ring_buffer_init(&ctx->audioRing, AUDIO_RING_CAPACITY_FRAMES)) {
        LOGE("Failed to allocate audio ring buffer");
        logBreadcrumb(ctx, "nativeCreate: ring_buffer_init FAILED");
        ctx->core->deinit(ctx->core);
        free(ctx->logPath);
        free(ctx);
        return 0;
    }

    ctx->videoBufferRef = (*env)->NewGlobalRef(env, videoBuffer);
    ctx->core->setVideoBuffer(ctx->core, (mColor *) videoPtr, GBA_WIDTH);
    logBreadcrumb(ctx, "nativeCreate: setVideoBuffer ok");

    ctx->avStream.videoDimensionsChanged = NULL;
    ctx->avStream.audioRateChanged = NULL;
    ctx->avStream.postVideoFrame = NULL;
    ctx->avStream.postAudioFrame = onPostAudioFrame;
    ctx->avStream.postAudioBuffer = NULL;
    ctx->core->setAVStream(ctx->core, &ctx->avStream);

    // Real-time pacing (see mCoreSyncWaitFrameStart/End called from the
    // render thread) instead of mAudioBuffer-driven throttling, and a
    // rolling rewind history sampled every REWIND_BUFFER_INTERVAL frames.
    ctx->core->opts.videoSync = true;
    ctx->core->opts.audioSync = false;
    ctx->core->opts.fpsTarget = 0; // defaults to 60
    ctx->core->opts.rewindEnable = true;
    ctx->core->opts.rewindBufferCapacity = REWIND_BUFFER_CAPACITY;
    ctx->core->opts.rewindBufferInterval = REWIND_BUFFER_INTERVAL;
    ctx->core->opts.useBios = false; // use mGBA's built-in HLE BIOS; no BIOS dump required
    ctx->core->opts.skipBios = true;

    logBreadcrumb(ctx, "nativeCreate: done");
    return (jlong) (intptr_t) ctx;
}

JNIEXPORT jint JNICALL
Java_com_kino_gbaemu_core_MgbaCore_nativeLoadRom(JNIEnv *env, jobject thiz, jlong handle, jstring romPath, jstring savePath) {
    (void) thiz;
    EmuContext *ctx = (EmuContext *) (intptr_t) handle;
    if (!ctx) {
        return 3;
    }

    logBreadcrumb(ctx, "nativeLoadRom: begin");

    const char *romPathC = (*env)->GetStringUTFChars(env, romPath, NULL);
    struct VFile *romVf = VFileOpen(romPathC, O_RDONLY);
    (*env)->ReleaseStringUTFChars(env, romPath, romPathC);

    if (!romVf) {
        LOGE("Could not open ROM file");
        logBreadcrumb(ctx, "nativeLoadRom: VFileOpen(rom) FAILED");
        return 1;
    }
    logBreadcrumb(ctx, "nativeLoadRom: VFileOpen(rom) ok");

    if (!ctx->core->loadROM(ctx->core, romVf)) {
        LOGE("core->loadROM rejected the ROM file");
        logBreadcrumb(ctx, "nativeLoadRom: core->loadROM FAILED");
        romVf->close(romVf);
        return 2;
    }
    logBreadcrumb(ctx, "nativeLoadRom: core->loadROM ok");

    const char *savePathC = (*env)->GetStringUTFChars(env, savePath, NULL);
    struct VFile *saveVf = VFileOpen(savePathC, O_RDWR | O_CREAT);
    (*env)->ReleaseStringUTFChars(env, savePath, savePathC);

    if (saveVf) {
        ctx->core->loadSave(ctx->core, saveVf);
        logBreadcrumb(ctx, "nativeLoadRom: loadSave ok");
    } else {
        LOGW("Could not open/create save file; progress will not persist");
        logBreadcrumb(ctx, "nativeLoadRom: VFileOpen(save) FAILED (non-fatal)");
    }

    logBreadcrumb(ctx, "nativeLoadRom: done");
    return 0;
}

JNIEXPORT jboolean JNICALL
Java_com_kino_gbaemu_core_MgbaCore_nativeStart(JNIEnv *env, jobject thiz, jlong handle) {
    (void) env;
    (void) thiz;
    EmuContext *ctx = (EmuContext *) (intptr_t) handle;
    if (!ctx || ctx->threadStarted) {
        return JNI_FALSE;
    }

    logBreadcrumb(ctx, "nativeStart: begin");
    memset(&ctx->thread, 0, sizeof(ctx->thread));
    ctx->thread.core = ctx->core;

    if (!mCoreThreadStart(&ctx->thread)) {
        LOGE("mCoreThreadStart failed");
        logBreadcrumb(ctx, "nativeStart: mCoreThreadStart FAILED");
        return JNI_FALSE;
    }

    ctx->threadStarted = 1;
    logBreadcrumb(ctx, "nativeStart: mCoreThreadStart ok, CPU thread running");
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_kino_gbaemu_core_MgbaCore_nativeStop(JNIEnv *env, jobject thiz, jlong handle) {
    (void) env;
    (void) thiz;
    EmuContext *ctx = (EmuContext *) (intptr_t) handle;
    if (!ctx || !ctx->threadStarted) {
        return;
    }

    mCoreThreadEnd(&ctx->thread);
    // Wake up a render thread that might be blocked inside WaitFrameStart.
    mCoreSyncForceFrame(&ctx->thread.impl->sync);
    mCoreThreadJoin(&ctx->thread);
    ctx->threadStarted = 0;
}

JNIEXPORT void JNICALL
Java_com_kino_gbaemu_core_MgbaCore_nativeDestroy(JNIEnv *env, jobject thiz, jlong handle) {
    (void) thiz;
    EmuContext *ctx = (EmuContext *) (intptr_t) handle;
    if (!ctx) {
        return;
    }

    if (ctx->threadStarted) {
        mCoreThreadEnd(&ctx->thread);
        mCoreSyncForceFrame(&ctx->thread.impl->sync);
        mCoreThreadJoin(&ctx->thread);
    }

    if (ctx->core) {
        ctx->core->deinit(ctx->core);
    }

    ring_buffer_destroy(&ctx->audioRing);

    if (ctx->videoBufferRef) {
        (*env)->DeleteGlobalRef(env, ctx->videoBufferRef);
    }

    free(ctx->logPath);
    free(ctx);
}

JNIEXPORT void JNICALL
Java_com_kino_gbaemu_core_MgbaCore_nativePause(JNIEnv *env, jobject thiz, jlong handle) {
    (void) env;
    (void) thiz;
    EmuContext *ctx = (EmuContext *) (intptr_t) handle;
    if (ctx && ctx->threadStarted) {
        mCoreThreadPause(&ctx->thread);
    }
}

JNIEXPORT jboolean JNICALL
Java_com_kino_gbaemu_core_MgbaCore_nativeIsPaused(JNIEnv *env, jobject thiz, jlong handle) {
    (void) env;
    (void) thiz;
    EmuContext *ctx = (EmuContext *) (intptr_t) handle;
    if (!ctx || !ctx->threadStarted) {
        return JNI_FALSE;
    }
    return mCoreThreadIsPaused(&ctx->thread) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_kino_gbaemu_core_MgbaCore_nativeUnpause(JNIEnv *env, jobject thiz, jlong handle) {
    (void) env;
    (void) thiz;
    EmuContext *ctx = (EmuContext *) (intptr_t) handle;
    if (ctx && ctx->threadStarted) {
        mCoreThreadUnpause(&ctx->thread);
    }
}

JNIEXPORT void JNICALL
Java_com_kino_gbaemu_core_MgbaCore_nativeReset(JNIEnv *env, jobject thiz, jlong handle) {
    (void) env;
    (void) thiz;
    EmuContext *ctx = (EmuContext *) (intptr_t) handle;
    if (ctx && ctx->threadStarted) {
        mCoreThreadReset(&ctx->thread);
    }
}

JNIEXPORT void JNICALL
Java_com_kino_gbaemu_core_MgbaCore_nativeSetRewinding(JNIEnv *env, jobject thiz, jlong handle, jboolean rewinding) {
    (void) env;
    (void) thiz;
    EmuContext *ctx = (EmuContext *) (intptr_t) handle;
    if (ctx && ctx->threadStarted) {
        mCoreThreadSetRewinding(&ctx->thread, rewinding == JNI_TRUE);
    }
}

JNIEXPORT void JNICALL
Java_com_kino_gbaemu_core_MgbaCore_nativeSetKey(JNIEnv *env, jobject thiz, jlong handle, jint keyIndex, jboolean pressed) {
    (void) env;
    (void) thiz;
    EmuContext *ctx = (EmuContext *) (intptr_t) handle;
    if (!ctx || !ctx->core || keyIndex < 0 || keyIndex >= 10) {
        return;
    }
    uint32_t bit = 1u << keyIndex;
    if (pressed) {
        ctx->core->addKeys(ctx->core, bit);
    } else {
        ctx->core->clearKeys(ctx->core, bit);
    }
}

// Returns JNI_TRUE if a frame is ready and was latched; the caller must
// blit the shared video buffer and then call nativeEndFrame before the
// core is allowed to render into it again.
//
// mCoreSyncWaitFrameStart() leaves its internal mutex LOCKED on every
// return path (true or false) - only mCoreSyncWaitFrameEnd() unlocks it.
// When we have no frame to hand back (false), we must still pair the call
// with WaitFrameEnd ourselves right here, or the next WaitFrameStart call
// from this same thread would deadlock relocking an already-held mutex.
JNIEXPORT jboolean JNICALL
Java_com_kino_gbaemu_core_MgbaCore_nativeBeginFrame(JNIEnv *env, jobject thiz, jlong handle) {
    (void) env;
    (void) thiz;
    EmuContext *ctx = (EmuContext *) (intptr_t) handle;
    if (!ctx || !ctx->threadStarted) {
        return JNI_FALSE;
    }
    if (!mCoreSyncWaitFrameStart(&ctx->thread.impl->sync)) {
        mCoreSyncWaitFrameEnd(&ctx->thread.impl->sync);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_kino_gbaemu_core_MgbaCore_nativeEndFrame(JNIEnv *env, jobject thiz, jlong handle) {
    (void) env;
    (void) thiz;
    EmuContext *ctx = (EmuContext *) (intptr_t) handle;
    if (ctx && ctx->threadStarted) {
        mCoreSyncWaitFrameEnd(&ctx->thread.impl->sync);
    }
}

JNIEXPORT jint JNICALL
Java_com_kino_gbaemu_core_MgbaCore_nativeGetSampleRate(JNIEnv *env, jobject thiz, jlong handle) {
    (void) env;
    (void) thiz;
    EmuContext *ctx = (EmuContext *) (intptr_t) handle;
    if (!ctx || !ctx->core) {
        return 32768;
    }
    unsigned rate = ctx->core->audioSampleRate(ctx->core);
    return rate ? (jint) rate : 32768;
}

JNIEXPORT jint JNICALL
Java_com_kino_gbaemu_core_MgbaCore_nativePopAudio(JNIEnv *env, jobject thiz, jlong handle, jshortArray out, jint maxFrames) {
    (void) thiz;
    EmuContext *ctx = (EmuContext *) (intptr_t) handle;
    if (!ctx || maxFrames <= 0) {
        return 0;
    }

    jshort *buffer = (*env)->GetShortArrayElements(env, out, NULL);
    if (!buffer) {
        return 0;
    }
    size_t copied = ring_buffer_pop(&ctx->audioRing, (int16_t *) buffer, (size_t) maxFrames);
    (*env)->ReleaseShortArrayElements(env, out, buffer, 0);
    return (jint) copied;
}

JNIEXPORT jbyteArray JNICALL
Java_com_kino_gbaemu_core_MgbaCore_nativeSaveStateBytes(JNIEnv *env, jobject thiz, jlong handle) {
    (void) thiz;
    EmuContext *ctx = (EmuContext *) (intptr_t) handle;
    if (!ctx || !ctx->core) {
        return NULL;
    }

    size_t size = ctx->core->stateSize(ctx->core);
    if (!size) {
        return NULL;
    }

    void *state = malloc(size);
    if (!state) {
        return NULL;
    }

    jbyteArray result = NULL;
    if (ctx->core->saveState(ctx->core, state)) {
        result = (*env)->NewByteArray(env, (jsize) size);
        if (result) {
            (*env)->SetByteArrayRegion(env, result, 0, (jsize) size, (const jbyte *) state);
        }
    } else {
        LOGE("core->saveState failed");
    }

    free(state);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_kino_gbaemu_core_MgbaCore_nativeLoadStateBytes(JNIEnv *env, jobject thiz, jlong handle, jbyteArray data) {
    (void) thiz;
    EmuContext *ctx = (EmuContext *) (intptr_t) handle;
    if (!ctx || !ctx->core || !data) {
        return JNI_FALSE;
    }

    jsize length = (*env)->GetArrayLength(env, data);
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (!bytes) {
        return JNI_FALSE;
    }

    jboolean ok = ctx->core->loadState(ctx->core, bytes) ? JNI_TRUE : JNI_FALSE;
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
    (void) length;
    return ok;
}

// The first call to core->cheatDevice() lazily creates the cheat device and
// attaches it to the ARM core (mutating core->cpu->components[] and calling
// ARMHotplugAttach). If the mCoreThread is running concurrently at that
// moment, this races with the CPU thread's own use of that same array -
// callers on the Kotlin side MUST pause the thread (EmulatorEngine already
// does this in replaceCheats()) before calling nativeCheatsClear/Add.
JNIEXPORT void JNICALL
Java_com_kino_gbaemu_core_MgbaCore_nativeCheatsClear(JNIEnv *env, jobject thiz, jlong handle) {
    (void) env;
    (void) thiz;
    EmuContext *ctx = (EmuContext *) (intptr_t) handle;
    if (!ctx || !ctx->core) {
        return;
    }
    logBreadcrumb(ctx, "nativeCheatsClear: begin (core->cheatDevice may lazily attach it)");
    struct mCheatDevice *device = ctx->core->cheatDevice(ctx->core);
    logBreadcrumb(ctx, "nativeCheatsClear: cheatDevice() returned");
    if (device) {
        mCheatDeviceClear(device);
    }
    logBreadcrumb(ctx, "nativeCheatsClear: done");
}

JNIEXPORT jboolean JNICALL
Java_com_kino_gbaemu_core_MgbaCore_nativeCheatsAdd(JNIEnv *env, jobject thiz, jlong handle, jstring name, jstring code) {
    (void) thiz;
    EmuContext *ctx = (EmuContext *) (intptr_t) handle;
    if (!ctx || !ctx->core) {
        return JNI_FALSE;
    }

    struct mCheatDevice *device = ctx->core->cheatDevice(ctx->core);
    if (!device) {
        return JNI_FALSE;
    }

    const char *nameC = (*env)->GetStringUTFChars(env, name, NULL);
    struct mCheatSet *set = device->createSet(device, nameC);
    (*env)->ReleaseStringUTFChars(env, name, nameC);
    if (!set) {
        return JNI_FALSE;
    }

    const char *codeC = (*env)->GetStringUTFChars(env, code, NULL);
    bool added = mCheatAddLine(set, codeC, 0);
    (*env)->ReleaseStringUTFChars(env, code, codeC);

    if (!added) {
        mCheatSetDeinit(set);
        free(set);
        return JNI_FALSE;
    }

    set->enabled = true;
    mCheatAddSet(device, set);
    return JNI_TRUE;
}
