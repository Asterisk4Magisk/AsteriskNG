// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

#include "bpf2socks.h"

#include <arpa/inet.h>
#include <errno.h>
#include <inttypes.h>
#include <signal.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

volatile sig_atomic_t bpf2socks_stop_requested = 0;

static void on_signal(int signo) {
    (void)signo;
    bpf2socks_stop_requested = 1;
}

static void install_signal_handlers(void) {
    struct sigaction action;
    memset(&action, 0, sizeof(action));
    action.sa_handler = on_signal;
    sigemptyset(&action.sa_mask);
    action.sa_flags = 0;
    (void)sigaction(SIGTERM, &action, NULL);
    (void)sigaction(SIGINT, &action, NULL);
    action.sa_handler = SIG_IGN;
    (void)sigaction(SIGPIPE, &action, NULL);
}

static char *read_file(const char *path) {
    FILE *file = fopen(path, "rb");
    if (file == NULL) return NULL;
    if (fseek(file, 0, SEEK_END) != 0) {
        fclose(file);
        return NULL;
    }
    long length = ftell(file);
    if (length < 0) {
        fclose(file);
        return NULL;
    }
    rewind(file);
    char *data = calloc((size_t)length + 1U, 1U);
    if (data == NULL) {
        fclose(file);
        return NULL;
    }
    if (length > 0 && fread(data, 1U, (size_t)length, file) != (size_t)length) {
        free(data);
        fclose(file);
        return NULL;
    }
    fclose(file);
    return data;
}

static const char *json_value_pos(const char *json, const char *key) {
    char needle[96];
    snprintf(needle, sizeof(needle), "\"%s\"", key);
    const char *pos = strstr(json, needle);
    if (pos == NULL) return NULL;
    pos = strchr(pos, ':');
    if (pos == NULL) return NULL;
    return pos + 1;
}

static uint32_t json_uint(const char *json, const char *key, uint32_t fallback) {
    const char *pos = json_value_pos(json, key);
    if (pos == NULL) return fallback;
    return (uint32_t)strtoul(pos, NULL, 10);
}

static bool json_bool(const char *json, const char *key, bool fallback) {
    const char *pos = json_value_pos(json, key);
    if (pos == NULL) return fallback;
    while (*pos == ' ' || *pos == '\n' || *pos == '\t' || *pos == '\r') ++pos;
    if (strncmp(pos, "true", 4) == 0) return true;
    if (strncmp(pos, "false", 5) == 0) return false;
    return fallback;
}

static bool env_bool_enabled(const char *key) {
    const char *value = getenv(key);
    return value != NULL && (strcmp(value, "1") == 0 || strcmp(value, "true") == 0 || strcmp(value, "TRUE") == 0);
}

static bool json_string(const char *json, const char *key, char *out, size_t out_size) {
    const char *pos = json_value_pos(json, key);
    if (pos == NULL) return false;
    pos = strchr(pos, '"');
    if (pos == NULL) return false;
    ++pos;
    const char *end = strchr(pos, '"');
    if (end == NULL) return false;
    size_t length = (size_t)(end - pos);
    if (length >= out_size) length = out_size - 1U;
    memcpy(out, pos, length);
    out[length] = '\0';
    return true;
}

static void json_uint_array(const char *json, const char *key, uint32_t *out, size_t *count, size_t max_count) {
    *count = 0;
    const char *pos = json_value_pos(json, key);
    if (pos == NULL) return;
    pos = strchr(pos, '[');
    if (pos == NULL) return;
    ++pos;
    while (*pos != '\0' && *pos != ']' && *count < max_count) {
        while (*pos == ' ' || *pos == '\n' || *pos == '\t' || *pos == '\r' || *pos == ',') ++pos;
        if (*pos == ']') break;
        char *end = NULL;
        unsigned long value = strtoul(pos, &end, 10);
        if (end == pos) break;
        out[(*count)++] = (uint32_t)value;
        pos = end;
    }
}

