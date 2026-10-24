package top.niunaijun.blackbox.fake.service;

import android.app.IServiceConnection;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import black.android.app.BRIServiceConnectionO;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.compat.BuildCompat;

/**
 * Play services checks that the package in GetServiceRequest belongs to the
 * calling uid. The guest package does not, so the broker throws
 * "Unknown calling package name" and the game's Firebase native code aborts.
 * Rewrite that string to the host package on the broker binder.
 */
public final class GmsCallingIdentity {
    private static final String TAG = "GmsCallingIdentity";
    private static boolean sReplyWrapBroken;
    private static Method sWriteStringBackup;
    private static Method sWriteString8Backup;
    private static String sGuestPackage;
    private static String sHostPackage;

    private GmsCallingIdentity() {
    }

    public static boolean isGms(Intent intent) {
        if (intent == null) return false;
        ComponentName component = intent.getComponent();
        if (component != null && "com.google.android.gms".equals(component.getPackageName())) {
            return true;
        }
        if ("com.google.android.gms".equals(intent.getPackage())) return true;
        String action = intent.getAction();
        return action != null && action.startsWith("com.google.android.gms");
    }

    /**
     * Measurement writes the guest package into its own binder parcel. Rewrite that
     * string only. Context.getPackageName stays the guest name so Crashlytics can
     * still find its Firebase component.
     */
    public static void install() {
        if (sWriteStringBackup != null) return;
        sGuestPackage = BActivityThread.getAppPackageName();
        sHostPackage = BlackBoxCore.getHostPkg();
        sWriteStringBackup = hookParcelWrite("writeString");
        sWriteString8Backup = hookParcelWrite("writeString8");
    }

    private static Method hookParcelWrite(String name) {
        try {
            Method original = Parcel.class.getDeclaredMethod(name, String.class);
            Method replacement = WriteStringHook.class.getDeclaredMethod(name, String.class);
            Method backup = WriteStringBackup.class.getDeclaredMethod(name, String.class);
            if (!top.niunaijun.blackbox.utils.ArtMethodHook.hook(original, replacement, backup)) {
                Slog.w(TAG, name + " hook failed");
                return null;
            }
            Slog.i(TAG, name + " hook installed");
            return backup;
        } catch (Throwable e) {
            Slog.w(TAG, name + " hook failed: " + e.getMessage());
            return null;
        }
    }

    public static void writePackage(Object parcel, String value, boolean utf8) {
        if (sGuestPackage != null && sHostPackage != null && sGuestPackage.equals(value) && fromMeasurement()) {
            value = sHostPackage;
            Slog.d(TAG, "measurement package " + sGuestPackage + " -> " + sHostPackage);
        }
        Method backup = sWriteStringBackup;
        if (utf8) return;
        try {
            top.niunaijun.blackbox.utils.ArtMethodHook.call(backup, parcel, value);
        } catch (Throwable e) {
            Slog.w(TAG, "writeString backup failed: " + e.getMessage());
        }
    }

    public static void writePackage8(Object parcel, String value) {
        if (sGuestPackage != null && sHostPackage != null && sGuestPackage.equals(value) && fromMeasurement()) {
            value = sHostPackage;
        }
        try {
            if (sWriteString8Backup != null) {
                top.niunaijun.blackbox.utils.ArtMethodHook.call(sWriteString8Backup, parcel, value);
            }
        } catch (Throwable e) {
            Slog.w(TAG, "writeString8 backup failed: " + e.getMessage());
        }
    }

    /** Stack file names from dynamite look like :com.google.android.gms.dynamite_measurementdynamite@... */
    private static boolean fromMeasurement() {
        StackTraceElement[] stack = new Throwable().getStackTrace();
        boolean hit = false;
        for (int i = 0; i < stack.length; i++) {
            String type = stack[i].getClassName();
            String file = stack[i].getFileName();
            if (type.contains("crashlytics") || (file != null && file.contains("crashlytics"))) return false;
            if (type.contains("measurement") || (file != null && file.contains("measurement"))) hit = true;
        }
        return hit;
    }

    public static final class WriteStringHook {
        public void writeString(String value) {
            GmsCallingIdentity.writePackage(this, value, false);
        }

        public void writeString8(String value) {
            GmsCallingIdentity.writePackage8(this, value);
        }
    }

    public static final class WriteStringBackup {
        public void writeString(String value) {
        }

        public void writeString8(String value) {
        }
    }

