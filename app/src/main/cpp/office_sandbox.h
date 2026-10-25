/* SPDX-License-Identifier: GPL-3.0-or-later */
#ifndef CAPT_OFFICE_SANDBOX_H
#define CAPT_OFFICE_SANDBOX_H
#include <stdint.h>
typedef struct capt_broker capt_broker;
/* The broker lives in the UI process; the filter is installed ONLY in :office. */
capt_broker *capt_broker_start(const char *root, const char *apk, const char *libraries,
                              int *client_fd);
void capt_broker_stop(capt_broker *broker);
void capt_broker_set_worker(capt_broker *broker, int pid);
int capt_broker_denied(capt_broker *broker);
int capt_sandbox_install(int socket_fd, const char *root);
#endif
