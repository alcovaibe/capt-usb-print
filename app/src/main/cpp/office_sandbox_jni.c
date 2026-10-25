/* SPDX-License-Identifier: GPL-3.0-or-later */
#define _GNU_SOURCE 1
#include <jni.h>
#include <stdint.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include "office_sandbox.h"

JNIEXPORT jlongArray JNICALL Java_com_cryonum_captusbprint_office_OfficeSandbox_startNative
  (JNIEnv *env,jobject self,jstring directory,jstring apk,jstring libraries) {
    (void)self;
    if(!directory || !apk || !libraries)return NULL;
    const char *root=(*env)->GetStringUTFChars(env,directory,NULL);
    const char *code=(*env)->GetStringUTFChars(env,apk,NULL);
    const char *libs=(*env)->GetStringUTFChars(env,libraries,NULL);
    int fd=-1;
    capt_broker *broker=(root && code && libs)?capt_broker_start(root,code,libs,&fd):NULL;
    if(root)(*env)->ReleaseStringUTFChars(env,directory,root);
    if(code)(*env)->ReleaseStringUTFChars(env,apk,code);
    if(libs)(*env)->ReleaseStringUTFChars(env,libraries,libs);
    if(!broker)return NULL;
    jlong values[]={(jlong)(uintptr_t)broker,fd};
    jlongArray result=(*env)->NewLongArray(env,2);
    if(result)(*env)->SetLongArrayRegion(env,result,0,2,values);
    if(!result || (*env)->ExceptionCheck(env)) { close(fd);capt_broker_stop(broker);return NULL; }
    return result;
}
JNIEXPORT void JNICALL Java_com_cryonum_captusbprint_office_OfficeSandbox_stopNative
  (JNIEnv *env,jobject self,jlong handle) {
    (void)env;(void)self;capt_broker_stop((capt_broker *)(uintptr_t)handle);
}
JNIEXPORT void JNICALL Java_com_cryonum_captusbprint_office_OfficeSandbox_attachNative
  (JNIEnv *env,jobject self,jlong handle,jint pid) {
    (void)env;(void)self;capt_broker_set_worker((capt_broker *)(uintptr_t)handle,pid);
}
JNIEXPORT jint JNICALL Java_com_cryonum_captusbprint_office_OfficeSandbox_deniedNative
  (JNIEnv *env,jobject self,jlong handle) {
    (void)env;(void)self;return capt_broker_denied((capt_broker *)(uintptr_t)handle);
}
JNIEXPORT jint JNICALL Java_com_cryonum_captusbprint_office_OfficeSandbox_installNative
  (JNIEnv *env,jobject self,jint socket_fd,jstring directory) {
    (void)self;
    /* A caller cannot accidentally confine the UI/USB process. */
    char name[512]; int fd=open("/proc/self/cmdline",O_RDONLY|O_CLOEXEC);
    if(fd<0 || !directory)return 0;
    ssize_t n=read(fd,name,sizeof(name)-1);close(fd);
    if(n<=0)return 0;
    name[n]=0;
    size_t length=strlen(name);
    if(length<7 || strcmp(name+length-7,":office"))return 0;
    const char *root=(*env)->GetStringUTFChars(env,directory,NULL);
    if(!root)return 0;
    int result=capt_sandbox_install(socket_fd,root);
    (*env)->ReleaseStringUTFChars(env,directory,root);
    return result;
}
