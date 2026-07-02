// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

#define _GNU_SOURCE

#include "bridge_internal.h"

#include <arpa/inet.h>
#include <errno.h>
#include <linux/ip.h>
#include <linux/ipv6.h>
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

#ifndef IP_ORIGDSTADDR
#define IP_ORIGDSTADDR 20
#endif

#ifndef IP_RECVORIGDSTADDR
#define IP_RECVORIGDSTADDR IP_ORIGDSTADDR
#endif

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
    struct bpf2socks_sockaddr original_dst;
    uint8_t *payload;
    size_t payload_len;
};

enum udp_fd_kind {
    UDP_FD_LISTENER4,
    UDP_FD_LISTENER6,
    UDP_FD_SESSION,
};

struct udp_fd_ref {
    enum udp_fd_kind kind;
    int fd;
    struct bpf2socks_udp_client_session *session;
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
    struct udp_fd_ref *session_refs;
    struct epoll_event *events;
    struct bpf2socks_udp_client_session **session_buckets;
    struct bpf2socks_udp_client_session *lru_head;
    struct bpf2socks_udp_client_session *lru_tail;
    struct bpf2socks_udp_reply_binding *binding_lru_head;
    struct bpf2socks_udp_reply_binding *binding_lru_tail;
    struct udp_fd_ref listener_ref;
    struct udp_fd_ref listener6_ref;
    int epoll_fd;
    size_t session_cap;
    size_t session_count;
    size_t binding_cap;
    size_t binding_cap_per_session;
    size_t binding_count;
    size_t event_cap;
};

