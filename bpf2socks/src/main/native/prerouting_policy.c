// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

#include "bpf2socks.h"

#include <arpa/inet.h>
#include <errno.h>
#include <linux/bpf.h>
#include <netinet/in.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#ifndef BPF_F_NO_PREALLOC
#define BPF_F_NO_PREALLOC 1U
#endif

#define ARRAY_SIZE(x) (sizeof(x) / sizeof((x)[0]))
#define XT_BPF_NOMATCH 0U
#define XT_BPF_MATCH 0xffffU
#define BPF2SOCKS_PROBE_PREROUTING_V4_PATH "/sys/fs/bpf/asteriskng/bpf2socks/probe_prerouting_v4"
#define BPF2SOCKS_PROBE_PREROUTING_V6_PATH "/sys/fs/bpf/asteriskng/bpf2socks/probe_prerouting_v6"

#define BPF_ALU64_IMM_OP(OP, DST, IMM) ((struct bpf_insn){.code = BPF_ALU64 | BPF_OP(OP) | BPF_K, .dst_reg = DST, .imm = (int32_t)(IMM)})
#define BPF_ALU64_REG_OP(OP, DST, SRC) ((struct bpf_insn){.code = BPF_ALU64 | BPF_OP(OP) | BPF_X, .dst_reg = DST, .src_reg = SRC})
#define BPF_MOV64_REG(DST, SRC) BPF_ALU64_REG_OP(BPF_MOV, DST, SRC)
#define BPF_MOV64_IMM(DST, IMM) BPF_ALU64_IMM_OP(BPF_MOV, DST, IMM)
#define BPF_ST_MEM(SIZE, DST, OFF, IMM) ((struct bpf_insn){.code = BPF_ST | BPF_SIZE(SIZE) | BPF_MEM, .dst_reg = DST, .off = OFF, .imm = (int32_t)(IMM)})
#define BPF_LDX_MEM(SIZE, DST, SRC, OFF) ((struct bpf_insn){.code = BPF_LDX | BPF_SIZE(SIZE) | BPF_MEM, .dst_reg = DST, .src_reg = SRC, .off = OFF})
#define BPF_JMP_IMM_OP(OP, DST, IMM, OFF) ((struct bpf_insn){.code = BPF_JMP | BPF_OP(OP) | BPF_K, .dst_reg = DST, .off = OFF, .imm = (int32_t)(IMM)})
#define BPF_CALL_FUNC(FUNC) ((struct bpf_insn){.code = BPF_JMP | BPF_CALL, .imm = FUNC})
#define BPF_EXIT_INSN() ((struct bpf_insn){.code = BPF_JMP | BPF_EXIT})

enum {
    STACK_BYTE = -4,
    STACK_DPORT = -8,
    STACK_LPM4_KEY = -16,
    STACK_LPM6_KEY = -40,
};

struct bpf_builder {
    struct bpf_insn insns[512];
    size_t count;
    bool overflow;
};

static void close_fd(int *fd) {
    if (fd != NULL && *fd >= 0) {
        close(*fd);
        *fd = -1;
    }
}

static void emit(struct bpf_builder *builder, struct bpf_insn insn) {
    if (builder->count < ARRAY_SIZE(builder->insns)) {
        builder->insns[builder->count++] = insn;
    } else {
        builder->overflow = true;
    }
}

static size_t emit_jump(struct bpf_builder *builder, struct bpf_insn insn) {
    size_t index = builder->count;
    emit(builder, insn);
    return index;
}

static void patch_jump(struct bpf_builder *builder, size_t jump_index, size_t target_index) {
    builder->insns[jump_index].off = (int16_t)(target_index - jump_index - 1U);
}

static void emit_ld_map_fd(struct bpf_builder *builder, int dst_reg, int map_fd) {
    emit(builder, (struct bpf_insn){
        .code = BPF_LD | BPF_DW | BPF_IMM,
        .dst_reg = (uint8_t)dst_reg,
        .src_reg = BPF_PSEUDO_MAP_FD,
        .imm = map_fd,
    });
    emit(builder, (struct bpf_insn){.code = 0, .imm = 0});
}

