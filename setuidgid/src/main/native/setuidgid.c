#define _GNU_SOURCE

#include <grp.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/types.h>
#include <unistd.h>

static void usage(const char *name) {
    fprintf(stderr, "usage: %s uid [gid] prog args\n", name);
    exit(1);
}

static void fail(const char *message) {
    perror(message);
    exit(1);
}

static int parse_id(const char *value, const char *name) {
    char *end = NULL;
    long parsed = strtol(value, &end, 10);
    if (end == value || *end != '\0' || parsed < 0 || parsed > 2147483647L) {
        fprintf(stderr, "invalid %s: %s\n", name, value);
        exit(1);
    }
    return (int)parsed;
}

static int is_id(const char *value) {
    if (value == NULL || *value == '\0') return 0;
    for (const char *p = value; *p != '\0'; ++p) {
        if (*p < '0' || *p > '9') return 0;
    }
    return 1;
}

int main(int argc, char **argv) {
    if (argc < 3) {
        usage(argv[0]);
    }

    int uid = parse_id(argv[1], "uid");
    int gid = uid;
    int prog_index = 2;
    if (argc >= 4 && is_id(argv[2])) {
        gid = parse_id(argv[2], "gid");
        prog_index = 3;
    }

    if (setgroups(0, NULL) != 0) fail("setgroups");
    if (setresgid(gid, gid, gid) != 0) fail("setresgid");
    if (setresuid(uid, uid, uid) != 0) fail("setresuid");

    execvp(argv[prog_index], &argv[prog_index]);
    fail("exec");
}
