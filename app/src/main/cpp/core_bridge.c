// Generic JNI <-> libretro bridge.
//
// This is the "different methodology" behind Kino GBA's rewrite: instead of
// linking directly against an emulator core's internal C API (what the
// previous GBA-only version did against mGBA's mCore/mCoreThread), this file
// only ever talks to cores through the standard libretro plugin API
// (libretro/libretro.h) - the same small, stable, versioned interface
// RetroArch itself uses. A core .so is dlopen()'d at runtime and every
// retro_* entry point is resolved with dlsym(); nothing here depends on any
// core's internal struct layouts, so the whole class of ABI-mismatch bug
// that caused a real crash in the previous version (a missing compile
// define shifting struct mCore's layout) structurally cannot happen here.
//
// Because exactly one core is ever loaded per play session, this file is
// compiled into TWO separate shared libraries (see CMakeLists.txt) - one
// linked as a thin wrapper around mGBA's libretro core, another around
// melonDS DS's - that both export the identical
// Java_com_kino_gbaemu_core_LibretroCore_native* symbols. Kotlin loads
// whichever one matches the platform of the game being played
// (System.loadLibrary("kino_core_gba") or "kino_core_nds"), so there is
// never a symbol collision at runtime.
//
// Threading model: retro_run() is synchronous and single-threaded by
// design - it polls input, advances one frame of emulation, and invokes the
// video/audio callbacks below before returning. There is no separate
// emulation thread here (unlike the previous mCoreThread-based version), so
// there is no cross-thread synchronization to get wrong: the Kotlin render
// thread simply calls nativeRunFrame() once per displayed frame, paced to
// whatever FPS the core reports.

#include <jni.h>

#include <android/log.h>
#include <dlfcn.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "libretro/libretro.h"
#include "ring_buffer.h"

#define LOG_TAG "KinoCore-Native"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

#define AUDIO_RING_CAPACITY_FRAMES 32768
#define MAX_PORTS 1
#define MAX_JOYPAD_BUTTONS 16
#define MAX_VARIABLES 64
#define MAX_VAR_KEY 64
#define MAX_VAR_VALUE 256

// One core-supplied "key -> chosen value" pair, as answered to
// RETRO_ENVIRONMENT_GET_VARIABLE. Populated from the core's own
// RETRO_ENVIRONMENT_SET_VARIABLES call (legacy/v0 core-options API - see the
// long comment above CoreCtx.forceVariables for why we deliberately steer
// every core down that specific path instead of v1/v2).
typedef struct {
    char key[MAX_VAR_KEY];
    char value[MAX_VAR_VALUE];
} CoreVariable;

typedef struct {
    void *soHandle;

    void (*retro_init)(void);
    void (*retro_deinit)(void);
    unsigned (*retro_api_version)(void);
    void (*retro_get_system_info)(struct retro_system_info *info);
    void (*retro_get_system_av_info)(struct retro_system_av_info *info);
    void (*retro_set_environment)(retro_environment_t);
    void (*retro_set_video_refresh)(retro_video_refresh_t);
    void (*retro_set_audio_sample)(retro_audio_sample_t);
    void (*retro_set_audio_sample_batch)(retro_audio_sample_batch_t);
    void (*retro_set_input_poll)(retro_input_poll_t);
    void (*retro_set_input_state)(retro_input_state_t);
    void (*retro_set_controller_port_device)(unsigned port, unsigned device);
    void (*retro_reset)(void);
    void (*retro_run)(void);
    size_t (*retro_serialize_size)(void);
    bool (*retro_serialize)(void *data, size_t size);
    bool (*retro_unserialize)(const void *data, size_t size);
    bool (*retro_load_game)(const struct retro_game_info *game);
    void (*retro_unload_game)(void);
    void *(*retro_get_memory_data)(unsigned id);
    size_t (*retro_get_memory_size)(unsigned id);

    // --- Frontend state exposed to the environment/video/audio/input callbacks ---
    char systemDir[512];
    char saveDir[512];

    enum retro_pixel_format pixelFormat;
    struct retro_game_geometry geometry;
    struct retro_system_timing timing;

    // Tightly packed (no row padding) ARGB8888 buffer sized
    // geometry.max_width * geometry.max_height, written by videoRefreshCb
    // and read back by Kotlin via nativeGetVideoBuffer(). "Tightly packed"
    // means each row starts at frameWidth-independent max_width*4, NOT at
    // the current frame's own pitch, so the Kotlin side can treat it as a
    // fixed-size bitmap and only draw the top-left currentWidth x
    // currentHeight sub-rect each frame.
    uint32_t *videoBuffer;
    unsigned videoBufferMaxWidth;
    unsigned videoBufferMaxHeight;
    _Atomic unsigned lastFrameWidth;
    _Atomic unsigned lastFrameHeight;

    RingBuffer audioRing;

    // Polled input state. RETRO_DEVICE_JOYPAD buttons per port, plus a
    // single analog pointer/touch position (libretro's RETRO_DEVICE_POINTER
    // convention: normalized to [-32767, 32767] over the *whole* combined
    // video frame, 0,0 at the center) used for the DS touch screen.
    _Atomic bool joypad[MAX_PORTS][MAX_JOYPAD_BUTTONS];
    _Atomic int pointerX;
    _Atomic int pointerY;
    _Atomic bool pointerPressed;

    CoreVariable variables[MAX_VARIABLES];
    int variableCount;
} CoreCtx;

