#define _GNU_SOURCE

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <linux/netlink.h>
#include <linux/rtnetlink.h>
#include <poll.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>

#ifndef RTMGRP_IPV6_IFADDR
#define RTMGRP_IPV6_IFADDR 0x100
#endif

static const char *IPV6_CONF_DIR = "/proc/sys/net/ipv6/conf";
static const int STARTUP_RETRY_DELAY_MS = 1000;
static const int EVENT_RETRY_DELAY_MS = 300;

static volatile sig_atomic_t stop_requested = 0;

typedef struct {
    char *iface;
    char value;
} StateEntry;

typedef struct {
    StateEntry *entries;
    size_t count;
    size_t capacity;
} State;

typedef struct {
    const char *pid_path;
    const char *log_path;
    FILE *log_file;
} Options;

static void on_signal(int signal_number) {
    (void) signal_number;
    stop_requested = 1;
}

static void usage(const char *name) {
    fprintf(
        stderr,
        "usage: %s daemon --pid PATH --log PATH\n",
        name
    );
    exit(1);
}

static void open_log(Options *options) {
    if (options->log_path == NULL) {
        options->log_file = stderr;
        return;
    }
    options->log_file = fopen(options->log_path, "a");
    if (options->log_file == NULL) {
        options->log_file = stderr;
    }
    setvbuf(options->log_file, NULL, _IOLBF, 0);
}

static void close_log(Options *options) {
    if (options->log_file != NULL && options->log_file != stderr) {
        fclose(options->log_file);
    }
    options->log_file = NULL;
}

static void log_message(Options *options, const char *format, ...) {
    FILE *log_file = options->log_file != NULL ? options->log_file : stderr;
    time_t now = time(NULL);
    struct tm tm_value;
    char timestamp[32];
    if (localtime_r(&now, &tm_value) != NULL) {
        strftime(timestamp, sizeof(timestamp), "%Y-%m-%d %H:%M:%S", &tm_value);
    } else {
        snprintf(timestamp, sizeof(timestamp), "time-unavailable");
    }

    fprintf(log_file, "[%s] ", timestamp);
    va_list args;
    va_start(args, format);
    vfprintf(log_file, format, args);
    va_end(args);
    fputc('\n', log_file);
}

static int parse_options(int argc, char **argv, int start_index, Options *options) {
    for (int i = start_index; i < argc; ++i) {
        if (strcmp(argv[i], "--pid") == 0 && i + 1 < argc) {
            options->pid_path = argv[++i];
        } else if (strcmp(argv[i], "--log") == 0 && i + 1 < argc) {
            options->log_path = argv[++i];
        } else {
            return -1;
        }
    }
    return 0;
}

static char *copy_string(const char *value) {
    size_t length = strlen(value);
    char *copy = malloc(length + 1);
    if (copy == NULL) {
        return NULL;
    }
    memcpy(copy, value, length + 1);
    return copy;
}

static void free_state(State *state) {
    for (size_t i = 0; i < state->count; ++i) {
        free(state->entries[i].iface);
    }
    free(state->entries);
    state->entries = NULL;
    state->count = 0;
    state->capacity = 0;
}

static ssize_t find_state_entry(const State *state, const char *iface) {
    for (size_t i = 0; i < state->count; ++i) {
        if (strcmp(state->entries[i].iface, iface) == 0) {
            return (ssize_t) i;
        }
    }
    return -1;
}

static int add_state_entry(State *state, const char *iface, char value) {
    if (find_state_entry(state, iface) >= 0) {
        return 0;
    }
    if (state->count == state->capacity) {
        size_t next_capacity = state->capacity == 0 ? 8 : state->capacity * 2;
        StateEntry *next_entries = realloc(state->entries, next_capacity * sizeof(StateEntry));
        if (next_entries == NULL) {
            return -1;
        }
        state->entries = next_entries;
        state->capacity = next_capacity;
    }
    char *iface_copy = copy_string(iface);
    if (iface_copy == NULL) {
        return -1;
    }
    state->entries[state->count].iface = iface_copy;
    state->entries[state->count].value = value;
    state->count++;
    return 0;
}

