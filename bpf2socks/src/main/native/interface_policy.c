// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

#include "bpf2socks.h"

#include <arpa/inet.h>
#include <errno.h>
#include <linux/netlink.h>
#include <linux/rtnetlink.h>
#include <net/if.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

struct bpf2socks_interface_runtime {
    pthread_t thread;
    int netlink_fd;
    const struct bpf2socks_policy_config *policy;
    struct bpf2socks_bpf_runtime *bpf_runtime;
};

static int open_route_netlink(uint32_t groups) {
    int fd = socket(AF_NETLINK, SOCK_RAW | SOCK_CLOEXEC, NETLINK_ROUTE);
    if (fd < 0) return -1;

    struct sockaddr_nl local;
    memset(&local, 0, sizeof(local));
    local.nl_family = AF_NETLINK;
    local.nl_groups = groups;
    if (bind(fd, (struct sockaddr *)&local, sizeof(local)) != 0) {
        int saved = errno;
        close(fd);
        errno = saved;
        return -1;
    }
    return fd;
}

static bool nlmsg_ok_policy(const struct nlmsghdr *nh, int remaining) {
    return remaining >= (int)sizeof(struct nlmsghdr) &&
        nh->nlmsg_len >= sizeof(struct nlmsghdr) &&
        (int)nh->nlmsg_len <= remaining;
}

static void clear_ifindex_map(int map_fd) {
    uint32_t key;
    while (bpf2socks_get_next_key(map_fd, NULL, &key) == 0) {
        if (bpf2socks_delete_map(map_fd, &key) != 0) break;
    }
}

static void clear_lpm4_map(int map_fd) {
    struct bpf2socks_lpm4_key key;
    while (bpf2socks_get_next_key(map_fd, NULL, &key) == 0) {
        if (bpf2socks_delete_map(map_fd, &key) != 0) break;
    }
}

static int refresh_ignored_ifindices(const struct bpf2socks_interface_runtime *interfaces) {
    clear_ifindex_map(interfaces->bpf_runtime->ignored_ifindex_map_fd);

    uint8_t value = 1U;
    for (size_t i = 0; i < interfaces->policy->ignored_interface_count && i < BPF2SOCKS_MAX_INTERFACES; ++i) {
        const char *name = interfaces->policy->ignored_interfaces[i];
        if (name[0] == '\0') continue;
        unsigned int ifindex = if_nametoindex(name);
        if (ifindex == 0U) continue;
        uint32_t key = ifindex;
        if (bpf2socks_update_map(interfaces->bpf_runtime->ignored_ifindex_map_fd, &key, &value) < 0) {
            return -1;
        }
    }
    return 0;
}

static bool ifindex_ignored(const struct bpf2socks_interface_runtime *interfaces, int ifindex) {
    if (ifindex <= 0) return false;
    uint32_t key = (uint32_t)ifindex;
    uint8_t value = 0U;
    return bpf2socks_lookup_map(interfaces->bpf_runtime->ignored_ifindex_map_fd, &key, &value) == 0;
}

static uint32_t mask_ipv4_route_addr(uint32_t addr, uint8_t prefixlen) {
    if (prefixlen == 0U) return 0U;
    if (prefixlen >= 32U) return addr;
    uint32_t mask = 0xffffffffU << (32U - prefixlen);
    return htonl(ntohl(addr) & mask);
}

static int add_ignored_route(const struct bpf2socks_interface_runtime *interfaces, const struct nlmsghdr *nh) {
    const struct rtmsg *rtm = (const struct rtmsg *)NLMSG_DATA(nh);
    if (rtm->rtm_family != AF_INET || rtm->rtm_dst_len > 32U || rtm->rtm_type != RTN_UNICAST) {
        return 0;
    }

    int len = RTM_PAYLOAD(nh);
    const struct rtattr *attr = RTM_RTA(rtm);
    int oif = 0;
    uint32_t dst = 0U;
    for (; RTA_OK(attr, len); attr = RTA_NEXT(attr, len)) {
        if (attr->rta_type == RTA_OIF && RTA_PAYLOAD(attr) >= (int)sizeof(oif)) {
            memcpy(&oif, RTA_DATA(attr), sizeof(oif));
        } else if (attr->rta_type == RTA_DST && RTA_PAYLOAD(attr) >= (int)sizeof(dst)) {
            memcpy(&dst, RTA_DATA(attr), sizeof(dst));
        }
    }

    if (!ifindex_ignored(interfaces, oif)) return 0;

    struct bpf2socks_lpm4_key key;
    memset(&key, 0, sizeof(key));
    key.prefixlen = rtm->rtm_dst_len;
    key.addr = mask_ipv4_route_addr(dst, rtm->rtm_dst_len);
    uint8_t value = 1U;
    return bpf2socks_update_map(interfaces->bpf_runtime->ignored_route_cidr4_map_fd, &key, &value);
}