static size_t emit_skb_load_bytes_const(struct bpf_builder *builder, int offset, int stack_offset, int size) {
    emit(builder, BPF_MOV64_REG(BPF_REG_1, BPF_REG_6));
    emit(builder, BPF_MOV64_IMM(BPF_REG_2, offset));
    emit(builder, BPF_MOV64_REG(BPF_REG_3, BPF_REG_10));
    emit(builder, BPF_ALU64_IMM_OP(BPF_ADD, BPF_REG_3, stack_offset));
    emit(builder, BPF_MOV64_IMM(BPF_REG_4, size));
    emit(builder, BPF_CALL_FUNC(BPF_FUNC_skb_load_bytes));
    return emit_jump(builder, BPF_JMP_IMM_OP(BPF_JNE, BPF_REG_0, 0, 0));
}

static void emit_map_lookup_jump(
    struct bpf_builder *builder,
    int map_fd,
    int key_stack_offset,
    size_t *jumps,
    size_t *jump_count,
    bool jump_on_match) {
    if (map_fd < 0) return;
    emit_ld_map_fd(builder, BPF_REG_1, map_fd);
    emit(builder, BPF_MOV64_REG(BPF_REG_2, BPF_REG_10));
    emit(builder, BPF_ALU64_IMM_OP(BPF_ADD, BPF_REG_2, key_stack_offset));
    emit(builder, BPF_CALL_FUNC(BPF_FUNC_map_lookup_elem));
    jumps[(*jump_count)++] = emit_jump(
        builder,
        jump_on_match
            ? BPF_JMP_IMM_OP(BPF_JNE, BPF_REG_0, 0, 0)
            : BPF_JMP_IMM_OP(BPF_JEQ, BPF_REG_0, 0, 0));
}

static size_t emit_exit(struct bpf_builder *builder, int result) {
    size_t label = builder->count;
    emit(builder, BPF_MOV64_IMM(BPF_REG_0, result));
    emit(builder, BPF_EXIT_INSN());
    return label;
}

static bool proxy_cidr4_map_required(const struct bpf2socks_policy_config *policy) {
    return policy != NULL && policy->proxy_private_cidr_v4_count > 0U;
}

static bool bypass_cidr4_map_required(const struct bpf2socks_policy_config *policy) {
    return policy != NULL &&
        (policy->bypass_private_cidr_v4_count > 0U ||
         policy->local_interface_cidr_v4_count > 0U ||
         policy->bypass_direct_cidrs);
}

static bool proxy_cidr6_map_required(const struct bpf2socks_policy_config *policy) {
    return policy != NULL && policy->enable_ipv6 && policy->proxy_private_cidr_v6_count > 0U;
}

static bool bypass_cidr6_map_required(const struct bpf2socks_policy_config *policy) {
    return policy != NULL &&
        policy->enable_ipv6 &&
        (policy->bypass_private_cidr_v6_count > 0U ||
         policy->local_interface_cidr_v6_count > 0U ||
         policy->bypass_direct_cidrs);
}

