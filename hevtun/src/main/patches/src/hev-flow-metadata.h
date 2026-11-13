// Copyright 2026, Asterisk4Magisk contributors
// SPDX-License-Identifier: GPL-3.0

#ifndef HEV_FLOW_METADATA_H
#define HEV_FLOW_METADATA_H

#include "flow-metadata.h"
#include "hev-config.h"
#include "hev-utils.h"
#include <hev-task-mutex.h>
#include "hev-socks5-session-udp.h"
#include <hev-task-io.h>
#include <hev-socks5-misc.h>

static inline int hev_flow_enabled(void) {
    HevConfigServer *s = hev_config_get_socks5_server();
    return s->user && !strcmp(s->user, FLOW_METADATA_USERNAME) && s->pass && strlen(s->pass) == 32;
}

static inline int hev_flow_sockaddr(const HevSocks5Addr *a, struct sockaddr_storage *out) {
    memset(out, 0, sizeof(*out));
    if (a->atype == HEV_SOCKS5_ADDR_TYPE_IPV4) {
        struct sockaddr_in *s = (void *)out;
        s->sin_family = AF_INET;
        memcpy(&s->sin_addr, a->ipv4.addr, 4);
        s->sin_port = a->ipv4.port;
    } else if (a->atype == HEV_SOCKS5_ADDR_TYPE_IPV6) {
        struct sockaddr_in6 *s = (void *)out;
        s->sin6_family = AF_INET6;
        memcpy(&s->sin6_addr, a->ipv6.addr, 16);
        s->sin6_port = a->ipv6.port;
    } else
        return -1;
    return 0;
}

static inline size_t hev_flow_header(
    uint8_t *out, const ip_addr_t *ip, uint16_t port, const HevSocks5Addr *dst, int udp) {
    HevSocks5Addr src;
    struct sockaddr_storage s, d;
    hev_socks5_addr_from_lwip(&src, ip, port);
    if (hev_flow_sockaddr(&src, &s) || hev_flow_sockaddr(dst, &d)) return 0;
    return flow_metadata_encode(out, FLOW_METADATA_MAX_HEADER, (void *)&s, (void *)&d, udp,
        FLOW_METADATA_UNKNOWN_UID, hev_config_get_socks5_server()->pass);
}

void hev_flow_run(HevSocks5Session *self);
int hev_flow_sendmmsg(
    HevSocks5SessionUDP *self, HevSocks5UDPMsg *msgv, unsigned int count, HevTaskIOYielder yielder);
int hev_flow_recvmmsg(HevSocks5SessionUDP *self, HevSocks5UDPMsg *msgv, unsigned int count);

#endif
