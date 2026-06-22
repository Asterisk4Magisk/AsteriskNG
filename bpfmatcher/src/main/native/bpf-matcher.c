// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/bpf.h>
#include <linux/unistd.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

#ifndef BPF_F_NO_PREALLOC
#define BPF_F_NO_PREALLOC 1U
#endif

#define ARRAY_SIZE(x) (sizeof(x) / sizeof((x)[0]))
#define LOG_BUF_SIZE 65536
#define MAX_UIDS 8192
#define MAX_CIDR_MAP_ENTRIES 16384
#define MAX_PATH_LEN 512
#define XT_BPF_NOMATCH 0U
#define XT_BPF_MATCH 0xffffU
#define DEFAULT_BPF_DIR "/sys/fs/bpf/asteriskng"
#define DEFAULT_XT_OUTPUT_V4_PROGRAM_PATH DEFAULT_BPF_DIR "/xt_output_v4"
#define DEFAULT_XT_OUTPUT_V6_PROGRAM_PATH DEFAULT_BPF_DIR "/xt_output_v6"
#define DEFAULT_XT_PREROUTING_V4_PROGRAM_PATH DEFAULT_BPF_DIR "/xt_prerouting_v4"
#define DEFAULT_XT_PREROUTING_V6_PROGRAM_PATH DEFAULT_BPF_DIR "/xt_prerouting_v6"
#define PROBE_XT_OUTPUT_V4_PROGRAM_PATH DEFAULT_BPF_DIR "/probe_xt_output_v4"
#define PROBE_XT_OUTPUT_V6_PROGRAM_PATH DEFAULT_BPF_DIR "/probe_xt_output_v6"
#define PROBE_XT_PREROUTING_V4_PROGRAM_PATH DEFAULT_BPF_DIR "/probe_xt_prerouting_v4"
#define PROBE_XT_PREROUTING_V6_PROGRAM_PATH DEFAULT_BPF_DIR "/probe_xt_prerouting_v6"
#define UID_SELECTED 1U
#define MODE_BLACKLIST 0U
#define MODE_WHITELIST 1U
#define MODE_GLOBAL 2U

#ifndef BPF_FUNC_get_socket_uid
#define BPF_FUNC_get_socket_uid 47
#endif

#ifndef BPF_OBJ_NAME_LEN
#define BPF_OBJ_NAME_LEN 16
#endif

#define BPF_ALU64_IMM_OP(OP, DST, IMM) ((struct bpf_insn){.code = BPF_ALU64 | BPF_OP(OP) | BPF_K, .dst_reg = DST, .imm = IMM})
#define BPF_ALU64_REG_OP(OP, DST, SRC) ((struct bpf_insn){.code = BPF_ALU64 | BPF_OP(OP) | BPF_X, .dst_reg = DST, .src_reg = SRC})
#define BPF_ALU32_IMM_OP(OP, DST, IMM) ((struct bpf_insn){.code = BPF_ALU | BPF_OP(OP) | BPF_K, .dst_reg = DST, .imm = IMM})
#define BPF_MOV64_REG(DST, SRC) BPF_ALU64_REG_OP(BPF_MOV, DST, SRC)
#define BPF_MOV64_IMM(DST, IMM) BPF_ALU64_IMM_OP(BPF_MOV, DST, IMM)
#define BPF_MOV32_REG(DST, SRC) ((struct bpf_insn){.code = BPF_ALU | BPF_MOV | BPF_X, .dst_reg = DST, .src_reg = SRC})
#define BPF_ST_MEM(SIZE, DST, OFF, IMM) ((struct bpf_insn){.code = BPF_ST | BPF_SIZE(SIZE) | BPF_MEM, .dst_reg = DST, .off = OFF, .imm = IMM})
#define BPF_STX_MEM(SIZE, DST, SRC, OFF) ((struct bpf_insn){.code = BPF_STX | BPF_SIZE(SIZE) | BPF_MEM, .dst_reg = DST, .src_reg = SRC, .off = OFF})
#define BPF_LDX_MEM(SIZE, DST, SRC, OFF) ((struct bpf_insn){.code = BPF_LDX | BPF_SIZE(SIZE) | BPF_MEM, .dst_reg = DST, .src_reg = SRC, .off = OFF})
#define BPF_JMP_IMM_OP(OP, DST, IMM, OFF) ((struct bpf_insn){.code = BPF_JMP | BPF_OP(OP) | BPF_K, .dst_reg = DST, .off = OFF, .imm = IMM})
#define BPF_JMP_REG_OP(OP, DST, SRC, OFF) ((struct bpf_insn){.code = BPF_JMP | BPF_OP(OP) | BPF_X, .dst_reg = DST, .src_reg = SRC, .off = OFF})
#define BPF_JMP_A(OFF) ((struct bpf_insn){.code = BPF_JMP | BPF_JA, .off = OFF})
#define BPF_CALL_FUNC(FUNC) ((struct bpf_insn){.code = BPF_JMP | BPF_CALL, .imm = FUNC})
#define BPF_EXIT_INSN() ((struct bpf_insn){.code = BPF_JMP | BPF_EXIT})
#define BPF_LD_MAP_FD(DST, FD) \
    (struct bpf_insn){.code = BPF_LD | BPF_DW | BPF_IMM, .dst_reg = DST, .src_reg = BPF_PSEUDO_MAP_FD, .imm = FD}, \
    (struct bpf_insn){.code = 0, .imm = 0}