static int build_prerouting_ipv4_prog(
    const struct bpf2socks_policy_config *policy,
    int proxy_cidr4_map_fd,
    int bypass_cidr4_map_fd,
    const char *name,
    bool log_error) {
    struct bpf_builder b = {0};
    size_t match_jumps[8];
    size_t match_jump_count = 0;
    size_t nomatch_jumps[16];
    size_t nomatch_jump_count = 0;

    emit(&b, BPF_MOV64_REG(BPF_REG_6, BPF_REG_1));

    nomatch_jumps[nomatch_jump_count++] = emit_skb_load_bytes_const(&b, 0, STACK_BYTE, 1);
    emit(&b, BPF_LDX_MEM(BPF_B, BPF_REG_7, BPF_REG_10, STACK_BYTE));
    emit(&b, BPF_MOV64_REG(BPF_REG_2, BPF_REG_7));
    emit(&b, BPF_ALU64_IMM_OP(BPF_AND, BPF_REG_2, 0xf0));
    nomatch_jumps[nomatch_jump_count++] = emit_jump(&b, BPF_JMP_IMM_OP(BPF_JNE, BPF_REG_2, 0x40, 0));

    if (policy != NULL && policy->enable_dns_hijack) {
        emit(&b, BPF_MOV64_REG(BPF_REG_2, BPF_REG_7));
        emit(&b, BPF_ALU64_IMM_OP(BPF_AND, BPF_REG_2, 0x0f));
        size_t not_plain_ipv4 = emit_jump(&b, BPF_JMP_IMM_OP(BPF_JNE, BPF_REG_2, 5, 0));
        nomatch_jumps[nomatch_jump_count++] = emit_skb_load_bytes_const(&b, 22, STACK_DPORT, 2);
        emit(&b, BPF_LDX_MEM(BPF_H, BPF_REG_2, BPF_REG_10, STACK_DPORT));
        match_jumps[match_jump_count++] = emit_jump(&b, BPF_JMP_IMM_OP(BPF_JEQ, BPF_REG_2, htons(53), 0));
        patch_jump(&b, not_plain_ipv4, b.count);
    }

    if (proxy_cidr4_map_fd >= 0 || bypass_cidr4_map_fd >= 0) {
        emit(&b, BPF_ST_MEM(BPF_W, BPF_REG_10, STACK_LPM4_KEY, 32));
        nomatch_jumps[nomatch_jump_count++] = emit_skb_load_bytes_const(
            &b,
            16,
            STACK_LPM4_KEY + (int)offsetof(struct bpf2socks_lpm4_key, addr),
            4);

        emit_map_lookup_jump(&b, proxy_cidr4_map_fd, STACK_LPM4_KEY, match_jumps, &match_jump_count, true);
        emit_map_lookup_jump(&b, bypass_cidr4_map_fd, STACK_LPM4_KEY, nomatch_jumps, &nomatch_jump_count, true);
    }

    size_t match_label = emit_exit(&b, XT_BPF_MATCH);
    size_t nomatch_label = emit_exit(&b, XT_BPF_NOMATCH);
    for (size_t i = 0; i < match_jump_count; ++i) {
        patch_jump(&b, match_jumps[i], match_label);
    }
    for (size_t i = 0; i < nomatch_jump_count; ++i) {
        patch_jump(&b, nomatch_jumps[i], nomatch_label);
    }

    if (b.overflow) {
        errno = EMSGSIZE;
        return -1;
    }
    return bpf2socks_load_prog(
        b.insns,
        b.count,
        name,
        BPF_PROG_TYPE_SOCKET_FILTER,
        0,
        log_error);
}

