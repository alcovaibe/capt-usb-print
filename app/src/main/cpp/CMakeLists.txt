cmake_minimum_required(VERSION 3.22.1)
project(capt_hiscoa C)
add_library(capt_hiscoa SHARED hiscoa_jni.c
    upstream/hiscoa-compress.c upstream/hiscoa-common.c)
target_include_directories(capt_hiscoa PRIVATE upstream)
target_compile_features(capt_hiscoa PRIVATE c_std_11)
target_compile_options(capt_hiscoa PRIVATE -Wall -Wextra -Werror -fstack-protector-strong)
# Support Android devices that use 16KiB memory pages.
target_link_options(capt_hiscoa PRIVATE -Wl,-z,max-page-size=16384)
