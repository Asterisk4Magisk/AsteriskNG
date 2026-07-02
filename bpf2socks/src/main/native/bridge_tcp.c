// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

#define _GNU_SOURCE

#include "bridge_internal.h"

#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/epoll.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>

#ifndef SO_ORIGINAL_DST
#define SO_ORIGINAL_DST 80
#endif

#ifndef EPOLLRDHUP
#define EPOLLRDHUP 0x2000
#endif

#define BPF2SOCKS_TCP_EVENTS 256
#define BPF2SOCKS_TCP_HANDSHAKE_CAP 32U
#define BPF2SOCKS_TCP_RESPONSE_CAP 262U
#define BPF2SOCKS_TCP_SPLICE_CHUNK 65536U
#define BPF2SOCKS_TCP_RELAY_EVENTS (EPOLLERR | EPOLLHUP | EPOLLRDHUP)

enum tcp_stage {
    TCP_STAGE_CONNECTING,
    TCP_STAGE_HELLO_WRITE,
    TCP_STAGE_HELLO_READ,
    TCP_STAGE_REQUEST_WRITE,
    TCP_STAGE_RESPONSE_READ,
    TCP_STAGE_RELAY,
};

enum tcp_side {
    TCP_SIDE_LISTENER,
    TCP_SIDE_CLIENT,
    TCP_SIDE_UPSTREAM,
};

struct tcp_connection;

struct tcp_fd_ref {
    struct tcp_connection *connection;
    enum tcp_side side;
    int fd;
    uint32_t events;
};

struct tcp_splice_pipe {
    int read_fd;
    int write_fd;
    size_t queued;
    bool input_eof;
    bool output_shutdown;
};

struct tcp_connection {
    int client_fd;
    int upstream_fd;
    enum tcp_stage stage;
    bool closing;
    bool request_pipelined;
    struct bpf2socks_bridge_worker *worker;
    struct tcp_fd_ref client_ref;
    struct tcp_fd_ref upstream_ref;
    struct tcp_splice_pipe client_to_upstream;
    struct tcp_splice_pipe upstream_to_client;
    struct bpf2socks_sockaddr target;
    uint8_t handshake[BPF2SOCKS_TCP_HANDSHAKE_CAP];
    size_t handshake_length;
    size_t handshake_offset;
    uint8_t response[BPF2SOCKS_TCP_RESPONSE_CAP];
    size_t response_length;
    size_t response_expected;
    struct tcp_connection *next;
};

struct tcp_worker_state {
    struct bpf2socks_bridge_worker *worker;
    struct tcp_fd_ref listener_ref;
    struct tcp_fd_ref listener6_ref;
    struct tcp_connection *active;
    struct tcp_connection *closed;
};

static void close_fd_if_open(int *fd) {
    if (fd != NULL && *fd >= 0) {
        close(*fd);
        *fd = -1;
    }
}

static void set_probe_message(char *message, size_t message_size, const char *stage, int error) {
    if (message == NULL || message_size == 0U) return;
    snprintf(message, message_size, "splice %s is unavailable: errno=%d", stage, error);
}

static void set_socket_timeout_1s(int fd) {
    struct timeval timeout;
    timeout.tv_sec = 1;
    timeout.tv_usec = 0;
    (void)setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
    (void)setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));
}