static bool ipv4_is_loopback(uint32_t addr_net) {
    return (ntohl(addr_net) & 0xff000000U) == 0x7f000000U;
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

static int sockaddr_in_to_bpf_sockaddr(const struct sockaddr_in *addr, struct bpf2socks_sockaddr *dst) {
    if (addr == NULL || dst == NULL || addr->sin_family != AF_INET) return -1;
    memset(dst, 0, sizeof(*dst));
    dst->family = AF_INET;
    dst->port = ntohs(addr->sin_port);
    memcpy(dst->addr, &addr->sin_addr, 4);
    return 0;
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
    // Connected UDP sockets only accept packets from their original peer, not from the loopback token.
    return packet->original_dst.family == AF_INET || packet->original_dst.family == AF_INET6;
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

static void close_binding(struct bpf2socks_udp_reply_binding *binding) {
    if (binding == NULL) return;
    if (binding->reply_fd >= 0) close(binding->reply_fd);
    free(binding);
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
    close_binding(binding);
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
    struct bpf2socks_udp_client_session *session,
    struct bpf2socks_bridge_worker *worker) {
    while (session->binding_count >= state->binding_cap_per_session) {
        if (evict_binding(state, session, worker) < 0) return -1;
    }
    while (state->binding_count >= state->binding_cap) {
        if (evict_binding(state, NULL, worker) < 0) return -1;
    }
    return 0;
}

static size_t session_index(const struct udp_state *state, const struct bpf2socks_udp_client_session *session) {
    return (size_t)(session - state->sessions);
}

static int add_udp_epoll_fd(struct udp_state *state, int fd, struct udp_fd_ref *ref) {
    if (state->epoll_fd < 0 || fd < 0 || ref == NULL) return 0;
    struct epoll_event event;
    memset(&event, 0, sizeof(event));
    event.events = EPOLLIN | EPOLLERR | EPOLLHUP;
    event.data.ptr = ref;
    return epoll_ctl(state->epoll_fd, EPOLL_CTL_ADD, fd, &event);
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
    struct udp_fd_ref *ref = &state->session_refs[index];
    ref->kind = UDP_FD_SESSION;
    ref->fd = session->udp_fd;
    ref->session = session;
    return add_udp_epoll_fd(state, session->udp_fd, ref);
}

static void close_session(struct udp_state *state, struct bpf2socks_udp_client_session *session) {
    if (session == NULL || !session->used) return;
    if (session->tcp_fd >= 0) close(session->tcp_fd);
    if (session->udp_fd >= 0) close(session->udp_fd);
    while (session->bindings != NULL) {
        destroy_binding(state, session, session->bindings);
    }
    memset(session, 0, sizeof(*session));
    session->tcp_fd = -1;
    session->udp_fd = -1;
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
            session->last_seen = time(NULL);
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
    remove_udp_epoll_fd(state, session->udp_fd);
    size_t index = session_index(state, session);
    if (index < state->session_cap) {
        memset(&state->session_refs[index], 0, sizeof(state->session_refs[index]));
        state->session_refs[index].fd = -1;
    }
    unlink_session_from_hash(state, session);
    lru_remove(state, session);
    close_session(state, session);
    if (state->session_count > 0U) --state->session_count;
}

static struct bpf2socks_udp_client_session *alloc_session(struct udp_state *state, struct bpf2socks_bridge_worker *worker) {
    for (size_t i = 0; i < state->session_cap; ++i) {
        if (!state->sessions[i].used) return &state->sessions[i];
    }
    struct bpf2socks_udp_client_session *victim = state->lru_head;
    if (victim == NULL) {
        errno = ENOMEM;
        return NULL;
    }
    drop_session(state, victim);
    ++worker->stats.udp_session_evictions;
    return victim;
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
    bool needs_original_reply_socket,
    bool fullcone,
    struct bpf2socks_bridge_worker *worker) {
    if (ensure_binding_capacity(state, session, worker) < 0) return NULL;
    struct bpf2socks_udp_reply_binding *binding = calloc(1U, sizeof(*binding));
    if (binding == NULL) return NULL;
    binding->reply_fd = -1;
    binding->original_dst = *original;
    if (token_addr != NULL && token_addr_len > 0U) {
        if (token_addr_len > sizeof(binding->token_addr)) token_addr_len = sizeof(binding->token_addr);
        memcpy(binding->token_addr, token_addr, token_addr_len);
    }
    binding->last_seen = time(NULL);
    if (needs_original_reply_socket) {
        binding->reply_fd = create_udp_client_reply_socket(original, &binding->reply_raw);
        if (binding->reply_fd < 0) {
            fprintf(stderr, "failed to create UDP original reply socket: errno=%d\n", errno);
            free(binding);
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
        packet->original_from_socket || packet_needs_original_reply_socket(packet),
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
    if (bpf2socks_socks5_udp_associate_addr(
            (const struct sockaddr *)&worker->socks_addr,
            worker->socks_addr_len,
            &session->tcp_fd,
            &session->udp_fd,
            &session->relay_addr,
            &session->relay_addr_len) < 0) {
        close_session(state, session);
        return NULL;
    }
    (void)setsockopt(
        session->udp_fd,
        SOL_SOCKET,
        SO_RCVBUF,
        &worker->config->udp_recv_buffer_size,
        sizeof(worker->config->udp_recv_buffer_size));
    session->used = true;
    session->client_addr = packet->client_addr;
    session->client_addr_len = packet->client_addr_len;
    session->last_seen = time(NULL);
    if (add_session_udp_epoll_fd(state, session) < 0) {
        close_session(state, session);
        return NULL;
    }
    uint32_t bucket = session_bucket(&session->client_addr, session->client_addr_len);
    session->next = state->session_buckets[bucket];
    state->session_buckets[bucket] = session;
    lru_add_tail(state, session);
    ++state->session_count;
    ++worker->stats.udp_associate_creates;
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
    return original_to_sockaddr(&original, &packet->original_dst);
}

static int parse_udp_client_packet_cmsgs(struct msghdr *msg, struct udp_client_packet *packet) {
    bool has_pktinfo = false;
    for (struct cmsghdr *cmsg = CMSG_FIRSTHDR(msg); cmsg != NULL; cmsg = CMSG_NXTHDR(msg, cmsg)) {
        if (cmsg->cmsg_level == IPPROTO_IP && cmsg->cmsg_type == IP_PKTINFO) {
            const struct in_pktinfo *pktinfo = (const struct in_pktinfo *)CMSG_DATA(cmsg);
            packet->token_family = AF_INET;
            memcpy(packet->token_addr, &pktinfo->ipi_addr, 4);
            has_pktinfo = true;
        } else if (cmsg->cmsg_level == IPPROTO_IPV6 && cmsg->cmsg_type == IPV6_PKTINFO) {
            const struct in6_pktinfo *pktinfo = (const struct in6_pktinfo *)CMSG_DATA(cmsg);
            packet->token_family = AF_INET6;
            memcpy(packet->token_addr, &pktinfo->ipi6_addr, 16);
            has_pktinfo = true;
        } else if (cmsg->cmsg_level == IPPROTO_IP && cmsg->cmsg_type == IP_ORIGDSTADDR) {
            const struct sockaddr_in *original = (const struct sockaddr_in *)CMSG_DATA(cmsg);
            if (original->sin_family == AF_INET &&
                !ipv4_is_loopback(original->sin_addr.s_addr) &&
                sockaddr_in_to_bpf_sockaddr(original, &packet->original_dst) == 0) {
                packet->has_original_dst = true;
            }
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

    time_t now = time(NULL);
    batch->session->last_seen = now;
    for (int i = 0; i < sent; ++i) {
        batch->bindings[i]->last_seen = now;
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
                        CMSG_SPACE(sizeof(struct sockaddr_in))];
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
    memset(outgoing_sessions, 0, sizeof(outgoing_sessions));
    memset(outgoing_messages, 0, sizeof(outgoing_messages));

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

        if (parse_udp_client_packet_cmsgs(&vec[i].msg_hdr, &packet) < 0 ||
            lookup_udp_original(worker->config, &packet) < 0) {
            ++worker->stats.udp_token_misses;
            fprintf(stderr, "missing UDP original destination: errno=%d\n", errno);
            continue;
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
            binding->last_seen = time(NULL);
            binding_lru_touch(state, session, binding);
        }

        outgoing_sessions[outgoing_count] = session;
        outgoing_messages[outgoing_count] = (struct bpf2socks_udp_msg){
            .addr = packet.original_dst,
            .payload = packet.payload,
            .payload_len = packet.payload_len,
        };
        ++outgoing_count;
    }

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
                time_t now = time(NULL);
                session->last_seen = now;
                binding->last_seen = now;
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
    time_t now = time(NULL);
    uint32_t timeout = config->udp_idle_timeout_seconds;
    if (timeout == 0U) timeout = BPF2SOCKS_DEFAULT_UDP_IDLE_TIMEOUT_SECONDS;
    struct bpf2socks_udp_client_session *session = state->lru_head;
    while (session != NULL) {
        struct bpf2socks_udp_client_session *next = session->lru_next;
        if (!session->used || now - session->last_seen < (time_t)timeout) {
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
    time_t now = time(NULL);
    uint32_t timeout = config->udp_idle_timeout_seconds;
    if (timeout == 0U) timeout = BPF2SOCKS_DEFAULT_UDP_IDLE_TIMEOUT_SECONDS;
    struct bpf2socks_udp_reply_binding *binding = state->binding_lru_head;
    while (binding != NULL) {
        struct bpf2socks_udp_reply_binding *next = binding->global_lru_next;
        if (!binding->used || now - binding->last_seen < (time_t)timeout) break;
        struct bpf2socks_udp_client_session *session = binding->owner;
        destroy_binding(state, session, binding);
        ++worker->stats.udp_binding_evictions;
        binding = next;
    }
}

static int init_state(struct udp_state *state, const struct bpf2socks_runtime_config *config) {
    memset(state, 0, sizeof(*state));
    state->epoll_fd = -1;
    size_t max_sessions = config->max_udp_sessions;
    if (max_sessions == 0U) max_sessions = BPF2SOCKS_DEFAULT_MAX_UDP_SESSIONS;
    state->binding_cap = config->max_udp_bindings;
    if (state->binding_cap == 0U) state->binding_cap = BPF2SOCKS_DEFAULT_MAX_UDP_BINDINGS;
    state->binding_cap_per_session = config->max_udp_bindings_per_session;
    if (state->binding_cap_per_session == 0U) {
        state->binding_cap_per_session = BPF2SOCKS_DEFAULT_MAX_UDP_BINDINGS_PER_SESSION;
    }
    state->sessions = calloc(max_sessions, sizeof(*state->sessions));
    state->session_refs = calloc(max_sessions, sizeof(*state->session_refs));
    state->event_cap = max_sessions + 2U;
    state->events = calloc(state->event_cap, sizeof(*state->events));
    state->session_buckets = calloc(BPF2SOCKS_SESSION_HASH_BUCKETS, sizeof(*state->session_buckets));
    if (state->sessions == NULL || state->session_refs == NULL ||
        state->events == NULL || state->session_buckets == NULL) {
        free(state->sessions);
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
        state->session_refs[i].fd = -1;
    }
    state->listener_ref.kind = UDP_FD_LISTENER4;
    state->listener_ref.fd = -1;
    state->listener6_ref.kind = UDP_FD_LISTENER6;
    state->listener6_ref.fd = -1;
    return 0;
}

static void free_state(struct udp_state *state) {
    if (state == NULL) return;
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
    free(state->sessions);
    memset(state, 0, sizeof(*state));
}

int bpf2socks_bridge_udp_worker_run(struct bpf2socks_bridge_worker *worker) {
    if (worker == NULL || worker->udp_listener_fd < 0 || worker->config == NULL) return -1;
    struct udp_state state;
    if (init_state(&state, worker->config) < 0) return -1;

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
            } else if (ref->kind == UDP_FD_SESSION &&
                ref->session != NULL &&
                ref->session->used) {
                if ((events & (EPOLLERR | EPOLLHUP)) != 0) {
                    drop_session(&state, ref->session);
                } else if ((events & EPOLLIN) != 0) {
                    handle_udp_upstream_packets(&state, worker, ref->session, upstream_packets);
                }
            }
        }
        evict_idle_bindings(&state, worker->config, worker);
        expire_sessions(&state, worker->config);
    }

    free(client_payloads);
    free(upstream_packets);
    free_state(&state);
    return 0;
}
