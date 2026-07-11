// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

#define _GNU_SOURCE

#include "bridge_dns.h"
#include "bridge_internal.h"

#include <arpa/inet.h>
#include <errno.h>
#include <linux/ip.h>
#include <linux/ipv6.h>
#include <netdb.h>
#include <netinet/in.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/epoll.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

#ifndef IP_FREEBIND
#define IP_FREEBIND 15
#endif

#ifndef IP_TRANSPARENT
#define IP_TRANSPARENT 19
#endif

#ifndef IPV6_TRANSPARENT
#define IPV6_TRANSPARENT 75
#endif

#ifndef IPV6_FREEBIND
#define IPV6_FREEBIND 78
#endif
#ifndef IPV6_HDRINCL
#define IPV6_HDRINCL 36
#endif

#define BPF2SOCKS_UDP_ZEROCOPY_MIN_BYTES 8192U
#define BPF2SOCKS_DNS_CHANNELS_PER_WORKER 2U
#define BPF2SOCKS_DNS_MAX_TRANSACTIONS_PER_WORKER 8192U
#define BPF2SOCKS_DNS_CHANNEL_REBUILD_DELAY_MS 1000ULL
#define BPF2SOCKS_DNS_MAX_EXPIRE_PER_LOOP 64U

struct bpf2socks_udp_header {
    uint16_t source;
    uint16_t dest;
    uint16_t len;
    uint16_t check;
};

struct udp_client_packet {
    struct sockaddr_storage client_addr;
    socklen_t client_addr_len;
    int token_family;
    uint8_t token_addr[16];
    bool has_original_dst;
    bool original_from_socket;
    bool connected_udp_token;
    struct bpf2socks_sockaddr original_dst;
    uint8_t *payload;
    size_t payload_len;
};

enum udp_fd_kind {
    UDP_FD_LISTENER4,
    UDP_FD_LISTENER6,
    UDP_FD_SESSION_CONTROL,
    UDP_FD_SESSION_RELAY,
    UDP_FD_DNS_CONTROL,
    UDP_FD_DNS_RELAY,
};

struct udp_fd_ref {
    enum udp_fd_kind kind;
    int fd;
    struct bpf2socks_udp_client_session *session;
    uint32_t dns_channel_index;
};

struct udp_session_fd_refs {
    struct udp_fd_ref control;
    struct udp_fd_ref relay;
};

struct dns_channel {
    uint32_t index;
    int tcp_fd;
    int udp_fd;
    bool available;
    uint64_t next_rebuild_ms;
    struct sockaddr_storage relay_addr;
    socklen_t relay_addr_len;
    struct udp_fd_ref control_ref;
    struct udp_fd_ref relay_ref;
};

struct udp_send_batch {
    struct bpf2socks_udp_client_session *session;
    struct bpf2socks_udp_msg messages[64];
    unsigned int count;
};

struct udp_downlink_batch {
    int fd;
    bool use_pktinfo;
    struct bpf2socks_udp_client_session *session;
    struct bpf2socks_udp_reply_binding *bindings[64];
    const struct bpf2socks_udp_msg *messages[64];
    unsigned int count;
};

struct udp_state {
    struct bpf2socks_udp_client_session *sessions;
    struct bpf2socks_udp_client_session *free_sessions;
    struct bpf2socks_udp_reply_binding *bindings;
    struct bpf2socks_udp_reply_binding *free_bindings;
    struct udp_session_fd_refs *session_refs;
    struct epoll_event *events;
    struct bpf2socks_udp_client_session **session_buckets;
    struct bpf2socks_udp_client_session *lru_head;
    struct bpf2socks_udp_client_session *lru_tail;
    struct bpf2socks_udp_reply_binding *binding_lru_head;
    struct bpf2socks_udp_reply_binding *binding_lru_tail;
    struct udp_fd_ref listener_ref;
    struct udp_fd_ref listener6_ref;
    struct dns_channel dns_channels[BPF2SOCKS_DNS_CHANNELS_PER_WORKER];
    struct bpf2socks_dns_table dns_table;
    int dns_raw4_fd;
    int dns_raw6_fd;
    int epoll_fd;
    size_t session_cap;
    size_t session_count;
    size_t binding_cap;
    size_t binding_count;
    struct bpf2socks_udp_pending_budget *pending_budget;
    size_t event_cap;
    uint32_t next_dns_channel;
};

static bool udp_control_fd_is_healthy(int fd) {
    uint8_t byte = 0U;
    ssize_t received = recv(fd, &byte, sizeof(byte), MSG_PEEK | MSG_DONTWAIT);
    if (received == 0) return false;
    if (received > 0) return false;
    return errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR;
}

static bool udp_control_event_is_failure(int fd, uint32_t events) {
    if ((events & (EPOLLERR | EPOLLHUP | EPOLLRDHUP)) != 0U) return true;
    return (events & EPOLLIN) != 0U && !udp_control_fd_is_healthy(fd);
}

static bool same_sockaddr(
    const struct sockaddr_storage *a,
    socklen_t a_len,
    const struct sockaddr_storage *b,
    socklen_t b_len) {
    if (a->ss_family != b->ss_family || a_len != b_len) return false;
    if (a->ss_family == AF_INET) {
        const struct sockaddr_in *ia = (const struct sockaddr_in *)a;
        const struct sockaddr_in *ib = (const struct sockaddr_in *)b;
        return ia->sin_port == ib->sin_port && ia->sin_addr.s_addr == ib->sin_addr.s_addr;
    }
    return memcmp(a, b, a_len) == 0;
}

static bool same_original_dst(const struct bpf2socks_sockaddr *a, const struct bpf2socks_sockaddr *b) {
    if (a->family != b->family || a->port != b->port) return false;
    size_t len = a->family == AF_INET ? 4U : 16U;
    return memcmp(a->addr, b->addr, len) == 0;
}

static uint32_t sockaddr_hash(const struct sockaddr_storage *addr, socklen_t addr_len) {
    if (addr->ss_family == AF_INET) {
        const struct sockaddr_in *in = (const struct sockaddr_in *)addr;
        struct {
            uint16_t family;
            uint16_t port;
            uint32_t address;
        } key;
        key.family = AF_INET;
        key.port = in->sin_port;
        key.address = in->sin_addr.s_addr;
        return bpf2socks_hash_bytes(&key, sizeof(key));
    }
    return bpf2socks_hash_bytes(addr, addr_len);
}

static size_t udp_payload_stride(const struct bpf2socks_runtime_config *config) {
    (void)config;
    return BPF2SOCKS_UDP_BUFFER_SIZE;
}

static uint64_t monotonic_ms(void) {
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) return 0U;
    return ((uint64_t)ts.tv_sec * 1000ULL) + ((uint64_t)ts.tv_nsec / 1000000ULL);
}

static int original_to_sockaddr(const struct bpf2socks_original_dst *src, struct bpf2socks_sockaddr *dst) {
    memset(dst, 0, sizeof(*dst));
    dst->family = src->family;
    dst->port = src->port;
    if (src->family == AF_INET) {
        memcpy(dst->addr, src->addr, 4);
        return 0;
    }
    if (src->family == AF_INET6) {
        memcpy(dst->addr, src->addr, 16);
        return 0;
    }
    return -1;
}

static int lookup_original_with_client_fallback(
    int map_fd,
    const struct bpf2socks_token_key *key,
    struct bpf2socks_original_dst *original) {
    if (bpf2socks_token_lookup(map_fd, key, original) == 0) return 0;
    struct bpf2socks_token_key fallback = *key;
    fallback.client_port = 0;
    memset(fallback.client_addr, 0, sizeof(fallback.client_addr));
    return bpf2socks_token_lookup(map_fd, &fallback, original);
}

static bool packet_needs_original_reply_socket(const struct udp_client_packet *packet) {
    if (packet == NULL) return false;
    if (packet->connected_udp_token) return false;
    // UDP sockets that kept the original peer need replies sourced from that original destination.
    return packet->original_dst.family == AF_INET || packet->original_dst.family == AF_INET6;
}

static bool original_uses_connected_udp_token(const struct udp_client_packet *packet) {
    return packet != NULL && packet->connected_udp_token;
}

static uint32_t checksum_add_bytes(uint32_t sum, const void *data, size_t len) {
    const uint8_t *ptr = data;
    while (len > 1U) {
        sum += ((uint32_t)ptr[0] << 8) | ptr[1];
        ptr += 2;
        len -= 2U;
    }
    if (len == 1U) {
        sum += (uint32_t)ptr[0] << 8;
    }
    return sum;
}

static uint16_t checksum_fold(uint32_t sum) {
    while ((sum >> 16U) != 0U) {
        sum = (sum & 0xffffU) + (sum >> 16U);
    }
    return (uint16_t)~sum;
}

static uint16_t udp_ipv4_checksum(
    uint32_t source_addr,
    uint32_t destination_addr,
    const struct bpf2socks_udp_header *udp,
    const uint8_t *payload,
    size_t payload_len) {
    struct {
        uint32_t source_addr;
        uint32_t destination_addr;
        uint8_t zero;
        uint8_t protocol;
        uint16_t length;
    } pseudo;
    pseudo.source_addr = source_addr;
    pseudo.destination_addr = destination_addr;
    pseudo.zero = 0;
    pseudo.protocol = IPPROTO_UDP;
    pseudo.length = udp->len;

    uint32_t sum = 0;
    sum = checksum_add_bytes(sum, &pseudo, sizeof(pseudo));
    sum = checksum_add_bytes(sum, udp, sizeof(*udp));
    sum = checksum_add_bytes(sum, payload, payload_len);
    uint16_t result = checksum_fold(sum);
    return result == 0U ? 0xffffU : result;
}

static uint16_t udp_ipv6_checksum(
    const uint8_t source_addr[16],
    const uint8_t destination_addr[16],
    const struct bpf2socks_udp_header *udp,
    const uint8_t *payload,
    size_t payload_len) {
    struct {
        uint8_t source_addr[16];
        uint8_t destination_addr[16];
        uint32_t length;
        uint8_t zero[3];
        uint8_t next_header;
    } pseudo;
    memcpy(pseudo.source_addr, source_addr, 16);
    memcpy(pseudo.destination_addr, destination_addr, 16);
    pseudo.length = htonl((uint32_t)(sizeof(*udp) + payload_len));
    memset(pseudo.zero, 0, sizeof(pseudo.zero));
    pseudo.next_header = IPPROTO_UDP;

    uint32_t sum = 0;
    sum = checksum_add_bytes(sum, &pseudo, sizeof(pseudo));
    sum = checksum_add_bytes(sum, udp, sizeof(*udp));
    sum = checksum_add_bytes(sum, payload, payload_len);
    uint16_t result = checksum_fold(sum);
    return result == 0U ? 0xffffU : result;
}

static int send_raw_udp_to_client(
    int fd,
    const struct bpf2socks_udp_client_session *session,
    const struct bpf2socks_udp_reply_binding *binding,
    const uint8_t *payload,
    size_t payload_len) {
    if (session == NULL || binding == NULL ||
        session->client_addr.ss_family != AF_INET ||
        binding->original_dst.family != AF_INET) {
        errno = EAFNOSUPPORT;
        return -1;
    }
    const size_t udp_len = sizeof(struct bpf2socks_udp_header) + payload_len;
    const size_t ip_len = sizeof(struct iphdr) + udp_len;
    if (ip_len > UINT16_MAX || payload_len > BPF2SOCKS_UDP_BUFFER_SIZE) {
        errno = EMSGSIZE;
        return -1;
    }

    uint8_t packet[sizeof(struct iphdr) + sizeof(struct bpf2socks_udp_header) + BPF2SOCKS_UDP_BUFFER_SIZE];
    memset(packet, 0, sizeof(struct iphdr) + sizeof(struct bpf2socks_udp_header));
    struct iphdr *ip = (struct iphdr *)packet;
    struct bpf2socks_udp_header *udp = (struct bpf2socks_udp_header *)(packet + sizeof(*ip));
    uint8_t *body = packet + sizeof(*ip) + sizeof(*udp);
    memcpy(body, payload, payload_len);

    const struct sockaddr_in *client = (const struct sockaddr_in *)&session->client_addr;
    uint32_t source_addr = 0;
    memcpy(&source_addr, binding->original_dst.addr, sizeof(source_addr));

    ip->version = 4;
    ip->ihl = sizeof(*ip) / 4U;
    ip->ttl = 64;
    ip->protocol = IPPROTO_UDP;
    ip->tot_len = htons((uint16_t)ip_len);
    ip->id = htons((uint16_t)time(NULL));
    ip->saddr = source_addr;
    ip->daddr = client->sin_addr.s_addr;
    ip->check = htons(checksum_fold(checksum_add_bytes(0, ip, sizeof(*ip))));

    udp->source = htons(binding->original_dst.port);
    udp->dest = client->sin_port;
    udp->len = htons((uint16_t)udp_len);
    udp->check = 0;
    udp->check = htons(udp_ipv4_checksum(ip->saddr, ip->daddr, udp, body, payload_len));

    ssize_t sent = sendto(fd, packet, ip_len, 0, (const struct sockaddr *)client, sizeof(*client));
    return sent == (ssize_t)ip_len ? 0 : -1;
}

