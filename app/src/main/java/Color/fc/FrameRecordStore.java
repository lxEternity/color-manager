package Color.fc;

import android.content.Context;
import android.net.Uri;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 帧率录制记录存储：
 * files/frame_records.json，每行一条 JSON（开始时间/时长/采样数/核心数/帧率与CPU统计/图片引用）
 * 主页「帧率录制记录」卡片读取此数据展示列表，点击查看曲线图
 */
public class FrameRecordStore {

    public static class Rec {
        public long t;          // 开始时间
        public long dur;        // 时长 ms
        public int samples;     // 采样数
        public int cores;       // 核心数
        public double avgFps, minFps, maxFps;
        public double avgCpu, maxCpu;
        public String ref;      // 图片引用（content:// 或绝对路径）
        public String name;     // 文件名
    }

    /** 保留上限 */
    private static final int KEEP = 50;

    private static File file(Context ctx) {
        return new File(ctx.getFilesDir(), "frame_records.json");
    }

    /** 保存一条录制记录（含统计），并裁剪超限条目 */
    public static synchronized void add(Context ctx, long t, long dur, int samples, int cores,
                                        List<Float> fps, List<Float> cpu, String ref, String name) {
        double avgF = 0, minF = Double.MAX_VALUE, maxF = 0, avgC = 0, maxC = 0;
        int fn = 0;
        for (float v : fps) {
            if (v > 0) {
                avgF += v;
                fn++;
                minF = Math.min(minF, v);
                maxF = Math.max(maxF, v);
            }
        }
        int cn = cpu.size();
        for (float v : cpu) {
            avgC += v;
            maxC = Math.max(maxC, v);
        }
        try {
            JSONObject o = new JSONObject();
            o.put("t", t).put("dur", dur).put("n", samples).put("cores", cores);
            o.put("avgF", fn > 0 ? avgF / fn : 0).put("minF", fn > 0 ? minF : 0).put("maxF", maxF);
            o.put("avgC", cn > 0 ? avgC / cn : 0).put("maxC", maxC);
            o.put("ref", ref).put("name", name);
            try (PrintWriter w = new PrintWriter(new FileWriter(file(ctx), true))) {
                w.println(o.toString());
            }
        } catch (Exception ignored) {
        }
        trim(ctx);
    }

    private static void trim(Context ctx) {
        List<Rec> all = parse(ctx);
        if (all.size() <= KEEP) return;
        List<Rec> keep = new ArrayList<>(all.subList(all.size() - KEEP, all.size()));
        try (PrintWriter w = new PrintWriter(new FileWriter(file(ctx)))) {
            for (Rec r : keep) w.println(toJson(r).toString());
        } catch (Exception ignored) {
        }
    }

    /** 记录列表（最新在前） */
    public static synchronized List<Rec> list(Context ctx) {
        List<Rec> all = parse(ctx);
        List<Rec> out = new ArrayList<>(all.size());
        for (int i = all.size() - 1; i >= 0; i--) out.add(all.get(i));
        return out;
    }

    /** 主页卡片摘要 */
    public static synchronized String summary(Context ctx) {
        List<Rec> all = parse(ctx);
        if (all.isEmpty()) return "暂无录制 · 在悬浮窗点 ● 开始";
        Rec last = all.get(all.size() - 1);
        return String.format(Locale.US, "共 %d 条 · 最近 %d:%02d 均%.0fHz",
                all.size(), last.dur / 60000, (last.dur / 1000) % 60, last.avgFps);
    }

    /** 清空全部记录并删除对应图片 */
    public static synchronized void clear(Context ctx) {
        for (Rec r : parse(ctx)) {
            try {
                if (r.ref != null && r.ref.startsWith("content://")) {
                    ctx.getContentResolver().delete(Uri.parse(r.ref), null, null);
                } else if (r.ref != null) {
                    //noinspection ResultOfMethodCallIgnored
                    new File(r.ref).delete();
                }
            } catch (Exception ignored) {
            }
        }
        try {
            //noinspection ResultOfMethodCallIgnored
            file(ctx).delete();
        } catch (Exception ignored) {
        }
    }

    private static List<Rec> parse(Context ctx) {
        List<Rec> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader(file(ctx)))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    JSONObject o = new JSONObject(line);
                    Rec rec = new Rec();
                    rec.t = o.getLong("t");
                    rec.dur = o.getLong("dur");
                    rec.samples = o.optInt("n", 0);
                    rec.cores = o.optInt("cores", 0);
                    rec.avgFps = o.optDouble("avgF", 0);
                    rec.minFps = o.optDouble("minF", 0);
                    rec.maxFps = o.optDouble("maxF", 0);
                    rec.avgCpu = o.optDouble("avgC", 0);
                    rec.maxCpu = o.optDouble("maxC", 0);
                    rec.ref = o.optString("ref", "");
                    rec.name = o.optString("name", "");
                    out.add(rec);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static JSONObject toJson(Rec r) {
        try {
            return new JSONObject()
                    .put("t", r.t).put("dur", r.dur).put("n", r.samples).put("cores", r.cores)
                    .put("avgF", r.avgFps).put("minF", r.minFps).put("maxF", r.maxFps)
                    .put("avgC", r.avgCpu).put("maxC", r.maxCpu)
                    .put("ref", r.ref).put("name", r.name);
        } catch (Exception e) {
            return new JSONObject();
        }
    }
}
