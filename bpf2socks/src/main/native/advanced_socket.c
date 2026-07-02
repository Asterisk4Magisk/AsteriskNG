// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

#define _GNU_SOURCE

#include "bridge_internal.h"

#include <arpa/inet.h>
#include <asm/unistd.h>
#include <errno.h>
#include <linux/io_uring.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <unistd.h>

#ifndef TCP_FASTOPEN
#define TCP_FASTOPEN 23
#endif
#ifndef TCP_FASTOPEN_CONNECT
#define TCP_FASTOPEN_CONNECT 30
#endif
#ifndef TCP_QUICKACK
#define TCP_QUICKACK 12
#endif
#ifndef TCP_CORK
#define TCP_CORK 3
#endif
#ifndef UDP_SEGMENT
#define UDP_SEGMENT 103
#endif
#ifndef UDP_GRO
#define UDP_GRO 104
#endif
#ifndef SO_ZEROCOPY
#define SO_ZEROCOPY 60
#endif
#ifndef MSG_ZEROCOPY
#define MSG_ZEROCOPY 0x4000000
#endif

static void set_probe_message(char *message, size_t message_size, const char *feature, int error) {
    if (message == NULL || message_size == 0U) return;
    snprintf(message, message_size, "%s is unavailable: errno=%d", feature, error);
}

static int probe_setsockopt(
    const char *feature,
    int family,
    int type,
    int protocol,
    int level,
    int optname,
    int value,
    char *message,
    size_t message_size) {
    int fd = socket(family, type | SOCK_CLOEXEC, protocol);
    if (fd < 0) {
        set_probe_message(message, message_size, feature, errno);
        return -1;
    }
    int rc = setsockopt(fd, level, optname, &value, sizeof(value));
    int saved = errno;
    close(fd);
    if (rc != 0) {
        errno = saved;
        set_probe_message(message, message_size, feature, errno);
        return -1;
    }
    return 0;
}

static int probe_udp_segment_sendmsg(char *message, size_t message_size) {
    int fd = socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    if (fd < 0) {
        set_probe_message(message, message_size, "UDP_SEGMENT cmsg", errno);
        return -1;
    }

    struct sockaddr_in dst;
    memset(&dst, 0, sizeof(dst));
    dst.sin_family = AF_INET;
    dst.sin_port = htons(9);
    (void)inet_pton(AF_INET, "127.0.0.1", &dst.sin_addr);

    uint8_t payload[2400];
    memset(payload, 0x51, sizeof(payload));
    struct iovec iov = {
        .iov_base = payload,
        .iov_len = sizeof(payload),
    };

    char control[CMSG_SPACE(sizeof(uint16_t))];
    memset(control, 0, sizeof(control));
    struct msghdr msg;
    memset(&msg, 0, sizeof(msg));
    msg.msg_name = &dst;
    msg.msg_namelen = sizeof(dst);
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1U;
    msg.msg_control = control;
    msg.msg_controllen = sizeof(control);

    struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);
    cmsg->cmsg_level = IPPROTO_UDP;
    cmsg->cmsg_type = UDP_SEGMENT;
    cmsg->cmsg_len = CMSG_LEN(sizeof(uint16_t));
    uint16_t segment = 1200U;
    memcpy(CMSG_DATA(cmsg), &segment, sizeof(segment));

    ssize_t sent = sendmsg(fd, &msg, 0);
    int saved = errno;
    close(fd);
    if (sent != (ssize_t)sizeof(payload)) {
        errno = saved;
        set_probe_message(message, message_size, "UDP_SEGMENT cmsg", errno);
        return -1;
    }
    return 0;
}

static int probe_msg_zerocopy_udp(char *message, size_t message_size) {
    int fd = socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    if (fd < 0) {
        set_probe_message(message, message_size, "MSG_ZEROCOPY UDP", errno);
        return -1;
    }
    int one = 1;
    if (setsockopt(fd, SOL_SOCKET, SO_ZEROCOPY, &one, sizeof(one)) != 0) {
        int saved = errno;
        close(fd);
        errno = saved;
        set_probe_message(message, message_size, "SO_ZEROCOPY UDP", errno);
        return -1;
    }

    struct sockaddr_in dst;
    memset(&dst, 0, sizeof(dst));
    dst.sin_family = AF_INET;
    dst.sin_port = htons(9);
    (void)inet_pton(AF_INET, "127.0.0.1", &dst.sin_addr);

    uint8_t payload[1200];
    memset(payload, 0x52, sizeof(payload));
    ssize_t sent = sendto(fd, payload, sizeof(payload), MSG_ZEROCOPY, (const struct sockaddr *)&dst, sizeof(dst));
    int saved = errno;
    bpf2socks_drain_zerocopy_completions(fd);
    close(fd);
    if (sent != (ssize_t)sizeof(payload)) {
        errno = saved;
        set_probe_message(message, message_size, "MSG_ZEROCOPY UDP", errno);
        return -1;
    }
    return 0;
}

