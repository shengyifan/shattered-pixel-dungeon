/* Native transport observer. The game and protocol stay in the original JVM. */
#define _DARWIN_C_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <limits.h>
#include <mach-o/dyld.h>
#include <poll.h>
#include <signal.h>
#include <spawn.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

extern char **environ;
#define BUFFER_SIZE 65536
#define TRACE_VERSION "SPDCTL_TRACE\t1\n"
#define TRACE_FAILURE 74

typedef struct {
    char session[PATH_MAX];
    int raw[3], index, owner;
    uint64_t offset[3], sequence;
    bool failed;
} Trace;
typedef struct { unsigned char bytes[BUFFER_SIZE]; size_t begin, end; } Buffer;
static volatile sig_atomic_t interrupted;
static void receive_signal(int value) { interrupted = value; }
static uint64_t now_ns(void) {
    struct timespec value;
    clock_gettime(CLOCK_REALTIME, &value);
    return (uint64_t)value.tv_sec * UINT64_C(1000000000) + (uint64_t)value.tv_nsec;
}
static double monotonic_seconds(void) {
    struct timespec value;
    clock_gettime(CLOCK_MONOTONIC, &value);
    return value.tv_sec + value.tv_nsec / 1000000000.0;
}
static bool join_path(char *out, const char *directory, const char *name) {
    return snprintf(out, PATH_MAX, "%s/%s", directory, name) < PATH_MAX;
}
static int write_all(int fd, const void *data, size_t size) {
    const unsigned char *next = data;
    while (size) {
        ssize_t count = write(fd, next, size);
        if (count > 0) { next += count; size -= (size_t)count; }
        else if (count < 0 && errno == EINTR) continue;
        else return -1;
    }
    return 0;
}
static void diagnostic(const char *message) {
    /* Best effort: a broken diagnostics consumer must never deadlock the game. */
    (void)write_all(STDERR_FILENO, message, strlen(message));
}
static int fail(const char *message, int code) { diagnostic(message); return code; }
static void close_fd(int *fd) { if (*fd >= 0) { close(*fd); *fd = -1; } }
static int cloexec(int fd) { return fcntl(fd, F_SETFD, FD_CLOEXEC); }
static int nonblocking(int fd) {
    int flags = fcntl(fd, F_GETFL);
    return flags < 0 || fcntl(fd, F_SETFL, flags | O_NONBLOCK) < 0 ? -1 : flags;
}

/* Resolve existing symlinks without creating or enumerating the game profile.
 * Missing suffixes are resolved component by component, including dot segments. */
