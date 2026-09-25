v1.3.9.7
1. 主页新增实时功耗曲线（图表引擎与配色照搬 opbatt 电池工具包，Chart.js + datalabels）：
   - 功率（W）/电池温度（℃）双 Y 轴曲线，滚动记录最近 30 条采样（2 秒/条），localStorage 持久化
   - 顶部实时功率 / 电池温度 / 充放状态三数据格，充电中·放电中·已接电状态自动识别
   - 曲线末端胶囊数值标签、深色圆角悬浮提示，离开主页自动停止采样
2. 调速器方案选择改为 SOC 识别（与 Color 管理器 APP 完全一致）：
   - 13 项 SOC 映射表（sm8850/8845/8875/8950/8975 → 方案2，其余骁龙8系与天玑旗舰 → 方案1，未知默认方案1）
   - getprop ro.board.platform → ro.soc.model → ro.mediatek.platform → ro.hardware 多级回退
   - 调速器页显示"当前SOC：xxx → 加载方案 x"，当前调速器按方案显示 scx/conservative（不再读 sysfs，
     避免部分设备 scaling_governor 读值异常导致显示不正常）
3. 移除调度参数页与调速器页的"写入 xxx 路径"提示

v1.3.9.6
1. WebUI 修复：主页"大核最高频"改为遍历全部 cpufreq policy 取最大值（不再依赖 policy7，适配无 policy7 的 SoC）
2. WebUI 文案简化：形态卡片显示"Color调度"/"自适应限频"，提示改为
   "当前模式为Color调度模式，点击切换到自适应限频"（自适应形态反之）
3. 调速器页当前调速器读取改为 policy0（与主页一致，避免大核 policy 缺失时显示空）

v1.3.9.5
1. 新增 KernelSU/APatch 标准模块 WebUI（webroot/index.html）：管理器内直接打开，无需 Termux/Python
   - 主页：模块形态展示 + 一键切换、模式快切、系统状态（手动刷新，非监视器）
   - 调度参数：四模式 opt2/CPU上下限/LLCC/uclamp/WALT 滑条编辑，写入当前活动方案 conf，当前模式实时应用
   - 调速器：四模式调速器切换与参数编辑，写入 A/B 方案脚本
2. 自适应限频（Ktwo powerd_freqcap）合并为模块内置形态，WebUI 一键切换：
   - 切换到自适应：完全卸载 Color 调度（模块文件 + /data/powercfg 运行时接口）并备份到 /data/adb/colorFC_store，
     全新释放自适应限频文件到 ColorFC 目录，模块 ID/目录同步 colorFC，自动重启管理器刷新模块列表
   - 切换回调度：自适应守护干净退出并恢复频率快照，调度文件重新释放，保留期间对 games.txt/powerd.conf 的修改
   - 注意：重刷/更新模块安装包会恢复为调度形态；/data/adb/colorFC_store 存有形态备份请勿手动清理
3. 移除旧版 Python 本地服务器 WebUI（webui/server.py），service.sh 相应清理
