/* SPDX-License-Identifier: GPL-3.0-or-later
 * Filesystem broker for the disposable Office process. Never parses Office data.
 * seccomp redirects pathname syscalls to a different process; only that process
 * can open the job directory. Denied calls return EACCES, never a document path.
 */
#define _GNU_SOURCE 1
#include "office_sandbox.h"
#include <errno.h>
#include <dirent.h>
#include <fcntl.h>
#include <limits.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <pthread.h>
#include <sched.h>
#include <signal.h>
#include <stdatomic.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/statfs.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <ucontext.h>
#include <unistd.h>
#ifndef SYS_SECCOMP
#define SYS_SECCOMP 1
#endif

enum { OPEN = 1, STAT, ACCESS, READLINK, MKDIR, UNLINK, RENAME, CHMOD, STATFS, CHDIR };
enum { READ = 1, WRITE = 2, META = 4 };
#define WIRE_VERSION 1
#define MAX_ROOTS 16
typedef struct {
    uint32_t version;
    int op, flags, mode, at[2];
    char path[2][PATH_MAX];
} request;
typedef struct {
    int error, length;
    unsigned char data[PATH_MAX];
} response;
typedef struct { char path[PATH_MAX], logical[PATH_MAX]; int fd, access, exact; } root_entry;
struct capt_broker {
    int socket;
    pthread_t thread;
    root_entry roots[MAX_ROOTS];
    int count;
    char job[PATH_MAX];
    atomic_int worker_pid, denied;
};

/* Component boundaries matter: /job-elsewhere is not /job. */
static int beneath(const char *path, const char *root) {
    size_t n = strlen(root);
    return !strncmp(path, root, n) && (path[n] == '\0' || path[n] == '/' || !strcmp(root,"/"));
}
static int normalize(const char *input, char *out) {
    if (!input || input[0] != '/') return 0;
    size_t n = 1; out[0] = '/'; out[1] = 0;
    for (size_t i = 1; input[i];) {
        while (input[i] == '/') ++i;
        size_t begin = i;
        while (input[i] && input[i] != '/') ++i;
        size_t len = i-begin;
        if (!len || (len==1 && input[begin]=='.')) continue;
        if (len==2 && input[begin]=='.' && input[begin+1]=='.') {
            if (n > 1) { while (n>1 && out[n-1]!='/') --n; if (n>1) --n; }
            out[n]=0; continue;
        }
        if (n + len + 2 >= PATH_MAX) return 0;
        if (n>1) out[n++]='/';
        memcpy(out+n,input+begin,len); n+=len; out[n]=0;
    }
    return 1;
}
static int add_root(capt_broker *b, const char *path, int access, int exact, int required) {
    if (!path || !*path || b->count >= MAX_ROOTS) return !required;
    root_entry *r = &b->roots[b->count];
    if(!normalize(path,r->logical))return !required;
    if (!realpath(path,r->path)) return !required;
    r->fd = open(r->path,O_PATH|O_NOFOLLOW|O_CLOEXEC);
    if (r->fd < 0) return !required;
    r->access=access; r->exact=exact; ++b->count; return 1;
}
/* No realpath on untrusted job paths: every directory is walked O_NOFOLLOW.
 * No symlink creation is permitted by the worker's filter. */
