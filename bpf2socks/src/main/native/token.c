// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

#include "bpf2socks.h"

#include <string.h>

int bpf2socks_token_lookup(int map_fd, const struct bpf2socks_token_key *key, struct bpf2socks_original_dst *out) {
    if (map_fd < 0 || key == NULL || out == NULL) return -1;
    memset(out, 0, sizeof(*out));
    return bpf2socks_lookup_map(map_fd, key, out);
}