static void json_string_array(
    const char *json,
    const char *key,
    char *out,
    size_t row_size,
    size_t *count,
    size_t max_count) {
    *count = 0;
    const char *pos = json_value_pos(json, key);
    if (pos == NULL) return;
    pos = strchr(pos, '[');
    if (pos == NULL) return;
    ++pos;
    while (*pos != '\0' && *pos != ']' && *count < max_count) {
        while (*pos == ' ' || *pos == '\n' || *pos == '\t' || *pos == '\r' || *pos == ',') ++pos;
        if (*pos == ']') break;
        if (*pos != '"') break;
        ++pos;
        const char *end = strchr(pos, '"');
        if (end == NULL) break;
        size_t length = (size_t)(end - pos);
        if (length >= row_size) length = row_size - 1U;
        char *slot = out + (*count * row_size);
        memcpy(slot, pos, length);
        slot[length] = '\0';
        ++(*count);
        pos = end + 1;
    }
}

static int parse_ipv6_prefix64(const char *cidr, uint8_t out[16], uint32_t *prefix_bits) {
    if (cidr == NULL || out == NULL || prefix_bits == NULL) return -1;
    char buffer[128];
    snprintf(buffer, sizeof(buffer), "%s", cidr);
    char *slash = strchr(buffer, '/');
    if (slash == NULL) return -1;
    *slash++ = '\0';
    char *end = NULL;
    errno = 0;
    unsigned long prefix = strtoul(slash, &end, 10);
    if (errno != 0 || end == slash || *end != '\0' || prefix != BPF2SOCKS_TOKEN_IPV6_PREFIX_BITS) {
        errno = EINVAL;
        return -1;
    }
    if (inet_pton(AF_INET6, buffer, out) != 1) {
        errno = EINVAL;
        return -1;
    }
    memset(out + 8U, 0, 8U);
    *prefix_bits = (uint32_t)prefix;
    return 0;
}

static void init_runtime_config_defaults(struct bpf2socks_runtime_config *config) {
    memset(config, 0, sizeof(*config));
    config->token_map_fd = -1;
    config->sk_lookup_sock_map_fd = -1;
    (void)parse_ipv6_prefix64(
        BPF2SOCKS_DEFAULT_TOKEN_IPV6_PREFIX,
        config->token_ipv6_prefix,
        &config->token_ipv6_prefix_bits);
    snprintf(config->cgroup_path, sizeof(config->cgroup_path), "%s", BPF2SOCKS_DEFAULT_CGROUP_PATH);
    config->enable_udp = true;
    config->worker_count = 0U;
    config->tcp_buffer_size = BPF2SOCKS_DEFAULT_TCP_BUFFER_SIZE;
    config->udp_recv_buffer_size = BPF2SOCKS_DEFAULT_UDP_RECV_BUFFER_SIZE;
    config->udp_batch_size = BPF2SOCKS_DEFAULT_UDP_BATCH_SIZE;
    config->udp_mtu = BPF2SOCKS_DEFAULT_UDP_MTU;
    config->max_udp_sessions = BPF2SOCKS_DEFAULT_MAX_UDP_SESSIONS;
    config->max_udp_bindings = BPF2SOCKS_DEFAULT_MAX_UDP_BINDINGS;
    config->udp_idle_timeout_seconds = BPF2SOCKS_DEFAULT_UDP_IDLE_TIMEOUT_SECONDS;
    config->max_udp_pending_bytes = BPF2SOCKS_DEFAULT_MAX_UDP_PENDING_BYTES;
    config->dns_transaction_timeout_milliseconds = BPF2SOCKS_DEFAULT_DNS_TRANSACTION_TIMEOUT_MILLISECONDS;
}

static void init_policy_config_defaults(struct bpf2socks_policy_config *policy) {
    memset(policy, 0, sizeof(*policy));
    policy->mode = BPF2SOCKS_MODE_GLOBAL;
    policy->self_bypass_gid_enabled = true;
    policy->self_bypass_gid = (uint32_t)getegid();
}

static uint32_t auto_worker_count(void) {
    long cpu_count = sysconf(_SC_NPROCESSORS_ONLN);
    if (cpu_count >= 8L) return 4U;
    return BPF2SOCKS_DEFAULT_WORKER_COUNT;
}