static int anchor(capt_broker *b, const char *path, int access, char *leaf) {
    root_entry *best = NULL;
    size_t matched=0;
    for (int i=0; i<b->count; ++i) {
        root_entry *r=&b->roots[i];
        const char *names[]={r->path,r->logical};
        for(int j=0;j<2;++j) {
            size_t length=strlen(names[j]);
            if ((r->exact ? !strcmp(path,names[j]) : beneath(path,names[j])) &&
                (r->access & access)==access && (!best || length>matched)) { best=r;matched=length; }
        }
    }
    if (!best) { errno=EACCES; return -1; }
    const char *relative=path+matched;
    if (best->exact) {
        /* Exact read-only files: return their parent and fixed basename. */
        char parent[PATH_MAX]; strcpy(parent,best->path);
        char *slash=strrchr(parent,'/'); strcpy(leaf,slash+1);
        if (slash==parent) slash[1]=0; else *slash=0;
        return open(parent,O_PATH|O_DIRECTORY|O_CLOEXEC);
    }
    while (*relative=='/') ++relative;
    int fd=fcntl(best->fd,F_DUPFD_CLOEXEC,3);
    if (fd<0) return -1;
    if (!*relative) { strcpy(leaf,"."); return fd; }
    while (1) {
        const char *slash=strchr(relative,'/');
        if (!slash) { strcpy(leaf,relative); return fd; }
        size_t len=(size_t)(slash-relative);
        char part[PATH_MAX]; memcpy(part,relative,len); part[len]=0;
        int next=openat(fd,part,O_PATH|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);
        close(fd); if (next<0) { if(errno==ELOOP || errno==ENOTDIR)errno=EACCES;return -1; } fd=next; relative=slash+1;
    }
}
static int absolute_path(const char *input, int fd, char *out) {
    if (input[0]=='/') return normalize(input,out);
    if (fd<0) return 0;
    char proc[64],base[PATH_MAX],joined[PATH_MAX];
    snprintf(proc,sizeof(proc),"/proc/self/fd/%d",fd);
    ssize_t n=readlink(proc,base,sizeof(base)-1);
    if (n<=0 || (size_t)n>=sizeof(base)-1) return 0;
    base[n]=0;
    if (snprintf(joined,sizeof(joined),"%s/%s",base,input)>=(int)sizeof(joined)) return 0;
    return normalize(joined,out);
}
static int ancestor(capt_broker *b, const char *path) {
    for (int i=0;i<b->count;++i) if (beneath(b->roots[i].path,path) || beneath(b->roots[i].logical,path)) return 1;
    return 0;
}
static void deny_resource(capt_broker *b,int op,const char *path) {
    /* Fixed categories only: never retain a document path in diagnostics. */
    const char *prefix[]={"/.cache","/.config","/tmp","/proc","/dev","/sys","/storage","/sdcard","/data/fonts","/data/app","/data/user","/data/data","/etc"};
    int kind=99;
    for(size_t i=0;i<sizeof(prefix)/sizeof(prefix[0]);++i)if(beneath(path,prefix[i])){kind=(int)i+1;break;}
    int expected=0;atomic_compare_exchange_strong(&b->denied,&expected,op*100+kind);
}
static int check_leaf(int dir,const char *leaf,int missing) {
    struct stat st;
    if (fstatat(dir,leaf,&st,AT_SYMLINK_NOFOLLOW)) return missing && errno==ENOENT;
    if (S_ISLNK(st.st_mode)) { errno=EACCES; return 0; }
    if (!S_ISREG(st.st_mode) && !S_ISDIR(st.st_mode) && !S_ISCHR(st.st_mode)) { errno=EACCES; return 0; }
    return 1;
}
static void serve(capt_broker *b, const request *q, const int *fds, int count, response *r, int *result_fd) {
    memset(r,0,sizeof(*r)); *result_fd=-1;
    if (q->version!=WIRE_VERSION || !memchr(q->path[0],0,PATH_MAX) || !memchr(q->path[1],0,PATH_MAX)) { r->error=EACCES; return; }
    char paths[2][PATH_MAX];
    int number=q->op==RENAME?2:1;
    for (int i=0;i<number;++i) {
        if (q->at[i]<0 || q->at[i]>count || !absolute_path(q->path[i],q->at[i]?fds[q->at[i]-1]:-1,paths[i])) { r->error=EACCES; return; }
    }
    /* Only the converter's own runtime metadata, never /proc/<parent>/...,
     * /proc/self/fd or /proc/self/mem. The broker must not reinterpret self. */
    if (!strncmp(paths[0],"/proc/self/",11)) {
        const char *suffix=paths[0]+11;
        int pid=atomic_load(&b->worker_pid);
        int permitted=!strcmp(suffix,"cmdline") || !strcmp(suffix,"status") || !strcmp(suffix,"stat") ||
            !strcmp(suffix,"statm") || !strcmp(suffix,"maps") || !strcmp(suffix,"auxv");
        if(pid>0 && ((permitted && q->op==OPEN && (q->flags&O_ACCMODE)==O_RDONLY && !(q->flags&(O_CREAT|O_TRUNC|O_APPEND))) ||
            (!strcmp(suffix,"exe") && q->op==READLINK))) {
            char translated[64];snprintf(translated,sizeof(translated),"/proc/%d/%.8s",pid,suffix);
            if(q->op==OPEN) { *result_fd=open(translated,O_RDONLY|O_CLOEXEC|O_NOFOLLOW);if(*result_fd<0)r->error=errno; }
            else { ssize_t n=readlink(translated,(char *)r->data,sizeof(r->data));if(n<0)r->error=errno;else r->length=(int)n; }
            return;
        }
    }
    int write_access=q->op==MKDIR || q->op==UNLINK || q->op==RENAME || q->op==CHMOD ||
        (q->op==OPEN && ((q->flags&O_ACCMODE)!=O_RDONLY || (q->flags&(O_CREAT|O_TRUNC|O_APPEND))));
    char leaf[PATH_MAX],second[PATH_MAX];
    int dir=anchor(b,paths[0],write_access?WRITE:READ,leaf), other=-1;
    if (dir<0) {
        /* Permit only metadata on the parents needed by realpath; never open/list them. */
        if ((q->op==STAT || q->op==READLINK || q->op==ACCESS) && ancestor(b,paths[0])) {
            if (q->op==READLINK) { r->error=EINVAL; return; }
            struct stat st;
            if (stat(paths[0],&st)) r->error=errno;
            else if (q->op==STAT) { memcpy(r->data,&st,sizeof(st)); r->length=sizeof(st); }
            else if (q->mode & W_OK) r->error=EACCES;
            return;
        }
        r->error=errno;
        if(q->op==OPEN || write_access)deny_resource(b,q->op,paths[0]);
        return;
    }
    errno=0; long result=-1;
    if (!check_leaf(dir,leaf,q->op==OPEN || q->op==MKDIR || q->op==RENAME)) { r->error=errno?errno:EACCES; if(r->error==EACCES)deny_resource(b,q->op,paths[0]);close(dir); return; }
    switch (q->op) {
        case OPEN: {
            /* No arbitrary device, FIFO or directory outside the allowlist. */
            int allowed=O_ACCMODE|O_APPEND|O_CREAT|O_EXCL|O_TRUNC|O_NONBLOCK|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC|O_LARGEFILE|O_SYNC|O_DSYNC|O_PATH;
            if ((q->flags&~allowed) || (q->flags&O_ACCMODE)==O_ACCMODE) { errno=EACCES; break; }
            result=openat(dir,leaf,q->flags|O_NOFOLLOW|O_CLOEXEC,0600);
            if (result>=0) { *result_fd=(int)result; result=0; }
            break;
        }
        case STAT: {
            struct stat st; result=fstatat(dir,leaf,&st,AT_SYMLINK_NOFOLLOW);
            if (!result) { memcpy(r->data,&st,sizeof(st)); r->length=sizeof(st); }
            break;
        }
        case ACCESS:
            if (q->mode&~(R_OK|W_OK|X_OK)) { errno=EINVAL; break; }
            if ((q->mode&W_OK) && !beneath(paths[0],b->job)) { errno=EACCES; break; }
            result=faccessat(dir,leaf,q->mode,0); break;
        case READLINK: errno=EINVAL; break; /* Symlinks are never imported into the job. */
        case MKDIR: result=mkdirat(dir,leaf,0700); break;
        case UNLINK: result=unlinkat(dir,leaf,q->flags&AT_REMOVEDIR); break;
        case RENAME:
            other=anchor(b,paths[1],WRITE,second);
            if (other>=0 && check_leaf(other,second,1)) result=renameat(dir,leaf,other,second);
            break;
        case CHMOD: result=fchmodat(dir,leaf,q->mode&0700,0); break;
        case STATFS: {
            struct statfs st; result=fstatfs(dir,&st);
            if (!result) { memcpy(r->data,&st,sizeof(st)); r->length=sizeof(st); }
            break;
        }
        case CHDIR: {
            struct stat st; result=fstatat(dir,leaf,&st,AT_SYMLINK_NOFOLLOW);
            if (!result && !S_ISDIR(st.st_mode)) { result=-1; errno=ENOTDIR; }
            if (!result) { r->length=(int)strlen(paths[0])+1; memcpy(r->data,paths[0],(size_t)r->length); }
            break;
        }
        default: errno=EACCES; break;
    }
    if (result<0) r->error=errno?errno:EACCES;
    if (other>=0) close(other);
    close(dir);
}
static void *broker_loop(void *pointer) {
    capt_broker *b=pointer;
    for (;;) {
        request q; response r;
        struct iovec vector={&q,sizeof(q)};
        union { struct cmsghdr align; char bytes[CMSG_SPACE(2*sizeof(int))]; } control;
        struct msghdr msg={0}; msg.msg_iov=&vector; msg.msg_iovlen=1; msg.msg_control=control.bytes; msg.msg_controllen=sizeof(control);
        ssize_t n=recvmsg(b->socket,&msg,MSG_CMSG_CLOEXEC);
        if (n<0 && errno==EINTR) continue;
        if (n<=0) break;
        int fds[2]={-1,-1},count=0,output=-1;
        for (struct cmsghdr *c=CMSG_FIRSTHDR(&msg);c;c=CMSG_NXTHDR(&msg,c)) {
            if (c->cmsg_level==SOL_SOCKET && c->cmsg_type==SCM_RIGHTS) {
                size_t length=(c->cmsg_len-CMSG_LEN(0))/sizeof(int);
                for (size_t i=0;i<length;++i) { int fd=((int *)CMSG_DATA(c))[i]; if (count<2) fds[count++]=fd; else close(fd); }
            }
        }
        if (n!=(ssize_t)sizeof(q) || (msg.msg_flags&(MSG_TRUNC|MSG_CTRUNC))) { memset(&r,0,sizeof(r)); r.error=EACCES; }
        else serve(b,&q,fds,count,&r,&output);
        for (int i=0;i<count;++i) close(fds[i]);
        vector.iov_base=&r; vector.iov_len=sizeof(r); memset(&msg,0,sizeof(msg)); msg.msg_iov=&vector; msg.msg_iovlen=1;
        if (output>=0) {
            memset(&control,0,sizeof(control)); msg.msg_control=control.bytes; msg.msg_controllen=CMSG_SPACE(sizeof(int));
            struct cmsghdr *c=CMSG_FIRSTHDR(&msg); c->cmsg_level=SOL_SOCKET; c->cmsg_type=SCM_RIGHTS; c->cmsg_len=CMSG_LEN(sizeof(int)); memcpy(CMSG_DATA(c),&output,sizeof(int));
        }
        do { n=sendmsg(b->socket,&msg,MSG_NOSIGNAL); } while(n<0 && errno==EINTR);
        if (output>=0) close(output);
        if (n!=(ssize_t)sizeof(r)) break;
    }
    return NULL;
}
capt_broker *capt_broker_start(const char *root,const char *apk,const char *libraries,int *client_fd) {
    capt_broker *b=calloc(1,sizeof(*b)); if (!b) return NULL;
    int pair[2]={-1,-1};
    if (!add_root(b,root,READ|WRITE,0,1) || !realpath(root,b->job) ||
        !add_root(b,apk,READ,1,1) || !add_root(b,libraries,READ,0,0)) goto failed;
    /* Immutable OS runtime resources. None is an app/user document directory. */
    const char *system[]={"/system","/apex","/vendor","/product","/odm"};
    for (size_t i=0;i<sizeof(system)/sizeof(system[0]);++i) if (!add_root(b,system[i],READ,0,0)) goto failed;
    /* Android's /etc alias points into immutable /system, not a host's /etc. */
    char etc[PATH_MAX];
    if(realpath("/etc",etc) && beneath(etc,"/system") && !add_root(b,"/etc",READ,0,0))goto failed;
    if(!add_root(b,"/proc/cpuinfo",READ,1,0) || !add_root(b,"/proc/meminfo",READ,1,0) ||
        !add_root(b,"/sys/devices/system/cpu",READ,0,0))goto failed;
    if (!add_root(b,"/dev/null",READ|WRITE,1,1) || !add_root(b,"/dev/urandom",READ,1,1) ||
        !add_root(b,"/dev/random",READ,1,1) || socketpair(AF_UNIX,SOCK_SEQPACKET|SOCK_CLOEXEC,0,pair)) goto failed;
    b->socket=pair[0];
    if (pthread_create(&b->thread,NULL,broker_loop,b)) goto failed;
    *client_fd=pair[1]; return b;
failed:
    if (pair[0]>=0) close(pair[0]);
    if (pair[1]>=0) close(pair[1]);
    for (int i=0;i<b->count;++i) close(b->roots[i].fd);
    free(b); return NULL;
}
void capt_broker_stop(capt_broker *b) {
    if (!b) return;
    shutdown(b->socket,SHUT_RDWR); pthread_join(b->thread,NULL); close(b->socket);
    for (int i=0;i<b->count;++i) close(b->roots[i].fd);
    free(b);
}
void capt_broker_set_worker(capt_broker *b,int pid) { if(b && pid>0)atomic_store(&b->worker_pid,pid); }
int capt_broker_denied(capt_broker *b) { return b?atomic_load(&b->denied):1; }