struct policy {
    uint32_t mode;
    bool bypass_direct_cidrs;
    char direct_cidr_path_v4[MAX_PATH_LEN];
    char direct_cidr_path_v6[MAX_PATH_LEN];
    char xt_output_v4_program_path[MAX_PATH_LEN];
    char xt_output_v6_program_path[MAX_PATH_LEN];
    char xt_prerouting_v4_program_path[MAX_PATH_LEN];
    char xt_prerouting_v6_program_path[MAX_PATH_LEN];
    uint32_t uids[MAX_UIDS];
    size_t uid_count;
};

struct lpm4_key {
    uint32_t prefixlen;
    uint32_t addr;
};

struct lpm6_key {
    uint32_t prefixlen;
    uint8_t addr[16];
};

static int xt_output_v4_fd = -1;
static int xt_output_v6_fd = -1;
static int xt_prerouting_v4_fd = -1;
static int xt_prerouting_v6_fd = -1;
static char active_xt_output_v4_program_path[MAX_PATH_LEN] = DEFAULT_XT_OUTPUT_V4_PROGRAM_PATH;
static char active_xt_output_v6_program_path[MAX_PATH_LEN] = DEFAULT_XT_OUTPUT_V6_PROGRAM_PATH;
static char active_xt_prerouting_v4_program_path[MAX_PATH_LEN] = DEFAULT_XT_PREROUTING_V4_PROGRAM_PATH;
static char active_xt_prerouting_v6_program_path[MAX_PATH_LEN] = DEFAULT_XT_PREROUTING_V6_PROGRAM_PATH;

static long bpf_sys(enum bpf_cmd cmd, union bpf_attr *attr, unsigned int size) {
    return syscall(__NR_bpf, cmd, attr, size);
}

static int create_map(enum bpf_map_type type, uint32_t key_size, uint32_t value_size, uint32_t max_entries, uint32_t flags) {
    union bpf_attr attr;
    memset(&attr, 0, sizeof(attr));
    attr.map_type = type;
    attr.key_size = key_size;
    attr.value_size = value_size;
    attr.max_entries = max_entries;
    attr.map_flags = flags;
    return (int)bpf_sys(BPF_MAP_CREATE, &attr, sizeof(attr));
}

static bool require_map(int fd, const char *name) {
    if (fd >= 0) {
        return true;
    }
    fprintf(stderr, "create %s map failed: errno=%d (%s)\n", name, errno, strerror(errno));
    return false;
}

static int update_map(int map_fd, const void *key, const void *value) {
    union bpf_attr attr;
    memset(&attr, 0, sizeof(attr));
    attr.map_fd = (uint32_t)map_fd;
    attr.key = (uint64_t)(uintptr_t)key;
    attr.value = (uint64_t)(uintptr_t)value;
    attr.flags = BPF_ANY;
    return (int)bpf_sys(BPF_MAP_UPDATE_ELEM, &attr, sizeof(attr));
}

static int load_prog(
    const struct bpf_insn *insns,
    size_t insn_count,
    const char *name,
    enum bpf_prog_type prog_type,
    enum bpf_attach_type expected_attach_type,
    bool log_error
) {
    static char log_buf[LOG_BUF_SIZE];
    union bpf_attr attr;
    memset(&attr, 0, sizeof(attr));
    memset(log_buf, 0, sizeof(log_buf));
    attr.prog_type = prog_type;
    attr.insns = (uint64_t)(uintptr_t)insns;
    attr.insn_cnt = (uint32_t)insn_count;
    attr.license = (uint64_t)(uintptr_t)"GPL";
    attr.expected_attach_type = expected_attach_type;
    attr.log_buf = (uint64_t)(uintptr_t)log_buf;
    attr.log_size = sizeof(log_buf);
    attr.log_level = 1;
    snprintf(attr.prog_name, BPF_OBJ_NAME_LEN, "%s", name);
    int fd = (int)bpf_sys(BPF_PROG_LOAD, &attr, sizeof(attr));
    if (fd < 0 && log_error) {
        fprintf(stderr, "%s load failed: errno=%d (%s)\n%s verifier log:\n%s\n",
                name, errno, strerror(errno), name, log_buf);
    }
    return fd;
}