static void normalize_runtime_tunables(struct bpf2socks_runtime_config *config) {
    if (config->worker_count == 0U) config->worker_count = auto_worker_count();
    if (config->worker_count > BPF2SOCKS_MAX_WORKER_COUNT) config->worker_count = BPF2SOCKS_MAX_WORKER_COUNT;
    if (config->tcp_buffer_size == 0U) config->tcp_buffer_size = BPF2SOCKS_DEFAULT_TCP_BUFFER_SIZE;
    if (config->udp_recv_buffer_size == 0U) config->udp_recv_buffer_size = BPF2SOCKS_DEFAULT_UDP_RECV_BUFFER_SIZE;
    if (config->udp_batch_size == 0U) config->udp_batch_size = BPF2SOCKS_DEFAULT_UDP_BATCH_SIZE;
    if (config->udp_mtu == 0U) config->udp_mtu = BPF2SOCKS_DEFAULT_UDP_MTU;
    if (config->max_udp_sessions == 0U) config->max_udp_sessions = BPF2SOCKS_DEFAULT_MAX_UDP_SESSIONS;
    if (config->max_udp_bindings == 0U) config->max_udp_bindings = BPF2SOCKS_DEFAULT_MAX_UDP_BINDINGS;
    if (config->udp_idle_timeout_seconds == 0U) {
        config->udp_idle_timeout_seconds = BPF2SOCKS_DEFAULT_UDP_IDLE_TIMEOUT_SECONDS;
    }
    if (config->max_udp_pending_bytes == 0U) {
        config->max_udp_pending_bytes = BPF2SOCKS_DEFAULT_MAX_UDP_PENDING_BYTES;
    }
    if (config->max_udp_pending_bytes < BPF2SOCKS_MIN_UDP_PENDING_BYTES) {
        config->max_udp_pending_bytes = BPF2SOCKS_MIN_UDP_PENDING_BYTES;
    }
    if (config->max_udp_pending_bytes > BPF2SOCKS_MAX_UDP_PENDING_BYTES) {
        config->max_udp_pending_bytes = BPF2SOCKS_MAX_UDP_PENDING_BYTES;
    }
    if (config->dns_transaction_timeout_milliseconds == 0U) {
        config->dns_transaction_timeout_milliseconds = BPF2SOCKS_DEFAULT_DNS_TRANSACTION_TIMEOUT_MILLISECONDS;
    }
    if (config->dns_transaction_timeout_milliseconds < BPF2SOCKS_MIN_DNS_TRANSACTION_TIMEOUT_MILLISECONDS) {
        config->dns_transaction_timeout_milliseconds = BPF2SOCKS_MIN_DNS_TRANSACTION_TIMEOUT_MILLISECONDS;
    }
    if (config->dns_transaction_timeout_milliseconds > BPF2SOCKS_MAX_DNS_TRANSACTION_TIMEOUT_MILLISECONDS) {
        config->dns_transaction_timeout_milliseconds = BPF2SOCKS_MAX_DNS_TRANSACTION_TIMEOUT_MILLISECONDS;
    }
    if (config->max_udp_sessions < config->worker_count) config->worker_count = config->max_udp_sessions;
    if (config->max_udp_bindings < config->worker_count) config->worker_count = config->max_udp_bindings;
}

