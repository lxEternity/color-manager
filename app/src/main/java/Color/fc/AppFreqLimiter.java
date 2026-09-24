package Color.fc;

import android.content.Context;
import android.os.SystemClock;

import java.util.HashMap;
import java.util.Map;

/**
 * 单应用负载限制（自适应限频方案，移植自 powerd_freqcap 模块引擎）：
 * 低频率（频率下限）/ 高频率（频率上限）—— 按各 policy 写 scaling_min/max_freq：
 *   频点吸附（snap 到本机可用频点，最近邻并列取小）+ 校验写入（写后回读，被压低视为成功）
 *   + 巡逻纠偏（目标不变时只在当前值漂移时才写，目标变更立即全量写）
 * 前台检测（Kin 关键源码）：cpuset top-app 进程态直读（最省电、零 dumpsys）→ dumpsys window → ResumedActivity 回退
 * 占用限制（CPU 占用百分比）—— cgroup CFS 配额（v2 cpu.max / v1 cfs_quota），按整机核心数折算，前台期间持续重申
 * 存储: /sdcard/Android/qingtd/单应用负载.conf，每行: 包名=低频率MHz,高频率MHz,占用%(0=未设)
 * 运行: AppLimitService 每秒 tick（前台检测），配置 5 秒重载
 */
public class AppFreqLimiter {

    static final String CONF = "/sdcard/Android/qingtd/单应用负载.conf";
    /** 进入限制前的各 CPU min/max 缓存（会话内首份，恢复后删除） */
    private static final String CACHE = "/data/local/tmp/afc.cache";

    private static final Map<String, long[]> limits = new HashMap<>();
    /** 当前已应用【频率】限制的包（恢复用） */
    private static String applied = null;
    /** 当前已应用【占用配额】的包（解除用） */
    private static String appliedQuota = null;
    private static long nextLoad = 0;
    /** 上次写入的目标频率（变更→强制全量写；未变→巡逻纠偏，只在漂移时写） */
    private static long lastMn = -1, lastMx = -1;
    /** 进程内一次性标记：首个 tick 先清扫上次会话可能残留的限制（服务被杀时来不及恢复） */
    private static boolean swept = false;

    /** 每秒调用：前台检测 + 限制应用/恢复（频率与占用配额每轮重申，防止外部脚本改写） */
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
                if (applied != null || appliedQuota != null) restoreAll();
                return;
            }
            String fg = fgPkg();
            if (fg == null) return;
            long[] v = limits.get(fg);
            boolean needFreq = v != null && (v[0] > 0 || v[1] > 0);
            boolean needPct = v != null && v[2] > 0;
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
            // 占用限制：切走应用、或占用限制被关闭 → 解除配额
            if (appliedQuota != null && (!fg.equals(appliedQuota) || !needPct)) {
                clearQuota(appliedQuota);
                appliedQuota = null;
            }
            if (needPct) {
                applyPct(fg, v[2]);           // 每轮重申，新起子进程 1 秒内纳入
                appliedQuota = fg;
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
        return limits.isEmpty() && applied == null && appliedQuota == null;
    }

    /** 是否配置过任一限制（ensure 启动前判断，避免空跑） */
    static boolean anyLimitConfigured() {
        load();
        for (long[] v : limits.values())
            if (v[0] > 0 || v[1] > 0 || v[2] > 0) return true;
        return false;
    }

    /** 恢复全部：频率按缓存还原 + 占用配额解除（服务停止/清空配置时调用） */
    static void restoreAll() {
        RootShell.exec(freqRestoreScript(), 10);
        if (appliedQuota != null) clearQuota(appliedQuota);
        applied = null;
        appliedQuota = null;
        lastMn = -1;
        lastMx = -1;
    }

    /** 读取限制配置（包名=低频率MHz,高频率MHz,占用%）。读取失败保留旧配置，
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
                        new long[]{parse(v.length > 0 ? v[0] : null), parse(v.length > 1 ? v[1] : null),
                                parse(v.length > 2 ? v[2] : null)});
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

    /** 应用频率限制（powerd_freqcap 自适应引擎）：
     *  按 policy 写（非按 cpu 写，离线核同簇生效）；频点吸附到本机可用频点；
     *  校验写入（写后回读，被压向目标方向视为成功）；
     *  巡逻纠偏：目标变更→全量写，目标未变→只在当前值漂移（cur 超过封顶/低于地板）时才写；
     *  单边关闭（低/高频率其一改回 0、另一边仍在生效）：被关的一侧按缓存恢复原值，
     *  否则旧封顶/地板会残留在系统上（表现为关了高频率仍锁在旧上限、关了低频率降不到最低频） */
    private static void applyFreq(String pkg, long[] v) {
        long mn = v[0], mx = v[1];
        if (mn > 0 && mx > 0 && mn > mx) mn = mx;   // 地板不超过封顶
        long mnK = mn * 1000, mxK = mx * 1000;
        boolean force = mnK != lastMn || mxK != lastMx;   // 目标变更→全量写
        boolean rm = lastMn > 0 && mnK <= 0;   // 低频率被关闭→恢复原始下限
        boolean rx = lastMx > 0 && mxK <= 0;   // 高频率被关闭→恢复原始上限
        lastMn = mnK;
        lastMx = mxK;
        RootShell.exec(applyFreqScript(mnK, mxK, force, rm, rx), 12);
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

    /** 占用限制：整机核心数 × 百分比 → CFS 配额，写入该包全部进程的 cgroup（每轮重申） */
    private static void applyPct(String pkg, long pct) {
        RootShell.exec(
                "n=$(ls -d /sys/devices/system/cpu/cpu[0-9]* 2>/dev/null | wc -l); "
                        + "[ \"$n\" -gt 0 ] || exit 0; "
                        + "q=$(( n * " + pct + " * 1000 )); "
                        + quotaScript(pkg, "$q 100000", "$q"), 10);
    }

    /** 定位并写该包全部进程的 CFS 配额：直接读 /proc/<pid>/cgroup 的真实层级路径，
     *  v1(/dev/cpuctl) 与 v2(/sys/fs/cgroup) 挂载点都尝试，覆盖 top-app/foreground 等变体。
     *  v2Val 例 "160000 100000"/"max 100000"；v1Val 例 "160000"/"-1" */
    private static String quotaScript(String pkg, String v2Val, String v1Val) {
        return "for pid in $(pgrep -f \"^" + pkg + "($|:| )\" 2>/dev/null); do "
                + "[ -d /proc/$pid ] || continue; "
                + "for p in $(cat /proc/$pid/cgroup 2>/dev/null | sed 's/.*://' | sort -u); do "
                + "case \"$p\" in *uid_*) ;; *) continue ;; esac; "
                + "for base in /dev/cpuctl /sys/fs/cgroup; do "
                + "cg=\"$base/$p\"; "
                + "[ -f \"$cg/cpu.max\" ] && echo \"" + v2Val + "\" > \"$cg/cpu.max\" 2>/dev/null; "
                + "[ -f \"$cg/cpu.cfs_quota_us\" ] && echo \"" + v1Val + "\" > \"$cg/cpu.cfs_quota_us\" 2>/dev/null; "
                + "done; done; done";
    }

    /** 解除占用配额（配额组随进程销毁，此处只清当前进程） */
    private static void clearQuota(String pkg) {
        RootShell.exec(quotaScript(pkg, "max 100000", "-1"), 10);
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
