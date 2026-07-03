// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

#ifndef ASTERISKNG_BPF2SOCKS_H
#define ASTERISKNG_BPF2SOCKS_H

#include <linux/bpf.h>
#include <signal.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <sys/socket.h>
#include <sys/types.h>

#define BPF2SOCKS_MAX_UIDS 8192U
#define BPF2SOCKS_MAX_CIDR_MAP_ENTRIES 16384U
#define BPF2SOCKS_MAX_POLICY_CIDRS 512U
#define BPF2SOCKS_MAX_CIDR_TEXT_LEN 64U
#define BPF2SOCKS_MAX_INTERFACES 64U
#define BPF2SOCKS_MAX_INTERFACE_NAME_LEN 32U
#define BPF2SOCKS_MAX_PATH_LEN 512U
#define BPF2SOCKS_UID_SELECTED 1U
#define BPF2SOCKS_UID_BYPASS 2U
#define BPF2SOCKS_MAX_TOKEN_MAP_ENTRIES 65536U
#define BPF2SOCKS_DEFAULT_CGROUP_PATH "/sys/fs/cgroup"
#define BPF2SOCKS_DEFAULT_WORKER_COUNT 2U
#define BPF2SOCKS_MAX_WORKER_COUNT 8U
#define BPF2SOCKS_DEFAULT_TCP_BUFFER_SIZE 65536U
#define BPF2SOCKS_DEFAULT_UDP_RECV_BUFFER_SIZE 524288U
#define BPF2SOCKS_DEFAULT_UDP_BATCH_SIZE 10U
#define BPF2SOCKS_DEFAULT_UDP_MTU 1500U
#define BPF2SOCKS_DEFAULT_MAX_UDP_SESSIONS 4096U
#define BPF2SOCKS_DEFAULT_MAX_UDP_BINDINGS 16384U
#define BPF2SOCKS_DEFAULT_MAX_UDP_BINDINGS_PER_SESSION 64U
#define BPF2SOCKS_DEFAULT_UDP_IDLE_TIMEOUT_SECONDS 120U
#define BPF2SOCKS_DEFAULT_TOKEN_IPV6_PREFIX "fd7a:7374:6572:6973::/64"
#define BPF2SOCKS_TOKEN_IPV6_PREFIX_BITS 64U
#define BPF2SOCKS_SK_LOOKUP_KEY(family, protocol, worker) \
    ((((uint32_t)(worker) & 0xffffU) * 4U) + \
     ((uint32_t)(family) == AF_INET6 ? 2U : 0U) + \
     ((uint32_t)(protocol) == BPF2SOCKS_PROTO_UDP ? 1U : 0U))
#define BPF2SOCKS_SK_LOOKUP_MAX_SOCKS (BPF2SOCKS_MAX_WORKER_COUNT * 4U)

#ifndef MSG_ZEROCOPY
#define MSG_ZEROCOPY 0x4000000
#endif

#define BPF2SOCKS_MODE_BLACKLIST 0U
#define BPF2SOCKS_MODE_WHITELIST 1U
#define BPF2SOCKS_MODE_GLOBAL 2U

#define BPF2SOCKS_PROTO_TCP 6U
#define BPF2SOCKS_PROTO_UDP 17U

extern volatile sig_atomic_t bpf2socks_stop_requested;

struct bpf2socks_lpm4_key {
    uint32_t prefixlen;
    uint32_t addr;
};

struct bpf2socks_lpm6_key {
    uint32_t prefixlen;
    uint8_t addr[16];
};

struct bpf2socks_policy_config {
    uint32_t mode;
    bool bypass_direct_cidrs;
    bool enable_ipv6;
    bool enable_dns_hijack;
    char direct_cidr_path_v4[BPF2SOCKS_MAX_PATH_LEN];
    char direct_cidr_path_v6[BPF2SOCKS_MAX_PATH_LEN];
    uint32_t uids[BPF2SOCKS_MAX_UIDS];
    size_t uid_count;
    uint32_t bypass_uids[BPF2SOCKS_MAX_UIDS];
    size_t bypass_uid_count;
    bool self_bypass_gid_enabled;
    uint32_t self_bypass_gid;
    char hotspot_interface_prefixes[BPF2SOCKS_MAX_INTERFACES][BPF2SOCKS_MAX_INTERFACE_NAME_LEN];
    size_t hotspot_interface_prefix_count;
    char ignored_interfaces[BPF2SOCKS_MAX_INTERFACES][BPF2SOCKS_MAX_INTERFACE_NAME_LEN];
    size_t ignored_interface_count;
    char proxy_private_cidrs_v4[BPF2SOCKS_MAX_POLICY_CIDRS][BPF2SOCKS_MAX_CIDR_TEXT_LEN];
    size_t proxy_private_cidr_v4_count;
    char bypass_private_cidrs_v4[BPF2SOCKS_MAX_POLICY_CIDRS][BPF2SOCKS_MAX_CIDR_TEXT_LEN];
    size_t bypass_private_cidr_v4_count;
    char local_interface_cidrs_v4[BPF2SOCKS_MAX_POLICY_CIDRS][BPF2SOCKS_MAX_CIDR_TEXT_LEN];
    size_t local_interface_cidr_v4_count;
    char proxy_private_cidrs_v6[BPF2SOCKS_MAX_POLICY_CIDRS][BPF2SOCKS_MAX_CIDR_TEXT_LEN];
    size_t proxy_private_cidr_v6_count;
    char bypass_private_cidrs_v6[BPF2SOCKS_MAX_POLICY_CIDRS][BPF2SOCKS_MAX_CIDR_TEXT_LEN];
    size_t bypass_private_cidr_v6_count;
    char local_interface_cidrs_v6[BPF2SOCKS_MAX_POLICY_CIDRS][BPF2SOCKS_MAX_CIDR_TEXT_LEN];
    size_t local_interface_cidr_v6_count;
};