static int load_runtime_config(
    const char *path,
    struct bpf2socks_runtime_config *config,
    struct bpf2socks_policy_config *policy) {
    init_runtime_config_defaults(config);
    init_policy_config_defaults(policy);
    char *json = read_file(path);
    if (json == NULL) {
        fprintf(stderr, "failed to read config: %s\n", path);
        return -1;
    }

    bool ok = true;
    ok = json_string(json, "socksHost", config->socks_host, sizeof(config->socks_host)) && ok;
    config->socks_port = (uint16_t)json_uint(json, "socksPort", 0U);
    ok = json_string(json, "bridgeListenAddress", config->listen_host, sizeof(config->listen_host)) && ok;
    config->listen_port = (uint16_t)json_uint(json, "bridgePort", 0U);
    ok = json_string(json, "pinnedObjectDir", config->pinned_object_dir, sizeof(config->pinned_object_dir)) && ok;
    (void)json_string(
        json,
        "preroutingPolicyIpv4Path",
        config->prerouting_policy_ipv4_path,
        sizeof(config->prerouting_policy_ipv4_path));
    (void)json_string(
        json,
        "preroutingPolicyIpv6Path",
        config->prerouting_policy_ipv6_path,
        sizeof(config->prerouting_policy_ipv6_path));
    if (!json_string(json, "cgroupPath", config->cgroup_path, sizeof(config->cgroup_path))) {
        snprintf(config->cgroup_path, sizeof(config->cgroup_path), "%s", BPF2SOCKS_DEFAULT_CGROUP_PATH);
    }
    config->enable_udp = json_bool(json, "enableUdp", true);
    config->enable_ipv6 = json_bool(json, "enableIpv6", false);
    config->enable_dns_hijack = json_bool(json, "enableDnsHijack", false);
    config->debug_stats = json_bool(json, "debugStats", false) || env_bool_enabled("BPF2SOCKS_DEBUG_STATS");
    char token_ipv6_prefix[128];
    if (json_string(json, "tokenIpv6Prefix", token_ipv6_prefix, sizeof(token_ipv6_prefix)) &&
        parse_ipv6_prefix64(token_ipv6_prefix, config->token_ipv6_prefix, &config->token_ipv6_prefix_bits) < 0) {
        fprintf(stderr, "invalid bpf2socks IPv6 token prefix: %s\n", token_ipv6_prefix);
        ok = false;
    }
    config->worker_count = json_uint(json, "workerCount", config->worker_count);
    config->tcp_buffer_size = json_uint(json, "tcpBufferSize", config->tcp_buffer_size);
    config->udp_recv_buffer_size = json_uint(json, "udpRecvBufferSize", config->udp_recv_buffer_size);
    config->udp_batch_size = json_uint(json, "udpBatchSize", config->udp_batch_size);
    config->udp_mtu = json_uint(json, "udpMtu", config->udp_mtu);
    config->max_udp_sessions = json_uint(json, "maxUdpSessions", config->max_udp_sessions);
    config->max_udp_bindings = json_uint(json, "maxUdpBindings", config->max_udp_bindings);
    config->udp_idle_timeout_seconds = json_uint(
        json,
        "udpIdleTimeoutSeconds",
        config->udp_idle_timeout_seconds);
    config->max_udp_pending_bytes = json_uint(
        json,
        "maxUdpPendingBytes",
        config->max_udp_pending_bytes);
    config->dns_transaction_timeout_milliseconds = json_uint(
        json,
        "dnsTransactionTimeoutMilliseconds",
        config->dns_transaction_timeout_milliseconds);
    normalize_runtime_tunables(config);

    const char *policy_json = json;
    const char *policy_value = json_value_pos(json, "policy");
    if (policy_value != NULL) {
        const char *policy_object = strchr(policy_value, '{');
        if (policy_object != NULL) policy_json = policy_object;
    }
    policy->mode = json_uint(policy_json, "mode", BPF2SOCKS_MODE_GLOBAL);
    policy->bypass_direct_cidrs = json_bool(policy_json, "bypassDirectCidrs", false);
    policy->enable_ipv6 = config->enable_ipv6;
    policy->enable_dns_hijack = config->enable_dns_hijack;
    (void)json_string(policy_json, "directCidrPathV4", policy->direct_cidr_path_v4, sizeof(policy->direct_cidr_path_v4));
    (void)json_string(policy_json, "directCidrPathV6", policy->direct_cidr_path_v6, sizeof(policy->direct_cidr_path_v6));
    json_uint_array(policy_json, "uids", policy->uids, &policy->uid_count, BPF2SOCKS_MAX_UIDS);
    json_uint_array(policy_json, "bypassUids", policy->bypass_uids, &policy->bypass_uid_count, BPF2SOCKS_MAX_UIDS);
    json_string_array(
        json,
        "hotspotInterfacePrefixes",
        (char *)policy->hotspot_interface_prefixes,
        BPF2SOCKS_MAX_INTERFACE_NAME_LEN,
        &policy->hotspot_interface_prefix_count,
        BPF2SOCKS_MAX_INTERFACES);
    json_string_array(
        json,
        "ignoredInterfaces",
        (char *)policy->ignored_interfaces,
        BPF2SOCKS_MAX_INTERFACE_NAME_LEN,
        &policy->ignored_interface_count,
        BPF2SOCKS_MAX_INTERFACES);
    json_string_array(
        json,
        "proxyPrivateCidrsV4",
        (char *)policy->proxy_private_cidrs_v4,
        BPF2SOCKS_MAX_CIDR_TEXT_LEN,
        &policy->proxy_private_cidr_v4_count,
        BPF2SOCKS_MAX_POLICY_CIDRS);
    json_string_array(
        json,
        "bypassPrivateCidrsV4",
        (char *)policy->bypass_private_cidrs_v4,
        BPF2SOCKS_MAX_CIDR_TEXT_LEN,
        &policy->bypass_private_cidr_v4_count,
        BPF2SOCKS_MAX_POLICY_CIDRS);
    json_string_array(
        json,
        "localInterfaceCidrsV4",
        (char *)policy->local_interface_cidrs_v4,
        BPF2SOCKS_MAX_CIDR_TEXT_LEN,
        &policy->local_interface_cidr_v4_count,
        BPF2SOCKS_MAX_POLICY_CIDRS);
    json_string_array(
        json,
        "proxyPrivateCidrsV6",
        (char *)policy->proxy_private_cidrs_v6,
        BPF2SOCKS_MAX_CIDR_TEXT_LEN,
        &policy->proxy_private_cidr_v6_count,
        BPF2SOCKS_MAX_POLICY_CIDRS);
    json_string_array(
        json,
        "bypassPrivateCidrsV6",
        (char *)policy->bypass_private_cidrs_v6,
        BPF2SOCKS_MAX_CIDR_TEXT_LEN,
        &policy->bypass_private_cidr_v6_count,
        BPF2SOCKS_MAX_POLICY_CIDRS);
    json_string_array(
        json,
        "localInterfaceCidrsV6",
        (char *)policy->local_interface_cidrs_v6,
        BPF2SOCKS_MAX_CIDR_TEXT_LEN,
        &policy->local_interface_cidr_v6_count,
        BPF2SOCKS_MAX_POLICY_CIDRS);

    free(json);
    if (!ok || config->socks_port == 0U || config->listen_port == 0U) {
        fprintf(stderr, "invalid bpf2socks config: %s\n", path);
        return -1;
    }
    return 0;
}

