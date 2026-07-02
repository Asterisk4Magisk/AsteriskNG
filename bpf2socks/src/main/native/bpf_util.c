// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

#include "bpf2socks.h"

#include <linux/unistd.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

#ifndef BPF_OBJ_NAME_LEN
#define BPF_OBJ_NAME_LEN 16
#endif

#define BPF2SOCKS_LOG_BUF_SIZE 65536U

#ifndef BPF_F_NO_PREALLOC
#define BPF_F_NO_PREALLOC 1U
#endif

#ifndef __NR_bpf
#if defined(__aarch64__)
#define __NR_bpf 280
#elif defined(__arm__)
#define __NR_bpf 386
#elif defined(__i386__)
#define __NR_bpf 357
#elif defined(__x86_64__)
#define __NR_bpf 321
#endif
#endif

long bpf2socks_bpf_sys(enum bpf_cmd cmd, union bpf_attr *attr, unsigned int size) {
    return syscall(__NR_bpf, cmd, attr, size);
}

int bpf2socks_create_map(enum bpf_map_type type, uint32_t key_size, uint32_t value_size, uint32_t max_entries, uint32_t flags) {
    union bpf_attr attr;
    memset(&attr, 0, sizeof(attr));
    attr.map_type = type;
    attr.key_size = key_size;
    attr.value_size = value_size;
    attr.max_entries = max_entries;
    attr.map_flags = flags;
    return (int)bpf2socks_bpf_sys(BPF_MAP_CREATE, &attr, sizeof(attr));
}

int bpf2socks_update_map(int map_fd, const void *key, const void *value) {
    union bpf_attr attr;
    memset(&attr, 0, sizeof(attr));
    attr.map_fd = (uint32_t)map_fd;
    attr.key = (uint64_t)(uintptr_t)key;
    attr.value = (uint64_t)(uintptr_t)value;
    attr.flags = BPF_ANY;
    return (int)bpf2socks_bpf_sys(BPF_MAP_UPDATE_ELEM, &attr, sizeof(attr));
}

int bpf2socks_lookup_map(int map_fd, const void *key, void *value) {
    union bpf_attr attr;
    memset(&attr, 0, sizeof(attr));
    attr.map_fd = (uint32_t)map_fd;
    attr.key = (uint64_t)(uintptr_t)key;
    attr.value = (uint64_t)(uintptr_t)value;
    return (int)bpf2socks_bpf_sys(BPF_MAP_LOOKUP_ELEM, &attr, sizeof(attr));
}

int bpf2socks_get_next_key(int map_fd, const void *key, void *next_key) {
    union bpf_attr attr;
    memset(&attr, 0, sizeof(attr));
    attr.map_fd = (uint32_t)map_fd;
    attr.key = (uint64_t)(uintptr_t)key;
    attr.next_key = (uint64_t)(uintptr_t)next_key;
    return (int)bpf2socks_bpf_sys(BPF_MAP_GET_NEXT_KEY, &attr, sizeof(attr));
}

int bpf2socks_delete_map(int map_fd, const void *key) {
    union bpf_attr attr;
    memset(&attr, 0, sizeof(attr));
    attr.map_fd = (uint32_t)map_fd;
    attr.key = (uint64_t)(uintptr_t)key;
    return (int)bpf2socks_bpf_sys(BPF_MAP_DELETE_ELEM, &attr, sizeof(attr));
}

static void mkdir_p(const char *path) {
    if (path == NULL || path[0] == '\0') return;
    char current[BPF2SOCKS_MAX_PATH_LEN];
    snprintf(current, sizeof(current), "%s", path);
    for (char *cursor = current + 1; *cursor != '\0'; ++cursor) {
        if (*cursor != '/') continue;
        *cursor = '\0';
        (void)mkdir(current, 0700);
        *cursor = '/';
    }
    (void)mkdir(current, 0700);
}

int bpf2socks_pin_fd(int fd, const char *path) {
    if (fd < 0 || path == NULL || path[0] == '\0') {
        errno = EINVAL;
        return -1;
    }
    char parent[BPF2SOCKS_MAX_PATH_LEN];
    snprintf(parent, sizeof(parent), "%s", path);
    char *slash = strrchr(parent, '/');
    if (slash != NULL) {
        *slash = '\0';
        if (parent[0] != '\0') {
            mkdir_p(parent);
        }
    }
    (void)unlink(path);
    union bpf_attr attr;
    memset(&attr, 0, sizeof(attr));
    attr.pathname = (uint64_t)(uintptr_t)path;
    attr.bpf_fd = (uint32_t)fd;
    return (int)bpf2socks_bpf_sys(BPF_OBJ_PIN, &attr, sizeof(attr));
}