/* Signal-side code only uses fixed stack storage and kernel calls. No JNI,
 * malloc, logging, exception strings or file names leave the private socket. */
static int broker_socket=-1;
static char current_directory[PATH_MAX];
static atomic_flag transport_lock=ATOMIC_FLAG_INIT;
static int copy_path(char *to,const char *from) {
    if (!from) return 0;
    for (size_t i=0;i<PATH_MAX;++i) { to[i]=from[i]; if (!from[i]) return 1; }
    return 0;
}
static long exchange(request *q,response *r,int dir1,int dir2) {
    int saved=errno;
    while (atomic_flag_test_and_set_explicit(&transport_lock,memory_order_acquire)) syscall(SYS_sched_yield);
    int fds[2],count=0;
    for (int i=0;i<(q->op==RENAME?2:1);++i) {
        int fd=i?dir2:dir1;
        if (q->path[i][0]=='/') q->at[i]=0;
        else if (fd==AT_FDCWD) {
            char joined[PATH_MAX];
            size_t a=strlen(current_directory),c=strlen(q->path[i]);
            if (a+c+2>=sizeof(joined)) { atomic_flag_clear_explicit(&transport_lock,memory_order_release); return -ENAMETOOLONG; }
            memcpy(joined,current_directory,a); joined[a]='/'; memcpy(joined+a+1,q->path[i],c+1); memcpy(q->path[i],joined,a+c+2); q->at[i]=0;
        } else { fds[count]=fd; q->at[i]=++count; }
    }
    struct iovec vector={q,sizeof(*q)};
    union { struct cmsghdr align; char bytes[CMSG_SPACE(2*sizeof(int))]; } control;
    struct msghdr msg={0}; msg.msg_iov=&vector; msg.msg_iovlen=1;
    if (count) {
        memset(&control,0,sizeof(control)); msg.msg_control=control.bytes; msg.msg_controllen=CMSG_SPACE((size_t)count*sizeof(int));
        struct cmsghdr *c=CMSG_FIRSTHDR(&msg); c->cmsg_level=SOL_SOCKET; c->cmsg_type=SCM_RIGHTS; c->cmsg_len=CMSG_LEN((size_t)count*sizeof(int)); memcpy(CMSG_DATA(c),fds,(size_t)count*sizeof(int));
    }
    long n;
    do { n=syscall(SYS_sendmsg,broker_socket,&msg,MSG_NOSIGNAL); } while(n<0 && errno==EINTR);
    long result=-EACCES;
    if (n==(long)sizeof(*q)) {
        memset(&msg,0,sizeof(msg)); memset(&control,0,sizeof(control)); vector.iov_base=r; vector.iov_len=sizeof(*r); msg.msg_iov=&vector; msg.msg_iovlen=1; msg.msg_control=control.bytes; msg.msg_controllen=sizeof(control);
        do { n=syscall(SYS_recvmsg,broker_socket,&msg,MSG_CMSG_CLOEXEC); } while(n<0 && errno==EINTR);
        if (n==(long)sizeof(*r) && !(msg.msg_flags&(MSG_TRUNC|MSG_CTRUNC))) {
            result=r->error?-(long)r->error:0;
            if (q->op==OPEN && !r->error) {
                struct cmsghdr *c=CMSG_FIRSTHDR(&msg);
                if (c && c->cmsg_level==SOL_SOCKET && c->cmsg_type==SCM_RIGHTS && c->cmsg_len==CMSG_LEN(sizeof(int))) { int received;memcpy(&received,CMSG_DATA(c),sizeof(int));result=received; }
                else result=-EACCES;
            }
            if (q->op==CHDIR && !r->error && r->length>0 && r->length<=PATH_MAX) memcpy(current_directory,r->data,(size_t)r->length);
        }
    }
    atomic_flag_clear_explicit(&transport_lock,memory_order_release); errno=saved; return result;
}
static long mediate(int nr,const uintptr_t *a) {
    request q; response r; memset(&q,0,sizeof(q)); q.version=WIRE_VERSION;
    const char *p=NULL,*p2=NULL; void *buffer=NULL; size_t length=0;
    int dir=AT_FDCWD,dir2=AT_FDCWD;
#define CALL(name) case __NR_##name
    switch(nr) {
#ifdef __NR_open
        CALL(open): q.op=OPEN;p=(const char *)a[0];q.flags=(int)a[1];break;
#endif
        CALL(openat): q.op=OPEN;dir=(int)a[0];p=(const char *)a[1];q.flags=(int)a[2];break;
#ifdef __NR_creat
        CALL(creat): q.op=OPEN;p=(const char *)a[0];q.flags=O_WRONLY|O_CREAT|O_TRUNC;break;
#endif
#ifdef __NR_stat
        CALL(stat):
#endif
#ifdef __NR_lstat
        CALL(lstat):
#endif
#ifdef __NR_stat64
        CALL(stat64):
#endif
#ifdef __NR_lstat64
        CALL(lstat64):
#endif
            q.op=STAT;p=(const char *)a[0];buffer=(void *)a[1];length=sizeof(struct stat);break;
#ifdef __NR_newfstatat
        CALL(newfstatat):
#endif
#ifdef __NR_fstatat64
        CALL(fstatat64):
#endif
            q.op=STAT;dir=(int)a[0];p=(const char *)a[1];buffer=(void *)a[2];length=sizeof(struct stat);break;
#ifdef __NR_access
        CALL(access): q.op=ACCESS;p=(const char *)a[0];q.mode=(int)a[1];break;
#endif
        CALL(faccessat): q.op=ACCESS;dir=(int)a[0];p=(const char *)a[1];q.mode=(int)a[2];break;
#ifdef __NR_readlink
        CALL(readlink): q.op=READLINK;p=(const char *)a[0];buffer=(void *)a[1];length=a[2];break;
#endif
        CALL(readlinkat): q.op=READLINK;dir=(int)a[0];p=(const char *)a[1];buffer=(void *)a[2];length=a[3];break;
#ifdef __NR_mkdir
        CALL(mkdir): q.op=MKDIR;p=(const char *)a[0];break;
#endif
        CALL(mkdirat): q.op=MKDIR;dir=(int)a[0];p=(const char *)a[1];break;
#ifdef __NR_unlink
        CALL(unlink): q.op=UNLINK;p=(const char *)a[0];break;
#endif
#ifdef __NR_rmdir
        CALL(rmdir): q.op=UNLINK;p=(const char *)a[0];q.flags=AT_REMOVEDIR;break;
#endif
        CALL(unlinkat): q.op=UNLINK;dir=(int)a[0];p=(const char *)a[1];q.flags=(int)a[2];break;
#ifdef __NR_rename
        CALL(rename): q.op=RENAME;p=(const char *)a[0];p2=(const char *)a[1];break;
#endif
        CALL(renameat): q.op=RENAME;dir=(int)a[0];p=(const char *)a[1];dir2=(int)a[2];p2=(const char *)a[3];break;
#ifdef __NR_chmod
        CALL(chmod): q.op=CHMOD;p=(const char *)a[0];q.mode=(int)a[1];break;
#endif
        CALL(fchmodat): q.op=CHMOD;dir=(int)a[0];p=(const char *)a[1];q.mode=(int)a[2];break;
        CALL(statfs): q.op=STATFS;p=(const char *)a[0];buffer=(void *)a[1];length=sizeof(struct statfs);break;
#ifdef __NR_statfs64
        CALL(statfs64): q.op=STATFS;p=(const char *)a[0];buffer=(void *)a[2];length=sizeof(struct statfs);break;
#endif
        CALL(chdir): q.op=CHDIR;p=(const char *)a[0];break;
        CALL(getcwd): {
            while (atomic_flag_test_and_set_explicit(&transport_lock,memory_order_acquire)) syscall(SYS_sched_yield);
            size_t n=strlen(current_directory)+1;
            long result=a[1]<n?-ERANGE:(long)n;
            if (result>0) memcpy((void *)a[0],current_directory,n);
            atomic_flag_clear_explicit(&transport_lock,memory_order_release);return result;
        }
        default:return -EACCES;
    }
#undef CALL
    if (!copy_path(q.path[0],p) || (p2 && !copy_path(q.path[1],p2))) return -ENAMETOOLONG;
    long result=exchange(&q,&r,dir,dir2);
    if (result>=0 && buffer) {
        if (r.length<0 || (size_t)r.length>length || (size_t)r.length>sizeof(r.data)) return -EACCES;
        memcpy(buffer,r.data,(size_t)r.length);
        if (q.op==READLINK) result=r.length;
    }
    return result;
}
static void trap(int signal,siginfo_t *info,void *pointer) {
    (void)signal;
    if (!info || info->si_code!=SYS_SECCOMP) _exit(124);
    ucontext_t *ctx=pointer; uintptr_t a[6];
#if defined(__x86_64__)
    a[0]=ctx->uc_mcontext.gregs[REG_RDI];a[1]=ctx->uc_mcontext.gregs[REG_RSI];a[2]=ctx->uc_mcontext.gregs[REG_RDX];a[3]=ctx->uc_mcontext.gregs[REG_R10];a[4]=ctx->uc_mcontext.gregs[REG_R8];a[5]=ctx->uc_mcontext.gregs[REG_R9];
    ctx->uc_mcontext.gregs[REG_RAX]=mediate(info->si_syscall,a);
#elif defined(__aarch64__)
    for(int i=0;i<6;++i)a[i]=ctx->uc_mcontext.regs[i];
    ctx->uc_mcontext.regs[0]=(uint64_t)mediate(info->si_syscall,a);
#elif defined(__arm__)
    a[0]=ctx->uc_mcontext.arm_r0;a[1]=ctx->uc_mcontext.arm_r1;a[2]=ctx->uc_mcontext.arm_r2;a[3]=ctx->uc_mcontext.arm_r3;a[4]=ctx->uc_mcontext.arm_r4;a[5]=ctx->uc_mcontext.arm_r5;
    ctx->uc_mcontext.arm_r0=(unsigned long)mediate(info->si_syscall,a);
#else
#error Unsupported Office sandbox ABI
#endif
}

