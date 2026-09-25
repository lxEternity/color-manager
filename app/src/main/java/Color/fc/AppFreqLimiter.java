package Color.fc;

import android.content.Context;
import android.os.SystemClock;

import java.util.HashMap;
import java.util.Map;

/**
 * 单应用负载限制（自适应限频方案，移植自 powerd_freqcap 模块引擎 + Kin 小核/大核百分比封顶）：
 * 小核上限% / 大核上限%（Kin maxL/maxB 语义）—— 按各 policy 的 cpuinfo_max_freq 分簇
 *   （最高簇=大核，其余=小核），目标 = 本簇 cpuinfo_max_freq × pct / 100，只封 scaling_max_freq：
 *   频点无需吸附（百分比折算值写不进时回读校验兜底）+ 校验写入（写后回读，被压低视为成功）
 *   + 巡逻纠偏（目标不变时只在当前值漂移时才写，目标变更立即全量写）
 *   + 单边关闭恢复（某侧 pct 改回 0 → 该簇按进入前缓存恢复，防旧封顶残留）
 * 旧版绝对 MHz 格式（值 >100 视为 MHz，向后兼容）：低/高频率按各 policy 写
 *   scaling_min/max_freq，频点吸附（最近邻并列取小）+ 校验写入 + 巡逻纠偏
 * 前台检测（Kin 关键源码）：cpuset top-app 进程态直读（最省电、零 dumpsys）→ dumpsys window → ResumedActivity 回退
 * 存储: /sdcard/Android/qingtd/单应用负载.conf，每行: 包名=小核上限%,大核上限%（0=未设；旧版格式兼容）
 * 运行: AppLimitService 每秒 tick（前台检测），配置 5 秒重载
 */
public class AppFreqLimiter {

    static final String CONF = "/sdcard/Android/qingtd/单应用负载.conf";
    /** 进入限制前的各 CPU min/max 缓存（会话内首份，恢复后删除） */
    private static final String CACHE = "/data/local/tmp/afc.cache";

    private static final Map<String, long[]> limits = new HashMap<>();
    /** 当前已应用频率限制的包（恢复用） */
    private static String applied = null;
    private static long nextLoad = 0;
    /** 上次写入的目标频率（变更→强制全量写；未变→巡逻纠偏，只在漂移时写） */
    private static long lastMn = -1, lastMx = -1;
    /** 进程内一次性标记：首个 tick 先清扫上次会话可能残留的限制（服务被杀时来不及恢复） */
    private static boolean swept = false;

    /** 每秒调用：前台检测 + 限制应用/恢复（频率每轮重申，防止外部脚本改写） */
    static void tick(Context ctx) {
        try {
            long now = SystemClock.elapsedRealtime();
            if (now >= nextLoad) {
                load();
                nextLoad = now + 5000;
            }
            if (!swept) {
                swept = true;
                RootShell.exec(freqRestoreScript(), 10);   // 启动清扫：把残留的 min/max 还原
            }
            if (limits.isEmpty()) {          // 无任何限制：清残留
                if (applied != null) restoreAll();
                return;
            }
            String fg = fgPkg();
            if (fg == null) return;
            long[] v = limits.get(fg);
            boolean needFreq = v != null && (v[0] > 0 || v[1] > 0);
            // 频率限制：切走应用、或该应用的负载限制被关闭/清空 → 立即按缓存恢复（否则限制会卡在系统上降不下去）
            if (applied != null && (!fg.equals(applied) || !needFreq)) {
                RootShell.exec(freqRestoreScript(), 10);
                applied = null;
                lastMn = -1;
                lastMx = -1;   // 下次进入强制全量写
            }
            if (needFreq) {
                applyFreq(fg, v);             // 每轮重申，外部限频立即被覆盖回
                applied = fg;
            }
        } catch (Exception ignored) {
        }
    }

    /** 无任何配置且当前无应用被限制（服务自动退出条件；判空前强制重读，防瞬时读取失败误停服务） */
    static boolean idle() {
        long now = SystemClock.elapsedRealtime();
        if (now >= nextLoad) {
            load();
            nextLoad = now + 5000;
        }
        return limits.isEmpty() && applied == null;
    }