// libretro callbacks are plain C function pointers with no user-data
// parameter, so we stash the one active CoreCtx here. This is safe because
// the whole point of splitting into two separate .so files (see the header
// comment) is that at most one core - and therefore one CoreCtx - is ever
// alive at a time.
static CoreCtx *g_ctx = NULL;

static CoreVariable *findVariable(CoreCtx *ctx, const char *key) {
    for (int i = 0; i < ctx->variableCount; i++) {
        if (strcmp(ctx->variables[i].key, key) == 0) {
            return &ctx->variables[i];
        }
    }
    return NULL;
}

static void setVariable(CoreCtx *ctx, const char *key, const char *value) {
    CoreVariable *existing = findVariable(ctx, key);
    if (existing) {
        snprintf(existing->value, sizeof(existing->value), "%s", value);
        return;
    }
    if (ctx->variableCount >= MAX_VARIABLES) {
        LOGW("Too many core variables, dropping %s", key);
        return;
    }
    CoreVariable *slot = &ctx->variables[ctx->variableCount++];
    snprintf(slot->key, sizeof(slot->key), "%s", key);
    snprintf(slot->value, sizeof(slot->value), "%s", value);
}

// Overrides applied on top of whatever default a core registers for itself,
// keyed by the exact option-key strings each project defines:
//  - melonDS DS ("melonds_sysfile_mode"): defaults to "native" (requires a
//    real DS BIOS/firmware dump). We force "builtin" so the core uses its
//    own built-in BIOS/firmware replacements instead - this app never asks
//    the user for BIOS files.
// Cores that don't define a given key simply never look it up, so this list
// is harmless to keep for every core.
static void applyForcedVariableOverrides(CoreCtx *ctx) {
    setVariable(ctx, "melonds_sysfile_mode", "builtin");
}

// Parses one legacy retro_variable.value string, formatted by the core as
// "Human description; defaultValue|otherValue|otherValue2", and records
// just the default. This is the RETRO_ENVIRONMENT_SET_VARIABLES (core
// options v0) format - see the comment on RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION
// below for why every core ends up using this specific, simplest format
// when talking to this frontend.
static void recordDefaultFromLegacyDescriptor(CoreCtx *ctx, const char *key, const char *descriptor) {
    const char *semicolon = strchr(descriptor, ';');
    if (!semicolon) {
        return;
    }
    const char *value = semicolon + 1;
    while (*value == ' ') value++;
    const char *pipe = strchr(value, '|');
    size_t len = pipe ? (size_t) (pipe - value) : strlen(value);
    if (len >= MAX_VAR_VALUE) len = MAX_VAR_VALUE - 1;

    char buf[MAX_VAR_VALUE];
    memcpy(buf, value, len);
    buf[len] = '\0';

    // Never clobber a value we've already forced (e.g. via
    // applyForcedVariableOverrides) with the core's own default.
    if (!findVariable(ctx, key)) {
        setVariable(ctx, key, buf);
    }
}

