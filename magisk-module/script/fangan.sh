#!/system/bin/sh
# ============================================================
# 方案检测（唯一实现：install.sh / main.sh / WebUI 共用）
# 规则（按优先级）：
#   检测到 scx       → A 方案（风驰内核）
#   检测到 hmbird    → B 方案（hmbird 加强版）
#   检测到 sugov_next→ A 方案（并把调速器改为 sugov_next）
#   三者都没有       → C 方案（无风驰内核如骁龙8gen2/8+）
# 与 SOC 检测正交：SOC(ro.board.platform→files/peiz) 决定配置文件后缀，
# 本脚本只决定方案前缀 a/b/c，两者同时生效、互不冲突
#
# 用法一(source)：. fangan.sh && fangan_detect → $FANGAN(a/b/c) $FANGAN_GOV(初始调速器)
# 用法二(执行)  ：sh fangan.sh → stdout "a scx" / "b hmbird" / "a sugov_next" / "c walt"
# ============================================================

fangan_detect(){
    FANGAN=""
    FANGAN_GOV=""
    local govs=$(cat /sys/devices/system/cpu/cpufreq/policy0/scaling_available_governors 2>/dev/null)
    [ -z "$govs" ] && govs=$(cat /sys/devices/system/cpu/cpufreq/policy*/scaling_available_governors 2>/dev/null | head -1)
    if echo "$govs" | grep -qw scx; then
        FANGAN=a
        FANGAN_GOV=scx
    elif echo "$govs" | grep -qw hmbird; then
        FANGAN=b
        FANGAN_GOV=hmbird
    elif echo "$govs" | grep -qw sugov_next; then
        # sugov_next 内核：用 A 配置，调速器统一设为 sugov_next
        FANGAN=a
        FANGAN_GOV=sugov_next
    else
        FANGAN=c
        # C 方案初始调速器：优先 walt，其次 conservative，再次列表第一个
        if echo "$govs" | grep -qw walt; then
            FANGAN_GOV=walt
        elif echo "$govs" | grep -qw conservative; then
            FANGAN_GOV=conservative
        else
            FANGAN_GOV=$(echo "$govs" | tr ' ' '\n' | head -1)
        fi
    fi
}

# 直接执行时输出方案与调速器（供 WebUI shOut 调用）
if [ "${0##*/}" = "fangan.sh" ] && [ -z "$FANGAN_SOURCED" ]; then
    fangan_detect
    echo "$FANGAN $FANGAN_GOV"
fi
