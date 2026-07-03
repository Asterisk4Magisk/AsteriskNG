// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

#include "bpf2socks.h"

#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/bpf.h>
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
#define BPF2SOCKS_MAX_INLINE_BYPASS_UIDS 16U
/* BPF_MAP_TYPE_LRU_HASH. Android NDK headers expose it as an enum, not a preprocessor macro. */
#define BPF2SOCKS_TOKEN_MAP_TYPE 9U

#define BPF_ALU64_IMM_OP(OP, DST, IMM) ((struct bpf_insn){.code = BPF_ALU64 | BPF_OP(OP) | BPF_K, .dst_reg = DST, .imm = (int32_t)(IMM)})
#define BPF_ALU64_REG_OP(OP, DST, SRC) ((struct bpf_insn){.code = BPF_ALU64 | BPF_OP(OP) | BPF_X, .dst_reg = DST, .src_reg = SRC})
#define BPF_ALU32_IMM_OP(OP, DST, IMM) ((struct bpf_insn){.code = BPF_ALU | BPF_OP(OP) | BPF_K, .dst_reg = DST, .imm = (int32_t)(IMM)})
#define BPF_ALU32_REG_OP(OP, DST, SRC) ((struct bpf_insn){.code = BPF_ALU | BPF_OP(OP) | BPF_X, .dst_reg = DST, .src_reg = SRC})
#define BPF_MOV64_REG(DST, SRC) BPF_ALU64_REG_OP(BPF_MOV, DST, SRC)
#define BPF_MOV64_IMM(DST, IMM) BPF_ALU64_IMM_OP(BPF_MOV, DST, IMM)
#define BPF_MOV32_REG(DST, SRC) BPF_ALU32_REG_OP(BPF_MOV, DST, SRC)
#define BPF_ST_MEM(SIZE, DST, OFF, IMM) ((struct bpf_insn){.code = BPF_ST | BPF_SIZE(SIZE) | BPF_MEM, .dst_reg = DST, .off = OFF, .imm = (int32_t)(IMM)})
#define BPF_STX_MEM(SIZE, DST, SRC, OFF) ((struct bpf_insn){.code = BPF_STX | BPF_SIZE(SIZE) | BPF_MEM, .dst_reg = DST, .src_reg = SRC, .off = OFF})
#define BPF_LDX_MEM(SIZE, DST, SRC, OFF) ((struct bpf_insn){.code = BPF_LDX | BPF_SIZE(SIZE) | BPF_MEM, .dst_reg = DST, .src_reg = SRC, .off = OFF})
#define BPF_JMP_IMM_OP(OP, DST, IMM, OFF) ((struct bpf_insn){.code = BPF_JMP | BPF_OP(OP) | BPF_K, .dst_reg = DST, .off = OFF, .imm = (int32_t)(IMM)})
#define BPF_JMP_REG_OP(OP, DST, SRC, OFF) ((struct bpf_insn){.code = BPF_JMP | BPF_OP(OP) | BPF_X, .dst_reg = DST, .src_reg = SRC, .off = OFF})
#define BPF_CALL_FUNC(FUNC) ((struct bpf_insn){.code = BPF_JMP | BPF_CALL, .imm = FUNC})
#define BPF_EXIT_INSN() ((struct bpf_insn){.code = BPF_JMP | BPF_EXIT})
#define BPF_ENDIAN_OP(DST, SIZE) ((struct bpf_insn){.code = BPF_ALU | BPF_END | BPF_TO_BE, .dst_reg = DST, .imm = SIZE})

