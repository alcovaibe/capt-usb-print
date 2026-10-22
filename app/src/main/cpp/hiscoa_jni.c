/*
 * JNI bridge to the GPLv3-or-later HISCOA compressor.
 * Vendored upstream files are kept intact; no CUPS dependency or USB access.
 */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include "upstream/hiscoa-compress.h"

static void invalid(JNIEnv *env, const char *msg) {
    jclass cls = (*env)->FindClass(env, "java/lang/IllegalArgumentException");
    if (cls) (*env)->ThrowNew(env, cls, msg);
}

JNIEXPORT jbyteArray JNICALL
Java_com_cryonum_captusbprint_core_HiscoaNative_encodeBand(
    JNIEnv *env, jobject self, jbyteArray pixels, jint line_size, jint nlines, jboolean last) {
    (void)self;
    if (!pixels || line_size < 8 || line_size > 8192 || nlines < 1 || nlines > 128) {
        invalid(env, "HISCOA dimensions invalid");
        return NULL;
    }
    jsize input_size = (*env)->GetArrayLength(env, pixels);
    if ((int64_t)line_size * (int64_t)nlines != input_size || input_size > 131072) {
        invalid(env, "HISCOA input size mismatch or too large");
        return NULL;
    }
    size_t cap = (size_t)input_size * 2 + 64;
    uint8_t *src = (uint8_t *)malloc((size_t)input_size);
    uint8_t *dst = (uint8_t *)malloc(cap);
    if (!src || !dst) {
        free(src); free(dst);
        invalid(env, "HISCOA allocation failed");
        return NULL;
    }
    (*env)->GetByteArrayRegion(env, pixels, 0, input_size, (jbyte *)src);
    if ((*env)->ExceptionCheck(env)) {
        free(src); free(dst);
        return NULL;
    }
    size_t compressed = hiscoa_compress_band(dst, cap, src, (unsigned)line_size,
        (unsigned)nlines, last ? HISCOA_EOB_LAST : HISCOA_EOB_NORMAL,
        &hiscoa_default_params);
    free(src);
    if (!compressed || compressed > cap || compressed > INT32_MAX) {
        free(dst);
        invalid(env, "HISCOA invalid compressed size");
        return NULL;
    }
    jbyteArray out = (*env)->NewByteArray(env, (jsize)compressed);
    if (out) (*env)->SetByteArrayRegion(env, out, 0, (jsize)compressed, (const jbyte *)dst);
    free(dst);
    return out;
}
