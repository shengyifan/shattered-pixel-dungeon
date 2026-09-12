/* Native prelude only: select the profile without creating it before Java preflight. */
#include <limits.h>
#include <mach-o/dyld.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

int main(int argc, char **argv) {
    char profile[PATH_MAX], executable[PATH_MAX], resolved[PATH_MAX];
    const char *home = getenv("HOME");
    if (!home || snprintf(profile, sizeof(profile), "%s/Library/Application Support/Shattered Pixel Dungeon CLI v2", home) >= (int)sizeof(profile)) {
        fputs("spdctl: PROFILE_UNAVAILABLE\n", stderr); return 64;
    }
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--data-dir") == 0) {
            if (++i >= argc || argv[i][0] != '/' || snprintf(profile, sizeof(profile), "%s", argv[i]) >= (int)sizeof(profile)) {
                fputs("spdctl: ABSOLUTE_PROFILE_REQUIRED\n", stderr); return 64;
            }
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