struct bpf2socks_sockaddr {
    int family;
    uint16_t port;
    uint8_t addr[16];
};

struct bpf2socks_udp_msg {
    struct bpf2socks_sockaddr addr;
    uint8_t *payload;
    size_t payload_len;
};

struct bpf2socks_token_key {
    uint8_t family;
    uint8_t protocol;
    uint16_t token_port;
    uint8_t token_addr[16];
    uint16_t client_port;
    uint16_t reserved;
    uint8_t client_addr[16];
};

struct bpf2socks_original_dst {
    uint8_t family;
    uint8_t protocol;
    uint16_t port;
    uint8_t addr[16];
};

struct bpf2socks_runtime_config {
    char socks_host[128];
    uint16_t socks_port;
    char listen_host[64];
    uint16_t listen_port;
    char pinned_object_dir[BPF2SOCKS_MAX_PATH_LEN];
    char prerouting_policy_ipv4_path[BPF2SOCKS_MAX_PATH_LEN];
    char prerouting_policy_ipv6_path[BPF2SOCKS_MAX_PATH_LEN];
    char cgroup_path[BPF2SOCKS_MAX_PATH_LEN];
    bool enable_udp;
    bool enable_ipv6;
    bool debug_stats;
    uint8_t token_ipv6_prefix[16];
    uint32_t token_ipv6_prefix_bits;
    uint32_t worker_count;
    uint32_t tcp_buffer_size;
    uint32_t udp_recv_buffer_size;
    uint32_t udp_batch_size;
    uint32_t udp_mtu;
    uint32_t max_udp_sessions;
    uint32_t max_udp_bindings;
    uint32_t max_udp_bindings_per_session;
    uint32_t udp_idle_timeout_seconds;
    int token_map_fd;
    int sk_lookup_sock_map_fd;
};

struct bpf2socks_bridge_stats {
    uint64_t tcp_accepts;
    uint64_t tcp_connect_failures;
    uint64_t tcp_bytes_client_to_upstream;
    uint64_t tcp_bytes_upstream_to_client;
    uint64_t udp_packets_from_client;
    uint64_t udp_packets_to_upstream;
    uint64_t udp_packets_from_upstream;
    uint64_t udp_packets_to_client;
    uint64_t udp_token_misses;
    uint64_t udp_session_hits;
    uint64_t udp_session_misses;
    uint64_t udp_session_evictions;
    uint64_t udp_associate_creates;
    uint64_t udp_associate_reuses;
    uint64_t udp_reply_binding_creates;
    uint64_t udp_reply_binding_hits;
    uint64_t udp_fullcone_binding_creates;
    uint64_t udp_binding_evictions;
    uint64_t udp_drops_malformed_socks5;
    uint64_t udp_drops_oversized;
    uint64_t udp_send_errors;
};

int bpf2socks_bridge_stats_dump(const char *pid_path, struct bpf2socks_bridge_stats *out);

struct bpf2socks_interface_runtime;

struct bpf2socks_bpf_runtime {
    int cgroup_fd;
    int uid_map_fd;
    int proxy_cidr4_map_fd;
    int bypass_cidr4_map_fd;
    int ignored_ifindex_map_fd;
    int ignored_route_cidr4_map_fd;
    int cidr6_map_fd;
    int token_map_fd;
    int sk_lookup_sock_map_fd;
    int sk_lookup_prog_fd;
    int sk_lookup_link_fd;
    int connect4_prog_fd;
    int connect6_prog_fd;
    int connect6_v4mapped_prog_fd;
    int udp4_sendmsg_prog_fd;
    int udp6_sendmsg_prog_fd;
    int udp4_recvmsg_prog_fd;
    int udp6_recvmsg_prog_fd;
    struct bpf2socks_interface_runtime *interfaces;
};

int bpf2socks_load_uid_map(int uid_map_fd, const struct bpf2socks_policy_config *policy);
int bpf2socks_load_direct_cidrs(int map_fd, const char *path, int expected_family);
int bpf2socks_load_cidr_strings(int map_fd, const char cidrs[][BPF2SOCKS_MAX_CIDR_TEXT_LEN], size_t count, int expected_family);
int bpf2socks_parse_ipv4_cidr_host(const char *cidr, uint32_t *base, uint32_t *host_bits);

