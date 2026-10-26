#define _GNU_SOURCE
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include "../app/src/main/cpp/office_limits_core.h"

static void require(int value) { if (!value) { fputs("KERNEL_LIMIT_CHECK_FAILED\n",stderr); exit(1); } }
int main(void) {
    alarm(15);
    struct rlimit before_file, before_cpu, before_as, after;
    require(!getrlimit(RLIMIT_FSIZE,&before_file) && !getrlimit(RLIMIT_CPU,&before_cpu) && !getrlimit(RLIMIT_AS,&before_as));
    for (int mode=0;mode<4;mode++) {
        int channel[2]; require(!pipe(channel));
        char path[]="/tmp/capt-limit-file-XXXXXX";
        int file=mkstemp(path); require(file>=0); unlink(path);
        pid_t child=fork(); require(child>=0);
        if (!child) {
            close(channel[0]);
            if (!capt_apply_limits(channel[1],4096,mode==1?1:5,8ULL<<20)) _exit(90);
            if (mode==0) {
                char data[8192]={0};
                for (;;) { ssize_t n=write(file,data,sizeof(data)); if (n<0) _exit(91); }
            } else if (mode==1) {
                volatile uint64_t work=0; for (;;) work++;
            } else if(mode==2) {
                errno=0;
                void *p=mmap(NULL,64ULL<<20,PROT_NONE,MAP_PRIVATE | MAP_ANONYMOUS,-1,0);
                if (p==MAP_FAILED && errno==ENOMEM) _exit(0);
                if (p!=MAP_FAILED) munmap(p,64ULL<<20);
                _exit(92);
            } else {
                int64_t committed=capt_probe_committed_memory();
                _exit(committed>0 && committed<=(8LL<<20)?0:93);
            }
        }
        close(channel[1]);
        int status=0; require(waitpid(child,&status,0)==child && WIFEXITED(status));
        char event[8]={0}; ssize_t bytes=read(channel[0],event,sizeof(event)); require(bytes>=0);
        if (mode==0) {
            struct stat info; require(!fstat(file,&info) && info.st_size==4096);
            require(WEXITSTATUS(status)==123 && bytes==5 && !memcmp(event,"FILE\n",5));
        } else if (mode==1) require(WEXITSTATUS(status)==122 && bytes==4 && !memcmp(event,"CPU\n",4));
        else require(WEXITSTATUS(status)==0 && bytes==0);
        close(channel[0]); close(file);
    }
    require(!getrlimit(RLIMIT_FSIZE,&after) && after.rlim_cur==before_file.rlim_cur && after.rlim_max==before_file.rlim_max);
    require(!getrlimit(RLIMIT_CPU,&after) && after.rlim_cur==before_cpu.rlim_cur && after.rlim_max==before_cpu.rlim_max);
    require(!getrlimit(RLIMIT_AS,&after) && after.rlim_cur==before_as.rlim_cur && after.rlim_max==before_as.rlim_max);
    /* A real ENOSPC write without filling the host or emulator filesystem. */
    int full=open("/dev/full",O_WRONLY|O_CLOEXEC);require(full>=0);
    errno=0;require(write(full,"x",1)==-1 && errno==ENOSPC);close(full);
    puts("CAPT_KERNEL_LIMITS_HOST_PASSED (file/CPU/address-space denial, bounded committed ENOMEM, real /dev/full ENOSPC; parent unchanged)");
    return 0;
}
