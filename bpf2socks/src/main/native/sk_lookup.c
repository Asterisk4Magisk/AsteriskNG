// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

#include "bpf2socks.h"

#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#define ARRAY_SIZE(x) (sizeof(x) / sizeof((x)[0]))
#define BPF_ALU64_IMM_OP(OP, DST, IMM) ((struct bpf_insn){.code = BPF_ALU64 | BPF_OP(OP) | BPF_K, .dst_reg = DST, .imm = (int32_t)(IMM)})
#define BPF_ALU64_REG_OP(OP, DST, SRC) ((struct bpf_insn){.code = BPF_ALU64 | BPF_OP(OP) | BPF_X, .dst_reg = DST, .src_reg = SRC})
#define BPF_MOV64_REG(DST, SRC) BPF_ALU64_REG_OP(BPF_MOV, DST, SRC)
#define BPF_MOV64_IMM(DST, IMM) BPF_ALU64_IMM_OP(BPF_MOV, DST, IMM)
#define BPF_ST_MEM(SIZE, DST, OFF, IMM) ((struct bpf_insn){.code = BPF_ST | BPF_SIZE(SIZE) | BPF_MEM, .dst_reg = DST, .off = OFF, .imm = (int32_t)(IMM)})
#define BPF_STX_MEM(SIZE, DST, SRC, OFF) ((struct bpf_insn){.code = BPF_STX | BPF_SIZE(SIZE) | BPF_MEM, .dst_reg = DST, .src_reg = SRC, .off = OFF})
#define BPF_LDX_MEM(SIZE, DST, SRC, OFF) ((struct bpf_insn){.code = BPF_LDX | BPF_SIZE(SIZE) | BPF_MEM, .dst_reg = DST, .src_reg = SRC, .off = OFF})
#define BPF_JMP_IMM_OP(OP, DST, IMM, OFF) ((struct bpf_insn){.code = BPF_JMP | BPF_OP(OP) | BPF_K, .dst_reg = DST, .off = OFF, .imm = (int32_t)(IMM)})
#define BPF_CALL_FUNC(FUNC) ((struct bpf_insn){.code = BPF_JMP | BPF_CALL, .imm = FUNC})
#define BPF_EXIT_INSN() ((struct bpf_insn){.code = BPF_JMP | BPF_EXIT})
#define BPF_ENDIAN_OP(DST, SIZE) ((struct bpf_insn){.code = BPF_ALU | BPF_END | BPF_TO_BE, .dst_reg = DST, .imm = SIZE})

enum {
    STACK_PROTO_KEY = -4,
    STACK_LPM4_KEY = -16,
    STACK_LPM6_KEY = -40,
};

#define BPF2SOCKS_SOCKMAP_MAX_ENTRIES BPF2SOCKS_SK_LOOKUP_MAX_SOCKS

