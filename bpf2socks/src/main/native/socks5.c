// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

#define _GNU_SOURCE

#include "bridge_internal.h"

#include <arpa/inet.h>
#include <errno.h>
#include <netdb.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/uio.h>
#include <unistd.h>

#define BPF2SOCKS_SOCKS5_UDP_HEADER_CAP 22U

static int write_full(int fd, const void *buf, size_t len) {
    const uint8_t *ptr = buf;
    while (len > 0U) {
        ssize_t res = send(fd, ptr, len, MSG_NOSIGNAL);
        if (res < 0 && errno == EINTR) continue;
        if (res <= 0) return -1;
        ptr += (size_t)res;
        len -= (size_t)res;
    }
    return 0;
}

static int read_full(int fd, void *buf, size_t len) {
    uint8_t *ptr = buf;
    while (len > 0U) {
        ssize_t res = recv(fd, ptr, len, MSG_WAITALL);
        if (res < 0 && errno == EINTR) continue;
        if (res <= 0) return -1;
        ptr += (size_t)res;
        len -= (size_t)res;
    }
    return 0;
}

static int connect_tcp_host(const char *host, uint16_t port) {
    struct sockaddr_storage addr;
    socklen_t addr_len = 0;
    if (bpf2socks_resolve_tcp_addr(host, port, &addr, &addr_len) < 0) return -1;
    int fd = socket(addr.ss_family, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;
    bpf2socks_bridge_tune_tcp_advanced(fd, true);
    if (connect(fd, (const struct sockaddr *)&addr, addr_len) == 0) return fd;
    close(fd);
    return -1;
}

int bpf2socks_resolve_tcp_addr(const char *host, uint16_t port, struct sockaddr_storage *addr, socklen_t *addr_len) {
    if (host == NULL || addr == NULL || addr_len == NULL) {
        errno = EINVAL;
        return -1;
    }
    char port_buf[16];
    struct addrinfo hints;
    struct addrinfo *result = NULL;
    snprintf(port_buf, sizeof(port_buf), "%u", port);
    memset(&hints, 0, sizeof(hints));
    hints.ai_socktype = SOCK_STREAM;
    hints.ai_family = AF_UNSPEC;
    if (getaddrinfo(host, port_buf, &hints, &result) != 0) return -1;

    int rc = -1;
    for (struct addrinfo *it = result; it != NULL; it = it->ai_next) {
        if ((size_t)it->ai_addrlen > sizeof(*addr)) continue;
        memset(addr, 0, sizeof(*addr));
        memcpy(addr, it->ai_addr, it->ai_addrlen);
        *addr_len = (socklen_t)it->ai_addrlen;
        rc = 0;
        break;
    }
    freeaddrinfo(result);
    if (rc < 0) errno = ENOENT;
    return rc;
}

static int connect_tcp_addr(const struct sockaddr *addr, socklen_t addr_len) {
    if (addr == NULL || addr_len == 0) {
        errno = EINVAL;
        return -1;
    }
    int fd = socket(addr->sa_family, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;
    bpf2socks_bridge_tune_tcp_advanced(fd, true);
    if (connect(fd, addr, addr_len) == 0) return fd;
    int saved = errno;
    close(fd);
    errno = saved;
    return -1;
}

static int socks5_handshake(int fd) {
    uint8_t hello[] = {0x05, 0x01, 0x00};
    uint8_t hello_reply[2];
    if (write_full(fd, hello, sizeof(hello)) < 0 || read_full(fd, hello_reply, sizeof(hello_reply)) < 0) return -1;
    return hello_reply[0] == 0x05 && hello_reply[1] == 0x00 ? 0 : -1;
}

static size_t socks5_write_addr(uint8_t *out, const struct bpf2socks_sockaddr *addr) {
    size_t len = 0;
    if (addr->family == AF_INET) {
        out[len++] = 0x01;
        memcpy(out + len, addr->addr, 4);
        len += 4U;
    } else if (addr->family == AF_INET6) {
        out[len++] = 0x04;
        memcpy(out + len, addr->addr, 16);
        len += 16U;
    } else {
        return 0;
    }
    out[len++] = (uint8_t)(addr->port >> 8);
    out[len++] = (uint8_t)(addr->port & 0xffU);
    return len;
}

static int socks5_read_bound_addr(int fd, struct sockaddr_storage *bound, socklen_t *bound_len) {
    uint8_t rep[4 + 255 + 2];
    if (read_full(fd, rep, 4) < 0 || rep[0] != 0x05 || rep[1] != 0x00) return -1;
    memset(bound, 0, sizeof(*bound));

    if (rep[3] == 0x01) {
        if (read_full(fd, rep, 4 + 2) < 0) return -1;
        struct sockaddr_in *addr = (struct sockaddr_in *)bound;
        addr->sin_family = AF_INET;
        memcpy(&addr->sin_addr, rep, 4);
        memcpy(&addr->sin_port, rep + 4, 2);
        *bound_len = sizeof(*addr);
        return 0;
    }
    if (rep[3] == 0x04) {
        if (read_full(fd, rep, 16 + 2) < 0) return -1;
        struct sockaddr_in6 *addr = (struct sockaddr_in6 *)bound;
        addr->sin6_family = AF_INET6;
        memcpy(&addr->sin6_addr, rep, 16);
        memcpy(&addr->sin6_port, rep + 16, 2);
        *bound_len = sizeof(*addr);
        return 0;
    }
    if (rep[3] == 0x03) {
        uint8_t n = 0;
        if (read_full(fd, &n, 1) < 0 || n == 0U || read_full(fd, rep, (size_t)n + 2U) < 0) return -1;
        char host[256];
        memcpy(host, rep, n);
        host[n] = '\0';
        char port_buf[8];
        uint16_t reply_port = ((uint16_t)rep[n] << 8) | rep[n + 1U];
        snprintf(port_buf, sizeof(port_buf), "%u", reply_port);
        struct addrinfo hints;
        struct addrinfo *result = NULL;
        memset(&hints, 0, sizeof(hints));
        hints.ai_socktype = SOCK_DGRAM;
        hints.ai_family = AF_UNSPEC;
        if (getaddrinfo(host, port_buf, &hints, &result) != 0 || result == NULL) return -1;
        if ((size_t)result->ai_addrlen > sizeof(*bound)) {
            freeaddrinfo(result);
            return -1;
        }
        memcpy(bound, result->ai_addr, result->ai_addrlen);
        *bound_len = (socklen_t)result->ai_addrlen;
        freeaddrinfo(result);
        return 0;
    }
    return -1;
}

static int replace_unspecified_relay_addr(
    const struct sockaddr *socks_addr,
    socklen_t socks_addr_len,
    struct sockaddr_storage *relay_addr,
    socklen_t *relay_addr_len) {
    (void)socks_addr_len;
    if (socks_addr == NULL || relay_addr == NULL || relay_addr_len == NULL) return -1;
    if (relay_addr->ss_family == AF_INET) {
        struct sockaddr_in *relay4 = (struct sockaddr_in *)relay_addr;
        if (relay4->sin_addr.s_addr != htonl(INADDR_ANY)) return 0;
        if (socks_addr->sa_family != AF_INET) return 0;
        const struct sockaddr_in *socks4 = (const struct sockaddr_in *)socks_addr;
        relay4->sin_addr = socks4->sin_addr;
        *relay_addr_len = sizeof(*relay4);
        return 0;
    }
    if (relay_addr->ss_family == AF_INET6) {
        struct sockaddr_in6 *relay6 = (struct sockaddr_in6 *)relay_addr;
        if (!IN6_IS_ADDR_UNSPECIFIED(&relay6->sin6_addr)) return 0;
        if (socks_addr->sa_family != AF_INET6) return 0;
        const struct sockaddr_in6 *socks6 = (const struct sockaddr_in6 *)socks_addr;
        relay6->sin6_addr = socks6->sin6_addr;
        *relay_addr_len = sizeof(*relay6);
    }
    return 0;
}

int bpf2socks_socks5_connect(const char *host, uint16_t port, const struct bpf2socks_sockaddr *dst) {
    if (host == NULL || dst == NULL) return -1;
    int fd = connect_tcp_host(host, port);
    if (fd < 0) return -1;

    if (socks5_handshake(fd) < 0) {
        close(fd);
        return -1;
    }

    uint8_t req[4 + 16 + 2];
    size_t len = 0;
    req[len++] = 0x05;
    req[len++] = 0x01;
    req[len++] = 0x00;
    size_t addr_len = socks5_write_addr(req + len, dst);
    if (addr_len == 0U) {
        close(fd);
        return -1;
    }
    len += addr_len;
    if (write_full(fd, req, len) < 0) {
        close(fd);
        return -1;
    }

    struct sockaddr_storage ignored;
    socklen_t ignored_len = 0;
    if (socks5_read_bound_addr(fd, &ignored, &ignored_len) < 0) {
        close(fd);
        return -1;
    }
    return fd;
}

int bpf2socks_socks5_udp_associate(
    const char *host,
    uint16_t port,
    int *tcp_fd,
    int *udp_fd,
    struct sockaddr_storage *relay_addr,
    socklen_t *relay_addr_len) {
    if (host == NULL || tcp_fd == NULL || udp_fd == NULL || relay_addr == NULL || relay_addr_len == NULL) return -1;
    struct sockaddr_storage socks_addr;
    socklen_t socks_addr_len = 0;
    if (bpf2socks_resolve_tcp_addr(host, port, &socks_addr, &socks_addr_len) < 0) return -1;
    return bpf2socks_socks5_udp_associate_addr(
        (const struct sockaddr *)&socks_addr,
        socks_addr_len,
        tcp_fd,
        udp_fd,
        relay_addr,
        relay_addr_len);
}

int bpf2socks_socks5_udp_associate_addr(
    const struct sockaddr *socks_addr,
    socklen_t socks_addr_len,
    int *tcp_fd,
    int *udp_fd,
    struct sockaddr_storage *relay_addr,
    socklen_t *relay_addr_len) {
    if (socks_addr == NULL || tcp_fd == NULL || udp_fd == NULL || relay_addr == NULL || relay_addr_len == NULL) {
        errno = EINVAL;
        return -1;
    }
    *tcp_fd = -1;
    *udp_fd = -1;
    int fd = connect_tcp_addr(socks_addr, socks_addr_len);
    if (fd < 0) return -1;
    if (socks5_handshake(fd) < 0) {
        close(fd);
        return -1;
    }

    struct bpf2socks_sockaddr any_addr;
    memset(&any_addr, 0, sizeof(any_addr));
    any_addr.family = AF_INET;
    any_addr.port = 0;
    uint8_t req[4 + 16 + 2];
    size_t len = 0;
    req[len++] = 0x05;
    req[len++] = 0x03;
    req[len++] = 0x00;
    size_t addr_len = socks5_write_addr(req + len, &any_addr);
    if (addr_len == 0U) {
        close(fd);
        return -1;
    }
    len += addr_len;
    if (write_full(fd, req, len) < 0 ||
        socks5_read_bound_addr(fd, relay_addr, relay_addr_len) < 0 ||
        replace_unspecified_relay_addr(socks_addr, socks_addr_len, relay_addr, relay_addr_len) < 0) {
        close(fd);
        return -1;
    }

    int dgram = socket(relay_addr->ss_family, SOCK_DGRAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
    if (dgram < 0) {
        close(fd);
        return -1;
    }
    bpf2socks_bridge_tune_udp_advanced(dgram);
    *tcp_fd = fd;
    *udp_fd = dgram;
    return 0;
}

int bpf2socks_socks5_udp_send(
    int udp_fd,
    const struct sockaddr *relay_addr,
    socklen_t relay_addr_len,
    const struct bpf2socks_sockaddr *dst,
    const uint8_t *payload,
    size_t payload_len) {
    if (dst == NULL) return -1;
    struct bpf2socks_udp_msg message = {
        .addr = *dst,
        .payload = (uint8_t *)payload,
        .payload_len = payload_len,
    };
    return bpf2socks_socks5_udp_sendmmsg(udp_fd, relay_addr, relay_addr_len, &message, 1U) == 1 ? 0 : -1;
}

ssize_t bpf2socks_socks5_udp_recv(
    int udp_fd,
    struct bpf2socks_sockaddr *src,
    uint8_t *payload,
    size_t payload_cap) {
    if (udp_fd < 0 || src == NULL || payload == NULL || payload_cap == 0U) return -1;
    uint8_t packet[BPF2SOCKS_SOCKS5_UDP_HEADER_CAP + payload_cap];
    struct bpf2socks_udp_msg message;
    memset(&message, 0, sizeof(message));
    int count = bpf2socks_socks5_udp_recvmmsg(udp_fd, &message, 1U, packet, sizeof(packet));
    if (count <= 0) return count;
    if (message.payload_len > payload_cap) {
        errno = EMSGSIZE;
        return -1;
    }
    *src = message.addr;
    memcpy(payload, message.payload, message.payload_len);
    return (ssize_t)message.payload_len;
}

int bpf2socks_socks5_udp_sendmmsg(
    int udp_fd,
    const struct sockaddr *relay_addr,
    socklen_t relay_addr_len,
    const struct bpf2socks_udp_msg *messages,
    unsigned int count) {
    if (udp_fd < 0 || relay_addr == NULL || messages == NULL || count == 0U) return -1;

    struct mmsghdr vec[count];
    struct iovec iov[count * 2U];
    uint8_t headers[count][BPF2SOCKS_SOCKS5_UDP_HEADER_CAP];
    memset(vec, 0, sizeof(vec));
    for (unsigned int i = 0; i < count; ++i) {
        if (messages[i].payload == NULL) {
            errno = EINVAL;
            return -1;
        }
        size_t header_len = 0;
        headers[i][header_len++] = 0x00;
        headers[i][header_len++] = 0x00;
        headers[i][header_len++] = 0x00;
        size_t addr_len = socks5_write_addr(headers[i] + header_len, &messages[i].addr);
        if (addr_len == 0U || header_len + addr_len > sizeof(headers[i])) {
            errno = EAFNOSUPPORT;
            return -1;
        }
        header_len += addr_len;

        iov[i * 2U].iov_base = headers[i];
        iov[i * 2U].iov_len = header_len;
        iov[i * 2U + 1U].iov_base = messages[i].payload;
        iov[i * 2U + 1U].iov_len = messages[i].payload_len;

        vec[i].msg_hdr.msg_name = (void *)relay_addr;
        vec[i].msg_hdr.msg_namelen = relay_addr_len;
        vec[i].msg_hdr.msg_iov = &iov[i * 2U];
        vec[i].msg_hdr.msg_iovlen = 2U;
    }

    int sent = sendmmsg(udp_fd, vec, count, MSG_ZEROCOPY);
    bpf2socks_drain_zerocopy_completions(udp_fd);
    return sent;
}

int bpf2socks_socks5_udp_recvmmsg(
    int udp_fd,
    struct bpf2socks_udp_msg *messages,
    unsigned int count,
    uint8_t *packet_buffer,
    size_t packet_stride) {
    if (udp_fd < 0 || messages == NULL || count == 0U || packet_buffer == NULL ||
        packet_stride <= BPF2SOCKS_SOCKS5_UDP_HEADER_CAP) {
        errno = EINVAL;
        return -1;
    }

    struct mmsghdr vec[count];
    struct iovec iov[count];
    memset(vec, 0, sizeof(vec));
    for (unsigned int i = 0; i < count; ++i) {
        iov[i].iov_base = packet_buffer + packet_stride * i;
        iov[i].iov_len = packet_stride;
        vec[i].msg_hdr.msg_iov = &iov[i];
        vec[i].msg_hdr.msg_iovlen = 1U;
    }

    int received = recvmmsg(udp_fd, vec, count, MSG_DONTWAIT, NULL);
    if (received <= 0) return received;

    for (int i = 0; i < received; ++i) {
        uint8_t *packet = packet_buffer + packet_stride * (size_t)i;
        size_t len = vec[i].msg_len;
        if ((vec[i].msg_hdr.msg_flags & MSG_TRUNC) != 0 || len > packet_stride) {
            errno = EMSGSIZE;
            return -1;
        }
        if (len < 4U || packet[0] != 0x00 || packet[1] != 0x00 || packet[2] != 0x00) {
            errno = EPROTO;
            return -1;
        }
        size_t pos = 3U;
        memset(&messages[i], 0, sizeof(messages[i]));
        if (packet[pos] == 0x01) {
            if (len < pos + 1U + 4U + 2U) {
                errno = EPROTO;
                return -1;
            }
            messages[i].addr.family = AF_INET;
            ++pos;
            memcpy(messages[i].addr.addr, packet + pos, 4);
            pos += 4U;
        } else if (packet[pos] == 0x04) {
            if (len < pos + 1U + 16U + 2U) {
                errno = EPROTO;
                return -1;
            }
            messages[i].addr.family = AF_INET6;
            ++pos;
            memcpy(messages[i].addr.addr, packet + pos, 16);
            pos += 16U;
        } else {
            errno = EAFNOSUPPORT;
            return -1;
        }
        messages[i].addr.port = ((uint16_t)packet[pos] << 8) | packet[pos + 1U];
        pos += 2U;
        messages[i].payload = packet + pos;
        messages[i].payload_len = len - pos;
    }
    return received;
}