    public static IServiceConnection wrap(IServiceConnection base) {
        if (base == null) return null;
        return new Connection(base);
    }

    private static final class Connection extends IServiceConnection.Stub {
        private final IServiceConnection base;

        Connection(IServiceConnection base) {
            this.base = base;
        }

        @Override
        public void connected(ComponentName name, IBinder service) throws RemoteException {
            connected(name, service, false);
        }

        public void connected(ComponentName name, IBinder service, boolean dead) throws RemoteException {
            IBinder delivered = service;
            if (service != null) {
                try {
                    String descriptor = service.getInterfaceDescriptor();
                    if (descriptor != null && descriptor.contains("com.google.android.gms")) {
                        delivered = new BrokerBinder(service);
                        Slog.d(TAG, "wrapping " + descriptor);
                    }
                } catch (RemoteException ignored) {
                }
            }
            if (BuildCompat.isOreo()) {
                BRIServiceConnectionO.get(base).connected(name, delivered, dead);
            } else {
                base.connected(name, delivered);
            }
        }
    }

    private static final class BrokerBinder extends Binder {
        private final IBinder remote;

        BrokerBinder(IBinder remote) {
            this.remote = remote;
        }

        @Override
        public String getInterfaceDescriptor() {
            try {
                return remote.getInterfaceDescriptor();
            } catch (RemoteException e) {
                return null;
            }
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            Parcel rewritten = rewrite(data);
            try {
                boolean result = remote.transact(code, rewritten != null ? rewritten : data, reply, flags);
                wrapReplyBinders(reply);
                return result;
            } finally {
                if (rewritten != null) rewritten.recycle();
            }
        }
    }

    private static Parcel rewrite(Parcel source) {
        String guest = BActivityThread.getAppPackageName();
        String host = BlackBoxCore.getHostPkg();
        if (guest == null || host == null || guest.equals(host)) return null;
        int start = source.dataPosition();
        try {
            int header = findSafeParcelHeader(source);
            if (header < 0) {
                source.setDataPosition(start);
                Parcel spliced = splicePackageString(source, guest, host);
                if (spliced != null) {
                    Slog.d(TAG, "calling package " + guest + " -> " + host);
                    return spliced;
                }
                Slog.w(TAG, "GetServiceRequest header not found");
                return null;
            }
            ClassLoader loader = BActivityThread.getApplication().getClassLoader();
            Class<?> type = loader.loadClass("com.google.android.gms.common.internal.GetServiceRequest");
            Parcelable.Creator<?> creator = (Parcelable.Creator<?>) type.getField("CREATOR").get(null);
            source.setDataPosition(header);
            Object request = creator.createFromParcel(source);
            if (!replacePackage(request, guest, host)) {
                source.setDataPosition(start);
                Slog.w(TAG, "package field not found on " + request.getClass().getName());
                return null;
            }
            Parcel out = Parcel.obtain();
            out.appendFrom(source, 0, header);
            ((Parcelable) request).writeToParcel(out, 0);
            out.setDataPosition(0);
            Slog.d(TAG, "calling package " + guest + " -> " + host);
            return out;
        } catch (Throwable e) {
            Slog.w(TAG, "rewrite failed: " + e.getClass().getSimpleName() + " " + e.getMessage());
            try {
                source.setDataPosition(start);
            } catch (RuntimeException ignored) {
            }
            return null;
        }
    }

    private static int findSafeParcelHeader(Parcel source) {
        int size = source.dataSize();
        int limit = firstBinder(source, size);
        for (int offset = 0; offset + 4 <= limit; offset += 4) {
            byte[] raw = sliceBytes(source, offset, 4);
            if (raw == null || raw.length < 4) continue;
            int value = (raw[0] & 0xff) | ((raw[1] & 0xff) << 8) | ((raw[2] & 0xff) << 16) | ((raw[3] & 0xff) << 24);
            if ((value & 0xffff) == 0x4f45) return offset;
        }
        return -1;
    }

