package top.niunaijun.blackbox.fake.service;

import android.content.Context;
import android.os.Bundle;
import java.lang.reflect.Method;
import java.util.Collections;
import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.utils.DebugStatePolicy;
import top.niunaijun.blackbox.utils.Reflector;

/** USB has a per-process disconnected view; physical USB and the host ADB channel remain intact. */
public final class IUsbManagerProxy extends BinderInvocationStub {
    public IUsbManagerProxy() { super(BRServiceManager.get().getService(Context.USB_SERVICE)); }

    @Override protected Object getWho() {
        try {
            return Reflector.on("android.hardware.usb.IUsbManager$Stub")
                    .method("asInterface", android.os.IBinder.class)
                    .call(BRServiceManager.get().getService(Context.USB_SERVICE));
        } catch (Exception e) { throw new IllegalStateException("Cannot obtain USB service", e); }
    }

    @Override protected void inject(Object base, Object proxy) {
        replaceSystemService(Context.USB_SERVICE);
        try {
            Object manager = BlackBoxCore.getContext().getSystemService(Context.USB_SERVICE);
            if (manager != null) Reflector.with(manager).field("mService").set(proxy);
        } catch (Exception e) { throw new IllegalStateException("Cannot install USB view", e); }
    }

    @Override public boolean isBadEnv() { return false; }

    @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (DebugStatePolicy.isEnabled()) {
            switch (method.getName()) {
                case "getDeviceList":
                    if (args != null) for (Object arg : args) if (arg instanceof Bundle) ((Bundle) arg).clear();
                    return null;
                case "getCurrentFunctions": return 4L; // MTP configured, ADB bit absent.
                case "getCurrentFunction": return "mtp";
                case "isFunctionEnabled": return args != null && args.length > 0 && "mtp".equals(args[0]);
                case "getCurrentUsbSpeed": return -1;
                case "getPorts":
                    return method.getReturnType().isArray()
                            ? java.lang.reflect.Array.newInstance(method.getReturnType().getComponentType(), 0)
                            : Collections.emptyList();
                case "hasDevicePermission":
                case "hasAccessoryPermission": return false;
                case "getCurrentAccessory":
                case "getPortStatus":
                case "openDevice":
                case "openAccessory":
                case "getControlFd":
                case "setCurrentFunction":
                case "setCurrentFunctions":
                case "setScreenUnlockedFunctions": return null;
                default: break;
            }
        }
        // Calls forwarded in a debugging profile still have the real host Binder UID.
        String guestPackage = BActivityThread.getAppPackageName();
        if (guestPackage != null && args != null) for (int i = 0; i < args.length; i++) {
            if (guestPackage.equals(args[i])) args[i] = BlackBoxCore.getHostPkg();
        }
        return super.invoke(proxy, method, args);
    }
}