static char *read_file(const char *path) {
    FILE *file = fopen(path, "rb");
    if (!file) return NULL;
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
    if (!data) {
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

static uint32_t json_uint(const char *json, const char *key, uint32_t fallback) {
    char needle[64];
    snprintf(needle, sizeof(needle), "\"%s\"", key);
    const char *pos = strstr(json, needle);
    if (!pos) return fallback;
    pos = strchr(pos, ':');
    if (!pos) return fallback;
    return (uint32_t)strtoul(pos + 1, NULL, 10);
}

static bool json_bool(const char *json, const char *key, bool fallback) {
    char needle[64];
    snprintf(needle, sizeof(needle), "\"%s\"", key);
    const char *pos = strstr(json, needle);
    if (!pos) return fallback;
    pos = strchr(pos, ':');
    if (!pos) return fallback;
    ++pos;
    while (*pos == ' ' || *pos == '\n' || *pos == '\t') ++pos;
    if (strncmp(pos, "true", 4) == 0) return true;
    if (strncmp(pos, "false", 5) == 0) return false;
    return fallback;
}

static bool json_string(const char *json, const char *key, char *out, size_t out_size) {
    char needle[64];
    snprintf(needle, sizeof(needle), "\"%s\"", key);
    const char *pos = strstr(json, needle);
    if (!pos) return false;
    pos = strchr(pos, ':');
    if (!pos) return false;
    pos = strchr(pos, '"');
    if (!pos) return false;
    ++pos;
    const char *end = strchr(pos, '"');
    if (!end) return false;
    size_t length = (size_t)(end - pos);
    if (length >= out_size) length = out_size - 1U;
    memcpy(out, pos, length);
    out[length] = '\0';
    return true;
}

static void json_uint_array(const char *json, const char *key, uint32_t *out, size_t *count) {
    *count = 0;
    char needle[64];
    snprintf(needle, sizeof(needle), "\"%s\"", key);
    const char *pos = strstr(json, needle);
    if (!pos) return;
    pos = strchr(pos, '[');
    if (!pos) return;
    ++pos;
    while (*pos && *pos != ']' && *count < MAX_UIDS) {
        while (*pos == ' ' || *pos == '\n' || *pos == '\t' || *pos == ',') ++pos;
        if (*pos == ']') break;
        char *end = NULL;
        unsigned long value = strtoul(pos, &end, 10);
        if (end == pos) break;
        out[(*count)++] = (uint32_t)value;
        pos = end;
    }
}

static bool load_policy(const char *path, struct policy *policy) {
    memset(policy, 0, sizeof(*policy));
    char *json = read_file(path);
    if (!json) {
        fprintf(stderr, "failed to read policy: %s\n", path);
        return false;
    }
    policy->mode = json_uint(json, "mode", MODE_GLOBAL);
    policy->bypass_direct_cidrs = json_bool(json, "bypassDirectCidrs", false);
    json_string(json, "directCidrPathV4", policy->direct_cidr_path_v4, sizeof(policy->direct_cidr_path_v4));
    json_string(json, "directCidrPathV6", policy->direct_cidr_path_v6, sizeof(policy->direct_cidr_path_v6));
    if (!json_string(
        json,
        "xtOutputV4ProgramPath",
        policy->xt_output_v4_program_path,
        sizeof(policy->xt_output_v4_program_path)
    )) {
        snprintf(
            policy->xt_output_v4_program_path,
            sizeof(policy->xt_output_v4_program_path),
            "%s",
            DEFAULT_XT_OUTPUT_V4_PROGRAM_PATH
        );
    }
    if (!json_string(
        json,
        "xtOutputV6ProgramPath",
        policy->xt_output_v6_program_path,
        sizeof(policy->xt_output_v6_program_path)
    )) {
        snprintf(
            policy->xt_output_v6_program_path,
            sizeof(policy->xt_output_v6_program_path),
            "%s",
            DEFAULT_XT_OUTPUT_V6_PROGRAM_PATH
        );
    }
    if (!json_string(
        json,
        "xtPreroutingV4ProgramPath",
        policy->xt_prerouting_v4_program_path,
        sizeof(policy->xt_prerouting_v4_program_path)
    )) {
        snprintf(
            policy->xt_prerouting_v4_program_path,
            sizeof(policy->xt_prerouting_v4_program_path),
            "%s",
            DEFAULT_XT_PREROUTING_V4_PROGRAM_PATH
        );
    }
    if (!json_string(
        json,
        "xtPreroutingV6ProgramPath",
        policy->xt_prerouting_v6_program_path,
        sizeof(policy->xt_prerouting_v6_program_path)
    )) {
        snprintf(
            policy->xt_prerouting_v6_program_path,
            sizeof(policy->xt_prerouting_v6_program_path),
            "%s",
            DEFAULT_XT_PREROUTING_V6_PROGRAM_PATH
        );
    }
    json_uint_array(json, "uids", policy->uids, &policy->uid_count);
    free(json);
    if (policy->bypass_direct_cidrs && policy->direct_cidr_path_v4[0] == '\0') {
        fprintf(stderr, "policy is missing directCidrPathV4\n");
        return false;
    }
    if (policy->bypass_direct_cidrs && policy->direct_cidr_path_v6[0] == '\0') {
        fprintf(stderr, "policy is missing directCidrPathV6\n");
        return false;
    }
    return true;
}

static bool parse_cidr(char *line, int *family, uint8_t *addr, uint32_t *prefixlen) {
    char *comment = strchr(line, '#');
    if (comment) *comment = '\0';
    char *start = line;
    while (*start == ' ' || *start == '\t' || *start == '\n' || *start == '\r') ++start;
    if (!*start) return false;
    char *slash = strchr(start, '/');
    if (!slash) return false;
    *slash = '\0';
    char *prefix = slash + 1;
    *prefixlen = (uint32_t)strtoul(prefix, NULL, 10);
    if (strchr(start, ':')) {
        *family = AF_INET6;
        return *prefixlen <= 128U && inet_pton(AF_INET6, start, addr) == 1;
    }
    *family = AF_INET;
    return *prefixlen <= 32U && inet_pton(AF_INET, start, addr) == 1;
}

static bool load_direct_cidrs(int map_fd, const char *path, int expected_family) {
    FILE *file = fopen(path, "r");
    if (!file) {
        return true;
    }
    char line[256];
    uint8_t value = 1;
    while (fgets(line, sizeof(line), file)) {
        int family = 0;
        uint8_t addr[16] = {0};
        uint32_t prefixlen = 0;
        if (!parse_cidr(line, &family, addr, &prefixlen)) {
            continue;
        }
        if (family != expected_family) {
            continue;
        }
        if (family == AF_INET) {
            struct lpm4_key key;
            memset(&key, 0, sizeof(key));
            key.prefixlen = prefixlen;
            memcpy(&key.addr, addr, sizeof(uint32_t));
            update_map(map_fd, &key, &value);
        } else {
            struct lpm6_key key;
            memset(&key, 0, sizeof(key));
            key.prefixlen = prefixlen;
            memcpy(key.addr, addr, sizeof(key.addr));
            update_map(map_fd, &key, &value);
        }
    }
    fclose(file);
    return true;
}

static void load_uids(int uid_map_fd, const struct policy *policy) {
    uint32_t value = UID_SELECTED;
    for (size_t i = 0; i < policy->uid_count; ++i) {
        update_map(uid_map_fd, &policy->uids[i], &value);
    }
}

struct bpf_builder {
    struct bpf_insn insns[512];
    size_t count;
};

enum packet_access_mode {
    PACKET_ACCESS_HELPER,
    PACKET_ACCESS_DIRECT,
};

static void emit(struct bpf_builder *builder, struct bpf_insn insn) {
    if (builder->count < ARRAY_SIZE(builder->insns)) {
        builder->insns[builder->count++] = insn;
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

static void emit_exit_match(struct bpf_builder *builder) {
    emit(builder, BPF_MOV64_IMM(BPF_REG_0, XT_BPF_MATCH));
    emit(builder, BPF_EXIT_INSN());
}

static void emit_exit_nomatch(struct bpf_builder *builder) {
    emit(builder, BPF_MOV64_IMM(BPF_REG_0, XT_BPF_NOMATCH));
    emit(builder, BPF_EXIT_INSN());
}

static void emit_xt_policy_check(
    struct bpf_builder *builder,
    size_t *nomatch_jumps,
    size_t *nomatch_jump_count,
    uint32_t policy_mode
) {
    if (policy_mode == MODE_GLOBAL) {
        return;
    }
    emit(builder, BPF_MOV64_REG(BPF_REG_2, BPF_REG_8));
    emit(builder, BPF_ALU64_IMM_OP(BPF_AND, BPF_REG_2, UID_SELECTED));
    if (policy_mode == MODE_WHITELIST) {
        nomatch_jumps[(*nomatch_jump_count)++] = emit_jump(builder, BPF_JMP_IMM_OP(BPF_JEQ, BPF_REG_2, 0, 0));
    } else {
        nomatch_jumps[(*nomatch_jump_count)++] = emit_jump(builder, BPF_JMP_IMM_OP(BPF_JNE, BPF_REG_2, 0, 0));
    }
}

static size_t emit_skb_load_bytes_const(
    struct bpf_builder *builder,
    int offset,
    int stack_offset,
    int size
) {
    emit(builder, BPF_MOV64_REG(BPF_REG_1, BPF_REG_6));
    emit(builder, BPF_MOV64_IMM(BPF_REG_2, offset));
    emit(builder, BPF_MOV64_REG(BPF_REG_3, BPF_REG_10));
    emit(builder, BPF_ALU64_IMM_OP(BPF_ADD, BPF_REG_3, stack_offset));
    emit(builder, BPF_MOV64_IMM(BPF_REG_4, size));
    emit(builder, BPF_CALL_FUNC(BPF_FUNC_skb_load_bytes));
    return emit_jump(builder, BPF_JMP_IMM_OP(BPF_JNE, BPF_REG_0, 0, 0));
}

static size_t emit_direct_packet_load_bytes_const(
    struct bpf_builder *builder,
    int offset,
    int stack_offset,
    int size
) {
    emit(builder, BPF_LDX_MEM(BPF_W, BPF_REG_1, BPF_REG_6, offsetof(struct __sk_buff, data)));
    emit(builder, BPF_LDX_MEM(BPF_W, BPF_REG_2, BPF_REG_6, offsetof(struct __sk_buff, data_end)));
    emit(builder, BPF_MOV64_REG(BPF_REG_3, BPF_REG_1));
    emit(builder, BPF_ALU64_IMM_OP(BPF_ADD, BPF_REG_3, offset + size));
    size_t out_of_bounds = emit_jump(builder, BPF_JMP_REG_OP(BPF_JGT, BPF_REG_3, BPF_REG_2, 0));
    emit(builder, BPF_ALU64_IMM_OP(BPF_ADD, BPF_REG_1, offset));
    for (int i = 0; i < size; ++i) {
        emit(builder, BPF_LDX_MEM(BPF_B, BPF_REG_3, BPF_REG_1, i));
        emit(builder, BPF_STX_MEM(BPF_B, BPF_REG_10, BPF_REG_3, stack_offset + i));
    }
    return out_of_bounds;
}

static size_t emit_packet_load_bytes_const(
    struct bpf_builder *builder,
    enum packet_access_mode packet_access,
    int offset,
    int stack_offset,
    int size
) {
    if (packet_access == PACKET_ACCESS_DIRECT) {
        return emit_direct_packet_load_bytes_const(builder, offset, stack_offset, size);
    }
    return emit_skb_load_bytes_const(builder, offset, stack_offset, size);
}

static int load_xt_filter_prog(
    uint32_t policy_mode,
    int uid_fd,
    int direct_fd,
    bool apply_uid_policy,
    bool bypass_direct_cidrs,
    enum packet_access_mode packet_access,
    int ip_family,
    const char *program_name,
    bool log_error
) {
    if (ip_family != AF_INET && ip_family != AF_INET6) {
        return -1;
    }
    struct bpf_builder b = {0};
    size_t nomatch_jumps[48];
    size_t nomatch_jump_count = 0;

    emit(&b, BPF_MOV64_REG(BPF_REG_6, BPF_REG_1));

    if (apply_uid_policy && policy_mode != MODE_GLOBAL) {
        emit(&b, BPF_MOV64_REG(BPF_REG_1, BPF_REG_6));
        emit(&b, BPF_CALL_FUNC(BPF_FUNC_get_socket_uid));
        emit(&b, BPF_MOV32_REG(BPF_REG_8, BPF_REG_0));
        emit(&b, BPF_STX_MEM(BPF_W, BPF_REG_10, BPF_REG_8, -8));
        emit_ld_map_fd(&b, BPF_REG_1, uid_fd);
        emit(&b, BPF_MOV64_REG(BPF_REG_2, BPF_REG_10));
        emit(&b, BPF_ALU64_IMM_OP(BPF_ADD, BPF_REG_2, -8));
        emit(&b, BPF_CALL_FUNC(BPF_FUNC_map_lookup_elem));
        emit(&b, BPF_MOV64_IMM(BPF_REG_8, 0));
        size_t uid_value_loaded = emit_jump(&b, BPF_JMP_IMM_OP(BPF_JEQ, BPF_REG_0, 0, 0));
        emit(&b, BPF_LDX_MEM(BPF_W, BPF_REG_8, BPF_REG_0, 0));
        size_t uid_value_label = b.count;
        patch_jump(&b, uid_value_loaded, uid_value_label);
    }

    if (apply_uid_policy) {
        emit_xt_policy_check(&b, nomatch_jumps, &nomatch_jump_count, policy_mode);
    }

    if (bypass_direct_cidrs) {
        if (ip_family == AF_INET) {
            emit(&b, BPF_ST_MEM(BPF_W, BPF_REG_10, -16, 32));
            nomatch_jumps[nomatch_jump_count++] =
                emit_packet_load_bytes_const(&b, packet_access, 16, -12, 4);
            emit_ld_map_fd(&b, BPF_REG_1, direct_fd);
            emit(&b, BPF_MOV64_REG(BPF_REG_2, BPF_REG_10));
            emit(&b, BPF_ALU64_IMM_OP(BPF_ADD, BPF_REG_2, -16));
        } else {
            emit(&b, BPF_ST_MEM(BPF_W, BPF_REG_10, -40, 128));
            nomatch_jumps[nomatch_jump_count++] =
                emit_packet_load_bytes_const(&b, packet_access, 24, -36, 16);
            emit_ld_map_fd(&b, BPF_REG_1, direct_fd);
            emit(&b, BPF_MOV64_REG(BPF_REG_2, BPF_REG_10));
            emit(&b, BPF_ALU64_IMM_OP(BPF_ADD, BPF_REG_2, -40));
        }
        emit(&b, BPF_CALL_FUNC(BPF_FUNC_map_lookup_elem));
        nomatch_jumps[nomatch_jump_count++] = emit_jump(&b, BPF_JMP_IMM_OP(BPF_JNE, BPF_REG_0, 0, 0));
    }

    emit_exit_match(&b);

    size_t nomatch_label = b.count;
    for (size_t i = 0; i < nomatch_jump_count; ++i) {
        patch_jump(&b, nomatch_jumps[i], nomatch_label);
    }
    emit_exit_nomatch(&b);

    return load_prog(b.insns, b.count, program_name, BPF_PROG_TYPE_SOCKET_FILTER, 0, log_error);
}

static int load_xt_filter_prog_with_fallback(
    uint32_t policy_mode,
    int uid_fd,
    int direct_fd,
    bool apply_uid_policy,
    bool bypass_direct_cidrs,
    int ip_family,
    const char *program_name
) {
    int fd = load_xt_filter_prog(
        policy_mode,
        uid_fd,
        direct_fd,
        apply_uid_policy,
        bypass_direct_cidrs,
        PACKET_ACCESS_DIRECT,
        ip_family,
        program_name,
        false
    );
    if (fd >= 0) {
        return fd;
    }
    return load_xt_filter_prog(
        policy_mode,
        uid_fd,
        direct_fd,
        apply_uid_policy,
        bypass_direct_cidrs,
        PACKET_ACCESS_HELPER,
        ip_family,
        program_name,
        true
    );
}

static int pin_bpf_object(int fd, const char *path) {
    char parent[MAX_PATH_LEN];
    snprintf(parent, sizeof(parent), "%s", path);
    char *slash = strrchr(parent, '/');
    if (slash) {
        *slash = '\0';
        mkdir(parent, 0700);
    }
    unlink(path);
    union bpf_attr attr;
    memset(&attr, 0, sizeof(attr));
    attr.pathname = (uint64_t)(uintptr_t)path;
    attr.bpf_fd = (uint32_t)fd;
    int result = (int)bpf_sys(BPF_OBJ_PIN, &attr, sizeof(attr));
    if (result != 0) {
        fprintf(stderr, "pin bpf object failed: path=%s errno=%d (%s)\n", path, errno, strerror(errno));
    }
    return result;
}

static void close_fd(int *fd) {
    if (*fd >= 0) {
        close(*fd);
        *fd = -1;
    }
}

static void cleanup_attaches(void) {
    unlink(active_xt_output_v4_program_path);
    unlink(active_xt_output_v6_program_path);
    unlink(active_xt_prerouting_v4_program_path);
    unlink(active_xt_prerouting_v6_program_path);
    unlink(PROBE_XT_OUTPUT_V4_PROGRAM_PATH);
    unlink(PROBE_XT_OUTPUT_V6_PROGRAM_PATH);
    unlink(PROBE_XT_PREROUTING_V4_PROGRAM_PATH);
    unlink(PROBE_XT_PREROUTING_V6_PROGRAM_PATH);
    rmdir(DEFAULT_BPF_DIR);
    close_fd(&xt_output_v4_fd);
    close_fd(&xt_output_v6_fd);
    close_fd(&xt_prerouting_v4_fd);
    close_fd(&xt_prerouting_v6_fd);
}

static int probe_json(bool need_ipv6) {
    int hash_fd = create_map(BPF_MAP_TYPE_HASH, sizeof(uint32_t), sizeof(uint32_t), 16, 0);
    int lpm4_fd = create_map(BPF_MAP_TYPE_LPM_TRIE, sizeof(struct lpm4_key), sizeof(uint8_t), 16, BPF_F_NO_PREALLOC);
    int lpm6_fd = create_map(BPF_MAP_TYPE_LPM_TRIE, sizeof(struct lpm6_key), sizeof(uint8_t), 16, BPF_F_NO_PREALLOC);
    int probe_xt_output_v4_fd = -1;
    int probe_xt_output_v6_fd = -1;
    int probe_xt_prerouting_v4_fd = -1;
    int probe_xt_prerouting_v6_fd = -1;
    bool xt_output_v4_prog_ok = false;
    bool xt_output_v4_pin_ok = false;
    bool xt_output_v6_prog_ok = false;
    bool xt_output_v6_pin_ok = false;
    bool xt_prerouting_v4_prog_ok = false;
    bool xt_prerouting_v4_pin_ok = false;
    bool xt_prerouting_v6_prog_ok = false;
    bool xt_prerouting_v6_pin_ok = false;
    bool direct_packet_access_ok = false;

    if (hash_fd >= 0 && lpm4_fd >= 0 && lpm6_fd >= 0) {
        probe_xt_output_v4_fd = load_xt_filter_prog(
            MODE_WHITELIST,
            hash_fd,
            lpm4_fd,
            true,
            true,
            PACKET_ACCESS_DIRECT,
            AF_INET,
            "ast_p_out4",
            false
        );
        bool output_v4_direct_ok = probe_xt_output_v4_fd >= 0;
        if (!output_v4_direct_ok) {
            probe_xt_output_v4_fd = load_xt_filter_prog(
                MODE_WHITELIST,
                hash_fd,
                lpm4_fd,
                true,
                true,
                PACKET_ACCESS_HELPER,
                AF_INET,
                "ast_p_out4",
                true
            );
        }
        xt_output_v4_prog_ok = probe_xt_output_v4_fd >= 0;
        if (xt_output_v4_prog_ok) {
            xt_output_v4_pin_ok = pin_bpf_object(probe_xt_output_v4_fd, PROBE_XT_OUTPUT_V4_PROGRAM_PATH) == 0;
            unlink(PROBE_XT_OUTPUT_V4_PROGRAM_PATH);
        }
        probe_xt_output_v6_fd = load_xt_filter_prog(
            MODE_WHITELIST,
            hash_fd,
            lpm6_fd,
            true,
            true,
            PACKET_ACCESS_DIRECT,
            AF_INET6,
            "ast_p_out6",
            false
        );
        bool output_v6_direct_ok = probe_xt_output_v6_fd >= 0;
        if (!output_v6_direct_ok) {
            probe_xt_output_v6_fd = load_xt_filter_prog(
                MODE_WHITELIST,
                hash_fd,
                lpm6_fd,
                true,
                true,
                PACKET_ACCESS_HELPER,
                AF_INET6,
                "ast_p_out6",
                true
            );
        }
        xt_output_v6_prog_ok = probe_xt_output_v6_fd >= 0;
        if (xt_output_v6_prog_ok) {
            xt_output_v6_pin_ok = pin_bpf_object(probe_xt_output_v6_fd, PROBE_XT_OUTPUT_V6_PROGRAM_PATH) == 0;
            unlink(PROBE_XT_OUTPUT_V6_PROGRAM_PATH);
        }
        probe_xt_prerouting_v4_fd = load_xt_filter_prog(
            MODE_GLOBAL,
            hash_fd,
            lpm4_fd,
            false,
            true,
            PACKET_ACCESS_DIRECT,
            AF_INET,
            "ast_p_pre4",
            false
        );
        bool prerouting_v4_direct_ok = probe_xt_prerouting_v4_fd >= 0;
        if (!prerouting_v4_direct_ok) {
            probe_xt_prerouting_v4_fd = load_xt_filter_prog(
                MODE_GLOBAL,
                hash_fd,
                lpm4_fd,
                false,
                true,
                PACKET_ACCESS_HELPER,
                AF_INET,
                "ast_p_pre4",
                true
            );
        }
        xt_prerouting_v4_prog_ok = probe_xt_prerouting_v4_fd >= 0;
        if (xt_prerouting_v4_prog_ok) {
            xt_prerouting_v4_pin_ok =
                pin_bpf_object(probe_xt_prerouting_v4_fd, PROBE_XT_PREROUTING_V4_PROGRAM_PATH) == 0;
            unlink(PROBE_XT_PREROUTING_V4_PROGRAM_PATH);
        }
        probe_xt_prerouting_v6_fd = load_xt_filter_prog(
            MODE_GLOBAL,
            hash_fd,
            lpm6_fd,
            false,
            true,
            PACKET_ACCESS_DIRECT,
            AF_INET6,
            "ast_p_pre6",
            false
        );
        bool prerouting_v6_direct_ok = probe_xt_prerouting_v6_fd >= 0;
        if (!prerouting_v6_direct_ok) {
            probe_xt_prerouting_v6_fd = load_xt_filter_prog(
                MODE_GLOBAL,
                hash_fd,
                lpm6_fd,
                false,
                true,
                PACKET_ACCESS_HELPER,
                AF_INET6,
                "ast_p_pre6",
                true
            );
        }
        xt_prerouting_v6_prog_ok = probe_xt_prerouting_v6_fd >= 0;
        if (xt_prerouting_v6_prog_ok) {
            xt_prerouting_v6_pin_ok =
                pin_bpf_object(probe_xt_prerouting_v6_fd, PROBE_XT_PREROUTING_V6_PROGRAM_PATH) == 0;
            unlink(PROBE_XT_PREROUTING_V6_PROGRAM_PATH);
        }
        direct_packet_access_ok =
            output_v4_direct_ok &&
            output_v6_direct_ok &&
            prerouting_v4_direct_ok &&
            prerouting_v6_direct_ok;
    }

    bool supported = hash_fd >= 0 &&
        lpm4_fd >= 0 &&
        lpm6_fd >= 0 &&
        xt_output_v4_prog_ok &&
        xt_output_v4_pin_ok &&
        xt_output_v6_prog_ok &&
        xt_output_v6_pin_ok &&
        xt_prerouting_v4_prog_ok &&
        xt_prerouting_v4_pin_ok &&
        xt_prerouting_v6_prog_ok &&
        xt_prerouting_v6_pin_ok;
    const char *message = "ok";
    if (hash_fd < 0 || lpm4_fd < 0 || lpm6_fd < 0) {
        message = "Required eBPF maps are unavailable on this device";
    } else if (!xt_output_v4_prog_ok || !xt_output_v6_prog_ok) {
        message = "eBPF xt_bpf OUTPUT socket filter cannot be loaded on this device";
    } else if (!xt_prerouting_v4_prog_ok || !xt_prerouting_v6_prog_ok) {
        message = "eBPF xt_bpf PREROUTING socket filter cannot be loaded on this device";
    } else if (!xt_output_v4_pin_ok || !xt_output_v6_pin_ok ||
               !xt_prerouting_v4_pin_ok || !xt_prerouting_v6_pin_ok) {
        message = "eBPF pinned object path is unavailable on this device";
    }

    printf("{\"supported\":%s,\"message\":\"%s\",\"checks\":[", supported ? "true" : "false", message);
    printf("{\"name\":\"bpf-hash-map\",\"supported\":%s}", hash_fd >= 0 ? "true" : "false");
    printf(",{\"name\":\"bpf-lpm-trie-ipv4\",\"supported\":%s}", lpm4_fd >= 0 ? "true" : "false");
    printf(",{\"name\":\"bpf-lpm-trie-ipv6\",\"supported\":%s}", lpm6_fd >= 0 ? "true" : "false");
    printf(",{\"name\":\"bpf-xt-output-v4-program\",\"supported\":%s}", xt_output_v4_prog_ok ? "true" : "false");
    printf(",{\"name\":\"bpf-xt-output-v6-program\",\"supported\":%s}", xt_output_v6_prog_ok ? "true" : "false");
    printf(",{\"name\":\"bpf-xt-prerouting-v4-program\",\"supported\":%s}", xt_prerouting_v4_prog_ok ? "true" : "false");
    printf(",{\"name\":\"bpf-xt-prerouting-v6-program\",\"supported\":%s}", xt_prerouting_v6_prog_ok ? "true" : "false");
    printf(",{\"name\":\"bpf-direct-packet-access\",\"supported\":%s}", direct_packet_access_ok ? "true" : "false");
    printf(
        ",{\"name\":\"bpf-pinned-object-path\",\"supported\":%s}",
        (xt_output_v4_pin_ok && xt_output_v6_pin_ok &&
         xt_prerouting_v4_pin_ok && xt_prerouting_v6_pin_ok) ? "true" : "false"
    );
    if (need_ipv6) {
        printf(",{\"name\":\"bpf-xt-ipv6-parser\",\"supported\":%s}", xt_output_v6_prog_ok ? "true" : "false");
    }
    printf("]}\n");
    close_fd(&probe_xt_output_v4_fd);
    close_fd(&probe_xt_output_v6_fd);
    close_fd(&probe_xt_prerouting_v4_fd);
    close_fd(&probe_xt_prerouting_v6_fd);
    close_fd(&hash_fd);
    close_fd(&lpm4_fd);
    close_fd(&lpm6_fd);
    return supported ? 0 : 1;
}

static int start_matcher(const char *policy_path) {
    struct policy policy;
    if (!load_policy(policy_path, &policy)) {
        return 2;
    }
    snprintf(active_xt_output_v4_program_path, sizeof(active_xt_output_v4_program_path), "%s", policy.xt_output_v4_program_path);
    snprintf(active_xt_output_v6_program_path, sizeof(active_xt_output_v6_program_path), "%s", policy.xt_output_v6_program_path);
    snprintf(
        active_xt_prerouting_v4_program_path,
        sizeof(active_xt_prerouting_v4_program_path),
        "%s",
        policy.xt_prerouting_v4_program_path
    );
    snprintf(
        active_xt_prerouting_v6_program_path,
        sizeof(active_xt_prerouting_v6_program_path),
        "%s",
        policy.xt_prerouting_v6_program_path
    );
    int uid_fd = create_map(BPF_MAP_TYPE_HASH, sizeof(uint32_t), sizeof(uint32_t), MAX_UIDS, 0);
    int direct4_fd = -1;
    int direct6_fd = -1;
    if (!require_map(uid_fd, "uid-map")) {
        return 2;
    }
    if (policy.bypass_direct_cidrs) {
        direct4_fd = create_map(
            BPF_MAP_TYPE_LPM_TRIE,
            sizeof(struct lpm4_key),
            sizeof(uint8_t),
            MAX_CIDR_MAP_ENTRIES,
            BPF_F_NO_PREALLOC
        );
        direct6_fd = create_map(
            BPF_MAP_TYPE_LPM_TRIE,
            sizeof(struct lpm6_key),
            sizeof(uint8_t),
            MAX_CIDR_MAP_ENTRIES,
            BPF_F_NO_PREALLOC
        );
        if (!require_map(direct4_fd, "direct-cidr-ipv4") ||
            !require_map(direct6_fd, "direct-cidr-ipv6")) {
            return 2;
        }
        load_direct_cidrs(direct4_fd, policy.direct_cidr_path_v4, AF_INET);
        load_direct_cidrs(direct6_fd, policy.direct_cidr_path_v6, AF_INET6);
    }
    load_uids(uid_fd, &policy);

    xt_output_v4_fd = load_xt_filter_prog_with_fallback(
        policy.mode,
        uid_fd,
        direct4_fd,
        true,
        policy.bypass_direct_cidrs,
        AF_INET,
        "ast_xt_out4"
    );
    if (xt_output_v4_fd < 0) {
        return 2;
    }
    if (pin_bpf_object(xt_output_v4_fd, policy.xt_output_v4_program_path) != 0) {
        return 2;
    }
    xt_output_v6_fd = load_xt_filter_prog_with_fallback(
        policy.mode,
        uid_fd,
        direct6_fd,
        true,
        policy.bypass_direct_cidrs,
        AF_INET6,
        "ast_xt_out6"
    );
    if (xt_output_v6_fd < 0) {
        cleanup_attaches();
        return 2;
    }
    if (pin_bpf_object(xt_output_v6_fd, policy.xt_output_v6_program_path) != 0) {
        cleanup_attaches();
        return 2;
    }
    xt_prerouting_v4_fd = load_xt_filter_prog_with_fallback(
        policy.mode,
        uid_fd,
        direct4_fd,
        false,
        policy.bypass_direct_cidrs,
        AF_INET,
        "ast_xt_pre4"
    );
    if (xt_prerouting_v4_fd < 0) {
        cleanup_attaches();
        return 2;
    }
    if (pin_bpf_object(xt_prerouting_v4_fd, policy.xt_prerouting_v4_program_path) != 0) {
        cleanup_attaches();
        return 2;
    }
    xt_prerouting_v6_fd = load_xt_filter_prog_with_fallback(
        policy.mode,
        uid_fd,
        direct6_fd,
        false,
        policy.bypass_direct_cidrs,
        AF_INET6,
        "ast_xt_pre6"
    );
    if (xt_prerouting_v6_fd < 0) {
        cleanup_attaches();
        return 2;
    }
    if (pin_bpf_object(xt_prerouting_v6_fd, policy.xt_prerouting_v6_program_path) != 0) {
        cleanup_attaches();
        return 2;
    }

    close_fd(&xt_output_v4_fd);
    close_fd(&xt_output_v6_fd);
    close_fd(&xt_prerouting_v4_fd);
    close_fd(&xt_prerouting_v6_fd);
    close_fd(&uid_fd);
    close_fd(&direct4_fd);
    close_fd(&direct6_fd);
    return 0;
}

static int stop_matcher(const char *policy_path) {
    struct policy policy;
    if (policy_path && load_policy(policy_path, &policy)) {
        snprintf(
            active_xt_output_v4_program_path,
            sizeof(active_xt_output_v4_program_path),
            "%s",
            policy.xt_output_v4_program_path
        );
        snprintf(
            active_xt_output_v6_program_path,
            sizeof(active_xt_output_v6_program_path),
            "%s",
            policy.xt_output_v6_program_path
        );
        snprintf(
            active_xt_prerouting_v4_program_path,
            sizeof(active_xt_prerouting_v4_program_path),
            "%s",
            policy.xt_prerouting_v4_program_path
        );
        snprintf(
            active_xt_prerouting_v6_program_path,
            sizeof(active_xt_prerouting_v6_program_path),
            "%s",
            policy.xt_prerouting_v6_program_path
        );
    } else {
        snprintf(
            active_xt_output_v4_program_path,
            sizeof(active_xt_output_v4_program_path),
            "%s",
            DEFAULT_XT_OUTPUT_V4_PROGRAM_PATH
        );
        snprintf(
            active_xt_output_v6_program_path,
            sizeof(active_xt_output_v6_program_path),
            "%s",
            DEFAULT_XT_OUTPUT_V6_PROGRAM_PATH
        );
        snprintf(
            active_xt_prerouting_v4_program_path,
            sizeof(active_xt_prerouting_v4_program_path),
            "%s",
            DEFAULT_XT_PREROUTING_V4_PROGRAM_PATH
        );
        snprintf(
            active_xt_prerouting_v6_program_path,
            sizeof(active_xt_prerouting_v6_program_path),
            "%s",
            DEFAULT_XT_PREROUTING_V6_PROGRAM_PATH
        );
    }
    cleanup_attaches();
    return 0;
}

static const char *arg_value(int argc, char **argv, const char *name) {
    for (int i = 1; i + 1 < argc; ++i) {
        if (strcmp(argv[i], name) == 0) {
            return argv[i + 1];
        }
    }
    return NULL;
}

static bool has_arg(int argc, char **argv, const char *name) {
    for (int i = 1; i < argc; ++i) {
        if (strcmp(argv[i], name) == 0) {
            return true;
        }
    }
    return false;
}

int main(int argc, char **argv) {
    if (has_arg(argc, argv, "--probe")) {
        const char *ipv6 = arg_value(argc, argv, "--ipv6");
        return probe_json(ipv6 && strcmp(ipv6, "1") == 0);
    }
    if (has_arg(argc, argv, "--stop")) {
        return stop_matcher(arg_value(argc, argv, "--policy"));
    }
    if (has_arg(argc, argv, "--start")) {
        const char *policy = arg_value(argc, argv, "--policy");
        if (!policy) {
            fprintf(stderr, "--policy is required\n");
            return 2;
        }
        return start_matcher(policy);
    }
    fprintf(stderr, "Usage: %s --probe --json | --start --policy FILE | --stop\n", argv[0]);
    return 2;
}