static const char *arg_value(int argc, char **argv, const char *name) {
    for (int i = 1; i + 1 < argc; ++i) {
        if (strcmp(argv[i], name) == 0) return argv[i + 1];
    }
    return NULL;
}

static const char *config_path_arg(int argc, char **argv) {
    return arg_value(argc, argv, "--config");
}

static const char *pid_path_arg(int argc, char **argv) {
    return arg_value(argc, argv, "--pid");
}

static void print_json_string(const char *value) {
    putchar('"');
    if (value != NULL) {
        for (const char *ptr = value; *ptr != '\0'; ++ptr) {
            if (*ptr == '"' || *ptr == '\\') {
                putchar('\\');
                putchar(*ptr);
            } else if ((unsigned char)*ptr < 0x20U) {
                printf("\\u%04x", (unsigned int)(unsigned char)*ptr);
            } else {
                putchar(*ptr);
            }
        }
    }
    putchar('"');
}

static void print_probe_json(
    bool supported,
    const char *message,
    bool splice_supported,
    bool advanced_socket_supported,
    bool bpf_supported,
    bool hotspot_policy) {
    printf("{\"supported\":%s,\"message\":", supported ? "true" : "false");
    print_json_string(message);
    if (splice_supported) {
        printf(
            ",\"capabilities\":{\"splice\":true,"
            "\"advancedSockets\":%s,"
            "\"bpf\":%s,"
            "\"skLookup\":%s,"
            "\"prerouting\":%s,"
            "\"hotspotPolicy\":%s}}\n",
            advanced_socket_supported ? "true" : "false",
            bpf_supported ? "true" : "false",
            (hotspot_policy && bpf_supported && advanced_socket_supported) ? "true" : "false",
            (hotspot_policy && bpf_supported && advanced_socket_supported) ? "true" : "false",
            hotspot_policy ? "true" : "false");
    } else {
        printf(
            ",\"capabilities\":{\"splice\":false,"
            "\"advancedSockets\":%s,"
            "\"bpf\":%s,"
            "\"skLookup\":false,"
            "\"prerouting\":false,"
            "\"hotspotPolicy\":%s}}\n",
            advanced_socket_supported ? "true" : "false",
            bpf_supported ? "true" : "false",
            hotspot_policy ? "true" : "false");
    }
}