struct bpf_builder {
    struct bpf_insn insns[768];
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

static void emit_zero_region(struct bpf_builder *builder, int base_off, size_t size) {
    for (size_t off = 0; off < size; off += sizeof(uint32_t)) {
        emit(builder, BPF_ST_MEM(BPF_W, BPF_REG_10, (int16_t)(base_off + (int)off), 0));
    }
}

static size_t emit_exit_pass(struct bpf_builder *builder) {
    size_t label = builder->count;
    emit(builder, BPF_MOV64_IMM(BPF_REG_0, SK_PASS));
    emit(builder, BPF_EXIT_INSN());
    return label;
}

static size_t emit_exit_drop(struct bpf_builder *builder) {
    size_t label = builder->count;
    emit(builder, BPF_MOV64_IMM(BPF_REG_0, SK_DROP));
    emit(builder, BPF_EXIT_INSN());
    return label;
}

static bool is_power_of_two_u32(uint32_t value) {
    return value != 0U && (value & (value - 1U)) == 0U;
}

static void emit_sk_lookup_local_addr_bypass_v4(
    struct bpf_builder *builder,
    int cidr4_map_fd,
    size_t *pass_jumps,
    size_t *pass_jump_count) {
    if (cidr4_map_fd < 0) return;

    emit(builder, BPF_LDX_MEM(BPF_W, BPF_REG_4, BPF_REG_6, offsetof(struct bpf_sk_lookup, local_port)));
    size_t dns_port = emit_jump(builder, BPF_JMP_IMM_OP(BPF_JEQ, BPF_REG_4, 53, 0));
    emit(builder, BPF_ST_MEM(BPF_W, BPF_REG_10, STACK_LPM4_KEY, 32));
    emit(builder, BPF_STX_MEM(BPF_W, BPF_REG_10, BPF_REG_7, STACK_LPM4_KEY + (int)offsetof(struct bpf2socks_lpm4_key, addr)));
    emit_ld_map_fd(builder, BPF_REG_1, cidr4_map_fd);
    emit(builder, BPF_MOV64_REG(BPF_REG_2, BPF_REG_10));
    emit(builder, BPF_ALU64_IMM_OP(BPF_ADD, BPF_REG_2, STACK_LPM4_KEY));
    emit(builder, BPF_CALL_FUNC(BPF_FUNC_map_lookup_elem));
    pass_jumps[(*pass_jump_count)++] = emit_jump(builder, BPF_JMP_IMM_OP(BPF_JNE, BPF_REG_0, 0, 0));
    patch_jump(builder, dns_port, builder->count);
}

static void emit_sk_lookup_local_addr_bypass_v6(
    struct bpf_builder *builder,
    int cidr6_map_fd,
    size_t *pass_jumps,
    size_t *pass_jump_count) {
    if (cidr6_map_fd < 0) return;

    emit(builder, BPF_LDX_MEM(BPF_W, BPF_REG_4, BPF_REG_6, offsetof(struct bpf_sk_lookup, local_port)));
    size_t dns_port = emit_jump(builder, BPF_JMP_IMM_OP(BPF_JEQ, BPF_REG_4, 53, 0));
    emit_zero_region(builder, STACK_LPM6_KEY, sizeof(struct bpf2socks_lpm6_key));
    emit(builder, BPF_ST_MEM(BPF_W, BPF_REG_10, STACK_LPM6_KEY, 128));
    emit(builder, BPF_STX_MEM(BPF_W, BPF_REG_10, BPF_REG_7, STACK_LPM6_KEY + (int)offsetof(struct bpf2socks_lpm6_key, addr)));
    emit(builder, BPF_STX_MEM(BPF_W, BPF_REG_10, BPF_REG_0, STACK_LPM6_KEY + (int)offsetof(struct bpf2socks_lpm6_key, addr) + 4));
    emit(builder, BPF_STX_MEM(BPF_W, BPF_REG_10, BPF_REG_1, STACK_LPM6_KEY + (int)offsetof(struct bpf2socks_lpm6_key, addr) + 8));
    emit(builder, BPF_STX_MEM(BPF_W, BPF_REG_10, BPF_REG_3, STACK_LPM6_KEY + (int)offsetof(struct bpf2socks_lpm6_key, addr) + 12));
    emit_ld_map_fd(builder, BPF_REG_1, cidr6_map_fd);
    emit(builder, BPF_MOV64_REG(BPF_REG_2, BPF_REG_10));
    emit(builder, BPF_ALU64_IMM_OP(BPF_ADD, BPF_REG_2, STACK_LPM6_KEY));
    emit(builder, BPF_CALL_FUNC(BPF_FUNC_map_lookup_elem));
    pass_jumps[(*pass_jump_count)++] = emit_jump(builder, BPF_JMP_IMM_OP(BPF_JNE, BPF_REG_0, 0, 0));
    patch_jump(builder, dns_port, builder->count);
}

static int build_sk_lookup_assign_prog(
    int sock_map_fd,
    int cidr4_map_fd,
    int cidr6_map_fd,
    uint32_t worker_count,
    bool enable_ipv6,
    const char *name,
    bool log_error) {
    if (worker_count == 0U) worker_count = BPF2SOCKS_DEFAULT_WORKER_COUNT;
    if (worker_count > BPF2SOCKS_MAX_WORKER_COUNT) worker_count = BPF2SOCKS_MAX_WORKER_COUNT;

    struct bpf_builder b = {0};
    size_t pass_jumps[32];
    size_t pass_jump_count = 0;

    emit(&b, BPF_MOV64_REG(BPF_REG_6, BPF_REG_1));
    emit(&b, BPF_LDX_MEM(BPF_W, BPF_REG_2, BPF_REG_6, offsetof(struct bpf_sk_lookup, family)));
    emit(&b, BPF_LDX_MEM(BPF_W, BPF_REG_8, BPF_REG_6, offsetof(struct bpf_sk_lookup, protocol)));
    size_t proto_tcp = emit_jump(&b, BPF_JMP_IMM_OP(BPF_JEQ, BPF_REG_8, BPF2SOCKS_PROTO_TCP, 0));
    size_t proto_udp = emit_jump(&b, BPF_JMP_IMM_OP(BPF_JEQ, BPF_REG_8, BPF2SOCKS_PROTO_UDP, 0));
    pass_jumps[pass_jump_count++] = emit_jump(&b, BPF_JMP_IMM_OP(BPF_JA, 0, 0, 0));
    patch_jump(&b, proto_tcp, b.count);
    patch_jump(&b, proto_udp, b.count);

    size_t family_v4 = emit_jump(&b, BPF_JMP_IMM_OP(BPF_JEQ, BPF_REG_2, AF_INET, 0));
    size_t family_v6 = 0U;
    if (enable_ipv6) {
        family_v6 = emit_jump(&b, BPF_JMP_IMM_OP(BPF_JEQ, BPF_REG_2, AF_INET6, 0));
    }
    pass_jumps[pass_jump_count++] = emit_jump(&b, BPF_JMP_IMM_OP(BPF_JA, 0, 0, 0));

    patch_jump(&b, family_v4, b.count);
    emit(&b, BPF_LDX_MEM(BPF_W, BPF_REG_7, BPF_REG_6, offsetof(struct bpf_sk_lookup, local_ip4)));
    pass_jumps[pass_jump_count++] = emit_jump(&b, BPF_JMP_IMM_OP(BPF_JEQ, BPF_REG_7, 0, 0));
    emit(&b, BPF_MOV64_REG(BPF_REG_3, BPF_REG_7));
    emit(&b, BPF_ENDIAN_OP(BPF_REG_3, 32));
    emit(&b, BPF_ALU64_IMM_OP(BPF_AND, BPF_REG_3, 0xff000000U));
    pass_jumps[pass_jump_count++] = emit_jump(&b, BPF_JMP_IMM_OP(BPF_JEQ, BPF_REG_3, 0x7f000000U, 0));
    emit_sk_lookup_local_addr_bypass_v4(&b, cidr4_map_fd, pass_jumps, &pass_jump_count);

    emit(&b, BPF_LDX_MEM(BPF_W, BPF_REG_9, BPF_REG_6, offsetof(struct bpf_sk_lookup, remote_ip4)));
    emit(&b, BPF_ALU64_REG_OP(BPF_XOR, BPF_REG_9, BPF_REG_7));
    emit(&b, BPF_LDX_MEM(BPF_W, BPF_REG_4, BPF_REG_6, offsetof(struct bpf_sk_lookup, remote_port)));
    emit(&b, BPF_ALU64_REG_OP(BPF_XOR, BPF_REG_9, BPF_REG_4));
    emit(&b, BPF_LDX_MEM(BPF_W, BPF_REG_4, BPF_REG_6, offsetof(struct bpf_sk_lookup, local_port)));
    emit(&b, BPF_ALU64_REG_OP(BPF_XOR, BPF_REG_9, BPF_REG_4));
    emit(&b, BPF_ALU64_REG_OP(BPF_XOR, BPF_REG_9, BPF_REG_8));
    emit(&b, BPF_MOV64_IMM(BPF_REG_4, 0));
    size_t family_done = 0U;
    if (enable_ipv6) {
        family_done = emit_jump(&b, BPF_JMP_IMM_OP(BPF_JA, 0, 0, 0));

        patch_jump(&b, family_v6, b.count);
        emit(&b, BPF_LDX_MEM(BPF_W, BPF_REG_7, BPF_REG_6, offsetof(struct bpf_sk_lookup, local_ip6)));
        emit(&b, BPF_LDX_MEM(BPF_W, BPF_REG_0, BPF_REG_6, offsetof(struct bpf_sk_lookup, local_ip6) + 4));
        emit(&b, BPF_LDX_MEM(BPF_W, BPF_REG_1, BPF_REG_6, offsetof(struct bpf_sk_lookup, local_ip6) + 8));
        emit(&b, BPF_LDX_MEM(BPF_W, BPF_REG_3, BPF_REG_6, offsetof(struct bpf_sk_lookup, local_ip6) + 12));
        emit(&b, BPF_MOV64_REG(BPF_REG_4, BPF_REG_7));
        emit(&b, BPF_ALU64_REG_OP(BPF_OR, BPF_REG_4, BPF_REG_0));
        emit(&b, BPF_ALU64_REG_OP(BPF_OR, BPF_REG_4, BPF_REG_1));
        emit(&b, BPF_ALU64_REG_OP(BPF_OR, BPF_REG_4, BPF_REG_3));
        pass_jumps[pass_jump_count++] = emit_jump(&b, BPF_JMP_IMM_OP(BPF_JEQ, BPF_REG_4, 0, 0));
        emit(&b, BPF_MOV64_REG(BPF_REG_5, BPF_REG_7));
        emit(&b, BPF_ALU64_REG_OP(BPF_OR, BPF_REG_5, BPF_REG_0));
        emit(&b, BPF_ALU64_REG_OP(BPF_OR, BPF_REG_5, BPF_REG_1));
        size_t not_v6_loopback = emit_jump(&b, BPF_JMP_IMM_OP(BPF_JNE, BPF_REG_5, 0, 0));
        emit(&b, BPF_MOV64_REG(BPF_REG_5, BPF_REG_3));
        emit(&b, BPF_ENDIAN_OP(BPF_REG_5, 32));
        pass_jumps[pass_jump_count++] = emit_jump(&b, BPF_JMP_IMM_OP(BPF_JEQ, BPF_REG_5, 1, 0));
        patch_jump(&b, not_v6_loopback, b.count);
        emit_sk_lookup_local_addr_bypass_v6(&b, cidr6_map_fd, pass_jumps, &pass_jump_count);

        emit(&b, BPF_MOV64_IMM(BPF_REG_9, 0));
        emit(&b, BPF_LDX_MEM(BPF_W, BPF_REG_7, BPF_REG_6, offsetof(struct bpf_sk_lookup, local_ip6)));
        emit(&b, BPF_LDX_MEM(BPF_W, BPF_REG_0, BPF_REG_6, offsetof(struct bpf_sk_lookup, local_ip6) + 4));
        emit(&b, BPF_LDX_MEM(BPF_W, BPF_REG_1, BPF_REG_6, offsetof(struct bpf_sk_lookup, local_ip6) + 8));
        emit(&b, BPF_LDX_MEM(BPF_W, BPF_REG_3, BPF_REG_6, offsetof(struct bpf_sk_lookup, local_ip6) + 12));
        emit(&b, BPF_MOV64_REG(BPF_REG_4, BPF_REG_7));
        emit(&b, BPF_ALU64_REG_OP(BPF_XOR, BPF_REG_9, BPF_REG_4));
        emit(&b, BPF_MOV64_REG(BPF_REG_4, BPF_REG_0));
        emit(&b, BPF_ALU64_REG_OP(BPF_XOR, BPF_REG_9, BPF_REG_4));
        emit(&b, BPF_MOV64_REG(BPF_REG_4, BPF_REG_1));
        emit(&b, BPF_ALU64_REG_OP(BPF_XOR, BPF_REG_9, BPF_REG_4));
        emit(&b, BPF_MOV64_REG(BPF_REG_4, BPF_REG_3));
        emit(&b, BPF_ALU64_REG_OP(BPF_XOR, BPF_REG_9, BPF_REG_4));
        emit(&b, BPF_LDX_MEM(BPF_W, BPF_REG_4, BPF_REG_6, offsetof(struct bpf_sk_lookup, remote_ip6)));
        emit(&b, BPF_ALU64_REG_OP(BPF_XOR, BPF_REG_9, BPF_REG_4));
        emit(&b, BPF_LDX_MEM(BPF_W, BPF_REG_4, BPF_REG_6, offsetof(struct bpf_sk_lookup, remote_ip6) + 4));
        emit(&b, BPF_ALU64_REG_OP(BPF_XOR, BPF_REG_9, BPF_REG_4));
        emit(&b, BPF_LDX_MEM(BPF_W, BPF_REG_4, BPF_REG_6, offsetof(struct bpf_sk_lookup, remote_ip6) + 8));
        emit(&b, BPF_ALU64_REG_OP(BPF_XOR, BPF_REG_9, BPF_REG_4));
        emit(&b, BPF_LDX_MEM(BPF_W, BPF_REG_4, BPF_REG_6, offsetof(struct bpf_sk_lookup, remote_ip6) + 12));
        emit(&b, BPF_ALU64_REG_OP(BPF_XOR, BPF_REG_9, BPF_REG_4));
        emit(&b, BPF_LDX_MEM(BPF_W, BPF_REG_4, BPF_REG_6, offsetof(struct bpf_sk_lookup, remote_port)));
        emit(&b, BPF_ALU64_REG_OP(BPF_XOR, BPF_REG_9, BPF_REG_4));
        emit(&b, BPF_LDX_MEM(BPF_W, BPF_REG_4, BPF_REG_6, offsetof(struct bpf_sk_lookup, local_port)));
        emit(&b, BPF_ALU64_REG_OP(BPF_XOR, BPF_REG_9, BPF_REG_4));
        emit(&b, BPF_ALU64_REG_OP(BPF_XOR, BPF_REG_9, BPF_REG_8));
        emit(&b, BPF_MOV64_IMM(BPF_REG_4, 2));

        patch_jump(&b, family_done, b.count);
    }
    if (worker_count > 1U) {
        if (is_power_of_two_u32(worker_count)) {
            emit(&b, BPF_ALU64_IMM_OP(BPF_AND, BPF_REG_9, worker_count - 1U));
        } else {
            emit(&b, BPF_ALU64_IMM_OP(BPF_MOD, BPF_REG_9, worker_count));
        }
    } else {
        emit(&b, BPF_MOV64_IMM(BPF_REG_9, 0));
    }
    emit(&b, BPF_ALU64_IMM_OP(BPF_LSH, BPF_REG_9, 2));
    emit(&b, BPF_MOV64_REG(BPF_REG_5, BPF_REG_4));
    emit(&b, BPF_MOV64_REG(BPF_REG_4, BPF_REG_8));
    emit(&b, BPF_ALU64_IMM_OP(BPF_AND, BPF_REG_4, 1));
    emit(&b, BPF_ALU64_REG_OP(BPF_OR, BPF_REG_4, BPF_REG_5));
    emit(&b, BPF_ALU64_REG_OP(BPF_OR, BPF_REG_4, BPF_REG_9));
    emit(&b, BPF_STX_MEM(BPF_W, BPF_REG_10, BPF_REG_4, STACK_PROTO_KEY));
    emit_ld_map_fd(&b, BPF_REG_1, sock_map_fd);
    emit(&b, BPF_MOV64_REG(BPF_REG_2, BPF_REG_10));
    emit(&b, BPF_ALU64_IMM_OP(BPF_ADD, BPF_REG_2, STACK_PROTO_KEY));
    emit(&b, BPF_CALL_FUNC(BPF_FUNC_map_lookup_elem));
    pass_jumps[pass_jump_count++] = emit_jump(&b, BPF_JMP_IMM_OP(BPF_JEQ, BPF_REG_0, 0, 0));

    emit(&b, BPF_MOV64_REG(BPF_REG_7, BPF_REG_0));
    emit(&b, BPF_MOV64_REG(BPF_REG_1, BPF_REG_6));
    emit(&b, BPF_MOV64_REG(BPF_REG_2, BPF_REG_7));
    emit(&b, BPF_MOV64_IMM(BPF_REG_3, BPF_SK_LOOKUP_F_REPLACE));
    emit(&b, BPF_CALL_FUNC(BPF_FUNC_sk_assign));
    emit(&b, BPF_MOV64_REG(BPF_REG_8, BPF_REG_0));
    emit(&b, BPF_MOV64_REG(BPF_REG_1, BPF_REG_7));
    emit(&b, BPF_CALL_FUNC(BPF_FUNC_sk_release));
    size_t assign_failed = emit_jump(&b, BPF_JMP_IMM_OP(BPF_JNE, BPF_REG_8, 0, 0));

    size_t pass_label = emit_exit_pass(&b);
    size_t drop_label = emit_exit_drop(&b);
    for (size_t i = 0; i < pass_jump_count; ++i) {
        patch_jump(&b, pass_jumps[i], pass_label);
    }
    patch_jump(&b, assign_failed, drop_label);

    if (b.overflow) {
        errno = EMSGSIZE;
        return -1;
    }
    return bpf2socks_load_prog(
        b.insns,
        b.count,
        name,
        BPF_PROG_TYPE_SK_LOOKUP,
        BPF_SK_LOOKUP,
        log_error);
}

static int create_sock_map(void) {
    return bpf2socks_create_map(
        BPF_MAP_TYPE_SOCKMAP,
        sizeof(uint32_t),
        sizeof(uint64_t),
        BPF2SOCKS_SOCKMAP_MAX_ENTRIES,
        0U);
}

static int update_sock_map(int sock_map_fd, const uint32_t *key, const uint64_t *value) {
    union bpf_attr attr;
    memset(&attr, 0, sizeof(attr));
    attr.map_fd = (uint32_t)sock_map_fd;
    attr.key = (uint64_t)(uintptr_t)key;
    attr.value = (uint64_t)(uintptr_t)value;
    attr.flags = BPF_NOEXIST;
    return (int)bpf2socks_bpf_sys(BPF_MAP_UPDATE_ELEM, &attr, sizeof(attr));
}

static int attach_current_netns(int prog_fd) {
    int netns_fd = open("/proc/self/ns/net", O_RDONLY | O_CLOEXEC);
    if (netns_fd < 0) return -1;
    int link_fd = bpf2socks_link_create(prog_fd, netns_fd, BPF_SK_LOOKUP, 0U);
    int saved = errno;
    close(netns_fd);
    errno = saved;
    return link_fd;
}

int bpf2socks_sk_lookup_probe(bool enable_ipv6, char *message, size_t message_size) {
    int sock_map_fd = -1;
    int prog_fd = -1;
    int link_fd = -1;
    int result = -1;

    sock_map_fd = create_sock_map();
    if (sock_map_fd < 0) {
        snprintf(message, message_size, "sk_lookup sock map is unavailable: errno=%d", errno);
        goto done;
    }
    prog_fd = build_sk_lookup_assign_prog(
        sock_map_fd,
        -1,
        -1,
        1U,
        enable_ipv6,
        "b2s_p_sklp",
        false);
    if (prog_fd < 0) {
        snprintf(message, message_size, "sk_lookup program cannot be loaded: errno=%d", errno);
        goto done;
    }
    link_fd = attach_current_netns(prog_fd);
    if (link_fd < 0) {
        snprintf(message, message_size, "sk_lookup attach is unavailable: errno=%d", errno);
        goto done;
    }
    result = 0;

done:
    close_fd(&link_fd);
    close_fd(&prog_fd);
    close_fd(&sock_map_fd);
    return result;
}

int bpf2socks_sk_lookup_start(
    const struct bpf2socks_policy_config *policy,
    const struct bpf2socks_runtime_config *config,
    struct bpf2socks_bpf_runtime *runtime) {
    if (policy == NULL || config == NULL || runtime == NULL) {
        errno = EINVAL;
        return -1;
    }
    if (policy->hotspot_interface_prefix_count == 0U) {
        return 0;
    }
    runtime->sk_lookup_sock_map_fd = create_sock_map();
    if (runtime->sk_lookup_sock_map_fd < 0) {
        return -1;
    }
    runtime->sk_lookup_prog_fd = build_sk_lookup_assign_prog(
        runtime->sk_lookup_sock_map_fd,
        runtime->bypass_cidr4_map_fd,
        runtime->cidr6_map_fd,
        config->worker_count,
        policy->enable_ipv6,
        "b2s_sklp",
        true);
    if (runtime->sk_lookup_prog_fd < 0) {
        return -1;
    }
    runtime->sk_lookup_link_fd = attach_current_netns(runtime->sk_lookup_prog_fd);
    return runtime->sk_lookup_link_fd >= 0 ? 0 : -1;
}

int bpf2socks_sk_lookup_register_worker_sockets(
    int sock_map_fd,
    uint32_t worker_id,
    int tcp4_fd,
    int udp4_fd,
    int tcp6_fd,
    int udp6_fd) {
    if (sock_map_fd < 0) return 0;
    if (worker_id >= BPF2SOCKS_MAX_WORKER_COUNT) {
        errno = EINVAL;
        return -1;
    }
    uint32_t key = BPF2SOCKS_SK_LOOKUP_KEY(AF_INET, BPF2SOCKS_PROTO_TCP, worker_id);
    uint64_t value = (uint64_t)tcp4_fd;
    if (tcp4_fd >= 0 && update_sock_map(sock_map_fd, &key, &value) < 0) {
        return -1;
    }
    key = BPF2SOCKS_SK_LOOKUP_KEY(AF_INET, BPF2SOCKS_PROTO_UDP, worker_id);
    value = (uint64_t)udp4_fd;
    if (udp4_fd >= 0 && update_sock_map(sock_map_fd, &key, &value) < 0) {
        return -1;
    }
    key = BPF2SOCKS_SK_LOOKUP_KEY(AF_INET6, BPF2SOCKS_PROTO_TCP, worker_id);
    value = (uint64_t)tcp6_fd;
    if (tcp6_fd >= 0 && update_sock_map(sock_map_fd, &key, &value) < 0) {
        return -1;
    }
    key = BPF2SOCKS_SK_LOOKUP_KEY(AF_INET6, BPF2SOCKS_PROTO_UDP, worker_id);
    value = (uint64_t)udp6_fd;
    if (udp6_fd >= 0 && update_sock_map(sock_map_fd, &key, &value) < 0) {
        return -1;
    }
    return 0;
}