static int build_disable_ipv6_path(const char *iface, char *path, size_t path_size) {
    int written = snprintf(path, path_size, "%s/%s/disable_ipv6", IPV6_CONF_DIR, iface);
    if (written < 0 || (size_t) written >= path_size) {
        errno = ENAMETOOLONG;
        return -1;
    }
    return 0;
}

static int read_disable_ipv6_value(const char *iface, char *value) {
    char path[PATH_MAX];
    if (build_disable_ipv6_path(iface, path, sizeof(path)) != 0) {
        return -1;
    }

    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        return -1;
    }

    char buffer[8];
    ssize_t count = read(fd, buffer, sizeof(buffer));
    int saved_errno = errno;
    close(fd);
    errno = saved_errno;
    if (count <= 0) {
        return -1;
    }

    for (ssize_t i = 0; i < count; ++i) {
        if (buffer[i] == '0' || buffer[i] == '1') {
            *value = buffer[i];
            return 0;
        }
    }
    errno = EINVAL;
    return -1;
}

static int write_disable_ipv6_value(const char *iface, char value) {
    char path[PATH_MAX];
    if (build_disable_ipv6_path(iface, path, sizeof(path)) != 0) {
        return -1;
    }

    int fd = open(path, O_WRONLY | O_CLOEXEC);
    if (fd < 0) {
        return -1;
    }

    char buffer[2] = { value, '\n' };
    ssize_t written = write(fd, buffer, sizeof(buffer));
    int saved_errno = errno;
    close(fd);
    errno = saved_errno;
    if (written != (ssize_t) sizeof(buffer)) {
        if (written >= 0) {
            errno = EIO;
        }
        return -1;
    }
    return 0;
}

static int should_touch_iface(const char *iface) {
    if (iface == NULL || iface[0] == '\0') {
        return 0;
    }
    if (strcmp(iface, ".") == 0 || strcmp(iface, "..") == 0) {
        return 0;
    }
    if (strcmp(iface, "all") == 0 || strcmp(iface, "default") == 0 || strcmp(iface, "lo") == 0) {
        return 0;
    }
    if (strchr(iface, '/') != NULL) {
        return 0;
    }
    return 1;
}

static int record_original_value(Options *options, State *state, const char *iface) {
    if (find_state_entry(state, iface) >= 0) {
        return 0;
    }

    char value = '\0';
    if (read_disable_ipv6_value(iface, &value) != 0) {
        log_message(options, "skip iface=%s read failed: %s", iface, strerror(errno));
        return -1;
    }
    if (add_state_entry(state, iface, value) != 0) {
        log_message(options, "skip iface=%s snapshot allocation failed", iface);
        return -1;
    }
    return 0;
}

static void disable_iface(Options *options, State *state, const char *iface) {
    if (record_original_value(options, state, iface) != 0) {
        return;
    }
    if (write_disable_ipv6_value(iface, '1') != 0) {
        log_message(options, "failed to disable iface=%s: %s", iface, strerror(errno));
        return;
    }
    log_message(options, "disabled iface=%s", iface);
}

static void sweep_disable_ipv6(Options *options, State *state) {
    disable_iface(options, state, "default");

    DIR *dir = opendir(IPV6_CONF_DIR);
    if (dir == NULL) {
        log_message(options, "failed to list %s: %s", IPV6_CONF_DIR, strerror(errno));
        return;
    }

    struct dirent *entry = NULL;
    while ((entry = readdir(dir)) != NULL) {
        if (!should_touch_iface(entry->d_name)) {
            continue;
        }
        disable_iface(options, state, entry->d_name);
    }
    closedir(dir);
}

static void restore_ipv6(Options *options, const State *state) {
    for (size_t i = 0; i < state->count; ++i) {
        const char *iface = state->entries[i].iface;
        char path[PATH_MAX];
        if (build_disable_ipv6_path(iface, path, sizeof(path)) != 0) {
            log_message(options, "skip restore iface=%s path too long", iface);
            continue;
        }
        if (access(path, F_OK) != 0) {
            log_message(options, "skip restore iface=%s missing", iface);
            continue;
        }
        if (write_disable_ipv6_value(iface, state->entries[i].value) != 0) {
            log_message(options, "failed to restore iface=%s: %s", iface, strerror(errno));
            continue;
        }
        log_message(options, "restored iface=%s value=%c", iface, state->entries[i].value);
    }
}

