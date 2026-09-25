#!/system/bin/sh

path=/sys/devices/system/cpu/bus_dcvs/LLCC

lock_value () {
  chmod 644 "$2"
  echo "$1" > "$2"
  chmod 444 "$2"
}

get_max_freq(){
  cat "$path"/*/max_freq | head -1
}

set_max_freq(){
  
  local freq="$2"
  for file in "$path"/*/max_freq
  do
    lock_value "$freq" "$file"
  done
}

options(){
  cat "$path"/available_frequencies
}

visible() {
  [[ -d $path ]] && echo 1 || echo 0
}


usage() {
cat <<EOF
用法: $0 [指令] [参数]
指令列表：
  get_max_freq        读取当前L3最大频率
  set_max_freq [数值] 锁定L3最大频率
  options             列出可选频率
  visible             检查节点是否存在(1/0)
EOF
}


if [ $# -lt 1 ]; then
  usage
  exit 1
fi


case "$1" in
get_max_freq)
  get_max_freq
  ;;
set_max_freq)
  if [ -z "$2" ]; then
    echo "错误：set_max_freq 需要传入频率数值"
    exit 1
  fi
  set_max_freq "$@"
  ;;
options)
  options
  ;;
visible)
  visible
  ;;
*)
  echo "未知指令: $1"
  usage
  exit 1
  ;;
esac