int bpf2socks_link_create(int prog_fd, int target_fd, enum bpf_attach_type attach_type, uint32_t flags) {
    if (prog_fd < 0 || target_fd < 0) {
        errno = EINVAL;
        return -1;
    }
    union bpf_attr attr;
    memset(&attr, 0, sizeof(attr));
    attr.link_create.prog_fd = (uint32_t)prog_fd;
    attr.link_create.target_fd = (uint32_t)target_fd;
    attr.link_create.attach_type = attach_type;
    attr.link_create.flags = flags;
    return (int)bpf2socks_bpf_sys(BPF_LINK_CREATE, &attr, sizeof(attr));
}

static int load_prog_once(
    const struct bpf_insn *insns,
    size_t insn_count,
    const char *name,
    enum bpf_prog_type prog_type,
    enum bpf_attach_type expected_attach_type,
    char *log_buf,
    uint32_t log_size,
    uint32_t log_level) {
    union bpf_attr attr;
    memset(&attr, 0, sizeof(attr));
    attr.prog_type = prog_type;
    attr.insns = (uint64_t)(uintptr_t)insns;
    attr.insn_cnt = (uint32_t)insn_count;
    attr.license = (uint64_t)(uintptr_t)"GPL";
    attr.expected_attach_type = expected_attach_type;
    attr.log_buf = log_level != 0U ? (uint64_t)(uintptr_t)log_buf : 0U;
    attr.log_size = log_level != 0U ? log_size : 0U;
    attr.log_level = log_level;
    if (name != NULL) {
        snprintf(attr.prog_name, BPF_OBJ_NAME_LEN, "%s", name);
    }
    return (int)bpf2socks_bpf_sys(BPF_PROG_LOAD, &attr, sizeof(attr));
}

static int retry_without_verifier_log(
    const struct bpf_insn *insns,
    size_t insn_count,
    const char *name,
    enum bpf_prog_type prog_type,
    enum bpf_attach_type expected_attach_type) {
    return load_prog_once(insns, insn_count, name, prog_type, expected_attach_type, NULL, 0U, 0U);
}

int bpf2socks_load_prog(
    const struct bpf_insn *insns,
    size_t insn_count,
    const char *name,
    enum bpf_prog_type prog_type,
    enum bpf_attach_type expected_attach_type,
    bool log_error) {
    static char log_buf[BPF2SOCKS_LOG_BUF_SIZE];
    memset(log_buf, 0, sizeof(log_buf));
    uint32_t log_level = log_error ? 1U : 0U;
    int fd = load_prog_once(
        insns,
        insn_count,
        name,
        prog_type,
        expected_attach_type,
        log_buf,
        sizeof(log_buf),
        log_level);
    if (fd < 0 && log_error && (errno == EAGAIN || errno == ENOSPC)) {
        int log_errno = errno;
        fd = retry_without_verifier_log(
            insns,
            insn_count,
            name,
            prog_type,
            expected_attach_type);
        if (fd >= 0) return fd;
        if (errno == EAGAIN || errno == ENOSPC) {
            errno = log_errno;
        }
    }
    if (fd < 0 && log_error) {
        fprintf(
            stderr,
            "%s load failed: errno=%d (%s)\nverifier log:\n%s\n",
            name == NULL ? "bpf program" : name,
            errno,
            strerror(errno),
            log_buf);
    }
    return fd;
}

int bpf2socks_attach_prog(int cgroup_fd, int prog_fd, enum bpf_attach_type attach_type) {
    union bpf_attr attr;
    memset(&attr, 0, sizeof(attr));
    attr.target_fd = (uint32_t)cgroup_fd;
    attr.attach_bpf_fd = (uint32_t)prog_fd;
    attr.attach_type = attach_type;
#ifdef BPF_F_ALLOW_MULTI
    attr.attach_flags = BPF_F_ALLOW_MULTI;
#else
    attr.attach_flags = 0U;
#endif
    int result = (int)bpf2socks_bpf_sys(BPF_PROG_ATTACH, &attr, sizeof(attr));
#ifdef BPF_F_ALLOW_MULTI
    if (result != 0 && (errno == EINVAL || errno == EPERM || errno == ENOTSUP || errno == EOPNOTSUPP)) {
        memset(&attr, 0, sizeof(attr));
        attr.target_fd = (uint32_t)cgroup_fd;
        attr.attach_bpf_fd = (uint32_t)prog_fd;
        attr.attach_type = attach_type;
        attr.attach_flags = 0U;
        result = (int)bpf2socks_bpf_sys(BPF_PROG_ATTACH, &attr, sizeof(attr));
    }
#endif
    return result;
}

int bpf2socks_detach_prog(int cgroup_fd, int prog_fd, enum bpf_attach_type attach_type) {
    union bpf_attr attr;
    memset(&attr, 0, sizeof(attr));
    attr.target_fd = (uint32_t)cgroup_fd;
    if (prog_fd >= 0) {
        attr.attach_bpf_fd = (uint32_t)prog_fd;
    }
    attr.attach_type = attach_type;
    return (int)bpf2socks_bpf_sys(BPF_PROG_DETACH, &attr, sizeof(attr));
}