static int send_raw_udp6_to_client(
    int fd,
    const struct bpf2socks_udp_client_session *session,
    const struct bpf2socks_udp_reply_binding *binding,
    const uint8_t *payload,
    size_t payload_len) {
    if (session == NULL || binding == NULL ||
        session->client_addr.ss_family != AF_INET6 ||
        binding->original_dst.family != AF_INET6) {
        errno = EAFNOSUPPORT;
        return -1;
    }
    const size_t udp_len = sizeof(struct bpf2socks_udp_header) + payload_len;
    const size_t ip_len = sizeof(struct ipv6hdr) + udp_len;
    if (udp_len > UINT16_MAX || payload_len > BPF2SOCKS_UDP_BUFFER_SIZE) {
        errno = EMSGSIZE;
        return -1;
    }

    uint8_t packet[sizeof(struct ipv6hdr) + sizeof(struct bpf2socks_udp_header) + BPF2SOCKS_UDP_BUFFER_SIZE];
    memset(packet, 0, sizeof(struct ipv6hdr) + sizeof(struct bpf2socks_udp_header));
    struct ipv6hdr *ip = (struct ipv6hdr *)packet;
    struct bpf2socks_udp_header *udp = (struct bpf2socks_udp_header *)(packet + sizeof(*ip));
    uint8_t *body = packet + sizeof(*ip) + sizeof(*udp);
    memcpy(body, payload, payload_len);

    const struct sockaddr_in6 *client = (const struct sockaddr_in6 *)&session->client_addr;
    ip->version = 6;
    ip->payload_len = htons((uint16_t)udp_len);
    ip->nexthdr = IPPROTO_UDP;
    ip->hop_limit = 64;
    memcpy(&ip->saddr, binding->original_dst.addr, 16);
    memcpy(&ip->daddr, &client->sin6_addr, 16);

    udp->source = htons(binding->original_dst.port);
    udp->dest = client->sin6_port;
    udp->len = htons((uint16_t)udp_len);
    udp->check = 0;
    udp->check = htons(udp_ipv6_checksum(binding->original_dst.addr, client->sin6_addr.s6_addr, udp, body, payload_len));

    ssize_t sent = sendto(fd, packet, ip_len, 0, (const struct sockaddr *)client, sizeof(*client));
    return sent == (ssize_t)ip_len ? 0 : -1;
}

static int create_raw_udp_reply_socket(void) {
    int fd = socket(AF_INET, SOCK_RAW | SOCK_NONBLOCK | SOCK_CLOEXEC, IPPROTO_RAW);
    if (fd < 0) return -1;

    int one = 1;
    (void)setsockopt(fd, IPPROTO_IP, IP_HDRINCL, &one, sizeof(one));
    (void)setsockopt(fd, IPPROTO_IP, IP_TRANSPARENT, &one, sizeof(one));
    return fd;
}

static int create_raw_udp6_reply_socket(void) {
    int fd = socket(AF_INET6, SOCK_RAW | SOCK_NONBLOCK | SOCK_CLOEXEC, IPPROTO_RAW);
    if (fd < 0) return -1;

    int one = 1;
    (void)setsockopt(fd, IPPROTO_IPV6, IPV6_HDRINCL, &one, sizeof(one));
    (void)setsockopt(fd, IPPROTO_IPV6, IPV6_TRANSPARENT, &one, sizeof(one));
    return fd;
}