    /** 是否配置过任一限制（ensure 启动前判断，避免空跑） */
    static boolean anyLimitConfigured() {
        load();
        for (long[] v : limits.values())
            if (v[0] > 0 || v[1] > 0) return true;
        return false;
    }

    /** 恢复全部：频率按缓存还原（服务停止/清空配置时调用） */
    static void restoreAll() {
        RootShell.exec(freqRestoreScript(), 10);
        applied = null;
        lastMn = -1;
        lastMx = -1;
    }

    /** 读取限制配置（包名=小核上限%,大核上限%；旧版三段格式忽略第三段）。读取失败保留旧配置，
     *  防止 su 瞬时超时被当成“无配置”→ 误清限制、误停服务 */
    private static void load() {
        String conf = RootShell.readFile(CONF);
        if (conf == null) return;
        limits.clear();
        for (String line : conf.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            try {
                String[] v = line.substring(eq + 1).trim().split(",");
                limits.put(line.substring(0, eq).trim(),
                        new long[]{parse(v.length > 0 ? v[0] : null), parse(v.length > 1 ? v[1] : null)});
            } catch (Exception ignored) {
            }
        }
    }

    private static long parse(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /** 前台包名（Kin 关键源码三级链，进程态优先——零 dumpsys）：
     *  1) cpuset top-app 任务表直读 /proc/<pid>/cmdline（最省电；只取含包名特征的进程，剥离 :子进程后缀）
     *  2) dumpsys window（mCurrentFocus/mFocusedApp）
     *  3) dumpsys activity ResumedActivity（部分 ROM 窗口焦点行缺失） */
    private static String fgPkg() {
        RootShell.Result r = RootShell.exec(
                "p=$(for t in $(cat /dev/cpuset/top-app/tasks 2>/dev/null); do "
                        + "c=$(tr '\\0' ' ' < /proc/$t/cmdline 2>/dev/null | awk '{print $1}'); "
                        + "case \"$c\" in *.*) echo \"${c%%:*}\";; esac; "
                        + "done | tail -1); "
                        + "[ -n \"$p\" ] || { f=$(dumpsys window 2>/dev/null | grep -m1 -E 'mCurrentFocus|mFocusedApp');"
                        + "p=$(echo \"$f\" | grep -oE '[a-zA-Z][a-zA-Z0-9._]+/' | head -1); }; "
                        + "[ -n \"${p%/}\" ] || { f=$(dumpsys activity activities 2>/dev/null"
                        + " | grep -m1 'ResumedActivity');"
                        + " p=$(echo \"$f\" | grep -oE '[a-zA-Z][a-zA-Z0-9._]+/' | head -1); }; "
                        + "echo \"${p%/}\"", 8);
        if (!r.ok() || r.out == null) return null;
        String p = r.out.trim();
        return p.isEmpty() ? null : p;
    }

    /** 应用频率限制（双模式）：
     *  值 ≤100 → Kin 百分比模式（分簇只封 max：小核 pctL / 大核 pctB）；
     *  值 >100 → 旧版绝对 MHz 模式（min+max 全写，原 powerd_freqcap 引擎）；
     *  巡逻纠偏：目标变更→全量写，目标未变→只在当前值漂移时才写；
     *  单边关闭：被关的一侧按进入前缓存恢复原值，否则旧封顶/地板会残留在系统上 */
    private static void applyFreq(String pkg, long[] v) {
        long a = v[0], b = v[1];
        boolean pct = a <= 100 && b <= 100;      // 新百分比格式（旧 MHz ≥300 必 >100）
        boolean force;
        boolean rm, rx;
        if (pct) {
            force = a != lastMn || b != lastMx;  // lastMn/lastMx 存百分比参数
            rm = false;                          // 百分比模式不动 min，无下限可关
            rx = (lastMn > 0 && a <= 0) || (lastMx > 0 && b <= 0);   // 某簇 pct 关→该簇恢复
            lastMn = a;
            lastMx = b;
        } else {
            long mn = a, mx = b;
            if (mn > 0 && mx > 0 && mn > mx) mn = mx;   // 地板不超过封顶
            long mnK = mn * 1000, mxK = mx * 1000;
            force = mnK != lastMn || mxK != lastMx;
            rm = lastMn > 100 && mnK <= 0;      // 低频率被关闭→恢复原始下限
            rx = lastMx > 100 && mxK <= 0;      // 高频率被关闭→恢复原始上限
            lastMn = mnK;
            lastMx = mxK;
            RootShell.exec(applyFreqScript(mnK, mxK, force, rm, rx), 12);
            return;
        }
        RootShell.exec(applyPctCapScript(a, b, force, rx), 12);
    }

    /** applyFreq 的脚本体（独立出来便于对生成脚本做语法/逻辑校验） */
    static String applyFreqScript(long mnK, long mxK, boolean force, boolean rm, boolean rx) {
        return
                // 首次进入：缓存各 CPU 当前 min/max（首行写启动标识，防止跨启动恢复旧值）
                "[ -f '" + CACHE + "' ] || { cat /proc/sys/kernel/random/boot_id > '" + CACHE + "' 2>/dev/null; "
                        + "for c in /sys/devices/system/cpu/cpu[0-9]*; do "
                        + "d=$c/cpufreq; echo \"$d $(cat $d/scaling_min_freq 2>/dev/null) "
                        + "$(cat $d/scaling_max_freq 2>/dev/null)\" >> '" + CACHE + "'; done; }; "
                        // 单边关闭：按缓存恢复被关的一侧（缓存缺失→回退硬件 cpuinfo 默认值）
                        + "RM=" + (rm ? 1 : 0) + "; RX=" + (rx ? 1 : 0) + "; "
                        + "if [ \"$RM\" = 1 ] || [ \"$RX\" = 1 ]; then "
                        + "if [ -f '" + CACHE + "' ]; then "
                        + "while read d mn mx; do "
                        + "[ -d \"$d\" ] || continue; "
                        + "if [ \"$RM\" = 1 ]; then "
                        + "w=$mn; [ -n \"$w\" ] && [ \"$w\" -gt 0 ] 2>/dev/null "
                        + "|| w=$(cat $d/cpuinfo_min_freq 2>/dev/null); "
                        + "chmod 644 $d/scaling_min_freq 2>/dev/null; "
                        + "echo \"$w\" > $d/scaling_min_freq 2>/dev/null; "
                        + "fi; "
                        + "if [ \"$RX\" = 1 ]; then "
                        + "w=$mx; [ -n \"$w\" ] && [ \"$w\" -gt 0 ] 2>/dev/null "
                        + "|| w=$(cat $d/cpuinfo_max_freq 2>/dev/null); "
                        + "cmn=$(cat $d/scaling_min_freq 2>/dev/null); "
                        + "[ -n \"$cmn\" ] && [ \"$w\" -lt \"$cmn\" ] 2>/dev/null "
                        + "&& echo \"$(cat $d/cpuinfo_min_freq 2>/dev/null)\" > $d/scaling_min_freq 2>/dev/null; "
                        + "chmod 644 $d/scaling_max_freq 2>/dev/null; "
                        + "echo \"$w\" > $d/scaling_max_freq 2>/dev/null; "
                        + "fi; "
                        + "done < '" + CACHE + "'; "
                        + "else "
                        + "for d in /sys/devices/system/cpu/cpufreq/policy*; do "
                        + "[ -d \"$d\" ] || continue; "
                        + "[ \"$RM\" = 1 ] && { chmod 644 $d/scaling_min_freq 2>/dev/null; "
                        + "echo \"$(cat $d/cpuinfo_min_freq 2>/dev/null)\" > $d/scaling_min_freq 2>/dev/null; }; "
                        + "[ \"$RX\" = 1 ] && { chmod 644 $d/scaling_max_freq 2>/dev/null; "
                        + "echo \"$(cat $d/cpuinfo_max_freq 2>/dev/null)\" > $d/scaling_max_freq 2>/dev/null; }; "
                        + "done; "
                        + "fi; fi; "
                        + "MN=" + mnK + "; MX=" + mxK + "; F=" + (force ? 1 : 0) + "; "
                        + "for d in /sys/devices/system/cpu/cpufreq/policy*; do "
                        + "[ -d \"$d\" ] || continue; "
                        + "av=$(cat $d/scaling_available_frequencies 2>/dev/null); "
                        // 高频率（封顶）：吸附到最近可用频点（并列取小，同模块 snap_freq）
                        + "if [ \"$MX\" -gt 0 ] 2>/dev/null; then "
                        + "w=$MX; [ -n \"$av\" ] && w=$(printf '%s\\n' $av | awk -v t=$MX "
                        + "'BEGIN{b=0;bd=2147483647}{d=$1-t;if(d<0)d=-d;"
                        + "if(d<bd||(d==bd&&$1<b)){bd=d;b=$1}}END{if(b>0)print b;else print t}'); "
                        + "cur=$(cat $d/scaling_max_freq 2>/dev/null); "
                        // 巡逻：目标未变且未漂移（cur 不超封顶）→ 不写，避免与外部脚本对冲
                        + "if [ \"$F\" = 1 ] || [ -z \"$cur\" ] || [ \"$cur\" -gt \"$w\" ] 2>/dev/null; then "
                        + "cmn=$(cat $d/scaling_min_freq 2>/dev/null); "
                        + "[ -n \"$cmn\" ] && [ \"$cmn\" -gt \"$w\" ] 2>/dev/null "
                        + "&& echo \"$(cat $d/cpuinfo_min_freq 2>/dev/null)\" > $d/scaling_min_freq 2>/dev/null; "
                        + "chmod 644 $d/scaling_max_freq 2>/dev/null; "
                        + "echo \"$w\" > $d/scaling_max_freq 2>/dev/null; "
                        // 校验：回读被压低（≤目标）视为成功，超过目标才算写失败
                        + "got=$(cat $d/scaling_max_freq 2>/dev/null); "
                        + "[ -z \"$got\" ] || [ \"$got\" -le \"$w\" ] 2>/dev/null || "
                        + "echo \"$w\" > $d/scaling_max_freq 2>/dev/null; "
                        + "fi; fi; "
                        // 低频率（地板）：吸附 + 巡逻（cur 不低于地板→不写）
                        + "if [ \"$MN\" -gt 0 ] 2>/dev/null; then "
                        + "w=$MN; [ -n \"$av\" ] && w=$(printf '%s\\n' $av | awk -v t=$MN "
                        + "'BEGIN{b=0;bd=2147483647}{d=$1-t;if(d<0)d=-d;"
                        + "if(d<bd||(d==bd&&$1<b)){bd=d;b=$1}}END{if(b>0)print b;else print t}'); "
                        + "cur=$(cat $d/scaling_min_freq 2>/dev/null); "
                        + "if [ \"$F\" = 1 ] || [ -z \"$cur\" ] || [ \"$cur\" -lt \"$w\" ] 2>/dev/null; then "
                        + "cmx=$(cat $d/scaling_max_freq 2>/dev/null); "
                        + "[ -n \"$cmx\" ] && [ \"$w\" -gt \"$cmx\" ] 2>/dev/null "
                        + "&& { chmod 644 $d/scaling_max_freq 2>/dev/null; "
                        + "echo \"$w\" > $d/scaling_max_freq 2>/dev/null; }; "
                        + "chmod 644 $d/scaling_min_freq 2>/dev/null; "
                        + "echo \"$w\" > $d/scaling_min_freq 2>/dev/null; "
                        + "got=$(cat $d/scaling_min_freq 2>/dev/null); "
                        + "[ -z \"$got\" ] || [ \"$got\" -ge \"$w\" ] 2>/dev/null || "
                        + "echo \"$w\" > $d/scaling_min_freq 2>/dev/null; "
                        + "fi; fi; "
                        + "done";
    }

    /** Kin 百分比封顶脚本体（小核 pctL / 大核 pctB，照搬 json_cpu_max_min 分簇语义）：
     *  最高簇（cpuinfo_max_freq 最大）=大核用 pctB，其余=小核用 pctL；
     *  目标 = 本簇 cpuinfo_max_freq × pct / 100，只封 scaling_max_freq（不动 min）；
     *  巡逻纠偏：目标未变且 cur ≤ 封顶 → 不写；单边关闭（某簇 pct=0 且 RX=1）→ 该簇
     *  按进入前缓存恢复（缓存行首字段为 cpu 路径，policy 经 related_cpus 首核反查），
     *  缓存缺失回退本簇硬件 cpuinfo_max_freq */
    static String applyPctCapScript(long pctL, long pctB, boolean force, boolean rx) {
        return
                // 首次进入：缓存各 CPU 当前 min/max（首行写启动标识，防止跨启动恢复旧值）
                "[ -f '" + CACHE + "' ] || { cat /proc/sys/kernel/random/boot_id > '" + CACHE + "' 2>/dev/null; "
                        + "for c in /sys/devices/system/cpu/cpu[0-9]*; do "
                        + "d=$c/cpufreq; echo \"$d $(cat $d/scaling_min_freq 2>/dev/null) "
                        + "$(cat $d/scaling_max_freq 2>/dev/null)\" >> '" + CACHE + "'; done; }; "
                        + "PL=" + pctL + "; PB=" + pctB + "; F=" + (force ? 1 : 0) + "; RX=" + (rx ? 1 : 0) + "; "
                        + "gm=0; "
                        + "for d in /sys/devices/system/cpu/cpufreq/policy*; do "
                        + "f=$(cat $d/cpuinfo_max_freq 2>/dev/null); "
                        + "[ -n \"$f\" ] && [ \"$f\" -gt \"$gm\" ] 2>/dev/null && gm=$f; "
                        + "done; "
                        + "for d in /sys/devices/system/cpu/cpufreq/policy*; do "
                        + "[ -d \"$d\" ] || continue; "
                        + "imf=$(cat $d/cpuinfo_max_freq 2>/dev/null); "
                        + "[ -n \"$imf\" ] || continue; "
                        + "pct=$PL; [ \"$imf\" = \"$gm\" ] && pct=$PB; "
                        + "if [ \"$pct\" -gt 0 ] 2>/dev/null; then "
                        + "w=$((imf * pct / 100)); "
                        + "cur=$(cat $d/scaling_max_freq 2>/dev/null); "
                        // 巡逻：目标未变且未漂移（cur 不超封顶）→ 不写，避免与外部脚本对冲
                        + "if [ \"$F\" = 1 ] || [ -z \"$cur\" ] || [ \"$cur\" -gt \"$w\" ] 2>/dev/null; then "
                        + "cmn=$(cat $d/scaling_min_freq 2>/dev/null); "
                        + "[ -n \"$cmn\" ] && [ \"$cmn\" -gt \"$w\" ] 2>/dev/null "
                        + "&& echo \"$(cat $d/cpuinfo_min_freq 2>/dev/null)\" > $d/scaling_min_freq 2>/dev/null; "
                        + "chmod 644 $d/scaling_max_freq 2>/dev/null; "
                        + "echo \"$w\" > $d/scaling_max_freq 2>/dev/null; "
                        // 校验：回读被压低（≤目标）视为成功，超过目标才算写失败
                        + "got=$(cat $d/scaling_max_freq 2>/dev/null); "
                        + "[ -z \"$got\" ] || [ \"$got\" -le \"$w\" ] 2>/dev/null || "
                        + "echo \"$w\" > $d/scaling_max_freq 2>/dev/null; "
                        + "fi; "
                        + "elif [ \"$RX\" = 1 ]; then "
                        // 单边关闭：该簇按缓存恢复（related_cpus 首核反查缓存行），缺失回退硬件上限
                        + "c0=$(cat $d/related_cpus 2>/dev/null | awk '{print $1}'); "
                        + "w=$(awk -v c=\"/sys/devices/system/cpu/cpu$c0/cpufreq\" '$1==c{print $3}' '" + CACHE + "' 2>/dev/null); "
                        + "[ -n \"$w\" ] && [ \"$w\" -gt 0 ] 2>/dev/null || w=$imf; "
                        + "chmod 644 $d/scaling_max_freq 2>/dev/null; "
                        + "echo \"$w\" > $d/scaling_max_freq 2>/dev/null; "
                        + "fi; "
                        + "done";
    }

    /** 频率恢复脚本（无缓存=幂等退出；缓存带启动标识，跨启动残留直接丢弃不回写旧值）。
     *  写前 chmod（与应用脚本对齐，防节点被降权写失败）+ 写后回读校验；
     *  只在全部写成功时才删缓存——写失败保留缓存，下次切换应用时重试，
     *  避免出现“恢复失败还删了缓存→卡住的限频值被当成原始值缓存→永久锁频” */
    static String freqRestoreScript() {
        return "[ -f '" + CACHE + "' ] || exit 0; "
                + "b=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null); "
                + "h=$(head -n 1 '" + CACHE + "'); "
                + "[ \"$b\" = \"$h\" ] || { rm -f '" + CACHE + "'; exit 0; }; "
                + "fail=0; "
                + "while read d mn mx; do "
                + "[ -d \"$d\" ] || continue; "
                + "[ -n \"$mn\" ] || mn=$(cat $d/cpuinfo_min_freq 2>/dev/null); "
                + "[ -n \"$mx\" ] || mx=$(cat $d/cpuinfo_max_freq 2>/dev/null); "
                + "imn=$(cat $d/cpuinfo_min_freq 2>/dev/null); "
                + "cmn=$(cat $d/scaling_min_freq 2>/dev/null); "
                + "[ -n \"$cmn\" ] && [ \"$mx\" -lt \"$cmn\" ] 2>/dev/null "
                + "&& { chmod 644 $d/scaling_min_freq 2>/dev/null; "
                + "echo \"$imn\" > $d/scaling_min_freq 2>/dev/null; }; "
                + "chmod 644 $d/scaling_max_freq 2>/dev/null; "
                + "echo \"$mx\" > $d/scaling_max_freq 2>/dev/null; "
                + "got=$(cat $d/scaling_max_freq 2>/dev/null); "
                + "if [ -n \"$got\" ] && [ \"$got\" != \"$mx\" ] 2>/dev/null; then "
                + "chmod 644 $d/scaling_max_freq 2>/dev/null; "
                + "echo \"$mx\" > $d/scaling_max_freq 2>/dev/null; "
                + "got=$(cat $d/scaling_max_freq 2>/dev/null); fi; "
                + "[ -z \"$got\" ] && fail=1; "
                + "[ -n \"$got\" ] && [ \"$got\" != \"$mx\" ] && fail=1; "
                + "chmod 644 $d/scaling_min_freq 2>/dev/null; "
                + "echo \"$mn\" > $d/scaling_min_freq 2>/dev/null; "
                + "got=$(cat $d/scaling_min_freq 2>/dev/null); "
                + "if [ -n \"$got\" ] && [ \"$got\" != \"$mn\" ] 2>/dev/null; then "
                + "chmod 644 $d/scaling_min_freq 2>/dev/null; "
                + "echo \"$mn\" > $d/scaling_min_freq 2>/dev/null; "
                + "got=$(cat $d/scaling_min_freq 2>/dev/null); fi; "
                + "[ -z \"$got\" ] && fail=1; "
                + "[ -n \"$got\" ] && [ \"$got\" != \"$mn\" ] && fail=1; "
                + "done < '" + CACHE + "'; "
                + "[ \"$fail\" = 0 ] && rm -f '" + CACHE + "'";
    }
}
