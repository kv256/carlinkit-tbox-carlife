#!/system/bin/sh

#############################################
CHANNEL=sd_msm808
EXE_PATH=/system/bin/
#############################################

# Native Baidu CarLife USB AOA mode (enabled via persist.tbox.carlife=1), checked first:
# the head unit only auto-starts CarLife from a one-shot scan at its boot, so the daemon
# must claim USB within ~1 s of init creating the gadget. The stock prelude below
# (iptables, CPU limits) takes >1 s at boot and is run in the background for this mode.
# USB ADB mode (persist.sdcarplay.reverse.disable=1) and CarPlay keep the stock flow.
if [ "`getprop persist.sdcarplay.reverse.disable`" != "1" ] && [ "`getprop persist.tbox.carlife`" = "1" ] && [ -x /data/adb/carlife/carlife-daemon.sh ];then
	setprop sys.usb.configfs 2
	setprop sys.usb.config carlife
	setprop persist.carplay.auto 0
	# "carlife" matches no init trigger: with "none", init's "sys.usb.config=none && configfs=1"
	# action unbinds the gadget that post-fs-data.sh publishes at ~6.5 s during the next boot.
	setprop persist.vendor.usb.config carlife
	setprop sys.qcom.main.start 1
	setprop system.qcom.main.start 1
	(
		iptables -A INPUT -p tcp --dport 5060 -j DROP
		iptables -A INPUT -p udp --dport 5060 -j DROP
		touch /cache/cp_aa
		for c in 0 1 2 3 4 5 6 7; do
			echo 652800 > /sys/devices/system/cpu/cpu$c/cpufreq/scaling_min_freq
			echo 1612800 > /sys/devices/system/cpu/cpu$c/cpufreq/scaling_max_freq
		done
	) &
	exec /data/adb/carlife/carlife-daemon.sh
fi

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

# (CarLife mode is dispatched at the top of this script.)

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
