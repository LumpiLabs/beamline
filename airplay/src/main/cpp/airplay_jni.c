/*
 * JNI glue between UxPlay's server (lib/raop.h) and io.github.lumpilabs.beamline.airplay.AirPlayServer.
 * UxPlay calls its callbacks on its own threads; each is attached to the JVM once and detached when
 * it ends. Video and sound buffers are copied once, into a Java byte array.
 */
#include <jni.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <android/log.h>

#include "dnssd.h"
#include "dnssd_android.h"
#include "logger.h"
#include "raop.h"
#include "stream.h"

#define TAG "UxPlay"
#define HW_ADDR_LEN 6

typedef struct {
    raop_t *raop;
    dnssd_t *dnssd;
    jobject server;
    raop_callbacks_t callbacks;
} server_t;

static JavaVM *jvm;
static pthread_key_t env_key;

static jmethodID on_client_request;
static jmethodID on_connection_opened;
static jmethodID on_connection_closed;
static jmethodID on_connection_reset;
static jmethodID on_mirror_running;
static jmethodID on_video_format;
static jmethodID on_video;
static jmethodID on_video_reset;
static jmethodID on_audio_format;
static jmethodID on_audio;

static void detach_thread(void *env)
{
    (void) env;
    (*jvm)->DetachCurrentThread(jvm);
}

/* The JNIEnv of the calling thread, attaching UxPlay's threads on first use. */
static JNIEnv *thread_env(void)
{
    JNIEnv *env = NULL;
    if ((*jvm)->GetEnv(jvm, (void **) &env, JNI_VERSION_1_6) == JNI_OK) {
        return env;
    }
    JavaVMAttachArgs args = { JNI_VERSION_1_6, "UxPlay", NULL };
    if ((*jvm)->AttachCurrentThread(jvm, &env, &args) != JNI_OK) {
        return NULL;
    }
    pthread_setspecific(env_key, env);
    return env;
}

static void clear_exception(JNIEnv *env)
{
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }
}

static jbyteArray byte_array(JNIEnv *env, const void *data, int length)
{
    jbyteArray array = (*env)->NewByteArray(env, length);
    if (array) {
        (*env)->SetByteArrayRegion(env, array, 0, length, (const jbyte *) data);
    }
    return array;
}

static jstring string_or_null(JNIEnv *env, const char *s)
{
    return s ? (*env)->NewStringUTF(env, s) : NULL;
}

