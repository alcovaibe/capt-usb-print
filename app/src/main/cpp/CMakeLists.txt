cmake_minimum_required(VERSION 3.22.1)
project(capt_hiscoa C)
add_library(capt_hiscoa SHARED hiscoa_jni.c
    upstream/hiscoa-compress.c upstream/hiscoa-common.c)
target_include_directories(capt_hiscoa PRIVATE upstream)
target_compile_features(capt_hiscoa PRIVATE c_std_11)
target_compile_options(capt_hiscoa PRIVATE -Wall -Wextra -Werror -fstack-protector-strong)
# Support Android devices that use 16KiB memory pages.
target_link_options(capt_hiscoa PRIVATE -Wl,-z,max-page-size=16384)

# Independent worker-only guard; neither links to nor changes the HISCOA codec.
add_library(capt_office_limits SHARED office_limits_jni.c)
target_compile_features(capt_office_limits PRIVATE c_std_11)
target_compile_options(capt_office_limits PRIVATE -Wall -Wextra -Werror -fstack-protector-strong)
target_link_options(capt_office_limits PRIVATE -Wl,-z,max-page-size=16384)

add_library(capt_office_sandbox SHARED office_sandbox.c office_sandbox_jni.c)
target_compile_features(capt_office_sandbox PRIVATE c_std_11)
target_compile_options(capt_office_sandbox PRIVATE -Wall -Wextra -Werror -fstack-protector-strong)
target_link_options(capt_office_sandbox PRIVATE -Wl,-z,max-page-size=16384)