static int probe_with_config(const char *path) {
    struct bpf2socks_runtime_config config;
    struct bpf2socks_policy_config policy;
    char message[256];
    if (path != NULL) {
        if (load_runtime_config(path, &config, &policy) < 0) {
            print_probe_json(false, "invalid bpf2socks config", false, false, false, false);
            return 1;
        }
    } else {
        init_runtime_config_defaults(&config);
        init_policy_config_defaults(&policy);
        config.listen_port = 65532U;
    }

    bool hotspot_policy = policy.hotspot_interface_prefix_count > 0U;
    if (bpf2socks_splice_probe(message, sizeof(message)) < 0) {
        print_probe_json(false, message, false, false, false, hotspot_policy);
        return 1;
    }
    if (bpf2socks_advanced_socket_probe(message, sizeof(message)) < 0) {
        print_probe_json(false, message, true, false, false, hotspot_policy);
        return 1;
    }
    if (bpf2socks_bpf_probe(&config, &policy, message, sizeof(message)) == 0) {
        print_probe_json(true, "ok", true, true, true, hotspot_policy);
        return 0;
    }
    print_probe_json(false, message, true, true, false, hotspot_policy);
    return 1;
}

static void cleanup_prerouting_policy_pin(const char *configured_path, const char *dir, const char *name) {
    char fallback[BPF2SOCKS_MAX_PATH_LEN];
    const char *base_dir = dir != NULL && dir[0] != '\0' ? dir : "/sys/fs/bpf/asteriskng/bpf2socks";
    snprintf(fallback, sizeof(fallback), "%s/%s", base_dir, name);
    if (configured_path != NULL && configured_path[0] != '\0') {
        (void)unlink(configured_path);
    }
    (void)unlink(fallback);
}

static void cleanup_prerouting_policy_pins(const struct bpf2socks_runtime_config *config) {
    if (config == NULL) return;
    cleanup_prerouting_policy_pin(
        config->prerouting_policy_ipv4_path,
        config->pinned_object_dir,
        "prerouting_v4");
    cleanup_prerouting_policy_pin(
        config->prerouting_policy_ipv6_path,
        config->pinned_object_dir,
        "prerouting_v6");
    cleanup_prerouting_policy_pin(NULL, config->pinned_object_dir, "probe_prerouting_v4");
    cleanup_prerouting_policy_pin(NULL, config->pinned_object_dir, "probe_prerouting_v6");
}

static int start_with_config(const char *path, const char *pid_path) {
    struct bpf2socks_runtime_config config;
    struct bpf2socks_policy_config policy;
    if (load_runtime_config(path, &config, &policy) < 0) return 2;
    if (bpf2socks_raise_nofile_limit(BPF2SOCKS_DEFAULT_NOFILE_LIMIT) != 0) {
        fprintf(stderr, "failed to raise bpf2socks file descriptor limit: errno=%d\n", errno);
    }

    char message[256];
    if (bpf2socks_splice_probe(message, sizeof(message)) < 0) {
        fprintf(stderr, "bpf2socks splice relay is unavailable: %s\n", message);
        return 1;
    }
    if (bpf2socks_advanced_socket_probe(message, sizeof(message)) < 0) {
        fprintf(stderr, "bpf2socks advanced socket path is unavailable: %s\n", message);
        return 1;
    }

    if (bpf2socks_prerouting_policy_prepare(&policy, &config) < 0) {
        int saved_errno = errno;
        cleanup_prerouting_policy_pins(&config);
        errno = saved_errno;
        fprintf(stderr, "failed to prepare bpf2socks PREROUTING policy: errno=%d\n", errno);
        return 1;
    }

    struct bpf2socks_bpf_runtime runtime;
    if (bpf2socks_bpf_start(&config, &policy, &runtime) < 0) {
        int saved_errno = errno;
        cleanup_prerouting_policy_pins(&config);
        errno = saved_errno;
        fprintf(stderr, "failed to start bpf2socks BPF runtime: errno=%d\n", errno);
        return 1;
    }

    install_signal_handlers();
    config.token_map_fd = runtime.token_map_fd;
    config.sk_lookup_sock_map_fd = runtime.sk_lookup_sock_map_fd;
    int result = bpf2socks_bridge_run(&config, pid_path);
    bpf2socks_bpf_stop(&runtime);
    return result == 0 ? 0 : 1;
}

static int stop_with_config(const char *path, const char *pid_path) {
    struct bpf2socks_runtime_config config;
    struct bpf2socks_policy_config policy;
    if (load_runtime_config(path, &config, &policy) < 0) return 0;
    FILE *file = fopen(pid_path, "r");
    if (file != NULL) {
        long pid = 0;
        if (fscanf(file, "%ld", &pid) == 1 && pid > 1L) {
            (void)kill((pid_t)pid, SIGTERM);
        }
        fclose(file);
    }
    (void)bpf2socks_detach_cgroup_path(config.cgroup_path);
    cleanup_prerouting_policy_pins(&config);
    return 0;
}

