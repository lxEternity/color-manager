#include <dirent.h>
#include <glob.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>

static const char *SR = "";

static long long rd_ll(const char *path) {
    char buf[256];
    FILE *f = fopen(path, "r");
    if (!f) return -1;
    size_t n = fread(buf, 1, sizeof(buf) - 1, f);
    fclose(f);
    buf[n] = 0;
    char *end;
    long long v = strtoll(buf, &end, 10);
    return end == buf ? -1 : v;
}

static void wr_ll(const char *path, long long v) {
    FILE *f = fopen(path, "w");
    if (!f) return;
    fprintf(f, "%lld\n", v);
    fclose(f);
}

static int cmpstr(const void *a, const void *b) {
    return strcmp(*(const char **)a, *(const char **)b);
}

static void gpu_cap(long long gpu) {
    static const char *pats[] = {
        "/sys/class/kgsl/kgsl-3d0/devfreq",
        "/sys/class/devfreq/*kgsl*",
        "/sys/class/devfreq/*gpu*",
    };
    for (int i = 0; i < 3; i++) {
        char pat[640];
        snprintf(pat, sizeof(pat), "%s%s", SR, pats[i]);
        glob_t g;
        if (glob(pat, GLOB_NOCHECK, NULL, &g) != 0) continue;
        for (size_t k = 0; k < g.gl_pathc; k++) {
            char af[768], mf[768];
            snprintf(af, sizeof(af), "%s/available_frequencies", g.gl_pathv[k]);
            snprintf(mf, sizeof(mf), "%s/max_freq", g.gl_pathv[k]);
            struct stat st;
            if (stat(af, &st) != 0) continue;
            FILE *f = fopen(af, "r");
            if (!f) continue;
            long long hw = -1, x;
            while (fscanf(f, "%lld", &x) == 1)
                if (x > hw) hw = x;
            fclose(f);
            if (hw <= 0) continue;
            long long v = hw;
            if (gpu > 0 && gpu < 100) v = hw * gpu / 100;
            wr_ll(mf, hw);
            wr_ll(mf, v);
        }
        globfree(&g);
    }
}

int main(int argc, char **argv) {
    long long mL, mB, mn, gpu;
    if (argc == 3) {
        mL = mB = atoll(argv[1]);
        mn = atoll(argv[2]);
        gpu = 0;
    } else if (argc == 5) {
        mL = atoll(argv[1]);
        mB = atoll(argv[2]);
        mn = atoll(argv[3]);
        gpu = atoll(argv[4]);
    } else {
        return 0;
    }

    const char *env = getenv("COLORFC_SYS_ROOT");
    if (env && *env) SR = env;

    char base[640];
    snprintf(base, sizeof(base), "%s/sys/devices/system/cpu/cpufreq", SR);
    DIR *d = opendir(base);
    if (!d) return 0;

    char *pol[128];
    int np = 0;
    struct dirent *e;
    while ((e = readdir(d)) != NULL && np < 128)
        if (strncmp(e->d_name, "policy", 6) == 0) pol[np++] = strdup(e->d_name);
    closedir(d);
    if (np == 0) return 0;
    qsort(pol, (size_t)np, sizeof(char *), cmpstr);

    long long gm = 0;
    for (int i = 0; i < np; i++) {
        char p[768];
        snprintf(p, sizeof(p), "%s/%s/cpuinfo_max_freq", base, pol[i]);
        long long f = rd_ll(p);
        if (f > gm) gm = f;
    }

    for (int i = 0; i < np; i++) {
        char p[768], fmax[896], fmin[896], pmax[896], pmin[896];
        snprintf(p, sizeof(p), "%s/%s", base, pol[i]);
        snprintf(fmax, sizeof(fmax), "%s/cpuinfo_max_freq", p);
        snprintf(fmin, sizeof(fmin), "%s/cpuinfo_min_freq", p);
        snprintf(pmax, sizeof(pmax), "%s/scaling_max_freq", p);
        snprintf(pmin, sizeof(pmin), "%s/scaling_min_freq", p);
        long long imf = rd_ll(fmax);
        if (imf <= 0) continue;
        long long imn = rd_ll(fmin);
        long long pct = (gm > 0 && imf == gm) ? mB : mL;
        long long vmax = imf * pct / 100;
        long long vmin = imf * mn / 100;
        if (imn > 0 && vmin < imn) vmin = imn;
        if (vmax < vmin) vmax = vmin;
        chmod(pmax, 0777);
        chmod(pmin, 0777);
        wr_ll(pmax, imf);
        if (imn > 0) wr_ll(pmin, imn);
        wr_ll(pmax, vmax);
        wr_ll(pmin, vmin);
        chmod(pmax, 0444);
        chmod(pmin, 0444);
    }

    for (int i = 0; i < np; i++) free(pol[i]);

    gpu_cap(gpu);
    return 0;
}
