ANDROID_SDK=`getprop ro.build.version.sdk`

if [[ "$ANDROID_SDK" -gt 30 ]]
then
  dumpsys display | grep -A 24 'mSfDisplayModes=' | grep ' DisplayMode{id=' | cut -f2 -d '{' | while read row
  do
    if [[ -n "$row" ]]; then
      echo $row | tr "," "\n" | while read col
      do
        case "$col" in
          "id="*)
            echo -n $(echo ${col:3}'|')
          ;;
          "width="*)
            echo -n $(echo ${col:6})
          ;;
          "height="*)
            echo -n x$(echo ${col:7})
          ;;
          "refreshRate="*)
            echo ' '$(echo ${col:12} | cut -f1 -d '.')Hz
          ;;
          "vsyncRate="*)
            echo ' '$(echo ${col:10} | cut -f1 -d '.')Hz
          ;;
        esac
      done
    fi
  done
else
  i=0
  dumpsys display | grep -A 1 'mSupportedModesByDisplay' | tail -1 | tr "}" "\n" | cut -f2 -d '{' | while read row
  do
    if [[ -n "$row" ]]; then
      echo -n "$i|"
      echo $row | tr "," "\n" | while read col
      do
        case "$col" in
          "width="*)
            echo -n $(echo ${col:6})
          ;;
          "height="*)
            echo -n x$(echo ${col:7})
          ;;
          "fps="*)
            echo ' '$(echo ${col:4} | cut -f1 -d '.')Hz
          ;;
        esac
      done
      i=$((i+1))
    fi
  done
fi