static bool canonical_path(const char *input, char *result) {
    if (!input || input[0] != '/' || strlen(input) >= PATH_MAX) return false;
    char copy[PATH_MAX]; strcpy(copy, input); strcpy(result, "/");
    char *state = NULL;
    for (char *part = strtok_r(copy, "/", &state); part; part = strtok_r(NULL, "/", &state)) {
        if (!strcmp(part, ".")) continue;
        if (!strcmp(part, "..")) {
            char *slash = strrchr(result, '/');
            if (slash == result) result[1] = '\0'; else if (slash) *slash = '\0';
            continue;
        }
        char appended[PATH_MAX], resolved[PATH_MAX];
        if (snprintf(appended, sizeof(appended), "%s%s%s", result,
                     !strcmp(result, "/") ? "" : "/", part) >= PATH_MAX) return false;
        if (realpath(appended, resolved)) strcpy(result, resolved);
        else {
            if (errno != ENOENT) return false;
            struct stat information;
            /* A dangling symlink cannot be treated as a harmless missing suffix. */
            if (lstat(appended, &information) == 0 || errno != ENOENT) return false;
            strcpy(result, appended);
        }
    }
    return true;
}
static bool contains_path(const char *parent, const char *child) {
    size_t size = strlen(parent);
    return !strcmp(parent, "/") || (!strncmp(parent, child, size) && (child[size] == '/' || child[size] == '\0'));
}
static bool overlap(const char *left, const char *right) { return contains_path(left, right) || contains_path(right, left); }
static int make_directories(const char *directory) {
    char path[PATH_MAX]; strcpy(path, directory);
    for (char *cursor = path + 1; ; cursor++) {
        if (*cursor != '/' && *cursor != '\0') continue;
        char old = *cursor; *cursor = '\0';
        if (mkdir(path, 0700) < 0 && errno != EEXIST) return -1;
        struct stat information;
        if (stat(path, &information) < 0 || !S_ISDIR(information.st_mode)) return -1;
        *cursor = old;
        if (!old) return 0;
    }
}
static bool self_path(char *out) {
    char path[PATH_MAX]; uint32_t size = sizeof(path);
    return _NSGetExecutablePath(path, &size) == 0 && realpath(path, out) != NULL;
}
static int create_private(const char *directory, const char *name) {
    char path[PATH_MAX];
    if (!join_path(path, directory, name)) { errno = ENAMETOOLONG; return -1; }
    return open(path, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW, 0600);
}
static void trace_error(Trace *trace) {
    if (trace->failed) return;
    trace->failed = true;
    diagnostic("spdctl: TRACE_IO_FAILED (recording incomplete; stopping new requests)\n");
    /* A raw stream can fail while the index is still writable. Never recurse if
     * this best-effort error event also fails (e.g. disk exhaustion). */
    if (trace->index >= 0) {
        char line[256];
        int count = snprintf(line, sizeof(line), "%" PRIu64 "\t%" PRIu64 "\tSTATUS\t0\t0\tTRACE_IO_FAILED\n",
                             ++trace->sequence, now_ns());
        if (count > 0 && count < (int)sizeof(line)) (void)write_all(trace->index, line, (size_t)count);
    }
}
static bool event(Trace *trace, const char *kind, uint64_t offset, uint64_t length, const char *detail) {
    if (trace->failed) return false;
    char line[512];
    int count = snprintf(line, sizeof(line), "%" PRIu64 "\t%" PRIu64 "\t%s\t%" PRIu64 "\t%" PRIu64 "\t%s\n",
                         ++trace->sequence, now_ns(), kind, offset, length, detail ? detail : "-");
    if (count < 0 || count >= (int)sizeof(line) || write_all(trace->index, line, (size_t)count) < 0) {
        trace_error(trace); return false;
    }
    return true;
}
static bool raw_event(Trace *trace, int stream, const void *data, size_t size) {
    static const char *kinds[] = {"SEND", "RECV", "STDERR"};
    if (trace->failed) return false;
    if (write_all(trace->raw[stream], data, size) < 0) { trace_error(trace); return false; }
    uint64_t offset = trace->offset[stream]; trace->offset[stream] += size;
    return event(trace, kinds[stream], offset, size, "-");
}
static bool shell_word(int fd, const char *text) {
    if (write_all(fd, "'", 1) < 0) return false;
    const char *start = text;
    while (*text) {
        if (*text == '\'') {
            if (write_all(fd, start, (size_t)(text - start)) < 0 || write_all(fd, "'\\''", 4) < 0) return false;
            start = text + 1;
        }
        text++;
    }
    return write_all(fd, start, (size_t)(text - start)) == 0 && write_all(fd, "'", 1) == 0;
}
static bool create_viewer_command(const char *session) {
    char executable[PATH_MAX];
    if (!self_path(executable)) return false;
    int fd = create_private(session, "open-viewer.command");
    if (fd < 0) return false;
    static const char header[] = "#!/bin/sh\nexec ";
    static const char arguments[] = " trace view --session ";
    bool ok = write_all(fd, header, sizeof(header) - 1) == 0 && shell_word(fd, executable)
        && write_all(fd, arguments, sizeof(arguments) - 1) == 0 && shell_word(fd, session)
        && write_all(fd, "\n", 1) == 0 && fchmod(fd, 0700) == 0;
    if (close(fd) != 0) ok = false;
    return ok;
}
static void remove_temporary_command(const char *directory, const char *command) {
    unlink(command);
    rmdir(directory);
}
static bool temporary_viewer_command(const char *session, char *directory, char *command) {
    strcpy(directory, "/tmp/spdctl-trace-view-XXXXXX");
    if (!mkdtemp(directory) || !join_path(command, directory, "viewer.command")) return false;
    int fd = create_private(directory, "viewer.command");
    if (fd < 0) { rmdir(directory); return false; }
    char executable[PATH_MAX];
    static const char first[] = "#!/bin/sh\n/bin/rm -f -- ";
    static const char second[] = "\n/bin/rmdir -- ";
    static const char third[] = "\nexec ";
    static const char arguments[] = " trace view --session ";
    bool ok = self_path(executable) && write_all(fd, first, sizeof(first) - 1) == 0
        && shell_word(fd, command) && write_all(fd, second, sizeof(second) - 1) == 0
        && shell_word(fd, directory) && write_all(fd, third, sizeof(third) - 1) == 0
        && shell_word(fd, executable) && write_all(fd, arguments, sizeof(arguments) - 1) == 0
        && shell_word(fd, session) && write_all(fd, "\n", 1) == 0 && fchmod(fd, 0700) == 0;
    if (close(fd) != 0) ok = false;
    if (!ok) remove_temporary_command(directory, command);
    return ok;
}
static int open_terminal(const char *session) {
    char directory[PATH_MAX], command[PATH_MAX];
    /* Never execute code read from a trace directory. Reopening after a build
     * replacement always uses this invocation's trusted native executable. */
    if (!temporary_viewer_command(session, directory, command)) return -1;
    /* posix_spawn actions ensure Terminal can never inherit a game pipe. */
    posix_spawn_file_actions_t actions;
    if (posix_spawn_file_actions_init(&actions) != 0) { remove_temporary_command(directory, command); return -1; }
    for (int fd = 0; fd <= 2; fd++) posix_spawn_file_actions_addopen(&actions, fd, "/dev/null", O_RDWR, 0);
    char *arguments[] = {"/usr/bin/open", "-a", "Terminal", command, NULL};
    pid_t child;
    posix_spawnattr_t attributes;
    int attribute_error = posix_spawnattr_init(&attributes);
    if (attribute_error) {
        posix_spawn_file_actions_destroy(&actions); remove_temporary_command(directory, command);
        errno = attribute_error; return -1;
    }
    posix_spawnattr_setflags(&attributes, POSIX_SPAWN_CLOEXEC_DEFAULT);
    int error = posix_spawn(&child, arguments[0], &actions, &attributes, arguments, environ);
    posix_spawnattr_destroy(&attributes);
    posix_spawn_file_actions_destroy(&actions);
    if (error) { errno = error; remove_temporary_command(directory, command); return -1; }
    int status;
    double deadline = monotonic_seconds() + 3;
    for (;;) {
        pid_t result = waitpid(child, &status, WNOHANG);
        if (result == child) {
            bool success = WIFEXITED(status) && WEXITSTATUS(status) == 0;
            if (!success) remove_temporary_command(directory, command);
            return success ? 0 : -1;
        }
        if (result < 0 && errno != EINTR) { remove_temporary_command(directory, command); return -1; }
        if (interrupted || monotonic_seconds() > deadline) {
            kill(child, SIGKILL);
            while (waitpid(child, &status, 0) < 0 && errno == EINTR) {}
            remove_temporary_command(directory, command);
            return -1;
        }
        struct timespec pause = {0, 10000000}; nanosleep(&pause, NULL);
    }
}
static void terminal_warning(const char *session) {
    diagnostic("spdctl: TERMINAL_OPEN_FAILED (recording continues; reopen with spdctl trace open --session PATH)\n");
    diagnostic("spdctl: TRACE_SESSION "); diagnostic(session); diagnostic("\n");
}
static bool trace_create(Trace *trace, const char *root, const char *profile) {
    memset(trace, 0, sizeof(*trace)); trace->index = trace->owner = -1;
    for (int i = 0; i < 3; i++) trace->raw[i] = -1;
    char resolved[PATH_MAX];
    if (!canonical_path(root, resolved) || overlap(resolved, profile)) {
        diagnostic("spdctl: TRACE_PROFILE_OVERLAP_OR_INVALID_PATH\n"); return false;
    }
    if (make_directories(resolved) < 0) { trace_error(trace); return false; }
    char verified[PATH_MAX];
    if (!realpath(resolved, verified) || overlap(verified, profile)) {
        diagnostic("spdctl: TRACE_PROFILE_OVERLAP_OR_INVALID_PATH\n"); return false;
    }
    if (snprintf(trace->session, sizeof(trace->session), "%s/session-%llu-%ld-XXXXXX", verified,
                 (unsigned long long)now_ns(), (long)getpid()) >= PATH_MAX || !mkdtemp(trace->session)) {
        trace_error(trace); return false;
    }
    trace->owner = create_private(trace->session, ".incomplete");
    if (trace->owner < 0 || flock(trace->owner, LOCK_EX | LOCK_NB) < 0) { trace_error(trace); return false; }
    char owner[96]; int owner_size = snprintf(owner, sizeof(owner), "transport_version=1\nrecorder_pid=%ld\n", (long)getpid());
    bool ok = write_all(trace->owner, owner, (size_t)owner_size) == 0;
    static const char *names[] = {"send.raw", "recv.raw", "stderr.raw"};
    for (int i = 0; i < 3 && ok; i++) { trace->raw[i] = create_private(trace->session, names[i]); ok = trace->raw[i] >= 0; }
    if (ok) { trace->index = create_private(trace->session, "events.tsv"); ok = trace->index >= 0; }
    if (ok) ok = write_all(trace->index, TRACE_VERSION, strlen(TRACE_VERSION)) == 0;
    if (ok) ok = create_viewer_command(trace->session);
    if (!ok) { trace_error(trace); return false; }
    return event(trace, "STATUS", 0, 0, "STARTED");
}
static bool trace_finish(Trace *trace) {
    if (!trace->failed) {
        for (int i = 0; i < 3; i++) if (trace->raw[i] >= 0 && fsync(trace->raw[i]) < 0) trace_error(trace);
        if (trace->index >= 0 && fsync(trace->index) < 0) trace_error(trace);
    }
    for (int i = 0; i < 3; i++) if (trace->raw[i] >= 0) {
        if (close(trace->raw[i]) < 0) trace_error(trace);
        trace->raw[i] = -1;
    }
    if (trace->index >= 0) { if (close(trace->index) < 0) trace_error(trace); trace->index = -1; }
    if (!trace->failed && trace->session[0]) {
        char marker[PATH_MAX];
        if (!join_path(marker, trace->session, ".incomplete") || unlink(marker) < 0) trace_error(trace);
    }
    close_fd(&trace->owner);
    return !trace->failed;
}

