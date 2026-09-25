sleep 10

until [ -d /data ]; do
sleep 1
done
sleep 1
BASEDIR="$(dirname $(readlink -f "$0"))"
. $BASEDIR/script/quanj.sh
sleep 1
#创建scene链接文件
mosdz1="$MODULE_PATH/files/pand"
mosdz="$MODULE_PATH/files"

sleep 1
if [ -f $MODULE_PATH/script/fz ]; then
sleep 20
else
cp -af $MODULE_PATH/script/vtools_powercfg.sh /data/powercfg.sh
cp -af $MODULE_PATH/config/powercfg.json /data/powercfg.json
sleep 10
fi


#等待开机反应
until [ $(getprop sys.boot_completed) == "1" ];
do
sleep 1
done

until [ -d /sdcard ]; do
sleep 1
done


chmod 777 /data/powercfg.sh
chmod 777 /data/powercfg.json
chmod 777 $MODULE_PATH/script/*
chmod 777 $MODULE_PATH/A/*
chmod 777 $MODULE_PATH/B/*
chmod 777 $MODULE_PATH/config/*
chmod 777 $MODULE_PATH/*



sleep 1
#开始对调控文件data/powercfg.sh的设置
peiz1="$MODULE_PATH/files/peiz"

clq=$(cat $peiz1)

if ! cat /data/powercfg.sh | grep -q "外部控制（比如scene）"; then
    
echo "if [[ \$kzlx != 1 ]]; then
#外部控制（比如scene）
ms=\$1
touch $mokml/stop
fi" >> /data/powercfg.sh

echo "sh $MODULE_PATH/script/main.sh \"\$ms\" \"$mosdz\" \"$clq\"" >> /data/powercfg.sh

fi

#判断修改文件是否可以访问，防止错误判断
until [ -d /sys/devices/system/cpu/cpufreq/ ]; do
sleep 1
done

#创建模式判断文件
filePath="$mokml/cur_powermode.txt"
dir=$mokml
sleep 1
#创建文件夹
mkdir $dir
sleep 1
#创建切换模式判断文件
touch $filePath
echo "powersave" > $filePath

sleep 1
#模式文件初始化
wenbp='未执行模式切换(可能开机自切换失败)'
mokdiz="$mokml/cur_powermode.txt"
echo $wenbp > $mokdiz
sleep 1
#模式文件添加
if [ -f $mokml/动态模式切换.conf ]; then
sleep 1
else
cp -af $MODULE_PATH/$mingc/动态模式切换.conf $mokml/
fi
sleep 1
#判断target_loads正负初始化
mkdir $mosdz
touch $mosdz1
echo 1 > $mosdz1
sleep 1
#判断模式是否正在切换
touch $mosdz/qhz
echo 1 > $mosdz/qhz
touch $mosdz/baom
echo 1 > $mosdz/baom


sleep 1
#将调速器改walt
for file in /sys/devices/system/cpu/cpufreq/policy*
do
chmod 777 $file/scaling_governor
echo 'conservative' > $file/scaling_governor
done


#判断target_loads正负
for file in /sys/devices/system/cpu/cpufreq/policy*
do
    for line in `cat $file/walt/target_loads`
    do
        bf=${line#*:}

        if [ $bf -le 0 ]; then
        echo 0 > $mosdz1

        else
        
        
          if [ $(cat $mosdz) -le 0 ]; then
             echo 0 > $mosdz1
          
          else
             echo 1 > $mosdz1
          fi
        fi
        
    done
done

sleep 1
#对修改文件经常判断，防止开机未加载
until [ -d $mokml ]; do
sleep 1
done

#创建判断fps数据文件
touch $mokml/fps.txt
sh $MODULE_PATH/script/display_modes.sh > $mokml/fps.txt
    
#删除外部控制的暂停文件
rm -rf $mokml/stop
#对日志文件的创建
touch $rizhidz

echo "[$(date '+%T')] 动态启动失败" > $rizhidz

killall qingtd.sh

nohup $MODULE_PATH/script/qingtd.sh >/dev/null 2>&1 &

#删除旧文件
wjdz=/sdcard/Android/qing8gen2

if [ -d $wjdz ]; then
	until [ -d $mokml ] && [ -d /data ]; do
		sleep 1
	done
	rm -rf $wjdz

fi




if [ -n "$(getprop persist.sys.oiface.enable)" ]; then
	setprop persist.sys.oiface.enable 1
fi






 

set_permissions() {
 set_perm_recursive $MODPATH 0 0 0755 0644
#来自于慕容~
}

echo "$(cat /sys/class/oplus_chg/battery/design_capacity) * 0.97" | bc | sed 's/\..*//' > /data/battery_fcc

       
    chmod 777 /sys/devices/platform/soc/3d00000.qcom,kgsl-3d0/kgsl/kgsl-3d0/max_pwrlevel

      
       


sleep 5
mkdir /sys/fs/cgroup/frozen/
mkdir /sys/fs/cgroup/unfrozen/
chown system:system /sys/fs/cgroup/frozen/cgroup.procs
chown system:system /sys/fs/cgroup/frozen/cgroup.freeze
chown system:system /sys/fs/cgroup/unfrozen/cgroup.procs
chown system:system /sys/fs/cgroup/unfrozen/cgroup.freeze
echo 1 > /sys/fs/cgroup/frozen/cgroup.freeze
echo 1 > /sys/fs/cgroup/unfrozen/cgroup.freeze
chmod 0777 /sys/devices/system



# WebUI 已迁移为 KernelSU 标准接口：管理器直接加载 webroot/index.html（ksu.exec 桥）
# 旧版 Python 本地服务器方案（webui/server.py）已移除

# 功耗记录守护（两形态常驻，数据写入 /data/adb/colorFC_store/pwlog）
chmod 0755 "$MODULE_PATH/pwlogd.sh" 2>/dev/null
nohup sh "$MODULE_PATH/pwlogd.sh" >/dev/null 2>&1 &
