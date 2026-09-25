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