long bpf2socks_bpf_sys(enum bpf_cmd cmd, union bpf_attr *attr, unsigned int size);
int bpf2socks_create_map(enum bpf_map_type type, uint32_t key_size, uint32_t value_size, uint32_t max_entries, uint32_t flags);
int bpf2socks_update_map(int map_fd, const void *key, const void *value);
int bpf2socks_lookup_map(int map_fd, const void *key, void *value);
int bpf2socks_get_next_key(int map_fd, const void *key, void *next_key);
int bpf2socks_load_prog(
    const struct bpf_insn *insns,
    size_t insn_count,
    const char *name,
    enum bpf_prog_type prog_type,
    enum bpf_attach_type expected_attach_type,
    bool log_error);
int bpf2socks_attach_prog(int cgroup_fd, int prog_fd, enum bpf_attach_type attach_type);
int bpf2socks_detach_prog(int cgroup_fd, int prog_fd, enum bpf_attach_type attach_type);
int bpf2socks_detach_named_progs(int cgroup_fd);
int bpf2socks_detach_cgroup_path(const char *cgroup_path);
int bpf2socks_delete_map(int map_fd, const void *key);
int bpf2socks_pin_fd(int fd, const char *path);
int bpf2socks_link_create(int prog_fd, int target_fd, enum bpf_attach_type attach_type, uint32_t flags);

int bpf2socks_token_lookup(int map_fd, const struct bpf2socks_token_key *key, struct bpf2socks_original_dst *out);
int bpf2socks_resolve_tcp_addr(const char *host, uint16_t port, struct sockaddr_storage *addr, socklen_t *addr_len);
int bpf2socks_socks5_connect(const char *host, uint16_t port, const struct bpf2socks_sockaddr *dst);
int bpf2socks_socks5_udp_associate(
    const char *host,
    uint16_t port,
    int *tcp_fd,
    int *udp_fd,
    struct sockaddr_storage *relay_addr,
    socklen_t *relay_addr_len);
int bpf2socks_socks5_udp_associate_addr(
    const struct sockaddr *socks_addr,
    socklen_t socks_addr_len,
    int *tcp_fd,
    int *udp_fd,
    struct sockaddr_storage *relay_addr,
    socklen_t *relay_addr_len);
int bpf2socks_socks5_udp_send(
    int udp_fd,
    const struct sockaddr *relay_addr,
    socklen_t relay_addr_len,
    const struct bpf2socks_sockaddr *dst,
    const uint8_t *payload,
    size_t payload_len);
ssize_t bpf2socks_socks5_udp_recv(
    int udp_fd,
    struct bpf2socks_sockaddr *src,
    uint8_t *payload,
    size_t payload_cap);
int bpf2socks_socks5_udp_sendmmsg(
    int udp_fd,
    const struct sockaddr *relay_addr,
    socklen_t relay_addr_len,
    const struct bpf2socks_udp_msg *messages,
    unsigned int count);
int bpf2socks_socks5_udp_recvmmsg(
    int udp_fd,
    struct bpf2socks_udp_msg *messages,
    unsigned int count,
    uint8_t *packet_buffer,
    size_t packet_stride);
int bpf2socks_splice_probe(char *message, size_t message_size);
int bpf2socks_advanced_socket_probe(char *message, size_t message_size);
int bpf2socks_bridge_run(const struct bpf2socks_runtime_config *config, const char *pid_path);

int bpf2socks_sk_lookup_probe(bool enable_ipv6, char *message, size_t message_size);
int bpf2socks_sk_lookup_start(
    const struct bpf2socks_policy_config *policy,
    const struct bpf2socks_runtime_config *config,
    struct bpf2socks_bpf_runtime *runtime);
int bpf2socks_sk_lookup_register_worker_sockets(
    int sock_map_fd,
    uint32_t worker_id,
    int tcp4_fd,
    int udp4_fd,
    int tcp6_fd,
    int udp6_fd);

int bpf2socks_prerouting_policy_probe(bool enable_ipv6, char *message, size_t message_size);
int bpf2socks_prerouting_policy_prepare(
    const struct bpf2socks_policy_config *policy,
    const struct bpf2socks_runtime_config *config);

int bpf2socks_interface_policy_start(
    const struct bpf2socks_policy_config *policy,
    struct bpf2socks_bpf_runtime *runtime);
void bpf2socks_interface_policy_stop(struct bpf2socks_bpf_runtime *runtime);

int bpf2socks_bpf_probe(
    const struct bpf2socks_runtime_config *config,
    const struct bpf2socks_policy_config *policy,
    char *message,
    size_t message_size);
int bpf2socks_bpf_start(
    const struct bpf2socks_runtime_config *config,
    const struct bpf2socks_policy_config *policy,
    struct bpf2socks_bpf_runtime *runtime);
void bpf2socks_bpf_stop(struct bpf2socks_bpf_runtime *runtime);

#endif
