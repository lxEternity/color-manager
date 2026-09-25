#!/system/bin/sh
# 频率控制统一由 service.sh 启动的 powerd.sh 管理。
# 此阶段不写 sysfs，避免主服务启动失败时遗留无法回滚的限制。
exit 0