static struct sock_filter filter[4096];
static size_t filter_size;
static int filter_overflow;
static void statement(unsigned short code,uint32_t k) { if(filter_size>=4096){filter_overflow=1;return;}filter[filter_size++]=(struct sock_filter)BPF_STMT(code,k); }
static void jump(unsigned short code,uint32_t k,unsigned char yes,unsigned char no) { if(filter_size>=4096){filter_overflow=1;return;}filter[filter_size++]=(struct sock_filter)BPF_JUMP(code,k,yes,no); }
static void rule(int nr,uint32_t action) { jump(BPF_JMP|BPF_JEQ|BPF_K,(uint32_t)nr,0,1);statement(BPF_RET|BPF_K,action); }
static void only_socket(int nr,int fd) {
    jump(BPF_JMP|BPF_JEQ|BPF_K,(uint32_t)nr,0,4);
    statement(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[0]));
    jump(BPF_JMP|BPF_JEQ|BPF_K,(uint32_t)fd,0,1);
    statement(BPF_RET|BPF_K,SECCOMP_RET_ALLOW);
    statement(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EACCES);
}
int capt_sandbox_install(int socket_fd,const char *root) {
    if (broker_socket>=0 || socket_fd<3 || !root || !normalize(root,current_directory)) return 0;
    /* Fontconfig/ICU/LO must not fall back to the Android passwd home (often
     * /) or the application's shared cache. All mutable defaults belong here. */
    const char *variables[]={"HOME","TMPDIR","XDG_CONFIG_HOME","XDG_CACHE_HOME","XDG_DATA_HOME"};
    const char *parts[]={"home","tmp","config","cache","data"};
    for(size_t i=0;i<sizeof(variables)/sizeof(variables[0]);++i) {
        char path[PATH_MAX];int n=snprintf(path,sizeof(path),"%s/%s",root,parts[i]);
        if(n<0 || n>=(int)sizeof(path) || (mkdir(path,0700) && errno!=EEXIST) || setenv(variables[i],path,1))return 0;
    }
    /* Hide native/Java stdio before any document is interpreted. */
    int null_fd=open("/dev/null",O_WRONLY|O_CLOEXEC);
    if(null_fd<0 || dup2(null_fd,STDOUT_FILENO)<0 || dup2(null_fd,STDERR_FILENO)<0) { if(null_fd>=0)close(null_fd);return 0; }
    close(null_fd);
    struct sigaction action;memset(&action,0,sizeof(action));action.sa_sigaction=trap;action.sa_flags=SA_SIGINFO;
    sigfillset(&action.sa_mask);
    if(sigaction(SIGSYS,&action,NULL))return 0;
    broker_socket=socket_fd;filter_size=0;filter_overflow=0;
    statement(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,arch));