static int write_pid_file(Options *options) {
    FILE *file = fopen(options->pid_path, "w");
    if (file == NULL) {
        return -1;
    }
    fprintf(file, "%ld\n", (long) getpid());
    if (fclose(file) != 0) {
        return -1;
    }
    return 0;
}

static int open_netlink_socket(Options *options) {
    int fd = socket(AF_NETLINK, SOCK_RAW | SOCK_CLOEXEC, NETLINK_ROUTE);
    if (fd < 0) {
        log_message(options, "failed to open netlink socket: %s", strerror(errno));
        return -1;
    }

    struct sockaddr_nl address;
    memset(&address, 0, sizeof(address));
    address.nl_family = AF_NETLINK;
    address.nl_groups = RTMGRP_IPV6_IFADDR;

    if (bind(fd, (struct sockaddr *) &address, sizeof(address)) != 0) {
        log_message(options, "failed to bind netlink socket: %s", strerror(errno));
        close(fd);
        return -1;
    }
    return fd;
}

static void drain_netlink_socket(int fd) {
    char buffer[8192];
    while (1) {
        ssize_t count = recv(fd, buffer, sizeof(buffer), MSG_DONTWAIT);
        if (count < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) {
                return;
            }
            return;
        }
        if (count == 0) {
            return;
        }
    }
}

static long long monotonic_millis(void) {
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) {
        return 0;
    }
    return (long long) ts.tv_sec * 1000LL + ts.tv_nsec / 1000000LL;
}

static int run_daemon(Options *options) {
    open_log(options);
    log_message(options, "starting ipv6disabler daemon");

    struct sigaction action;
    memset(&action, 0, sizeof(action));
    action.sa_handler = on_signal;
    sigemptyset(&action.sa_mask);
    sigaction(SIGTERM, &action, NULL);
    sigaction(SIGINT, &action, NULL);

    State state = {0};

    int netlink_fd = open_netlink_socket(options);
    if (netlink_fd < 0) {
        free_state(&state);
        close_log(options);
        return 1;
    }

    if (write_pid_file(options) != 0) {
        log_message(options, "failed to write pid file %s: %s", options->pid_path, strerror(errno));
        close(netlink_fd);
        free_state(&state);
        close_log(options);
        return 1;
    }

    sweep_disable_ipv6(options, &state);

    long long retry_at = monotonic_millis() + STARTUP_RETRY_DELAY_MS;
    while (!stop_requested) {
        int timeout = -1;
        if (retry_at >= 0) {
            long long now = monotonic_millis();
            long long remaining = retry_at - now;
            timeout = remaining > 0 ? (int) remaining : 0;
        }

        struct pollfd poll_fd;
        poll_fd.fd = netlink_fd;
        poll_fd.events = POLLIN;
        poll_fd.revents = 0;

        int result = poll(&poll_fd, 1, timeout);
        if (stop_requested) {
            break;
        }
        if (result > 0 && (poll_fd.revents & POLLIN) != 0) {
            drain_netlink_socket(netlink_fd);
            sweep_disable_ipv6(options, &state);
            retry_at = monotonic_millis() + EVENT_RETRY_DELAY_MS;
        } else if (result == 0 && retry_at >= 0) {
            sweep_disable_ipv6(options, &state);
            retry_at = -1;
        } else if (result < 0 && errno != EINTR) {
            log_message(options, "poll failed: %s", strerror(errno));
            break;
        }
    }

    log_message(options, "stopping ipv6disabler daemon");
    close(netlink_fd);
    restore_ipv6(options, &state);
    unlink(options->pid_path);
    free_state(&state);
    close_log(options);
    return 0;
}

int main(int argc, char **argv) {
    if (argc < 2) {
        usage(argv[0]);
    }

    Options options;
    memset(&options, 0, sizeof(options));
    if (parse_options(argc, argv, 2, &options) != 0) {
        usage(argv[0]);
    }

    if (strcmp(argv[1], "daemon") == 0) {
        if (options.pid_path == NULL || options.log_path == NULL) {
            usage(argv[0]);
        }
        return run_daemon(&options);
    }

    usage(argv[0]);
}
