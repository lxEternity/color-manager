
#include <dirent.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>

#define CPUFREQ_BASE "/sys/devices/system/cpu/cpufreq"


static char *readFileContent(const char *path) {
    struct stat st;
    if (stat(path, &st) != 0 || !S_ISREG(st.st_mode)) {
        fprintf(stderr, "File does not exist or is not a regular file\n");
        return NULL;
    }
    FILE *fp = fopen(path, "r");
    if (!fp) {
        perror("fopen failed");
        return NULL;
    }
    fseek(fp, 0, SEEK_END);
    long size = ftell(fp);
    rewind(fp);
    char *buf = malloc((size_t)size + 1);
    if (!buf) {
        fprintf(stderr, "malloc failed\n");
        fclose(fp);
        return NULL;
    }
    memset(buf, 0, (size_t)size + 1);
    if (fread(buf, 1, (size_t)size, fp) == 0) {
        /* 空文件 */
    }
    fclose(fp);
    return buf;
}


static int writeValue(const char *path, long long v) {
    chmod(path, 0777); /* 部分内核 sysfs 默认只读，先放开权限（失败忽略） */
    FILE *fp = fopen(path, "w");
    if (!fp) {
        fprintf(stderr, "Failed to open file: %s\n", path);
        return -1;
    }
    fprintf(fp, "%lld\n", v);
    fclose(fp);
    chmod(path, 0444); /* 写后复锁（root 写入不受影响） */
    return 0;
}

/* wj_zr：写入前先置零复位该 policy 的限频（0 = 解除限制），避免顺序约束 */
static void wj_zr(const char *policyDir) {
    char path[512];
    snprintf(path, sizeof(path), "%s/scaling_max_freq", policyDir);
    writeValue(path, 0);
    snprintf(path, sizeof(path), "%s/scaling_min_freq", policyDir);
    writeValue(path, 0);
}

int main(int argc, char *argv[]) {
    if (argc != 3) {
        fprintf(stderr, "Usage: %s <multiplier1> <multiplier2>\n", argv[0]);
        fprintf(stderr, "  multiplier1 = 最大频率百分比（调度页 cpuMax，如 42）\n");
        fprintf(stderr, "  multiplier2 = 最小频率百分比（调度页 cpuMin，如 5）\n");
        return EXIT_FAILURE;
    }
    int mx = atoi(argv[1]);
    int mn = atoi(argv[2]);

    DIR *dir = opendir(CPUFREQ_BASE);
    if (!dir) {
        perror("opendir failed");
        return EXIT_FAILURE;
    }

    struct dirent *ent;
    while ((ent = readdir(dir)) != NULL) {
        /* 只处理 policy* 目录（对应 ELF 中 strstr 过滤） */
        if (!strstr(ent->d_name, "policy"))
            continue;

        char policyDir[512], path[512];
        snprintf(policyDir, sizeof(policyDir), "%s/%s", CPUFREQ_BASE, ent->d_name);

        snprintf(path, sizeof(path), "%s/cpuinfo_max_freq", policyDir);
        char *content = readFileContent(path);
        if (!content)
            continue;
        long long infoMax = strtoll(content, NULL, 10);
        free(content);
        if (infoMax <= 0)
            continue;

        long long vmax = infoMax * mx / 100;
        long long vmin = infoMax * mn / 100;

        /* 先复位再写入（wj_zr 逻辑） */
        wj_zr(policyDir);

        snprintf(path, sizeof(path), "%s/scaling_max_freq", policyDir);
        writeValue(path, vmax);
        snprintf(path, sizeof(path), "%s/scaling_min_freq", policyDir);
        writeValue(path, vmin);

        printf("%s: max=%lld min=%lld\n", ent->d_name, vmax, vmin);
    }
    closedir(dir);
    return EXIT_SUCCESS;
}