#if defined(__x86_64__)
    jump(BPF_JMP|BPF_JEQ|BPF_K,AUDIT_ARCH_X86_64,1,0);
#elif defined(__aarch64__)
    jump(BPF_JMP|BPF_JEQ|BPF_K,AUDIT_ARCH_AARCH64,1,0);
#elif defined(__arm__)
    jump(BPF_JMP|BPF_JEQ|BPF_K,AUDIT_ARCH_ARM,1,0);
#endif
    statement(BPF_RET|BPF_K,SECCOMP_RET_KILL);
    statement(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr));
#if defined(__x86_64__)
    jump(BPF_JMP|BPF_JGE|BPF_K,0x40000000,0,1);statement(BPF_RET|BPF_K,SECCOMP_RET_KILL);
#endif
    only_socket(__NR_sendmsg,socket_fd);only_socket(__NR_recvmsg,socket_fd);
#define DENY(name) rule(__NR_##name,SECCOMP_RET_ERRNO|EACCES)
#define MEDIATE(name) rule(__NR_##name,SECCOMP_RET_TRAP)
    /* Network/logd channels are not inherited capabilities of the converter. */
    DENY(socket);DENY(socketpair);DENY(connect);DENY(bind);DENY(listen);DENY(accept4);DENY(sendto);