/* Calls a void method on the AirPlayServer behind cls, from any thread. */
#define CALL_VOID(cls, method, ...)                                                   \
    do {                                                                              \
        JNIEnv *env_ = thread_env();                                                  \
        if (env_) {                                                                   \
            (*env_)->CallVoidMethod(env_, ((server_t *) (cls))->server, method, ##__VA_ARGS__); \
            clear_exception(env_);                                                    \
        }                                                                             \
    } while (0)

static void log_callback(void *cls, int level, const char *msg)
{
    (void) cls;
    int priority = level <= LOGGER_ERR ? ANDROID_LOG_ERROR
                 : level == LOGGER_WARNING ? ANDROID_LOG_WARN
                 : level <= LOGGER_INFO ? ANDROID_LOG_INFO
                 : ANDROID_LOG_DEBUG;
    __android_log_write(priority, TAG, msg);
}

static void conn_init(void *cls)
{
    CALL_VOID(cls, on_connection_opened);
}

static void conn_destroy(void *cls)
{
    CALL_VOID(cls, on_connection_closed);
}

static void conn_reset(void *cls, int reason)
{
    CALL_VOID(cls, on_connection_reset, (jint) reason);
}

static void conn_feedback(void *cls)
{
    (void) cls;
}

static void report_client_request(void *cls, char *device_id, char *model, char *name, bool *admit)
{
    JNIEnv *env = thread_env();
    *admit = false;
    if (!env) return;
    jstring j_device_id = string_or_null(env, device_id);
    jstring j_model = string_or_null(env, model);
    jstring j_name = string_or_null(env, name);
    *admit = (*env)->CallBooleanMethod(env, ((server_t *) cls)->server, on_client_request,
                                       j_device_id, j_model, j_name);
    clear_exception(env);
    (*env)->DeleteLocalRef(env, j_device_id);
    (*env)->DeleteLocalRef(env, j_model);
    (*env)->DeleteLocalRef(env, j_name);
}

static void mirror_video_running(void *cls, bool is_running)
{
    CALL_VOID(cls, on_mirror_running, (jboolean) is_running);
}

static int video_set_codec_and_size(void *cls, video_codec_t codec, unsigned short width_source,
                                    unsigned short height_source, unsigned short x_offset,
                                    unsigned short y_offset, unsigned short width, unsigned short height)
{
    (void) x_offset;
    (void) y_offset;
    (void) width;
    (void) height;
    if (codec != VIDEO_CODEC_H264 && codec != VIDEO_CODEC_H265) {
        return -1;
    }
    CALL_VOID(cls, on_video_format, (jboolean) (codec == VIDEO_CODEC_H265),
              (jint) width_source, (jint) height_source);
    return 0;
}

static void video_process(void *cls, raop_ntp_t *ntp, video_decode_struct *data)
{
    (void) ntp;
    JNIEnv *env = thread_env();
    if (!env || data->data_len <= 0) return;
    jbyteArray nals = byte_array(env, data->data, data->data_len);
    if (!nals) {
        clear_exception(env);
        return;
    }
    (*env)->CallVoidMethod(env, ((server_t *) cls)->server, on_video, nals, (jboolean) data->is_h265,
                           (jint) data->nal_count, (jlong) data->ntp_time_local, (jlong) data->ntp_time_remote);
    clear_exception(env);
    (*env)->DeleteLocalRef(env, nals);
}

static void video_reset(void *cls, reset_type_t reset_type)
{
    CALL_VOID(cls, on_video_reset, (jint) reset_type);
}

static void video_pause(void *cls) { (void) cls; }
static void video_resume(void *cls) { (void) cls; }
static void video_flush(void *cls) { (void) cls; }

static void audio_get_format(void *cls, unsigned char *ct, unsigned short *spf, bool *using_screen,
                             bool *is_media, uint64_t *audio_format)
{
    CALL_VOID(cls, on_audio_format, (jint) *ct, (jint) *spf, (jboolean) *using_screen,
              (jboolean) *is_media, (jlong) *audio_format);
}

static void audio_process(void *cls, raop_ntp_t *ntp, audio_decode_struct *data)
{
    (void) ntp;
    JNIEnv *env = thread_env();
    if (!env || data->data_len <= 0) return;
    jbyteArray frame = byte_array(env, data->data, data->data_len);
    if (!frame) {
        clear_exception(env);
        return;
    }
    (*env)->CallVoidMethod(env, ((server_t *) cls)->server, on_audio, frame, (jint) data->ct,
                           (jlong) data->ntp_time_local, (jlong) data->ntp_time_remote);
    clear_exception(env);
    (*env)->DeleteLocalRef(env, frame);
}

static void audio_flush(void *cls) { (void) cls; }
static double audio_set_client_volume(void *cls) { (void) cls; return 0.0; }
static void audio_set_volume(void *cls, float volume) { (void) cls; (void) volume; }
static void audio_set_metadata(void *cls, const void *buffer, int buflen) { (void) cls; (void) buffer; (void) buflen; }
static void audio_set_coverart(void *cls, const void *buffer, int buflen) { (void) cls; (void) buffer; (void) buflen; }
static void audio_stop_coverart_rendering(void *cls) { (void) cls; }
static void audio_remote_control_id(void *cls, const char *dacp_id, const char *active_remote)
{
    (void) cls; (void) dacp_id; (void) active_remote;
}
static void audio_set_progress(void *cls, uint32_t *start, uint32_t *curr, uint32_t *end)
{
    (void) cls; (void) start; (void) curr; (void) end;
}
static void export_dacp(void *cls, const char *active_remote, const char *dacp_id)
{
    (void) cls; (void) active_remote; (void) dacp_id;
}

/* No PIN or password yet (a later step): every client may pair, and returning ones are welcome. */
static void display_pin(void *cls, char *pin) { (void) cls; (void) pin; }
static void register_client(void *cls, const char *device_id, const char *pk, const char *name)
{
    (void) cls; (void) device_id; (void) pk; (void) name;
}
static bool check_register(void *cls, const char *pk) { (void) cls; (void) pk; return true; }
static const char *passwd(void *cls, int *len) { (void) cls; *len = 0; return NULL; }

/* AirPlay video (HLS) is a later step and isn't advertised; these only keep UxPlay's calls safe. */
static void on_video_play(void *cls, const char *location, const float start) { (void) cls; (void) location; (void) start; }
static void on_video_scrub(void *cls, const float position) { (void) cls; (void) position; }
static void on_video_rate(void *cls, const float rate) { (void) cls; (void) rate; }
static void on_video_stop(void *cls) { (void) cls; }
static void on_video_acquire_playback_info(void *cls, playback_info_t *info) { (void) cls; (void) info; }
static float on_video_playlist_remove(void *cls) { (void) cls; return 0.0f; }
static void get_custom_profile(void *cls, const char **profile) { (void) cls; *profile = NULL; }

static void destroy(JNIEnv *env, server_t *server)
{
    if (server->raop) {
        raop_destroy(server->raop);
    }
    if (server->dnssd) {
        dnssd_unregister_raop(server->dnssd);
        dnssd_unregister_airplay(server->dnssd);
        dnssd_destroy(server->dnssd);
    }
    if (server->server) {
        (*env)->DeleteGlobalRef(env, server->server);
    }
    free(server);
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved)
{
    (void) reserved;
    JNIEnv *env;
    jvm = vm;
    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    if (pthread_key_create(&env_key, detach_thread)) {
        return JNI_ERR;
    }
    jclass cls = (*env)->FindClass(env, "io/github/lumpilabs/beamline/airplay/AirPlayServer");
    if (!cls) return JNI_ERR;
    on_client_request = (*env)->GetMethodID(env, cls, "onClientRequest",
                                            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Z");
    on_connection_opened = (*env)->GetMethodID(env, cls, "onConnectionOpened", "()V");
    on_connection_closed = (*env)->GetMethodID(env, cls, "onConnectionClosed", "()V");
    on_connection_reset = (*env)->GetMethodID(env, cls, "onConnectionReset", "(I)V");
    on_mirror_running = (*env)->GetMethodID(env, cls, "onMirrorRunning", "(Z)V");
    on_video_format = (*env)->GetMethodID(env, cls, "onVideoFormat", "(ZII)V");
    on_video = (*env)->GetMethodID(env, cls, "onVideo", "([BZIJJ)V");
    on_video_reset = (*env)->GetMethodID(env, cls, "onVideoReset", "(I)V");
    on_audio_format = (*env)->GetMethodID(env, cls, "onAudioFormat", "(IIZZJ)V");
    on_audio = (*env)->GetMethodID(env, cls, "onAudio", "([BIJJ)V");
    if ((*env)->ExceptionCheck(env)) {
        return JNI_ERR;
    }
    (*env)->DeleteLocalRef(env, cls);
    return JNI_VERSION_1_6;
}

JNIEXPORT jlong JNICALL
Java_io_github_lumpilabs_beamline_airplay_AirPlayServer_nativeStart(
    JNIEnv *env, jobject thiz, jstring j_name, jbyteArray j_hw_addr, jstring j_key_file,
    jint width, jint height, jint fps, jboolean h265, jboolean debug)
{
    if ((*env)->GetArrayLength(env, j_hw_addr) != HW_ADDR_LEN) {
        return 0;
    }
    char hw_addr[HW_ADDR_LEN];
    (*env)->GetByteArrayRegion(env, j_hw_addr, 0, HW_ADDR_LEN, (jbyte *) hw_addr);
    char device_id[3 * HW_ADDR_LEN];
    snprintf(device_id, sizeof(device_id), "%02x:%02x:%02x:%02x:%02x:%02x",
             (unsigned char) hw_addr[0], (unsigned char) hw_addr[1], (unsigned char) hw_addr[2],
             (unsigned char) hw_addr[3], (unsigned char) hw_addr[4], (unsigned char) hw_addr[5]);

    server_t *server = calloc(1, sizeof(server_t));
    if (!server) return 0;
    server->server = (*env)->NewGlobalRef(env, thiz);

    raop_callbacks_t *cb = &server->callbacks;
    cb->cls = server;
    cb->conn_init = conn_init;
    cb->conn_destroy = conn_destroy;
    cb->conn_reset = conn_reset;
    cb->conn_feedback = conn_feedback;
    cb->audio_process = audio_process;
    cb->video_process = video_process;
    cb->audio_flush = audio_flush;
    cb->video_flush = video_flush;
    cb->video_pause = video_pause;
    cb->video_resume = video_resume;
    cb->audio_set_client_volume = audio_set_client_volume;
    cb->audio_set_volume = audio_set_volume;
    cb->audio_get_format = audio_get_format;
    cb->audio_set_metadata = audio_set_metadata;
    cb->audio_set_coverart = audio_set_coverart;
    cb->audio_stop_coverart_rendering = audio_stop_coverart_rendering;
    cb->audio_remote_control_id = audio_remote_control_id;
    cb->audio_set_progress = audio_set_progress;
    cb->report_client_request = report_client_request;
    cb->display_pin = display_pin;
    cb->register_client = register_client;
    cb->check_register = check_register;
    cb->passwd = passwd;
    cb->export_dacp = export_dacp;
    cb->video_reset = video_reset;
    cb->video_set_codec_and_size = video_set_codec_and_size;
    cb->mirror_video_running = mirror_video_running;
    cb->on_video_play = on_video_play;
    cb->on_video_scrub = on_video_scrub;
    cb->on_video_rate = on_video_rate;
    cb->on_video_stop = on_video_stop;
    cb->on_video_acquire_playback_info = on_video_acquire_playback_info;
    cb->on_video_playlist_remove = on_video_playlist_remove;
    cb->get_custom_profile = get_custom_profile;

    const char *name = (*env)->GetStringUTFChars(env, j_name, NULL);
    const char *key_file = (*env)->GetStringUTFChars(env, j_key_file, NULL);
    int error = 0;
    unsigned short port = 0;

    server->dnssd = dnssd_init(name, (int) strlen(name), hw_addr, HW_ADDR_LEN, 0, &error);
    if (!server->dnssd || error) goto fail;
    /* The features UxPlay's defaults leave as they are, apart from H.265 (bit 42,
       SupportsScreenMultiCodec): iOS 27 clients may send H.265 and fail without it. */
    dnssd_set_airplay_features(server->dnssd, 42, h265 ? 1 : 0);

    server->raop = raop_init(cb);
    if (!server->raop) goto fail;
    raop_set_log_callback(server->raop, log_callback, NULL);
    raop_set_log_level(server->raop, debug ? LOGGER_DEBUG : LOGGER_INFO);
    if (raop_init2(server->raop, 1, device_id, key_file)) {
        /* raop_init2 frees nothing on failure, but leaves raop unusable for raop_destroy */
        free(server->raop);
        server->raop = NULL;
        goto fail;
    }
    raop_set_plist(server->raop, "width", width);
    raop_set_plist(server->raop, "height", height);
    raop_set_plist(server->raop, "refreshRate", fps);
    raop_set_plist(server->raop, "maxFPS", fps);

    if (raop_start_httpd(server->raop, &port) < 0) goto fail;
    raop_set_port(server->raop, port);
    raop_set_dnssd(server->raop, server->dnssd);
    if (dnssd_register_raop(server->dnssd, port) || dnssd_register_airplay(server->dnssd, port)) goto fail;

    (*env)->ReleaseStringUTFChars(env, j_name, name);
    (*env)->ReleaseStringUTFChars(env, j_key_file, key_file);
    __android_log_print(ANDROID_LOG_INFO, TAG, "AirPlay server on port %u, features 0x%llx", port,
                        (unsigned long long) dnssd_get_airplay_features(server->dnssd));
    return (jlong) (intptr_t) server;

fail:
    __android_log_print(ANDROID_LOG_ERROR, TAG, "AirPlay server didn't start (dnssd error %d)", error);
    (*env)->ReleaseStringUTFChars(env, j_name, name);
    (*env)->ReleaseStringUTFChars(env, j_key_file, key_file);
    destroy(env, server);
    return 0;
}

JNIEXPORT jstring JNICALL
Java_io_github_lumpilabs_beamline_airplay_AirPlayServer_nativeServiceName(
    JNIEnv *env, jobject thiz, jlong handle, jint service)
{
    (void) thiz;
    server_t *server = (server_t *) (intptr_t) handle;
    return string_or_null(env, dnssd_android_service_name(server->dnssd, service));
}

JNIEXPORT jbyteArray JNICALL
Java_io_github_lumpilabs_beamline_airplay_AirPlayServer_nativeServiceTxt(
    JNIEnv *env, jobject thiz, jlong handle, jint service)
{
    (void) thiz;
    server_t *server = (server_t *) (intptr_t) handle;
    int length = 0;
    const char *txt = service == DNSSD_SERVICE_RAOP ? dnssd_get_raop_txt(server->dnssd, &length)
                                                    : dnssd_get_airplay_txt(server->dnssd, &length);
    return byte_array(env, txt, length);
}

JNIEXPORT jint JNICALL
Java_io_github_lumpilabs_beamline_airplay_AirPlayServer_nativePort(
    JNIEnv *env, jobject thiz, jlong handle, jint service)
{
    (void) env;
    (void) thiz;
    server_t *server = (server_t *) (intptr_t) handle;
    return dnssd_android_service_port(server->dnssd, service);
}

/* After a lost or failed connection, as UxPlay's main loop does: drop the client's state and
   listen again on the same port. Not to be called from one of UxPlay's callbacks. */
JNIEXPORT jboolean JNICALL
Java_io_github_lumpilabs_beamline_airplay_AirPlayServer_nativeRestart(
    JNIEnv *env, jobject thiz, jlong handle)
{
    (void) env;
    (void) thiz;
    server_t *server = (server_t *) (intptr_t) handle;
    raop_stop_httpd(server->raop);
    raop_remove_known_connections(server->raop);
    unsigned short port = raop_get_port(server->raop);
    if (raop_start_httpd(server->raop, &port) < 0) {
        return JNI_FALSE;
    }
    raop_set_port(server->raop, port);
    /* should the old port be taken meanwhile; Kotlin announces the services again when it changed */
    if (dnssd_register_raop(server->dnssd, port) || dnssd_register_airplay(server->dnssd, port)) {
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_io_github_lumpilabs_beamline_airplay_AirPlayServer_nativeStop(
    JNIEnv *env, jobject thiz, jlong handle)
{
    (void) thiz;
    destroy(env, (server_t *) (intptr_t) handle);
}
