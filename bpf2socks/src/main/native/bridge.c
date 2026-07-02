// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

#include "bridge_internal.h"

#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#ifndef IP_ORIGDSTADDR
#define IP_ORIGDSTADDR 20
#endif

#ifndef IP_RECVORIGDSTADDR
#define IP_RECVORIGDSTADDR IP_ORIGDSTADDR
#endif

#ifndef IP_TRANSPARENT
#define IP_TRANSPARENT 19
#endif

#ifndef IPV6_TRANSPARENT
#define IPV6_TRANSPARENT 75
#endif

struct bridge_thread_args {
    struct bpf2socks_bridge_worker *worker;
    bool udp;
};

struct bridge_worker_threads {
    pthread_t tcp_thread;
    pthread_t udp_thread;
    bool tcp_started;
    bool udp_started;
};

struct bridge_stats_thread_args {
    const struct bpf2socks_bridge_worker *workers;
    uint32_t worker_count;
    char stats_path[BPF2SOCKS_MAX_PATH_LEN];
};

int bpf2socks_bridge_set_nonblocking(int fd) {
    int flags = fcntl(fd, F_GETFL, 0);
    if (flags < 0) return -1;
    if (fcntl(fd, F_SETFL, flags | O_NONBLOCK) != 0) return -1;
    return 0;
}

uint32_t bpf2socks_bridge_clamp_socket_buffer(uint32_t requested, uint32_t fallback) {
    uint32_t value = requested == 0U ? fallback : requested;
    if (value < 4096U) value = 4096U;
    if (value > 4194304U) value = 4194304U;
    return value;
}

void bpf2socks_bridge_record_socket_buffers(int fd) {
    int recv_buffer = 0;
    int send_buffer = 0;
    socklen_t recv_len = sizeof(recv_buffer);
    socklen_t send_len = sizeof(send_buffer);
    (void)getsockopt(fd, SOL_SOCKET, SO_RCVBUF, &recv_buffer, &recv_len);
    (void)getsockopt(fd, SOL_SOCKET, SO_SNDBUF, &send_buffer, &send_len);
    (void)recv_buffer;
    (void)send_buffer;
}

void bpf2socks_bridge_tune_socket_buffers(int fd, uint32_t recv_size, uint32_t send_size) {
    if (recv_size > 0U) {
        int value = (int)recv_size;
        (void)setsockopt(fd, SOL_SOCKET, SO_RCVBUF, &value, sizeof(value));
    }
    if (send_size > 0U) {
        int value = (int)send_size;
        (void)setsockopt(fd, SOL_SOCKET, SO_SNDBUF, &value, sizeof(value));
    }
    bpf2socks_bridge_record_socket_buffers(fd);
}