static void print_bridge_stats_json(const struct bpf2socks_bridge_stats *stats) {
    printf(
        "{"
        "\"tcpAccepts\":%" PRIu64 ","
        "\"tcpConnectFailures\":%" PRIu64 ","
        "\"tcpBytesClientToUpstream\":%" PRIu64 ","
        "\"tcpBytesUpstreamToClient\":%" PRIu64 ","
        "\"udpPacketsFromClient\":%" PRIu64 ","
        "\"udpPacketsToUpstream\":%" PRIu64 ","
        "\"udpPacketsFromUpstream\":%" PRIu64 ","
        "\"udpPacketsToClient\":%" PRIu64 ","
        "\"udpTokenMisses\":%" PRIu64 ","
        "\"udpSessionHits\":%" PRIu64 ","
        "\"udpSessionMisses\":%" PRIu64 ","
        "\"udpSessionEvictions\":%" PRIu64 ","
        "\"udpAssociateCreates\":%" PRIu64 ","
        "\"udpAssociateReuses\":%" PRIu64 ","
        "\"udpReplyBindingCreates\":%" PRIu64 ","
        "\"udpReplyBindingHits\":%" PRIu64 ","
        "\"udpFullconeBindingCreates\":%" PRIu64 ","
        "\"udpBindingEvictions\":%" PRIu64 ","
        "\"udpDropsMalformedSocks5\":%" PRIu64 ","
        "\"udpDropsOversized\":%" PRIu64 ","
        "\"udpSendErrors\":%" PRIu64
        "}\n",
        stats->tcp_accepts,
        stats->tcp_connect_failures,
        stats->tcp_bytes_client_to_upstream,
        stats->tcp_bytes_upstream_to_client,
        stats->udp_packets_from_client,
        stats->udp_packets_to_upstream,
        stats->udp_packets_from_upstream,
        stats->udp_packets_to_client,
        stats->udp_token_misses,
        stats->udp_session_hits,
        stats->udp_session_misses,
        stats->udp_session_evictions,
        stats->udp_associate_creates,
        stats->udp_associate_reuses,
        stats->udp_reply_binding_creates,
        stats->udp_reply_binding_hits,
        stats->udp_fullcone_binding_creates,
        stats->udp_binding_evictions,
        stats->udp_drops_malformed_socks5,
        stats->udp_drops_oversized,
        stats->udp_send_errors);
}

static int stats_with_pid(const char *pid_path) {
    struct bpf2socks_bridge_stats stats;
    if (bpf2socks_bridge_stats_dump(pid_path, &stats) < 0) {
        fprintf(stderr, "failed to read bpf2socks bridge stats: errno=%d\n", errno);
        return 1;
    }
    print_bridge_stats_json(&stats);
    return 0;
}

int main(int argc, char **argv) {
    if (argc >= 2 && strcmp(argv[1], "--probe") == 0) {
        return probe_with_config(config_path_arg(argc, argv));
    }
    if (argc >= 2 && strcmp(argv[1], "--start") == 0) {
        const char *path = config_path_arg(argc, argv);
        const char *pid_path = pid_path_arg(argc, argv);
        if (path == NULL || pid_path == NULL) {
            fprintf(stderr, "--start requires --config FILE --pid FILE\n");
            return 2;
        }
        return start_with_config(path, pid_path);
    }
    if (argc >= 2 && strcmp(argv[1], "--stop") == 0) {
        const char *path = config_path_arg(argc, argv);
        const char *pid_path = pid_path_arg(argc, argv);
        if (path == NULL || pid_path == NULL) {
            fprintf(stderr, "--stop requires --config FILE --pid FILE\n");
            return 2;
        }
        return stop_with_config(path, pid_path);
    }
    if (argc >= 2 && strcmp(argv[1], "--stats") == 0) {
        const char *pid_path = pid_path_arg(argc, argv);
        if (pid_path == NULL) {
            fprintf(stderr, "--stats requires --pid FILE\n");
            return 2;
        }
        return stats_with_pid(pid_path);
    }
    fprintf(stderr, "Usage: %s --probe [--config FILE] | --start --config FILE --pid FILE | --stop --config FILE --pid FILE | --stats --pid FILE\n", argv[0]);
    return 2;
}