static uint32_t convertPixel(const uint8_t *src, enum retro_pixel_format format) {
    switch (format) {
        case RETRO_PIXEL_FORMAT_XRGB8888: {
            uint32_t p;
            memcpy(&p, src, 4);
            uint8_t a = (uint8_t) (p >> 24);
            uint8_t r = (uint8_t) (p >> 16);
            uint8_t g = (uint8_t) (p >> 8);
            uint8_t b = (uint8_t) p;
            (void) a;
            // Android Bitmap.Config.ARGB_8888 memory byte order is R,G,B,A.
            return (uint32_t) r | ((uint32_t) g << 8) | ((uint32_t) b << 16) | (0xFFu << 24);
        }
        case RETRO_PIXEL_FORMAT_RGB565: {
            uint16_t p;
            memcpy(&p, src, 2);
            uint8_t r = (uint8_t) (((p >> 11) & 0x1F) * 255 / 31);
            uint8_t g = (uint8_t) (((p >> 5) & 0x3F) * 255 / 63);
            uint8_t b = (uint8_t) ((p & 0x1F) * 255 / 31);
            return (uint32_t) r | ((uint32_t) g << 8) | ((uint32_t) b << 16) | (0xFFu << 24);
        }
        case RETRO_PIXEL_FORMAT_0RGB1555:
        default: {
            uint16_t p;
            memcpy(&p, src, 2);
            uint8_t r = (uint8_t) (((p >> 10) & 0x1F) * 255 / 31);
            uint8_t g = (uint8_t) (((p >> 5) & 0x1F) * 255 / 31);
            uint8_t b = (uint8_t) ((p & 0x1F) * 255 / 31);
            return (uint32_t) r | ((uint32_t) g << 8) | ((uint32_t) b << 16) | (0xFFu << 24);
        }
    }
}

static void videoRefreshCb(const void *data, unsigned width, unsigned height, size_t pitch) {
    CoreCtx *ctx = g_ctx;
    if (!ctx || !ctx->videoBuffer) {
        return;
    }
    if (!data || data == RETRO_HW_FRAME_BUFFER_VALID) {
        // No software framebuffer this frame (e.g. a HW/OpenGL-rendered
        // frame). We disable HW rendering for every core we build, so this
        // should not normally happen; skip rather than read garbage.
        return;
    }
    if (width > ctx->videoBufferMaxWidth) width = ctx->videoBufferMaxWidth;
    if (height > ctx->videoBufferMaxHeight) height = ctx->videoBufferMaxHeight;

    unsigned bpp = ctx->pixelFormat == RETRO_PIXEL_FORMAT_XRGB8888 ? 4 : 2;
    const uint8_t *src = (const uint8_t *) data;
    for (unsigned y = 0; y < height; y++) {
        const uint8_t *row = src + (size_t) y * pitch;
        uint32_t *dstRow = ctx->videoBuffer + (size_t) y * ctx->videoBufferMaxWidth;
        for (unsigned x = 0; x < width; x++) {
            dstRow[x] = convertPixel(row + (size_t) x * bpp, ctx->pixelFormat);
        }
    }
    atomic_store_explicit(&ctx->lastFrameWidth, width, memory_order_release);
    atomic_store_explicit(&ctx->lastFrameHeight, height, memory_order_release);
}

static void audioSampleCb(int16_t left, int16_t right) {
    CoreCtx *ctx = g_ctx;
    if (ctx) {
        ring_buffer_push(&ctx->audioRing, left, right);
    }
}

static size_t audioSampleBatchCb(const int16_t *data, size_t frames) {
    CoreCtx *ctx = g_ctx;
    if (ctx) {
        for (size_t i = 0; i < frames; i++) {
            ring_buffer_push(&ctx->audioRing, data[i * 2], data[i * 2 + 1]);
        }
    }
    return frames;
}

static void inputPollCb(void) {
    // Nothing to do: Kotlin already writes directly into ctx->joypad /
    // ctx->pointer* asynchronously via nativeSetButton/nativeSetPointer.
    // This callback exists only because libretro requires the frontend to
    // register one.
}

