// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

#include "bpf2socks.h"

#include <arpa/inet.h>
#include <ctype.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

int bpf2socks_load_uid_map(int uid_map_fd, const struct bpf2socks_policy_config *policy) {
    if (uid_map_fd < 0 || policy == NULL) return -1;
    uint32_t value = BPF2SOCKS_UID_SELECTED;
    for (size_t i = 0; i < policy->uid_count && i < BPF2SOCKS_MAX_UIDS; ++i) {
        if (bpf2socks_update_map(uid_map_fd, &policy->uids[i], &value) < 0) {
            return -1;
        }
    }
    value = BPF2SOCKS_UID_BYPASS;
    for (size_t i = 0; i < policy->bypass_uid_count && i < BPF2SOCKS_MAX_UIDS; ++i) {
        if (bpf2socks_update_map(uid_map_fd, &policy->bypass_uids[i], &value) < 0) {
            return -1;
        }
    }
    return 0;
}

static char *trim_line(char *line) {
    while (isspace((unsigned char)*line)) ++line;
    char *end = line + strlen(line);
    while (end > line && isspace((unsigned char)end[-1])) {
        *--end = '\0';
    }
    return line;
}

static int parse_cidr(char *line, int expected_family, void *key, uint8_t *value) {
    char *slash = strchr(line, '/');
    if (slash == NULL) return -1;
    *slash++ = '\0';
    char *end = NULL;
    errno = 0;
    unsigned long prefix = strtoul(slash, &end, 10);
    if (errno != 0 || end == slash || *end != '\0') return -1;
    *value = 1U;

    if (expected_family == AF_INET) {
        if (prefix > 32UL) return -1;
        struct bpf2socks_lpm4_key *lpm = key;
        memset(lpm, 0, sizeof(*lpm));
        lpm->prefixlen = (uint32_t)prefix;
        struct in_addr addr;
        if (inet_pton(AF_INET, line, &addr) != 1) return -1;
        uint32_t mask = prefix == 0UL ? 0U : 0xffffffffU << (32UL - prefix);
        lpm->addr = htonl(ntohl(addr.s_addr) & mask);
        return 0;
    }
    if (expected_family == AF_INET6) {
        if (prefix > 128UL) return -1;
        struct bpf2socks_lpm6_key *lpm = key;
        memset(lpm, 0, sizeof(*lpm));
        lpm->prefixlen = (uint32_t)prefix;
        if (inet_pton(AF_INET6, line, lpm->addr) != 1) return -1;
        if (prefix < 128UL) {
            size_t full_bytes = (size_t)(prefix / 8UL);
            uint8_t rest_bits = (uint8_t)(prefix % 8UL);
            if (full_bytes < sizeof(lpm->addr)) {
                if (rest_bits == 0U) {
                    memset(lpm->addr + full_bytes, 0, sizeof(lpm->addr) - full_bytes);
                } else {
                    uint8_t mask = (uint8_t)(0xffU << (8U - rest_bits));
                    lpm->addr[full_bytes] &= mask;
                    memset(lpm->addr + full_bytes + 1U, 0, sizeof(lpm->addr) - full_bytes - 1U);
                }
            }
        }
        return 0;
    }
    return -1;
}

static int parse_cidr_text(const char *text, int expected_family, void *key, uint8_t *value) {
    if (text == NULL || text[0] == '\0') return -1;
    char line[256];
    snprintf(line, sizeof(line), "%s", text);
    char *trimmed = trim_line(line);
    if (trimmed[0] == '\0' || trimmed[0] == '#') return 0;
    char *comment = strchr(trimmed, '#');
    if (comment != NULL) {
        *comment = '\0';
        trimmed = trim_line(trimmed);
        if (trimmed[0] == '\0') return 0;
    }
    return parse_cidr(trimmed, expected_family, key, value) == 0 ? 1 : -1;
}

static bool cidr_map_has_covering_entry(int map_fd, const void *key, int expected_family) {
    (void)expected_family;
    if (map_fd < 0 || key == NULL) return false;
    uint8_t value = 0U;
    return bpf2socks_lookup_map(map_fd, key, &value) == 0;
}

static int update_cidr_map_if_uncovered(int map_fd, const void *key, const uint8_t *value, int expected_family) {
    if (cidr_map_has_covering_entry(map_fd, key, expected_family)) return 0;
    return bpf2socks_update_map(map_fd, key, value);
}

int bpf2socks_parse_ipv4_cidr_host(const char *cidr, uint32_t *base, uint32_t *host_bits) {
    if (cidr == NULL || base == NULL || host_bits == NULL) return -1;
    char line[256];
    snprintf(line, sizeof(line), "%s", cidr);
    char *trimmed = trim_line(line);
    char *slash = strchr(trimmed, '/');
    if (slash == NULL) return -1;
    *slash++ = '\0';
    char *end = NULL;
    errno = 0;
    unsigned long prefix = strtoul(slash, &end, 10);
    if (errno != 0 || end == slash || *end != '\0' || prefix > 32UL) return -1;
    struct in_addr addr;
    if (inet_pton(AF_INET, trimmed, &addr) != 1) return -1;
    uint32_t mask = prefix == 0UL ? 0U : 0xffffffffU << (32UL - prefix);
    *base = ntohl(addr.s_addr) & mask;
    *host_bits = (uint32_t)(32UL - prefix);
    return 0;
}

int bpf2socks_load_direct_cidrs(int map_fd, const char *path, int expected_family) {
    if (map_fd < 0 || path == NULL || path[0] == '\0') return -1;
    FILE *file = fopen(path, "r");
    if (file == NULL) return -1;

    char line[256];
    uint8_t value = 1U;
    int result = 0;
    while (fgets(line, sizeof(line), file) != NULL) {
        char *trimmed = trim_line(line);
        if (trimmed[0] == '\0' || trimmed[0] == '#') continue;
        char *comment = strchr(trimmed, '#');
        if (comment != NULL) {
            *comment = '\0';
            trimmed = trim_line(trimmed);
            if (trimmed[0] == '\0') continue;
        }
        union {
            struct bpf2socks_lpm4_key v4;
            struct bpf2socks_lpm6_key v6;
        } key;
        if (parse_cidr(trimmed, expected_family, &key, &value) < 0 ||
            update_cidr_map_if_uncovered(map_fd, &key, &value, expected_family) < 0) {
            result = -1;
            break;
        }
    }
    fclose(file);
    return result;
}

int bpf2socks_load_cidr_strings(int map_fd, const char cidrs[][BPF2SOCKS_MAX_CIDR_TEXT_LEN], size_t count, int expected_family) {
    if (map_fd < 0 || cidrs == NULL) return -1;
    uint8_t value = 1U;
    for (size_t i = 0; i < count; ++i) {
        union {
            struct bpf2socks_lpm4_key v4;
            struct bpf2socks_lpm6_key v6;
        } key;
        int parsed = parse_cidr_text(cidrs[i], expected_family, &key, &value);
        if (parsed < 0) {
            return -1;
        }
        if (parsed > 0 && update_cidr_map_if_uncovered(map_fd, &key, &value, expected_family) < 0) {
            return -1;
        }
    }
    return 0;
}
