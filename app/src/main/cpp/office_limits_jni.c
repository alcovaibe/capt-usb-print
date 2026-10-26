/* SPDX-License-Identifier: GPL-3.0-or-later */
#define _GNU_SOURCE 1
#include <jni.h>
#include <string.h>
#include <sys/mman.h>
#include "office_limits_core.h"

static int installed = 0;
static int office_process(void) {
    char name[512];
    int fd = open("/proc/self/cmdline", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;
    ssize_t n = read(fd, name, sizeof(name)-1); close(fd);
    if (n <= 0) return 0;
    name[n] = '\0';
    size_t length = strlen(name);
    return length >= 7 && !strcmp(name+length-7, ":office");
}
JNIEXPORT jint JNICALL Java_com_cryonum_captusbprint_office_OfficeKernelLimits_installNative
  (JNIEnv *env, jobject self, jstring marker, jlong file_bytes, jlong cpu_seconds, jlong growth) {
    (void)self;
    if (installed || !office_process() || !marker) return 0;
    const char *path = (*env)->GetStringUTFChars(env, marker, NULL);
    if (!path) return 0;
    int fd = open(path, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW, 0600);
    (*env)->ReleaseStringUTFChars(env, marker, path);
    if (fd < 0) return 0;
    installed = 1; /* A partially configured process must also never be reused. */
    if (file_bytes < 0 || cpu_seconds < 0 || growth < 0 ||
        !capt_apply_limits(fd, (uint64_t)file_bytes, (uint64_t)cpu_seconds, (uint64_t)growth)) {
        close(fd); capt_limit_event_fd = -1; return 0;
    }
    return 1; /* Keep the event FD until process exit, including asynchronous limit signals. */
}
JNIEXPORT jlongArray JNICALL Java_com_cryonum_captusbprint_office_OfficeKernelLimits_snapshotNative
  (JNIEnv *env, jobject self) {
    (void)self;
    struct rlimit file, cpu, address;
    if (getrlimit(RLIMIT_FSIZE,&file) || getrlimit(RLIMIT_CPU,&cpu) || getrlimit(RLIMIT_AS,&address)) return NULL;
    jlong values[] = {(jlong)file.rlim_cur,(jlong)file.rlim_max,(jlong)cpu.rlim_cur,
        (jlong)cpu.rlim_max,(jlong)address.rlim_cur,(jlong)address.rlim_max,(jlong)capt_vm_bytes()};
    jlongArray result = (*env)->NewLongArray(env,7);
    if (result) (*env)->SetLongArrayRegion(env,result,0,7,values);
    return result;
}
JNIEXPORT jboolean JNICALL Java_com_cryonum_captusbprint_office_OfficeKernelLimits_probeDeniedMappingNative
  (JNIEnv *env, jobject self, jlong size) {
    (void)env; (void)self;
    if (!installed || !office_process() || size < 1 || size > (1LL<<30)) return JNI_FALSE;
    errno = 0;
    void *mapping = mmap(NULL,(size_t)size,PROT_NONE,MAP_PRIVATE | MAP_ANONYMOUS,-1,0);
    if (mapping == MAP_FAILED) return errno == ENOMEM ? JNI_TRUE : JNI_FALSE;
    munmap(mapping,(size_t)size); return JNI_FALSE;
}
JNIEXPORT jlong JNICALL Java_com_cryonum_captusbprint_office_OfficeKernelLimits_probeCommittedMemoryNative
  (JNIEnv *env, jobject self) {
    (void)env;(void)self;
    if(!installed || !office_process())return -1;
    return (jlong)capt_probe_committed_memory();
}