static int16_t inputStateCb(unsigned port, unsigned device, unsigned index, unsigned id) {
    (void) index;
    CoreCtx *ctx = g_ctx;
    if (!ctx || port >= MAX_PORTS) {
        return 0;
    }
    if (device == RETRO_DEVICE_JOYPAD) {
        if (id >= MAX_JOYPAD_BUTTONS) return 0;
        return atomic_load_explicit(&ctx->joypad[port][id], memory_order_acquire) ? 1 : 0;
    }
    if (device == RETRO_DEVICE_POINTER && port == 0) {
        switch (id) {
            case RETRO_DEVICE_ID_POINTER_X:
                return (int16_t) atomic_load_explicit(&ctx->pointerX, memory_order_acquire);
            case RETRO_DEVICE_ID_POINTER_Y:
                return (int16_t) atomic_load_explicit(&ctx->pointerY, memory_order_acquire);
            case RETRO_DEVICE_ID_POINTER_PRESSED:
                return atomic_load_explicit(&ctx->pointerPressed, memory_order_acquire) ? 1 : 0;
            default:
                return 0;
        }
    }
    return 0;
}

static bool environmentCb(unsigned cmd, void *data) {
    CoreCtx *ctx = g_ctx;
    if (!ctx) {
        return false;
    }

    switch (cmd) {
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: {
            enum retro_pixel_format fmt = *(const enum retro_pixel_format *) data;
            ctx->pixelFormat = fmt;
            return true;
        }

        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY: {
            *(const char **) data = ctx->systemDir[0] ? ctx->systemDir : NULL;
            return ctx->systemDir[0] != '\0';
        }

        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY: {
            *(const char **) data = ctx->saveDir[0] ? ctx->saveDir : NULL;
            return ctx->saveDir[0] != '\0';
        }

        // Deliberately unimplemented (return false): this is what pushes
        // every core down to the oldest, simplest core-options registration
        // path (RETRO_ENVIRONMENT_SET_VARIABLES, a flat "key -> description;
        // default|other|other2" string array) instead of the newer v1/v2
        // APIs, which use much more complex category/subcategory structs we
        // would otherwise have to parse for no benefit - we don't expose a
        // core-options UI, we only need to (a) read each option's default
        // and (b) force a couple of specific keys (see
        // applyForcedVariableOverrides).
        case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION:
            return false;

        case RETRO_ENVIRONMENT_SET_VARIABLES: {
            const struct retro_variable *vars = (const struct retro_variable *) data;
            for (int i = 0; vars[i].key; i++) {
                recordDefaultFromLegacyDescriptor(ctx, vars[i].key, vars[i].value);
            }
            applyForcedVariableOverrides(ctx);
            return true;
        }

        case RETRO_ENVIRONMENT_GET_VARIABLE: {
            struct retro_variable *var = (struct retro_variable *) data;
            CoreVariable *found = var->key ? findVariable(ctx, var->key) : NULL;
            var->value = found ? found->value : NULL;
            return found != NULL;
        }

        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE: {
            // We never change variables after load, so nothing is ever "updated".
            *(bool *) data = false;
            return true;
        }

        case RETRO_ENVIRONMENT_SET_GEOMETRY:
        case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO: {
            const struct retro_game_geometry *geo =
                cmd == RETRO_ENVIRONMENT_SET_GEOMETRY
                    ? (const struct retro_game_geometry *) data
                    : &((const struct retro_system_av_info *) data)->geometry;
            ctx->geometry.base_width = geo->base_width;
            ctx->geometry.base_height = geo->base_height;
            if (geo->aspect_ratio > 0) {
                ctx->geometry.aspect_ratio = geo->aspect_ratio;
            }
            if (cmd == RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO) {
                ctx->timing = ((const struct retro_system_av_info *) data)->timing;
            }
            return true;
        }

        case RETRO_ENVIRONMENT_SET_MESSAGE: {
            const struct retro_message *msg = (const struct retro_message *) data;
            if (msg && msg->msg) LOGI("core message: %s", msg->msg);
            return true;
        }

        case RETRO_ENVIRONMENT_SET_MESSAGE_EXT: {
            const struct retro_message_ext *msg = (const struct retro_message_ext *) data;
            if (msg && msg->msg) LOGI("core message: %s", msg->msg);
            return true;
        }

        case RETRO_ENVIRONMENT_GET_LOG_INTERFACE: {
            // We don't hand out a real callback here (would need an extra
            // trampoline for the varargs signature); returning false makes
            // well-behaved cores fall back to stderr, which is harmless.
            return false;
        }

        case RETRO_ENVIRONMENT_GET_CAN_DUPE: {
            *(bool *) data = true;
            return true;
        }

        case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS:
            return false;

        case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
        case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
        case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_DISPLAY:
        case RETRO_ENVIRONMENT_SET_SUBSYSTEM_INFO:
            return true;

        default:
            return false;
    }
}

