// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

#include "bridge_internal.h"

#include <errno.h>
#include <sys/resource.h>

uint32_t bpf2socks_worker_quota(uint32_t total, uint32_t worker_id, uint32_t worker_count) {
    if (worker_count == 0U || worker_id >= worker_count) return 0U;
    return total / worker_count + (worker_id < total % worker_count ? 1U : 0U);
}

void bpf2socks_pending_budget_init(struct bpf2socks_udp_pending_budget *budget, size_t cap_bytes) {
    if (budget == NULL) return;
    budget->cap_bytes = cap_bytes;
    atomic_init(&budget->used_bytes, 0U);
    atomic_init(&budget->peak_bytes, 0U);
}

static void update_pending_budget_peak(struct bpf2socks_udp_pending_budget *budget, size_t used_bytes) {
    size_t peak = atomic_load_explicit(&budget->peak_bytes, memory_order_relaxed);
    while (peak < used_bytes &&
        !atomic_compare_exchange_weak_explicit(
            &budget->peak_bytes,
            &peak,
            used_bytes,
            memory_order_relaxed,
            memory_order_relaxed)) {
    }
}

int bpf2socks_pending_budget_reserve(struct bpf2socks_udp_pending_budget *budget, size_t bytes) {
    if (budget == NULL) {
        errno = EINVAL;
        return -1;
    }
    size_t used = atomic_load_explicit(&budget->used_bytes, memory_order_relaxed);
    for (;;) {
        if (bytes > budget->cap_bytes || used > budget->cap_bytes - bytes) {
            errno = ENOBUFS;
            return -1;
        }
        size_t next = used + bytes;
        if (atomic_compare_exchange_weak_explicit(
                &budget->used_bytes,
                &used,
                next,
                memory_order_acq_rel,
                memory_order_relaxed)) {
            update_pending_budget_peak(budget, next);
            return 0;
        }
    }
}

int bpf2socks_pending_budget_release(struct bpf2socks_udp_pending_budget *budget, size_t bytes) {
    if (budget == NULL) {
        errno = EINVAL;
        return -1;
    }
    size_t used = atomic_load_explicit(&budget->used_bytes, memory_order_relaxed);
    for (;;) {
        if (bytes > used) {
            errno = ERANGE;
            return -1;
        }
        if (atomic_compare_exchange_weak_explicit(
                &budget->used_bytes,
                &used,
                used - bytes,
                memory_order_acq_rel,
                memory_order_relaxed)) {
            return 0;
        }
    }
}

size_t bpf2socks_pending_budget_used(const struct bpf2socks_udp_pending_budget *budget) {
    return budget == NULL ? 0U : atomic_load_explicit(&budget->used_bytes, memory_order_relaxed);
}

size_t bpf2socks_pending_budget_peak(const struct bpf2socks_udp_pending_budget *budget) {
    return budget == NULL ? 0U : atomic_load_explicit(&budget->peak_bytes, memory_order_relaxed);
}

int bpf2socks_raise_nofile_limit(uint32_t requested_limit) {
    struct rlimit limit;
    if (getrlimit(RLIMIT_NOFILE, &limit) != 0) return -1;
    rlim_t target = (rlim_t)requested_limit;
    if (target > limit.rlim_max) target = limit.rlim_max;
    if (limit.rlim_cur >= target) return 0;
    limit.rlim_cur = target;
    return setrlimit(RLIMIT_NOFILE, &limit);
}