int bpf2socks_splice_probe(char *message, size_t message_size) {
    int listener_fd = -1;
    int client_fd = -1;
    int accepted_fd = -1;
    int pipe_fds[2] = {-1, -1};
    int result = -1;

    if (message != NULL && message_size > 0U) message[0] = '\0';

    listener_fd = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (listener_fd < 0) {
        set_probe_message(message, message_size, "listener socket", errno);
        goto done;
    }
    int one = 1;
    (void)setsockopt(listener_fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    addr.sin_port = 0;
    if (bind(listener_fd, (struct sockaddr *)&addr, sizeof(addr)) != 0 ||
        listen(listener_fd, 1) != 0) {
        set_probe_message(message, message_size, "loopback listener", errno);
        goto done;
    }
    socklen_t addr_len = sizeof(addr);
    if (getsockname(listener_fd, (struct sockaddr *)&addr, &addr_len) != 0) {
        set_probe_message(message, message_size, "listener address", errno);
        goto done;
    }

    client_fd = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (client_fd < 0) {
        set_probe_message(message, message_size, "client socket", errno);
        goto done;
    }
    set_socket_timeout_1s(client_fd);
    if (connect(client_fd, (struct sockaddr *)&addr, addr_len) != 0) {
        set_probe_message(message, message_size, "loopback connect", errno);
        goto done;
    }
    accepted_fd = accept4(listener_fd, NULL, NULL, SOCK_CLOEXEC);
    if (accepted_fd < 0) {
        set_probe_message(message, message_size, "accept4", errno);
        goto done;
    }
    set_socket_timeout_1s(accepted_fd);

    if (pipe2(pipe_fds, O_NONBLOCK | O_CLOEXEC) != 0) {
        set_probe_message(message, message_size, "pipe2", errno);
        goto done;
    }

    char payload = 'x';
    if (send(client_fd, &payload, 1U, MSG_NOSIGNAL) != 1) {
        set_probe_message(message, message_size, "probe send", errno);
        goto done;
    }
    ssize_t moved = splice(
        accepted_fd,
        NULL,
        pipe_fds[1],
        NULL,
        1U,
        SPLICE_F_MOVE | SPLICE_F_NONBLOCK);
    if (moved != 1) {
        set_probe_message(message, message_size, "socket-to-pipe", moved < 0 ? errno : EIO);
        goto done;
    }
    moved = splice(
        pipe_fds[0],
        NULL,
        accepted_fd,
        NULL,
        1U,
        SPLICE_F_MOVE | SPLICE_F_NONBLOCK);
    if (moved != 1) {
        set_probe_message(message, message_size, "pipe-to-socket", moved < 0 ? errno : EIO);
        goto done;
    }
    char reply = '\0';
    if (recv(client_fd, &reply, 1U, MSG_WAITALL) != 1 || reply != payload) {
        set_probe_message(message, message_size, "probe recv", errno != 0 ? errno : EIO);
        goto done;
    }

    if (message != NULL && message_size > 0U) {
        snprintf(message, message_size, "ok");
    }
    result = 0;

done:
    close_fd_if_open(&pipe_fds[0]);
    close_fd_if_open(&pipe_fds[1]);
    close_fd_if_open(&accepted_fd);
    close_fd_if_open(&client_fd);
    close_fd_if_open(&listener_fd);
    return result;
}

static bool ipv4_is_loopback(uint32_t addr_net) {
    return (ntohl(addr_net) & 0xff000000U) == 0x7f000000U;
}

static int tcp_socks5_write_addr(uint8_t *out, size_t out_cap, const struct bpf2socks_sockaddr *addr) {
    size_t len = 0U;
    if (addr->family == AF_INET) {
        if (out_cap < 1U + 4U + 2U) return -1;
        out[len++] = 0x01;
        memcpy(out + len, addr->addr, 4U);
        len += 4U;
    } else if (addr->family == AF_INET6) {
        if (out_cap < 1U + 16U + 2U) return -1;
        out[len++] = 0x04;
        memcpy(out + len, addr->addr, 16U);
        len += 16U;
    } else {
        errno = EAFNOSUPPORT;
        return -1;
    }
    out[len++] = (uint8_t)(addr->port >> 8);
    out[len++] = (uint8_t)(addr->port & 0xffU);
    return (int)len;
}

static int build_socks5_connect_request(
    const struct bpf2socks_sockaddr *dst,
    uint8_t *out,
    size_t out_cap,
    size_t *out_len) {
    if (out_cap < 4U || dst == NULL || out_len == NULL) {
        errno = EINVAL;
        return -1;
    }
    size_t len = 0U;
    out[len++] = 0x05;
    out[len++] = 0x01;
    out[len++] = 0x00;
    int addr_len = tcp_socks5_write_addr(out + len, out_cap - len, dst);
    if (addr_len < 0) return -1;
    len += (size_t)addr_len;
    *out_len = len;
    return 0;
}

static int fill_tcp_token_key(int client_fd, struct bpf2socks_token_key *key) {
    struct sockaddr_storage local;
    socklen_t local_len = sizeof(local);
    if (getsockname(client_fd, (struct sockaddr *)&local, &local_len) != 0) return -1;
    memset(key, 0, sizeof(*key));
    key->protocol = BPF2SOCKS_PROTO_TCP;

    if (local.ss_family == AF_INET) {
        const struct sockaddr_in *addr = (const struct sockaddr_in *)&local;
        key->family = AF_INET;
        key->token_port = ntohs(addr->sin_port);
        memcpy(key->token_addr, &addr->sin_addr, 4U);
    } else if (local.ss_family == AF_INET6) {
        const struct sockaddr_in6 *addr = (const struct sockaddr_in6 *)&local;
        key->family = AF_INET6;
        key->token_port = ntohs(addr->sin6_port);
        memcpy(key->token_addr, &addr->sin6_addr, 16U);
    } else {
        errno = EAFNOSUPPORT;
        return -1;
    }

    struct sockaddr_storage peer;
    socklen_t peer_len = sizeof(peer);
    if (getpeername(client_fd, (struct sockaddr *)&peer, &peer_len) == 0) {
        if (peer.ss_family == AF_INET) {
            const struct sockaddr_in *peer_addr = (const struct sockaddr_in *)&peer;
            key->client_port = ntohs(peer_addr->sin_port);
            memcpy(key->client_addr, &peer_addr->sin_addr, 4U);
        } else if (peer.ss_family == AF_INET6) {
            const struct sockaddr_in6 *peer_addr = (const struct sockaddr_in6 *)&peer;
            key->client_port = ntohs(peer_addr->sin6_port);
            memcpy(key->client_addr, &peer_addr->sin6_addr, 16U);
        }
    }
    return 0;
}

static int lookup_original_with_client_fallback(
    int map_fd,
    const struct bpf2socks_token_key *key,
    struct bpf2socks_original_dst *original) {
    if (bpf2socks_token_lookup(map_fd, key, original) == 0) return 0;
    struct bpf2socks_token_key fallback = *key;
    fallback.client_port = 0U;
    memset(fallback.client_addr, 0, sizeof(fallback.client_addr));
    return bpf2socks_token_lookup(map_fd, &fallback, original);
}

static int original_to_sockaddr(const struct bpf2socks_original_dst *src, struct bpf2socks_sockaddr *dst) {
    memset(dst, 0, sizeof(*dst));
    dst->family = src->family;
    dst->port = src->port;
    if (src->family == AF_INET) {
        memcpy(dst->addr, src->addr, 4U);
        return 0;
    }
    if (src->family == AF_INET6) {
        memcpy(dst->addr, src->addr, 16U);
        return 0;
    }
    errno = EAFNOSUPPORT;
    return -1;
}

static int sockaddr_in_to_original(
    const struct sockaddr_in *addr,
    uint8_t protocol,
    struct bpf2socks_original_dst *original) {
    if (addr == NULL || original == NULL || addr->sin_family != AF_INET) return -1;
    memset(original, 0, sizeof(*original));
    original->family = AF_INET;
    original->protocol = protocol;
    original->port = ntohs(addr->sin_port);
    memcpy(original->addr, &addr->sin_addr, 4U);
    return 0;
}

static int lookup_tcp_original_from_socket(int client_fd, struct bpf2socks_original_dst *original) {
    struct sockaddr_in original_addr;
    socklen_t original_len = sizeof(original_addr);
    memset(&original_addr, 0, sizeof(original_addr));
    if (getsockopt(client_fd, SOL_IP, SO_ORIGINAL_DST, &original_addr, &original_len) == 0 &&
        original_addr.sin_family == AF_INET &&
        !ipv4_is_loopback(original_addr.sin_addr.s_addr)) {
        return sockaddr_in_to_original(&original_addr, BPF2SOCKS_PROTO_TCP, original);
    }

    struct sockaddr_storage local;
    socklen_t local_len = sizeof(local);
    if (getsockname(client_fd, (struct sockaddr *)&local, &local_len) != 0 || local.ss_family != AF_INET) {
        return -1;
    }
    const struct sockaddr_in *local_addr = (const struct sockaddr_in *)&local;
    if (ipv4_is_loopback(local_addr->sin_addr.s_addr)) {
        return -1;
    }
    return sockaddr_in_to_original(local_addr, BPF2SOCKS_PROTO_TCP, original);
}

static void active_add(struct tcp_worker_state *state, struct tcp_connection *connection) {
    connection->next = state->active;
    state->active = connection;
}

static void active_remove(struct tcp_worker_state *state, struct tcp_connection *connection) {
    struct tcp_connection **slot = &state->active;
    while (*slot != NULL) {
        if (*slot == connection) {
            *slot = connection->next;
            connection->next = NULL;
            return;
        }
        slot = &(*slot)->next;
    }
}

static void closed_add(struct tcp_worker_state *state, struct tcp_connection *connection) {
    connection->next = state->closed;
    state->closed = connection;
}

static void init_splice_pipe(struct tcp_splice_pipe *pipe_state) {
    pipe_state->read_fd = -1;
    pipe_state->write_fd = -1;
    pipe_state->queued = 0U;
    pipe_state->input_eof = false;
    pipe_state->output_shutdown = false;
}

static int open_splice_pipe(struct tcp_splice_pipe *pipe_state) {
    int fds[2] = {-1, -1};
    if (pipe2(fds, O_NONBLOCK | O_CLOEXEC) != 0) return -1;
    pipe_state->read_fd = fds[0];
    pipe_state->write_fd = fds[1];
    pipe_state->queued = 0U;
    pipe_state->input_eof = false;
    pipe_state->output_shutdown = false;
    return 0;
}

static void close_splice_pipe(struct tcp_splice_pipe *pipe_state) {
    close_fd_if_open(&pipe_state->read_fd);
    close_fd_if_open(&pipe_state->write_fd);
    pipe_state->queued = 0U;
    pipe_state->input_eof = true;
    pipe_state->output_shutdown = true;
}

static int init_connection_splice_pipes(struct tcp_connection *connection) {
    if (open_splice_pipe(&connection->client_to_upstream) != 0) return -1;
    if (open_splice_pipe(&connection->upstream_to_client) != 0) {
        close_splice_pipe(&connection->client_to_upstream);
        return -1;
    }
    return 0;
}

static void free_connection(struct tcp_connection *connection) {
    if (connection == NULL) return;
    close_splice_pipe(&connection->client_to_upstream);
    close_splice_pipe(&connection->upstream_to_client);
    free(connection);
}

static void free_closed_connections(struct tcp_worker_state *state) {
    struct tcp_connection *connection = state->closed;
    state->closed = NULL;
    while (connection != NULL) {
        struct tcp_connection *next = connection->next;
        free_connection(connection);
        connection = next;
    }
}

static void close_connection(struct tcp_worker_state *state, struct tcp_connection *connection) {
    if (connection == NULL || connection->closing) return;
    connection->closing = true;
    connection->client_ref.connection = NULL;
    connection->upstream_ref.connection = NULL;
    active_remove(state, connection);
    if (connection->client_fd >= 0) {
        (void)epoll_ctl(state->worker->epoll_fd, EPOLL_CTL_DEL, connection->client_fd, NULL);
        close(connection->client_fd);
        connection->client_fd = -1;
    }
    if (connection->upstream_fd >= 0) {
        (void)epoll_ctl(state->worker->epoll_fd, EPOLL_CTL_DEL, connection->upstream_fd, NULL);
        close(connection->upstream_fd);
        connection->upstream_fd = -1;
    }
    closed_add(state, connection);
}

static void close_all_active(struct tcp_worker_state *state) {
    while (state->active != NULL) {
        close_connection(state, state->active);
    }
    free_closed_connections(state);
}

static uint32_t tcp_socket_buffer_size(const struct bpf2socks_runtime_config *config) {
    return bpf2socks_bridge_clamp_socket_buffer(
        config != NULL ? config->tcp_buffer_size : 0U,
        BPF2SOCKS_DEFAULT_TCP_BUFFER_SIZE);
}

static void tune_tcp_socket(int fd, const struct bpf2socks_runtime_config *config) {
    uint32_t buffer_size = tcp_socket_buffer_size(config);
    bpf2socks_bridge_tune_socket_buffers(fd, buffer_size, buffer_size);
    bpf2socks_bridge_tune_tcp_advanced(fd, false);
}

static void tune_upstream_tcp_socket(int fd, const struct bpf2socks_runtime_config *config) {
    uint32_t buffer_size = tcp_socket_buffer_size(config);
    bpf2socks_bridge_tune_socket_buffers(fd, buffer_size, buffer_size);
    bpf2socks_bridge_tune_tcp_advanced(fd, true);
}

static int connect_nonblocking_addr(
    struct bpf2socks_bridge_worker *worker,
    const struct sockaddr *addr,
    socklen_t addr_len,
    enum tcp_stage *stage) {
    if (addr == NULL || addr_len == 0U || stage == NULL) {
        errno = EINVAL;
        return -1;
    }
    int fd = socket(addr->sa_family, SOCK_STREAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;
    tune_upstream_tcp_socket(fd, worker != NULL ? worker->config : NULL);
    if (connect(fd, addr, addr_len) == 0) {
        *stage = TCP_STAGE_HELLO_WRITE;
        return fd;
    }
    if (errno == EINPROGRESS) {
        *stage = TCP_STAGE_CONNECTING;
        return fd;
    }
    int saved = errno;
    close(fd);
    errno = saved;
    return -1;
}

static int add_fd(struct tcp_worker_state *state, int fd, struct tcp_fd_ref *ref, uint32_t events) {
    struct epoll_event event;
    memset(&event, 0, sizeof(event));
    event.events = events;
    event.data.ptr = ref;
    if (epoll_ctl(state->worker->epoll_fd, EPOLL_CTL_ADD, fd, &event) != 0) return -1;
    ref->events = events;
    return 0;
}

static int mod_fd(struct tcp_worker_state *state, int fd, struct tcp_fd_ref *ref, uint32_t events) {
    if (ref->events == events) return 0;
    if (events == 0U) {
        if (epoll_ctl(state->worker->epoll_fd, EPOLL_CTL_DEL, fd, NULL) != 0) return -1;
        ref->events = 0U;
        return 0;
    }
    struct epoll_event event;
    memset(&event, 0, sizeof(event));
    event.events = events;
    event.data.ptr = ref;
    int op = ref->events == 0U ? EPOLL_CTL_ADD : EPOLL_CTL_MOD;
    if (epoll_ctl(state->worker->epoll_fd, op, fd, &event) != 0) return -1;
    ref->events = events;
    return 0;
}

static uint32_t relay_side_events(bool input_open, bool output_pending) {
    uint32_t events = 0U;
    if (input_open) events |= EPOLLIN;
    if (output_pending) events |= EPOLLOUT;
    if (events != 0U) {
        events |= BPF2SOCKS_TCP_RELAY_EVENTS;
    }
    return events;
}

static uint32_t client_events(const struct tcp_connection *connection) {
    uint32_t events = BPF2SOCKS_TCP_RELAY_EVENTS;
    if (connection->stage == TCP_STAGE_RELAY) {
        events = relay_side_events(
            !connection->client_to_upstream.input_eof,
            connection->upstream_to_client.queued > 0U);
    }
    return events;
}

static uint32_t upstream_events(const struct tcp_connection *connection) {
    uint32_t events = BPF2SOCKS_TCP_RELAY_EVENTS;
    switch (connection->stage) {
        case TCP_STAGE_CONNECTING:
        case TCP_STAGE_HELLO_WRITE:
        case TCP_STAGE_REQUEST_WRITE:
            events |= EPOLLOUT;
            break;
        case TCP_STAGE_HELLO_READ:
        case TCP_STAGE_RESPONSE_READ:
            events |= EPOLLIN;
            break;
        case TCP_STAGE_RELAY:
            events = relay_side_events(
                !connection->upstream_to_client.input_eof,
                connection->client_to_upstream.queued > 0U);
            break;
    }
    return events;
}

static void update_connection_events(struct tcp_worker_state *state, struct tcp_connection *connection) {
    if (connection == NULL || connection->closing) return;
    (void)mod_fd(state, connection->client_fd, &connection->client_ref, client_events(connection));
    (void)mod_fd(state, connection->upstream_fd, &connection->upstream_ref, upstream_events(connection));
}

static int start_hello_write(struct tcp_connection *connection) {
    connection->handshake[0] = 0x05;
    connection->handshake[1] = 0x01;
    connection->handshake[2] = 0x00;
    size_t length = 3U;
    size_t request_length = 0U;
    if (build_socks5_connect_request(
            &connection->target,
            connection->handshake + length,
            sizeof(connection->handshake) - length,
            &request_length) < 0) {
        return -1;
    }
    length += request_length;
    connection->handshake_length = length;
    connection->handshake_offset = 0U;
    connection->request_pipelined = true;
    connection->stage = TCP_STAGE_HELLO_WRITE;
    return 0;
}

static int flush_handshake(struct tcp_connection *connection) {
    while (connection->handshake_offset < connection->handshake_length) {
        ssize_t sent = send(
            connection->upstream_fd,
            connection->handshake + connection->handshake_offset,
            connection->handshake_length - connection->handshake_offset,
            MSG_DONTWAIT | MSG_NOSIGNAL);
        if (sent < 0 && errno == EINTR) continue;
        if (sent < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) return 0;
        if (sent <= 0) return -1;
        connection->handshake_offset += (size_t)sent;
    }
    connection->response_length = 0U;
    connection->response_expected = connection->stage == TCP_STAGE_HELLO_WRITE ? 2U : 4U;
    connection->stage = connection->stage == TCP_STAGE_HELLO_WRITE
        ? TCP_STAGE_HELLO_READ
        : TCP_STAGE_RESPONSE_READ;
    return 0;
}

static int read_socks_response(struct tcp_connection *connection) {
    while (connection->response_length < sizeof(connection->response)) {
        if (connection->response_expected <= connection->response_length) return 1;
        size_t remaining = connection->response_expected - connection->response_length;
        ssize_t received = recv(
            connection->upstream_fd,
            connection->response + connection->response_length,
            remaining,
            MSG_DONTWAIT);
        if (received < 0 && errno == EINTR) continue;
        if (received < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) return 0;
        if (received <= 0) return -1;
        connection->response_length += (size_t)received;

        if (connection->stage == TCP_STAGE_RESPONSE_READ &&
            connection->response_expected == 4U &&
            connection->response_length >= 4U) {
            uint8_t atyp = connection->response[3];
            if (atyp == 0x01) {
                connection->response_expected = 10U;
            } else if (atyp == 0x04) {
                connection->response_expected = 22U;
            } else if (atyp == 0x03) {
                connection->response_expected = 5U;
            } else {
                errno = EPROTO;
                return -1;
            }
        }
        if (connection->stage == TCP_STAGE_RESPONSE_READ &&
            connection->response_expected == 5U &&
            connection->response_length >= 5U) {
            connection->response_expected = 4U + 1U + connection->response[4] + 2U;
        }
        if (connection->response_length >= connection->response_expected) return 1;
    }
    errno = EPROTO;
    return -1;
}

static int advance_socks_state(struct tcp_connection *connection) {
    if (connection->stage == TCP_STAGE_CONNECTING) {
        int error = 0;
        socklen_t error_len = sizeof(error);
        if (getsockopt(connection->upstream_fd, SOL_SOCKET, SO_ERROR, &error, &error_len) != 0 || error != 0) {
            errno = error != 0 ? error : errno;
            return -1;
        }
        return start_hello_write(connection);
    }
    return 0;
}

static int read_hello_and_build_request(
    struct tcp_connection *connection,
    const struct bpf2socks_sockaddr *dst) {
    int result = read_socks_response(connection);
    if (result <= 0) return result;
    if (connection->response[0] != 0x05 || connection->response[1] != 0x00) {
        errno = EPROTO;
        return -1;
    }
    if (connection->request_pipelined) {
        connection->response_length = 0U;
        connection->response_expected = 4U;
        connection->stage = TCP_STAGE_RESPONSE_READ;
        return 0;
    }
    size_t length = 0U;
    if (build_socks5_connect_request(dst, connection->handshake, sizeof(connection->handshake), &length) < 0) {
        return -1;
    }
    connection->handshake_length = length;
    connection->handshake_offset = 0U;
    connection->stage = TCP_STAGE_REQUEST_WRITE;
    return 0;
}

static int read_connect_response(struct tcp_connection *connection) {
    int result = read_socks_response(connection);
    if (result <= 0) return result;
    if (connection->response[0] != 0x05 || connection->response[1] != 0x00) {
        errno = EPROTO;
        return -1;
    }
    if (init_connection_splice_pipes(connection) < 0) {
        return -1;
    }
    connection->stage = TCP_STAGE_RELAY;
    return 0;
}

static struct tcp_connection *create_connection(
    struct tcp_worker_state *state,
    int client_fd,
    const struct bpf2socks_sockaddr *dst) {
    struct tcp_connection *connection = calloc(1U, sizeof(*connection));
    if (connection == NULL) return NULL;
    connection->client_fd = client_fd;
    connection->upstream_fd = -1;
    init_splice_pipe(&connection->client_to_upstream);
    init_splice_pipe(&connection->upstream_to_client);
    connection->worker = state->worker;
    connection->client_ref.connection = connection;
    connection->client_ref.side = TCP_SIDE_CLIENT;
    connection->client_ref.fd = client_fd;
    connection->upstream_ref.connection = connection;
    connection->upstream_ref.side = TCP_SIDE_UPSTREAM;
    connection->target = *dst;

    connection->upstream_fd = connect_nonblocking_addr(
        state->worker,
        (const struct sockaddr *)&state->worker->socks_addr,
        state->worker->socks_addr_len,
        &connection->stage);
    if (connection->upstream_fd < 0) {
        free_connection(connection);
        return NULL;
    }
    connection->upstream_ref.fd = connection->upstream_fd;
    if (connection->stage == TCP_STAGE_HELLO_WRITE) {
        if (start_hello_write(connection) < 0) {
            free_connection(connection);
            return NULL;
        }
    }
    return connection;
}

static int client_original_dst(int client_fd, const struct bpf2socks_runtime_config *config, struct bpf2socks_sockaddr *dst) {
    struct bpf2socks_token_key key;
    struct bpf2socks_original_dst original;
    if (fill_tcp_token_key(client_fd, &key) < 0) return -1;
    if (lookup_original_with_client_fallback(config->token_map_fd, &key, &original) < 0 &&
        lookup_tcp_original_from_socket(client_fd, &original) < 0) {
        return -1;
    }
    return original_to_sockaddr(&original, dst);
}

static void accept_ready_clients(struct tcp_worker_state *state, int listener_fd) {
    while (!bpf2socks_stop_requested) {
        int client = accept4(listener_fd, NULL, NULL, SOCK_NONBLOCK | SOCK_CLOEXEC);
        if (client < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) return;
        if (client < 0 && errno == EINTR) continue;
        if (client < 0) {
            fprintf(stderr, "accept4 failed: errno=%d\n", errno);
            return;
        }
        ++state->worker->stats.tcp_accepts;
        tune_tcp_socket(client, state->worker->config);

        struct bpf2socks_sockaddr dst;
        if (client_original_dst(client, state->worker->config, &dst) < 0) {
            fprintf(stderr, "missing TCP original destination: errno=%d\n", errno);
            close(client);
            continue;
        }

        struct tcp_connection *connection = create_connection(state, client, &dst);
        if (connection == NULL) {
            ++state->worker->stats.tcp_connect_failures;
            fprintf(stderr, "SOCKS5 TCP upstream connect failed: errno=%d\n", errno);
            close(client);
            continue;
        }

        active_add(state, connection);
        if (add_fd(state, connection->client_fd, &connection->client_ref, client_events(connection)) != 0 ||
            add_fd(state, connection->upstream_fd, &connection->upstream_ref, upstream_events(connection)) != 0) {
            close_connection(state, connection);
        }
    }
}

static int shutdown_splice_target(int target_fd, struct tcp_splice_pipe *pipe_state) {
    if (!pipe_state->input_eof || pipe_state->queued > 0U || pipe_state->output_shutdown) return 0;
    if (shutdown(target_fd, SHUT_WR) != 0 && errno != ENOTCONN && errno != EPIPE) {
        return -1;
    }
    pipe_state->output_shutdown = true;
    return 0;
}

static int splice_socket_to_pipe(int source_fd, struct tcp_splice_pipe *pipe_state) {
    if (pipe_state->input_eof) return 0;
    while (true) {
        ssize_t moved = splice(
            source_fd,
            NULL,
            pipe_state->write_fd,
            NULL,
            BPF2SOCKS_TCP_SPLICE_CHUNK,
            SPLICE_F_MOVE | SPLICE_F_NONBLOCK);
        if (moved > 0) {
            pipe_state->queued += (size_t)moved;
            continue;
        }
        if (moved == 0) {
            pipe_state->input_eof = true;
            return 0;
        }
        if (errno == EINTR) continue;
        if (errno == EAGAIN || errno == EWOULDBLOCK) return 0;
        return -1;
    }
}

static int splice_pipe_to_socket(int target_fd, struct tcp_splice_pipe *pipe_state, uint64_t *bytes_out) {
    while (pipe_state->queued > 0U) {
        size_t chunk = pipe_state->queued;
        if (chunk > BPF2SOCKS_TCP_SPLICE_CHUNK) chunk = BPF2SOCKS_TCP_SPLICE_CHUNK;
        ssize_t moved = splice(
            pipe_state->read_fd,
            NULL,
            target_fd,
            NULL,
            chunk,
            SPLICE_F_MOVE | SPLICE_F_NONBLOCK);
        if (moved > 0) {
            pipe_state->queued -= (size_t)moved;
            if (bytes_out != NULL) *bytes_out += (uint64_t)moved;
            continue;
        }
        if (moved == 0) return -1;
        if (errno == EINTR) continue;
        if (errno == EAGAIN || errno == EWOULDBLOCK) break;
        return -1;
    }
    return shutdown_splice_target(target_fd, pipe_state);
}

static bool splice_pipe_complete(const struct tcp_splice_pipe *pipe_state) {
    return pipe_state->input_eof && pipe_state->queued == 0U && pipe_state->output_shutdown;
}

static bool relay_complete(const struct tcp_connection *connection) {
    return splice_pipe_complete(&connection->client_to_upstream) &&
        splice_pipe_complete(&connection->upstream_to_client);
}

static int flush_relay_pipes(struct tcp_connection *connection) {
    if (splice_pipe_to_socket(
            connection->upstream_fd,
            &connection->client_to_upstream,
            &connection->worker->stats.tcp_bytes_client_to_upstream) < 0) {
        return -1;
    }
    if (splice_pipe_to_socket(
            connection->client_fd,
            &connection->upstream_to_client,
            &connection->worker->stats.tcp_bytes_upstream_to_client) < 0) {
        return -1;
    }
    return 0;
}

static void handle_client_event(struct tcp_worker_state *state, struct tcp_connection *connection, uint32_t events) {
    if ((events & BPF2SOCKS_TCP_RELAY_EVENTS) != 0) {
        close_connection(state, connection);
    }
}

static void handle_upstream_handshake(
    struct tcp_worker_state *state,
    struct tcp_connection *connection,
    uint32_t events) {
    if ((events & BPF2SOCKS_TCP_RELAY_EVENTS) != 0) {
        close_connection(state, connection);
        return;
    }
    int result = 0;
    if (connection->stage == TCP_STAGE_CONNECTING && (events & EPOLLOUT) != 0) {
        result = advance_socks_state(connection);
    }
    if (result == 0 &&
        (connection->stage == TCP_STAGE_HELLO_WRITE || connection->stage == TCP_STAGE_REQUEST_WRITE) &&
        (events & EPOLLOUT) != 0) {
        result = flush_handshake(connection);
    }
    if (result == 0 && connection->stage == TCP_STAGE_HELLO_READ && (events & EPOLLIN) != 0) {
        result = read_hello_and_build_request(connection, &connection->target);
    }
    if (result == 0 && connection->stage == TCP_STAGE_RESPONSE_READ && (events & EPOLLIN) != 0) {
        result = read_connect_response(connection);
    }
    if (result < 0) {
        ++state->worker->stats.tcp_connect_failures;
        close_connection(state, connection);
    }
}

static void handle_splice_relay(
    struct tcp_worker_state *state,
    struct tcp_connection *connection,
    enum tcp_side side,
    uint32_t events) {
    if ((events & EPOLLERR) != 0) {
        close_connection(state, connection);
        return;
    }

    if (side == TCP_SIDE_CLIENT) {
        if ((events & EPOLLIN) != 0 &&
            splice_socket_to_pipe(connection->client_fd, &connection->client_to_upstream) < 0) {
            close_connection(state, connection);
            return;
        }
        if ((events & (EPOLLHUP | EPOLLRDHUP)) != 0) {
            connection->client_to_upstream.input_eof = true;
        }
    } else if (side == TCP_SIDE_UPSTREAM) {
        if ((events & EPOLLIN) != 0 &&
            splice_socket_to_pipe(connection->upstream_fd, &connection->upstream_to_client) < 0) {
            close_connection(state, connection);
            return;
        }
        if ((events & (EPOLLHUP | EPOLLRDHUP)) != 0) {
            connection->upstream_to_client.input_eof = true;
        }
    }

    if (flush_relay_pipes(connection) < 0) {
        close_connection(state, connection);
        return;
    }
    if (relay_complete(connection)) {
        close_connection(state, connection);
    }
}

static void handle_connection_event(struct tcp_worker_state *state, struct tcp_fd_ref *ref, uint32_t events) {
    if (ref == NULL) return;
    if (ref->side == TCP_SIDE_LISTENER) {
        if ((events & EPOLLIN) != 0) accept_ready_clients(state, ref->fd);
        return;
    }
    if (ref->connection == NULL) return;
    struct tcp_connection *connection = ref->connection;
    if (connection->stage == TCP_STAGE_RELAY) {
        handle_splice_relay(state, connection, ref->side, events);
    } else if (ref->side == TCP_SIDE_CLIENT) {
        handle_client_event(state, connection, events);
    } else {
        handle_upstream_handshake(state, connection, events);
    }
    update_connection_events(state, connection);
}

int bpf2socks_bridge_tcp_worker_run(struct bpf2socks_bridge_worker *worker) {
    if (worker == NULL || worker->tcp_listener_fd < 0) return -1;
    int epoll_fd = epoll_create1(EPOLL_CLOEXEC);
    if (epoll_fd < 0) return -1;
    worker->epoll_fd = epoll_fd;

    struct tcp_worker_state state;
    memset(&state, 0, sizeof(state));
    state.worker = worker;
    state.listener_ref.side = TCP_SIDE_LISTENER;
    state.listener_ref.fd = worker->tcp_listener_fd;
    state.listener6_ref.side = TCP_SIDE_LISTENER;
    state.listener6_ref.fd = worker->tcp_listener6_fd;

    struct epoll_event event;
    memset(&event, 0, sizeof(event));
    event.events = EPOLLIN;
    event.data.ptr = &state.listener_ref;
    if (epoll_ctl(epoll_fd, EPOLL_CTL_ADD, worker->tcp_listener_fd, &event) != 0) {
        int saved = errno;
        close(epoll_fd);
        worker->epoll_fd = -1;
        errno = saved;
        return -1;
    }
    if (worker->tcp_listener6_fd >= 0) {
        memset(&event, 0, sizeof(event));
        event.events = EPOLLIN;
        event.data.ptr = &state.listener6_ref;
        if (epoll_ctl(epoll_fd, EPOLL_CTL_ADD, worker->tcp_listener6_fd, &event) != 0) {
            int saved = errno;
            close(epoll_fd);
            worker->epoll_fd = -1;
            errno = saved;
            return -1;
        }
    }

    struct epoll_event events[BPF2SOCKS_TCP_EVENTS];
    while (!bpf2socks_stop_requested) {
        int ready = epoll_wait(epoll_fd, events, BPF2SOCKS_TCP_EVENTS, 1000);
        if (ready < 0 && errno == EINTR) continue;
        if (ready < 0) break;
        for (int i = 0; i < ready; ++i) {
            handle_connection_event(&state, events[i].data.ptr, events[i].events);
        }
        free_closed_connections(&state);
    }

    close_all_active(&state);
    close(epoll_fd);
    worker->epoll_fd = -1;
    return 0;
}
