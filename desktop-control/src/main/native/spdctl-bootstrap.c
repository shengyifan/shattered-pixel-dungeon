/* Native prelude only: establish the profile before jpackage starts its one JVM. */
#include <errno.h>
#include <limits.h>
#include <mach-o/dyld.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

static int directories(const char *path) {
    char copy[PATH_MAX];
    if (snprintf(copy, sizeof(copy), "%s", path) >= (int)sizeof(copy)) return -1;
    for (char *p = copy + 1; ; p++) {
        if (*p == '/' || *p == '\0') {
            char saved = *p; *p = '\0';
            if (mkdir(copy, 0700) != 0 && errno != EEXIST) return -1;
            struct stat status;
            if (stat(copy, &status) != 0 || !S_ISDIR(status.st_mode)) return -1;
            *p = saved;
            if (saved == '\0') break;
        }
    }
    return 0;
}

int main(int argc, char **argv) {
    char profile[PATH_MAX], emergency[PATH_MAX], executable[PATH_MAX], resolved[PATH_MAX];
    const char *home = getenv("HOME");
    if (!home || snprintf(profile, sizeof(profile), "%s/Library/Application Support/Shattered Pixel Dungeon CLI", home) >= (int)sizeof(profile)) {
        fputs("spdctl: PROFILE_UNAVAILABLE\n", stderr); return 64;
    }
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--data-dir") == 0) {
            if (++i >= argc || argv[i][0] != '/' || snprintf(profile, sizeof(profile), "%s", argv[i]) >= (int)sizeof(profile)) {
                fputs("spdctl: ABSOLUTE_PROFILE_REQUIRED\n", stderr); return 64;
            }
        }
    }
    int informational = argc == 2 && (strcmp(argv[1], "--help") == 0 || strcmp(argv[1], "--version") == 0);
    if (!informational) {
        if (directories(profile) != 0 || !realpath(profile, resolved)
                || snprintf(profile, sizeof(profile), "%s", resolved) >= (int)sizeof(profile)
                || snprintf(emergency, sizeof(emergency), "%s/audit/emergency", profile) >= (int)sizeof(emergency)
                || directories(emergency) != 0) {
            fputs("spdctl: PROFILE_UNAVAILABLE\n", stderr); return 1;
        }
    }
    if (setenv("SPDCTL_PROFILE", profile, 1) != 0) return 1;
    uint32_t size = sizeof(executable);
    if (_NSGetExecutablePath(executable, &size) != 0 || !realpath(executable, resolved)) return 1;
    char *name = strrchr(resolved, '/');
    if (!name) return 1;
    *name = '\0';
    if (snprintf(executable, sizeof(executable), "%s/spdctl-jvm", resolved) >= (int)sizeof(executable)) return 1;
    argv[0] = executable;
    execv(executable, argv);
    fputs("spdctl: BOOTSTRAP_EXEC_FAILED\n", stderr);
    return 1;
}
