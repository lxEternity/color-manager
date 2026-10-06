sleep 10

until [ -d /data ]; do
sleep 1
done
sleep 1
BASEDIR="$(dirname $(readlink -f "$0"))"
. $BASEDIR/script/quanj.sh
sleep 1
mosdz1="$MODULE_PATH/files/pand"
mosdz="$MODULE_PATH/files"

sleep 1
if [ -f $MODULE_PATH/script/fz ]; then
sleep 20
else
cp -af $MODULE_PATH/config/powercfg.json /data/powercfg.json
sleep 10
fi

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
chmod 777 $MODULE_PATH/C/*
chmod 777 $MODULE_PATH/bin/* 2>/dev/null
chmod 777 $MODULE_PATH/config/*
chmod 777 $MODULE_PATH/*
chmod 0755 $MODULE_PATH/webroot/*.sh 2>/dev/null

sleep 1
cat > /data/powercfg.sh <<'PCEOF'
#!/system/bin/sh
MODULE_PATH="__MODPATH__"
if [ "$kzlx" != "1" ]; then
    ms="$1"
    kzlx="${2:-0}"
    if [ "$kzlx" = "manual" ]; then
        for f in /sdcard/Android/qingtd/*.conf; do
            [ -f "$f" ] || continue
            grep -q '^moren=' "$f" && sed -i 's/^moren=.*/moren='"$ms"'/' "$f" || echo "moren=$ms" >> "$f"
        done 2>/dev/null
    elif [ "$kzlx" != "1" ]; then
        touch /sdcard/Android/qingtd/stop 2>/dev/null
    fi
fi
sh "$MODULE_PATH/script/main.sh" "$ms" "$MODULE_PATH/files" "$(cat "$MODULE_PATH/files/peiz" 2>/dev/null)"
PCEOF
sed -i "s|__MODPATH__|$MODULE_PATH|g" /data/powercfg.sh
chmod 777 /data/powercfg.sh

until [ -d /sys/devices/system/cpu/cpufreq/ ]; do
sleep 1
done

filePath="$mokml/cur_powermode.txt"
dir=$mokml
sleep 1
mkdir $dir
sleep 1
lm=$(cat $filePath 2>/dev/null)
case "$lm" in
    powersave|balance|performance|fast) echo "$lm" > $MODULE_PATH/files/lastmode;;
esac
touch $filePath
echo "powersave" > $filePath

sleep 1
wenbp='未执行模式切换(可能开机自切换失败)'
mokdiz="$mokml/cur_powermode.txt"
echo $wenbp > $mokdiz
sleep 1
if [ -f $mokml/动态模式切换.conf ]; then
sleep 1
else
cp -af $MODULE_PATH/$mingc/动态模式切换.conf $mokml/
fi
sleep 1
mkdir $mosdz
touch $mosdz1
echo 1 > $mosdz1
sleep 1
touch $mosdz/qhz
echo 1 > $mosdz/qhz
touch $mosdz/baom
echo 1 > $mosdz/baom

sleep 1
. $MODULE_PATH/script/fangan.sh
fangan_detect
for file in /sys/devices/system/cpu/cpufreq/policy*
do
chmod 777 $file/scaling_governor
echo "$FANGAN_GOV" > $file/scaling_governor
done

for file in /sys/devices/system/cpu/cpufreq/policy*
do
    for line in `cat $file/walt/target_loads`
    do
        bf=${line#*:}

        if [ $bf -le 0 ]; then
        echo 0 > $mosdz1

        else

          if [ $(cat $mosdz1) -le 0 ]; then
             echo 0 > $mosdz1

          else
             echo 1 > $mosdz1
          fi
        fi

    done
done

sleep 1
until [ -d $mokml ]; do
sleep 1
done

touch $mokml/fps.txt
sh $MODULE_PATH/script/display_modes.sh > $mokml/fps.txt

rm -rf $mokml/stop
touch $rizhidz

echo "[$(date '+%T')] 动态启动失败" > $rizhidz

killall qingtd.sh

nohup $MODULE_PATH/script/qingtd.sh >/dev/null 2>&1 &

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

chmod 0755 "$MODULE_PATH/pwlogd.sh" 2>/dev/null
nohup sh "$MODULE_PATH/pwlogd.sh" >/dev/null 2>&1 &