static int dump_ignored_routes(const struct bpf2socks_interface_runtime *interfaces) {
    clear_lpm4_map(interfaces->bpf_runtime->ignored_route_cidr4_map_fd);

    int fd = open_route_netlink(0U);
    if (fd < 0) return -1;

    struct {
        struct nlmsghdr nh;
        struct rtmsg rt;
    } req;
    memset(&req, 0, sizeof(req));
    req.nh.nlmsg_len = NLMSG_LENGTH(sizeof(req.rt));
    req.nh.nlmsg_type = RTM_GETROUTE;
    req.nh.nlmsg_flags = NLM_F_REQUEST | NLM_F_DUMP;
    req.nh.nlmsg_seq = 1U;
    req.rt.rtm_family = AF_INET;

    struct sockaddr_nl kernel;
    memset(&kernel, 0, sizeof(kernel));
    kernel.nl_family = AF_NETLINK;
    if (sendto(fd, &req, req.nh.nlmsg_len, 0, (struct sockaddr *)&kernel, sizeof(kernel)) < 0) {
        int saved = errno;
        close(fd);
        errno = saved;
        return -1;
    }

    int result = 0;
    char buf[16384];
    while (result == 0) {
        ssize_t len = recv(fd, buf, sizeof(buf), 0);
        if (len < 0 && errno == EINTR) continue;
        if (len <= 0) {
            result = -1;
            if (len == 0) errno = ECONNRESET;
            break;
        }
        int remaining = (int)len;
        for (struct nlmsghdr *nh = (struct nlmsghdr *)buf; nlmsg_ok_policy(nh, remaining); nh = NLMSG_NEXT(nh, remaining)) {
            if (nh->nlmsg_type == NLMSG_DONE) {
                close(fd);
                return 0;
            }
            if (nh->nlmsg_type == NLMSG_ERROR) {
                const struct nlmsgerr *err = (const struct nlmsgerr *)NLMSG_DATA(nh);
                errno = err->error < 0 ? -err->error : EIO;
                result = -1;
                break;
            }
            if (nh->nlmsg_type == RTM_NEWROUTE && add_ignored_route(interfaces, nh) < 0) {
                result = -1;
                break;
            }
        }
    }

    int saved = errno;
    close(fd);
    errno = saved;
    return result;
}

static int refresh_interface_policy(const struct bpf2socks_interface_runtime *interfaces) {
    if (refresh_ignored_ifindices(interfaces) < 0) return -1;
    return dump_ignored_routes(interfaces);
}

static void handle_interface_policy_message(
    const struct bpf2socks_interface_runtime *interfaces,
    const struct nlmsghdr *nh) {
    if (nh->nlmsg_type == RTM_NEWLINK || nh->nlmsg_type == RTM_DELLINK) {
        if (refresh_interface_policy(interfaces) < 0) {
            fprintf(stderr, "failed to refresh bpf2socks ignored interface policy: errno=%d\n", errno);
        }
    } else if (nh->nlmsg_type == RTM_NEWROUTE || nh->nlmsg_type == RTM_DELROUTE) {
        if (dump_ignored_routes(interfaces) < 0) {
            fprintf(stderr, "failed to refresh bpf2socks ignored interface routes: errno=%d\n", errno);
        }
    }
}

static void *interface_policy_thread(void *arg) {
    struct bpf2socks_interface_runtime *interfaces = arg;
    char buf[8192];
    while (!bpf2socks_stop_requested) {
        ssize_t len = recv(interfaces->netlink_fd, buf, sizeof(buf), 0);
        if (len < 0 && errno == EINTR) continue;
        if (len <= 0) break;
        int remaining = (int)len;
        for (struct nlmsghdr *nh = (struct nlmsghdr *)buf; nlmsg_ok_policy(nh, remaining); nh = NLMSG_NEXT(nh, remaining)) {
            if (nh->nlmsg_type == NLMSG_DONE) break;
            if (nh->nlmsg_type == NLMSG_ERROR) continue;
            handle_interface_policy_message(interfaces, nh);
        }
    }
    return NULL;
}

int bpf2socks_interface_policy_start(
    const struct bpf2socks_policy_config *policy,
    struct bpf2socks_bpf_runtime *runtime) {
    if (policy == NULL || runtime == NULL) {
        errno = EINVAL;
        return -1;
    }
    if (policy->ignored_interface_count == 0U) return 0;
    if (runtime->ignored_ifindex_map_fd < 0 || runtime->ignored_route_cidr4_map_fd < 0) {
        errno = EBADF;
        return -1;
    }

    struct bpf2socks_interface_runtime *interfaces = calloc(1U, sizeof(*interfaces));
    if (interfaces == NULL) return -1;
    interfaces->netlink_fd = -1;
    interfaces->policy = policy;
    interfaces->bpf_runtime = runtime;

    interfaces->netlink_fd = open_route_netlink(RTMGRP_LINK | RTMGRP_IPV4_ROUTE);
    if (interfaces->netlink_fd < 0) {
        int saved = errno;
        free(interfaces);
        errno = saved;
        return -1;
    }

    if (refresh_interface_policy(interfaces) < 0) {
        int saved = errno;
        close(interfaces->netlink_fd);
        free(interfaces);
        errno = saved;
        return -1;
    }

    int thread_result = pthread_create(&interfaces->thread, NULL, interface_policy_thread, interfaces);
    if (thread_result != 0) {
        close(interfaces->netlink_fd);
        free(interfaces);
        errno = thread_result;
        return -1;
    }

    runtime->interfaces = interfaces;
    return 0;
}

void bpf2socks_interface_policy_stop(struct bpf2socks_bpf_runtime *runtime) {
    if (runtime == NULL || runtime->interfaces == NULL) return;
    struct bpf2socks_interface_runtime *interfaces = runtime->interfaces;
    runtime->interfaces = NULL;

    int fd = interfaces->netlink_fd;
    interfaces->netlink_fd = -1;
    if (fd >= 0) {
        (void)shutdown(fd, SHUT_RD);
        close(fd);
    }
    (void)pthread_join(interfaces->thread, NULL);
    free(interfaces);
}