static int build_prerouting_ipv6_prog(
    const struct bpf2socks_policy_config *policy,
    int proxy_cidr6_map_fd,
    int bypass_cidr6_map_fd,
    const char *name,
    bool log_error) {
    struct bpf_builder b = {0};
    size_t match_jumps[8];
    size_t match_jump_count = 0;
    size_t nomatch_jumps[16];
    size_t nomatch_jump_count = 0;

    emit(&b, BPF_MOV64_REG(BPF_REG_6, BPF_REG_1));

    nomatch_jumps[nomatch_jump_count++] = emit_skb_load_bytes_const(&b, 0, STACK_BYTE, 1);
    emit(&b, BPF_LDX_MEM(BPF_B, BPF_REG_7, BPF_REG_10, STACK_BYTE));
    emit(&b, BPF_MOV64_REG(BPF_REG_2, BPF_REG_7));
    emit(&b, BPF_ALU64_IMM_OP(BPF_AND, BPF_REG_2, 0xf0));
    nomatch_jumps[nomatch_jump_count++] = emit_jump(&b, BPF_JMP_IMM_OP(BPF_JNE, BPF_REG_2, 0x60, 0));

    if (policy != NULL && policy->enable_dns_hijack) {
        nomatch_jumps[nomatch_jump_count++] = emit_skb_load_bytes_const(&b, 42, STACK_DPORT, 2);
        emit(&b, BPF_LDX_MEM(BPF_H, BPF_REG_2, BPF_REG_10, STACK_DPORT));
        match_jumps[match_jump_count++] = emit_jump(&b, BPF_JMP_IMM_OP(BPF_JEQ, BPF_REG_2, htons(53), 0));
    }

    if (proxy_cidr6_map_fd >= 0 || bypass_cidr6_map_fd >= 0) {
        emit(&b, BPF_ST_MEM(BPF_W, BPF_REG_10, STACK_LPM6_KEY, 128));
        nomatch_jumps[nomatch_jump_count++] = emit_skb_load_bytes_const(
            &b,
            24,
            STACK_LPM6_KEY + (int)offsetof(struct bpf2socks_lpm6_key, addr),
            16);

        emit_map_lookup_jump(&b, proxy_cidr6_map_fd, STACK_LPM6_KEY, match_jumps, &match_jump_count, true);
        emit_map_lookup_jump(&b, bypass_cidr6_map_fd, STACK_LPM6_KEY, nomatch_jumps, &nomatch_jump_count, true);
    }

    size_t match_label = emit_exit(&b, XT_BPF_MATCH);
    size_t nomatch_label = emit_exit(&b, XT_BPF_NOMATCH);
    for (size_t i = 0; i < match_jump_count; ++i) {
        patch_jump(&b, match_jumps[i], match_label);
    }
    for (size_t i = 0; i < nomatch_jump_count; ++i) {
        patch_jump(&b, nomatch_jumps[i], nomatch_label);
    }

    if (b.overflow) {
        errno = EMSGSIZE;
        return -1;
    }
    return bpf2socks_load_prog(
        b.insns,
        b.count,
        name,
        BPF_PROG_TYPE_SOCKET_FILTER,
        0,
        log_error);
}

static int load_policy_maps(
    const struct bpf2socks_policy_config *policy,
    int *proxy_cidr4_fd,
    int *bypass_cidr4_fd,
    int *proxy_cidr6_fd,
    int *bypass_cidr6_fd) {
    *proxy_cidr4_fd = -1;
    *bypass_cidr4_fd = -1;
    *proxy_cidr6_fd = -1;
    *bypass_cidr6_fd = -1;
    bool need_proxy4 = proxy_cidr4_map_required(policy);
    bool need_bypass4 = bypass_cidr4_map_required(policy);
    bool need_proxy6 = proxy_cidr6_map_required(policy);
    bool need_bypass6 = bypass_cidr6_map_required(policy);
    if (need_proxy4) {
        *proxy_cidr4_fd = bpf2socks_create_map(
            BPF_MAP_TYPE_LPM_TRIE,
            sizeof(struct bpf2socks_lpm4_key),
            sizeof(uint8_t),
            BPF2SOCKS_MAX_CIDR_MAP_ENTRIES,
            BPF_F_NO_PREALLOC);
    }
    if (need_bypass4) {
        *bypass_cidr4_fd = bpf2socks_create_map(
            BPF_MAP_TYPE_LPM_TRIE,
            sizeof(struct bpf2socks_lpm4_key),
            sizeof(uint8_t),
            BPF2SOCKS_MAX_CIDR_MAP_ENTRIES,
            BPF_F_NO_PREALLOC);
    }
    if ((need_proxy4 && *proxy_cidr4_fd < 0) || (need_bypass4 && *bypass_cidr4_fd < 0)) {
        return -1;
    }
    bool enable_ipv6 = policy == NULL || policy->enable_ipv6;
    if (enable_ipv6) {
        if (need_proxy6) {
            *proxy_cidr6_fd = bpf2socks_create_map(
                BPF_MAP_TYPE_LPM_TRIE,
                sizeof(struct bpf2socks_lpm6_key),
                sizeof(uint8_t),
                BPF2SOCKS_MAX_CIDR_MAP_ENTRIES,
                BPF_F_NO_PREALLOC);
        }
        if (need_bypass6) {
            *bypass_cidr6_fd = bpf2socks_create_map(
                BPF_MAP_TYPE_LPM_TRIE,
                sizeof(struct bpf2socks_lpm6_key),
                sizeof(uint8_t),
                BPF2SOCKS_MAX_CIDR_MAP_ENTRIES,
                BPF_F_NO_PREALLOC);
        }
        if ((need_proxy6 && *proxy_cidr6_fd < 0) || (need_bypass6 && *bypass_cidr6_fd < 0)) {
            return -1;
        }
    }
    if (policy == NULL) return 0;
    if (need_proxy4 &&
        bpf2socks_load_cidr_strings(
            *proxy_cidr4_fd,
            policy->proxy_private_cidrs_v4,
            policy->proxy_private_cidr_v4_count,
            AF_INET) < 0) {
        return -1;
    }
    if (need_bypass4) {
        if (bpf2socks_load_cidr_strings(
                *bypass_cidr4_fd,
                policy->bypass_private_cidrs_v4,
                policy->bypass_private_cidr_v4_count,
                AF_INET) < 0 ||
            bpf2socks_load_cidr_strings(
                *bypass_cidr4_fd,
                policy->local_interface_cidrs_v4,
                policy->local_interface_cidr_v4_count,
                AF_INET) < 0) {
            return -1;
        }
        if (policy->bypass_direct_cidrs &&
            bpf2socks_load_direct_cidrs(*bypass_cidr4_fd, policy->direct_cidr_path_v4, AF_INET) < 0) {
            return -1;
        }
    }
    if (enable_ipv6 &&
        need_proxy6 &&
        bpf2socks_load_cidr_strings(
                *proxy_cidr6_fd,
                policy->proxy_private_cidrs_v6,
                policy->proxy_private_cidr_v6_count,
                AF_INET6) < 0) {
        return -1;
    }
    if (enable_ipv6 && need_bypass6) {
        if (bpf2socks_load_cidr_strings(
                *bypass_cidr6_fd,
                policy->bypass_private_cidrs_v6,
                policy->bypass_private_cidr_v6_count,
                AF_INET6) < 0 ||
            bpf2socks_load_cidr_strings(
                *bypass_cidr6_fd,
                policy->local_interface_cidrs_v6,
                policy->local_interface_cidr_v6_count,
                AF_INET6) < 0) {
            return -1;
        }
        if (policy->bypass_direct_cidrs &&
            bpf2socks_load_direct_cidrs(*bypass_cidr6_fd, policy->direct_cidr_path_v6, AF_INET6) < 0) {
            return -1;
        }
    }
    return 0;
}

