// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

#include "bridge_internal.h"

#include <stdint.h>

uint32_t bpf2socks_hash_bytes(const void *data, size_t len) {
    const uint8_t *ptr = data;
    uint32_t hash = 2166136261U;
    for (size_t i = 0; i < len; ++i) {
        hash ^= ptr[i];
        hash *= 16777619U;
    }
    return hash;
}

uint32_t bpf2socks_session_hash_bucket(uint32_t hash) {
    return hash % BPF2SOCKS_SESSION_HASH_BUCKETS;
}

uint32_t bpf2socks_udp_effective_idle_timeout(
    uint32_t configured_timeout,
    uint32_t default_timeout) {
    uint32_t timeout = configured_timeout != 0U ? configured_timeout : default_timeout;
    if (timeout == 0U) timeout = 120U;
    return timeout;
}

void bpf2socks_session_lru_detach(struct bpf2socks_udp_client_session *session) {
    if (session == NULL) return;
    if (session->lru_prev != NULL) session->lru_prev->lru_next = session->lru_next;
    if (session->lru_next != NULL) session->lru_next->lru_prev = session->lru_prev;
    session->lru_prev = NULL;
    session->lru_next = NULL;
}
