package top.niunaijun.blackbox.core.system.tls;

import android.os.Binder;
import android.os.Process;
import android.app.ActivityManager;
import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.system.BProcessManagerService;

public final class TlsServiceSecurity {
    private TlsServiceSecurity() {}

    public static void enforceMainCaller() {
        int pid = Binder.getCallingPid();
        int callerUid = Binder.getCallingUid();
        int hostUid = Process.myUid();
        String processName = readProcessName(pid);
        String hostPackage = BlackBoxCore.getHostPkg();
        if (callerUid != hostUid || hostPackage == null || !hostPackage.equals(processName)) {
            throw new SecurityException("TLS control caller is not MAIN (pid=" + pid +
                    ", uidMatch=" + (callerUid == hostUid) + ", process=" + processName +
                    ", host=" + hostPackage + ")");
        }
    }

    /**
     * Android 16 can deny an app process access to a sibling process's /proc/PID/cmdline.
     * Resolve the kernel-reported process name through ActivityManager in that case. The
     * caller UID check remains mandatory, and the system-reported name must still be exact MAIN.
     */
    private static String readProcessName(int pid) {
        String cmdline = readCmdline(pid);
        if (!cmdline.isEmpty()) return cmdline;
        try {
            Context context = BlackBoxCore.getContext();
            if (context == null) return "";
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) return "";
            java.util.List<ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
            if (processes == null) return "";
            for (ActivityManager.RunningAppProcessInfo process : processes) {
                if (process.pid == pid) return process.processName == null ? "" : process.processName;
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    public static BProcessManagerService.NetworkCaller requireGuestCaller() {
        int pid = Binder.getCallingPid();
        BProcessManagerService.NetworkCaller caller = BProcessManagerService.get().resolveNetworkCaller(pid);
        if (caller == null || caller.pid != pid || Binder.getCallingUid() != Process.myUid()) {
            throw new SecurityException("TLS route caller is not an attested guest");
        }
        return caller;
    }

    public static long readStartTicks(int pid) {
        try {
            String stat = new String(readBounded(new File("/proc/" + pid + "/stat"), 8192),
                    StandardCharsets.UTF_8);
            int close = stat.lastIndexOf(')');
            if (close < 0 || close + 2 >= stat.length()) return -1;
            String[] fields = stat.substring(close + 2).trim().split("\\s+");
            if (fields.length <= 19) return -1;
            long ticks = Long.parseLong(fields[19]);
            return ticks > 0 ? ticks : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static String readCmdline(int pid) {
        try {
            byte[] bytes = readBounded(new File("/proc/" + pid + "/cmdline"), 4096);
            int length = 0;
            while (length < bytes.length && bytes[length] != 0) length++;
            return new String(bytes, 0, length, StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static byte[] readBounded(File file, int limit) throws IOException {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 256))) {
            byte[] buffer = new byte[256];
            int total = 0;
            while (total < limit) {
                int count = input.read(buffer, 0, Math.min(buffer.length, limit - total));
                if (count < 0) break;
                if (count == 0) continue;
                output.write(buffer, 0, count);
                total += count;
            }
            if (input.read() >= 0) throw new IOException("proc file exceeds limit");
            return output.toByteArray();
        }
    }
}
