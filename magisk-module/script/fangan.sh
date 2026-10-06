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
        FANGAN=a
        FANGAN_GOV=sugov_next
    else
        FANGAN=c
        if echo "$govs" | grep -qw walt; then
            FANGAN_GOV=walt
        elif echo "$govs" | grep -qw conservative; then
            FANGAN_GOV=conservative
        else
            FANGAN_GOV=$(echo "$govs" | tr ' ' '\n' | head -1)
        fi
    fi
}

if [ "${0##*/}" = "fangan.sh" ] && [ -z "$FANGAN_SOURCED" ]; then
    fangan_detect
    echo "$FANGAN $FANGAN_GOV"
fi