enum {
    STACK_UID_KEY = -4,
    STACK_IFINDEX_KEY = -8,
    STACK_LPM4_KEY = -16,
    STACK_LPM6_KEY = -40,
    STACK_TOKEN_KEY = -96,
    STACK_ORIGINAL_DST = -144,
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

static void init_runtime(struct bpf2socks_bpf_runtime *runtime) {
    memset(runtime, -1, sizeof(*runtime));
    runtime->interfaces = NULL;
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

static size_t emit_exit(struct bpf_builder *builder, int result) {
    size_t label = builder->count;
    emit(builder, BPF_MOV64_IMM(BPF_REG_0, result));
    emit(builder, BPF_EXIT_INSN());
    return label;
}

static uint32_t uid_map_max_entries(const struct bpf2socks_policy_config *policy) {
    size_t count = 0U;
    if (policy != NULL) {
        count = policy->uid_count + policy->bypass_uid_count;
    }
    if (count < 16U) return 16U;
    return count > UINT32_MAX ? UINT32_MAX : (uint32_t)count;
}

static size_t inline_bypass_uid_count(const struct bpf2socks_policy_config *policy) {
    if (policy == NULL) return 0U;
    return policy->bypass_uid_count < BPF2SOCKS_MAX_INLINE_BYPASS_UIDS
        ? policy->bypass_uid_count
        : BPF2SOCKS_MAX_INLINE_BYPASS_UIDS;
}

static bool uid_map_required(const struct bpf2socks_policy_config *policy) {
    if (policy == NULL) return false;
    if (policy->mode != BPF2SOCKS_MODE_GLOBAL) return true;
    return policy->bypass_uid_count > inline_bypass_uid_count(policy);
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

static bool bypass_cidr6_map_required(const struct bpf2socks_policy_config *policy) {
    return policy != NULL &&
        policy->enable_ipv6 &&
        (policy->bypass_private_cidr_v6_count > 0U ||
         policy->local_interface_cidr_v6_count > 0U ||
         policy->bypass_direct_cidrs);
}

static int create_token_map(uint32_t max_entries) {
    return bpf2socks_create_map(
        (enum bpf_map_type)BPF2SOCKS_TOKEN_MAP_TYPE,
        sizeof(struct bpf2socks_token_key),
        sizeof(struct bpf2socks_original_dst),
        max_entries,
        0U);
}

static void emit_zero_region(struct bpf_builder *builder, int base_off, size_t size) {
    for (size_t off = 0; off < size; off += sizeof(uint32_t)) {
        emit(builder, BPF_ST_MEM(BPF_W, BPF_REG_10, (int16_t)(base_off + (int)off), 0));
    }
}

static void emit_self_gid_bypass_check(
    struct bpf_builder *builder,
    const struct bpf2socks_policy_config *policy,
    size_t *bypass_jumps,
    size_t *bypass_jump_count) {
    if (policy == NULL || !policy->self_bypass_gid_enabled) return;

    emit(builder, BPF_MOV64_REG(BPF_REG_8, BPF_REG_0));
    emit(builder, BPF_ALU64_IMM_OP(BPF_RSH, BPF_REG_8, 32));
    bypass_jumps[(*bypass_jump_count)++] =
        emit_jump(builder, BPF_JMP_IMM_OP(BPF_JEQ, BPF_REG_8, policy->self_bypass_gid, 0));
}

static void emit_uid_policy(
    struct bpf_builder *builder,
    const struct bpf2socks_policy_config *policy,
    int uid_map_fd,
    size_t *bypass_jumps,
    size_t *bypass_jump_count,
    size_t *drop_jumps,
    size_t *drop_jump_count) {
    if (policy == NULL) return;
    size_t inline_count = inline_bypass_uid_count(policy);
    bool self_gid_bypass = policy->self_bypass_gid_enabled;
    bool needs_map = uid_map_fd >= 0;
    if (inline_count == 0U && !self_gid_bypass && !needs_map && policy->mode == BPF2SOCKS_MODE_GLOBAL) return;

    emit(builder, BPF_CALL_FUNC(BPF_FUNC_get_current_uid_gid));
    emit_self_gid_bypass_check(builder, policy, bypass_jumps, bypass_jump_count);
    emit(builder, BPF_MOV32_REG(BPF_REG_7, BPF_REG_0));
    for (size_t i = 0; i < inline_count; ++i) {
        bypass_jumps[(*bypass_jump_count)++] =
            emit_jump(builder, BPF_JMP_IMM_OP(BPF_JEQ, BPF_REG_7, policy->bypass_uids[i], 0));
    }

    if (!needs_map) {
        if (policy->mode != BPF2SOCKS_MODE_GLOBAL) {
            drop_jumps[(*drop_jump_count)++] = emit_jump(builder, BPF_JMP_IMM_OP(BPF_JA, 0, 0, 0));
        }
        return;
    }

    emit(builder, BPF_STX_MEM(BPF_W, BPF_REG_10, BPF_REG_7, STACK_UID_KEY));
    emit_ld_map_fd(builder, BPF_REG_1, uid_map_fd);
    emit(builder, BPF_MOV64_REG(BPF_REG_2, BPF_REG_10));
    emit(builder, BPF_ALU64_IMM_OP(BPF_ADD, BPF_REG_2, STACK_UID_KEY));
    emit(builder, BPF_CALL_FUNC(BPF_FUNC_map_lookup_elem));
    emit(builder, BPF_MOV64_IMM(BPF_REG_8, 0));
    size_t uid_not_found = emit_jump(builder, BPF_JMP_IMM_OP(BPF_JEQ, BPF_REG_0, 0, 0));
    emit(builder, BPF_LDX_MEM(BPF_W, BPF_REG_8, BPF_REG_0, 0));
    patch_jump(builder, uid_not_found, builder->count);

    emit(builder, BPF_MOV64_REG(BPF_REG_2, BPF_REG_8));
    emit(builder, BPF_ALU64_IMM_OP(BPF_AND, BPF_REG_2, BPF2SOCKS_UID_BYPASS));
    bypass_jumps[(*bypass_jump_count)++] = emit_jump(builder, BPF_JMP_IMM_OP(BPF_JNE, BPF_REG_2, 0, 0));

    if (policy->mode == BPF2SOCKS_MODE_GLOBAL) {
        return;
    }

    emit(builder, BPF_MOV64_REG(BPF_REG_2, BPF_REG_8));
    emit(builder, BPF_ALU64_IMM_OP(BPF_AND, BPF_REG_2, BPF2SOCKS_UID_SELECTED));
    if (policy->mode == BPF2SOCKS_MODE_WHITELIST) {
        bypass_jumps[(*bypass_jump_count)++] = emit_jump(builder, BPF_JMP_IMM_OP(BPF_JEQ, BPF_REG_2, 0, 0));
    } else if (policy->mode == BPF2SOCKS_MODE_BLACKLIST) {
        bypass_jumps[(*bypass_jump_count)++] = emit_jump(builder, BPF_JMP_IMM_OP(BPF_JNE, BPF_REG_2, 0, 0));
    } else {
        drop_jumps[(*drop_jump_count)++] = emit_jump(builder, BPF_JMP_IMM_OP(BPF_JA, 0, 0, 0));
    }
}

static void emit_ipv4_policy_checks_from_regs(
    struct bpf_builder *builder,
    const struct bpf2socks_policy_config *policy,
    int proxy_cidr4_map_fd,
    int bypass_cidr4_map_fd,
    int ignored_ifindex_map_fd,
    int ignored_route_cidr4_map_fd,
    uint8_t protocol,
    bool protocol_from_context,
    size_t *bypass_jumps,
    size_t *bypass_jump_count) {
    bypass_jumps[(*bypass_jump_count)++] = emit_jump(builder, BPF_JMP_IMM_OP(BPF_JEQ, BPF_REG_7, 0, 0));

    emit(builder, BPF_MOV64_REG(BPF_REG_2, BPF_REG_7));
    emit(builder, BPF_ENDIAN_OP(BPF_REG_2, 32));
    emit(builder, BPF_ALU64_IMM_OP(BPF_AND, BPF_REG_2, 0xff000000U));
    bypass_jumps[(*bypass_jump_count)++] = emit_jump(builder, BPF_JMP_IMM_OP(BPF_JEQ, BPF_REG_2, 0x7f000000U, 0));

    size_t force_proxy_jumps[8];
    size_t force_proxy_jump_count = 0;
    if (policy->enable_dns_hijack) {
        if (protocol_from_context) {
            emit(builder, BPF_LDX_MEM(BPF_W, BPF_REG_2, BPF_REG_6, offsetof(struct bpf_sock_addr, type)));
            size_t not_udp = emit_jump(builder, BPF_JMP_IMM_OP(BPF_JNE, BPF_REG_2, SOCK_DGRAM, 0));
            force_proxy_jumps[force_proxy_jump_count++] = emit_jump(builder, BPF_JMP_IMM_OP(BPF_JEQ, BPF_REG_8, htons(53), 0));
            patch_jump(builder, not_udp, builder->count);
        } else if (protocol == BPF2SOCKS_PROTO_UDP) {
            force_proxy_jumps[force_proxy_jump_count++] = emit_jump(builder, BPF_JMP_IMM_OP(BPF_JEQ, BPF_REG_8, htons(53), 0));
        }
    }

    if (proxy_cidr4_map_fd >= 0) {
        emit(builder, BPF_ST_MEM(BPF_W, BPF_REG_10, STACK_LPM4_KEY, 32));
        emit(builder, BPF_STX_MEM(BPF_W, BPF_REG_10, BPF_REG_7, STACK_LPM4_KEY + (int)offsetof(struct bpf2socks_lpm4_key, addr)));
        emit_ld_map_fd(builder, BPF_REG_1, proxy_cidr4_map_fd);
        emit(builder, BPF_MOV64_REG(BPF_REG_2, BPF_REG_10));
        emit(builder, BPF_ALU64_IMM_OP(BPF_ADD, BPF_REG_2, STACK_LPM4_KEY));
        emit(builder, BPF_CALL_FUNC(BPF_FUNC_map_lookup_elem));
        force_proxy_jumps[force_proxy_jump_count++] = emit_jump(builder, BPF_JMP_IMM_OP(BPF_JNE, BPF_REG_0, 0, 0));
    }

    if (ignored_ifindex_map_fd >= 0) {
        emit(builder, BPF_LDX_MEM(BPF_DW, BPF_REG_2, BPF_REG_6, offsetof(struct bpf_sock_addr, sk)));
        size_t no_sock = emit_jump(builder, BPF_JMP_IMM_OP(BPF_JEQ, BPF_REG_2, 0, 0));
        emit(builder, BPF_LDX_MEM(BPF_W, BPF_REG_3, BPF_REG_2, offsetof(struct bpf_sock, bound_dev_if)));
        size_t no_bound_if = emit_jump(builder, BPF_JMP_IMM_OP(BPF_JEQ, BPF_REG_3, 0, 0));
        emit(builder, BPF_STX_MEM(BPF_W, BPF_REG_10, BPF_REG_3, STACK_IFINDEX_KEY));
        emit_ld_map_fd(builder, BPF_REG_1, ignored_ifindex_map_fd);
        emit(builder, BPF_MOV64_REG(BPF_REG_2, BPF_REG_10));
        emit(builder, BPF_ALU64_IMM_OP(BPF_ADD, BPF_REG_2, STACK_IFINDEX_KEY));
        emit(builder, BPF_CALL_FUNC(BPF_FUNC_map_lookup_elem));
        bypass_jumps[(*bypass_jump_count)++] = emit_jump(builder, BPF_JMP_IMM_OP(BPF_JNE, BPF_REG_0, 0, 0));
        patch_jump(builder, no_sock, builder->count);
        patch_jump(builder, no_bound_if, builder->count);
    }

    if (ignored_route_cidr4_map_fd >= 0) {
        emit(builder, BPF_ST_MEM(BPF_W, BPF_REG_10, STACK_LPM4_KEY, 32));
        emit(builder, BPF_STX_MEM(BPF_W, BPF_REG_10, BPF_REG_7, STACK_LPM4_KEY + (int)offsetof(struct bpf2socks_lpm4_key, addr)));
        emit_ld_map_fd(builder, BPF_REG_1, ignored_route_cidr4_map_fd);
        emit(builder, BPF_MOV64_REG(BPF_REG_2, BPF_REG_10));
        emit(builder, BPF_ALU64_IMM_OP(BPF_ADD, BPF_REG_2, STACK_LPM4_KEY));
        emit(builder, BPF_CALL_FUNC(BPF_FUNC_map_lookup_elem));
        bypass_jumps[(*bypass_jump_count)++] = emit_jump(builder, BPF_JMP_IMM_OP(BPF_JNE, BPF_REG_0, 0, 0));
    }

    if (bypass_cidr4_map_fd >= 0) {
        emit(builder, BPF_ST_MEM(BPF_W, BPF_REG_10, STACK_LPM4_KEY, 32));
        emit(builder, BPF_STX_MEM(BPF_W, BPF_REG_10, BPF_REG_7, STACK_LPM4_KEY + (int)offsetof(struct bpf2socks_lpm4_key, addr)));
        emit_ld_map_fd(builder, BPF_REG_1, bypass_cidr4_map_fd);
        emit(builder, BPF_MOV64_REG(BPF_REG_2, BPF_REG_10));
        emit(builder, BPF_ALU64_IMM_OP(BPF_ADD, BPF_REG_2, STACK_LPM4_KEY));
        emit(builder, BPF_CALL_FUNC(BPF_FUNC_map_lookup_elem));
        bypass_jumps[(*bypass_jump_count)++] = emit_jump(builder, BPF_JMP_IMM_OP(BPF_JNE, BPF_REG_0, 0, 0));
    }

    for (size_t i = 0; i < force_proxy_jump_count; ++i) {
        patch_jump(builder, force_proxy_jumps[i], builder->count);
    }
}

static void emit_ipv4_policy_checks(
    struct bpf_builder *builder,
    const struct bpf2socks_policy_config *policy,
    int proxy_cidr4_map_fd,
    int bypass_cidr4_map_fd,
    int ignored_ifindex_map_fd,
    int ignored_route_cidr4_map_fd,
    uint8_t protocol,
    bool protocol_from_context,
    size_t *bypass_jumps,
    size_t *bypass_jump_count) {
    emit(builder, BPF_LDX_MEM(BPF_W, BPF_REG_7, BPF_REG_6, offsetof(struct bpf_sock_addr, user_ip4)));
    emit(builder, BPF_LDX_MEM(BPF_W, BPF_REG_8, BPF_REG_6, offsetof(struct bpf_sock_addr, user_port)));
    emit_ipv4_policy_checks_from_regs(
        builder,
        policy,
        proxy_cidr4_map_fd,
        bypass_cidr4_map_fd,
        ignored_ifindex_map_fd,
        ignored_route_cidr4_map_fd,
        protocol,
        protocol_from_context,
        bypass_jumps,
        bypass_jump_count);
}

static uint32_t ipv6_word_for_bpf_imm(const uint8_t addr[16], size_t offset) {
    uint32_t value = 0U;
    memcpy(&value, addr + offset, sizeof(value));
    return value;
}

static void emit_ipv4_mapped_ipv6_check(
    struct bpf_builder *builder,
    size_t *bypass_jumps,
    size_t *bypass_jump_count) {
    emit(builder, BPF_LDX_MEM(BPF_W, BPF_REG_2, BPF_REG_6, offsetof(struct bpf_sock_addr, user_ip6)));
    bypass_jumps[(*bypass_jump_count)++] = emit_jump(builder, BPF_JMP_IMM_OP(BPF_JNE, BPF_REG_2, 0, 0));
    emit(builder, BPF_LDX_MEM(BPF_W, BPF_REG_2, BPF_REG_6, offsetof(struct bpf_sock_addr, user_ip6) + 4));
    bypass_jumps[(*bypass_jump_count)++] = emit_jump(builder, BPF_JMP_IMM_OP(BPF_JNE, BPF_REG_2, 0, 0));
    emit(builder, BPF_LDX_MEM(BPF_W, BPF_REG_2, BPF_REG_6, offsetof(struct bpf_sock_addr, user_ip6) + 8));
    emit(builder, BPF_ENDIAN_OP(BPF_REG_2, 32));
    bypass_jumps[(*bypass_jump_count)++] = emit_jump(builder, BPF_JMP_IMM_OP(BPF_JNE, BPF_REG_2, 0x0000ffffU, 0));
}

static void emit_ipv4_mapped_ipv6_policy_checks(
    struct bpf_builder *builder,
    const struct bpf2socks_policy_config *policy,
    int proxy_cidr4_map_fd,
    int bypass_cidr4_map_fd,
    int ignored_ifindex_map_fd,
    int ignored_route_cidr4_map_fd,
    uint8_t protocol,
    bool protocol_from_context,
    size_t *bypass_jumps,
    size_t *bypass_jump_count) {
    emit(builder, BPF_LDX_MEM(BPF_W, BPF_REG_7, BPF_REG_6, offsetof(struct bpf_sock_addr, user_ip6) + 12));
    emit(builder, BPF_LDX_MEM(BPF_W, BPF_REG_8, BPF_REG_6, offsetof(struct bpf_sock_addr, user_port)));
    emit_ipv4_policy_checks_from_regs(
        builder,
        policy,
        proxy_cidr4_map_fd,
        bypass_cidr4_map_fd,
        ignored_ifindex_map_fd,
        ignored_route_cidr4_map_fd,
        protocol,
        protocol_from_context,
        bypass_jumps,
        bypass_jump_count);
}

static void emit_ipv6_policy_checks(
    struct bpf_builder *builder,
    const struct bpf2socks_policy_config *policy,
    int cidr6_map_fd,
    uint8_t protocol,
    bool protocol_from_context,
    size_t *bypass_jumps,
    size_t *bypass_jump_count) {
    emit(builder, BPF_LDX_MEM(BPF_W, BPF_REG_7, BPF_REG_6, offsetof(struct bpf_sock_addr, user_ip6)));
    emit(builder, BPF_LDX_MEM(BPF_W, BPF_REG_8, BPF_REG_6, offsetof(struct bpf_sock_addr, user_ip6) + 4));
    emit(builder, BPF_LDX_MEM(BPF_W, BPF_REG_9, BPF_REG_6, offsetof(struct bpf_sock_addr, user_ip6) + 8));
    emit(builder, BPF_LDX_MEM(BPF_W, BPF_REG_4, BPF_REG_6, offsetof(struct bpf_sock_addr, user_ip6) + 12));
    emit(builder, BPF_LDX_MEM(BPF_W, BPF_REG_5, BPF_REG_6, offsetof(struct bpf_sock_addr, user_port)));

    emit(builder, BPF_MOV64_REG(BPF_REG_2, BPF_REG_7));
    emit(builder, BPF_ALU64_REG_OP(BPF_OR, BPF_REG_2, BPF_REG_8));
    emit(builder, BPF_ALU64_REG_OP(BPF_OR, BPF_REG_2, BPF_REG_9));
    size_t not_zero_or_loopback = emit_jump(builder, BPF_JMP_IMM_OP(BPF_JNE, BPF_REG_2, 0, 0));
    emit(builder, BPF_MOV64_REG(BPF_REG_3, BPF_REG_4));
    emit(builder, BPF_ENDIAN_OP(BPF_REG_3, 32));
    bypass_jumps[(*bypass_jump_count)++] = emit_jump(builder, BPF_JMP_IMM_OP(BPF_JEQ, BPF_REG_3, 0, 0));
    bypass_jumps[(*bypass_jump_count)++] = emit_jump(builder, BPF_JMP_IMM_OP(BPF_JEQ, BPF_REG_3, 1, 0));
    patch_jump(builder, not_zero_or_loopback, builder->count);

    size_t force_proxy_jumps[4];
    size_t force_proxy_jump_count = 0;
    if (policy->enable_dns_hijack) {
        if (protocol_from_context) {
            emit(builder, BPF_LDX_MEM(BPF_W, BPF_REG_2, BPF_REG_6, offsetof(struct bpf_sock_addr, type)));
            size_t not_udp = emit_jump(builder, BPF_JMP_IMM_OP(BPF_JNE, BPF_REG_2, SOCK_DGRAM, 0));
            force_proxy_jumps[force_proxy_jump_count++] = emit_jump(builder, BPF_JMP_IMM_OP(BPF_JEQ, BPF_REG_5, htons(53), 0));
            patch_jump(builder, not_udp, builder->count);
        } else if (protocol == BPF2SOCKS_PROTO_UDP) {
            force_proxy_jumps[force_proxy_jump_count++] = emit_jump(builder, BPF_JMP_IMM_OP(BPF_JEQ, BPF_REG_5, htons(53), 0));
        }
    }

    if (cidr6_map_fd >= 0) {
        emit_zero_region(builder, STACK_LPM6_KEY, sizeof(struct bpf2socks_lpm6_key));
        emit(builder, BPF_ST_MEM(BPF_W, BPF_REG_10, STACK_LPM6_KEY, 128));
        emit(builder, BPF_STX_MEM(BPF_W, BPF_REG_10, BPF_REG_7, STACK_LPM6_KEY + (int)offsetof(struct bpf2socks_lpm6_key, addr)));
        emit(builder, BPF_STX_MEM(BPF_W, BPF_REG_10, BPF_REG_8, STACK_LPM6_KEY + (int)offsetof(struct bpf2socks_lpm6_key, addr) + 4));
        emit(builder, BPF_STX_MEM(BPF_W, BPF_REG_10, BPF_REG_9, STACK_LPM6_KEY + (int)offsetof(struct bpf2socks_lpm6_key, addr) + 8));
        emit(builder, BPF_STX_MEM(BPF_W, BPF_REG_10, BPF_REG_4, STACK_LPM6_KEY + (int)offsetof(struct bpf2socks_lpm6_key, addr) + 12));
        emit_ld_map_fd(builder, BPF_REG_1, cidr6_map_fd);
        emit(builder, BPF_MOV64_REG(BPF_REG_2, BPF_REG_10));
        emit(builder, BPF_ALU64_IMM_OP(BPF_ADD, BPF_REG_2, STACK_LPM6_KEY));
        emit(builder, BPF_CALL_FUNC(BPF_FUNC_map_lookup_elem));
        bypass_jumps[(*bypass_jump_count)++] = emit_jump(builder, BPF_JMP_IMM_OP(BPF_JNE, BPF_REG_0, 0, 0));
    }

    for (size_t i = 0; i < force_proxy_jump_count; ++i) {
        patch_jump(builder, force_proxy_jumps[i], builder->count);
    }
}

static void emit_token_update_and_rewrite(
    struct bpf_builder *builder,
    int token_map_fd,
    uint8_t protocol,
    bool protocol_from_context,
    uint16_t bridge_port,
    size_t *drop_jumps,
    size_t *drop_jump_count) {
    emit(builder, BPF_CALL_FUNC(BPF_FUNC_get_prandom_u32));
    emit(builder, BPF_MOV32_REG(BPF_REG_9, BPF_REG_0));
    emit(builder, BPF_ALU32_IMM_OP(BPF_AND, BPF_REG_9, 0x00ffffffU));
    emit(builder, BPF_ALU32_IMM_OP(BPF_OR, BPF_REG_9, 0x7f000000U));
    emit(builder, BPF_ENDIAN_OP(BPF_REG_9, 32));
    if (protocol_from_context) {
        emit(builder, BPF_LDX_MEM(BPF_W, BPF_REG_4, BPF_REG_6, offsetof(struct bpf_sock_addr, type)));
        emit(builder, BPF_MOV64_IMM(BPF_REG_5, BPF2SOCKS_PROTO_TCP));
        size_t not_udp = emit_jump(builder, BPF_JMP_IMM_OP(BPF_JNE, BPF_REG_4, SOCK_DGRAM, 0));
        emit(builder, BPF_MOV64_IMM(BPF_REG_5, BPF2SOCKS_PROTO_UDP));
        patch_jump(builder, not_udp, builder->count);
    } else {
        emit(builder, BPF_MOV64_IMM(BPF_REG_5, protocol));
    }

    emit_zero_region(builder, STACK_TOKEN_KEY, sizeof(struct bpf2socks_token_key));
    emit_zero_region(builder, STACK_ORIGINAL_DST, sizeof(struct bpf2socks_original_dst));

    emit(builder, BPF_ST_MEM(BPF_B, BPF_REG_10, STACK_TOKEN_KEY + (int)offsetof(struct bpf2socks_token_key, family), AF_INET));
    emit(builder, BPF_STX_MEM(BPF_B, BPF_REG_10, BPF_REG_5, STACK_TOKEN_KEY + (int)offsetof(struct bpf2socks_token_key, protocol)));
    emit(builder, BPF_ST_MEM(BPF_H, BPF_REG_10, STACK_TOKEN_KEY + (int)offsetof(struct bpf2socks_token_key, token_port), bridge_port));
    emit(builder, BPF_STX_MEM(BPF_W, BPF_REG_10, BPF_REG_9, STACK_TOKEN_KEY + (int)offsetof(struct bpf2socks_token_key, token_addr)));

    emit(builder, BPF_ST_MEM(BPF_B, BPF_REG_10, STACK_ORIGINAL_DST + (int)offsetof(struct bpf2socks_original_dst, family), AF_INET));
    emit(builder, BPF_STX_MEM(BPF_B, BPF_REG_10, BPF_REG_5, STACK_ORIGINAL_DST + (int)offsetof(struct bpf2socks_original_dst, protocol)));
    emit(builder, BPF_ENDIAN_OP(BPF_REG_8, 16));
    emit(builder, BPF_STX_MEM(BPF_H, BPF_REG_10, BPF_REG_8, STACK_ORIGINAL_DST + (int)offsetof(struct bpf2socks_original_dst, port)));
    emit(builder, BPF_STX_MEM(BPF_W, BPF_REG_10, BPF_REG_7, STACK_ORIGINAL_DST + (int)offsetof(struct bpf2socks_original_dst, addr)));

    emit_ld_map_fd(builder, BPF_REG_1, token_map_fd);
    emit(builder, BPF_MOV64_REG(BPF_REG_2, BPF_REG_10));
    emit(builder, BPF_ALU64_IMM_OP(BPF_ADD, BPF_REG_2, STACK_TOKEN_KEY));
    emit(builder, BPF_MOV64_REG(BPF_REG_3, BPF_REG_10));
    emit(builder, BPF_ALU64_IMM_OP(BPF_ADD, BPF_REG_3, STACK_ORIGINAL_DST));
    emit(builder, BPF_MOV64_IMM(BPF_REG_4, BPF_ANY));
    emit(builder, BPF_CALL_FUNC(BPF_FUNC_map_update_elem));
    drop_jumps[(*drop_jump_count)++] = emit_jump(builder, BPF_JMP_IMM_OP(BPF_JNE, BPF_REG_0, 0, 0));

    emit(builder, BPF_STX_MEM(BPF_W, BPF_REG_6, BPF_REG_9, offsetof(struct bpf_sock_addr, user_ip4)));
    emit(builder, BPF_ST_MEM(BPF_W, BPF_REG_6, offsetof(struct bpf_sock_addr, user_port), htons(bridge_port)));
}

static void emit_token_update_and_rewrite_v6(
    struct bpf_builder *builder,
    const struct bpf2socks_runtime_config *config,
    int token_map_fd,
    uint8_t protocol,
    bool protocol_from_context,
    uint16_t bridge_port,
    size_t *drop_jumps,
    size_t *drop_jump_count) {
    uint32_t prefix0 = ipv6_word_for_bpf_imm(config->token_ipv6_prefix, 0U);
    uint32_t prefix1 = ipv6_word_for_bpf_imm(config->token_ipv6_prefix, 4U);

    emit(builder, BPF_CALL_FUNC(BPF_FUNC_get_prandom_u32));
    emit(builder, BPF_MOV32_REG(BPF_REG_8, BPF_REG_0));
    emit(builder, BPF_CALL_FUNC(BPF_FUNC_get_prandom_u32));
    emit(builder, BPF_MOV32_REG(BPF_REG_9, BPF_REG_0));

    if (protocol_from_context) {
        emit(builder, BPF_LDX_MEM(BPF_W, BPF_REG_4, BPF_REG_6, offsetof(struct bpf_sock_addr, type)));
        emit(builder, BPF_MOV64_IMM(BPF_REG_5, BPF2SOCKS_PROTO_TCP));
        size_t not_udp = emit_jump(builder, BPF_JMP_IMM_OP(BPF_JNE, BPF_REG_4, SOCK_DGRAM, 0));
        emit(builder, BPF_MOV64_IMM(BPF_REG_5, BPF2SOCKS_PROTO_UDP));
        patch_jump(builder, not_udp, builder->count);
    } else {
        emit(builder, BPF_MOV64_IMM(BPF_REG_5, protocol));
    }

    emit_zero_region(builder, STACK_TOKEN_KEY, sizeof(struct bpf2socks_token_key));
    emit_zero_region(builder, STACK_ORIGINAL_DST, sizeof(struct bpf2socks_original_dst));

    emit(builder, BPF_ST_MEM(BPF_B, BPF_REG_10, STACK_TOKEN_KEY + (int)offsetof(struct bpf2socks_token_key, family), AF_INET6));
    emit(builder, BPF_STX_MEM(BPF_B, BPF_REG_10, BPF_REG_5, STACK_TOKEN_KEY + (int)offsetof(struct bpf2socks_token_key, protocol)));
    emit(builder, BPF_ST_MEM(BPF_H, BPF_REG_10, STACK_TOKEN_KEY + (int)offsetof(struct bpf2socks_token_key, token_port), bridge_port));
    emit(builder, BPF_ST_MEM(BPF_W, BPF_REG_10, STACK_TOKEN_KEY + (int)offsetof(struct bpf2socks_token_key, token_addr), prefix0));
    emit(builder, BPF_ST_MEM(BPF_W, BPF_REG_10, STACK_TOKEN_KEY + (int)offsetof(struct bpf2socks_token_key, token_addr) + 4, prefix1));
    emit(builder, BPF_STX_MEM(BPF_W, BPF_REG_10, BPF_REG_8, STACK_TOKEN_KEY + (int)offsetof(struct bpf2socks_token_key, token_addr) + 8));
    emit(builder, BPF_STX_MEM(BPF_W, BPF_REG_10, BPF_REG_9, STACK_TOKEN_KEY + (int)offsetof(struct bpf2socks_token_key, token_addr) + 12));

    emit(builder, BPF_ST_MEM(BPF_B, BPF_REG_10, STACK_ORIGINAL_DST + (int)offsetof(struct bpf2socks_original_dst, family), AF_INET6));
    emit(builder, BPF_STX_MEM(BPF_B, BPF_REG_10, BPF_REG_5, STACK_ORIGINAL_DST + (int)offsetof(struct bpf2socks_original_dst, protocol)));
    emit(builder, BPF_LDX_MEM(BPF_W, BPF_REG_4, BPF_REG_6, offsetof(struct bpf_sock_addr, user_port)));
    emit(builder, BPF_ENDIAN_OP(BPF_REG_4, 16));
    emit(builder, BPF_STX_MEM(BPF_H, BPF_REG_10, BPF_REG_4, STACK_ORIGINAL_DST + (int)offsetof(struct bpf2socks_original_dst, port)));
    emit(builder, BPF_LDX_MEM(BPF_W, BPF_REG_1, BPF_REG_6, offsetof(struct bpf_sock_addr, user_ip6)));
    emit(builder, BPF_LDX_MEM(BPF_W, BPF_REG_2, BPF_REG_6, offsetof(struct bpf_sock_addr, user_ip6) + 4));
    emit(builder, BPF_LDX_MEM(BPF_W, BPF_REG_3, BPF_REG_6, offsetof(struct bpf_sock_addr, user_ip6) + 8));
    emit(builder, BPF_LDX_MEM(BPF_W, BPF_REG_4, BPF_REG_6, offsetof(struct bpf_sock_addr, user_ip6) + 12));
    emit(builder, BPF_STX_MEM(BPF_W, BPF_REG_10, BPF_REG_1, STACK_ORIGINAL_DST + (int)offsetof(struct bpf2socks_original_dst, addr)));
    emit(builder, BPF_STX_MEM(BPF_W, BPF_REG_10, BPF_REG_2, STACK_ORIGINAL_DST + (int)offsetof(struct bpf2socks_original_dst, addr) + 4));
    emit(builder, BPF_STX_MEM(BPF_W, BPF_REG_10, BPF_REG_3, STACK_ORIGINAL_DST + (int)offsetof(struct bpf2socks_original_dst, addr) + 8));
    emit(builder, BPF_STX_MEM(BPF_W, BPF_REG_10, BPF_REG_4, STACK_ORIGINAL_DST + (int)offsetof(struct bpf2socks_original_dst, addr) + 12));

    emit_ld_map_fd(builder, BPF_REG_1, token_map_fd);
    emit(builder, BPF_MOV64_REG(BPF_REG_2, BPF_REG_10));
    emit(builder, BPF_ALU64_IMM_OP(BPF_ADD, BPF_REG_2, STACK_TOKEN_KEY));
    emit(builder, BPF_MOV64_REG(BPF_REG_3, BPF_REG_10));
    emit(builder, BPF_ALU64_IMM_OP(BPF_ADD, BPF_REG_3, STACK_ORIGINAL_DST));
    emit(builder, BPF_MOV64_IMM(BPF_REG_4, BPF_ANY));
    emit(builder, BPF_CALL_FUNC(BPF_FUNC_map_update_elem));
    drop_jumps[(*drop_jump_count)++] = emit_jump(builder, BPF_JMP_IMM_OP(BPF_JNE, BPF_REG_0, 0, 0));

    emit(builder, BPF_ST_MEM(BPF_W, BPF_REG_6, offsetof(struct bpf_sock_addr, user_ip6), prefix0));
    emit(builder, BPF_ST_MEM(BPF_W, BPF_REG_6, offsetof(struct bpf_sock_addr, user_ip6) + 4, prefix1));
    emit(builder, BPF_STX_MEM(BPF_W, BPF_REG_6, BPF_REG_8, offsetof(struct bpf_sock_addr, user_ip6) + 8));
    emit(builder, BPF_STX_MEM(BPF_W, BPF_REG_6, BPF_REG_9, offsetof(struct bpf_sock_addr, user_ip6) + 12));
    emit(builder, BPF_ST_MEM(BPF_W, BPF_REG_6, offsetof(struct bpf_sock_addr, user_port), htons(bridge_port)));
}

static void emit_ipv4_mapped_token_update_and_rewrite(
    struct bpf_builder *builder,
    int token_map_fd,
    uint8_t protocol,
    bool protocol_from_context,
    uint16_t bridge_port,
    size_t *drop_jumps,
    size_t *drop_jump_count) {
    emit(builder, BPF_CALL_FUNC(BPF_FUNC_get_prandom_u32));
    emit(builder, BPF_MOV32_REG(BPF_REG_9, BPF_REG_0));
    emit(builder, BPF_ALU32_IMM_OP(BPF_AND, BPF_REG_9, 0x00ffffffU));
    emit(builder, BPF_ALU32_IMM_OP(BPF_OR, BPF_REG_9, 0x7f000000U));
    emit(builder, BPF_ENDIAN_OP(BPF_REG_9, 32));
    if (protocol_from_context) {
        emit(builder, BPF_LDX_MEM(BPF_W, BPF_REG_4, BPF_REG_6, offsetof(struct bpf_sock_addr, type)));
        emit(builder, BPF_MOV64_IMM(BPF_REG_5, BPF2SOCKS_PROTO_TCP));
        size_t not_udp = emit_jump(builder, BPF_JMP_IMM_OP(BPF_JNE, BPF_REG_4, SOCK_DGRAM, 0));
        emit(builder, BPF_MOV64_IMM(BPF_REG_5, BPF2SOCKS_PROTO_UDP));
        patch_jump(builder, not_udp, builder->count);
    } else {
        emit(builder, BPF_MOV64_IMM(BPF_REG_5, protocol));
    }

    emit_zero_region(builder, STACK_TOKEN_KEY, sizeof(struct bpf2socks_token_key));
    emit_zero_region(builder, STACK_ORIGINAL_DST, sizeof(struct bpf2socks_original_dst));

    emit(builder, BPF_ST_MEM(BPF_B, BPF_REG_10, STACK_TOKEN_KEY + (int)offsetof(struct bpf2socks_token_key, family), AF_INET));
    emit(builder, BPF_STX_MEM(BPF_B, BPF_REG_10, BPF_REG_5, STACK_TOKEN_KEY + (int)offsetof(struct bpf2socks_token_key, protocol)));
    emit(builder, BPF_ST_MEM(BPF_H, BPF_REG_10, STACK_TOKEN_KEY + (int)offsetof(struct bpf2socks_token_key, token_port), bridge_port));
    emit(builder, BPF_STX_MEM(BPF_W, BPF_REG_10, BPF_REG_9, STACK_TOKEN_KEY + (int)offsetof(struct bpf2socks_token_key, token_addr)));

    emit(builder, BPF_ST_MEM(BPF_B, BPF_REG_10, STACK_ORIGINAL_DST + (int)offsetof(struct bpf2socks_original_dst, family), AF_INET));
    emit(builder, BPF_STX_MEM(BPF_B, BPF_REG_10, BPF_REG_5, STACK_ORIGINAL_DST + (int)offsetof(struct bpf2socks_original_dst, protocol)));
    emit(builder, BPF_LDX_MEM(BPF_W, BPF_REG_8, BPF_REG_6, offsetof(struct bpf_sock_addr, user_port)));
    emit(builder, BPF_ENDIAN_OP(BPF_REG_8, 16));
    emit(builder, BPF_STX_MEM(BPF_H, BPF_REG_10, BPF_REG_8, STACK_ORIGINAL_DST + (int)offsetof(struct bpf2socks_original_dst, port)));
    emit(builder, BPF_LDX_MEM(BPF_W, BPF_REG_7, BPF_REG_6, offsetof(struct bpf_sock_addr, user_ip6) + 12));
    emit(builder, BPF_STX_MEM(BPF_W, BPF_REG_10, BPF_REG_7, STACK_ORIGINAL_DST + (int)offsetof(struct bpf2socks_original_dst, addr)));

    emit_ld_map_fd(builder, BPF_REG_1, token_map_fd);
    emit(builder, BPF_MOV64_REG(BPF_REG_2, BPF_REG_10));
    emit(builder, BPF_ALU64_IMM_OP(BPF_ADD, BPF_REG_2, STACK_TOKEN_KEY));
    emit(builder, BPF_MOV64_REG(BPF_REG_3, BPF_REG_10));
    emit(builder, BPF_ALU64_IMM_OP(BPF_ADD, BPF_REG_3, STACK_ORIGINAL_DST));
    emit(builder, BPF_MOV64_IMM(BPF_REG_4, BPF_ANY));
    emit(builder, BPF_CALL_FUNC(BPF_FUNC_map_update_elem));
    drop_jumps[(*drop_jump_count)++] = emit_jump(builder, BPF_JMP_IMM_OP(BPF_JNE, BPF_REG_0, 0, 0));

    emit(builder, BPF_ST_MEM(BPF_W, BPF_REG_6, offsetof(struct bpf_sock_addr, user_ip6), 0));
    emit(builder, BPF_ST_MEM(BPF_W, BPF_REG_6, offsetof(struct bpf_sock_addr, user_ip6) + 4, 0));
    emit(builder, BPF_ST_MEM(BPF_W, BPF_REG_6, offsetof(struct bpf_sock_addr, user_ip6) + 8, 0xffff0000U));
    emit(builder, BPF_STX_MEM(BPF_W, BPF_REG_6, BPF_REG_9, offsetof(struct bpf_sock_addr, user_ip6) + 12));
    emit(builder, BPF_ST_MEM(BPF_W, BPF_REG_6, offsetof(struct bpf_sock_addr, user_port), htons(bridge_port)));
}

static int build_ipv4_sock_addr_prog(
    const struct bpf2socks_policy_config *policy,
    int uid_map_fd,
    int proxy_cidr4_map_fd,
    int bypass_cidr4_map_fd,
    int ignored_ifindex_map_fd,
    int ignored_route_cidr4_map_fd,
    int token_map_fd,
    uint8_t protocol,
    bool protocol_from_context,
    uint16_t bridge_port,
    enum bpf_attach_type attach_type,
    const char *name,
    bool log_error) {
    struct bpf_builder b = {0};
    size_t bypass_jumps[64];
    size_t bypass_jump_count = 0;
    size_t drop_jumps[16];
    size_t drop_jump_count = 0;

    emit(&b, BPF_MOV64_REG(BPF_REG_6, BPF_REG_1));
    // Connected UDP stacks observe getpeername(); keep the peer unchanged and redirect packets in UDP_SENDMSG.
    if (attach_type == BPF_CGROUP_INET4_CONNECT && protocol_from_context) {
        emit(&b, BPF_LDX_MEM(BPF_W, BPF_REG_2, BPF_REG_6, offsetof(struct bpf_sock_addr, protocol)));
        bypass_jumps[bypass_jump_count++] =
            emit_jump(&b, BPF_JMP_IMM_OP(BPF_JNE, BPF_REG_2, BPF2SOCKS_PROTO_TCP, 0));
    }
    emit_uid_policy(&b, policy, uid_map_fd, bypass_jumps, &bypass_jump_count, drop_jumps, &drop_jump_count);
    emit_ipv4_policy_checks(
        &b,
        policy,
        proxy_cidr4_map_fd,
        bypass_cidr4_map_fd,
        ignored_ifindex_map_fd,
        ignored_route_cidr4_map_fd,
        protocol,
        protocol_from_context,
        bypass_jumps,
        &bypass_jump_count);
    emit_token_update_and_rewrite(&b, token_map_fd, protocol, protocol_from_context, bridge_port, drop_jumps, &drop_jump_count);
    size_t allow_label = emit_exit(&b, 1);
    size_t drop_label = emit_exit(&b, 0);

    for (size_t i = 0; i < bypass_jump_count; ++i) {
        patch_jump(&b, bypass_jumps[i], allow_label);
    }
    for (size_t i = 0; i < drop_jump_count; ++i) {
        patch_jump(&b, drop_jumps[i], drop_label);
    }

    if (b.overflow) {
        errno = EMSGSIZE;
        return -1;
    }
    return bpf2socks_load_prog(
        b.insns,
        b.count,
        name,
        BPF_PROG_TYPE_CGROUP_SOCK_ADDR,
        attach_type,
        log_error);
}

static int build_ipv6_sock_addr_prog(
    const struct bpf2socks_policy_config *policy,
    const struct bpf2socks_runtime_config *config,
    int uid_map_fd,
    int cidr6_map_fd,
    int token_map_fd,
    uint8_t protocol,
    bool protocol_from_context,
    uint16_t bridge_port,
    enum bpf_attach_type attach_type,
    const char *name,
    bool log_error) {
    struct bpf_builder b = {0};
    size_t bypass_jumps[64];
    size_t bypass_jump_count = 0;
    size_t drop_jumps[16];
    size_t drop_jump_count = 0;

    emit(&b, BPF_MOV64_REG(BPF_REG_6, BPF_REG_1));
    // Connected UDP stacks observe getpeername(); keep the peer unchanged and redirect packets in UDP_SENDMSG.
    if (attach_type == BPF_CGROUP_INET6_CONNECT && protocol_from_context) {
        emit(&b, BPF_LDX_MEM(BPF_W, BPF_REG_2, BPF_REG_6, offsetof(struct bpf_sock_addr, protocol)));
        bypass_jumps[bypass_jump_count++] =
            emit_jump(&b, BPF_JMP_IMM_OP(BPF_JNE, BPF_REG_2, BPF2SOCKS_PROTO_TCP, 0));
    }
    emit_uid_policy(&b, policy, uid_map_fd, bypass_jumps, &bypass_jump_count, drop_jumps, &drop_jump_count);
    emit_ipv6_policy_checks(
        &b,
        policy,
        cidr6_map_fd,
        protocol,
        protocol_from_context,
        bypass_jumps,
        &bypass_jump_count);
    emit_token_update_and_rewrite_v6(
        &b,
        config,
        token_map_fd,
        protocol,
        protocol_from_context,
        bridge_port,
        drop_jumps,
        &drop_jump_count);
    size_t allow_label = emit_exit(&b, 1);
    size_t drop_label = emit_exit(&b, 0);

    for (size_t i = 0; i < bypass_jump_count; ++i) {
        patch_jump(&b, bypass_jumps[i], allow_label);
    }
    for (size_t i = 0; i < drop_jump_count; ++i) {
        patch_jump(&b, drop_jumps[i], drop_label);
    }

    if (b.overflow) {
        errno = EMSGSIZE;
        return -1;
    }
    return bpf2socks_load_prog(
        b.insns,
        b.count,
        name,
        BPF_PROG_TYPE_CGROUP_SOCK_ADDR,
        attach_type,
        log_error);
}

static int build_ipv4_mapped_ipv6_sock_addr_prog(
    const struct bpf2socks_policy_config *policy,
    int uid_map_fd,
    int proxy_cidr4_map_fd,
    int bypass_cidr4_map_fd,
    int ignored_ifindex_map_fd,
    int ignored_route_cidr4_map_fd,
    int token_map_fd,
    uint16_t bridge_port,
    const char *name,
    bool log_error) {
    struct bpf_builder b = {0};
    size_t bypass_jumps[64];
    size_t bypass_jump_count = 0;
    size_t drop_jumps[16];
    size_t drop_jump_count = 0;

    emit(&b, BPF_MOV64_REG(BPF_REG_6, BPF_REG_1));
    emit(&b, BPF_LDX_MEM(BPF_W, BPF_REG_2, BPF_REG_6, offsetof(struct bpf_sock_addr, protocol)));
    bypass_jumps[bypass_jump_count++] =
        emit_jump(&b, BPF_JMP_IMM_OP(BPF_JNE, BPF_REG_2, BPF2SOCKS_PROTO_TCP, 0));
    emit_uid_policy(&b, policy, uid_map_fd, bypass_jumps, &bypass_jump_count, drop_jumps, &drop_jump_count);
    emit_ipv4_mapped_ipv6_check(&b, bypass_jumps, &bypass_jump_count);
    emit_ipv4_mapped_ipv6_policy_checks(
        &b,
        policy,
        proxy_cidr4_map_fd,
        bypass_cidr4_map_fd,
        ignored_ifindex_map_fd,
        ignored_route_cidr4_map_fd,
        BPF2SOCKS_PROTO_TCP,
        true,
        bypass_jumps,
        &bypass_jump_count);
    emit_ipv4_mapped_token_update_and_rewrite(
        &b,
        token_map_fd,
        BPF2SOCKS_PROTO_TCP,
        true,
        bridge_port,
        drop_jumps,
        &drop_jump_count);
    size_t allow_label = emit_exit(&b, 1);
    size_t drop_label = emit_exit(&b, 0);

    for (size_t i = 0; i < bypass_jump_count; ++i) {
        patch_jump(&b, bypass_jumps[i], allow_label);
    }
    for (size_t i = 0; i < drop_jump_count; ++i) {
        patch_jump(&b, drop_jumps[i], drop_label);
    }

    if (b.overflow) {
        errno = EMSGSIZE;
        return -1;
    }
    return bpf2socks_load_prog(
        b.insns,
        b.count,
        name,
        BPF_PROG_TYPE_CGROUP_SOCK_ADDR,
        BPF_CGROUP_INET6_CONNECT,
        log_error);
}

static int build_udp4_recvmsg_prog(int token_map_fd, const char *name, bool log_error) {
    struct bpf_builder b = {0};
    size_t bypass_jumps[8];
    size_t bypass_jump_count = 0;

    emit(&b, BPF_MOV64_REG(BPF_REG_6, BPF_REG_1));
    emit(&b, BPF_LDX_MEM(BPF_W, BPF_REG_7, BPF_REG_6, offsetof(struct bpf_sock_addr, user_ip4)));
    emit(&b, BPF_LDX_MEM(BPF_W, BPF_REG_8, BPF_REG_6, offsetof(struct bpf_sock_addr, user_port)));

    emit(&b, BPF_MOV64_REG(BPF_REG_2, BPF_REG_7));
    emit(&b, BPF_ENDIAN_OP(BPF_REG_2, 32));
    emit(&b, BPF_ALU64_IMM_OP(BPF_AND, BPF_REG_2, 0xff000000U));
    bypass_jumps[bypass_jump_count++] = emit_jump(&b, BPF_JMP_IMM_OP(BPF_JNE, BPF_REG_2, 0x7f000000U, 0));

    emit_zero_region(&b, STACK_TOKEN_KEY, sizeof(struct bpf2socks_token_key));
    emit(&b, BPF_ST_MEM(BPF_B, BPF_REG_10, STACK_TOKEN_KEY + (int)offsetof(struct bpf2socks_token_key, family), AF_INET));
    emit(&b, BPF_ST_MEM(BPF_B, BPF_REG_10, STACK_TOKEN_KEY + (int)offsetof(struct bpf2socks_token_key, protocol), BPF2SOCKS_PROTO_UDP));
    emit(&b, BPF_ENDIAN_OP(BPF_REG_8, 16));
    emit(&b, BPF_STX_MEM(BPF_H, BPF_REG_10, BPF_REG_8, STACK_TOKEN_KEY + (int)offsetof(struct bpf2socks_token_key, token_port)));
    emit(&b, BPF_STX_MEM(BPF_W, BPF_REG_10, BPF_REG_7, STACK_TOKEN_KEY + (int)offsetof(struct bpf2socks_token_key, token_addr)));

    emit_ld_map_fd(&b, BPF_REG_1, token_map_fd);
    emit(&b, BPF_MOV64_REG(BPF_REG_2, BPF_REG_10));
    emit(&b, BPF_ALU64_IMM_OP(BPF_ADD, BPF_REG_2, STACK_TOKEN_KEY));
    emit(&b, BPF_CALL_FUNC(BPF_FUNC_map_lookup_elem));
    bypass_jumps[bypass_jump_count++] = emit_jump(&b, BPF_JMP_IMM_OP(BPF_JEQ, BPF_REG_0, 0, 0));

    emit(&b, BPF_LDX_MEM(BPF_W, BPF_REG_7, BPF_REG_0, offsetof(struct bpf2socks_original_dst, addr)));
    emit(&b, BPF_LDX_MEM(BPF_H, BPF_REG_8, BPF_REG_0, offsetof(struct bpf2socks_original_dst, port)));
    emit(&b, BPF_ENDIAN_OP(BPF_REG_8, 16));
    emit(&b, BPF_STX_MEM(BPF_W, BPF_REG_6, BPF_REG_7, offsetof(struct bpf_sock_addr, user_ip4)));
    emit(&b, BPF_STX_MEM(BPF_W, BPF_REG_6, BPF_REG_8, offsetof(struct bpf_sock_addr, user_port)));

    size_t allow_label = emit_exit(&b, 1);
    for (size_t i = 0; i < bypass_jump_count; ++i) {
        patch_jump(&b, bypass_jumps[i], allow_label);
    }

    if (b.overflow) {
        errno = EMSGSIZE;
        return -1;
    }
    return bpf2socks_load_prog(
        b.insns,
        b.count,
        name,
        BPF_PROG_TYPE_CGROUP_SOCK_ADDR,
        BPF_CGROUP_UDP4_RECVMSG,
        log_error);
}

static int build_udp6_recvmsg_prog(int token_map_fd, const char *name, bool log_error) {
    struct bpf_builder b = {0};
    size_t bypass_jumps[8];
    size_t bypass_jump_count = 0;

    emit(&b, BPF_MOV64_REG(BPF_REG_6, BPF_REG_1));

    emit_zero_region(&b, STACK_TOKEN_KEY, sizeof(struct bpf2socks_token_key));
    emit(&b, BPF_ST_MEM(BPF_B, BPF_REG_10, STACK_TOKEN_KEY + (int)offsetof(struct bpf2socks_token_key, family), AF_INET6));
    emit(&b, BPF_ST_MEM(BPF_B, BPF_REG_10, STACK_TOKEN_KEY + (int)offsetof(struct bpf2socks_token_key, protocol), BPF2SOCKS_PROTO_UDP));
    emit(&b, BPF_LDX_MEM(BPF_W, BPF_REG_7, BPF_REG_6, offsetof(struct bpf_sock_addr, user_port)));
    emit(&b, BPF_ENDIAN_OP(BPF_REG_7, 16));
    emit(&b, BPF_STX_MEM(BPF_H, BPF_REG_10, BPF_REG_7, STACK_TOKEN_KEY + (int)offsetof(struct bpf2socks_token_key, token_port)));
    emit(&b, BPF_LDX_MEM(BPF_W, BPF_REG_7, BPF_REG_6, offsetof(struct bpf_sock_addr, user_ip6)));
    emit(&b, BPF_LDX_MEM(BPF_W, BPF_REG_8, BPF_REG_6, offsetof(struct bpf_sock_addr, user_ip6) + 4));
    emit(&b, BPF_LDX_MEM(BPF_W, BPF_REG_9, BPF_REG_6, offsetof(struct bpf_sock_addr, user_ip6) + 8));
    emit(&b, BPF_LDX_MEM(BPF_W, BPF_REG_4, BPF_REG_6, offsetof(struct bpf_sock_addr, user_ip6) + 12));
    emit(&b, BPF_STX_MEM(BPF_W, BPF_REG_10, BPF_REG_7, STACK_TOKEN_KEY + (int)offsetof(struct bpf2socks_token_key, token_addr)));
    emit(&b, BPF_STX_MEM(BPF_W, BPF_REG_10, BPF_REG_8, STACK_TOKEN_KEY + (int)offsetof(struct bpf2socks_token_key, token_addr) + 4));
    emit(&b, BPF_STX_MEM(BPF_W, BPF_REG_10, BPF_REG_9, STACK_TOKEN_KEY + (int)offsetof(struct bpf2socks_token_key, token_addr) + 8));
    emit(&b, BPF_STX_MEM(BPF_W, BPF_REG_10, BPF_REG_4, STACK_TOKEN_KEY + (int)offsetof(struct bpf2socks_token_key, token_addr) + 12));

    emit_ld_map_fd(&b, BPF_REG_1, token_map_fd);
    emit(&b, BPF_MOV64_REG(BPF_REG_2, BPF_REG_10));
    emit(&b, BPF_ALU64_IMM_OP(BPF_ADD, BPF_REG_2, STACK_TOKEN_KEY));
    emit(&b, BPF_CALL_FUNC(BPF_FUNC_map_lookup_elem));
    bypass_jumps[bypass_jump_count++] = emit_jump(&b, BPF_JMP_IMM_OP(BPF_JEQ, BPF_REG_0, 0, 0));

    emit(&b, BPF_LDX_MEM(BPF_W, BPF_REG_7, BPF_REG_0, offsetof(struct bpf2socks_original_dst, addr)));
    emit(&b, BPF_LDX_MEM(BPF_W, BPF_REG_8, BPF_REG_0, offsetof(struct bpf2socks_original_dst, addr) + 4));
    emit(&b, BPF_LDX_MEM(BPF_W, BPF_REG_9, BPF_REG_0, offsetof(struct bpf2socks_original_dst, addr) + 8));
    emit(&b, BPF_LDX_MEM(BPF_W, BPF_REG_4, BPF_REG_0, offsetof(struct bpf2socks_original_dst, addr) + 12));
    emit(&b, BPF_STX_MEM(BPF_W, BPF_REG_6, BPF_REG_7, offsetof(struct bpf_sock_addr, user_ip6)));
    emit(&b, BPF_STX_MEM(BPF_W, BPF_REG_6, BPF_REG_8, offsetof(struct bpf_sock_addr, user_ip6) + 4));
    emit(&b, BPF_STX_MEM(BPF_W, BPF_REG_6, BPF_REG_9, offsetof(struct bpf_sock_addr, user_ip6) + 8));
    emit(&b, BPF_STX_MEM(BPF_W, BPF_REG_6, BPF_REG_4, offsetof(struct bpf_sock_addr, user_ip6) + 12));
    emit(&b, BPF_LDX_MEM(BPF_H, BPF_REG_7, BPF_REG_0, offsetof(struct bpf2socks_original_dst, port)));
    emit(&b, BPF_ENDIAN_OP(BPF_REG_7, 16));
    emit(&b, BPF_STX_MEM(BPF_W, BPF_REG_6, BPF_REG_7, offsetof(struct bpf_sock_addr, user_port)));

    size_t allow_label = emit_exit(&b, 1);
    for (size_t i = 0; i < bypass_jump_count; ++i) {
        patch_jump(&b, bypass_jumps[i], allow_label);
    }

    if (b.overflow) {
        errno = EMSGSIZE;
        return -1;
    }
    return bpf2socks_load_prog(
        b.insns,
        b.count,
        name,
        BPF_PROG_TYPE_CGROUP_SOCK_ADDR,
        BPF_CGROUP_UDP6_RECVMSG,
        log_error);
}

static int load_noop_sock_addr_prog(enum bpf_attach_type attach_type, const char *name) {
    struct bpf_insn insns[] = {
        BPF_MOV64_IMM(BPF_REG_0, 1),
        BPF_EXIT_INSN(),
    };
    return bpf2socks_load_prog(
        insns,
        ARRAY_SIZE(insns),
        name,
        BPF_PROG_TYPE_CGROUP_SOCK_ADDR,
        attach_type,
        false);
}

static int open_cgroup_path(const char *path) {
    const char *actual = path != NULL && path[0] != '\0' ? path : BPF2SOCKS_DEFAULT_CGROUP_PATH;
    return open(actual, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
}

int bpf2socks_bpf_probe(
    const struct bpf2socks_runtime_config *config,
    const struct bpf2socks_policy_config *policy,
    char *message,
    size_t message_size) {
    struct bpf2socks_policy_config probe_policy;
    if (policy == NULL) {
        memset(&probe_policy, 0, sizeof(probe_policy));
        probe_policy.mode = BPF2SOCKS_MODE_GLOBAL;
        policy = &probe_policy;
    }
    struct bpf2socks_runtime_config probe_config;
    if (config == NULL) {
        memset(&probe_config, 0, sizeof(probe_config));
        probe_config.listen_port = 65532U;
        probe_config.token_ipv6_prefix_bits = BPF2SOCKS_TOKEN_IPV6_PREFIX_BITS;
        (void)inet_pton(AF_INET6, "fd7a:7374:6572:6973::", probe_config.token_ipv6_prefix);
        config = &probe_config;
    }
    if (bpf2socks_splice_probe(message, message_size) < 0) {
        return -1;
    }
    bool need_uid_map = uid_map_required(policy);
    bool need_proxy_cidr4_map = proxy_cidr4_map_required(policy);
    bool need_bypass_cidr4_map = bypass_cidr4_map_required(policy);
    bool need_bypass_cidr6_map = bypass_cidr6_map_required(policy);
    int uid_fd = need_uid_map
        ? bpf2socks_create_map(BPF_MAP_TYPE_HASH, sizeof(uint32_t), sizeof(uint32_t), 16U, 0U)
        : -1;
    int proxy_cidr4_fd = need_proxy_cidr4_map
        ? bpf2socks_create_map(
              BPF_MAP_TYPE_LPM_TRIE,
              sizeof(struct bpf2socks_lpm4_key),
              sizeof(uint8_t),
              16U,
              BPF_F_NO_PREALLOC)
        : -1;
    int bypass_cidr4_fd = need_bypass_cidr4_map
        ? bpf2socks_create_map(
              BPF_MAP_TYPE_LPM_TRIE,
              sizeof(struct bpf2socks_lpm4_key),
              sizeof(uint8_t),
              16U,
              BPF_F_NO_PREALLOC)
        : -1;
    int token_fd = create_token_map(16U);
    int cidr6_fd = need_bypass_cidr6_map
        ? bpf2socks_create_map(
              BPF_MAP_TYPE_LPM_TRIE,
              sizeof(struct bpf2socks_lpm6_key),
              sizeof(uint8_t),
              16U,
              BPF_F_NO_PREALLOC)
        : -1;
    bool interface_policy_enabled = policy->ignored_interface_count > 0U;
    int ignored_ifindex_fd = interface_policy_enabled
        ? bpf2socks_create_map(BPF_MAP_TYPE_HASH, sizeof(uint32_t), sizeof(uint8_t), 16U, 0U)
        : -1;
    int ignored_route_cidr4_fd = interface_policy_enabled
        ? bpf2socks_create_map(
              BPF_MAP_TYPE_LPM_TRIE,
              sizeof(struct bpf2socks_lpm4_key),
              sizeof(uint8_t),
              16U,
              BPF_F_NO_PREALLOC)
        : -1;
    int cgroup_fd = -1;
    int connect4_fd = -1;
    int connect6_fd = -1;
    int connect6_v4mapped_fd = -1;
    int udp4_fd = -1;
    int udp6_fd = -1;
    int udp4_recv_fd = -1;
    int udp6_recv_fd = -1;
    int noop_connect4_fd = -1;
    int noop_connect6_fd = -1;
    int noop_udp4_fd = -1;
    int noop_udp6_fd = -1;
    int result = -1;

    if ((need_uid_map && uid_fd < 0) ||
        (need_proxy_cidr4_map && proxy_cidr4_fd < 0) ||
        (need_bypass_cidr4_map && bypass_cidr4_fd < 0) ||
        token_fd < 0 ||
        (need_bypass_cidr6_map && cidr6_fd < 0) ||
        (interface_policy_enabled && (ignored_ifindex_fd < 0 || ignored_route_cidr4_fd < 0))) {
        snprintf(message, message_size, "required bpf maps are unavailable: errno=%d", errno);
        goto done;
    }

    uint16_t bridge_port = config != NULL && config->listen_port != 0U ? config->listen_port : 65532U;
    connect4_fd = build_ipv4_sock_addr_prog(
        policy,
        uid_fd,
        proxy_cidr4_fd,
        bypass_cidr4_fd,
        ignored_ifindex_fd,
        ignored_route_cidr4_fd,
        token_fd,
        BPF2SOCKS_PROTO_TCP,
        true,
        bridge_port,
        BPF_CGROUP_INET4_CONNECT,
        "b2s_conn4",
        false);
    if (connect4_fd < 0) {
        snprintf(message, message_size, "cgroup connect4 program cannot be loaded: errno=%d", errno);
        goto done;
    }
    udp4_fd = build_ipv4_sock_addr_prog(
        policy,
        uid_fd,
        proxy_cidr4_fd,
        bypass_cidr4_fd,
        ignored_ifindex_fd,
        ignored_route_cidr4_fd,
        token_fd,
        BPF2SOCKS_PROTO_UDP,
        false,
        bridge_port,
        BPF_CGROUP_UDP4_SENDMSG,
        "b2s_udp4",
        false);
    if (udp4_fd < 0) {
        snprintf(message, message_size, "cgroup udp4 sendmsg program cannot be loaded: errno=%d", errno);
        goto done;
    }
    udp4_recv_fd = build_udp4_recvmsg_prog(token_fd, "b2s_urcv4", false);
    if (udp4_recv_fd < 0) {
        snprintf(message, message_size, "cgroup udp4 recvmsg program cannot be loaded: errno=%d", errno);
        goto done;
    }
    if (policy->enable_ipv6) {
        connect6_fd = build_ipv6_sock_addr_prog(
            policy,
            config,
            uid_fd,
            cidr6_fd,
            token_fd,
            BPF2SOCKS_PROTO_TCP,
            true,
            bridge_port,
            BPF_CGROUP_INET6_CONNECT,
            "b2s_conn6",
            false);
        udp6_fd = build_ipv6_sock_addr_prog(
            policy,
            config,
            uid_fd,
            cidr6_fd,
            token_fd,
            BPF2SOCKS_PROTO_UDP,
            false,
            bridge_port,
            BPF_CGROUP_UDP6_SENDMSG,
            "b2s_udp6",
            false);
        udp6_recv_fd = build_udp6_recvmsg_prog(token_fd, "b2s_urcv6", false);
        if (connect6_fd < 0 || udp6_fd < 0 || udp6_recv_fd < 0) {
            snprintf(message, message_size, "cgroup IPv6 programs cannot be loaded: errno=%d", errno);
            goto done;
        }
    } else {
        connect6_v4mapped_fd = build_ipv4_mapped_ipv6_sock_addr_prog(
            policy,
            uid_fd,
            proxy_cidr4_fd,
            bypass_cidr4_fd,
            ignored_ifindex_fd,
            ignored_route_cidr4_fd,
            token_fd,
            bridge_port,
            "b2s_c6v4m",
            false);
        if (connect6_v4mapped_fd < 0) {
            snprintf(message, message_size, "cgroup IPv4-mapped connect6 program cannot be loaded: errno=%d", errno);
            goto done;
        }
    }

    cgroup_fd = open_cgroup_path(config != NULL ? config->cgroup_path : NULL);
    if (cgroup_fd < 0) {
        snprintf(message, message_size, "cgroup path cannot be opened: errno=%d", errno);
        goto done;
    }

    noop_connect4_fd = load_noop_sock_addr_prog(BPF_CGROUP_INET4_CONNECT, "b2s_pconn4");
    noop_udp4_fd = load_noop_sock_addr_prog(BPF_CGROUP_UDP4_SENDMSG, "b2s_pudp4");
    bool need_connect6_attach = policy->enable_ipv6 || connect6_v4mapped_fd >= 0;
    if (need_connect6_attach) {
        noop_connect6_fd = load_noop_sock_addr_prog(BPF_CGROUP_INET6_CONNECT, "b2s_pconn6");
    }
    if (policy->enable_ipv6) {
        noop_udp6_fd = load_noop_sock_addr_prog(BPF_CGROUP_UDP6_SENDMSG, "b2s_pudp6");
    }
    if (noop_connect4_fd < 0 || noop_udp4_fd < 0 ||
        (need_connect6_attach && noop_connect6_fd < 0) ||
        (policy->enable_ipv6 && noop_udp6_fd < 0)) {
        snprintf(message, message_size, "cgroup probe program cannot be loaded: errno=%d", errno);
        goto done;
    }
    if (bpf2socks_attach_prog(cgroup_fd, noop_connect4_fd, BPF_CGROUP_INET4_CONNECT) < 0) {
        snprintf(message, message_size, "cgroup connect4 attach is unavailable: errno=%d", errno);
        goto done;
    }
    (void)bpf2socks_detach_prog(cgroup_fd, noop_connect4_fd, BPF_CGROUP_INET4_CONNECT);
    if (bpf2socks_attach_prog(cgroup_fd, noop_udp4_fd, BPF_CGROUP_UDP4_SENDMSG) < 0) {
        snprintf(message, message_size, "cgroup udp4 sendmsg attach is unavailable: errno=%d", errno);
        goto done;
    }
    (void)bpf2socks_detach_prog(cgroup_fd, noop_udp4_fd, BPF_CGROUP_UDP4_SENDMSG);
    if (bpf2socks_attach_prog(cgroup_fd, udp4_recv_fd, BPF_CGROUP_UDP4_RECVMSG) < 0) {
        snprintf(message, message_size, "cgroup udp4 recvmsg attach is unavailable: errno=%d", errno);
        goto done;
    }
    (void)bpf2socks_detach_prog(cgroup_fd, udp4_recv_fd, BPF_CGROUP_UDP4_RECVMSG);
    if (need_connect6_attach) {
        if (bpf2socks_attach_prog(cgroup_fd, noop_connect6_fd, BPF_CGROUP_INET6_CONNECT) < 0) {
            snprintf(message, message_size, "cgroup connect6 attach is unavailable: errno=%d", errno);
            goto done;
        }
        (void)bpf2socks_detach_prog(cgroup_fd, noop_connect6_fd, BPF_CGROUP_INET6_CONNECT);
    }
    if (policy->enable_ipv6) {
        if (bpf2socks_attach_prog(cgroup_fd, noop_udp6_fd, BPF_CGROUP_UDP6_SENDMSG) < 0) {
            snprintf(message, message_size, "cgroup udp6 sendmsg attach is unavailable: errno=%d", errno);
            goto done;
        }
        (void)bpf2socks_detach_prog(cgroup_fd, noop_udp6_fd, BPF_CGROUP_UDP6_SENDMSG);
        if (bpf2socks_attach_prog(cgroup_fd, udp6_recv_fd, BPF_CGROUP_UDP6_RECVMSG) < 0) {
            snprintf(message, message_size, "cgroup udp6 recvmsg attach is unavailable: errno=%d", errno);
            goto done;
        }
        (void)bpf2socks_detach_prog(cgroup_fd, udp6_recv_fd, BPF_CGROUP_UDP6_RECVMSG);
    }

    if (policy->hotspot_interface_prefix_count > 0U) {
        if (bpf2socks_prerouting_policy_probe(policy->enable_ipv6, message, message_size) < 0) {
            goto done;
        }
        if (bpf2socks_sk_lookup_probe(policy->enable_ipv6, message, message_size) < 0) {
            goto done;
        }
    }

    snprintf(message, message_size, "ok");
    result = 0;

done:
    close_fd(&noop_udp6_fd);
    close_fd(&noop_connect6_fd);
    close_fd(&noop_udp4_fd);
    close_fd(&noop_connect4_fd);
    close_fd(&udp6_recv_fd);
    close_fd(&udp4_recv_fd);
    close_fd(&udp6_fd);
    close_fd(&udp4_fd);
    close_fd(&connect6_v4mapped_fd);
    close_fd(&connect6_fd);
    close_fd(&connect4_fd);
    close_fd(&cgroup_fd);
    close_fd(&ignored_route_cidr4_fd);
    close_fd(&ignored_ifindex_fd);
    close_fd(&token_fd);
    close_fd(&cidr6_fd);
    close_fd(&bypass_cidr4_fd);
    close_fd(&proxy_cidr4_fd);
    close_fd(&uid_fd);
    return result;
}

int bpf2socks_bpf_start(
    const struct bpf2socks_runtime_config *config,
    const struct bpf2socks_policy_config *policy,
    struct bpf2socks_bpf_runtime *runtime) {
    if (runtime == NULL || config == NULL || policy == NULL) {
        errno = EINVAL;
        return -1;
    }
    init_runtime(runtime);
    char stage_detail[96];
    const char *stage = "init";
    bool interface_policy_enabled = policy->ignored_interface_count > 0U;
    bool need_uid_map = uid_map_required(policy);
    bool need_proxy_cidr4_map = proxy_cidr4_map_required(policy);
    bool need_bypass_cidr4_map = bypass_cidr4_map_required(policy);
    bool need_bypass_cidr6_map = bypass_cidr6_map_required(policy);

    if (need_uid_map) {
        uint32_t uid_entries = uid_map_max_entries(policy);
        snprintf(stage_detail, sizeof(stage_detail), "create uid map (%u entries)", uid_entries);
        stage = stage_detail;
        runtime->uid_map_fd = bpf2socks_create_map(
            BPF_MAP_TYPE_HASH,
            sizeof(uint32_t),
            sizeof(uint32_t),
            uid_entries,
            0U);
        if (runtime->uid_map_fd < 0) goto fail;
    }
    if (need_proxy_cidr4_map) {
        stage = "create proxy cidr4 map";
        runtime->proxy_cidr4_map_fd = bpf2socks_create_map(
            BPF_MAP_TYPE_LPM_TRIE,
            sizeof(struct bpf2socks_lpm4_key),
            sizeof(uint8_t),
            BPF2SOCKS_MAX_CIDR_MAP_ENTRIES,
            BPF_F_NO_PREALLOC);
        if (runtime->proxy_cidr4_map_fd < 0) goto fail;
    }
    if (need_bypass_cidr4_map) {
        stage = "create bypass cidr4 map";
        runtime->bypass_cidr4_map_fd = bpf2socks_create_map(
            BPF_MAP_TYPE_LPM_TRIE,
            sizeof(struct bpf2socks_lpm4_key),
            sizeof(uint8_t),
            BPF2SOCKS_MAX_CIDR_MAP_ENTRIES,
            BPF_F_NO_PREALLOC);
        if (runtime->bypass_cidr4_map_fd < 0) goto fail;
    }
    stage = "create token map";
    runtime->token_map_fd = create_token_map(BPF2SOCKS_MAX_TOKEN_MAP_ENTRIES);
    if (runtime->token_map_fd < 0) goto fail;
    if (need_bypass_cidr6_map) {
        stage = "create cidr6 map";
        runtime->cidr6_map_fd = bpf2socks_create_map(
            BPF_MAP_TYPE_LPM_TRIE,
            sizeof(struct bpf2socks_lpm6_key),
            sizeof(uint8_t),
            BPF2SOCKS_MAX_CIDR_MAP_ENTRIES,
            BPF_F_NO_PREALLOC);
        if (runtime->cidr6_map_fd < 0) goto fail;
    }
    if (interface_policy_enabled) {
        stage = "create ignored ifindex map";
        runtime->ignored_ifindex_map_fd = bpf2socks_create_map(
            BPF_MAP_TYPE_HASH,
            sizeof(uint32_t),
            sizeof(uint8_t),
            BPF2SOCKS_MAX_INTERFACES,
            0U);
        if (runtime->ignored_ifindex_map_fd < 0) goto fail;
        stage = "create ignored route cidr4 map";
        runtime->ignored_route_cidr4_map_fd = bpf2socks_create_map(
            BPF_MAP_TYPE_LPM_TRIE,
            sizeof(struct bpf2socks_lpm4_key),
            sizeof(uint8_t),
            BPF2SOCKS_MAX_CIDR_MAP_ENTRIES,
            BPF_F_NO_PREALLOC);
        if (runtime->ignored_route_cidr4_map_fd < 0) goto fail;
    }
    if (need_uid_map && bpf2socks_load_uid_map(runtime->uid_map_fd, policy) < 0) {
        stage = "load uid map";
        goto fail;
    }
    if (need_proxy_cidr4_map &&
        bpf2socks_load_cidr_strings(
            runtime->proxy_cidr4_map_fd,
            policy->proxy_private_cidrs_v4,
            policy->proxy_private_cidr_v4_count,
            AF_INET) < 0) {
        stage = "load proxy cidr4 map";
        goto fail;
    }
    if (need_bypass_cidr4_map) {
        if (bpf2socks_load_cidr_strings(
                runtime->bypass_cidr4_map_fd,
                policy->bypass_private_cidrs_v4,
                policy->bypass_private_cidr_v4_count,
                AF_INET) < 0 ||
            bpf2socks_load_cidr_strings(
                runtime->bypass_cidr4_map_fd,
                policy->local_interface_cidrs_v4,
                policy->local_interface_cidr_v4_count,
                AF_INET) < 0) {
            stage = "load bypass cidr4 map";
            goto fail;
        }
        if (policy->bypass_direct_cidrs &&
            bpf2socks_load_direct_cidrs(runtime->bypass_cidr4_map_fd, policy->direct_cidr_path_v4, AF_INET) < 0) {
            stage = "load direct cidr4 map";
            goto fail;
        }
    }
    if (need_bypass_cidr6_map &&
        (bpf2socks_load_cidr_strings(
                runtime->cidr6_map_fd,
                policy->bypass_private_cidrs_v6,
                policy->bypass_private_cidr_v6_count,
                AF_INET6) < 0 ||
            bpf2socks_load_cidr_strings(
                runtime->cidr6_map_fd,
                policy->local_interface_cidrs_v6,
                policy->local_interface_cidr_v6_count,
                AF_INET6) < 0)) {
        stage = "load bypass cidr6 map";
        goto fail;
    }
    if (need_bypass_cidr6_map &&
        policy->bypass_direct_cidrs &&
        bpf2socks_load_direct_cidrs(runtime->cidr6_map_fd, policy->direct_cidr_path_v6, AF_INET6) < 0) {
        stage = "load direct cidr6 map";
        goto fail;
    }

    runtime->cgroup_fd = open_cgroup_path(config->cgroup_path);
    if (runtime->cgroup_fd < 0) {
        stage = "open cgroup";
        goto fail;
    }
    if (bpf2socks_detach_named_progs(runtime->cgroup_fd) < 0) {
        stage = "detach previous cgroup programs";
        goto fail;
    }
    runtime->connect4_prog_fd = build_ipv4_sock_addr_prog(
        policy,
        runtime->uid_map_fd,
        runtime->proxy_cidr4_map_fd,
        runtime->bypass_cidr4_map_fd,
        runtime->ignored_ifindex_map_fd,
        runtime->ignored_route_cidr4_map_fd,
        runtime->token_map_fd,
        BPF2SOCKS_PROTO_TCP,
        true,
        config->listen_port,
        BPF_CGROUP_INET4_CONNECT,
        "b2s_conn4",
        true);
    runtime->udp4_sendmsg_prog_fd = build_ipv4_sock_addr_prog(
        policy,
        runtime->uid_map_fd,
        runtime->proxy_cidr4_map_fd,
        runtime->bypass_cidr4_map_fd,
        runtime->ignored_ifindex_map_fd,
        runtime->ignored_route_cidr4_map_fd,
        runtime->token_map_fd,
        BPF2SOCKS_PROTO_UDP,
        false,
        config->listen_port,
        BPF_CGROUP_UDP4_SENDMSG,
        "b2s_udp4",
        true);
    runtime->udp4_recvmsg_prog_fd = build_udp4_recvmsg_prog(runtime->token_map_fd, "b2s_urcv4", true);
    if (runtime->connect4_prog_fd < 0 || runtime->udp4_sendmsg_prog_fd < 0 || runtime->udp4_recvmsg_prog_fd < 0) {
        stage = "load cgroup programs";
        goto fail;
    }
    if (policy->enable_ipv6) {
        runtime->connect6_prog_fd = build_ipv6_sock_addr_prog(
            policy,
            config,
            runtime->uid_map_fd,
            runtime->cidr6_map_fd,
            runtime->token_map_fd,
            BPF2SOCKS_PROTO_TCP,
            true,
            config->listen_port,
            BPF_CGROUP_INET6_CONNECT,
            "b2s_conn6",
            true);
        runtime->udp6_sendmsg_prog_fd = build_ipv6_sock_addr_prog(
            policy,
            config,
            runtime->uid_map_fd,
            runtime->cidr6_map_fd,
            runtime->token_map_fd,
            BPF2SOCKS_PROTO_UDP,
            false,
            config->listen_port,
            BPF_CGROUP_UDP6_SENDMSG,
            "b2s_udp6",
            true);
        runtime->udp6_recvmsg_prog_fd = build_udp6_recvmsg_prog(runtime->token_map_fd, "b2s_urcv6", true);
        if (runtime->connect6_prog_fd < 0 ||
            runtime->udp6_sendmsg_prog_fd < 0 ||
            runtime->udp6_recvmsg_prog_fd < 0) {
            stage = "load cgroup IPv6 programs";
            goto fail;
        }
    } else {
        runtime->connect6_v4mapped_prog_fd = build_ipv4_mapped_ipv6_sock_addr_prog(
            policy,
            runtime->uid_map_fd,
            runtime->proxy_cidr4_map_fd,
            runtime->bypass_cidr4_map_fd,
            runtime->ignored_ifindex_map_fd,
            runtime->ignored_route_cidr4_map_fd,
            runtime->token_map_fd,
            config->listen_port,
            "b2s_c6v4m",
            true);
        if (runtime->connect6_v4mapped_prog_fd < 0) {
            stage = "load cgroup IPv4-mapped connect6 program";
            goto fail;
        }
    }
    if (bpf2socks_interface_policy_start(policy, runtime) < 0) {
        stage = "start interface policy";
        goto fail;
    }
    if (bpf2socks_sk_lookup_start(policy, config, runtime) < 0) {
        stage = "start sk_lookup";
        goto fail;
    }
    if (bpf2socks_attach_prog(runtime->cgroup_fd, runtime->connect4_prog_fd, BPF_CGROUP_INET4_CONNECT) < 0) {
        stage = "attach connect4";
        goto fail;
    }
    if (bpf2socks_attach_prog(runtime->cgroup_fd, runtime->udp4_sendmsg_prog_fd, BPF_CGROUP_UDP4_SENDMSG) < 0) {
        (void)bpf2socks_detach_prog(runtime->cgroup_fd, runtime->connect4_prog_fd, BPF_CGROUP_INET4_CONNECT);
        stage = "attach udp4 sendmsg";
        goto fail;
    }
    if (bpf2socks_attach_prog(runtime->cgroup_fd, runtime->udp4_recvmsg_prog_fd, BPF_CGROUP_UDP4_RECVMSG) < 0) {
        (void)bpf2socks_detach_prog(runtime->cgroup_fd, runtime->udp4_sendmsg_prog_fd, BPF_CGROUP_UDP4_SENDMSG);
        (void)bpf2socks_detach_prog(runtime->cgroup_fd, runtime->connect4_prog_fd, BPF_CGROUP_INET4_CONNECT);
        stage = "attach udp4 recvmsg";
        goto fail;
    }
    if (policy->enable_ipv6) {
        if (bpf2socks_attach_prog(runtime->cgroup_fd, runtime->connect6_prog_fd, BPF_CGROUP_INET6_CONNECT) < 0) {
            stage = "attach connect6";
            goto fail;
        }
        if (bpf2socks_attach_prog(runtime->cgroup_fd, runtime->udp6_sendmsg_prog_fd, BPF_CGROUP_UDP6_SENDMSG) < 0) {
            (void)bpf2socks_detach_prog(runtime->cgroup_fd, runtime->connect6_prog_fd, BPF_CGROUP_INET6_CONNECT);
            stage = "attach udp6 sendmsg";
            goto fail;
        }
        if (bpf2socks_attach_prog(runtime->cgroup_fd, runtime->udp6_recvmsg_prog_fd, BPF_CGROUP_UDP6_RECVMSG) < 0) {
            (void)bpf2socks_detach_prog(runtime->cgroup_fd, runtime->udp6_sendmsg_prog_fd, BPF_CGROUP_UDP6_SENDMSG);
            (void)bpf2socks_detach_prog(runtime->cgroup_fd, runtime->connect6_prog_fd, BPF_CGROUP_INET6_CONNECT);
            stage = "attach udp6 recvmsg";
            goto fail;
        }
    } else if (bpf2socks_attach_prog(runtime->cgroup_fd, runtime->connect6_v4mapped_prog_fd, BPF_CGROUP_INET6_CONNECT) < 0) {
        stage = "attach connect6 v4mapped";
        goto fail;
    }
    return 0;

fail:
    fprintf(stderr, "failed to start bpf2socks BPF stage '%s': errno=%d\n", stage, errno);
    bpf2socks_bpf_stop(runtime);
    return -1;
}

void bpf2socks_bpf_stop(struct bpf2socks_bpf_runtime *runtime) {
    if (runtime == NULL) return;
    bpf2socks_interface_policy_stop(runtime);
    if (runtime->cgroup_fd >= 0) {
        if (runtime->udp6_recvmsg_prog_fd >= 0) {
            (void)bpf2socks_detach_prog(runtime->cgroup_fd, runtime->udp6_recvmsg_prog_fd, BPF_CGROUP_UDP6_RECVMSG);
        }
        if (runtime->udp6_sendmsg_prog_fd >= 0) {
            (void)bpf2socks_detach_prog(runtime->cgroup_fd, runtime->udp6_sendmsg_prog_fd, BPF_CGROUP_UDP6_SENDMSG);
        }
        if (runtime->connect6_prog_fd >= 0) {
            (void)bpf2socks_detach_prog(runtime->cgroup_fd, runtime->connect6_prog_fd, BPF_CGROUP_INET6_CONNECT);
        }
        if (runtime->connect6_v4mapped_prog_fd >= 0) {
            (void)bpf2socks_detach_prog(runtime->cgroup_fd, runtime->connect6_v4mapped_prog_fd, BPF_CGROUP_INET6_CONNECT);
        }
        if (runtime->udp4_recvmsg_prog_fd >= 0) {
            (void)bpf2socks_detach_prog(runtime->cgroup_fd, runtime->udp4_recvmsg_prog_fd, BPF_CGROUP_UDP4_RECVMSG);
        }
        if (runtime->udp4_sendmsg_prog_fd >= 0) {
            (void)bpf2socks_detach_prog(runtime->cgroup_fd, runtime->udp4_sendmsg_prog_fd, BPF_CGROUP_UDP4_SENDMSG);
        }
        if (runtime->connect4_prog_fd >= 0) {
            (void)bpf2socks_detach_prog(runtime->cgroup_fd, runtime->connect4_prog_fd, BPF_CGROUP_INET4_CONNECT);
        }
    }
    close_fd(&runtime->sk_lookup_link_fd);
    close_fd(&runtime->sk_lookup_prog_fd);
    close_fd(&runtime->sk_lookup_sock_map_fd);
    close_fd(&runtime->udp6_recvmsg_prog_fd);
    close_fd(&runtime->udp4_recvmsg_prog_fd);
    close_fd(&runtime->udp6_sendmsg_prog_fd);
    close_fd(&runtime->udp4_sendmsg_prog_fd);
    close_fd(&runtime->connect6_v4mapped_prog_fd);
    close_fd(&runtime->connect6_prog_fd);
    close_fd(&runtime->connect4_prog_fd);
    close_fd(&runtime->token_map_fd);
    close_fd(&runtime->cidr6_map_fd);
    close_fd(&runtime->ignored_route_cidr4_map_fd);
    close_fd(&runtime->ignored_ifindex_map_fd);
    close_fd(&runtime->bypass_cidr4_map_fd);
    close_fd(&runtime->proxy_cidr4_map_fd);
    close_fd(&runtime->uid_map_fd);
    close_fd(&runtime->cgroup_fd);
}