static const char *prerouting_ipv4_path(
    const struct bpf2socks_runtime_config *config,
    char *fallback,
    size_t fallback_size) {
    if (config != NULL && config->prerouting_policy_ipv4_path[0] != '\0') {
        return config->prerouting_policy_ipv4_path;
    }
    const char *dir = config != NULL && config->pinned_object_dir[0] != '\0'
        ? config->pinned_object_dir
        : "/sys/fs/bpf/asteriskng/bpf2socks";
    snprintf(fallback, fallback_size, "%s/prerouting_v4", dir);
    return fallback;
}

static const char *prerouting_ipv6_path(
    const struct bpf2socks_runtime_config *config,
    char *fallback,
    size_t fallback_size) {
    if (config != NULL && config->prerouting_policy_ipv6_path[0] != '\0') {
        return config->prerouting_policy_ipv6_path;
    }
    const char *dir = config != NULL && config->pinned_object_dir[0] != '\0'
        ? config->pinned_object_dir
        : "/sys/fs/bpf/asteriskng/bpf2socks";
    snprintf(fallback, fallback_size, "%s/prerouting_v6", dir);
    return fallback;
}

int bpf2socks_prerouting_policy_probe(bool enable_ipv6, char *message, size_t message_size) {
    int proxy_cidr4_fd = -1;
    int bypass_cidr4_fd = -1;
    int proxy_cidr6_fd = -1;
    int bypass_cidr6_fd = -1;
    int prog_fd = -1;
    int result = -1;
    struct bpf2socks_policy_config probe_policy;
    memset(&probe_policy, 0, sizeof(probe_policy));
    probe_policy.enable_ipv6 = enable_ipv6;
    if (load_policy_maps(&probe_policy, &proxy_cidr4_fd, &bypass_cidr4_fd, &proxy_cidr6_fd, &bypass_cidr6_fd) < 0) {
        snprintf(message, message_size, "PREROUTING policy maps are unavailable: errno=%d", errno);
        goto done;
    }
    prog_fd = build_prerouting_ipv4_prog(NULL, proxy_cidr4_fd, bypass_cidr4_fd, "b2s_p_pre4", false);
    if (prog_fd < 0) {
        snprintf(message, message_size, "PREROUTING socket filter cannot be loaded: errno=%d", errno);
        goto done;
    }
    if (bpf2socks_pin_fd(prog_fd, BPF2SOCKS_PROBE_PREROUTING_V4_PATH) < 0) {
        snprintf(message, message_size, "PREROUTING pinned object path is unavailable: errno=%d", errno);
        goto done;
    }
    (void)unlink(BPF2SOCKS_PROBE_PREROUTING_V4_PATH);
    if (enable_ipv6) {
        close_fd(&prog_fd);
        prog_fd = build_prerouting_ipv6_prog(&probe_policy, proxy_cidr6_fd, bypass_cidr6_fd, "b2s_p_pre6", false);
        if (prog_fd < 0) {
            snprintf(message, message_size, "PREROUTING IPv6 socket filter cannot be loaded: errno=%d", errno);
            goto done;
        }
        if (bpf2socks_pin_fd(prog_fd, BPF2SOCKS_PROBE_PREROUTING_V6_PATH) < 0) {
            snprintf(message, message_size, "PREROUTING IPv6 pinned object path is unavailable: errno=%d", errno);
            goto done;
        }
        (void)unlink(BPF2SOCKS_PROBE_PREROUTING_V6_PATH);
    }
    result = 0;

done:
    close_fd(&prog_fd);
    close_fd(&bypass_cidr6_fd);
    close_fd(&proxy_cidr6_fd);
    close_fd(&bypass_cidr4_fd);
    close_fd(&proxy_cidr4_fd);
    return result;
}

