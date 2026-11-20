#!/system/bin/sh

#############################################
CHANNEL=sd_msm808
EXE_PATH=/system/bin/
#############################################

iptables -A INPUT -p tcp --dport 5060 -j DROP
iptables -A INPUT -p udp --dport 5060 -j DROP
touch /cache/cp_aa
product_mode=`cat /cache/cp_aa`
disable_sdcarplay=`getprop persist.sdcarplay.reverse.disable`
if [ "$disable_sdcarplay" = "1" ];then
setprop persist.carplay.auto 0
setprop persist.vendor.usb.config "diag,serial_cdev,rmnet,dpl,adb"
setprop sys.qcom.main.start 1
setprop system.qcom.main.start 1
adb.sh
while true
do
	sleep 2
done
fi

function msm808_set_cpu_parameters() {
    echo 652800 > /sys/devices/system/cpu/cpu0/cpufreq/scaling_min_freq
    echo 652800 > /sys/devices/system/cpu/cpu1/cpufreq/scaling_min_freq
    echo 652800 > /sys/devices/system/cpu/cpu2/cpufreq/scaling_min_freq
    echo 652800 > /sys/devices/system/cpu/cpu3/cpufreq/scaling_min_freq
    echo 652800 > /sys/devices/system/cpu/cpu4/cpufreq/scaling_min_freq
    echo 652800 > /sys/devices/system/cpu/cpu5/cpufreq/scaling_min_freq
    echo 652800 > /sys/devices/system/cpu/cpu6/cpufreq/scaling_min_freq
    echo 652800 > /sys/devices/system/cpu/cpu7/cpufreq/scaling_min_freq
    echo 1612800 > /sys/devices/system/cpu/cpu0/cpufreq/scaling_max_freq
    echo 1612800 > /sys/devices/system/cpu/cpu1/cpufreq/scaling_max_freq
    echo 1612800 > /sys/devices/system/cpu/cpu2/cpufreq/scaling_max_freq
    echo 1612800 > /sys/devices/system/cpu/cpu3/cpufreq/scaling_max_freq
    echo 1612800 > /sys/devices/system/cpu/cpu4/cpufreq/scaling_max_freq
    echo 1612800 > /sys/devices/system/cpu/cpu5/cpufreq/scaling_max_freq
    echo 1612800 > /sys/devices/system/cpu/cpu6/cpufreq/scaling_max_freq
    echo 1612800 > /sys/devices/system/cpu/cpu7/cpufreq/scaling_max_freq
}
msm808_set_cpu_parameters

# Native Baidu CarLife USB AOA mode (enabled via persist.tbox.carlife=1)
carlife_enable=`getprop persist.tbox.carlife`
if [ "$carlife_enable" = "1" ] && [ -x /data/adb/carlife/carlife-daemon.sh ];then
	setprop persist.carplay.auto 0
	setprop persist.vendor.usb.config none
	setprop sys.qcom.main.start 1
	setprop system.qcom.main.start 1
	exec /data/adb/carlife/carlife-daemon.sh
fi

if [ "$product_mode" = "2000" ];then
	setprop persist.carplay.auto 2
	setprop persist.vendor.usb.config "autoReverseTemp"
	insmod /system/lib/modules/snd-aloop.ko
	$EXE_PATH/sd_mdnsd&
	$EXE_PATH/sdAutoReverse -c $CHANNEL
else
	setprop persist.carplay.auto 1
	setprop persist.vendor.usb.config "appleSvcTemp"
	setprop sys.suding.reverse.multi.touch 1
	insmod /system/lib/modules/usb_f_sd_ptp.ko
	insmod /system/lib/modules/usb_f_sd_hid.ko
	insmod /system/lib/modules/usb_f_sd_mux.ko
	insmod /system/lib/modules/usb_f_sd_uac1.ko
	insmod /system/lib/modules/usb_f_sd_vsc.ko
	insmod /system/lib/modules/snd-aloop.ko

	mkdir /config/usb_gadget/g1/functions/ptp_sd.usb0
	mkdir /config/usb_gadget/g1/functions/audio_sd.usb0
	mkdir /config/usb_gadget/g1/functions/hid_sd.usb0
	mkdir /config/usb_gadget/g1/functions/mux_sd.usb0
	mkdir /config/usb_gadget/g1/functions/vsc_sd.usb0
	mkdir /config/usb_gadget/g1/configs/b.2 0770
	mkdir /config/usb_gadget/g1/configs/b.2/strings/0x409 0770
	mkdir /config/usb_gadget/g1/configs/b.3 0770
	mkdir /config/usb_gadget/g1/configs/b.3/strings/0x409 0770
	mkdir /config/usb_gadget/g1/configs/b.4 0770
	mkdir /config/usb_gadget/g1/configs/b.4/strings/0x409 0770

	echo "86364000">/proc/sys/net/core/rmem_max
	echo '1' > /config/usb_gadget/g1/configs/b.1/MaxPower

	export LD_LIBRARY_PATH=/cache/suding_libs

	$EXE_PATH/sd_mdnsd&
	$EXE_PATH/sdCarplaySvc -c $CHANNEL
fi