static int bpf2socks_prog_fd_by_id(uint32_t prog_id) {
    union bpf_attr attr;
    memset(&attr, 0, sizeof(attr));
    attr.prog_id = prog_id;
    return (int)bpf2socks_bpf_sys(BPF_PROG_GET_FD_BY_ID, &attr, sizeof(attr));
}

static int bpf2socks_prog_name(int prog_fd, char *name, size_t name_size) {
    if (name == NULL || name_size == 0U) return -1;
    struct bpf_prog_info info;
    memset(&info, 0, sizeof(info));
    union bpf_attr attr;
    memset(&attr, 0, sizeof(attr));
    attr.info.bpf_fd = (uint32_t)prog_fd;
    attr.info.info_len = sizeof(info);
    attr.info.info = (uint64_t)(uintptr_t)&info;
    if (bpf2socks_bpf_sys(BPF_OBJ_GET_INFO_BY_FD, &attr, sizeof(attr)) != 0) {
        return -1;
    }
    snprintf(name, name_size, "%s", info.name);
    return 0;
}

static int bpf2socks_detach_named_for_type(int cgroup_fd, enum bpf_attach_type attach_type) {
    uint32_t prog_ids[64];
    union bpf_attr attr;
    memset(prog_ids, 0, sizeof(prog_ids));
    memset(&attr, 0, sizeof(attr));
    attr.query.target_fd = (uint32_t)cgroup_fd;
    attr.query.attach_type = attach_type;
    attr.query.prog_cnt = (uint32_t)(sizeof(prog_ids) / sizeof(prog_ids[0]));
    attr.query.prog_ids = (uint64_t)(uintptr_t)prog_ids;
    if (bpf2socks_bpf_sys(BPF_PROG_QUERY, &attr, sizeof(attr)) != 0) {
        return -1;
    }

    int first_error = 0;
    for (uint32_t i = 0; i < attr.query.prog_cnt && i < (uint32_t)(sizeof(prog_ids) / sizeof(prog_ids[0])); ++i) {
        int prog_fd = bpf2socks_prog_fd_by_id(prog_ids[i]);
        if (prog_fd < 0) {
            if (first_error == 0) first_error = errno;
            continue;
        }
        char name[BPF_OBJ_NAME_LEN];
        if (bpf2socks_prog_name(prog_fd, name, sizeof(name)) == 0 &&
            strncmp(name, "b2s_", 4) == 0 &&
            bpf2socks_detach_prog(cgroup_fd, prog_fd, attach_type) != 0 &&
            first_error == 0) {
            first_error = errno;
        }
        close(prog_fd);
    }
    if (first_error != 0) {
        errno = first_error;
        return -1;
    }
    return 0;
}

int bpf2socks_detach_named_progs(int cgroup_fd) {
    if (cgroup_fd < 0) {
        errno = EBADF;
        return -1;
    }
    int first_error = 0;
    const enum bpf_attach_type attach_types[] = {
        BPF_CGROUP_INET4_CONNECT,
        BPF_CGROUP_UDP4_SENDMSG,
        BPF_CGROUP_UDP4_RECVMSG,
        BPF_CGROUP_INET6_CONNECT,
        BPF_CGROUP_UDP6_SENDMSG,
        BPF_CGROUP_UDP6_RECVMSG,
    };
    for (size_t i = 0; i < sizeof(attach_types) / sizeof(attach_types[0]); ++i) {
        if (bpf2socks_detach_named_for_type(cgroup_fd, attach_types[i]) != 0 &&
            first_error == 0 &&
            errno != EINVAL &&
            errno != ENOTSUP &&
            errno != EOPNOTSUPP) {
            first_error = errno;
        }
    }
    if (first_error != 0) {
        errno = first_error;
        return -1;
    }
    return 0;
}

int bpf2socks_detach_cgroup_path(const char *cgroup_path) {
    const char *path = cgroup_path != NULL && cgroup_path[0] != '\0' ? cgroup_path : BPF2SOCKS_DEFAULT_CGROUP_PATH;
    int cgroup_fd = open(path, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (cgroup_fd < 0) return -1;
    (void)bpf2socks_detach_named_progs(cgroup_fd);
    (void)bpf2socks_detach_prog(cgroup_fd, -1, BPF_CGROUP_INET4_CONNECT);
    (void)bpf2socks_detach_prog(cgroup_fd, -1, BPF_CGROUP_UDP4_SENDMSG);
    (void)bpf2socks_detach_prog(cgroup_fd, -1, BPF_CGROUP_UDP4_RECVMSG);
    (void)bpf2socks_detach_prog(cgroup_fd, -1, BPF_CGROUP_INET6_CONNECT);
    (void)bpf2socks_detach_prog(cgroup_fd, -1, BPF_CGROUP_UDP6_SENDMSG);
    (void)bpf2socks_detach_prog(cgroup_fd, -1, BPF_CGROUP_UDP6_RECVMSG);
    close(cgroup_fd);
    return 0;
}
