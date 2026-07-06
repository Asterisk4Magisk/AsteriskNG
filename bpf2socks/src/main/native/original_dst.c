// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

#include "bpf2socks.h"

#include <errno.h>
#include <netinet/in.h>
#include <stdbool.h>
#include <string.h>

static bool ipv4_is_loopback(uint32_t addr_net) {
    return (ntohl(addr_net) & 0xff000000U) == 0x7f000000U;
}

static bool ipv6_matches_prefix(const uint8_t addr[16], const uint8_t prefix[16], uint32_t prefix_bits) {
    if (prefix_bits > 128U) return false;
    if (prefix_bits == 0U) return true;

    uint32_t full_bytes = prefix_bits / 8U;
    uint32_t remaining_bits = prefix_bits % 8U;
    if (full_bytes > 0U && memcmp(addr, prefix, full_bytes) != 0) return false;
    if (remaining_bits == 0U) return true;

    uint8_t mask = (uint8_t)(0xffU << (8U - remaining_bits));
    return (addr[full_bytes] & mask) == (prefix[full_bytes] & mask);
}

static bool ipv6_is_token_address(
    const uint8_t addr[16],
    const struct bpf2socks_runtime_config *config) {
    if (config == NULL || config->token_ipv6_prefix_bits == 0U) return false;
    return ipv6_matches_prefix(addr, config->token_ipv6_prefix, config->token_ipv6_prefix_bits);
}

static int sockaddr_in_to_original(
    const struct sockaddr_in *addr,
    uint8_t protocol,
    struct bpf2socks_original_dst *original) {
    if (addr == NULL || original == NULL || addr->sin_family != AF_INET) {
        errno = EINVAL;
        return -1;
    }
    if (ipv4_is_loopback(addr->sin_addr.s_addr)) {
        errno = EADDRNOTAVAIL;
        return -1;
    }

    memset(original, 0, sizeof(*original));
    original->family = AF_INET;
    original->protocol = protocol;
    original->port = ntohs(addr->sin_port);
    memcpy(original->addr, &addr->sin_addr, 4U);
    return 0;
}

static int sockaddr_in6_to_original(
    const struct sockaddr_in6 *addr,
    const struct bpf2socks_runtime_config *config,
    uint8_t protocol,
    struct bpf2socks_original_dst *original) {
    if (addr == NULL || original == NULL || addr->sin6_family != AF_INET6) {
        errno = EINVAL;
        return -1;
    }
    const uint8_t *addr_bytes = addr->sin6_addr.s6_addr;
    if (IN6_IS_ADDR_UNSPECIFIED(&addr->sin6_addr) ||
        IN6_IS_ADDR_LOOPBACK(&addr->sin6_addr) ||
        ipv6_is_token_address(addr_bytes, config)) {
        errno = EADDRNOTAVAIL;
        return -1;
    }

    memset(original, 0, sizeof(*original));
    original->family = AF_INET6;
    original->protocol = protocol;
    original->port = ntohs(addr->sin6_port);
    memcpy(original->addr, addr_bytes, 16U);
    return 0;
}

int bpf2socks_original_from_sockaddr_storage(
    const struct sockaddr_storage *addr,
    const struct bpf2socks_runtime_config *config,
    uint8_t protocol,
    struct bpf2socks_original_dst *original) {
    if (addr == NULL || original == NULL) {
        errno = EINVAL;
        return -1;
    }
    if (addr->ss_family == AF_INET) {
        return sockaddr_in_to_original((const struct sockaddr_in *)addr, protocol, original);
    }
    if (addr->ss_family == AF_INET6) {
        return sockaddr_in6_to_original((const struct sockaddr_in6 *)addr, config, protocol, original);
    }
    errno = EAFNOSUPPORT;
    return -1;
}
