// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

#ifndef ASTERISKNG_BPF2SOCKS_BRIDGE_INTERNAL_H
#define ASTERISKNG_BPF2SOCKS_BRIDGE_INTERNAL_H

#include "bpf2socks.h"

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <sys/socket.h>
#include <time.h>

#define BPF2SOCKS_RELAY_BUFFER_SIZE 16384U
#define BPF2SOCKS_UDP_BUFFER_SIZE 65535U
#define BPF2SOCKS_SESSION_HASH_BUCKETS 4096U
#define BPF2SOCKS_MAX_SOCKS5_UDP_HEADER 22U

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
    struct bpf2socks_bridge_stats stats;
};

struct bpf2socks_udp_reply_binding {
    bool used;
    struct bpf2socks_sockaddr original_dst;
    uint8_t token_addr[16];
    int reply_fd;
    bool reply_raw;
    time_t last_seen;
    struct bpf2socks_udp_client_session *owner;
    struct bpf2socks_udp_reply_binding *next;
    struct bpf2socks_udp_reply_binding *lru_prev;
    struct bpf2socks_udp_reply_binding *lru_next;
    struct bpf2socks_udp_reply_binding *global_lru_prev;
    struct bpf2socks_udp_reply_binding *global_lru_next;
};

struct bpf2socks_udp_client_session {
    bool used;
    struct sockaddr_storage client_addr;
    socklen_t client_addr_len;
    int tcp_fd;
    int udp_fd;
    struct sockaddr_storage relay_addr;
    socklen_t relay_addr_len;
    time_t last_seen;
    struct bpf2socks_udp_reply_binding *bindings;
    struct bpf2socks_udp_reply_binding *binding_lru_head;
    struct bpf2socks_udp_reply_binding *binding_lru_tail;
    size_t binding_count;
    struct bpf2socks_udp_client_session *next;
    struct bpf2socks_udp_client_session *lru_prev;
    struct bpf2socks_udp_client_session *lru_next;
};

uint32_t bpf2socks_hash_bytes(const void *data, size_t len);
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
