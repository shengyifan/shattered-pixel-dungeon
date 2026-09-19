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
typedef enum { STREAM_ALL, STREAM_SEND, STREAM_RECV } StreamSelection;
static const char *stream_name(StreamSelection stream) {
    return stream == STREAM_SEND ? "send" : stream == STREAM_RECV ? "recv" : "all";
}
static bool selected_stream(StreamSelection selection, int stream) {
    return selection == STREAM_ALL || (selection == STREAM_SEND ? stream == 0 : stream != 0);
}
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
static bool create_viewer_command(const char *session, StreamSelection stream) {
    char executable[PATH_MAX];
    if (!self_path(executable)) return false;
    int fd = create_private(session, stream == STREAM_SEND ? "open-send.command" : "open-recv.command");
    if (fd < 0) return false;
    static const char header[] = "#!/bin/sh\nexec ";
    /* Dedicated Terminal windows request ANSI explicitly; controller auto-color settings are unrelated. */
    static const char arguments[] = " trace view --color always --stream ";
    bool ok = write_all(fd, header, sizeof(header) - 1) == 0 && shell_word(fd, executable)
        && write_all(fd, arguments, sizeof(arguments) - 1) == 0 && shell_word(fd, stream_name(stream))
        && write_all(fd, " --session ", 11) == 0 && shell_word(fd, session)
        && write_all(fd, "\n", 1) == 0 && fchmod(fd, 0700) == 0;
    if (close(fd) != 0) ok = false;
    return ok;
}
static void remove_temporary_command(const char *directory, const char *command) {
    unlink(command);
    rmdir(directory);
}
static bool temporary_viewer_command(const char *session, StreamSelection stream, char *directory, char *command) {
    strcpy(directory, "/tmp/spdctl-trace-view-XXXXXX");
    if (!mkdtemp(directory) || !join_path(command, directory, "viewer.command")) return false;
    int fd = create_private(directory, "viewer.command");
    if (fd < 0) { rmdir(directory); return false; }
    char executable[PATH_MAX];
    static const char first[] = "#!/bin/sh\n/bin/rm -f -- ";
    static const char second[] = "\n/bin/rmdir -- ";
    static const char third[] = "\nexec ";
    /* Keep automatic opening and trace open consistent with the saved Terminal command. */
    static const char arguments[] = " trace view --color always --stream ";
    bool ok = self_path(executable) && write_all(fd, first, sizeof(first) - 1) == 0
        && shell_word(fd, command) && write_all(fd, second, sizeof(second) - 1) == 0
        && shell_word(fd, directory) && write_all(fd, third, sizeof(third) - 1) == 0
        && shell_word(fd, executable) && write_all(fd, arguments, sizeof(arguments) - 1) == 0
        && shell_word(fd, stream_name(stream)) && write_all(fd, " --session ", 11) == 0
        && shell_word(fd, session) && write_all(fd, "\n", 1) == 0 && fchmod(fd, 0700) == 0;
    if (close(fd) != 0) ok = false;
    if (!ok) remove_temporary_command(directory, command);
    return ok;
}
/* The source is constant: paths are argv data, then quoted by AppleScript for
 * the shell. An untargeted do-script creates a new window rather than writing
 * into an existing user tab. Verify the returned tab's owning window as well. */
static const char terminal_script[] =
    "on run argv\n"
    "try\n"
    "tell application id \"com.apple.Terminal\"\n"
    "set previousWindows to id of every window\n"
    "set createdTab to do script (\"exec /bin/sh \" & quoted form of (item 1 of argv))\n"
    "set createdTTY to tty of createdTab\n"
    "set createdWindow to 0\n"
    "repeat with candidate in windows\n"
    "repeat with candidateTab in tabs of candidate\n"
    "if tty of candidateTab is createdTTY then set createdWindow to id of candidate\n"
    "end repeat\n"
    "end repeat\n"
    "if createdWindow is 0 or previousWindows contains createdWindow then return \"UNCERTAIN\"\n"
    "if (createdWindow as text) is (item 3 of argv) then return \"UNCERTAIN\"\n"
    "set custom title of createdTab to item 2 of argv\n"
    "return \"OK:\" & (createdWindow as text)\n"
    "end tell\n"
    "on error errorText number errorNumber\n"
    "if errorNumber is -1743 then return \"DENIED\"\n"
    "return \"UNCERTAIN\"\n"
    "end try\n"
    "end run\n";