    /**
     * Measurement puts the guest package in a string after a binder, so it is not a
     * GetServiceRequest. Binder objects cannot be read with readInt. Locate them by
     * a slice that refuses to marshall, then splice the UTF-16 package string.
     */
    private static Parcel splicePackageString(Parcel source, String guest, String host) {
        int size = source.dataSize();
        if (size < 4 || guest.length() > 0xffff) return null;
        boolean[] blocked = new boolean[size];
        int pos = 0;
        while (pos + 4 <= size) {
            int binder = firstBinder(source, size, pos);
            if (binder < 0) break;
            int end = Math.min(size, binder + 24);
            for (int i = binder; i < end; i++) blocked[i] = true;
            pos = end;
        }
        byte[] utf16 = guest.getBytes(StandardCharsets.UTF_16LE);
        byte[] utf8 = guest.getBytes(StandardCharsets.UTF_8);
        List<int[]> hits = new ArrayList<>();
        for (int offset = 0; offset + 4 <= size; offset += 4) {
            byte[] lenRaw = sliceBytes(source, offset, 4);
            if (lenRaw == null || lenRaw.length < 4) continue;
            int len = (lenRaw[0] & 0xff) | ((lenRaw[1] & 0xff) << 8)
                    | ((lenRaw[2] & 0xff) << 16) | ((lenRaw[3] & 0xff) << 24);
            if (len != guest.length()) continue;
            int utf16Bytes = (len + 1) * 2;
            int utf8Bytes = len + 1;
            int[] widths = new int[]{utf16Bytes, utf8Bytes};
            int[] kinds = new int[]{0, 1};
            byte[][] needles = new byte[][]{utf16, utf8};
            for (int kind = 0; kind < 2; kind++) {
                int byteLen = widths[kind];
                int padded = (byteLen + 3) & ~3;
                int dataPos = offset + 4;
                if (dataPos + byteLen > size) continue;
                byte[] raw = sliceBytes(source, dataPos, byteLen);
                if (raw == null || raw.length < byteLen) continue;
                byte[] needle = needles[kind];
                boolean match = true;
                for (int i = 0; i < needle.length; i++) {
                    if (raw[i] != needle[i]) match = false;
                }
                if (raw[needle.length] != 0 || (kind == 0 && raw[needle.length + 1] != 0)) match = false;
                if (match) {
                    hits.add(new int[]{offset, dataPos + padded, kinds[kind]});
                    break;
                }
            }
        }
        if (hits.isEmpty()) return null;
        int[] written = new int[2];
        for (int kind = 0; kind < 2; kind++) {
            Parcel measured = Parcel.obtain();
            if (kind == 0) measured.writeString(host);
            else writeUtf8String(measured, host);
            written[kind] = measured.dataSize();
            measured.recycle();
        }
        // Bundle and byte-array lengths count the bytes after the length int. A shorter
        // package shifts that payload, so those lengths have to shrink with it.
        Parcel edited = Parcel.obtain();
        edited.appendFrom(source, 0, size);
        for (int offset = 0; offset + 4 <= size; offset += 4) {
            if (blocked[offset]) continue;
            byte[] raw = sliceBytes(source, offset, 4);
            if (raw == null || raw.length < 4) continue;
            int value = (raw[0] & 0xff) | ((raw[1] & 0xff) << 8)
                    | ((raw[2] & 0xff) << 16) | ((raw[3] & 0xff) << 24);
            if (value <= 0 || (value & 3) != 0) continue;
            int coverStart = offset + 4;
            int coverEnd = coverStart + value;
            if (coverEnd > size) continue;
            int adjust = 0;
            for (int[] hit : hits) {
                if (hit[0] >= coverStart && hit[1] <= coverEnd) {
                    adjust += written[hit[2]] - (hit[1] - hit[0]);
                }
            }
            if (adjust != 0 && value + adjust >= 0) {
                edited.setDataPosition(offset);
                edited.writeInt(value + adjust);
            }
        }
        Parcel out = Parcel.obtain();
        int cursor = 0;
        for (int[] hit : hits) {
            if (hit[0] < cursor) continue;
            out.appendFrom(edited, cursor, hit[0] - cursor);
            if (hit[2] == 0) out.writeString(host);
            else writeUtf8String(out, host);
            cursor = hit[1];
        }
        if (cursor < size) out.appendFrom(edited, cursor, size - cursor);
        out.setDataPosition(0);
        edited.recycle();
        source.setDataPosition(0);
        return out;
    }

    private static int firstBinder(Parcel source, int size) {
        return firstBinder(source, size, 0);
    }

    private static int firstBinder(Parcel source, int size, int from) {
        for (int offset = from; offset + 4 <= size; offset += 4) {
            if (sliceBytes(source, offset, 4) == null) return offset;
        }
        return -1;
    }

