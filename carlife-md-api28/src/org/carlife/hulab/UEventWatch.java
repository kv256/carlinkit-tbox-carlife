package org.carlife.hulab;

import android.os.SystemClock;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Pure-Java Android UEventObserver watcher for kernel AOA ACCESSORY=START uevents.
 * Eliminates NDK/C cross-compilation requirements on Android 9+.
 *
 * Usage:
 *   CLASSPATH=carlife-md-api28.jar app_process /system/bin org.carlife.hulab.UEventWatch <marker_path> [timeout_sec]
 */
public final class UEventWatch {
    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) {
            System.err.println("Usage: UEventWatch <marker_path> [timeout_sec]");
            System.exit(64);
        }
        final File marker = new File(args[0]);
        final int timeoutSec = args.length == 2 ? Integer.parseInt(args[1]) : 0;
        final CountDownLatch matched = new CountDownLatch(1);
        final String[] matchedLine = new String[1];

        android.os.UEventObserver observer = new android.os.UEventObserver() {
            @Override
            public void onUEvent(android.os.UEventObserver.UEvent event) {
                String acc = event.get("ACCESSORY");
                String devpath = event.get("DEVPATH");
                String seq = event.get("SEQNUM");
                String state = event.get("USB_STATE");
                long now = SystemClock.uptimeMillis();
                String line = "UEVENT uptime_ms=" + now + " DEVPATH=" + devpath
                        + " ACCESSORY=" + acc + " USB_STATE=" + state + " SEQNUM=" + seq;
                System.out.println(line);
                System.out.flush();
                if ("START".equals(acc)) {
                    matchedLine[0] = line + "\n";
                    matched.countDown();
                }
            }
        };

        observer.startObserving("ACCESSORY=START");
        System.out.println("READY uptime_ms=" + SystemClock.uptimeMillis());
        System.out.flush();

        boolean got;
        if (timeoutSec > 0) {
            got = matched.await(timeoutSec, TimeUnit.SECONDS);
        } else {
            matched.await();
            got = true;
        }
        observer.stopObserving();

        if (!got) {
            System.exit(2);
        }
        File tmp = new File(marker.getParentFile(), "." + marker.getName() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            fos.write(matchedLine[0].getBytes(StandardCharsets.UTF_8));
            fos.getFD().sync();
        }
        if (!tmp.renameTo(marker)) {
            throw new RuntimeException("Failed to publish marker " + marker);
        }
        System.exit(0);
    }
}
