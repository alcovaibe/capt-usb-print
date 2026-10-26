/* SPDX-License-Identifier: GPL-3.0-or-later
 * Independent Office worker resource guard. No CAPT/HISCOA code.
 */
#ifndef CAPT_OFFICE_LIMITS_CORE_H
#define CAPT_OFFICE_LIMITS_CORE_H
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdint.h>
#include <stdlib.h>
#include <sys/resource.h>
#include <sys/mman.h>
#include <unistd.h>

static volatile sig_atomic_t capt_limit_event_fd = -1;
static void capt_limit_signal(int value) {
    /* Async-signal-safe, fixed data only; no Android logs, allocation or Java calls. */
    const char *marker = value == SIGXCPU ? "CPU\n" : "FILE\n";
    size_t length = value == SIGXCPU ? 4 : 5;
    if (capt_limit_event_fd >= 0) {
        ssize_t ignored = write((int)capt_limit_event_fd, marker, length);
        (void)ignored;
    }
    _exit(value == SIGXCPU ? 122 : 123);
}
static inline uint64_t capt_vm_bytes(void) {
    char buffer[64];
    int fd = open("/proc/self/statm", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;
    ssize_t n = read(fd, buffer, sizeof(buffer)-1);
    close(fd);
    if (n <= 0) return 0;
    buffer[n] = '\0'; errno = 0;
    char *end = NULL;
    unsigned long long pages = strtoull(buffer, &end, 10);
    long unit = sysconf(_SC_PAGESIZE);
    if (errno || end == buffer || *end != ' ' || unit <= 0 || pages > UINT64_MAX/(uint64_t)unit) return 0;
    return (uint64_t)pages * (uint64_t)unit;
}
/* Bounded stress probe: commit real anonymous pages until RLIMIT_AS returns ENOMEM.
 * At most 64 MiB is touched, then every mapping is released. Not global RAM OOM. */
static inline int64_t capt_probe_committed_memory(void) {
    void *blocks[32];
    size_t count=0, bytes=2U<<20;
    int denied=0;
    for (;count<32;++count) {
        void *block=mmap(NULL,bytes,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
        if(block==MAP_FAILED) { denied=errno==ENOMEM;break; }
        blocks[count]=block;
        long page=sysconf(_SC_PAGESIZE);
        if(page<=0) { munmap(block,bytes);break; }
        for(size_t offset=0;offset<bytes;offset+=(size_t)page)
            ((volatile unsigned char *)block)[offset]=(unsigned char)(offset/((size_t)page)+count);
    }
    int64_t committed=(int64_t)count*(int64_t)bytes;
    for(size_t i=0;i<count;++i)munmap(blocks[i],bytes);
    return denied && committed>0?committed:-1;
}
static inline int capt_lower_limit(int resource, uint64_t soft, uint64_t hard) {
    struct rlimit previous, desired, actual;
    if (getrlimit(resource, &previous) || soft > hard || !soft) return 0;
    if (soft >= (uint64_t)RLIM_INFINITY) soft = (uint64_t)RLIM_INFINITY-1;
    if (hard >= (uint64_t)RLIM_INFINITY) hard = (uint64_t)RLIM_INFINITY-1;
    desired.rlim_max = previous.rlim_max < (rlim_t)hard ? previous.rlim_max : (rlim_t)hard;
    desired.rlim_cur = desired.rlim_max < (rlim_t)soft ? desired.rlim_max : (rlim_t)soft;
    if (setrlimit(resource, &desired) || getrlimit(resource, &actual)) return 0;
    return actual.rlim_cur == desired.rlim_cur && actual.rlim_max == desired.rlim_max;
}
static inline int capt_apply_limits(int event_fd, uint64_t file_bytes, uint64_t cpu_seconds,
                                    uint64_t address_growth) {
    if (event_fd < 0 || file_bytes < 1024 || file_bytes > (64ULL<<20) ||
        cpu_seconds < 1 || cpu_seconds > 120 || address_growth < (8ULL<<20) || address_growth > (2ULL<<30)) return 0;
    uint64_t before = capt_vm_bytes();
    if (!before || before > UINT64_MAX-address_growth) return 0;
    struct sigaction action = {0};
    action.sa_handler = capt_limit_signal;
    action.sa_flags = 0;
    if (sigemptyset(&action.sa_mask) || sigaction(SIGXCPU, &action, NULL) || sigaction(SIGXFSZ, &action, NULL)) return 0;
    sigset_t unblocked;
    if (sigemptyset(&unblocked) || sigaddset(&unblocked, SIGXCPU) || sigaddset(&unblocked, SIGXFSZ)
        || sigprocmask(SIG_UNBLOCK, &unblocked, NULL)) return 0;
    capt_limit_event_fd = event_fd;
    if (!capt_lower_limit(RLIMIT_FSIZE, file_bytes, file_bytes) ||
        !capt_lower_limit(RLIMIT_CPU, cpu_seconds, cpu_seconds+1) ||
        !capt_lower_limit(RLIMIT_AS, before+address_growth, before+address_growth)) return 0;
    return 1;
}
#endif