/* Safe presentation of exact raw bytes: never execute terminal escape sequences. */
static bool safe_text(const unsigned char *data, size_t size) {
    for (size_t i = 0; i < size; ) {
        unsigned char byte = data[i];
        if (byte == '\n' || byte == '\t' || (byte >= 0x20 && byte <= 0x7e)) {
            if (fputc(byte, stdout) == EOF) return false;
            i++; continue;
        }
        size_t count = byte >= 0xc2 && byte <= 0xdf ? 2 : byte >= 0xe0 && byte <= 0xef ? 3 : byte >= 0xf0 && byte <= 0xf4 ? 4 : 0;
        uint32_t codepoint = count ? byte & ((1u << (7 - count)) - 1) : 0;
        bool valid = count && i + count <= size;
        for (size_t j = 1; valid && j < count; j++) {
            if ((data[i + j] & 0xc0) != 0x80) valid = false;
            else codepoint = (codepoint << 6) | (data[i + j] & 0x3f);
        }
        if (valid && ((count == 2 && codepoint < 0x80) || (count == 3 && codepoint < 0x800)
            || (count == 4 && codepoint < 0x10000) || codepoint > 0x10ffff || (codepoint >= 0xd800 && codepoint <= 0xdfff))) valid = false;
        if (valid && !(codepoint >= 0x80 && codepoint <= 0x9f) && !(codepoint >= 0x202a && codepoint <= 0x202e)
            && !(codepoint >= 0x2066 && codepoint <= 0x2069) && codepoint != 0x2028 && codepoint != 0x2029) {
            if (fwrite(data + i, 1, count, stdout) != count) return false;
            i += count;
        } else {
            if (fprintf(stdout, "\\x%02X", byte) < 0) return false;
            i++;
        }
    }
    return true;
}
static int read_session_file(int directory, const char *name) {
    int fd = openat(directory, name, O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK);
    struct stat information;
    if (fd >= 0 && (fstat(fd, &information) < 0 || !S_ISREG(information.st_mode))) {
        close_fd(&fd); errno = EINVAL;
    }
    return fd;
}
typedef struct { unsigned char bytes[4]; size_t count; } TextTail;
static bool read_range(int fd, uint64_t offset, uint64_t size, TextTail *tail, bool *ends_newline) {
    unsigned char data[BUFFER_SIZE + 4];
    *ends_newline = false;
    while (size) {
        memcpy(data, tail->bytes, tail->count);
        size_t count = size > BUFFER_SIZE ? BUFFER_SIZE : (size_t)size;
        ssize_t read_count;
        do { read_count = pread(fd, data + tail->count, count, (off_t)offset); } while (read_count < 0 && errno == EINTR);
        if (read_count <= 0) return false;
        offset += (uint64_t)read_count; size -= (uint64_t)read_count;
        size_t available = tail->count + (size_t)read_count, display = available;
        /* A UTF-8 codepoint can cross OS reads and trace events. The at-most-three
         * byte tail belongs to this direction, not to a particular event. */
        for (size_t back = 1; back <= 3 && back <= available; back++) {
            unsigned char byte = data[available - back];
            if (byte >= 0xc2 && byte <= 0xf4) {
                size_t wanted = byte <= 0xdf ? 2 : byte <= 0xef ? 3 : 4;
                if (wanted > back) display -= back;
                break;
            }
            if ((byte & 0xc0) != 0x80) break;
        }
        if (!safe_text(data, display)) return false;
        if (display) *ends_newline = data[display - 1] == '\n';
        tail->count = available - display;
        if (tail->count) memcpy(tail->bytes, data + display, tail->count);
    }
    return true;
}
static bool flush_text_tails(TextTail *tails) {
    static const char *names[] = {"SEND", "RECV", "STDERR"};
    for (int i = 0; i < 3; i++) if (tails[i].count) {
        if (printf("\n%s incomplete UTF-8 tail: ", names[i]) < 0
            || !safe_text(tails[i].bytes, tails[i].count) || fputc('\n', stdout) == EOF) return false;
        tails[i].count = 0;
    }
    return true;
}
static int view_session(const char *session) {
    int directory = open(session, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
    if (directory < 0) return fail("spdctl: TRACE_SESSION_UNAVAILABLE\n", 66);
    int index = read_session_file(directory, "events.tsv"), raw[3];
    const char *names[] = {"send.raw", "recv.raw", "stderr.raw"};
    for (int i = 0; i < 3; i++) raw[i] = read_session_file(directory, names[i]);
    FILE *input = index >= 0 ? fdopen(index, "r") : NULL;
    int result = 0;
    char line[1024];
    if (!input || raw[0] < 0 || raw[1] < 0 || raw[2] < 0 || !fgets(line, sizeof(line), input) || strcmp(line, TRACE_VERSION)) {
        result = fail("spdctl: TRACE_FORMAT_UNSUPPORTED_OR_INCOMPLETE\n", 65); goto done;
    }
    if (printf("spdctl transport viewer — SEND: controller → CLI; RECV: CLI → recorder\n"
               "Raw bytes remain in the session files. DELIVERED means written to the controller pipe.\n") < 0) { result = 1; goto done; }
    if (fputs("Session: ", stdout) == EOF || !safe_text((const unsigned char *)session, strlen(session))
        || fputc('\n', stdout) == EOF || fflush(stdout) != 0) { result = 1; goto done; }
    uint64_t expected = 1;
    bool finished = false;
    TextTail tails[3] = {{{0}, 0}, {{0}, 0}, {{0}, 0}};
    for (;;) {
        off_t position = ftello(input);
        if (fgets(line, sizeof(line), input)) {
            if (!strchr(line, '\n')) {
                if (!feof(input)) { result = fail("spdctl: TRACE_INDEX_INVALID\n", 65); break; }
                clearerr(input); fseeko(input, position, SEEK_SET);
            } else {
                uint64_t sequence, timestamp, offset, length;
                char kind[32], detail[128], extra;
                if (sscanf(line, "%" SCNu64 "\t%" SCNu64 "\t%31[^\t]\t%" SCNu64 "\t%" SCNu64 "\t%127[^\n]%c",
                           &sequence, &timestamp, kind, &offset, &length, detail, &extra) != 7 || extra != '\n'
                    || sequence != expected++ || offset > INT64_MAX || length > INT64_MAX - offset) {
                    result = fail("spdctl: TRACE_INDEX_INVALID\n", 65); break;
                }
                int stream = !strcmp(kind, "SEND") ? 0 : !strcmp(kind, "RECV") ? 1 : !strcmp(kind, "STDERR") ? 2 : -1;
                if (printf("\n[%" PRIu64 ".%09" PRIu64 "] #%" PRIu64 " ", timestamp / UINT64_C(1000000000), timestamp % UINT64_C(1000000000), sequence) < 0
                    || !safe_text((unsigned char *)kind, strlen(kind))) { result = 1; break; }
                if (stream >= 0) {
                    bool ends_newline;
                    if (printf(" (%" PRIu64 " bytes)\n", length) < 0 || !read_range(raw[stream], offset, length, &tails[stream], &ends_newline)
                        || (!ends_newline && fputc('\n', stdout) == EOF)) { result = fail("spdctl: TRACE_DATA_UNAVAILABLE\n", 65); break; }
                } else {
                    if (strcmp(kind, "STATUS") && strcmp(kind, "DELIVERED")) { result = fail("spdctl: TRACE_INDEX_INVALID\n", 65); break; }
                    if (printf(" offset=%" PRIu64 " bytes=%" PRIu64 " ", offset, length) < 0
                        || !safe_text((unsigned char *)detail, strlen(detail)) || fputc('\n', stdout) == EOF) { result = 1; break; }
                }
                if (fflush(stdout) != 0) { result = 1; break; }
                continue;
            }
        } else if (ferror(input)) { result = fail("spdctl: TRACE_INDEX_READ_FAILED\n", 65); break; }
        clearerr(input);
        if (interrupted) break;
        int marker = read_session_file(directory, ".incomplete");
        if (marker < 0) {
            if (errno != ENOENT) { result = fail("spdctl: TRACE_OWNER_STATUS_UNAVAILABLE\n", 65); break; }
            if (!flush_text_tails(tails)) result = 1;
            puts("\nSession ended. This viewer can be reopened at any time."); fflush(stdout); finished = true; break;
        }
        /* The writer's CLOEXEC lock lasts for its lifetime, unlike a PID which
         * can be recycled. Viewers acquire only a nonblocking shared read lock. */
        int locked = flock(marker, LOCK_SH | LOCK_NB);
        int lock_error = errno;
        close(marker);
        if (locked == 0) {
            if (!flush_text_tails(tails)) result = 1;
            puts("\nRecording incomplete: recorder exited without finalizing the trace."); fflush(stdout); finished = true; break;
        }
        if (lock_error != EWOULDBLOCK && lock_error != EAGAIN) {
            result = fail("spdctl: TRACE_OWNER_STATUS_UNAVAILABLE\n", 65); break;
        }
        struct timespec pause = {0, 100000000}; nanosleep(&pause, NULL);
    }
    if (finished && !interrupted && result == 0 && isatty(STDIN_FILENO) && isatty(STDOUT_FILENO)) {
        fputs("\nPress Enter to close this viewer. The recording remains available.\n", stdout); fflush(stdout);
        while (!interrupted) {
            int key = getchar();
            if (key == '\n' || key == EOF) break;
        }
    }
done:
    if (input) fclose(input); else close_fd(&index);
    for (int i = 0; i < 3; i++) close_fd(&raw[i]);
    close(directory);
    return result;
}
static bool make_pipe(int descriptors[2]) {
    if (pipe(descriptors) < 0) return false;
    if (cloexec(descriptors[0]) < 0 || cloexec(descriptors[1]) < 0) {
        close(descriptors[0]); close(descriptors[1]); descriptors[0] = descriptors[1] = -1; return false;
    }
    return true;
}
static pid_t launch_engine(char **arguments, int *input, int *output, int *errors) {
    int pipes[3][2];
    for (int i = 0; i < 3; i++) pipes[i][0] = pipes[i][1] = -1;
    for (int i = 0; i < 3; i++) if (!make_pipe(pipes[i])) goto failure;
    posix_spawn_file_actions_t actions;
    if (posix_spawn_file_actions_init(&actions) != 0) goto failure;
    posix_spawn_file_actions_adddup2(&actions, pipes[0][0], STDIN_FILENO);
    posix_spawn_file_actions_adddup2(&actions, pipes[1][1], STDOUT_FILENO);
    posix_spawn_file_actions_adddup2(&actions, pipes[2][1], STDERR_FILENO);
    for (int i = 0; i < 3; i++) for (int j = 0; j < 2; j++) posix_spawn_file_actions_addclose(&actions, pipes[i][j]);
    posix_spawnattr_t attributes;
    if (posix_spawnattr_init(&attributes) != 0) { posix_spawn_file_actions_destroy(&actions); goto failure; }
    sigset_t defaults, empty_mask;
    sigemptyset(&defaults); sigaddset(&defaults, SIGPIPE); sigaddset(&defaults, SIGINT); sigaddset(&defaults, SIGTERM); sigaddset(&defaults, SIGXFSZ); sigaddset(&defaults, SIGHUP);
    sigemptyset(&empty_mask);
    posix_spawnattr_setsigdefault(&attributes, &defaults);
    posix_spawnattr_setsigmask(&attributes, &empty_mask);
    posix_spawnattr_setflags(&attributes, POSIX_SPAWN_SETSIGDEF | POSIX_SPAWN_SETSIGMASK | POSIX_SPAWN_CLOEXEC_DEFAULT);
    pid_t child;
    int error = posix_spawn(&child, arguments[0], &actions, &attributes, arguments, environ);
    posix_spawnattr_destroy(&attributes); posix_spawn_file_actions_destroy(&actions);
    if (error) { errno = error; goto failure; }
    close(pipes[0][0]); close(pipes[1][1]); close(pipes[2][1]);
    *input = pipes[0][1]; *output = pipes[1][0]; *errors = pipes[2][0];
    return child;
failure:
    for (int i = 0; i < 3; i++) for (int j = 0; j < 2; j++) close_fd(&pipes[i][j]);
    return -1;
}
static bool empty(Buffer *buffer) { return buffer->begin == buffer->end; }
static void reset_if_empty(Buffer *buffer) { if (empty(buffer)) buffer->begin = buffer->end = 0; }
static bool ready(struct pollfd *fds, int slot) { return fds[slot].revents & (POLLIN | POLLOUT | POLLHUP | POLLERR | POLLNVAL); }
static int relay(char **arguments, Trace *trace, bool terminal) {
    int child_in = -1, child_out = -1, child_err = -1;
    pid_t child = launch_engine(arguments, &child_in, &child_out, &child_err);
    if (child < 0) {
        event(trace, "STATUS", 0, 0, "LAUNCH_FAILED"); trace_finish(trace);
        return fail("spdctl: BOOTSTRAP_EXEC_FAILED\n", 1);
    }
    diagnostic("spdctl: TRACE_SESSION "); diagnostic(trace->session); diagnostic("\n");
    if (terminal && open_terminal(trace->session) != 0) terminal_warning(trace->session);
    int original[3] = {-1, -1, -1};
    bool setup_failed = false;
    for (int i = 0; i < 3; i++) { original[i] = nonblocking(i); if (original[i] < 0) setup_failed = true; }
    if (nonblocking(child_in) < 0 || nonblocking(child_out) < 0 || nonblocking(child_err) < 0) setup_failed = true;
    Buffer incoming = {{0}, 0, 0}, outgoing = {{0}, 0, 0}, diagnostics = {{0}, 0, 0};
    bool input_eof = setup_failed, stopping = setup_failed, output_broken = false, error_broken = false, reaped = false;
    bool term_sent = false, kill_sent = false, transport_failed = setup_failed;
    int child_status = 0;
    uint64_t output_offset = 0;
    double stop_started = 0, exit_seen = 0;
    if (setup_failed) diagnostic("spdctl: TRANSPORT_SETUP_FAILED\n");
    for (;;) {
        if (interrupted && !stopping) {
            char detail[48]; snprintf(detail, sizeof(detail), "SIGNAL:%d", interrupted);
            event(trace, "STATUS", 0, 0, detail);
            if (!reaped) kill(child, interrupted);
            stopping = true; transport_failed = true;
        }
        if (trace->failed) { stopping = true; transport_failed = true; }
        if (stopping) { input_eof = true; incoming.begin = incoming.end = 0; }
        if (input_eof && empty(&incoming) && child_in >= 0) {
            close_fd(&child_in); event(trace, "STATUS", 0, 0, "EOF_SENT");
            stop_started = monotonic_seconds();
        }
        if (!reaped) {
            pid_t result = waitpid(child, &child_status, WNOHANG);
            if (result == child || (result < 0 && errno == ECHILD)) {
                reaped = true; exit_seen = monotonic_seconds(); close_fd(&child_in);
            }
        }
        if (reaped && child_out < 0 && child_err < 0 && empty(&outgoing) && empty(&diagnostics)) break;
        double current = monotonic_seconds();
        if (!reaped && stop_started && current - stop_started > 30 && !term_sent) {
            kill(child, SIGTERM); term_sent = true; transport_failed = true; event(trace, "STATUS", 0, 0, "SHUTDOWN_TIMEOUT");
        }
        if (!reaped && stop_started && current - stop_started > 35 && !kill_sent) {
            kill(child, SIGKILL); kill_sent = true; event(trace, "STATUS", 0, 0, "FORCED_TERMINATION");
        }
        if (reaped && (stopping || transport_failed) && current - exit_seen > 5) {
            if (child_out >= 0 || child_err >= 0 || !empty(&outgoing) || !empty(&diagnostics)) {
                transport_failed = true; event(trace, "STATUS", 0, 0, "DRAIN_TIMEOUT");
                diagnostic("spdctl: TRANSPORT_DRAIN_FAILED (recording or delivery may be incomplete)\n");
                trace->failed = true;
            }
            close_fd(&child_out); close_fd(&child_err); break;
        }
        struct pollfd fds[6] = {
            { !input_eof && !reaped && child_in >= 0 && empty(&incoming) ? STDIN_FILENO : -1, POLLIN, 0 },
            { !empty(&incoming) ? child_in : -1, POLLOUT, 0 },
            { empty(&outgoing) ? child_out : -1, POLLIN, 0 },
            { !empty(&outgoing) && !output_broken ? STDOUT_FILENO : -1, POLLOUT, 0 },
            { empty(&diagnostics) ? child_err : -1, POLLIN, 0 },
            { !empty(&diagnostics) && !error_broken ? STDERR_FILENO : -1, POLLOUT, 0 }
        };
        int polled = poll(fds, 6, 100);
        if (polled < 0) { if (errno == EINTR) continue; stopping = true; transport_failed = true; continue; }
        if (ready(fds, 0)) {
            ssize_t count = read(STDIN_FILENO, incoming.bytes, sizeof(incoming.bytes));
            if (count > 0) incoming.end = (size_t)count;
            else if (count == 0) input_eof = true;
            else if (errno != EINTR && errno != EAGAIN) { input_eof = true; transport_failed = true; event(trace, "STATUS", 0, 0, "STDIN_READ_FAILED"); }
        }
        if (ready(fds, 1)) {
            ssize_t count = write(child_in, incoming.bytes + incoming.begin, incoming.end - incoming.begin);
            if (count > 0) { raw_event(trace, 0, incoming.bytes + incoming.begin, (size_t)count); incoming.begin += (size_t)count; reset_if_empty(&incoming); }
            else if (count < 0 && errno != EINTR && errno != EAGAIN) {
                close_fd(&child_in); stopping = true; transport_failed = true; stop_started = monotonic_seconds();
                event(trace, "STATUS", 0, 0, "CHILD_STDIN_BROKEN");
            }
        }
        if (ready(fds, 2)) {
            ssize_t count = read(child_out, outgoing.bytes, sizeof(outgoing.bytes));
            if (count > 0) {
                output_offset = trace->offset[1]; raw_event(trace, 1, outgoing.bytes, (size_t)count);
                if (!output_broken) outgoing.end = (size_t)count;
            } else if (count == 0) close_fd(&child_out);
            else if (errno != EINTR && errno != EAGAIN) { close_fd(&child_out); stopping = true; transport_failed = true; event(trace, "STATUS", 0, 0, "CHILD_STDOUT_READ_FAILED"); }
        }
        if (ready(fds, 3)) {
            ssize_t count = write(STDOUT_FILENO, outgoing.bytes + outgoing.begin, outgoing.end - outgoing.begin);
            if (count > 0) { event(trace, "DELIVERED", output_offset + outgoing.begin, (uint64_t)count, "-"); outgoing.begin += (size_t)count; reset_if_empty(&outgoing); }
            else if (count < 0 && errno != EINTR && errno != EAGAIN) {
                output_broken = true; stopping = true; transport_failed = true; outgoing.begin = outgoing.end = 0;
                event(trace, "STATUS", 0, 0, "STDOUT_BROKEN");
            }
        }
        if (ready(fds, 4)) {
            ssize_t count = read(child_err, diagnostics.bytes, sizeof(diagnostics.bytes));
            if (count > 0) { raw_event(trace, 2, diagnostics.bytes, (size_t)count); if (!error_broken) diagnostics.end = (size_t)count; }
            else if (count == 0) close_fd(&child_err);
            else if (errno != EINTR && errno != EAGAIN) { close_fd(&child_err); transport_failed = true; event(trace, "STATUS", 0, 0, "CHILD_STDERR_READ_FAILED"); }
        }
        if (ready(fds, 5)) {
            ssize_t count = write(STDERR_FILENO, diagnostics.bytes + diagnostics.begin, diagnostics.end - diagnostics.begin);
            if (count > 0) { diagnostics.begin += (size_t)count; reset_if_empty(&diagnostics); }
            else if (count < 0 && errno != EINTR && errno != EAGAIN) {
                error_broken = true; diagnostics.begin = diagnostics.end = 0; event(trace, "STATUS", 0, 0, "STDERR_BROKEN");
            }
        }
    }
    close_fd(&child_in); close_fd(&child_out); close_fd(&child_err);
    if (!reaped) { kill(child, SIGKILL); while (waitpid(child, &child_status, 0) < 0 && errno == EINTR) {} }
    int result = WIFEXITED(child_status) ? WEXITSTATUS(child_status) : WIFSIGNALED(child_status) ? 128 + WTERMSIG(child_status) : 1;
    char detail[48]; snprintf(detail, sizeof(detail), "EXITED:%d", result); event(trace, "STATUS", 0, 0, detail);
    bool complete = trace_finish(trace);
    for (int i = 0; i < 3; i++) if (original[i] >= 0) fcntl(i, F_SETFL, original[i]);
    return !complete ? TRACE_FAILURE : transport_failed ? (result ? result : 1) : result;
}

int main(int argc, char **argv) {
    struct sigaction action; memset(&action, 0, sizeof(action)); action.sa_handler = receive_signal; sigemptyset(&action.sa_mask);
    sigaction(SIGINT, &action, NULL); sigaction(SIGTERM, &action, NULL); sigaction(SIGHUP, &action, NULL); signal(SIGPIPE, SIG_IGN); signal(SIGXFSZ, SIG_IGN);
    int first = 1, engine_count = 1;
    char executable[PATH_MAX], *default_engine[1];
    char **engine = default_engine;
    if (argc > 1 && !strcmp(argv[1], "--engine-argc")) {
        if (argc < 5) return fail("spdctl: INVALID_ENGINE_ARGUMENTS\n", 64);
        char *end = NULL; long count = strtol(argv[2], &end, 10);
        if (!end || *end || count < 1 || count > argc - 4 || strcmp(argv[count + 3], "--") || argv[3][0] != '/')
            return fail("spdctl: INVALID_ENGINE_ARGUMENTS\n", 64);
        engine_count = (int)count; engine = argv + 3; first = engine_count + 4;
    } else {
        if (!self_path(executable)) return fail("spdctl: EXECUTABLE_UNAVAILABLE\n", 1);
        char *name = strrchr(executable, '/');
        if (!name || (size_t)(name - executable) + sizeof("/spdctl-jvm") > sizeof(executable)) return 1;
        strcpy(name, "/spdctl-jvm"); default_engine[0] = executable;
    }
    if (argc > first && !strcmp(argv[first], "trace")) {
        if (argc != first + 4 || (strcmp(argv[first + 1], "view") && strcmp(argv[first + 1], "open"))
            || strcmp(argv[first + 2], "--session") || argv[first + 3][0] != '/')
            return fail("spdctl: Expected trace view|open --session ABS\n", 64);
        char session[PATH_MAX];
        if (!realpath(argv[first + 3], session)) return fail("spdctl: TRACE_SESSION_UNAVAILABLE\n", 66);
        if (!strcmp(argv[first + 1], "view")) return view_session(session);
        int directory = open(session, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
        int index = directory >= 0 ? read_session_file(directory, "events.tsv") : -1;
        char header[sizeof(TRACE_VERSION)] = {0};
        ssize_t read_count = index >= 0 ? read(index, header, sizeof(TRACE_VERSION) - 1) : -1;
        close_fd(&index); close_fd(&directory);
        if (read_count != (ssize_t)sizeof(TRACE_VERSION) - 1 || strcmp(header, TRACE_VERSION))
            return fail("spdctl: TRACE_FORMAT_UNSUPPORTED_OR_INCOMPLETE\n", 65);
        if (open_terminal(session) != 0) { terminal_warning(session); return 1; }
        return 0;
    }
    char **forward = calloc((size_t)engine_count + (size_t)(argc - first) + 1, sizeof(char *));
    if (!forward) return fail("spdctl: ALLOCATION_FAILED\n", 1);
    for (int i = 0; i < engine_count; i++) forward[i] = engine[i];
    int forward_count = engine_count;
    bool run = argc > first && !strcmp(argv[first], "run"), machine = false, terminal = true;
    const char *home = getenv("HOME"), *selected = getenv("SPDCTL_PROFILE"), *trace_root = NULL;
    char default_profile[PATH_MAX], default_trace[PATH_MAX], profile[PATH_MAX];
    if (!home || home[0] != '/' || snprintf(default_profile, sizeof(default_profile), "%s/Library/Application Support/Shattered Pixel Dungeon CLI v2", home) >= PATH_MAX
        || snprintf(default_trace, sizeof(default_trace), "%s/Library/Logs/Shattered Pixel Dungeon CLI/transport", home) >= PATH_MAX)
        return fail("spdctl: HOME_UNAVAILABLE\n", 64);
    if (!selected) selected = default_profile;
    for (int i = first; i < argc; i++) {
        if (run && !strcmp(argv[i], "--no-terminal")) terminal = false;
        else if (run && !strcmp(argv[i], "--trace-dir")) {
            if (++i >= argc || argv[i][0] != '/') return fail("spdctl: ABSOLUTE_TRACE_DIRECTORY_REQUIRED\n", 64);
            trace_root = argv[i];
        } else {
            forward[forward_count++] = argv[i];
            if (run && !strcmp(argv[i], "--machine")) machine = true;
            else if (run && !strcmp(argv[i], "--data-dir")) {
                if (++i >= argc || argv[i][0] != '/') return fail("spdctl: ABSOLUTE_PROFILE_REQUIRED\n", 64);
                selected = argv[i]; forward[forward_count++] = argv[i];
            } else if (run && i != first) return fail("spdctl: UNKNOWN_LAUNCHER_ARGUMENT\n", 64);
        }
    }
    if (run && !machine) return fail("spdctl: MACHINE_MODE_REQUIRED\n", 64);
    if (run && (!canonical_path(selected, profile) || setenv("SPDCTL_PROFILE", profile, 1) != 0))
        return fail("spdctl: ABSOLUTE_PROFILE_REQUIRED\n", 64);
    if (!run) {
        execv(forward[0], forward); free(forward);
        return fail("spdctl: BOOTSTRAP_EXEC_FAILED\n", 1);
    }
    Trace trace;
    if (!trace_create(&trace, trace_root ? trace_root : default_trace, profile)) {
        trace.failed = true; trace_finish(&trace); free(forward); return TRACE_FAILURE;
    }
    int result = relay(forward, &trace, terminal); free(forward); return result;
}
