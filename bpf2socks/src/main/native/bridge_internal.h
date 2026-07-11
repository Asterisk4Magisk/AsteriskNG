// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

#ifndef ASTERISKNG_BPF2SOCKS_BRIDGE_INTERNAL_H
#define ASTERISKNG_BPF2SOCKS_BRIDGE_INTERNAL_H

#include "bpf2socks.h"

#include <stdbool.h>
#include <stddef.h>
#include <stdatomic.h>
#include <stdint.h>
#include <sys/socket.h>
#include <time.h>

#define BPF2SOCKS_RELAY_BUFFER_SIZE 16384U
#define BPF2SOCKS_UDP_BUFFER_SIZE 65535U
#define BPF2SOCKS_SESSION_HASH_BUCKETS 4096U
#define BPF2SOCKS_MAX_SOCKS5_UDP_HEADER 22U

struct bpf2socks_udp_pending_budget {
    size_t cap_bytes;
    atomic_size_t used_bytes;
    atomic_size_t peak_bytes;
};

struct bpf2socks_bridge_worker {
    uint32_t id;
    int tcp_listener_fd;
    int tcp_listener6_fd;
    int udp_listener_fd;
    int udp_listener6_fd;
    int epoll_fd;
    struct sockaddr_storage socks_addr;
    socklen_t socks_addr_len;
    const struct bpf2socks_runtime_config *config;
    size_t udp_session_cap;
    size_t udp_binding_cap;
    struct bpf2socks_udp_pending_budget *udp_pending_budget;
    struct bpf2socks_bridge_stats stats;
};

struct bpf2socks_udp_reply_binding {
    bool used;
    struct bpf2socks_sockaddr original_dst;
    uint8_t token_addr[16];
    bool connected_udp_token;
    int reply_fd;
    bool reply_raw;
    uint64_t last_seen_ms;
    struct bpf2socks_udp_client_session *owner;
    struct bpf2socks_udp_reply_binding *next;
    struct bpf2socks_udp_reply_binding *lru_prev;
    struct bpf2socks_udp_reply_binding *lru_next;
    struct bpf2socks_udp_reply_binding *global_lru_prev;
    struct bpf2socks_udp_reply_binding *global_lru_next;
    struct bpf2socks_udp_reply_binding *free_next;
};

struct bpf2socks_udp_pending_packet {
    struct bpf2socks_sockaddr original_dst;
    uint8_t *payload;
    size_t payload_len;
    size_t allocation_bytes;
    struct bpf2socks_udp_pending_packet *next;
};

enum bpf2socks_udp_session_stage {
    BPF2SOCKS_UDP_SESSION_UNUSED,
    BPF2SOCKS_UDP_SESSION_CONNECTING,
    BPF2SOCKS_UDP_SESSION_ASSOC_WRITE,
    BPF2SOCKS_UDP_SESSION_ASSOC_READ_HELLO,
    BPF2SOCKS_UDP_SESSION_ASSOC_READ_RESPONSE,
    BPF2SOCKS_UDP_SESSION_READY,
};

struct bpf2socks_udp_client_session {
    bool used;
    enum bpf2socks_udp_session_stage stage;
    struct sockaddr_storage client_addr;
    socklen_t client_addr_len;
    int tcp_fd;
    int udp_fd;
    struct sockaddr_storage relay_addr;
    socklen_t relay_addr_len;
    uint64_t last_seen_ms;
    uint8_t associate_write_buf[32];
    size_t associate_write_len;
    size_t associate_write_offset;
    uint8_t associate_read_buf[4 + 255 + 2];
    size_t associate_read_len;
    size_t associate_read_expected;
    struct bpf2socks_udp_pending_packet *pending_head;
    struct bpf2socks_udp_pending_packet *pending_tail;
    size_t pending_count;
    struct bpf2socks_udp_reply_binding *bindings;
    struct bpf2socks_udp_reply_binding *binding_lru_head;
    struct bpf2socks_udp_reply_binding *binding_lru_tail;
    size_t binding_count;
    struct bpf2socks_udp_client_session *next;
    struct bpf2socks_udp_client_session *lru_prev;
    struct bpf2socks_udp_client_session *lru_next;
    struct bpf2socks_udp_client_session *free_next;
};

uint32_t bpf2socks_hash_bytes(const void *data, size_t len);
uint32_t bpf2socks_worker_quota(uint32_t total, uint32_t worker_id, uint32_t worker_count);
uint32_t bpf2socks_udp_effective_idle_timeout(
    uint32_t configured_timeout,
    uint32_t default_timeout);
void bpf2socks_pending_budget_init(struct bpf2socks_udp_pending_budget *budget, size_t cap_bytes);
int bpf2socks_pending_budget_reserve(struct bpf2socks_udp_pending_budget *budget, size_t bytes);
int bpf2socks_pending_budget_release(struct bpf2socks_udp_pending_budget *budget, size_t bytes);
size_t bpf2socks_pending_budget_used(const struct bpf2socks_udp_pending_budget *budget);
size_t bpf2socks_pending_budget_peak(const struct bpf2socks_udp_pending_budget *budget);
int bpf2socks_bridge_set_nonblocking(int fd);
uint32_t bpf2socks_bridge_clamp_socket_buffer(uint32_t requested, uint32_t fallback);
void bpf2socks_bridge_tune_socket_buffers(int fd, uint32_t recv_size, uint32_t send_size);
void bpf2socks_bridge_record_socket_buffers(int fd);
void bpf2socks_bridge_tune_tcp_advanced(int fd, bool upstream);
void bpf2socks_bridge_tune_udp_advanced(int fd);
void bpf2socks_bridge_enable_tcp_fastopen_listener(int fd);
void bpf2socks_drain_zerocopy_completions(int fd);
int bpf2socks_bridge_udp_worker_run(struct bpf2socks_bridge_worker *worker);
int bpf2socks_bridge_tcp_worker_run(struct bpf2socks_bridge_worker *worker);

#endif