static int create_udp4_client_reply_socket(const struct bpf2socks_sockaddr *original, bool *raw_socket) {
    if (original == NULL || original->family != AF_INET) {
        errno = EAFNOSUPPORT;
        return -1;
    }
    if (raw_socket != NULL) *raw_socket = false;
    int fd = socket(AF_INET, SOCK_DGRAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;

    int one = 1;
    (void)setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    if (setsockopt(fd, IPPROTO_IP, IP_TRANSPARENT, &one, sizeof(one)) != 0) {
        int saved = errno;
        close(fd);
        errno = saved;
        return -1;
    }
    (void)setsockopt(fd, IPPROTO_IP, IP_FREEBIND, &one, sizeof(one));

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons(original->port);
    memcpy(&addr.sin_addr, original->addr, 4);
    if (bind(fd, (struct sockaddr *)&addr, sizeof(addr)) != 0) {
        int saved = errno;
        close(fd);
        fd = create_raw_udp_reply_socket();
        if (fd >= 0) {
            if (raw_socket != NULL) *raw_socket = true;
            return fd;
        }
        errno = saved;
        return -1;
    }
    return fd;
}

static int create_udp6_client_reply_socket(const struct bpf2socks_sockaddr *original, bool *raw_socket) {
    if (original == NULL || original->family != AF_INET6) {
        errno = EAFNOSUPPORT;
        return -1;
    }
    if (raw_socket != NULL) *raw_socket = false;
    int fd = socket(AF_INET6, SOCK_DGRAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;

    int one = 1;
    (void)setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    if (setsockopt(fd, IPPROTO_IPV6, IPV6_TRANSPARENT, &one, sizeof(one)) != 0) {
        int saved = errno;
        close(fd);
        errno = saved;
        return -1;
    }
    (void)setsockopt(fd, IPPROTO_IPV6, IPV6_FREEBIND, &one, sizeof(one));

    struct sockaddr_in6 addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin6_family = AF_INET6;
    addr.sin6_port = htons(original->port);
    memcpy(&addr.sin6_addr, original->addr, 16);
    if (bind(fd, (struct sockaddr *)&addr, sizeof(addr)) != 0) {
        int saved = errno;
        close(fd);
        fd = create_raw_udp6_reply_socket();
        if (fd >= 0) {
            if (raw_socket != NULL) *raw_socket = true;
            return fd;
        }
        errno = saved;
        return -1;
    }
    return fd;
}

static int create_udp_client_reply_socket(const struct bpf2socks_sockaddr *original, bool *raw_socket) {
    if (original == NULL) {
        errno = EINVAL;
        return -1;
    }
    if (original->family == AF_INET) return create_udp4_client_reply_socket(original, raw_socket);
    if (original->family == AF_INET6) return create_udp6_client_reply_socket(original, raw_socket);
    errno = EAFNOSUPPORT;
    return -1;
}

static void lru_remove(struct udp_state *state, struct bpf2socks_udp_client_session *session) {
    if (session->lru_prev != NULL) session->lru_prev->lru_next = session->lru_next;
    if (session->lru_next != NULL) session->lru_next->lru_prev = session->lru_prev;
    if (state->lru_head == session) state->lru_head = session->lru_next;
    if (state->lru_tail == session) state->lru_tail = session->lru_prev;
    session->lru_prev = NULL;
    session->lru_next = NULL;
}

static void lru_add_tail(struct udp_state *state, struct bpf2socks_udp_client_session *session) {
    session->lru_prev = state->lru_tail;
    session->lru_next = NULL;
    if (state->lru_tail != NULL) state->lru_tail->lru_next = session;
    state->lru_tail = session;
    if (state->lru_head == NULL) state->lru_head = session;
}

static void lru_touch(struct udp_state *state, struct bpf2socks_udp_client_session *session) {
    if (state->lru_tail == session) return;
    lru_remove(state, session);
    lru_add_tail(state, session);
}

static void binding_session_lru_remove(
    struct bpf2socks_udp_client_session *session,
    struct bpf2socks_udp_reply_binding *binding) {
    if (session == NULL || binding == NULL) return;
    if (binding->lru_prev != NULL) binding->lru_prev->lru_next = binding->lru_next;
    if (binding->lru_next != NULL) binding->lru_next->lru_prev = binding->lru_prev;
    if (session->binding_lru_head == binding) session->binding_lru_head = binding->lru_next;
    if (session->binding_lru_tail == binding) session->binding_lru_tail = binding->lru_prev;
    binding->lru_prev = NULL;
    binding->lru_next = NULL;
}

static void binding_session_lru_add_tail(
    struct bpf2socks_udp_client_session *session,
    struct bpf2socks_udp_reply_binding *binding) {
    binding->lru_prev = session->binding_lru_tail;
    binding->lru_next = NULL;
    if (session->binding_lru_tail != NULL) session->binding_lru_tail->lru_next = binding;
    session->binding_lru_tail = binding;
    if (session->binding_lru_head == NULL) session->binding_lru_head = binding;
}

static void binding_global_lru_remove(struct udp_state *state, struct bpf2socks_udp_reply_binding *binding) {
    if (state == NULL || binding == NULL) return;
    if (binding->global_lru_prev != NULL) binding->global_lru_prev->global_lru_next = binding->global_lru_next;
    if (binding->global_lru_next != NULL) binding->global_lru_next->global_lru_prev = binding->global_lru_prev;
    if (state->binding_lru_head == binding) state->binding_lru_head = binding->global_lru_next;
    if (state->binding_lru_tail == binding) state->binding_lru_tail = binding->global_lru_prev;
    binding->global_lru_prev = NULL;
    binding->global_lru_next = NULL;
}

static void binding_global_lru_add_tail(struct udp_state *state, struct bpf2socks_udp_reply_binding *binding) {
    binding->global_lru_prev = state->binding_lru_tail;
    binding->global_lru_next = NULL;
    if (state->binding_lru_tail != NULL) state->binding_lru_tail->global_lru_next = binding;
    state->binding_lru_tail = binding;
    if (state->binding_lru_head == NULL) state->binding_lru_head = binding;
}

static void binding_lru_touch(
    struct udp_state *state,
    struct bpf2socks_udp_client_session *session,
    struct bpf2socks_udp_reply_binding *binding) {
    if (state == NULL || session == NULL || binding == NULL) return;
    if (session->binding_lru_tail != binding) {
        binding_session_lru_remove(session, binding);
        binding_session_lru_add_tail(session, binding);
    }
    if (state->binding_lru_tail != binding) {
        binding_global_lru_remove(state, binding);
        binding_global_lru_add_tail(state, binding);
    }
}

static struct bpf2socks_udp_reply_binding *alloc_binding(struct udp_state *state) {
    if (state == NULL || state->free_bindings == NULL) {
        errno = ENOMEM;
        return NULL;
    }
    struct bpf2socks_udp_reply_binding *binding = state->free_bindings;
    state->free_bindings = binding->free_next;
    memset(binding, 0, sizeof(*binding));
    binding->reply_fd = -1;
    return binding;
}

static void close_binding(struct bpf2socks_udp_reply_binding *binding) {
    if (binding == NULL) return;
    if (binding->reply_fd >= 0) close(binding->reply_fd);
    binding->reply_fd = -1;
}

static void release_binding(struct udp_state *state, struct bpf2socks_udp_reply_binding *binding) {
    if (state == NULL || binding == NULL) return;
    close_binding(binding);
    memset(binding, 0, sizeof(*binding));
    binding->reply_fd = -1;
    binding->free_next = state->free_bindings;
    state->free_bindings = binding;
}

static void unlink_binding_from_session(
    struct bpf2socks_udp_client_session *session,
    struct bpf2socks_udp_reply_binding *binding) {
    struct bpf2socks_udp_reply_binding **slot = &session->bindings;
    while (*slot != NULL) {
        if (*slot == binding) {
            *slot = binding->next;
            binding->next = NULL;
            return;
        }
        slot = &(*slot)->next;
    }
}

static void destroy_binding(
    struct udp_state *state,
    struct bpf2socks_udp_client_session *session,
    struct bpf2socks_udp_reply_binding *binding) {
    if (session == NULL || binding == NULL) return;
    unlink_binding_from_session(session, binding);
    binding_session_lru_remove(session, binding);
    binding_global_lru_remove(state, binding);
    if (session->binding_count > 0U) --session->binding_count;
    if (state != NULL && state->binding_count > 0U) --state->binding_count;
    release_binding(state, binding);
}

static int evict_binding(
    struct udp_state *state,
    struct bpf2socks_udp_client_session *session,
    struct bpf2socks_bridge_worker *worker) {
    struct bpf2socks_udp_reply_binding *binding = session != NULL ? session->binding_lru_head : NULL;
    if (binding == NULL && state != NULL) {
        binding = state->binding_lru_head;
        if (binding != NULL) session = binding->owner;
    }
    if (binding == NULL || session == NULL) {
        errno = ENOMEM;
        return -1;
    }
    destroy_binding(state, session, binding);
    ++worker->stats.udp_binding_evictions;
    return 0;
}

static int ensure_binding_capacity(
    struct udp_state *state,
    struct bpf2socks_bridge_worker *worker) {
    while (state->binding_count >= state->binding_cap) {
        if (evict_binding(state, NULL, worker) < 0) return -1;
    }
    return 0;
}

static size_t session_index(const struct udp_state *state, const struct bpf2socks_udp_client_session *session) {
    return (size_t)(session - state->sessions);
}

static int add_udp_epoll_fd_events(struct udp_state *state, int fd, struct udp_fd_ref *ref, uint32_t events) {
    if (state->epoll_fd < 0 || fd < 0 || ref == NULL) return 0;
    struct epoll_event event;
    memset(&event, 0, sizeof(event));
    event.events = events | EPOLLERR | EPOLLHUP;
    event.data.ptr = ref;
    return epoll_ctl(state->epoll_fd, EPOLL_CTL_ADD, fd, &event);
}

static int mod_udp_epoll_fd_events(struct udp_state *state, int fd, struct udp_fd_ref *ref, uint32_t events) {
    if (state->epoll_fd < 0 || fd < 0 || ref == NULL) return 0;
    struct epoll_event event;
    memset(&event, 0, sizeof(event));
    event.events = events | EPOLLERR | EPOLLHUP;
    event.data.ptr = ref;
    return epoll_ctl(state->epoll_fd, EPOLL_CTL_MOD, fd, &event);
}

static int add_udp_epoll_fd(struct udp_state *state, int fd, struct udp_fd_ref *ref) {
    return add_udp_epoll_fd_events(state, fd, ref, EPOLLIN);
}

static void remove_udp_epoll_fd(struct udp_state *state, int fd) {
    if (state == NULL || state->epoll_fd < 0 || fd < 0) return;
    (void)epoll_ctl(state->epoll_fd, EPOLL_CTL_DEL, fd, NULL);
}

static int add_session_udp_epoll_fd(struct udp_state *state, struct bpf2socks_udp_client_session *session) {
    size_t index = session_index(state, session);
    if (index >= state->session_cap) {
        errno = EINVAL;
        return -1;
    }
    struct udp_fd_ref *ref = &state->session_refs[index].relay;
    ref->kind = UDP_FD_SESSION_RELAY;
    ref->fd = session->udp_fd;
    ref->session = session;
    return add_udp_epoll_fd(state, session->udp_fd, ref);
}

static int add_session_tcp_epoll_fd(struct udp_state *state, struct bpf2socks_udp_client_session *session, uint32_t events) {
    size_t index = session_index(state, session);
    if (index >= state->session_cap) {
        errno = EINVAL;
        return -1;
    }
    struct udp_fd_ref *ref = &state->session_refs[index].control;
    ref->kind = UDP_FD_SESSION_CONTROL;
    ref->fd = session->tcp_fd;
    ref->session = session;
    return add_udp_epoll_fd_events(state, session->tcp_fd, ref, events);
}

static int switch_session_epoll_to_udp(struct udp_state *state, struct bpf2socks_udp_client_session *session) {
    size_t index = session_index(state, session);
    if (index >= state->session_cap) {
        errno = EINVAL;
        return -1;
    }
    struct udp_fd_ref *control_ref = &state->session_refs[index].control;
    if (mod_udp_epoll_fd_events(
            state,
            session->tcp_fd,
            control_ref,
            EPOLLIN | EPOLLRDHUP) < 0) {
        return -1;
    }
    return add_session_udp_epoll_fd(state, session);
}

static struct bpf2socks_udp_pending_packet *alloc_pending_packet(
    struct udp_state *state,
    size_t payload_len) {
    if (state == NULL || state->pending_budget == NULL ||
        payload_len > SIZE_MAX - sizeof(struct bpf2socks_udp_pending_packet)) {
        errno = ENOBUFS;
        return NULL;
    }
    size_t allocation_bytes = sizeof(struct bpf2socks_udp_pending_packet) + payload_len;
    if (bpf2socks_pending_budget_reserve(state->pending_budget, allocation_bytes) < 0) return NULL;
    struct bpf2socks_udp_pending_packet *packet = malloc(allocation_bytes);
    if (packet == NULL) {
        (void)bpf2socks_pending_budget_release(state->pending_budget, allocation_bytes);
        errno = ENOMEM;
        return NULL;
    }
    memset(packet, 0, sizeof(*packet));
    packet->payload = (uint8_t *)(packet + 1);
    packet->allocation_bytes = allocation_bytes;
    return packet;
}

static void release_pending_packet(struct udp_state *state, struct bpf2socks_udp_pending_packet *packet) {
    if (state == NULL || packet == NULL) return;
    size_t allocation_bytes = packet->allocation_bytes;
    free(packet);
    if (state->pending_budget != NULL) {
        (void)bpf2socks_pending_budget_release(state->pending_budget, allocation_bytes);
    }
}

static void release_session_pending_packets(struct udp_state *state, struct bpf2socks_udp_client_session *session) {
    if (state == NULL || session == NULL) return;
    struct bpf2socks_udp_pending_packet *packet = session->pending_head;
    while (packet != NULL) {
        struct bpf2socks_udp_pending_packet *next = packet->next;
        release_pending_packet(state, packet);
        packet = next;
    }
    session->pending_head = NULL;
    session->pending_tail = NULL;
    session->pending_count = 0U;
}

static int queue_pending_udp_packet(
    struct udp_state *state,
    struct bpf2socks_udp_client_session *session,
    const struct bpf2socks_sockaddr *original_dst,
    const uint8_t *payload,
    size_t payload_len,
    size_t payload_cap) {
    if (state == NULL || session == NULL || original_dst == NULL || payload == NULL || payload_len > payload_cap) {
        errno = EINVAL;
        return -1;
    }
    struct bpf2socks_udp_pending_packet *packet = alloc_pending_packet(state, payload_len);
    if (packet == NULL) return -1;
    packet->original_dst = *original_dst;
    packet->payload_len = payload_len;
    memcpy(packet->payload, payload, payload_len);
    packet->next = NULL;
    if (session->pending_tail != NULL) {
        session->pending_tail->next = packet;
    } else {
        session->pending_head = packet;
    }
    session->pending_tail = packet;
    ++session->pending_count;
    session->last_seen_ms = monotonic_ms();
    lru_touch(state, session);
    return 0;
}

static size_t udp_socks5_write_addr(uint8_t *out, const struct bpf2socks_sockaddr *addr) {
    size_t len = 0U;
    if (addr->family == AF_INET) {
        out[len++] = 0x01;
        memcpy(out + len, addr->addr, 4U);
        len += 4U;
    } else if (addr->family == AF_INET6) {
        out[len++] = 0x04;
        memcpy(out + len, addr->addr, 16U);
        len += 16U;
    } else {
        return 0U;
    }
    out[len++] = (uint8_t)(addr->port >> 8);
    out[len++] = (uint8_t)(addr->port & 0xffU);
    return len;
}

static int build_udp_associate_pipeline_request(struct bpf2socks_udp_client_session *session) {
    if (session == NULL) {
        errno = EINVAL;
        return -1;
    }
    struct bpf2socks_sockaddr any_addr;
    memset(&any_addr, 0, sizeof(any_addr));
    any_addr.family = AF_INET;
    any_addr.port = 0U;

    uint8_t *buf = session->associate_write_buf;
    size_t len = 0U;
    buf[len++] = 0x05;
    buf[len++] = 0x01;
    buf[len++] = 0x00;
    buf[len++] = 0x05;
    buf[len++] = 0x03;
    buf[len++] = 0x00;
    size_t addr_len = udp_socks5_write_addr(buf + len, &any_addr);
    if (addr_len == 0U || len + addr_len > sizeof(session->associate_write_buf)) {
        errno = EINVAL;
        return -1;
    }
    len += addr_len;
    session->associate_write_len = len;
    session->associate_write_offset = 0U;
    return 0;
}

static int replace_unspecified_udp_relay_addr(
    const struct sockaddr *socks_addr,
    struct sockaddr_storage *relay_addr,
    socklen_t *relay_addr_len) {
    if (socks_addr == NULL || relay_addr == NULL || relay_addr_len == NULL) {
        errno = EINVAL;
        return -1;
    }
    if (relay_addr->ss_family == AF_INET) {
        struct sockaddr_in *relay4 = (struct sockaddr_in *)relay_addr;
        if (relay4->sin_addr.s_addr != htonl(INADDR_ANY)) return 0;
        if (socks_addr->sa_family != AF_INET) return 0;
        relay4->sin_addr = ((const struct sockaddr_in *)socks_addr)->sin_addr;
        *relay_addr_len = sizeof(*relay4);
    } else if (relay_addr->ss_family == AF_INET6) {
        struct sockaddr_in6 *relay6 = (struct sockaddr_in6 *)relay_addr;
        if (!IN6_IS_ADDR_UNSPECIFIED(&relay6->sin6_addr)) return 0;
        if (socks_addr->sa_family != AF_INET6) return 0;
        relay6->sin6_addr = ((const struct sockaddr_in6 *)socks_addr)->sin6_addr;
        *relay_addr_len = sizeof(*relay6);
    }
    return 0;
}

static int parse_udp_associate_response_addr(
    struct bpf2socks_udp_client_session *session,
    struct sockaddr_storage *relay_addr,
    socklen_t *relay_addr_len) {
    uint8_t *rep = session->associate_read_buf;
    if (session->associate_read_len < session->associate_read_expected ||
        rep[0] != 0x05 || rep[1] != 0x00) {
        errno = EPROTO;
        return -1;
    }
    memset(relay_addr, 0, sizeof(*relay_addr));
    if (rep[3] == 0x01) {
        if (session->associate_read_expected != 10U) {
            errno = EPROTO;
            return -1;
        }
        struct sockaddr_in *addr = (struct sockaddr_in *)relay_addr;
        addr->sin_family = AF_INET;
        memcpy(&addr->sin_addr, rep + 4, 4U);
        memcpy(&addr->sin_port, rep + 8, 2U);
        *relay_addr_len = sizeof(*addr);
        return 0;
    }
    if (rep[3] == 0x04) {
        if (session->associate_read_expected != 22U) {
            errno = EPROTO;
            return -1;
        }
        struct sockaddr_in6 *addr = (struct sockaddr_in6 *)relay_addr;
        addr->sin6_family = AF_INET6;
        memcpy(&addr->sin6_addr, rep + 4, 16U);
        memcpy(&addr->sin6_port, rep + 20, 2U);
        *relay_addr_len = sizeof(*addr);
        return 0;
    }
    if (rep[3] == 0x03) {
        uint8_t host_len = rep[4];
        if (host_len == 0U || session->associate_read_expected != 4U + 1U + host_len + 2U) {
            errno = EPROTO;
            return -1;
        }
        char host[256];
        memcpy(host, rep + 5, host_len);
        host[host_len] = '\0';
        uint16_t port = ((uint16_t)rep[5U + host_len] << 8) | rep[6U + host_len];
        char port_buf[8];
        snprintf(port_buf, sizeof(port_buf), "%u", port);
        struct addrinfo hints;
        struct addrinfo *result = NULL;
        memset(&hints, 0, sizeof(hints));
        hints.ai_socktype = SOCK_DGRAM;
        hints.ai_family = AF_UNSPEC;
        if (getaddrinfo(host, port_buf, &hints, &result) != 0 || result == NULL) {
            errno = ENOENT;
            return -1;
        }
        if ((size_t)result->ai_addrlen > sizeof(*relay_addr)) {
            freeaddrinfo(result);
            errno = EOVERFLOW;
            return -1;
        }
        memcpy(relay_addr, result->ai_addr, result->ai_addrlen);
        *relay_addr_len = (socklen_t)result->ai_addrlen;
        freeaddrinfo(result);
        return 0;
    }
    errno = EPROTO;
    return -1;
}

static int update_udp_associate_response_expected(struct bpf2socks_udp_client_session *session) {
    uint8_t *rep = session->associate_read_buf;
    if (session->associate_read_expected == 4U && session->associate_read_len >= 4U) {
        if (rep[0] != 0x05 || rep[1] != 0x00) {
            errno = EPROTO;
            return -1;
        }
        if (rep[3] == 0x01) {
            session->associate_read_expected = 10U;
        } else if (rep[3] == 0x04) {
            session->associate_read_expected = 22U;
        } else if (rep[3] == 0x03) {
            session->associate_read_expected = 5U;
        } else {
            errno = EPROTO;
            return -1;
        }
    }
    if (session->associate_read_expected == 5U && session->associate_read_len >= 5U) {
        session->associate_read_expected = 4U + 1U + rep[4] + 2U;
    }
    if (session->associate_read_expected > sizeof(session->associate_read_buf)) {
        errno = EOVERFLOW;
        return -1;
    }
    return 0;
}

static void close_session(struct udp_state *state, struct bpf2socks_udp_client_session *session) {
    if (session == NULL || !session->used) return;
    release_session_pending_packets(state, session);
    if (session->tcp_fd >= 0) close(session->tcp_fd);
    if (session->udp_fd >= 0) close(session->udp_fd);
    while (session->bindings != NULL) {
        destroy_binding(state, session, session->bindings);
    }
    memset(session, 0, sizeof(*session));
    session->tcp_fd = -1;
    session->udp_fd = -1;
}

static int open_udp_associate_tcp_fd(
    const struct sockaddr *socks_addr,
    socklen_t socks_addr_len,
    enum bpf2socks_udp_session_stage *stage) {
    if (socks_addr == NULL || socks_addr_len == 0U || stage == NULL) {
        errno = EINVAL;
        return -1;
    }
    int fd = socket(socks_addr->sa_family, SOCK_STREAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;
    bpf2socks_bridge_tune_tcp_advanced(fd, true);
    if (connect(fd, socks_addr, socks_addr_len) == 0) {
        *stage = BPF2SOCKS_UDP_SESSION_ASSOC_WRITE;
        return fd;
    }
    if (errno == EINPROGRESS) {
        *stage = BPF2SOCKS_UDP_SESSION_CONNECTING;
        return fd;
    }
    int saved = errno;
    close(fd);
    errno = saved;
    return -1;
}

static int finish_udp_associate_connect(struct bpf2socks_udp_client_session *session) {
    int error = 0;
    socklen_t error_len = sizeof(error);
    if (getsockopt(session->tcp_fd, SOL_SOCKET, SO_ERROR, &error, &error_len) != 0) return -1;
    if (error != 0) {
        errno = error;
        return -1;
    }
    session->stage = BPF2SOCKS_UDP_SESSION_ASSOC_WRITE;
    return 0;
}

static int flush_udp_associate_write(struct bpf2socks_udp_client_session *session) {
    while (session->associate_write_offset < session->associate_write_len) {
        ssize_t sent = send(
            session->tcp_fd,
            session->associate_write_buf + session->associate_write_offset,
            session->associate_write_len - session->associate_write_offset,
            MSG_DONTWAIT | MSG_NOSIGNAL);
        if (sent < 0 && errno == EINTR) continue;
        if (sent < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) return 0;
        if (sent <= 0) return -1;
        session->associate_write_offset += (size_t)sent;
    }
    session->stage = BPF2SOCKS_UDP_SESSION_ASSOC_READ_HELLO;
    session->associate_read_len = 0U;
    session->associate_read_expected = 2U;
    return 1;
}

static int read_udp_associate_bytes(struct bpf2socks_udp_client_session *session) {
    while (session->associate_read_len < session->associate_read_expected) {
        ssize_t received = recv(
            session->tcp_fd,
            session->associate_read_buf + session->associate_read_len,
            session->associate_read_expected - session->associate_read_len,
            MSG_DONTWAIT);
        if (received < 0 && errno == EINTR) continue;
        if (received < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) return 0;
        if (received <= 0) return -1;
        session->associate_read_len += (size_t)received;
        if (session->stage == BPF2SOCKS_UDP_SESSION_ASSOC_READ_RESPONSE &&
            update_udp_associate_response_expected(session) < 0) {
            return -1;
        }
    }
    return 1;
}

static int read_udp_associate_hello(struct bpf2socks_udp_client_session *session) {
    int result = read_udp_associate_bytes(session);
    if (result <= 0) return result;
    if (session->associate_read_buf[0] != 0x05 || session->associate_read_buf[1] != 0x00) {
        errno = EPROTO;
        return -1;
    }
    session->stage = BPF2SOCKS_UDP_SESSION_ASSOC_READ_RESPONSE;
    session->associate_read_len = 0U;
    session->associate_read_expected = 4U;
    return 1;
}

static int create_udp_associate_relay_socket(
    struct bpf2socks_bridge_worker *worker,
    struct bpf2socks_udp_client_session *session) {
    struct sockaddr_storage relay_addr;
    socklen_t relay_addr_len = 0U;
    if (parse_udp_associate_response_addr(session, &relay_addr, &relay_addr_len) < 0 ||
        replace_unspecified_udp_relay_addr(
            (const struct sockaddr *)&worker->socks_addr,
            &relay_addr,
            &relay_addr_len) < 0) {
        return -1;
    }
    int udp_fd = socket(relay_addr.ss_family, SOCK_DGRAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
    if (udp_fd < 0) return -1;
    bpf2socks_bridge_tune_udp_advanced(udp_fd);
    bpf2socks_bridge_tune_socket_buffers(
        udp_fd,
        worker->config->udp_recv_buffer_size,
        worker->config->udp_recv_buffer_size);
    session->udp_fd = udp_fd;
    session->relay_addr = relay_addr;
    session->relay_addr_len = relay_addr_len;
    return 0;
}

static int flush_session_pending_packets(
    struct udp_state *state,
    struct bpf2socks_bridge_worker *worker,
    struct bpf2socks_udp_client_session *session) {
    while (session->pending_head != NULL) {
        struct bpf2socks_udp_msg messages[64];
        struct bpf2socks_udp_pending_packet *packets[64];
        unsigned int count = 0U;
        for (struct bpf2socks_udp_pending_packet *packet = session->pending_head;
             packet != NULL && count < 64U;
             packet = packet->next) {
            messages[count] = (struct bpf2socks_udp_msg){
                .addr = packet->original_dst,
                .payload = packet->payload,
                .payload_len = packet->payload_len,
            };
            packets[count] = packet;
            ++count;
        }
        int sent = bpf2socks_socks5_udp_sendmmsg(
            session->udp_fd,
            (const struct sockaddr *)&session->relay_addr,
            session->relay_addr_len,
            messages,
            count);
        if (sent <= 0) return -1;
        worker->stats.udp_packets_to_upstream += (uint64_t)sent;
        session->last_seen_ms = monotonic_ms();
        lru_touch(state, session);
        for (int i = 0; i < sent; ++i) {
            struct bpf2socks_udp_pending_packet *packet = packets[i];
            if (session->pending_head == packet) session->pending_head = packet->next;
            if (session->pending_tail == packet) session->pending_tail = NULL;
            if (session->pending_count > 0U) --session->pending_count;
            release_pending_packet(state, packet);
        }
        if ((unsigned int)sent < count) {
            errno = EIO;
            return -1;
        }
    }
    session->pending_tail = NULL;
    return 0;
}

static int complete_udp_associate(
    struct udp_state *state,
    struct bpf2socks_bridge_worker *worker,
    struct bpf2socks_udp_client_session *session) {
    if (create_udp_associate_relay_socket(worker, session) < 0 ||
        switch_session_epoll_to_udp(state, session) < 0) {
        return -1;
    }
    session->stage = BPF2SOCKS_UDP_SESSION_READY;
    ++worker->stats.udp_associate_creates;
    return flush_session_pending_packets(state, worker, session);
}

static int advance_udp_associate(
    struct udp_state *state,
    struct bpf2socks_bridge_worker *worker,
    struct bpf2socks_udp_client_session *session,
    uint32_t events) {
    if ((events & (EPOLLERR | EPOLLHUP)) != 0) return -1;
    if (session->stage == BPF2SOCKS_UDP_SESSION_CONNECTING && (events & EPOLLOUT) != 0) {
        if (finish_udp_associate_connect(session) < 0) return -1;
    }
    if (session->stage == BPF2SOCKS_UDP_SESSION_ASSOC_WRITE && (events & EPOLLOUT) != 0) {
        int result = flush_udp_associate_write(session);
        if (result < 0) return -1;
        if (result > 0) {
            size_t index = session_index(state, session);
            if (index < state->session_cap) {
                struct udp_fd_ref *ref = &state->session_refs[index].control;
                if (mod_udp_epoll_fd_events(state, session->tcp_fd, ref, EPOLLIN) < 0) return -1;
            }
        }
    }
    if (session->stage == BPF2SOCKS_UDP_SESSION_ASSOC_READ_HELLO && (events & EPOLLIN) != 0) {
        int result = read_udp_associate_hello(session);
        if (result < 0) return -1;
        if (result == 0) return 0;
    }
    if (session->stage == BPF2SOCKS_UDP_SESSION_ASSOC_READ_RESPONSE && (events & EPOLLIN) != 0) {
        int result = read_udp_associate_bytes(session);
        if (result < 0) return -1;
        if (result > 0) return complete_udp_associate(state, worker, session);
    }
    return 0;
}

static uint32_t session_bucket(const struct sockaddr_storage *addr, socklen_t addr_len) {
    return sockaddr_hash(addr, addr_len) % BPF2SOCKS_SESSION_HASH_BUCKETS;
}

static struct bpf2socks_udp_client_session *find_session(
    struct udp_state *state,
    const struct sockaddr_storage *client_addr,
    socklen_t client_addr_len) {
    uint32_t bucket = session_bucket(client_addr, client_addr_len);
    for (struct bpf2socks_udp_client_session *session = state->session_buckets[bucket];
         session != NULL;
         session = session->next) {
        if (session->used &&
            same_sockaddr(&session->client_addr, session->client_addr_len, client_addr, client_addr_len)) {
            session->last_seen_ms = monotonic_ms();
            lru_touch(state, session);
            return session;
        }
    }
    return NULL;
}

static void unlink_session_from_hash(struct udp_state *state, struct bpf2socks_udp_client_session *session) {
    uint32_t bucket = session_bucket(&session->client_addr, session->client_addr_len);
    struct bpf2socks_udp_client_session **slot = &state->session_buckets[bucket];
    while (*slot != NULL) {
        if (*slot == session) {
            *slot = session->next;
            session->next = NULL;
            return;
        }
        slot = &(*slot)->next;
    }
}

static void drop_session(struct udp_state *state, struct bpf2socks_udp_client_session *session) {
    if (state == NULL || session == NULL || !session->used) return;
    remove_udp_epoll_fd(state, session->tcp_fd);
    remove_udp_epoll_fd(state, session->udp_fd);
    size_t index = session_index(state, session);
    if (index < state->session_cap) {
        memset(&state->session_refs[index], 0, sizeof(state->session_refs[index]));
        state->session_refs[index].control.kind = UDP_FD_SESSION_CONTROL;
        state->session_refs[index].control.fd = -1;
        state->session_refs[index].relay.kind = UDP_FD_SESSION_RELAY;
        state->session_refs[index].relay.fd = -1;
    }
    unlink_session_from_hash(state, session);
    lru_remove(state, session);
    close_session(state, session);
    if (state->session_count > 0U) --state->session_count;
    session->free_next = state->free_sessions;
    state->free_sessions = session;
}

static void udp_session_control_event(
    struct udp_state *state,
    struct bpf2socks_udp_client_session *session,
    uint32_t events) {
    if (session == NULL || session->tcp_fd < 0) return;
    if (udp_control_event_is_failure(session->tcp_fd, events)) {
        drop_session(state, session);
    }
}

static struct bpf2socks_udp_client_session *alloc_session(struct udp_state *state, struct bpf2socks_bridge_worker *worker) {
    if (state->free_sessions != NULL) {
        struct bpf2socks_udp_client_session *session = state->free_sessions;
        state->free_sessions = session->free_next;
        session->free_next = NULL;
        return session;
    }
    struct bpf2socks_udp_client_session *victim = state->lru_head;
    if (victim == NULL) {
        errno = ENOMEM;
        return NULL;
    }
    drop_session(state, victim);
    ++worker->stats.udp_session_evictions;
    return alloc_session(state, worker);
}

static struct bpf2socks_udp_reply_binding *find_binding(
    struct bpf2socks_udp_client_session *session,
    const struct bpf2socks_sockaddr *original) {
    for (struct bpf2socks_udp_reply_binding *binding = session->bindings;
         binding != NULL;
         binding = binding->next) {
        if (same_original_dst(&binding->original_dst, original)) return binding;
    }
    return NULL;
}

static struct bpf2socks_udp_reply_binding *create_binding(
    struct udp_state *state,
    struct bpf2socks_udp_client_session *session,
    const struct bpf2socks_sockaddr *original,
    const uint8_t *token_addr,
    size_t token_addr_len,
    bool connected_udp_token,
    bool needs_original_reply_socket,
    bool fullcone,
    struct bpf2socks_bridge_worker *worker) {
    if (ensure_binding_capacity(state, worker) < 0) return NULL;
    struct bpf2socks_udp_reply_binding *binding = alloc_binding(state);
    if (binding == NULL) return NULL;
    binding->original_dst = *original;
    if (token_addr != NULL && token_addr_len > 0U) {
        if (token_addr_len > sizeof(binding->token_addr)) token_addr_len = sizeof(binding->token_addr);
        memcpy(binding->token_addr, token_addr, token_addr_len);
    }
    binding->last_seen_ms = monotonic_ms();
    binding->connected_udp_token = connected_udp_token;
    if (needs_original_reply_socket) {
        binding->reply_fd = create_udp_client_reply_socket(original, &binding->reply_raw);
        if (binding->reply_fd < 0) {
            fprintf(stderr, "failed to create UDP original reply socket: errno=%d\n", errno);
            release_binding(state, binding);
            return NULL;
        }
    }
    binding->used = true;
    binding->owner = session;
    binding->next = session->bindings;
    session->bindings = binding;
    binding_session_lru_add_tail(session, binding);
    binding_global_lru_add_tail(state, binding);
    ++session->binding_count;
    ++state->binding_count;
    ++worker->stats.udp_reply_binding_creates;
    if (fullcone) ++worker->stats.udp_fullcone_binding_creates;
    return binding;
}

static struct bpf2socks_udp_reply_binding *create_packet_binding(
    struct udp_state *state,
    struct bpf2socks_udp_client_session *session,
    const struct udp_client_packet *packet,
    struct bpf2socks_bridge_worker *worker) {
    return create_binding(
        state,
        session,
        &packet->original_dst,
        packet->token_addr,
        packet->token_family == AF_INET6 ? 16U : 4U,
        packet->connected_udp_token,
        !original_uses_connected_udp_token(packet) &&
            (packet->original_from_socket || packet_needs_original_reply_socket(packet)),
        false,
        worker);
}

static struct bpf2socks_udp_reply_binding *create_fullcone_binding(
    struct udp_state *state,
    struct bpf2socks_udp_client_session *session,
    const struct bpf2socks_sockaddr *remote,
    struct bpf2socks_bridge_worker *worker) {
    return create_binding(
        state,
        session,
        remote,
        NULL,
        0U,
        false,
        remote->family == AF_INET || remote->family == AF_INET6,
        true,
        worker);
}

static struct bpf2socks_udp_client_session *get_session(
    struct udp_state *state,
    const struct udp_client_packet *packet,
    struct bpf2socks_bridge_worker *worker) {
    struct bpf2socks_udp_client_session *session =
        find_session(state, &packet->client_addr, packet->client_addr_len);
    if (session != NULL) {
        ++worker->stats.udp_session_hits;
        ++worker->stats.udp_associate_reuses;
        return session;
    }

    ++worker->stats.udp_session_misses;
    session = alloc_session(state, worker);
    if (session == NULL) return NULL;
    memset(session, 0, sizeof(*session));
    session->tcp_fd = -1;
    session->udp_fd = -1;
    session->used = true;
    session->client_addr = packet->client_addr;
    session->client_addr_len = packet->client_addr_len;
    session->last_seen_ms = monotonic_ms();
    if (build_udp_associate_pipeline_request(session) < 0) {
        close_session(state, session);
        session->free_next = state->free_sessions;
        state->free_sessions = session;
        return NULL;
    }
    session->tcp_fd = open_udp_associate_tcp_fd(
        (const struct sockaddr *)&worker->socks_addr,
        worker->socks_addr_len,
        &session->stage);
    if (session->tcp_fd < 0) {
        close_session(state, session);
        session->free_next = state->free_sessions;
        state->free_sessions = session;
        return NULL;
    }
    uint32_t bucket = session_bucket(&session->client_addr, session->client_addr_len);
    session->next = state->session_buckets[bucket];
    state->session_buckets[bucket] = session;
    lru_add_tail(state, session);
    ++state->session_count;
    if (add_session_tcp_epoll_fd(state, session, EPOLLIN | EPOLLOUT) < 0) {
        drop_session(state, session);
        return NULL;
    }
    return session;
}

static int lookup_udp_original(
    const struct bpf2socks_runtime_config *config,
    struct udp_client_packet *packet) {
    struct bpf2socks_token_key key;
    struct bpf2socks_original_dst original;
    memset(&key, 0, sizeof(key));
    key.family = (uint8_t)packet->token_family;
    key.protocol = BPF2SOCKS_PROTO_UDP;
    key.token_port = config->listen_port;
    memcpy(key.token_addr, packet->token_addr, packet->token_family == AF_INET6 ? 16U : 4U);
    if (packet->client_addr.ss_family == AF_INET) {
        const struct sockaddr_in *client = (const struct sockaddr_in *)&packet->client_addr;
        key.client_port = ntohs(client->sin_port);
        memcpy(key.client_addr, &client->sin_addr, 4);
    } else if (packet->client_addr.ss_family == AF_INET6) {
        const struct sockaddr_in6 *client = (const struct sockaddr_in6 *)&packet->client_addr;
        key.client_port = ntohs(client->sin6_port);
        memcpy(key.client_addr, &client->sin6_addr, 16);
    }
    if (lookup_original_with_client_fallback(config->token_map_fd, &key, &original) < 0) {
        if (packet->has_original_dst) {
            packet->original_from_socket = true;
            return 0;
        }
        return -1;
    }
    packet->connected_udp_token =
        (original.flags & BPF2SOCKS_ORIGINAL_DST_FLAG_CONNECTED_UDP) != 0U;
    return original_to_sockaddr(&original, &packet->original_dst);
}

static int parse_udp_client_packet_cmsgs(
    struct msghdr *msg,
    const struct bpf2socks_runtime_config *config,
    struct udp_client_packet *packet) {
    bool has_pktinfo = false;
    for (struct cmsghdr *cmsg = CMSG_FIRSTHDR(msg); cmsg != NULL; cmsg = CMSG_NXTHDR(msg, cmsg)) {
        if (cmsg->cmsg_level == IPPROTO_IP && cmsg->cmsg_type == IP_PKTINFO) {
            const struct in_pktinfo *pktinfo = (const struct in_pktinfo *)CMSG_DATA(cmsg);
            packet->token_family = AF_INET;
            memcpy(packet->token_addr, &pktinfo->ipi_addr, 4);
            has_pktinfo = true;
            continue;
        } else if (cmsg->cmsg_level == IPPROTO_IPV6 && cmsg->cmsg_type == IPV6_PKTINFO) {
            const struct in6_pktinfo *pktinfo = (const struct in6_pktinfo *)CMSG_DATA(cmsg);
            packet->token_family = AF_INET6;
            memcpy(packet->token_addr, &pktinfo->ipi6_addr, 16);
            has_pktinfo = true;
            continue;
        }

        struct bpf2socks_original_dst original;
        if (bpf2socks_original_from_cmsg(cmsg, config, BPF2SOCKS_PROTO_UDP, &original) == 0 &&
            original_to_sockaddr(&original, &packet->original_dst) == 0) {
            packet->has_original_dst = true;
        }
    }
    return has_pktinfo ? 0 : -1;
}

static void init_udp_downlink_batch(struct udp_downlink_batch *batch) {
    memset(batch, 0, sizeof(*batch));
    batch->fd = -1;
}

static int fill_udp_downlink_pktinfo(
    struct msghdr *msg,
    char *control,
    size_t control_size,
    const struct bpf2socks_udp_client_session *session,
    const struct bpf2socks_udp_reply_binding *binding) {
    msg->msg_control = control;
    msg->msg_controllen = control_size;
    struct cmsghdr *cmsg = CMSG_FIRSTHDR(msg);
    if (cmsg == NULL) {
        errno = EMSGSIZE;
        return -1;
    }

    if (session->client_addr.ss_family == AF_INET6) {
        cmsg->cmsg_level = IPPROTO_IPV6;
        cmsg->cmsg_type = IPV6_PKTINFO;
        cmsg->cmsg_len = CMSG_LEN(sizeof(struct in6_pktinfo));
        struct in6_pktinfo *pktinfo = (struct in6_pktinfo *)CMSG_DATA(cmsg);
        memset(pktinfo, 0, sizeof(*pktinfo));
        memcpy(&pktinfo->ipi6_addr, binding->token_addr, 16);
        msg->msg_controllen = CMSG_SPACE(sizeof(struct in6_pktinfo));
    } else {
        cmsg->cmsg_level = IPPROTO_IP;
        cmsg->cmsg_type = IP_PKTINFO;
        cmsg->cmsg_len = CMSG_LEN(sizeof(struct in_pktinfo));
        struct in_pktinfo *pktinfo = (struct in_pktinfo *)CMSG_DATA(cmsg);
        memset(pktinfo, 0, sizeof(*pktinfo));
        memcpy(&pktinfo->ipi_spec_dst, binding->token_addr, 4);
        msg->msg_controllen = CMSG_SPACE(sizeof(struct in_pktinfo));
    }
    return 0;
}

static bool udp_downlink_batch_should_zerocopy(const struct udp_downlink_batch *batch) {
    if (batch == NULL) return false;
    size_t total = 0U;
    for (unsigned int i = 0U; i < batch->count; ++i) {
        if (batch->messages[i] == NULL) continue;
        total += batch->messages[i]->payload_len;
        if (total >= BPF2SOCKS_UDP_ZEROCOPY_MIN_BYTES) return true;
    }
    return false;
}

static int send_udp_downlink_mmsg(
    int fd,
    struct mmsghdr *vec,
    unsigned int count,
    bool zerocopy) {
    if (zerocopy) {
        bpf2socks_drain_zerocopy_completions(fd);
    }
    int sent = sendmmsg(fd, vec, count, zerocopy ? MSG_ZEROCOPY : 0);
    if (sent < 0 &&
        zerocopy &&
        (errno == EAGAIN || errno == EWOULDBLOCK || errno == ENOBUFS)) {
        bpf2socks_drain_zerocopy_completions(fd);
        sent = sendmmsg(fd, vec, count, 0);
    }
    if (zerocopy && sent > 0) {
        bpf2socks_drain_zerocopy_completions(fd);
    }
    return sent;
}

static int flush_udp_downlink_batch(
    struct udp_downlink_batch *batch,
    struct bpf2socks_bridge_worker *worker) {
    if (batch->count == 0U) return 0;

    struct mmsghdr vec[64];
    struct iovec iov[64];
    char control[64][CMSG_SPACE(sizeof(struct in_pktinfo)) + CMSG_SPACE(sizeof(struct in6_pktinfo))];
    memset(vec, 0, sizeof(vec));
    memset(control, 0, sizeof(control));

    for (unsigned int i = 0U; i < batch->count; ++i) {
        const struct bpf2socks_udp_msg *message = batch->messages[i];
        iov[i].iov_base = message->payload;
        iov[i].iov_len = message->payload_len;
        vec[i].msg_hdr.msg_name = &batch->session->client_addr;
        vec[i].msg_hdr.msg_namelen = batch->session->client_addr_len;
        vec[i].msg_hdr.msg_iov = &iov[i];
        vec[i].msg_hdr.msg_iovlen = 1U;
        if (batch->use_pktinfo &&
            fill_udp_downlink_pktinfo(
                &vec[i].msg_hdr,
                control[i],
                sizeof(control[i]),
                batch->session,
                batch->bindings[i]) < 0) {
            return -1;
        }
    }

    bool zerocopy = udp_downlink_batch_should_zerocopy(batch);
    int sent = send_udp_downlink_mmsg(batch->fd, vec, batch->count, zerocopy);
    if (sent <= 0) return -1;

    uint64_t now_ms = monotonic_ms();
    batch->session->last_seen_ms = now_ms;
    for (int i = 0; i < sent; ++i) {
        batch->bindings[i]->last_seen_ms = now_ms;
        ++worker->stats.udp_packets_to_client;
    }
    if ((unsigned int)sent < batch->count) {
        init_udp_downlink_batch(batch);
        errno = EIO;
        return -1;
    }

    init_udp_downlink_batch(batch);
    return 0;
}

static bool udp_downlink_batch_matches(
    const struct udp_downlink_batch *batch,
    int fd,
    bool use_pktinfo,
    const struct bpf2socks_udp_client_session *session) {
    return batch->count > 0U &&
        batch->fd == fd &&
        batch->use_pktinfo == use_pktinfo &&
        batch->session == session;
}

static int queue_udp_downlink_batch(
    struct udp_downlink_batch *batch,
    struct bpf2socks_bridge_worker *worker,
    struct bpf2socks_udp_client_session *session,
    struct bpf2socks_udp_reply_binding *binding,
    const struct bpf2socks_udp_msg *message,
    int fd,
    bool use_pktinfo) {
    if (batch->count > 0U && !udp_downlink_batch_matches(batch, fd, use_pktinfo, session)) {
        if (flush_udp_downlink_batch(batch, worker) < 0) return -1;
    }
    if (batch->count == 0U) {
        batch->fd = fd;
        batch->use_pktinfo = use_pktinfo;
        batch->session = session;
    }
    batch->bindings[batch->count] = binding;
    batch->messages[batch->count] = message;
    ++batch->count;
    if (batch->count >= 64U) {
        return flush_udp_downlink_batch(batch, worker);
    }
    return 0;
}

static bool is_dns_fast_path_candidate(
    const struct bpf2socks_runtime_config *config,
    const struct udp_client_packet *packet) {
    return config != NULL &&
        config->enable_dns_hijack &&
        packet != NULL &&
        packet->original_dst.port == 53U &&
        (packet->original_dst.family == AF_INET || packet->original_dst.family == AF_INET6);
}

static void release_dns_channel_transactions(struct udp_state *state, uint32_t channel_index) {
    if (state == NULL || state->dns_table.transactions == NULL) return;
    for (size_t i = 0U; i < state->dns_table.capacity; ++i) {
        struct bpf2socks_dns_transaction *tx = &state->dns_table.transactions[i];
        if (tx->used && tx->channel_index == channel_index) {
            bpf2socks_dns_table_release(&state->dns_table, tx);
        }
    }
}

static void reset_dns_channel_fds(struct udp_state *state, struct dns_channel *channel) {
    if (channel == NULL) return;
    if (channel->tcp_fd >= 0) remove_udp_epoll_fd(state, channel->tcp_fd);
    if (channel->udp_fd >= 0) remove_udp_epoll_fd(state, channel->udp_fd);
    if (channel->tcp_fd >= 0) close(channel->tcp_fd);
    if (channel->udp_fd >= 0) close(channel->udp_fd);
    channel->tcp_fd = -1;
    channel->udp_fd = -1;
    channel->available = false;
    memset(&channel->relay_addr, 0, sizeof(channel->relay_addr));
    channel->relay_addr_len = 0U;
    memset(&channel->control_ref, 0, sizeof(channel->control_ref));
    channel->control_ref.kind = UDP_FD_DNS_CONTROL;
    channel->control_ref.fd = -1;
    channel->control_ref.dns_channel_index = channel->index;
    memset(&channel->relay_ref, 0, sizeof(channel->relay_ref));
    channel->relay_ref.kind = UDP_FD_DNS_RELAY;
    channel->relay_ref.fd = -1;
    channel->relay_ref.dns_channel_index = channel->index;
}

static void close_dns_channel(struct udp_state *state, struct dns_channel *channel, uint64_t now_ms) {
    if (state == NULL || channel == NULL) return;
    release_dns_channel_transactions(state, channel->index);
    reset_dns_channel_fds(state, channel);
    if (now_ms == 0U) now_ms = monotonic_ms();
    channel->next_rebuild_ms = now_ms + BPF2SOCKS_DNS_CHANNEL_REBUILD_DELAY_MS;
}

static void udp_dns_control_event(
    struct udp_state *state,
    struct dns_channel *channel,
    uint32_t events,
    uint64_t now_ms) {
    if (channel == NULL || channel->tcp_fd < 0) return;
    if (udp_control_event_is_failure(channel->tcp_fd, events)) {
        close_dns_channel(state, channel, now_ms);
    }
}

static int register_dns_channel_epoll(struct udp_state *state, struct dns_channel *channel) {
    if (state == NULL || channel == NULL || channel->tcp_fd < 0 || channel->udp_fd < 0) {
        errno = EINVAL;
        return -1;
    }
    channel->control_ref.kind = UDP_FD_DNS_CONTROL;
    channel->control_ref.fd = channel->tcp_fd;
    channel->control_ref.session = NULL;
    channel->control_ref.dns_channel_index = channel->index;
    channel->relay_ref.kind = UDP_FD_DNS_RELAY;
    channel->relay_ref.fd = channel->udp_fd;
    channel->relay_ref.session = NULL;
    channel->relay_ref.dns_channel_index = channel->index;
    if (add_udp_epoll_fd_events(
            state,
            channel->tcp_fd,
            &channel->control_ref,
            EPOLLIN | EPOLLRDHUP) < 0) {
        return -1;
    }
    if (add_udp_epoll_fd(state, channel->udp_fd, &channel->relay_ref) < 0) {
        int saved = errno;
        remove_udp_epoll_fd(state, channel->tcp_fd);
        errno = saved;
        return -1;
    }
    return 0;
}

static int open_dns_channel(
    struct udp_state *state,
    struct bpf2socks_bridge_worker *worker,
    struct dns_channel *channel,
    uint64_t now_ms) {
    if (state == NULL || worker == NULL || channel == NULL) {
        errno = EINVAL;
        return -1;
    }
    if (channel->available && channel->udp_fd >= 0) return 0;
    if (now_ms == 0U) now_ms = monotonic_ms();
    if (channel->next_rebuild_ms != 0U && now_ms < channel->next_rebuild_ms) {
        errno = EAGAIN;
        return -1;
    }

    reset_dns_channel_fds(state, channel);
    int tcp_fd = -1;
    int udp_fd = -1;
    struct sockaddr_storage relay_addr;
    socklen_t relay_addr_len = 0U;
    memset(&relay_addr, 0, sizeof(relay_addr));
    if (bpf2socks_socks5_udp_associate_addr(
            (const struct sockaddr *)&worker->socks_addr,
            worker->socks_addr_len,
            &tcp_fd,
            &udp_fd,
            &relay_addr,
            &relay_addr_len) < 0) {
        int saved = errno;
        if (tcp_fd >= 0) close(tcp_fd);
        if (udp_fd >= 0) close(udp_fd);
        channel->next_rebuild_ms = now_ms + BPF2SOCKS_DNS_CHANNEL_REBUILD_DELAY_MS;
        errno = saved;
        return -1;
    }

    bpf2socks_bridge_tune_socket_buffers(
        udp_fd,
        worker->config->udp_recv_buffer_size,
        worker->config->udp_recv_buffer_size);

    channel->tcp_fd = tcp_fd;
    channel->udp_fd = udp_fd;
    channel->relay_addr = relay_addr;
    channel->relay_addr_len = relay_addr_len;
    if (register_dns_channel_epoll(state, channel) < 0) {
        int saved = errno;
        reset_dns_channel_fds(state, channel);
        channel->next_rebuild_ms = now_ms + BPF2SOCKS_DNS_CHANNEL_REBUILD_DELAY_MS;
        errno = saved;
        return -1;
    }
    channel->available = true;
    channel->next_rebuild_ms = 0U;
    ++worker->stats.udp_associate_creates;
    return 0;
}

static int ensure_dns_channel(
    struct udp_state *state,
    struct bpf2socks_bridge_worker *worker,
    struct dns_channel *channel,
    uint64_t now_ms) {
    if (channel != NULL &&
        channel->available &&
        channel->tcp_fd >= 0 &&
        channel->udp_fd >= 0 &&
        udp_control_fd_is_healthy(channel->tcp_fd)) {
        ++worker->stats.udp_associate_reuses;
        return 0;
    }
    if (channel != NULL &&
        (channel->available || channel->tcp_fd >= 0 || channel->udp_fd >= 0)) {
        close_dns_channel(state, channel, now_ms);
    }
    return open_dns_channel(state, worker, channel, now_ms);
}

static struct dns_channel *select_dns_channel(
    struct udp_state *state,
    struct bpf2socks_bridge_worker *worker,
    uint64_t now_ms) {
    if (state == NULL || worker == NULL) return NULL;
    uint32_t start = state->next_dns_channel % BPF2SOCKS_DNS_CHANNELS_PER_WORKER;
    for (uint32_t attempt = 0U; attempt < BPF2SOCKS_DNS_CHANNELS_PER_WORKER; ++attempt) {
        uint32_t index = (start + attempt) % BPF2SOCKS_DNS_CHANNELS_PER_WORKER;
        struct dns_channel *channel = &state->dns_channels[index];
        if (ensure_dns_channel(state, worker, channel, now_ms) == 0) {
            state->next_dns_channel = (index + 1U) % BPF2SOCKS_DNS_CHANNELS_PER_WORKER;
            return channel;
        }
    }
    return NULL;
}

static int prepare_dns_client_packet(
    struct udp_state *state,
    struct bpf2socks_bridge_worker *worker,
    struct udp_client_packet *packet,
    const struct bpf2socks_dns_packet_info *info,
    uint64_t now_ms,
    struct bpf2socks_dns_transaction **out_tx,
    uint32_t *out_channel_index,
    struct bpf2socks_udp_msg *out_message) {
    if (state == NULL || worker == NULL || packet == NULL || info == NULL ||
        out_tx == NULL || out_channel_index == NULL || out_message == NULL) {
        errno = EINVAL;
        return -1;
    }
    struct dns_channel *channel = select_dns_channel(state, worker, now_ms);
    if (channel == NULL) return -1;

    struct bpf2socks_dns_transaction *tx = bpf2socks_dns_table_alloc(
        &state->dns_table,
        channel->index,
        info->id,
        info->question_fingerprint,
        now_ms);
    if (tx == NULL) return -1;

    channel = &state->dns_channels[tx->channel_index];
    if (ensure_dns_channel(state, worker, channel, now_ms) < 0) {
        bpf2socks_dns_table_release(&state->dns_table, tx);
        return -1;
    }

    tx->client_addr = packet->client_addr;
    tx->client_addr_len = packet->client_addr_len;
    tx->original_dst = packet->original_dst;
    tx->token_family = packet->token_family;
    memcpy(tx->token_addr, packet->token_addr, packet->token_family == AF_INET6 ? 16U : 4U);
    tx->connected_udp_token = packet->connected_udp_token;
    tx->original_from_socket = packet->original_from_socket;

    bpf2socks_dns_set_id(packet->payload, packet->payload_len, tx->rewritten_id);
    *out_tx = tx;
    *out_channel_index = tx->channel_index;
    *out_message = (struct bpf2socks_udp_msg){
        .addr = packet->original_dst,
        .payload = packet->payload,
        .payload_len = packet->payload_len,
    };
    return 0;
}

static void flush_dns_outgoing_packets(
    struct udp_state *state,
    struct bpf2socks_bridge_worker *worker,
    struct bpf2socks_dns_transaction **transactions,
    uint32_t *channel_indices,
    struct bpf2socks_udp_msg *messages,
    unsigned int count) {
    if (state == NULL || worker == NULL || transactions == NULL ||
        channel_indices == NULL || messages == NULL) {
        return;
    }
    for (unsigned int i = 0U; i < count; ++i) {
        if (transactions[i] == NULL) continue;
        uint32_t channel_index = channel_indices[i];
        if (channel_index >= BPF2SOCKS_DNS_CHANNELS_PER_WORKER) {
            bpf2socks_dns_table_release(&state->dns_table, transactions[i]);
            transactions[i] = NULL;
            continue;
        }
        struct dns_channel *channel = &state->dns_channels[channel_index];
        struct bpf2socks_udp_msg send_messages[64];
        struct bpf2socks_dns_transaction *send_transactions[64];
        unsigned int send_count = 0U;
        for (unsigned int j = i; j < count; ++j) {
            if (transactions[j] == NULL || channel_indices[j] != channel_index) continue;
            send_transactions[send_count] = transactions[j];
            send_messages[send_count++] = messages[j];
            transactions[j] = NULL;
        }
        if (!channel->available || channel->udp_fd < 0) {
            close_dns_channel(state, channel, monotonic_ms());
            continue;
        }
        int sent = bpf2socks_socks5_udp_sendmmsg(
            channel->udp_fd,
            (const struct sockaddr *)&channel->relay_addr,
            channel->relay_addr_len,
            send_messages,
            send_count);
        if (sent <= 0) {
            ++worker->stats.udp_send_errors;
            if (errno == EAGAIN || errno == EWOULDBLOCK || errno == ENOBUFS) {
                for (unsigned int j = 0U; j < send_count; ++j) {
                    bpf2socks_dns_table_release(&state->dns_table, send_transactions[j]);
                }
            } else {
                close_dns_channel(state, channel, monotonic_ms());
            }
            continue;
        }
        worker->stats.udp_packets_to_upstream += (uint64_t)sent;
        if ((unsigned int)sent < send_count) {
            ++worker->stats.udp_send_errors;
            for (unsigned int j = (unsigned int)sent; j < send_count; ++j) {
                bpf2socks_dns_table_release(&state->dns_table, send_transactions[j]);
            }
        }
    }
}

static bool dns_transaction_needs_original_reply_socket(const struct bpf2socks_dns_transaction *tx) {
    if (tx == NULL || tx->connected_udp_token) return false;
    return tx->original_from_socket ||
        tx->original_dst.family == AF_INET ||
        tx->original_dst.family == AF_INET6;
}

static int ensure_dns_raw_reply_socket(struct udp_state *state, int family) {
    if (state == NULL) {
        errno = EINVAL;
        return -1;
    }
    if (family == AF_INET) {
        if (state->dns_raw4_fd < 0) state->dns_raw4_fd = create_raw_udp_reply_socket();
        return state->dns_raw4_fd;
    }
    if (family == AF_INET6) {
        if (state->dns_raw6_fd < 0) state->dns_raw6_fd = create_raw_udp6_reply_socket();
        return state->dns_raw6_fd;
    }
    errno = EAFNOSUPPORT;
    return -1;
}

static int send_dns_response_with_binding(
    struct bpf2socks_bridge_worker *worker,
    struct bpf2socks_udp_client_session *session,
    struct bpf2socks_udp_reply_binding *binding,
    const struct bpf2socks_udp_msg *message) {
    if (worker == NULL || session == NULL || binding == NULL || message == NULL) {
        errno = EINVAL;
        return -1;
    }
    if (binding->reply_fd >= 0) {
        if (binding->reply_raw) {
            int send_result = binding->original_dst.family == AF_INET6
                ? send_raw_udp6_to_client(binding->reply_fd, session, binding, message->payload, message->payload_len)
                : send_raw_udp_to_client(binding->reply_fd, session, binding, message->payload, message->payload_len);
            if (send_result < 0) return -1;
            ++worker->stats.udp_packets_to_client;
            return 0;
        }
        struct udp_downlink_batch batch;
        init_udp_downlink_batch(&batch);
        if (queue_udp_downlink_batch(&batch, worker, session, binding, message, binding->reply_fd, false) < 0) {
            return -1;
        }
        return flush_udp_downlink_batch(&batch, worker);
    }

    int reply_listener_fd = session->client_addr.ss_family == AF_INET6 && worker->udp_listener6_fd >= 0
        ? worker->udp_listener6_fd
        : worker->udp_listener_fd;
    struct udp_downlink_batch batch;
    init_udp_downlink_batch(&batch);
    if (queue_udp_downlink_batch(&batch, worker, session, binding, message, reply_listener_fd, true) < 0) {
        return -1;
    }
    return flush_udp_downlink_batch(&batch, worker);
}

static int send_dns_response_to_client(
    struct udp_state *state,
    struct bpf2socks_bridge_worker *worker,
    struct bpf2socks_dns_transaction *tx,
    const struct bpf2socks_udp_msg *message) {
    if (state == NULL || worker == NULL || tx == NULL || message == NULL) {
        errno = EINVAL;
        return -1;
    }
    struct bpf2socks_udp_client_session session;
    memset(&session, 0, sizeof(session));
    session.used = true;
    session.client_addr = tx->client_addr;
    session.client_addr_len = tx->client_addr_len;
    session.tcp_fd = -1;
    session.udp_fd = -1;

    struct bpf2socks_udp_reply_binding binding;
    memset(&binding, 0, sizeof(binding));
    binding.used = true;
    binding.original_dst = tx->original_dst;
    binding.connected_udp_token = tx->connected_udp_token;
    binding.reply_fd = -1;
    binding.reply_raw = false;
    binding.owner = &session;
    memcpy(binding.token_addr, tx->token_addr, tx->token_family == AF_INET6 ? 16U : 4U);

    if (dns_transaction_needs_original_reply_socket(tx)) {
        int raw_fd = ensure_dns_raw_reply_socket(state, tx->original_dst.family);
        if (raw_fd >= 0) {
            binding.reply_fd = raw_fd;
            binding.reply_raw = true;
            return send_dns_response_with_binding(worker, &session, &binding, message);
        }

        bool reply_raw = false;
        int reply_fd = create_udp_client_reply_socket(&tx->original_dst, &reply_raw);
        if (reply_fd < 0) return -1;
        binding.reply_fd = reply_fd;
        binding.reply_raw = reply_raw;
        int result = send_dns_response_with_binding(worker, &session, &binding, message);
        close(reply_fd);
        return result;
    }

    return send_dns_response_with_binding(worker, &session, &binding, message);
}

static void handle_dns_upstream_packets(
    struct udp_state *state,
    struct bpf2socks_bridge_worker *worker,
    struct dns_channel *channel,
    uint8_t *upstream_packets) {
    if (state == NULL || worker == NULL || channel == NULL || upstream_packets == NULL ||
        !channel->available || channel->udp_fd < 0) {
        return;
    }
    uint32_t batch = worker->config->udp_batch_size;
    if (batch == 0U) batch = BPF2SOCKS_DEFAULT_UDP_BATCH_SIZE;
    if (batch > 64U) batch = 64U;
    size_t stride = udp_payload_stride(worker->config) + BPF2SOCKS_MAX_SOCKS5_UDP_HEADER;

    struct bpf2socks_udp_msg messages[batch];
    memset(messages, 0, sizeof(messages));
    int received = bpf2socks_socks5_udp_recvmmsg(channel->udp_fd, messages, batch, upstream_packets, stride);
    if (received <= 0) {
        if (received < 0 && errno == EMSGSIZE) {
            ++worker->stats.udp_drops_oversized;
            return;
        }
        if (received < 0 && errno != EAGAIN && errno != EWOULDBLOCK) {
            close_dns_channel(state, channel, monotonic_ms());
        }
        return;
    }

    for (int i = 0; i < received; ++i) {
        ++worker->stats.udp_packets_from_upstream;
        struct bpf2socks_dns_packet_info info;
        if (bpf2socks_dns_parse_response(messages[i].payload, messages[i].payload_len, &info) < 0) {
            ++worker->stats.udp_drops_malformed_socks5;
            continue;
        }
        struct bpf2socks_dns_transaction *tx =
            bpf2socks_dns_table_find(&state->dns_table, channel->index, info.id);
        if (tx == NULL) continue;
        if (info.has_question && info.question_fingerprint != tx->question_fingerprint) continue;

        bpf2socks_dns_set_id(messages[i].payload, messages[i].payload_len, tx->original_id);
        if (send_dns_response_to_client(state, worker, tx, &messages[i]) < 0) {
            ++worker->stats.udp_send_errors;
            fprintf(stderr, "failed to send DNS response to client: errno=%d\n", errno);
            bpf2socks_dns_table_release(&state->dns_table, tx);
            continue;
        }
        bpf2socks_dns_table_release(&state->dns_table, tx);
    }
}

static void handle_udp_client_packets(
    struct udp_state *state,
    struct bpf2socks_bridge_worker *worker,
    int listener_fd,
    uint8_t *client_payloads) {
    uint32_t batch = worker->config->udp_batch_size;
    if (batch == 0U) batch = BPF2SOCKS_DEFAULT_UDP_BATCH_SIZE;
    if (batch > 64U) batch = 64U;
    size_t payload_stride = udp_payload_stride(worker->config);

    struct mmsghdr vec[batch];
    struct iovec iov[batch];
    struct sockaddr_storage addrs[batch];
    char control[batch][CMSG_SPACE(sizeof(struct in_pktinfo)) +
                        CMSG_SPACE(sizeof(struct in6_pktinfo)) +
                        CMSG_SPACE(sizeof(struct sockaddr_in)) +
                        CMSG_SPACE(sizeof(struct sockaddr_in6))];
    memset(vec, 0, sizeof(vec));
    memset(addrs, 0, sizeof(addrs));
    memset(control, 0, sizeof(control));
    for (uint32_t i = 0; i < batch; ++i) {
        iov[i].iov_base = client_payloads + (size_t)i * payload_stride;
        iov[i].iov_len = payload_stride;
        vec[i].msg_hdr.msg_name = &addrs[i];
        vec[i].msg_hdr.msg_namelen = sizeof(addrs[i]);
        vec[i].msg_hdr.msg_iov = &iov[i];
        vec[i].msg_hdr.msg_iovlen = 1;
        vec[i].msg_hdr.msg_control = control[i];
        vec[i].msg_hdr.msg_controllen = sizeof(control[i]);
    }

    int received = recvmmsg(listener_fd, vec, batch, MSG_DONTWAIT, NULL);
    if (received < 0) {
        if (errno != EAGAIN && errno != EWOULDBLOCK) {
            fprintf(stderr, "failed to receive UDP client packet batch: errno=%d\n", errno);
        }
        return;
    }

    struct bpf2socks_udp_client_session *outgoing_sessions[batch];
    struct bpf2socks_udp_msg outgoing_messages[batch];
    unsigned int outgoing_count = 0U;
    struct bpf2socks_dns_transaction *dns_transactions[batch];
    uint32_t dns_channel_indices[batch];
    struct bpf2socks_udp_msg dns_messages[batch];
    unsigned int dns_count = 0U;
    uint64_t now_ms = monotonic_ms();
    memset(outgoing_sessions, 0, sizeof(outgoing_sessions));
    memset(outgoing_messages, 0, sizeof(outgoing_messages));
    memset(dns_transactions, 0, sizeof(dns_transactions));
    memset(dns_channel_indices, 0, sizeof(dns_channel_indices));
    memset(dns_messages, 0, sizeof(dns_messages));
    (void)bpf2socks_dns_table_expire(
        &state->dns_table,
        now_ms,
        worker->config->dns_transaction_timeout_milliseconds,
        BPF2SOCKS_DNS_MAX_EXPIRE_PER_LOOP);

    for (int i = 0; i < received; ++i) {
        struct udp_client_packet packet;
        memset(&packet, 0, sizeof(packet));
        if ((vec[i].msg_hdr.msg_flags & MSG_TRUNC) != 0 || vec[i].msg_len > payload_stride) {
            ++worker->stats.udp_drops_oversized;
            fprintf(stderr, "dropping truncated UDP client packet: len=%u flags=0x%x\n",
                vec[i].msg_len,
                vec[i].msg_hdr.msg_flags);
            continue;
        }
        packet.client_addr = addrs[i];
        packet.client_addr_len = vec[i].msg_hdr.msg_namelen;
        packet.payload = client_payloads + (size_t)i * payload_stride;
        packet.payload_len = vec[i].msg_len;
        ++worker->stats.udp_packets_from_client;

        if (parse_udp_client_packet_cmsgs(&vec[i].msg_hdr, worker->config, &packet) < 0 ||
            lookup_udp_original(worker->config, &packet) < 0) {
            ++worker->stats.udp_token_misses;
            fprintf(stderr, "missing UDP original destination: errno=%d\n", errno);
            continue;
        }

        if (is_dns_fast_path_candidate(worker->config, &packet)) {
            struct bpf2socks_dns_packet_info dns_info;
            if (bpf2socks_dns_parse_query(packet.payload, packet.payload_len, &dns_info) == 0) {
                if (prepare_dns_client_packet(
                        state,
                        worker,
                        &packet,
                        &dns_info,
                        now_ms,
                        &dns_transactions[dns_count],
                        &dns_channel_indices[dns_count],
                        &dns_messages[dns_count]) == 0) {
                    ++dns_count;
                } else {
                    ++worker->stats.udp_send_errors;
                }
                continue;
            }
        }

        struct bpf2socks_udp_client_session *session = get_session(state, &packet, worker);
        if (session == NULL) {
            fprintf(stderr, "SOCKS5 UDP ASSOCIATE failed: errno=%d\n", errno);
            continue;
        }
        struct bpf2socks_udp_reply_binding *binding = find_binding(session, &packet.original_dst);
        if (binding == NULL) {
            binding = create_packet_binding(state, session, &packet, worker);
            if (binding == NULL) continue;
        } else {
            ++worker->stats.udp_reply_binding_hits;
            memcpy(binding->token_addr, packet.token_addr, packet.token_family == AF_INET6 ? 16U : 4U);
            bool needs_original_reply_socket =
                !original_uses_connected_udp_token(&packet) &&
                (packet.original_from_socket || packet_needs_original_reply_socket(&packet));
            if (!needs_original_reply_socket && binding->reply_fd >= 0) {
                close(binding->reply_fd);
                binding->reply_fd = -1;
                binding->reply_raw = false;
            } else if (needs_original_reply_socket && binding->reply_fd < 0) {
                binding->reply_fd = create_udp_client_reply_socket(&binding->original_dst, &binding->reply_raw);
                if (binding->reply_fd < 0) {
                    fprintf(stderr, "failed to create UDP original reply socket: errno=%d\n", errno);
                    continue;
                }
            }
            binding->connected_udp_token = packet.connected_udp_token;
            binding->last_seen_ms = monotonic_ms();
            binding_lru_touch(state, session, binding);
        }

        if (session->stage != BPF2SOCKS_UDP_SESSION_READY) {
            if (queue_pending_udp_packet(
                    state,
                    session,
                    &packet.original_dst,
                    packet.payload,
                    packet.payload_len,
                    payload_stride) < 0) {
                if (errno == ENOBUFS) {
                    ++worker->stats.udp_drops_pending_budget;
                    uint64_t drops = worker->stats.udp_drops_pending_budget;
                    if ((drops & (drops - 1U)) == 0U) {
                        fprintf(stderr,
                            "dropping UDP pending packet: pending budget exhausted (drops=%llu)\n",
                            (unsigned long long)drops);
                    }
                } else {
                    ++worker->stats.udp_send_errors;
                }
            }
            continue;
        }

        outgoing_sessions[outgoing_count] = session;
        outgoing_messages[outgoing_count] = (struct bpf2socks_udp_msg){
            .addr = packet.original_dst,
            .payload = packet.payload,
            .payload_len = packet.payload_len,
        };
        ++outgoing_count;
    }

    flush_dns_outgoing_packets(state, worker, dns_transactions, dns_channel_indices, dns_messages, dns_count);

    for (unsigned int i = 0U; i < outgoing_count; ++i) {
        struct bpf2socks_udp_client_session *session = outgoing_sessions[i];
        if (session == NULL) continue;
        struct udp_send_batch send_batch;
        memset(&send_batch, 0, sizeof(send_batch));
        send_batch.session = session;
        for (unsigned int j = i; j < outgoing_count; ++j) {
            if (outgoing_sessions[j] != session) continue;
            send_batch.messages[send_batch.count++] = outgoing_messages[j];
            outgoing_sessions[j] = NULL;
        }
        int sent = bpf2socks_socks5_udp_sendmmsg(
                session->udp_fd,
                (const struct sockaddr *)&session->relay_addr,
                session->relay_addr_len,
                send_batch.messages,
                send_batch.count);
        if (sent <= 0) {
            ++worker->stats.udp_send_errors;
            fprintf(stderr, "SOCKS5 UDP send failed: errno=%d\n", errno);
            drop_session(state, session);
            continue;
        }
        worker->stats.udp_packets_to_upstream += (uint64_t)sent;
        if ((unsigned int)sent < send_batch.count) {
            ++worker->stats.udp_send_errors;
            drop_session(state, session);
        }
    }
}

static void handle_udp_upstream_packets(
    struct udp_state *state,
    struct bpf2socks_bridge_worker *worker,
    struct bpf2socks_udp_client_session *session,
    uint8_t *upstream_packets) {
    uint32_t batch = worker->config->udp_batch_size;
    if (batch == 0U) batch = BPF2SOCKS_DEFAULT_UDP_BATCH_SIZE;
    if (batch > 64U) batch = 64U;
    size_t stride = udp_payload_stride(worker->config) + BPF2SOCKS_MAX_SOCKS5_UDP_HEADER;

    struct bpf2socks_udp_msg messages[batch];
    memset(messages, 0, sizeof(messages));
    int received = bpf2socks_socks5_udp_recvmmsg(session->udp_fd, messages, batch, upstream_packets, stride);
    if (received <= 0) {
        if (received < 0 && errno == EMSGSIZE) {
            ++worker->stats.udp_drops_oversized;
            return;
        }
        if (received < 0 && errno != EAGAIN && errno != EWOULDBLOCK) drop_session(state, session);
        return;
    }

    struct udp_downlink_batch downlink_batch;
    init_udp_downlink_batch(&downlink_batch);

    for (int i = 0; i < received; ++i) {
        ++worker->stats.udp_packets_from_upstream;
        struct bpf2socks_udp_reply_binding *binding = find_binding(session, &messages[i].addr);
        if (binding == NULL) {
            binding = create_fullcone_binding(state, session, &messages[i].addr, worker);
            if (binding == NULL) {
                ++worker->stats.udp_drops_malformed_socks5;
                continue;
            }
        } else {
            ++worker->stats.udp_reply_binding_hits;
            binding_lru_touch(state, session, binding);
        }
        if (binding->reply_fd >= 0) {
            if (binding->reply_raw) {
                if (flush_udp_downlink_batch(&downlink_batch, worker) < 0) {
                    ++worker->stats.udp_send_errors;
                    fprintf(stderr, "failed to send UDP response batch to client: errno=%d\n", errno);
                    drop_session(state, session);
                    return;
                }
                int send_result = binding->original_dst.family == AF_INET6
                    ? send_raw_udp6_to_client(
                          binding->reply_fd,
                          session,
                          binding,
                          messages[i].payload,
                          messages[i].payload_len)
                    : send_raw_udp_to_client(
                          binding->reply_fd,
                          session,
                          binding,
                          messages[i].payload,
                          messages[i].payload_len);
                if (send_result < 0) {
                    ++worker->stats.udp_send_errors;
                    fprintf(stderr, "failed to send UDP response to client: errno=%d\n", errno);
                    drop_session(state, session);
                    return;
                }
                uint64_t now_ms = monotonic_ms();
                session->last_seen_ms = now_ms;
                binding->last_seen_ms = now_ms;
                ++worker->stats.udp_packets_to_client;
            } else {
                if (queue_udp_downlink_batch(
                        &downlink_batch,
                        worker,
                        session,
                        binding,
                        &messages[i],
                        binding->reply_fd,
                        false) < 0) {
                    ++worker->stats.udp_send_errors;
                    fprintf(stderr, "failed to send UDP response batch to client: errno=%d\n", errno);
                    drop_session(state, session);
                    return;
                }
            }
        } else {
            int reply_listener_fd = session->client_addr.ss_family == AF_INET6 && worker->udp_listener6_fd >= 0
                ? worker->udp_listener6_fd
                : worker->udp_listener_fd;
            if (queue_udp_downlink_batch(
                    &downlink_batch,
                    worker,
                    session,
                    binding,
                    &messages[i],
                    reply_listener_fd,
                    true) < 0) {
                ++worker->stats.udp_send_errors;
                fprintf(stderr, "failed to send UDP response batch to client: errno=%d\n", errno);
                drop_session(state, session);
                return;
            }
        }
    }

    if (flush_udp_downlink_batch(&downlink_batch, worker) < 0) {
        ++worker->stats.udp_send_errors;
        fprintf(stderr, "failed to send UDP response batch to client: errno=%d\n", errno);
        drop_session(state, session);
    }
}

static void expire_sessions(struct udp_state *state, const struct bpf2socks_runtime_config *config) {
    uint64_t now_ms = monotonic_ms();
    if (now_ms == 0U) return;
    uint64_t timeout_ms = (uint64_t)bpf2socks_udp_effective_idle_timeout(
        config->udp_idle_timeout_seconds,
        BPF2SOCKS_DEFAULT_UDP_IDLE_TIMEOUT_SECONDS) * 1000ULL;
    struct bpf2socks_udp_client_session *session = state->lru_head;
    while (session != NULL) {
        struct bpf2socks_udp_client_session *next = session->lru_next;
        if (!session->used || now_ms - session->last_seen_ms < timeout_ms) {
            break;
        }
        drop_session(state, session);
        session = next;
    }
}

static void evict_idle_bindings(
    struct udp_state *state,
    const struct bpf2socks_runtime_config *config,
    struct bpf2socks_bridge_worker *worker) {
    uint64_t now_ms = monotonic_ms();
    if (now_ms == 0U) return;
    uint64_t timeout_ms = (uint64_t)bpf2socks_udp_effective_idle_timeout(
        config->udp_idle_timeout_seconds,
        BPF2SOCKS_DEFAULT_UDP_IDLE_TIMEOUT_SECONDS) * 1000ULL;
    struct bpf2socks_udp_reply_binding *binding = state->binding_lru_head;
    while (binding != NULL) {
        struct bpf2socks_udp_reply_binding *next = binding->global_lru_next;
        if (!binding->used || now_ms - binding->last_seen_ms < timeout_ms) break;
        struct bpf2socks_udp_client_session *session = binding->owner;
        destroy_binding(state, session, binding);
        ++worker->stats.udp_binding_evictions;
        binding = next;
    }
}

static int init_state(struct udp_state *state, struct bpf2socks_bridge_worker *worker) {
    if (state == NULL || worker == NULL || worker->config == NULL || worker->udp_pending_budget == NULL ||
        worker->udp_session_cap == 0U || worker->udp_binding_cap == 0U) {
        errno = EINVAL;
        return -1;
    }
    memset(state, 0, sizeof(*state));
    state->epoll_fd = -1;
    state->dns_raw4_fd = -1;
    state->dns_raw6_fd = -1;
    size_t max_sessions = worker->udp_session_cap;
    state->binding_cap = worker->udp_binding_cap;
    state->pending_budget = worker->udp_pending_budget;
    state->sessions = calloc(max_sessions, sizeof(*state->sessions));
    state->bindings = calloc(state->binding_cap, sizeof(*state->bindings));
    state->session_refs = calloc(max_sessions, sizeof(*state->session_refs));
    state->event_cap = max_sessions * 2U + 2U + BPF2SOCKS_DNS_CHANNELS_PER_WORKER * 2U;
    state->events = calloc(state->event_cap, sizeof(*state->events));
    state->session_buckets = calloc(BPF2SOCKS_SESSION_HASH_BUCKETS, sizeof(*state->session_buckets));
    if (state->sessions == NULL || state->bindings == NULL || state->session_refs == NULL ||
        state->events == NULL || state->session_buckets == NULL) {
        free(state->sessions);
        free(state->bindings);
        free(state->session_refs);
        free(state->events);
        free(state->session_buckets);
        memset(state, 0, sizeof(*state));
        return -1;
    }
    state->session_cap = max_sessions;
    for (size_t i = 0; i < max_sessions; ++i) {
        state->sessions[i].tcp_fd = -1;
        state->sessions[i].udp_fd = -1;
        state->sessions[i].free_next = state->free_sessions;
        state->free_sessions = &state->sessions[i];
        state->session_refs[i].control.kind = UDP_FD_SESSION_CONTROL;
        state->session_refs[i].control.fd = -1;
        state->session_refs[i].relay.kind = UDP_FD_SESSION_RELAY;
        state->session_refs[i].relay.fd = -1;
    }
    for (size_t i = 0; i < state->binding_cap; ++i) {
        state->bindings[i].reply_fd = -1;
        state->bindings[i].free_next = state->free_bindings;
        state->free_bindings = &state->bindings[i];
    }
    state->listener_ref.kind = UDP_FD_LISTENER4;
    state->listener_ref.fd = -1;
    state->listener6_ref.kind = UDP_FD_LISTENER6;
    state->listener6_ref.fd = -1;
    for (uint32_t i = 0U; i < BPF2SOCKS_DNS_CHANNELS_PER_WORKER; ++i) {
        state->dns_channels[i].index = i;
        state->dns_channels[i].tcp_fd = -1;
        state->dns_channels[i].udp_fd = -1;
        state->dns_channels[i].control_ref.kind = UDP_FD_DNS_CONTROL;
        state->dns_channels[i].control_ref.fd = -1;
        state->dns_channels[i].control_ref.dns_channel_index = i;
        state->dns_channels[i].relay_ref.kind = UDP_FD_DNS_RELAY;
        state->dns_channels[i].relay_ref.fd = -1;
        state->dns_channels[i].relay_ref.dns_channel_index = i;
    }
    if (bpf2socks_dns_table_init(
            &state->dns_table,
            BPF2SOCKS_DNS_CHANNELS_PER_WORKER,
            BPF2SOCKS_DNS_MAX_TRANSACTIONS_PER_WORKER) < 0) {
        free(state->session_buckets);
        free(state->events);
        free(state->session_refs);
        free(state->bindings);
        free(state->sessions);
        memset(state, 0, sizeof(*state));
        state->epoll_fd = -1;
        return -1;
    }
    return 0;
}

static void free_state(struct udp_state *state) {
    if (state == NULL) return;
    for (uint32_t i = 0U; i < BPF2SOCKS_DNS_CHANNELS_PER_WORKER; ++i) {
        close_dns_channel(state, &state->dns_channels[i], monotonic_ms());
    }
    if (state->dns_raw4_fd >= 0) {
        close(state->dns_raw4_fd);
        state->dns_raw4_fd = -1;
    }
    if (state->dns_raw6_fd >= 0) {
        close(state->dns_raw6_fd);
        state->dns_raw6_fd = -1;
    }
    if (state->epoll_fd >= 0) {
        close(state->epoll_fd);
        state->epoll_fd = -1;
    }
    for (size_t i = 0; i < state->session_cap; ++i) {
        close_session(state, &state->sessions[i]);
    }
    free(state->session_buckets);
    free(state->events);
    free(state->session_refs);
    free(state->bindings);
    free(state->sessions);
    bpf2socks_dns_table_free(&state->dns_table);
    memset(state, 0, sizeof(*state));
}

int bpf2socks_bridge_udp_worker_run(struct bpf2socks_bridge_worker *worker) {
    if (worker == NULL || worker->udp_listener_fd < 0 || worker->config == NULL) return -1;
    struct udp_state state;
    if (init_state(&state, worker) < 0) return -1;

    uint32_t batch = worker->config->udp_batch_size;
    if (batch == 0U) batch = BPF2SOCKS_DEFAULT_UDP_BATCH_SIZE;
    if (batch > 64U) batch = 64U;
    size_t payload_stride = udp_payload_stride(worker->config);
    size_t upstream_stride = payload_stride + BPF2SOCKS_MAX_SOCKS5_UDP_HEADER;

    uint8_t *client_payloads = calloc(batch, payload_stride);
    uint8_t *upstream_packets = calloc(batch, upstream_stride);
    if (client_payloads == NULL || upstream_packets == NULL) {
        free(client_payloads);
        free(upstream_packets);
        free_state(&state);
        return -1;
    }

    state.epoll_fd = epoll_create1(EPOLL_CLOEXEC);
    if (state.epoll_fd < 0) {
        free(client_payloads);
        free(upstream_packets);
        free_state(&state);
        return -1;
    }
    state.listener_ref.fd = worker->udp_listener_fd;
    if (add_udp_epoll_fd(&state, worker->udp_listener_fd, &state.listener_ref) < 0) {
        free(client_payloads);
        free(upstream_packets);
        free_state(&state);
        return -1;
    }
    if (worker->udp_listener6_fd >= 0) {
        state.listener6_ref.fd = worker->udp_listener6_fd;
        if (add_udp_epoll_fd(&state, worker->udp_listener6_fd, &state.listener6_ref) < 0) {
            free(client_payloads);
            free(upstream_packets);
            free_state(&state);
            return -1;
        }
    }

    while (!bpf2socks_stop_requested) {
        int ready = epoll_wait(state.epoll_fd, state.events, (int)state.event_cap, 1000);
        if (ready < 0 && errno == EINTR) {
            continue;
        }
        if (ready < 0) {
            break;
        }
        if (ready == 0) {
            evict_idle_bindings(&state, worker->config, worker);
            expire_sessions(&state, worker->config);
            (void)bpf2socks_dns_table_expire(
                &state.dns_table,
                monotonic_ms(),
                worker->config->dns_transaction_timeout_milliseconds,
                BPF2SOCKS_DNS_MAX_EXPIRE_PER_LOOP);
            continue;
        }
        for (int i = 0; i < ready; ++i) {
            struct udp_fd_ref *ref = state.events[i].data.ptr;
            if (ref == NULL) continue;
            uint32_t events = state.events[i].events;
            if ((events & EPOLLIN) != 0 && ref->kind == UDP_FD_LISTENER4) {
                handle_udp_client_packets(&state, worker, worker->udp_listener_fd, client_payloads);
            } else if ((events & EPOLLIN) != 0 && ref->kind == UDP_FD_LISTENER6) {
                handle_udp_client_packets(&state, worker, worker->udp_listener6_fd, client_payloads);
            } else if (ref->kind == UDP_FD_SESSION_CONTROL &&
                ref->session != NULL &&
                ref->session->used) {
                if (ref->session->stage != BPF2SOCKS_UDP_SESSION_READY) {
                    if (advance_udp_associate(&state, worker, ref->session, events) < 0) {
                        ++worker->stats.udp_send_errors;
                        drop_session(&state, ref->session);
                    }
                } else {
                    udp_session_control_event(&state, ref->session, events);
                }
            } else if (ref->kind == UDP_FD_SESSION_RELAY &&
                ref->session != NULL &&
                ref->session->used &&
                ref->session->stage == BPF2SOCKS_UDP_SESSION_READY) {
                if ((events & (EPOLLERR | EPOLLHUP)) != 0) {
                    drop_session(&state, ref->session);
                } else if ((events & EPOLLIN) != 0) {
                    handle_udp_upstream_packets(&state, worker, ref->session, upstream_packets);
                }
            } else if (ref->kind == UDP_FD_DNS_CONTROL &&
                ref->dns_channel_index < BPF2SOCKS_DNS_CHANNELS_PER_WORKER) {
                struct dns_channel *channel = &state.dns_channels[ref->dns_channel_index];
                udp_dns_control_event(&state, channel, events, monotonic_ms());
            } else if (ref->kind == UDP_FD_DNS_RELAY &&
                ref->dns_channel_index < BPF2SOCKS_DNS_CHANNELS_PER_WORKER) {
                struct dns_channel *channel = &state.dns_channels[ref->dns_channel_index];
                if (!channel->available || channel->udp_fd < 0) {
                    continue;
                }
                if ((events & (EPOLLERR | EPOLLHUP)) != 0) {
                    close_dns_channel(&state, channel, monotonic_ms());
                } else if ((events & EPOLLIN) != 0) {
                    handle_dns_upstream_packets(&state, worker, channel, upstream_packets);
                }
            }
        }
        evict_idle_bindings(&state, worker->config, worker);
        expire_sessions(&state, worker->config);
        (void)bpf2socks_dns_table_expire(
            &state.dns_table,
            monotonic_ms(),
            worker->config->dns_transaction_timeout_milliseconds,
            BPF2SOCKS_DNS_MAX_EXPIRE_PER_LOOP);
    }

    free(client_payloads);
    free(upstream_packets);
    free_state(&state);
    return 0;
}