/* -1 means dispatch definitely failed; -2 means its delivery is uncertain.
 * Never retry uncertain dispatch or remove a command Terminal may still run. */
static int open_terminal_view(const char *session, StreamSelection stream, long previous_window, long *window) {
    char directory[PATH_MAX], command[PATH_MAX];
    /* Never execute code read from a trace directory. Reopening after a build
     * replacement always uses this invocation's trusted native executable. */
    if (!temporary_viewer_command(session, stream, directory, command)) return -1;
    int receipt[2];
    if (pipe(receipt) < 0) { remove_temporary_command(directory, command); return -1; }
    if (cloexec(receipt[0]) < 0 || cloexec(receipt[1]) < 0 || nonblocking(receipt[0]) < 0) {
        close(receipt[0]); close(receipt[1]); remove_temporary_command(directory, command); return -1;
    }
    /* posix_spawn actions ensure Terminal can never inherit a game pipe. */
    posix_spawn_file_actions_t actions;
    if (posix_spawn_file_actions_init(&actions) != 0) {
        close(receipt[0]); close(receipt[1]); remove_temporary_command(directory, command); return -1;
    }
    int action_error = posix_spawn_file_actions_addopen(&actions, 0, "/dev/null", O_RDONLY, 0);
    if (!action_error) action_error = posix_spawn_file_actions_adddup2(&actions, receipt[1], 1);
    if (!action_error) action_error = posix_spawn_file_actions_addopen(&actions, 2, "/dev/null", O_WRONLY, 0);
    char previous[32], title[128];
    snprintf(previous, sizeof(previous), "%ld", previous_window);
    const char *base = strrchr(session, '/');
    snprintf(title, sizeof(title), "spdctl %s — %.80s", stream == STREAM_SEND ? "SEND" : "RECV + ERROR", base ? base + 1 : session);
    char *arguments[] = {"/usr/bin/osascript", "-e", (char *)terminal_script, command, title, previous, NULL};
    pid_t child;
    posix_spawnattr_t attributes;
    int attribute_error = posix_spawnattr_init(&attributes);
    if (attribute_error) {
        posix_spawn_file_actions_destroy(&actions); close(receipt[0]); close(receipt[1]); remove_temporary_command(directory, command);
        errno = attribute_error; return -1;
    }
    if (!action_error) action_error = posix_spawnattr_setflags(&attributes, POSIX_SPAWN_CLOEXEC_DEFAULT);
    int error = action_error ? action_error : posix_spawn(&child, arguments[0], &actions, &attributes, arguments, environ);
    posix_spawnattr_destroy(&attributes);
    posix_spawn_file_actions_destroy(&actions);
    close(receipt[1]);
    if (error) { close(receipt[0]); errno = error; remove_temporary_command(directory, command); return -1; }
    int status = 0;
    char reply[128] = {0}; size_t reply_size = 0;
    double deadline = monotonic_seconds() + 3;
    for (;;) {
        ssize_t count;
        while (reply_size < sizeof(reply) - 1 && (count = read(receipt[0], reply + reply_size, sizeof(reply) - 1 - reply_size)) > 0)
            reply_size += (size_t)count;
        pid_t result = waitpid(child, &status, WNOHANG);
        if (result == child) {
            while (reply_size < sizeof(reply) - 1 && (count = read(receipt[0], reply + reply_size, sizeof(reply) - 1 - reply_size)) > 0)
                reply_size += (size_t)count;
            close(receipt[0]);
            if (WIFEXITED(status) && WEXITSTATUS(status) == 0 && reply_size >= 5 && !strncmp(reply, "OK:", 3)
                && reply[3] >= '0' && reply[3] <= '9') {
                char *end = NULL;
                errno = 0;
                long identifier = strtol(reply + 3, &end, 10);
                if (errno != ERANGE && identifier > 0 && identifier != previous_window
                    && end == reply + reply_size - 1 && *end == '\n' && end[1] == '\0') {
                    *window = identifier; return 0;
                }
            }
            if (reply_size == 7 && !memcmp(reply, "DENIED\n", 7)) { remove_temporary_command(directory, command); return -1; }
            return -2;
        }
        if (result < 0 && errno != EINTR) { close(receipt[0]); return -2; }
        if (interrupted || monotonic_seconds() > deadline) {
            kill(child, SIGKILL);
            while (waitpid(child, &status, 0) < 0 && errno == EINTR) {}
            close(receipt[0]);
            return -2;
        }
        struct timespec pause = {0, 10000000}; nanosleep(&pause, NULL);
    }
}
static void terminal_warning(const char *session, StreamSelection stream, bool uncertain) {
    diagnostic(uncertain ? "spdctl: TERMINAL_OPEN_UNCERTAIN stream=" : "spdctl: TERMINAL_OPEN_FAILED stream=");
    diagnostic(stream_name(stream));
    diagnostic(" (recording continues; inspect existing windows before manually reopening this stream)\n");
    diagnostic("spdctl: Reopen with trace open --stream "); diagnostic(stream_name(stream)); diagnostic(" --session PATH\n");
    diagnostic("spdctl: TRACE_SESSION "); diagnostic(session); diagnostic("\n");
}
static int open_terminal(const char *session, StreamSelection selection) {
    long previous_window = 0; int failed = 0;
    for (StreamSelection stream = STREAM_SEND; stream <= STREAM_RECV; stream++) {
        if (selection != STREAM_ALL && selection != stream) continue;
        long window = 0;
        int result = open_terminal_view(session, stream, previous_window, &window);
        if (result) { terminal_warning(session, stream, result == -2); failed = 1; }
        else previous_window = window;
    }
    return failed;
}
static pid_t open_terminal_async(const char *session) {
    char executable[PATH_MAX];
    if (!self_path(executable)) return -1;
    posix_spawn_file_actions_t actions; posix_spawnattr_t attributes;
    int error = posix_spawn_file_actions_init(&actions);
    if (error) return -1;
    error = posix_spawnattr_init(&attributes);
    if (error) { posix_spawn_file_actions_destroy(&actions); return -1; }
    if (!error) error = posix_spawn_file_actions_addopen(&actions, 0, "/dev/null", O_RDONLY, 0);
    if (!error) error = posix_spawn_file_actions_addopen(&actions, 1, "/dev/null", O_WRONLY, 0);
    /* Keep only the controller diagnostics descriptor, never engine/trace FDs. */
    if (!error) error = posix_spawn_file_actions_adddup2(&actions, STDERR_FILENO, STDERR_FILENO);
    if (!error) error = posix_spawnattr_setflags(&attributes, POSIX_SPAWN_CLOEXEC_DEFAULT);
    char *arguments[] = {executable, "trace", "open", "--session", (char *)session, NULL};
    pid_t child = -1;
    if (!error) error = posix_spawn(&child, executable, &actions, &attributes, arguments, environ);
    posix_spawnattr_destroy(&attributes); posix_spawn_file_actions_destroy(&actions);
    return error ? -1 : child;
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
    if (ok) ok = create_viewer_command(trace->session, STREAM_SEND) && create_viewer_command(trace->session, STREAM_RECV);
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
typedef enum { PLAIN, KEY, STRING, NUMBER, LITERAL, PUNCT, SEND_COLOR, RECV_COLOR, ERROR_COLOR } TextColor;
static const char *ansi_colors[] = {"\033[0;1;39m", "\033[1;94m", "\033[1;92m", "\033[1;93m", "\033[1;95m", "\033[1;39m", "\033[1;96m", "\033[1;92m", "\033[1;91m"};
typedef struct {
    unsigned char tail[4];
    size_t tail_count, depth, capacity;
    char *containers;
    bool message_open, escaped;
    TextColor token;
    uint64_t timestamp, sequence;
} TextStream;
typedef struct {
    TextStream streams[3];
    bool color, line_start;
    int last_stream;
    TextColor active_color;
} Viewer;
static bool set_color(Viewer *viewer, TextColor color) {
    if (viewer->active_color == color) return true;
    viewer->active_color = color;
    return !viewer->color || fputs(ansi_colors[color], stdout) != EOF;
}
static void reset_json(TextStream *stream) {
    stream->depth = 0; stream->token = PLAIN; stream->escaped = false;
}
/* Store only the nesting grammar and the current token, never a JSON frame or
 * string. A stack entry is '[' for arrays, '{' while expecting an object key,
 * and ':' while expecting its value. Invalid JSON remains displayable. */
static bool token_color(TextStream *stream, unsigned char byte, TextColor *color) {
    if (stream->token == KEY || stream->token == STRING) {
        *color = stream->token;
        if (stream->escaped) stream->escaped = false;
        else if (byte == '\\') stream->escaped = true;
        else if (byte == '"') stream->token = PLAIN;
        return true;
    }
    if (stream->token == NUMBER || stream->token == LITERAL) {
        bool number = (byte >= '0' && byte <= '9') || byte == '.' || byte == '-' || byte == '+' || byte == 'e' || byte == 'E';
        bool letter = byte >= 'a' && byte <= 'z';
        if ((stream->token == NUMBER && number) || (stream->token == LITERAL && letter)) {
            *color = stream->token; return true;
        }
        stream->token = PLAIN;
    }
    *color = PLAIN;
    if (byte == '"') {
        stream->token = stream->depth && stream->containers[stream->depth - 1] == '{' ? KEY : STRING;
        *color = stream->token;
    } else if (byte == '{' || byte == '[') {
        if (stream->depth == stream->capacity) {
            size_t capacity = stream->capacity ? stream->capacity * 2 : 32;
            if (capacity < stream->capacity) return false;
            char *containers = realloc(stream->containers, capacity);
            if (!containers) return false;
            stream->containers = containers; stream->capacity = capacity;
        }
        stream->containers[stream->depth++] = (char)byte; *color = PUNCT;
    } else if (byte == '}' || byte == ']') {
        if (stream->depth) stream->depth--;
        *color = PUNCT;
    } else if (byte == ':' || byte == ',') {
        if (stream->depth && stream->containers[stream->depth - 1] != '[')
            stream->containers[stream->depth - 1] = byte == ':' ? ':' : '{';
        *color = PUNCT;
    } else if ((byte >= '0' && byte <= '9') || byte == '-') {
        stream->token = *color = NUMBER;
    } else if (byte == 't' || byte == 'f' || byte == 'n') {
        stream->token = *color = LITERAL;
    }
    return true;
}
static bool viewer_header(Viewer *viewer, int direction, uint64_t timestamp, uint64_t sequence, bool continued) {
    static const char *names[] = {"SEND", "RECV", "ERROR"};
    static const TextColor colors[] = {SEND_COLOR, RECV_COLOR, ERROR_COLOR};
    if (!set_color(viewer, PLAIN) || (!viewer->line_start && fputc('\n', stdout) == EOF)
        || !set_color(viewer, colors[direction])
        || printf("[%" PRIu64 ".%09" PRIu64 "] #%" PRIu64 " %s%s", timestamp / UINT64_C(1000000000),
                  timestamp % UINT64_C(1000000000), sequence, names[direction], continued ? " (continued)" : "") < 0
        || !set_color(viewer, PLAIN) || fputc('\n', stdout) == EOF) return false;
    viewer->line_start = true; viewer->last_stream = direction;
    return true;
}
static size_t utf8_unit(const unsigned char *data, size_t size) {
    unsigned char byte = data[0];
    size_t count = byte >= 0xc2 && byte <= 0xdf ? 2 : byte >= 0xe0 && byte <= 0xef ? 3 : byte >= 0xf0 && byte <= 0xf4 ? 4 : 1;
    if (count > size) return 1;
    for (size_t i = 1; i < count; i++) if ((data[i] & 0xc0) != 0x80) return 1;
    return count;
}
static bool render_bytes(Viewer *viewer, int direction, const unsigned char *data, size_t size) {
    TextStream *stream = &viewer->streams[direction];
    for (size_t i = 0; i < size; ) {
        if (!stream->message_open || viewer->last_stream != direction) {
            if (!viewer_header(viewer, direction, stream->timestamp, stream->sequence, stream->message_open)) return false;
            stream->message_open = true;
        }
        unsigned char byte = data[i];
        TextColor color = direction == 2 ? ERROR_COLOR : PLAIN;
        if (direction != 2 && !token_color(stream, byte, &color)) return false;
        if (byte == '\n') color = PLAIN;
        size_t count = byte < 0x80 ? 1 : utf8_unit(data + i, size - i);
        if (!set_color(viewer, color) || !safe_text(data + i, count)) return false;
        viewer->line_start = byte == '\n';
        if (byte == '\n') { stream->message_open = false; reset_json(stream); }
        i += count;
    }
    return true;
}
static bool read_range(int fd, uint64_t offset, uint64_t size, Viewer *viewer, int direction) {
    TextStream *stream = &viewer->streams[direction];
    unsigned char data[BUFFER_SIZE + 4];
    while (size) {
        memcpy(data, stream->tail, stream->tail_count);
        size_t count = size > BUFFER_SIZE ? BUFFER_SIZE : (size_t)size;
        ssize_t read_count;
        do { read_count = pread(fd, data + stream->tail_count, count, (off_t)offset); } while (read_count < 0 && errno == EINTR);
        if (read_count <= 0) return false;
        offset += (uint64_t)read_count; size -= (uint64_t)read_count;
        size_t available = stream->tail_count + (size_t)read_count, display = available;
        /* A UTF-8 codepoint can cross reads and events, independently per stream. */
        for (size_t back = 1; back <= 3 && back <= available; back++) {
            unsigned char byte = data[available - back];
            if (byte >= 0xc2 && byte <= 0xf4) {
                size_t wanted = byte <= 0xdf ? 2 : byte <= 0xef ? 3 : 4;
                if (wanted > back) display -= back;
                break;
            }
            if ((byte & 0xc0) != 0x80) break;
        }
        if (!render_bytes(viewer, direction, data, display)) return false;
        stream->tail_count = available - display;
        if (stream->tail_count) memcpy(stream->tail, data + display, stream->tail_count);
    }
    return true;
}
static bool flush_text_tails(Viewer *viewer) {
    for (int i = 0; i < 3; i++) {
        TextStream *stream = &viewer->streams[i];
        if (stream->tail_count && !render_bytes(viewer, i, stream->tail, stream->tail_count)) return false;
        stream->tail_count = 0;
    }
    return set_color(viewer, PLAIN);
}
static bool viewer_error(Viewer *viewer, uint64_t timestamp, uint64_t sequence, const char *detail) {
    if (!viewer_header(viewer, 2, timestamp, sequence, false)
        || !set_color(viewer, ERROR_COLOR) || !safe_text((const unsigned char *)detail, strlen(detail))
        || !set_color(viewer, PLAIN) || fputc('\n', stdout) == EOF) return false;
    viewer->last_stream = -1; viewer->line_start = true;
    return true;
}
static bool status_is_error(const char *detail) {
    /* Hide only known successful lifecycle events. New failure statuses remain
     * visible by default without requiring a viewer update. */
    return strcmp(detail, "STARTED") && strcmp(detail, "EOF_SENT") && strcmp(detail, "EXITED:0");
}
static int view_session(const char *session, bool color, StreamSelection selection) {
    int directory = open(session, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
    if (directory < 0) return fail("spdctl: TRACE_SESSION_UNAVAILABLE\n", 66);
    int index = read_session_file(directory, "events.tsv"), raw[3];
    const char *names[] = {"send.raw", "recv.raw", "stderr.raw"};
    for (int i = 0; i < 3; i++) raw[i] = read_session_file(directory, names[i]);
    FILE *input = index >= 0 ? fdopen(index, "r") : NULL;
    Viewer viewer = { .color = color, .line_start = true, .last_stream = -1, .active_color = PLAIN };
    int result = 0;
    char line[1024];
    if (!input || raw[0] < 0 || raw[1] < 0 || raw[2] < 0 || !fgets(line, sizeof(line), input) || strcmp(line, TRACE_VERSION)) {
        result = fail("spdctl: TRACE_FORMAT_UNSUPPORTED_OR_INCOMPLETE\n", 65); goto done;
    }
    if (viewer.color && fputs(ansi_colors[PLAIN], stdout) == EOF) { result = 1; goto done; }
    const char *description = selection == STREAM_SEND ? "SEND: controller → CLI" : selection == STREAM_RECV
        ? "RECV: CLI → recorder; ERROR: diagnostics" : "SEND: controller → CLI; RECV: CLI → recorder; ERROR: diagnostics";
    if (printf("spdctl transport viewer — %s\nRaw bytes remain in the session files.\n", description) < 0) { result = 1; goto done; }
    if (fputs("Session: ", stdout) == EOF || !safe_text((const unsigned char *)session, strlen(session))
        || fputc('\n', stdout) == EOF || fflush(stdout) != 0) { result = 1; goto done; }
    uint64_t expected = 1;
    uint64_t next_offset[3] = {0, 0, 0};
    bool finished = false;
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
                if (stream >= 0) {
                    struct stat information;
                    if (offset != next_offset[stream] || fstat(raw[stream], &information) < 0
                        || information.st_size < 0 || offset + length > (uint64_t)information.st_size) {
                        result = fail("spdctl: TRACE_DATA_UNAVAILABLE\n", 65); break;
                    }
                    next_offset[stream] += length;
                    if (selected_stream(selection, stream)) {
                        viewer.streams[stream].timestamp = timestamp; viewer.streams[stream].sequence = sequence;
                        if (!read_range(raw[stream], offset, length, &viewer, stream)) {
                            result = fail("spdctl: TRACE_DATA_UNAVAILABLE\n", 65); break;
                        }
                    }
                } else if (!strcmp(kind, "STATUS")) {
                    if (selected_stream(selection, 2) && status_is_error(detail)
                        && !viewer_error(&viewer, timestamp, sequence, detail)) { result = 1; break; }
                } else if (strcmp(kind, "DELIVERED")) { result = fail("spdctl: TRACE_INDEX_INVALID\n", 65); break; }
                if (fflush(stdout) != 0) { result = 1; break; }
                continue;
            }
        } else if (ferror(input)) { result = fail("spdctl: TRACE_INDEX_READ_FAILED\n", 65); break; }
        clearerr(input);
        if (interrupted) break;
        int marker = read_session_file(directory, ".incomplete");
        if (marker < 0) {
            if (errno != ENOENT) { result = fail("spdctl: TRACE_OWNER_STATUS_UNAVAILABLE\n", 65); break; }
            if (!flush_text_tails(&viewer)) result = 1;
            puts("\nSession ended. This viewer can be reopened at any time."); fflush(stdout); finished = true; break;
        }
        /* The writer's CLOEXEC lock lasts for its lifetime, unlike a PID which
         * can be recycled. Viewers acquire only a nonblocking shared read lock. */
        int locked = flock(marker, LOCK_SH | LOCK_NB);
        int lock_error = errno;
        close(marker);
        if (locked == 0) {
            if (!flush_text_tails(&viewer)
                || (selected_stream(selection, 2)
                    && !viewer_error(&viewer, now_ns(), expected, "Recording incomplete: recorder exited without finalizing the trace."))) result = 1;
            fflush(stdout); finished = true; break;
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
    if (viewer.color) (void)fputs("\033[0m", stdout);
    for (int i = 0; i < 3; i++) free(viewer.streams[i].containers);
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
    pid_t terminal_child = terminal ? open_terminal_async(trace->session) : -1;
    if (terminal && terminal_child < 0) {
        terminal_warning(trace->session, STREAM_SEND, false); terminal_warning(trace->session, STREAM_RECV, false);
    }
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
        if (terminal_child > 0) {
            int terminal_status;
            pid_t result = waitpid(terminal_child, &terminal_status, WNOHANG);
            if (result == terminal_child || (result < 0 && errno == ECHILD)) terminal_child = -1;
        }
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
        if (argc < first + 4 || (strcmp(argv[first + 1], "view") && strcmp(argv[first + 1], "open")))
            return fail("spdctl: Expected trace view|open --session ABS [--stream all|send|recv] [--color auto|always|never]\n", 64);
        bool viewing = !strcmp(argv[first + 1], "view");
        const char *requested_session = NULL, *color_mode = "auto", *stream_mode = "all";
        bool color_seen = false, stream_seen = false;
        for (int i = first + 2; i < argc; i++) {
            if (!strcmp(argv[i], "--session") && !requested_session && i + 1 < argc) requested_session = argv[++i];
            else if (viewing && !strcmp(argv[i], "--color") && !color_seen && i + 1 < argc) {
                color_mode = argv[++i]; color_seen = true;
            } else if (!strcmp(argv[i], "--stream") && !stream_seen && i + 1 < argc) {
                stream_mode = argv[++i]; stream_seen = true;
            } else return fail("spdctl: INVALID_TRACE_ARGUMENTS\n", 64);
        }
        if (!requested_session || requested_session[0] != '/' || (strcmp(color_mode, "auto")
            && strcmp(color_mode, "always") && strcmp(color_mode, "never"))
            || (strcmp(stream_mode, "all") && strcmp(stream_mode, "send") && strcmp(stream_mode, "recv")))
            return fail("spdctl: INVALID_TRACE_ARGUMENTS\n", 64);
        StreamSelection selection = !strcmp(stream_mode, "send") ? STREAM_SEND : !strcmp(stream_mode, "recv") ? STREAM_RECV : STREAM_ALL;
        const char *term = getenv("TERM");
        bool color = !strcmp(color_mode, "always") || (!strcmp(color_mode, "auto") && isatty(STDOUT_FILENO)
                     && (!term || strcmp(term, "dumb")) && !getenv("NO_COLOR"));
        char session[PATH_MAX];
        if (!realpath(requested_session, session)) return fail("spdctl: TRACE_SESSION_UNAVAILABLE\n", 66);
        if (viewing) return view_session(session, color, selection);
        int directory = open(session, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
        int index = directory >= 0 ? read_session_file(directory, "events.tsv") : -1;
        char header[sizeof(TRACE_VERSION)] = {0};
        ssize_t read_count = index >= 0 ? read(index, header, sizeof(TRACE_VERSION) - 1) : -1;
        close_fd(&index); close_fd(&directory);
        if (read_count != (ssize_t)sizeof(TRACE_VERSION) - 1 || strcmp(header, TRACE_VERSION))
            return fail("spdctl: TRACE_FORMAT_UNSUPPORTED_OR_INCOMPLETE\n", 65);
        return open_terminal(session, selection);
    }
    char **forward = calloc((size_t)engine_count + (size_t)(argc - first) + 1, sizeof(char *));
    if (!forward) return fail("spdctl: ALLOCATION_FAILED\n", 1);
    for (int i = 0; i < engine_count; i++) forward[i] = engine[i];
    int forward_count = engine_count;
    bool run = argc > first && !strcmp(argv[first], "run"), machine = false, terminal = true;
    const char *home = getenv("HOME"), *selected = getenv("SPDCTL_PROFILE"), *trace_root = NULL;
    char default_profile[PATH_MAX], default_trace[PATH_MAX], profile[PATH_MAX];
    if (!home || home[0] != '/' || snprintf(default_profile, sizeof(default_profile), "%s/Library/Application Support/Shattered Pixel Dungeon CLI v5", home) >= PATH_MAX
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