#ifdef __NR_accept
    DENY(accept);
#endif
#ifdef __NR_sendmmsg
    DENY(sendmmsg);
#endif
    DENY(execve);DENY(execveat);DENY(ptrace);DENY(process_vm_readv);DENY(process_vm_writev);
    DENY(linkat);DENY(symlinkat);DENY(mknodat);DENY(mount);DENY(umount2);DENY(chroot);DENY(fchdir);
    DENY(unshare);DENY(setns);DENY(inotify_add_watch);
#ifdef __NR_close_range
    DENY(close_range);
#endif
#ifdef __NR_open_tree
    DENY(open_tree);DENY(move_mount);DENY(fsopen);DENY(fsconfig);DENY(fsmount);
#endif
#ifdef __NR_mount_setattr
    DENY(mount_setattr);
#endif
#ifdef __NR_fchmodat2
    DENY(fchmodat2);
#endif
#ifdef __NR_fork
    DENY(fork);
#endif
#ifdef __NR_vfork
    DENY(vfork);
#endif
#ifdef __NR_clone3
    DENY(clone3);
#endif
#ifdef __NR_openat2
    DENY(openat2);
#endif
#ifdef __NR_open_by_handle_at
    DENY(open_by_handle_at);
#endif
#ifdef __NR_name_to_handle_at
    DENY(name_to_handle_at);
