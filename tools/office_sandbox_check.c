/* SPDX-License-Identifier: GPL-3.0-or-later */
#define _GNU_SOURCE 1
#include "../app/src/main/cpp/office_sandbox.h"
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/sendfile.h>
#include <sys/stat.h>
#include <sys/statfs.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <unistd.h>

static const char *secret_path;
static atomic_int ready,go,thread_ok;
static void *other_thread(void *unused) {
    (void)unused;atomic_store(&ready,1);
    while(!atomic_load(&go))sched_yield();
    errno=0;int fd=open(secret_path,O_RDONLY);
    atomic_store(&thread_ok,fd<0 && errno==EACCES);
    if(fd>=0)close(fd);
    return NULL;
}
static void fail(int pipe,int code) { ssize_t ignored=write(pipe,&code,sizeof(code));(void)ignored;_exit(1); }
#define CHECK(value) do { ++checks;if(!(value))fail(report,checks); } while(0)
static void child(int socket,int report,int logger,const char *job,const char *secret,const char *apk,const char *library) {
    int checks=0;
    /* Android starts the real worker through zygote, not fork. Drop the host
     * harness's inherited broker/root descriptors to model that boundary. */
    for(int fd=3;fd<1024;++fd)if(fd!=socket && fd!=report && fd!=logger)close(fd);
    secret_path=secret;pthread_t thread;
    CHECK(pthread_create(&thread,NULL,other_thread,NULL)==0);
    while(!atomic_load(&ready))sched_yield();
    CHECK(capt_sandbox_install(socket,job)==1);
    atomic_store(&go,1);
    CHECK(pthread_join(thread,NULL)==0 && atomic_load(&thread_ok));
    char name[8192];snprintf(name,sizeof(name),"%s/output",job);
    int fd=open(name,O_RDWR|O_CREAT|O_TRUNC,0600);CHECK(fd>=0);
    CHECK(write(fd,"own",3)==3);CHECK(lseek(fd,0,SEEK_SET)==0);
    int duplicate=dup(fd);CHECK(duplicate>=0);CHECK(close(duplicate)==0);
    char bytes[8]={0};CHECK(read(fd,bytes,3)==3 && !memcmp(bytes,"own",3));CHECK(close(fd)==0);
    CHECK(open(secret,O_RDONLY)<0 && errno==EACCES);
    CHECK(syscall(SYS_openat,AT_FDCWD,secret,O_RDONLY,0)<0 && errno==EACCES);
    CHECK(open("../secret",O_RDONLY)<0 && errno==EACCES);
    CHECK(open("../job-other/secret",O_RDONLY)<0 && errno==EACCES);
    CHECK(open("file-link",O_RDONLY)<0 && errno==EACCES);
    CHECK(open("dir-link/secret",O_RDONLY)<0 && errno==EACCES);
    CHECK(open("/proc/self/mem",O_RDONLY)<0 && errno==EACCES);
    CHECK(open("/proc/self/fd",O_RDONLY|O_DIRECTORY)<0 && errno==EACCES);
    CHECK(open("/dev/zero",O_RDONLY)<0 && errno==EACCES);
    int dir=open(job,O_RDONLY|O_DIRECTORY);CHECK(dir>=0);
    fd=openat(dir,"output",O_RDONLY);CHECK(fd>=0);close(fd);
    CHECK(openat(dir,"../secret",O_RDONLY)<0 && errno==EACCES);
    CHECK(openat(dir,"file-link",O_RDONLY)<0 && errno==EACCES);
    CHECK(mkdir("subdir",0700)==0);CHECK(chdir("subdir")==0);
    CHECK(getcwd(name,sizeof(name)) && strstr(name,"/job/subdir"));
    fd=open("relative",O_WRONLY|O_CREAT,0600);CHECK(fd>=0);close(fd);
    CHECK(rename("relative","renamed")==0);CHECK(unlink("renamed")==0);
    CHECK(chdir(".. ")<0);CHECK(chdir("..")==0);CHECK(rmdir("subdir")==0);
    struct { struct stat st; unsigned char canary[16]; } metadata;
    memset(&metadata,0x55,sizeof(metadata));CHECK(stat("output",&metadata.st)==0 && S_ISREG(metadata.st.st_mode));
    for(size_t i=0;i<sizeof(metadata.canary);++i)CHECK(metadata.canary[i]==0x55);
    CHECK(stat(secret,&metadata.st)<0 && errno==EACCES);
    struct statfs space;CHECK(statfs(job,&space)==0 && space.f_bsize>0);
    CHECK(unlink(secret)<0 && errno==EACCES);CHECK(chmod(secret,0600)<0 && errno==EACCES);
    CHECK(truncate(secret,0)<0 && errno==EACCES);
    CHECK(rename("output",secret)<0 && errno==EACCES);
    CHECK(symlink(secret,"new-link")<0 && errno==EACCES);CHECK(link(secret,"hard-link")<0 && errno==EACCES);
    fd=open(apk,O_RDONLY);CHECK(fd>=0);close(fd);
    CHECK(open(apk,O_WRONLY|O_TRUNC)<0 && errno==EACCES);
    fd=open(library,O_RDONLY);CHECK(fd>=0);close(fd);
    CHECK(open(library,O_WRONLY|O_TRUNC)<0 && errno==EACCES);
    CHECK(socketpair(AF_UNIX,SOCK_STREAM,0,(int[2]){0})<0 && errno==EACCES);
    CHECK(write(logger,"PRIVATE_SENTINEL",16)<0 && errno==EACCES);
    struct iovec vec={(void *)"PRIVATE_SENTINEL",16};CHECK(writev(logger,&vec,1)<0 && errno==EACCES);
    CHECK(send(logger,"PRIVATE_SENTINEL",16,0)<0 && errno==EACCES);
    CHECK(dup(logger)<0 && errno==EACCES);CHECK(fcntl(logger,F_DUPFD,3)<0 && errno==EACCES);
    fd=open("output",O_RDONLY);CHECK(fd>=0);
    CHECK(sendfile(logger,fd,NULL,3)<0 && errno==EACCES);close(fd);
    CHECK(kill(getppid(),SIGUSR1)<0 && errno==EACCES);
    CHECK(fork()<0 && errno==EACCES);
    CHECK(open("/dev/urandom",O_RDONLY)>=0);
    /* Failed broker must never trigger an unsandboxed local-open fallback. */
    CHECK(shutdown(socket,SHUT_RDWR)==0);
    CHECK(open("output",O_RDONLY)<0 && errno==EACCES);
    ssize_t ignored=write(report,&checks,sizeof(checks));(void)ignored;_exit(0);
}
static void file(const char *path) { int fd=open(path,O_CREAT|O_WRONLY,0600);if(fd<0)exit(2);if(write(fd,"secret",6)!=6)exit(2);close(fd); }
int main(int argc,char **argv) {
    if(argc!=2)return 64;
    char job[4096],secret[4096],apk[4096],libs[4096],library[4096],path[4096],alias[4096];
    snprintf(job,sizeof(job),"%s/job",argv[1]);snprintf(secret,sizeof(secret),"%s/secret",argv[1]);snprintf(apk,sizeof(apk),"%s/base.apk",argv[1]);snprintf(libs,sizeof(libs),"%s/libs",argv[1]);snprintf(library,sizeof(library),"%s/libs-link/resource",argv[1]);snprintf(alias,sizeof(alias),"%s/libs-link",argv[1]);
    if(mkdir(job,0700)||mkdir(libs,0700))return 2;
    file(secret);file(apk);snprintf(path,sizeof(path),"%s/libs/resource",argv[1]);file(path);
    if(symlink(libs,alias))return 2;
    snprintf(path,sizeof(path),"%s/job/file-link",argv[1]);if(symlink(secret,path))return 2;
    snprintf(path,sizeof(path),"%s/job/dir-link",argv[1]);if(symlink(argv[1],path))return 2;
    int client=-1,report[2],logger[2];
    capt_broker *broker=capt_broker_start(job,apk,alias,&client);
    if(!broker || pipe(report) || socketpair(AF_UNIX,SOCK_DGRAM,0,logger))return 2;
    pid_t pid=fork();if(pid<0)return 2;
    if(!pid)child(client,report[1],logger[1],job,secret,apk,library);
    close(client);close(report[1]);close(logger[1]);
    int status=0,checks=0;ssize_t count=read(report[0],&checks,sizeof(checks));waitpid(pid,&status,0);
    capt_broker_stop(broker);
    if(count!=sizeof(checks) || !WIFEXITED(status) || WEXITSTATUS(status)) { fprintf(stderr,"SANDBOX_FAILED check=%d status=%d bytes=%zd\n",checks,status,count);return 1; }
    char buffer[32];if(recv(logger[0],buffer,sizeof(buffer),MSG_DONTWAIT)>=0 || errno!=EAGAIN)return 1;
    int fd=open(secret,O_RDONLY);if(fd<0 || read(fd,buffer,6)!=6 || memcmp(buffer,"secret",6))return 1;close(fd);
    int pair[2];if(socketpair(AF_UNIX,SOCK_STREAM,0,pair))return 1;close(pair[0]);close(pair[1]);
    printf("CAPT_OFFICE_SANDBOX_HOST_PASSED checks=%d (real TSYNC/filter/broker; parent filesystem/network unchanged)\n",checks);
    return 0;
}