static int bind_tcp_listener(const struct bpf2socks_runtime_config *config) {
    int fd = socket(AF_INET, SOCK_STREAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;
    int one = 1;
    (void)setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    (void)setsockopt(fd, SOL_SOCKET, SO_REUSEPORT, &one, sizeof(one));
    uint32_t tcp_buffer = bpf2socks_bridge_clamp_socket_buffer(
        config->tcp_buffer_size,
        BPF2SOCKS_DEFAULT_TCP_BUFFER_SIZE);
    bpf2socks_bridge_tune_socket_buffers(fd, tcp_buffer, tcp_buffer);
    bpf2socks_bridge_enable_tcp_fastopen_listener(fd);
    if (config->sk_lookup_sock_map_fd >= 0 &&
        setsockopt(fd, IPPROTO_IP, IP_TRANSPARENT, &one, sizeof(one)) != 0) {
        int saved = errno;
        close(fd);
        errno = saved;
        return -1;
    }

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons(config->listen_port);
    if (inet_pton(AF_INET, config->listen_host, &addr.sin_addr) != 1) {
        close(fd);
        errno = EINVAL;
        return -1;
    }
    if (bind(fd, (struct sockaddr *)&addr, sizeof(addr)) != 0 ||
        listen(fd, 4096) != 0) {
        int saved = errno;
        close(fd);
        errno = saved;
        return -1;
    }
    return fd;
}

static int bind_tcp6_listener(const struct bpf2socks_runtime_config *config) {
    int fd = socket(AF_INET6, SOCK_STREAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;
    int one = 1;
    (void)setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    (void)setsockopt(fd, SOL_SOCKET, SO_REUSEPORT, &one, sizeof(one));
    uint32_t tcp_buffer = bpf2socks_bridge_clamp_socket_buffer(
        config->tcp_buffer_size,
        BPF2SOCKS_DEFAULT_TCP_BUFFER_SIZE);
    bpf2socks_bridge_tune_socket_buffers(fd, tcp_buffer, tcp_buffer);
    bpf2socks_bridge_enable_tcp_fastopen_listener(fd);
    (void)setsockopt(fd, IPPROTO_IPV6, IPV6_V6ONLY, &one, sizeof(one));
    if (config->sk_lookup_sock_map_fd >= 0 &&
        setsockopt(fd, IPPROTO_IPV6, IPV6_TRANSPARENT, &one, sizeof(one)) != 0) {
        int saved = errno;
        close(fd);
        errno = saved;
        return -1;
    }

    struct sockaddr_in6 addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin6_family = AF_INET6;
    addr.sin6_port = htons(config->listen_port);
    addr.sin6_addr = in6addr_any;
    if (bind(fd, (struct sockaddr *)&addr, sizeof(addr)) != 0 ||
        listen(fd, 4096) != 0) {
        int saved = errno;
        close(fd);
        errno = saved;
        return -1;
    }
    return fd;
}

static int bind_udp_listener(const struct bpf2socks_runtime_config *config) {
    int fd = socket(AF_INET, SOCK_DGRAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;
    int one = 1;
    (void)setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    (void)setsockopt(fd, SOL_SOCKET, SO_REUSEPORT, &one, sizeof(one));
    uint32_t udp_buffer = bpf2socks_bridge_clamp_socket_buffer(
        config->udp_recv_buffer_size,
        BPF2SOCKS_DEFAULT_UDP_RECV_BUFFER_SIZE);
    bpf2socks_bridge_tune_socket_buffers(fd, udp_buffer, udp_buffer);
    bpf2socks_bridge_tune_udp_advanced(fd);
    if (setsockopt(fd, IPPROTO_IP, IP_PKTINFO, &one, sizeof(one)) != 0) {
        int saved = errno;
        close(fd);
        errno = saved;
        return -1;
    }
    if (config->sk_lookup_sock_map_fd >= 0 &&
        setsockopt(fd, IPPROTO_IP, IP_TRANSPARENT, &one, sizeof(one)) != 0) {
        int saved = errno;
        close(fd);
        errno = saved;
        return -1;
    }
    (void)setsockopt(fd, IPPROTO_IP, IP_RECVORIGDSTADDR, &one, sizeof(one));

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons(config->listen_port);
    if (inet_pton(AF_INET, config->listen_host, &addr.sin_addr) != 1) {
        close(fd);
        errno = EINVAL;
        return -1;
    }
    if (bind(fd, (struct sockaddr *)&addr, sizeof(addr)) != 0) {
        int saved = errno;
        close(fd);
        errno = saved;
        return -1;
    }
    return fd;
}

static int bind_udp6_listener(const struct bpf2socks_runtime_config *config) {
    int fd = socket(AF_INET6, SOCK_DGRAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;
    int one = 1;
    (void)setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    (void)setsockopt(fd, SOL_SOCKET, SO_REUSEPORT, &one, sizeof(one));
    uint32_t udp_buffer = bpf2socks_bridge_clamp_socket_buffer(
        config->udp_recv_buffer_size,
        BPF2SOCKS_DEFAULT_UDP_RECV_BUFFER_SIZE);
    bpf2socks_bridge_tune_socket_buffers(fd, udp_buffer, udp_buffer);
    bpf2socks_bridge_tune_udp_advanced(fd);
    (void)setsockopt(fd, IPPROTO_IPV6, IPV6_V6ONLY, &one, sizeof(one));
    if (setsockopt(fd, IPPROTO_IPV6, IPV6_RECVPKTINFO, &one, sizeof(one)) != 0) {
        int saved = errno;
        close(fd);
        errno = saved;
        return -1;
    }
    if (config->sk_lookup_sock_map_fd >= 0 &&
        setsockopt(fd, IPPROTO_IPV6, IPV6_TRANSPARENT, &one, sizeof(one)) != 0) {
        int saved = errno;
        close(fd);
        errno = saved;
        return -1;
    }

    struct sockaddr_in6 addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin6_family = AF_INET6;
    addr.sin6_port = htons(config->listen_port);
    addr.sin6_addr = in6addr_any;
    if (bind(fd, (struct sockaddr *)&addr, sizeof(addr)) != 0) {
        int saved = errno;
        close(fd);
        errno = saved;
        return -1;
    }
    return fd;
}

static void *bridge_worker_thread(void *arg) {
    struct bridge_thread_args *thread_args = arg;
    if (thread_args == NULL) return NULL;
    struct bpf2socks_bridge_worker *worker = thread_args->worker;
    bool udp = thread_args->udp;
    free(thread_args);
    if (udp) {
        (void)bpf2socks_bridge_udp_worker_run(worker);
    } else {
        (void)bpf2socks_bridge_tcp_worker_run(worker);
    }
    return NULL;
}

static int start_worker_thread(
    struct bpf2socks_bridge_worker *worker,
    bool udp,
    pthread_t *thread) {
    struct bridge_thread_args *args = malloc(sizeof(*args));
    if (args == NULL) return -1;
    args->worker = worker;
    args->udp = udp;
    int rc = pthread_create(thread, NULL, bridge_worker_thread, args);
    if (rc != 0) {
        free(args);
        errno = rc;
        return -1;
    }
    return 0;
}

static uint32_t normalized_worker_count(const struct bpf2socks_runtime_config *config) {
    uint32_t worker_count = config->worker_count;
    if (worker_count == 0U) worker_count = BPF2SOCKS_DEFAULT_WORKER_COUNT;
    if (worker_count > BPF2SOCKS_MAX_WORKER_COUNT) worker_count = BPF2SOCKS_MAX_WORKER_COUNT;
    return worker_count;
}

static void close_worker_sockets(struct bpf2socks_bridge_worker *worker) {
    if (worker == NULL) return;
    if (worker->tcp_listener_fd >= 0) {
        close(worker->tcp_listener_fd);
        worker->tcp_listener_fd = -1;
    }
    if (worker->tcp_listener6_fd >= 0) {
        close(worker->tcp_listener6_fd);
        worker->tcp_listener6_fd = -1;
    }
    if (worker->udp_listener_fd >= 0) {
        close(worker->udp_listener_fd);
        worker->udp_listener_fd = -1;
    }
    if (worker->udp_listener6_fd >= 0) {
        close(worker->udp_listener6_fd);
        worker->udp_listener6_fd = -1;
    }
}

static void close_workers(struct bpf2socks_bridge_worker *workers, uint32_t worker_count) {
    if (workers == NULL) return;
    for (uint32_t i = 0; i < worker_count; ++i) {
        close_worker_sockets(&workers[i]);
    }
}

static void add_worker_stats(struct bpf2socks_bridge_stats *total, const struct bpf2socks_bridge_stats *stats) {
    total->tcp_accepts += stats->tcp_accepts;
    total->tcp_connect_failures += stats->tcp_connect_failures;
    total->tcp_bytes_client_to_upstream += stats->tcp_bytes_client_to_upstream;
    total->tcp_bytes_upstream_to_client += stats->tcp_bytes_upstream_to_client;
    total->udp_packets_from_client += stats->udp_packets_from_client;
    total->udp_packets_to_upstream += stats->udp_packets_to_upstream;
    total->udp_packets_from_upstream += stats->udp_packets_from_upstream;
    total->udp_packets_to_client += stats->udp_packets_to_client;
    total->udp_token_misses += stats->udp_token_misses;
    total->udp_session_hits += stats->udp_session_hits;
    total->udp_session_misses += stats->udp_session_misses;
    total->udp_session_evictions += stats->udp_session_evictions;
    total->udp_associate_creates += stats->udp_associate_creates;
    total->udp_associate_reuses += stats->udp_associate_reuses;
    total->udp_reply_binding_creates += stats->udp_reply_binding_creates;
    total->udp_reply_binding_hits += stats->udp_reply_binding_hits;
    total->udp_fullcone_binding_creates += stats->udp_fullcone_binding_creates;
    total->udp_binding_evictions += stats->udp_binding_evictions;
    total->udp_drops_malformed_socks5 += stats->udp_drops_malformed_socks5;
    total->udp_drops_oversized += stats->udp_drops_oversized;
    total->udp_send_errors += stats->udp_send_errors;
}

static int stats_path_from_pid_path(const char *pid_path, char *out, size_t out_size) {
    if (pid_path == NULL || pid_path[0] == '\0') {
        errno = EINVAL;
        return -1;
    }
    int written = snprintf(out, out_size, "%s.stats", pid_path);
    if (written < 0 || (size_t)written >= out_size) {
        errno = ENAMETOOLONG;
        return -1;
    }
    return 0;
}

static void aggregate_worker_stats(
    const struct bpf2socks_bridge_worker *workers,
    uint32_t worker_count,
    struct bpf2socks_bridge_stats *out) {
    memset(out, 0, sizeof(*out));
    if (workers == NULL) return;
    for (uint32_t i = 0; i < worker_count; ++i) {
        add_worker_stats(out, &workers[i].stats);
    }
}

static int write_stats_snapshot(const char *path, const struct bpf2socks_bridge_stats *stats) {
    char tmp_path[BPF2SOCKS_MAX_PATH_LEN];
    int written = snprintf(tmp_path, sizeof(tmp_path), "%s.tmp", path);
    if (written < 0 || (size_t)written >= sizeof(tmp_path)) {
        errno = ENAMETOOLONG;
        return -1;
    }
    FILE *file = fopen(tmp_path, "wb");
    if (file == NULL) return -1;
    size_t count = fwrite(stats, sizeof(*stats), 1U, file);
    int close_result = fclose(file);
    if (count != 1U || close_result != 0) {
        (void)unlink(tmp_path);
        errno = EIO;
        return -1;
    }
    if (rename(tmp_path, path) != 0) {
        (void)unlink(tmp_path);
        return -1;
    }
    return 0;
}

static void *bridge_stats_thread(void *arg) {
    struct bridge_stats_thread_args *args = arg;
    if (args == NULL) return NULL;
    while (!bpf2socks_stop_requested) {
        struct bpf2socks_bridge_stats stats;
        aggregate_worker_stats(args->workers, args->worker_count, &stats);
        (void)write_stats_snapshot(args->stats_path, &stats);
        sleep(1U);
    }
    struct bpf2socks_bridge_stats stats;
    aggregate_worker_stats(args->workers, args->worker_count, &stats);
    (void)write_stats_snapshot(args->stats_path, &stats);
    free(args);
    return NULL;
}

static int start_stats_thread(
    const char *pid_path,
    const struct bpf2socks_bridge_worker *workers,
    uint32_t worker_count,
    pthread_t *thread) {
    struct bridge_stats_thread_args *args = calloc(1U, sizeof(*args));
    if (args == NULL) return -1;
    args->workers = workers;
    args->worker_count = worker_count;
    if (stats_path_from_pid_path(pid_path, args->stats_path, sizeof(args->stats_path)) < 0) {
        free(args);
        return -1;
    }
    int rc = pthread_create(thread, NULL, bridge_stats_thread, args);
    if (rc != 0) {
        free(args);
        errno = rc;
        return -1;
    }
    return 0;
}

int bpf2socks_bridge_run(const struct bpf2socks_runtime_config *config, const char *pid_path) {
    if (config == NULL) return -1;
    uint32_t worker_count = normalized_worker_count(config);
    struct sockaddr_storage socks_addr;
    socklen_t socks_addr_len = 0;
    if (bpf2socks_resolve_tcp_addr(config->socks_host, config->socks_port, &socks_addr, &socks_addr_len) < 0) {
        return -1;
    }
    struct bpf2socks_bridge_worker *workers = calloc(worker_count, sizeof(*workers));
    struct bridge_worker_threads *threads = calloc(worker_count, sizeof(*threads));
    if (workers == NULL || threads == NULL) {
        free(workers);
        free(threads);
        return -1;
    }

    int result = -1;
    pthread_t stats_thread;
    bool stats_started = false;
    for (uint32_t i = 0; i < worker_count; ++i) {
        workers[i].id = i;
        workers[i].tcp_listener_fd = -1;
        workers[i].tcp_listener6_fd = -1;
        workers[i].udp_listener_fd = -1;
        workers[i].udp_listener6_fd = -1;
        workers[i].epoll_fd = -1;
        workers[i].socks_addr = socks_addr;
        workers[i].socks_addr_len = socks_addr_len;
        workers[i].config = config;

        if (config->enable_udp) {
            workers[i].udp_listener_fd = bind_udp_listener(config);
            if (workers[i].udp_listener_fd < 0) {
                fprintf(stderr, "failed to bind bpf2socks UDP listener for worker %u: errno=%d\n", i, errno);
                bpf2socks_stop_requested = 1;
                goto done;
            }
        }

        if (config->enable_udp && config->enable_ipv6) {
            workers[i].udp_listener6_fd = bind_udp6_listener(config);
            if (workers[i].udp_listener6_fd < 0) {
                fprintf(stderr, "failed to bind bpf2socks UDP6 listener for worker %u: errno=%d\n", i, errno);
                bpf2socks_stop_requested = 1;
                goto done;
            }
        }

        workers[i].tcp_listener_fd = bind_tcp_listener(config);
        if (workers[i].tcp_listener_fd < 0) {
            fprintf(stderr, "failed to bind bpf2socks TCP listener for worker %u: errno=%d\n", i, errno);
            bpf2socks_stop_requested = 1;
            goto done;
        }

        if (config->enable_ipv6) {
            workers[i].tcp_listener6_fd = bind_tcp6_listener(config);
            if (workers[i].tcp_listener6_fd < 0) {
                fprintf(stderr, "failed to bind bpf2socks TCP6 listener for worker %u: errno=%d\n", i, errno);
                bpf2socks_stop_requested = 1;
                goto done;
            }
        }

        if (bpf2socks_sk_lookup_register_worker_sockets(
                config->sk_lookup_sock_map_fd,
                workers[i].id,
                workers[i].tcp_listener_fd,
                workers[i].udp_listener_fd,
                workers[i].tcp_listener6_fd,
                workers[i].udp_listener6_fd) < 0) {
            fprintf(stderr, "failed to register bpf2socks sockets for sk_lookup worker %u: errno=%d\n", i, errno);
            bpf2socks_stop_requested = 1;
            goto done;
        }
    }

    if (config->debug_stats && start_stats_thread(pid_path, workers, worker_count, &stats_thread) == 0) {
        stats_started = true;
    }

    for (uint32_t i = 0; i < worker_count; ++i) {
        if (start_worker_thread(&workers[i], false, &threads[i].tcp_thread) == 0) {
            threads[i].tcp_started = true;
        } else {
            fprintf(stderr, "failed to start bpf2socks TCP worker %u: errno=%d\n", i, errno);
            bpf2socks_stop_requested = 1;
            goto done;
        }
        if (config->enable_udp) {
            if (start_worker_thread(&workers[i], true, &threads[i].udp_thread) == 0) {
                threads[i].udp_started = true;
            } else {
                fprintf(stderr, "failed to start bpf2socks UDP worker %u: errno=%d\n", i, errno);
                bpf2socks_stop_requested = 1;
                goto done;
            }
        }
    }

    result = 0;

done:
    for (uint32_t i = 0; i < worker_count; ++i) {
        if (threads[i].tcp_started) pthread_join(threads[i].tcp_thread, NULL);
    }
    bpf2socks_stop_requested = 1;
    for (uint32_t i = 0; i < worker_count; ++i) {
        if (threads[i].udp_started) pthread_join(threads[i].udp_thread, NULL);
    }
    if (stats_started) pthread_join(stats_thread, NULL);
    close_workers(workers, worker_count);
    free(threads);
    free(workers);
    return result;
}

int bpf2socks_bridge_stats_dump(const char *pid_path, struct bpf2socks_bridge_stats *out) {
    if (out == NULL) return -1;
    memset(out, 0, sizeof(*out));
    char path[BPF2SOCKS_MAX_PATH_LEN];
    if (stats_path_from_pid_path(pid_path, path, sizeof(path)) < 0) return -1;
    FILE *file = fopen(path, "rb");
    if (file == NULL) {
        return errno == ENOENT ? 0 : -1;
    }
    size_t count = fread(out, sizeof(*out), 1U, file);
    int close_result = fclose(file);
    if (count != 1U || close_result != 0) {
        memset(out, 0, sizeof(*out));
        errno = EIO;
        return -1;
    }
    return 0;
}