#endif
#ifdef __NR_pidfd_getfd
    DENY(pidfd_getfd);
#endif
#ifdef __NR_io_uring_setup
    DENY(io_uring_setup);DENY(io_uring_enter);DENY(io_uring_register);
#endif
#ifdef __NR_link
    DENY(link);DENY(symlink);DENY(mknod);
#endif
#ifdef __NR_renameat2
    DENY(renameat2);
#endif
#ifdef __NR_utime
    DENY(utime);
#endif
#ifdef __NR_utimes
    DENY(utimes);
#endif
    DENY(utimensat);
#ifdef __NR_truncate
    DENY(truncate);
#endif
#ifdef __NR_truncate64
    DENY(truncate64);
#endif
#ifdef __NR_chown
    DENY(chown);DENY(lchown);
#endif
#ifdef __NR_chown32
    DENY(chown32);DENY(lchown32);
#endif
    DENY(fchownat);DENY(setxattr);DENY(lsetxattr);DENY(getxattr);DENY(lgetxattr);
    DENY(listxattr);DENY(llistxattr);DENY(removexattr);DENY(lremovexattr);
#ifdef __NR_faccessat2
    DENY(faccessat2);
#endif
#ifdef __NR_statx
    DENY(statx);
#endif
#ifdef __NR_pidfd_open
    DENY(pidfd_open);
#endif
#ifdef __NR_pidfd_send_signal
    DENY(pidfd_send_signal);
#endif
    /* Do not let a same-UID converter signal or change limits of its parent. */
    const int self_calls[]={__NR_kill,__NR_tgkill,__NR_prlimit64};
    for(size_t i=0;i<sizeof(self_calls)/sizeof(self_calls[0]);++i) {
        jump(BPF_JMP|BPF_JEQ|BPF_K,(uint32_t)self_calls[i],0,5);
        statement(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[0]));
        jump(BPF_JMP|BPF_JEQ|BPF_K,(uint32_t)getpid(),2,0);
        jump(BPF_JMP|BPF_JEQ|BPF_K,0,self_calls[i]==__NR_prlimit64?1:0,0);
        statement(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EACCES);
        statement(BPF_RET|BPF_K,SECCOMP_RET_ALLOW);
    }
    DENY(tkill);