JNIEXPORT jlong JNICALL
Java_com_kino_gbaemu_core_LibretroCore_nativeLoadCore(JNIEnv *env, jobject thiz, jstring corePath) {
    (void) thiz;
    const char *pathC = (*env)->GetStringUTFChars(env, corePath, NULL);

    void *so = dlopen(pathC, RTLD_NOW | RTLD_LOCAL);
    if (!so) {
        LOGE("dlopen(%s) failed: %s", pathC, dlerror());
        (*env)->ReleaseStringUTFChars(env, corePath, pathC);
        return 0;
    }
    LOGI("Loaded core %s", pathC);
    (*env)->ReleaseStringUTFChars(env, corePath, pathC);

    CoreCtx *ctx = calloc(1, sizeof(CoreCtx));
    if (!ctx) {
        dlclose(so);
        return 0;
    }
    ctx->soHandle = so;
    // Spec-mandated default when a core never calls
    // RETRO_ENVIRONMENT_SET_PIXEL_FORMAT at all; in practice both cores we
    // ship call it explicitly, but this keeps convertPixel() correct either way.
    ctx->pixelFormat = RETRO_PIXEL_FORMAT_0RGB1555;

#define RESOLVE(fn) \
    do { \
        *(void **) (&ctx->fn) = dlsym(so, #fn); \
        if (!ctx->fn) { \
            LOGE("dlsym(" #fn ") failed: %s", dlerror()); \
            dlclose(so); \
            free(ctx); \
            return 0; \
        } \
    } while (0)

    RESOLVE(retro_init);
    RESOLVE(retro_deinit);
    RESOLVE(retro_api_version);
    RESOLVE(retro_get_system_info);
    RESOLVE(retro_get_system_av_info);
    RESOLVE(retro_set_environment);
    RESOLVE(retro_set_video_refresh);
    RESOLVE(retro_set_audio_sample);
    RESOLVE(retro_set_audio_sample_batch);
    RESOLVE(retro_set_input_poll);
    RESOLVE(retro_set_input_state);
    RESOLVE(retro_set_controller_port_device);
    RESOLVE(retro_reset);
    RESOLVE(retro_run);
    RESOLVE(retro_serialize_size);
    RESOLVE(retro_serialize);
    RESOLVE(retro_unserialize);
    RESOLVE(retro_load_game);
    RESOLVE(retro_unload_game);
    RESOLVE(retro_get_memory_data);
    RESOLVE(retro_get_memory_size);
#undef RESOLVE

    if (!ring_buffer_init(&ctx->audioRing, AUDIO_RING_CAPACITY_FRAMES)) {
        LOGE("Failed to allocate audio ring buffer");
        dlclose(so);
        free(ctx);
        return 0;
    }

    g_ctx = ctx;
    ctx->retro_set_environment(environmentCb);
    ctx->retro_set_video_refresh(videoRefreshCb);
    ctx->retro_set_audio_sample(audioSampleCb);
    ctx->retro_set_audio_sample_batch(audioSampleBatchCb);
    ctx->retro_set_input_poll(inputPollCb);
    ctx->retro_set_input_state(inputStateCb);

    ctx->retro_init();

    struct retro_system_info sysInfo = {0};
    ctx->retro_get_system_info(&sysInfo);
    LOGI("Core: %s %s (need_fullpath=%d)", sysInfo.library_name ? sysInfo.library_name : "?",
         sysInfo.library_version ? sysInfo.library_version : "?", sysInfo.need_fullpath);

    return (jlong) (intptr_t) ctx;
}

JNIEXPORT void JNICALL
Java_com_kino_gbaemu_core_LibretroCore_nativeSetDirectories(JNIEnv *env, jobject thiz, jlong handle, jstring systemDir, jstring saveDir) {
    (void) thiz;
    CoreCtx *ctx = (CoreCtx *) (intptr_t) handle;
    if (!ctx) return;

    const char *sysC = (*env)->GetStringUTFChars(env, systemDir, NULL);
    snprintf(ctx->systemDir, sizeof(ctx->systemDir), "%s", sysC);
    (*env)->ReleaseStringUTFChars(env, systemDir, sysC);

    const char *saveC = (*env)->GetStringUTFChars(env, saveDir, NULL);
    snprintf(ctx->saveDir, sizeof(ctx->saveDir), "%s", saveC);
    (*env)->ReleaseStringUTFChars(env, saveDir, saveC);
}

JNIEXPORT jboolean JNICALL
Java_com_kino_gbaemu_core_LibretroCore_nativeLoadGame(JNIEnv *env, jobject thiz, jlong handle, jstring romPath) {
    (void) thiz;
    CoreCtx *ctx = (CoreCtx *) (intptr_t) handle;
    if (!ctx) return JNI_FALSE;

    const char *romPathC = (*env)->GetStringUTFChars(env, romPath, NULL);

    struct retro_game_info info = {0};
    info.path = romPathC;

    bool ok = ctx->retro_load_game(&info);
    (*env)->ReleaseStringUTFChars(env, romPath, romPathC);

    if (!ok) {
        LOGE("retro_load_game failed");
        return JNI_FALSE;
    }

    struct retro_system_av_info avInfo = {0};
    ctx->retro_get_system_av_info(&avInfo);
    ctx->geometry = avInfo.geometry;
    ctx->timing = avInfo.timing;

    unsigned maxW = avInfo.geometry.max_width ? avInfo.geometry.max_width : avInfo.geometry.base_width;
    unsigned maxH = avInfo.geometry.max_height ? avInfo.geometry.max_height : avInfo.geometry.base_height;
    if (maxW == 0 || maxH == 0) {
        LOGE("Core reported zero video geometry");
        return JNI_FALSE;
    }

    ctx->videoBuffer = calloc((size_t) maxW * maxH, sizeof(uint32_t));
    if (!ctx->videoBuffer) {
        LOGE("Failed to allocate video buffer %ux%u", maxW, maxH);
        return JNI_FALSE;
    }
    ctx->videoBufferMaxWidth = maxW;
    ctx->videoBufferMaxHeight = maxH;
    atomic_store_explicit(&ctx->lastFrameWidth, avInfo.geometry.base_width, memory_order_release);
    atomic_store_explicit(&ctx->lastFrameHeight, avInfo.geometry.base_height, memory_order_release);

    LOGI("Loaded game: base=%ux%u max=%ux%u fps=%.4f rate=%.1f",
         avInfo.geometry.base_width, avInfo.geometry.base_height, maxW, maxH,
         avInfo.timing.fps, avInfo.timing.sample_rate);

    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_kino_gbaemu_core_LibretroCore_nativeUnloadGame(JNIEnv *env, jobject thiz, jlong handle) {
    (void) env;
    (void) thiz;
    CoreCtx *ctx = (CoreCtx *) (intptr_t) handle;
    if (ctx) {
        ctx->retro_unload_game();
    }
}

JNIEXPORT void JNICALL
Java_com_kino_gbaemu_core_LibretroCore_nativeUnloadCore(JNIEnv *env, jobject thiz, jlong handle) {
    (void) env;
    (void) thiz;
    CoreCtx *ctx = (CoreCtx *) (intptr_t) handle;
    if (!ctx) return;

    ctx->retro_deinit();
    ring_buffer_destroy(&ctx->audioRing);
    free(ctx->videoBuffer);
    if (ctx->soHandle) {
        dlclose(ctx->soHandle);
    }
    if (g_ctx == ctx) {
        g_ctx = NULL;
    }
    free(ctx);
}

JNIEXPORT void JNICALL
Java_com_kino_gbaemu_core_LibretroCore_nativeRunFrame(JNIEnv *env, jobject thiz, jlong handle) {
    (void) env;
    (void) thiz;
    CoreCtx *ctx = (CoreCtx *) (intptr_t) handle;
    if (ctx) {
        ctx->retro_run();
    }
}

JNIEXPORT void JNICALL
Java_com_kino_gbaemu_core_LibretroCore_nativeReset(JNIEnv *env, jobject thiz, jlong handle) {
    (void) env;
    (void) thiz;
    CoreCtx *ctx = (CoreCtx *) (intptr_t) handle;
    if (ctx) {
        ctx->retro_reset();
    }
}

JNIEXPORT jobject JNICALL
Java_com_kino_gbaemu_core_LibretroCore_nativeGetVideoBuffer(JNIEnv *env, jobject thiz, jlong handle) {
    (void) thiz;
    CoreCtx *ctx = (CoreCtx *) (intptr_t) handle;
    if (!ctx || !ctx->videoBuffer) return NULL;
    return (*env)->NewDirectByteBuffer(env, ctx->videoBuffer,
                                        (jlong) ctx->videoBufferMaxWidth * ctx->videoBufferMaxHeight * 4);
}

JNIEXPORT jint JNICALL
Java_com_kino_gbaemu_core_LibretroCore_nativeGetMaxWidth(JNIEnv *env, jobject thiz, jlong handle) {
    (void) env; (void) thiz;
    CoreCtx *ctx = (CoreCtx *) (intptr_t) handle;
    return ctx ? (jint) ctx->videoBufferMaxWidth : 0;
}

JNIEXPORT jint JNICALL
Java_com_kino_gbaemu_core_LibretroCore_nativeGetMaxHeight(JNIEnv *env, jobject thiz, jlong handle) {
    (void) env; (void) thiz;
    CoreCtx *ctx = (CoreCtx *) (intptr_t) handle;
    return ctx ? (jint) ctx->videoBufferMaxHeight : 0;
}

JNIEXPORT jint JNICALL
Java_com_kino_gbaemu_core_LibretroCore_nativeGetFrameWidth(JNIEnv *env, jobject thiz, jlong handle) {
    (void) env; (void) thiz;
    CoreCtx *ctx = (CoreCtx *) (intptr_t) handle;
    return ctx ? (jint) atomic_load_explicit(&ctx->lastFrameWidth, memory_order_acquire) : 0;
}

JNIEXPORT jint JNICALL
Java_com_kino_gbaemu_core_LibretroCore_nativeGetFrameHeight(JNIEnv *env, jobject thiz, jlong handle) {
    (void) env; (void) thiz;
    CoreCtx *ctx = (CoreCtx *) (intptr_t) handle;
    return ctx ? (jint) atomic_load_explicit(&ctx->lastFrameHeight, memory_order_acquire) : 0;
}

JNIEXPORT jdouble JNICALL
Java_com_kino_gbaemu_core_LibretroCore_nativeGetFps(JNIEnv *env, jobject thiz, jlong handle) {
    (void) env; (void) thiz;
    CoreCtx *ctx = (CoreCtx *) (intptr_t) handle;
    return (ctx && ctx->timing.fps > 0) ? ctx->timing.fps : 60.0;
}

JNIEXPORT jdouble JNICALL
Java_com_kino_gbaemu_core_LibretroCore_nativeGetSampleRate(JNIEnv *env, jobject thiz, jlong handle) {
    (void) env; (void) thiz;
    CoreCtx *ctx = (CoreCtx *) (intptr_t) handle;
    return (ctx && ctx->timing.sample_rate > 0) ? ctx->timing.sample_rate : 32000.0;
}

JNIEXPORT void JNICALL
Java_com_kino_gbaemu_core_LibretroCore_nativeSetButton(JNIEnv *env, jobject thiz, jlong handle, jint port, jint id, jboolean pressed) {
    (void) env; (void) thiz;
    CoreCtx *ctx = (CoreCtx *) (intptr_t) handle;
    if (!ctx || port < 0 || port >= MAX_PORTS || id < 0 || id >= MAX_JOYPAD_BUTTONS) return;
    atomic_store_explicit(&ctx->joypad[port][id], pressed == JNI_TRUE, memory_order_release);
}

JNIEXPORT void JNICALL
Java_com_kino_gbaemu_core_LibretroCore_nativeSetPointer(JNIEnv *env, jobject thiz, jlong handle, jint x, jint y, jboolean pressed) {
    (void) env; (void) thiz;
    CoreCtx *ctx = (CoreCtx *) (intptr_t) handle;
    if (!ctx) return;
    atomic_store_explicit(&ctx->pointerX, x, memory_order_release);
    atomic_store_explicit(&ctx->pointerY, y, memory_order_release);
    atomic_store_explicit(&ctx->pointerPressed, pressed == JNI_TRUE, memory_order_release);
}

JNIEXPORT jint JNICALL
Java_com_kino_gbaemu_core_LibretroCore_nativePopAudio(JNIEnv *env, jobject thiz, jlong handle, jshortArray out, jint maxFrames) {
    (void) thiz;
    CoreCtx *ctx = (CoreCtx *) (intptr_t) handle;
    if (!ctx || maxFrames <= 0) return 0;

    jshort *buffer = (*env)->GetShortArrayElements(env, out, NULL);
    if (!buffer) return 0;
    size_t copied = ring_buffer_pop(&ctx->audioRing, (int16_t *) buffer, (size_t) maxFrames);
    (*env)->ReleaseShortArrayElements(env, out, buffer, 0);
    return (jint) copied;
}

JNIEXPORT jlong JNICALL
Java_com_kino_gbaemu_core_LibretroCore_nativeSerializeSize(JNIEnv *env, jobject thiz, jlong handle) {
    (void) env; (void) thiz;
    CoreCtx *ctx = (CoreCtx *) (intptr_t) handle;
    return ctx ? (jlong) ctx->retro_serialize_size() : 0;
}

JNIEXPORT jbyteArray JNICALL
Java_com_kino_gbaemu_core_LibretroCore_nativeSerialize(JNIEnv *env, jobject thiz, jlong handle) {
    (void) thiz;
    CoreCtx *ctx = (CoreCtx *) (intptr_t) handle;
    if (!ctx) return NULL;

    size_t size = ctx->retro_serialize_size();
    if (!size) return NULL;

    void *buf = malloc(size);
    if (!buf) return NULL;

    jbyteArray result = NULL;
    if (ctx->retro_serialize(buf, size)) {
        result = (*env)->NewByteArray(env, (jsize) size);
        if (result) {
            (*env)->SetByteArrayRegion(env, result, 0, (jsize) size, (const jbyte *) buf);
        }
    }
    free(buf);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_kino_gbaemu_core_LibretroCore_nativeUnserialize(JNIEnv *env, jobject thiz, jlong handle, jbyteArray data) {
    (void) thiz;
    CoreCtx *ctx = (CoreCtx *) (intptr_t) handle;
    if (!ctx || !data) return JNI_FALSE;

    jsize length = (*env)->GetArrayLength(env, data);
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (!bytes) return JNI_FALSE;

    jboolean ok = ctx->retro_unserialize(bytes, (size_t) length) ? JNI_TRUE : JNI_FALSE;
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
    return ok;
}

JNIEXPORT jlong JNICALL
Java_com_kino_gbaemu_core_LibretroCore_nativeGetSaveRamSize(JNIEnv *env, jobject thiz, jlong handle) {
    (void) env; (void) thiz;
    CoreCtx *ctx = (CoreCtx *) (intptr_t) handle;
    return ctx ? (jlong) ctx->retro_get_memory_size(RETRO_MEMORY_SAVE_RAM) : 0;
}

JNIEXPORT jbyteArray JNICALL
Java_com_kino_gbaemu_core_LibretroCore_nativeReadSaveRam(JNIEnv *env, jobject thiz, jlong handle) {
    (void) thiz;
    CoreCtx *ctx = (CoreCtx *) (intptr_t) handle;
    if (!ctx) return NULL;

    size_t size = ctx->retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);
    void *ptr = ctx->retro_get_memory_data(RETRO_MEMORY_SAVE_RAM);
    if (!size || !ptr) return NULL;

    jbyteArray result = (*env)->NewByteArray(env, (jsize) size);
    if (result) {
        (*env)->SetByteArrayRegion(env, result, 0, (jsize) size, (const jbyte *) ptr);
    }
    return result;
}

JNIEXPORT void JNICALL
Java_com_kino_gbaemu_core_LibretroCore_nativeWriteSaveRam(JNIEnv *env, jobject thiz, jlong handle, jbyteArray data) {
    (void) thiz;
    CoreCtx *ctx = (CoreCtx *) (intptr_t) handle;
    if (!ctx || !data) return;

    size_t coreSize = ctx->retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);
    void *ptr = ctx->retro_get_memory_data(RETRO_MEMORY_SAVE_RAM);
    if (!coreSize || !ptr) return;

    jsize length = (*env)->GetArrayLength(env, data);
    size_t toCopy = (size_t) length < coreSize ? (size_t) length : coreSize;

    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (!bytes) return;
    memcpy(ptr, bytes, toCopy);
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
}