int bpf2socks_prerouting_policy_prepare(
    const struct bpf2socks_policy_config *policy,
    const struct bpf2socks_runtime_config *config) {
    if (policy == NULL || config == NULL) {
        errno = EINVAL;
        return -1;
    }
    if (policy->hotspot_interface_prefix_count == 0U) {
        return 0;
    }
    int proxy_cidr4_fd = -1;
    int bypass_cidr4_fd = -1;
    int proxy_cidr6_fd = -1;
    int bypass_cidr6_fd = -1;
    int prog_fd = -1;
    int result = -1;
    char path4_buf[BPF2SOCKS_MAX_PATH_LEN];
    char path6_buf[BPF2SOCKS_MAX_PATH_LEN];
    const char *path4 = prerouting_ipv4_path(config, path4_buf, sizeof(path4_buf));
    const char *path6 = prerouting_ipv6_path(config, path6_buf, sizeof(path6_buf));

    if (load_policy_maps(policy, &proxy_cidr4_fd, &bypass_cidr4_fd, &proxy_cidr6_fd, &bypass_cidr6_fd) < 0) {
        goto done;
    }
    prog_fd = build_prerouting_ipv4_prog(policy, proxy_cidr4_fd, bypass_cidr4_fd, "b2s_pre4", true);
    if (prog_fd < 0) {
        goto done;
    }
    if (bpf2socks_pin_fd(prog_fd, path4) < 0) {
        goto done;
    }
    close_fd(&prog_fd);
    if (policy->enable_ipv6) {
        prog_fd = build_prerouting_ipv6_prog(policy, proxy_cidr6_fd, bypass_cidr6_fd, "b2s_pre6", true);
        if (prog_fd < 0) {
            goto done;
        }
        if (bpf2socks_pin_fd(prog_fd, path6) < 0) {
            goto done;
        }
    }
    result = 0;

done:
    close_fd(&prog_fd);
    close_fd(&bypass_cidr6_fd);
    close_fd(&proxy_cidr6_fd);
    close_fd(&bypass_cidr4_fd);
    close_fd(&proxy_cidr4_fd);
    return result;
}
