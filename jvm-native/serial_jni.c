/*
 * JNI-обёртка над serial_core.
 *
 * Класс: pw.binom.serial.jni.SerialNative
 *
 * Соглашение: методы возвращают int; отрицательное значение — код ошибки
 * SERIAL_ERR_*, ноль/положительное — результат. Handle передаётся как long
 * (на POSIX это fd, на Windows — HANDLE, приведённый к intptr_t).
 */
#include <jni.h>
#include <stdlib.h>
#include <string.h>

#include "serial_core.h"

static void throw_illegal_state(JNIEnv *env, const char *message) {
    jclass cls = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (cls != NULL) {
        (*env)->ThrowNew(env, cls, message);
    }
}

JNIEXPORT jlong JNICALL
Java_pw_binom_serial_jni_SerialNative_open(JNIEnv *env, jclass cls,
                                           jstring path,
                                           jint baud_rate,
                                           jint data_bits,
                                           jint parity,
                                           jint stop_bits,
                                           jint flow_control,
                                           jint read_timeout_ms,
                                           jint write_timeout_ms) {
    (void) cls;
    if (path == NULL) {
        throw_illegal_state(env, "path is null");
        return SERIAL_ERR_ARG;
    }

    const char *native_path = (*env)->GetStringUTFChars(env, path, NULL);
    if (native_path == NULL) return SERIAL_ERR_ARG;

    serial_config_t cfg;
    cfg.baud_rate = baud_rate;
    cfg.data_bits = data_bits;
    cfg.parity = parity;
    cfg.stop_bits = stop_bits;
    cfg.flow_control = flow_control;
    cfg.read_timeout_ms = read_timeout_ms;
    cfg.write_timeout_ms = write_timeout_ms;

    intptr_t handle = serial_open(native_path, &cfg);
    (*env)->ReleaseStringUTFChars(env, path, native_path);
    return (jlong) handle;
}

JNIEXPORT jint JNICALL
Java_pw_binom_serial_jni_SerialNative_read(JNIEnv *env, jclass cls,
                                           jlong handle, jbyteArray buffer,
                                           jint offset, jint length,
                                           jint timeout_ms) {
    (void) cls;
    if (buffer == NULL) return SERIAL_ERR_ARG;

    jsize capacity = (*env)->GetArrayLength(env, buffer);
    if (offset < 0 || length < 0 || offset + length > capacity) return SERIAL_ERR_ARG;

    void *raw = malloc((size_t) length);
    if (raw == NULL) return SERIAL_ERR_IO;

    int result = serial_read((intptr_t) handle, raw, length, timeout_ms);
    if (result > 0) {
        (*env)->SetByteArrayRegion(env, buffer, offset, result, (const jbyte *) raw);
    }
    free(raw);
    return result;
}

JNIEXPORT jint JNICALL
Java_pw_binom_serial_jni_SerialNative_write(JNIEnv *env, jclass cls,
                                            jlong handle, jbyteArray buffer,
                                            jint offset, jint length,
                                            jint timeout_ms) {
    (void) cls;
    if (buffer == NULL) return SERIAL_ERR_ARG;

    jsize capacity = (*env)->GetArrayLength(env, buffer);
    if (offset < 0 || length < 0 || offset + length > capacity) return SERIAL_ERR_ARG;

    void *raw = malloc((size_t) length);
    if (raw == NULL) return SERIAL_ERR_IO;

    (*env)->GetByteArrayRegion(env, buffer, offset, length, (jbyte *) raw);
    int result = serial_write((intptr_t) handle, raw, length, timeout_ms);
    free(raw);
    return result;
}

JNIEXPORT jint JNICALL
Java_pw_binom_serial_jni_SerialNative_flush(JNIEnv *env, jclass cls, jlong handle) {
    (void) env;
    (void) cls;
    return serial_flush((intptr_t) handle);
}

JNIEXPORT jint JNICALL
Java_pw_binom_serial_jni_SerialNative_drain(JNIEnv *env, jclass cls, jlong handle) {
    (void) env;
    (void) cls;
    return serial_drain((intptr_t) handle);
}

JNIEXPORT jint JNICALL
Java_pw_binom_serial_jni_SerialNative_close(JNIEnv *env, jclass cls, jlong handle) {
    (void) env;
    (void) cls;
    return serial_close((intptr_t) handle);
}

JNIEXPORT jobjectArray JNICALL
Java_pw_binom_serial_jni_SerialNative_list(JNIEnv *env, jclass cls) {
    (void) cls;

    jclass string_class = (*env)->FindClass(env, "java/lang/String");
    if (string_class == NULL) return NULL;

    /* Буфер на 64 порта по 256 символов — с запасом. */
    const int capacity = 64 * 256;
    char *buffer = (char *) malloc((size_t) capacity);
    if (buffer == NULL) {
        return (*env)->NewObjectArray(env, 0, string_class, NULL);
    }

    int total = serial_list(buffer, capacity);
    if (total <= 0) {
        free(buffer);
        return (*env)->NewObjectArray(env, 0, string_class, NULL);
    }

    /* Считаем количество строк. */
    int count = 0;
    for (int offset = 0; offset < total; offset += (int) strlen(buffer + offset) + 1) {
        count++;
    }

    jobjectArray result = (*env)->NewObjectArray(env, count, string_class, NULL);
    if (result == NULL) {
        free(buffer);
        return NULL;
    }

    int index = 0;
    for (int offset = 0; offset < total && index < count; offset += (int) strlen(buffer + offset) + 1) {
        jstring item = (*env)->NewStringUTF(env, buffer + offset);
        if (item != NULL) {
            (*env)->SetObjectArrayElement(env, result, index, item);
            (*env)->DeleteLocalRef(env, item);
        }
        index++;
    }

    free(buffer);
    return result;
}

JNIEXPORT jstring JNICALL
Java_pw_binom_serial_jni_SerialNative_errorText(JNIEnv *env, jclass cls, jint code) {
    (void) cls;
    return (*env)->NewStringUTF(env, serial_error_text(code));
}
