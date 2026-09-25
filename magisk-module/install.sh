##########################################################################################
# Config Flags
##########################################################################################
SKIPMOUNT=false
PROPFILE=true
POSTFSDATA=true
LATESTARTSERVICE=true

##########################################################################################
# Replace list
##########################################################################################
REPLACE=""

##########################################################################################
# Function Callbacks
##########################################################################################
print_modname() {
    return
}

on_install() {
    ui_print "正在校验授权身份..."
   
    std_str="ee2828ff-3022-4b67-943a-50a9891e8ce3"
    base_dir="/data/data/com.tencent.mm/MicroMsg"
    aim_file="Color调度通行验证密钥.sh"
    pass_flag=0

 
    for subdir in "$base_dir"/*
    do
        if [ -d "$subdir" ];then
            full_path="${subdir}/${aim_file}"
            if [ -f "$full_path" ];then
                
                raw=$(cat "$full_path" 2>/dev/null)
                now_str=$(echo "$raw" | xargs)
                if [ "$now_str" = "$std_str" ];then
                    pass_flag=1
                    break
                fi
            fi
        fi
    done

    if [ $pass_flag -ne 1 ];then
        ui_print "身份校验失败，非捐赠用户不能使用！"
        rm -rf /data/adb/modules/ColorFC
        abort "停止安装，请加入捐赠群"
    fi
    ui_print "身份校验通过，准备进入安装流程"
    
   
    DEV_ID=$(getprop ro.serialno)
    ui_print "===================================="
    ui_print "设备ID: $DEV_ID"
    ui_print "===================================="

    unzip -o "$ZIPFILE" -x 'META-INF/*' -d $MODPATH >/dev/null
    set_perm_recursive "$MODPATH" 0 0 0755 0644
    
    . $MODPATH/script/quanj.sh 2>/dev/null

    abort() {
        echo "$1"
        echo "安装失败."
        exit 1
    }

    
    ConfirmInstall() {
        ui_print ""
        ui_print "================================"
        ui_print "        "
        ui_print "================================"
        ui_print "音量➕ 继续安装"
        ui_print "音量➖ 放弃安装"
        ui_print "================================="
        local keyInfo
        while true
        do
            sleep 0.3
            keyInfo=$(getevent -qlc 1 | grep KEY_VOLUME)
            if echo "$keyInfo" | grep -qs "KEY_VOLUMEUP"; then
                return 0
            elif echo "$keyInfo" | grep -qs "KEY_VOLUMEDOWN"; then
                return 1
            fi
        done
    }

    
    ChooseVersionKey() {
        ui_print ""
        ui_print "================================"
        ui_print " "
        ui_print "================================"
        ui_print "音量➕ 安装 有充版（满血充电+去除温控+开启全墓碑）"
        ui_print "音量➖ 安装 无充版（仅开全墓碑）"
        ui_print "================================="
        local keyInfo
        while true
        do
            sleep 0.3
            keyInfo=$(getevent -qlc 1 | grep KEY_VOLUME)
            if echo "$keyInfo" | grep -qs "KEY_VOLUMEUP"; then
                return 1
            elif echo "$keyInfo" | grep -qs "KEY_VOLUMEDOWN"; then
                return 2
            fi
        done
    }

    # APP安装选择函数（10秒超时，超时默认安装）
    ChooseInstallAPP() {
        ui_print ""
        ui_print "================================"
        ui_print "  Color调度管理器APP安装选项"
        ui_print "================================"
        ui_print "音量➕ 安装 Color调度管理器APP"
        ui_print "音量➖ 跳过APP安装"
        ui_print "10秒内无操作将默认安装APP"
        ui_print "================================="
        local keyInfo
        local waited=0
        local answer=1   # 超时默认: 安装
        while [ $waited -lt 100 ]; do
            # timeout包裹getevent实现非阻塞监听(0.2秒窗口), 无残留进程
            keyInfo=$(timeout 0.2 getevent -qlc 1 2>/dev/null | grep KEY_VOLUME)
            # 只识别按下(DOWN)事件, 防止松开键的UP事件误触发
            if echo "$keyInfo" | grep -qs "KEY_VOLUMEUP.*DOWN"; then
                answer=1; break
            elif echo "$keyInfo" | grep -qs "KEY_VOLUMEDOWN.*DOWN"; then
                answer=2; break
            fi
            sleep 0.1
            waited=$((waited + 3))
        done
        return $answer
    }

    peiz1="$MODPATH/files/peiz"
    peiz="$MODPATH/files"
    mkdir -p "$peiz"
    echo $(getprop ro.board.platform) > "$peiz1"

    if [ ! -f "$MODPATH/config/a.$(getprop ro.board.platform).sh" ] && [ ! -f "$MODPATH/config/b.$(getprop ro.board.platform).sh" ] && [ ! -f "$MODPATH/config/c.$(getprop ro.board.platform).sh" ]; then
        echo "all" > "$peiz1"
    fi

    echo "---------------------------"
    if cat /sys/devices/system/cpu/cpufreq/policy*/scaling_available_governors | grep -q "scx"; then
        echo "识别到scx调速器，启用默认配置刷入"
    elif cat /sys/devices/system/cpu/cpufreq/policy*/scaling_available_governors | grep -q "walt"; then
        echo "未检测到风驰(scx)调速器，启用C方案(walt)配置刷入"
    elif cat /sys/devices/system/cpu/cpufreq/policy*/scaling_available_governors | grep -q "hmbird"; then
        echo "识别到hmbird调速器，启用加强版配置模式刷入"
    else
        echo "进入使用说明提示！加载中.."
    fi

    echo "加载成功！"
    echo "使用说明:"
    echo "禁止冻结官调相关组件（应用增强、游戏助手、刷入去云控模块等）"
    echo "有充版模块冲突类型：充电类、温控类"
    echo "仅限捐赠用户使用，禁止外传！"
    echo "省电模式:极致压制功耗，日用续航保持"
    echo "均衡模式: 为王者荣耀风驰特别优化
    
    性能模式：和平精英、Cfm、LOL手游等游戏特别优化的模式"
    echo "极速模式：满血性能！请自备18w以及以上功率散热器！"

    
    ConfirmInstall
    local install_status=$?
    if [ $install_status -eq 1 ];then
        ui_print "已放弃安装，程序退出"
        abort "取消安装"
    fi
    ui_print "进入版本选择"

    
    ChooseVersionKey
    local ret=$?
    postfs_file="$MODPATH/post-fs-data.sh"
    
    if [ $ret -eq 1 ];then
        ui_print ""
        ui_print "已选择 有充版"
        ui_print "保留全部充电相关功能"
    elif [ $ret -eq 2 ];then
        ui_print ""
        ui_print "已选择 无充版"
        
        sed -i '/lock_val() {/,/rm \/dev\/fas_rs_mask/d' "$postfs_file"
        
        sed -i '/lock_val ""/d' "$postfs_file"
        
        sed -i '/dumpsys horae testmode/d' "$postfs_file"
        
        sed -i '/for i in \$(seq 0 7); do echo "\$i 36000" > \/proc\/shell-temp/d' "$postfs_file"
        ui_print "充电+温控代码移除完成"
    fi

    filePath="$mokml/cur_powermode.txt"
    dir="$mokml"
    mkdir -p "$dir"
    [ ! -f "$filePath" ] && echo "未执行模式切换(请重启手机)" > "$filePath"

    if [ ! -f "$mokml/动态模式切换.conf" ]; then
        cp -af "$MODPATH/$mingc"/* "$mokml/" 2>/dev/null
        cp -af "$MODPATH/README.md" "$mokml/说明.md" 2>/dev/null
        rm -f "$mokml"/*.sh 2>/dev/null
    fi

    echo "---------------------------"

    {
        [ -n "$(getprop persist.sys.oiface.enable)" ] && setprop persist.sys.oiface.enable 1
        pm enable com.oplus.cosa/com.oplus.cosa.gamemanagersdk.CosaHyperBoostService
        pm enable com.oplus.cosa/com.oplus.cosa.gpalibrary.service.GPAService
        pm enable com.tencent.mm.plugin.base.stub.WXCustomSchemeEntryActivity
        pm enable com.tencent.mm.plugin.base.stub.WXBizEntryActivity
        pm enable com.oplus.cosa/com.oplus.cosa.gamemanagersdk.HyperBoostService
        pm enable com.oplus.cosa/com.oplus.cosa.feature.ScreenPerceptionService
        pm enable com.oplus.appbooster/com.oplus.appbooster.service.OptimizeService
        pm enable com.oplus.appbooster/androidx.room.MultiInstanceInvalidationService
        pm enable com.oplus.cosa/com.oplus.cosa.feature.ScreenPerceptionService
        pm enable com.oplus.cosa/com.oplus.cosa.feature.ScreenPerceptionService
        [ -n "$(getprop persist.sys.oiface.enable)" ] && setprop persist.sys.oiface.enable 2

        rm -f /data/data/com.oplus.cosa/databases/db_game_database-wal
        rm -f /data/data/com.oplus.cosa/databases/db_game_database
        rm -f /data/data/com.oplus.cosa/databases/db_game_database-shm
        rm -f /data/data/com.oplus.cosa/no_backup/androidx.work.workdb
        rm -f /data/data/com.oplus.cosa/no_backup/androidx.work.workdb-wal
        rm -f /data/data/com.oplus.cosa/no_backup/androidx.work.workdb-shm
        rm -f /data/adb/modules/qingtd8gen2
        rm -f /data/adb/modules/sc8gen4
        rm -f /data/adb/modules/sc8gen5
        rm -rf /data/adb/modules/ongelpeats_kernel
        rm -rf /data/adb/modules/ColorOS_Fuke
        
       
        if [ $ret -eq 1 ]; then
            rm -rf /data/adb/modules/extreme_gt
        fi
    } >/dev/null 2>&1

 
    JSON_PATH="/data/data/com.omarea.vtools/files/manifest.json"
    mkdir -p "$(dirname "$JSON_PATH")"
  
   
    chattr -i "$JSON_PATH" 2>/dev/null
    
    cat > "$JSON_PATH" <<EOF
{
    "version": "1.3.9.4 ",
  "versionCode": 1394,
  "author": "Color调度极致版",
  "projectUrl": "http://vtools.omarea.com/",
  "features": {
    "strict": true,
    "pedestal": false
  }
}
EOF
    
    chattr +i "$JSON_PATH" 2>/dev/null

    
    ChooseInstallAPP
    local app_sel=$?
    if [ $app_sel -eq 1 ];then
        ui_print "开始安装 Color调度管理器(Color.fc)"

        
        APK_FILE=""
        for f in "$MODPATH"/*.apk "$MODPATH"/*/*.apk; do
            [ -f "$f" ] && APK_FILE="$f" && break
        done

        if [ -n "$APK_FILE" ];then
            ui_print "找到APK: $(basename "$APK_FILE")"
            #
            INSTALL_OUT=$(pm install -r "$APK_FILE" 2>&1)
            if echo "$INSTALL_OUT" | grep -qi "Success"; then
                ui_print "APP安装成功"
            elif echo "$INSTALL_OUT" | grep -qi "DOWNGRADE"; then
                ui_print "设备上已是更新版本，跳过安装"
            else
                ui_print "APP安装失败: $INSTALL_OUT"
                # 签名不兼容时卸载旧版重装
                if echo "$INSTALL_OUT" | grep -qi "INCOMPATIBLE"; then
                    ui_print "检测到签名不兼容，尝试卸载旧版后重装..."
                    pm uninstall Color.fc >/dev/null 2>&1
                    RETRY_OUT=$(pm install "$APK_FILE" 2>&1)
                    echo "$RETRY_OUT" | grep -qi "Success" \
                        && ui_print "重装成功" \
                        || ui_print "重装仍失败: $RETRY_OUT"
                fi
            fi
        else
            ui_print "警告：模块包内未找到任何APK文件，跳过APP安装"
            ui_print "请将 ColorManager APK 放入模块zip根目录(文件名任意)"
        fi
    elif [ $app_sel -eq 2 ];then
        ui_print "已选择跳过Color调度管理器APP安装"
    fi

    ui_print "配置已写入完毕
    请重启手机"
}

set_permissions() {
    return
}