    /** Parcel.writeString8 is hidden on the engine stub. Same layout: int32 chars, UTF-8, NUL, pad. */
    private static void writeUtf8String(Parcel parcel, String value) {
        byte[] text = value.getBytes(StandardCharsets.UTF_8);
        int padded = ((text.length + 1) + 3) & ~3;
        byte[] raw = new byte[4 + padded];
        int len = text.length;
        raw[0] = (byte) len;
        raw[1] = (byte) (len >> 8);
        raw[2] = (byte) (len >> 16);
        raw[3] = (byte) (len >> 24);
        System.arraycopy(text, 0, raw, 4, text.length);
        Parcel tmp = Parcel.obtain();
        tmp.unmarshall(raw, 0, raw.length);
        tmp.setDataPosition(0);
        parcel.appendFrom(tmp, 0, raw.length);
        tmp.recycle();
    }

    /** Bytes of a binder-free range, or null when the range holds a binder object. */
    private static byte[] sliceBytes(Parcel source, int offset, int length) {
        if (length <= 0) return new byte[0];
        Parcel slice = Parcel.obtain();
        try {
            slice.appendFrom(source, offset, length);
            return slice.marshall();
        } catch (RuntimeException e) {
            return null;
        } finally {
            slice.recycle();
        }
    }

    /** Measurement calls the binder returned by the broker. Wrap those too. */
    private static void wrapReplyBinders(Parcel reply) {
        if (reply == null || sReplyWrapBroken) return;
        try {
            Field objects = Parcel.class.getDeclaredField("mObjects");
            objects.setAccessible(true);
            Object value = objects.get(reply);
            if (!(value instanceof Object[])) return;
            Object[] binders = (Object[]) value;
            for (int i = 0; i < binders.length; i++) {
                if (!(binders[i] instanceof IBinder) || binders[i] instanceof BrokerBinder) continue;
                IBinder binder = (IBinder) binders[i];
                String descriptor = null;
                try {
                    descriptor = binder.getInterfaceDescriptor();
                } catch (RemoteException ignored) {
                }
                if (descriptor != null && descriptor.contains("com.google.android.gms")) {
                    binders[i] = new BrokerBinder(binder);
                    Slog.d(TAG, "wrap reply " + descriptor);
                }
            }
        } catch (Throwable e) {
            sReplyWrapBroken = true;
        }
    }

    /** One line showing how the guest package is actually stored when the length scan misses. */
    private static void notePackageBytes(Parcel source, int size, String guest) {
        byte[] needle = "com.gameloft".getBytes(StandardCharsets.UTF_8);
        int found = -1;
        for (int offset = 0; offset + needle.length <= size; offset++) {
            byte[] raw = sliceBytes(source, offset, needle.length);
            if (raw == null || raw.length < needle.length) continue;
            boolean match = true;
            for (int i = 0; i < needle.length; i++) {
                if (raw[i] != needle[i]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                found = offset;
                break;
            }
        }
        if (found < 0) {
            StringBuilder head = new StringBuilder();
            for (int offset = 0; offset + 4 <= Math.min(size, 80); offset += 4) {
                byte[] raw = sliceBytes(source, offset, 4);
                if (raw == null) {
                    head.append("BIND");
                    break;
                }
                for (byte b : raw) head.append(String.format("%02x", b));
            }
            Slog.w(TAG, "guest package bytes not in parcel size=" + size + " head=" + head);
            return;
        }
        int from = Math.max(0, found - 12);
        byte[] pre = sliceBytes(source, from, found - from);
        StringBuilder hex = new StringBuilder();
        if (pre != null) {
            for (byte b : pre) {
                hex.append(String.format("%02x", b));
            }
        }
        Slog.w(TAG, "guest package bytes at " + found + " pre=" + hex);
    }

    private static boolean replacePackage(Object request, String guest, String host) {
        boolean changed = false;
        for (Field field : request.getClass().getDeclaredFields()) {
            if (field.getType() != String.class) continue;
            field.setAccessible(true);
            try {
                Object value = field.get(request);
                if (guest.equals(value)) {
                    field.set(request, host);
                    changed = true;
                    Slog.d(TAG, "field " + field.getName() + " " + guest + " -> " + host);
                } else if (value instanceof String) {
                    Slog.d(TAG, "field " + field.getName() + "=" + value);
                }
            } catch (IllegalAccessException ignored) {
            }
        }
        return changed;
    }
}