#ifdef __NR_futimesat
    DENY(futimesat);
#endif
#ifdef __NR_utimensat_time64
    DENY(utimensat_time64);
#endif
    /* Permit pthreads, not a new unsandboxed process (children inherit filter anyway). */
    jump(BPF_JMP|BPF_JEQ|BPF_K,__NR_clone,0,5);
    statement(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[0]));
    statement(BPF_ALU|BPF_AND|BPF_K,CLONE_THREAD|CLONE_VM|CLONE_SIGHAND);
    jump(BPF_JMP|BPF_JEQ|BPF_K,CLONE_THREAD|CLONE_VM|CLONE_SIGHAND,1,0);
    statement(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EACCES);
    statement(BPF_RET|BPF_K,SECCOMP_RET_ALLOW);
    /* All existing logd sockets stay reserved, but cannot be written or duplicated.
     * Do not close them: an fd-number filter must not later alias a normal PDF. */
    DIR *descriptors=opendir("/proc/self/fd");
    if(!descriptors)return 0;
    struct dirent *entry;
    while((entry=readdir(descriptors))) {
        char *end=NULL;long parsed=strtol(entry->d_name,&end,10);
        if(!end || *end || parsed<3 || parsed>INT_MAX)continue;
        int fd=(int)parsed;
        if(fd==socket_fd)continue;
        struct stat st;if(fstat(fd,&st)||!S_ISSOCK(st.st_mode))continue;
        const int calls[]={__NR_write,__NR_writev,__NR_close,__NR_dup,__NR_dup3,__NR_fcntl,
#ifdef __NR_dup2
            __NR_dup2,
#endif
#ifdef __NR_fcntl64
            __NR_fcntl64,
#endif
#ifdef __NR_sendfile
            __NR_sendfile,
#endif
#ifdef __NR_sendfile64
            __NR_sendfile64,
#endif
            __NR_vmsplice};
        for(size_t i=0;i<sizeof(calls)/sizeof(calls[0]);++i) {
            jump(BPF_JMP|BPF_JEQ|BPF_K,(uint32_t)calls[i],0,4);
            statement(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[0]));
            jump(BPF_JMP|BPF_JEQ|BPF_K,(uint32_t)fd,0,1);
            statement(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EACCES);
            statement(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr));
        }
        const int targets[][2]={{__NR_dup3,1},
#ifdef __NR_dup2
            {__NR_dup2,1},
#endif
            {__NR_splice,2},{__NR_tee,1}};
        for(size_t i=0;i<sizeof(targets)/sizeof(targets[0]);++i) {
            jump(BPF_JMP|BPF_JEQ|BPF_K,(uint32_t)targets[i][0],0,4);
            statement(BPF_LD|BPF_W|BPF_ABS,(uint32_t)(offsetof(struct seccomp_data,args[0])+8*targets[i][1]));
            jump(BPF_JMP|BPF_JEQ|BPF_K,(uint32_t)fd,0,1);
            statement(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EACCES);
            statement(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr));
        }
    }
    closedir(descriptors);
#ifdef __NR_open
    MEDIATE(open);MEDIATE(creat);
#endif
    MEDIATE(openat);
#ifdef __NR_stat
    MEDIATE(stat);MEDIATE(lstat);
#endif
#ifdef __NR_stat64
    MEDIATE(stat64);MEDIATE(lstat64);
#endif
#ifdef __NR_newfstatat
    MEDIATE(newfstatat);
#endif
#ifdef __NR_fstatat64
    MEDIATE(fstatat64);
#endif
#ifdef __NR_access
    MEDIATE(access);
#endif
    MEDIATE(faccessat);
#ifdef __NR_readlink
    MEDIATE(readlink);
#endif
    MEDIATE(readlinkat);
#ifdef __NR_mkdir
    MEDIATE(mkdir);MEDIATE(unlink);MEDIATE(rmdir);MEDIATE(rename);MEDIATE(chmod);
#endif
    MEDIATE(mkdirat);MEDIATE(unlinkat);MEDIATE(renameat);MEDIATE(fchmodat);MEDIATE(statfs);
#ifdef __NR_statfs64
    MEDIATE(statfs64);
#endif
    MEDIATE(chdir);MEDIATE(getcwd);
#undef DENY
#undef MEDIATE
    statement(BPF_RET|BPF_K,SECCOMP_RET_ALLOW);
    if(filter_overflow)return 0;
    struct sock_fprog program={(unsigned short)filter_size,filter};
    /* Synchronize every Java/Binder/native thread; refuse an unconfined fallback. */
    if(prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0) || syscall(__NR_seccomp,SECCOMP_SET_MODE_FILTER,SECCOMP_FILTER_FLAG_TSYNC,&program))return 0;
    return 1;
}