static int probe_io_uring_setup(char *message, size_t message_size) {
#ifdef __NR_io_uring_setup
    struct io_uring_params params;
    memset(&params, 0, sizeof(params));
    int fd = (int)syscall(__NR_io_uring_setup, 2U, &params);
    if (fd < 0) {
        set_probe_message(message, message_size, "__NR_io_uring_setup", errno);
        return -1;
    }
    close(fd);
    return 0;
#else
    errno = ENOSYS;
    set_probe_message(message, message_size, "__NR_io_uring_setup", errno);
    return -1;
#endif
}

int bpf2socks_advanced_socket_probe(char *message, size_t message_size) {
    if (message != NULL && message_size > 0U) message[0] = '\0';
    if (probe_setsockopt(
            "TCP_FASTOPEN_CONNECT",
            AF_INET,
            SOCK_STREAM,
            0,
            IPPROTO_TCP,
            TCP_FASTOPEN_CONNECT,
            1,
            message,
            message_size) < 0) {
        return -1;
    }
    if (probe_setsockopt("TCP_FASTOPEN", AF_INET, SOCK_STREAM, 0, IPPROTO_TCP, TCP_FASTOPEN, 5, message, message_size) < 0) {
        return -1;
    }
    if (probe_setsockopt("TCP_QUICKACK", AF_INET, SOCK_STREAM, 0, IPPROTO_TCP, TCP_QUICKACK, 1, message, message_size) < 0) {
        return -1;
    }
    if (probe_setsockopt("TCP_CORK", AF_INET, SOCK_STREAM, 0, IPPROTO_TCP, TCP_CORK, 1, message, message_size) < 0) {
        return -1;
    }
    if (probe_setsockopt("UDP_SEGMENT", AF_INET, SOCK_DGRAM, 0, IPPROTO_UDP, UDP_SEGMENT, 1200, message, message_size) < 0) {
        return -1;
    }
    if (probe_udp_segment_sendmsg(message, message_size) < 0) return -1;
    if (probe_setsockopt("UDP_GRO", AF_INET, SOCK_DGRAM, 0, IPPROTO_UDP, UDP_GRO, 1, message, message_size) < 0) {
        return -1;
    }
    if (probe_setsockopt("SO_ZEROCOPY TCP", AF_INET, SOCK_STREAM, 0, SOL_SOCKET, SO_ZEROCOPY, 1, message, message_size) < 0) {
        return -1;
    }
    if (probe_setsockopt("SO_ZEROCOPY UDP", AF_INET, SOCK_DGRAM, 0, SOL_SOCKET, SO_ZEROCOPY, 1, message, message_size) < 0) {
        return -1;
    }
    if (probe_msg_zerocopy_udp(message, message_size) < 0) return -1;
    if (probe_io_uring_setup(message, message_size) < 0) return -1;
    if (message != NULL && message_size > 0U) snprintf(message, message_size, "ok");
    return 0;
}

void bpf2socks_bridge_tune_tcp_advanced(int fd, bool upstream) {
    int one = 1;
    (void)setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
    (void)setsockopt(fd, IPPROTO_TCP, TCP_QUICKACK, &one, sizeof(one));
    if (upstream) {
        (void)setsockopt(fd, IPPROTO_TCP, TCP_FASTOPEN_CONNECT, &one, sizeof(one));
    }
}

void bpf2socks_bridge_tune_udp_advanced(int fd) {
    int one = 1;
    (void)setsockopt(fd, SOL_SOCKET, SO_ZEROCOPY, &one, sizeof(one));
}

void bpf2socks_bridge_enable_tcp_fastopen_listener(int fd) {
    int backlog = 4096;
    (void)setsockopt(fd, IPPROTO_TCP, TCP_FASTOPEN, &backlog, sizeof(backlog));
}

void bpf2socks_drain_zerocopy_completions(int fd) {
    char control[256];
    struct msghdr msg;
    memset(&msg, 0, sizeof(msg));
    msg.msg_control = control;
    msg.msg_controllen = sizeof(control);
    while (recvmsg(fd, &msg, MSG_ERRQUEUE | MSG_DONTWAIT) >= 0) {
        memset(&msg, 0, sizeof(msg));
        msg.msg_control = control;
        msg.msg_controllen = sizeof(control);
    }
}
