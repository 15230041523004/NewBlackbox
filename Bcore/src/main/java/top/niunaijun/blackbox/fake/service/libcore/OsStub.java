package top.niunaijun.blackbox.fake.service.libcore;

import android.os.Process;

import java.lang.reflect.Method;

import black.libcore.io.BRLibcore;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.core.IOCore;
import top.niunaijun.blackbox.fake.hook.ClassInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.Reflector;


public class OsStub extends ClassInvocationStub {
    public static final String TAG = "OsStub";
    private Object mBase;

    public OsStub() {
        mBase = BRLibcore.get().os();
    }

    @Override
    protected Object getWho() {
        return mBase;
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        BRLibcore.get()._set_os(proxyInvocation);
    }

    @Override
    protected void onBindMethod() {
    }

    @Override
    public boolean isBadEnv() {
        return BRLibcore.get().os() != getProxyInvocation();
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                if (args[i] == null)
                    continue;
                if (args[i] instanceof String && ((String) args[i]).startsWith("/")) {
                    String orig = (String) args[i];
                    args[i] = IOCore.get().redirectPath(orig);



                }
            }
        }
        Object result = super.invoke(proxy, method, args);
        if ("getifaddrs".equals(method.getName()) && result != null
                && top.niunaijun.blackbox.fake.service.GuestNetworkView.isGuest()) {
            java.util.ArrayList<Object> visible = new java.util.ArrayList<>();
            for (int i = 0; i < java.lang.reflect.Array.getLength(result); i++) {
                Object address = java.lang.reflect.Array.get(result, i);
                String name = (String) address.getClass().getField("ifa_name").get(address);
                if (!top.niunaijun.blackbox.fake.service.GuestNetworkView.isTunnel(name)) visible.add(address);
            }
            Object copy = java.lang.reflect.Array.newInstance(result.getClass().getComponentType(), visible.size());
            for (int i = 0; i < visible.size(); i++) java.lang.reflect.Array.set(copy, i, visible.get(i));
            return copy;
        }
        return result;
    }

    @ProxyMethod("getuid")
    public static class getuid extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            int callUid = (int) method.invoke(who, args);
            return getFakeUid(callUid);
        }
    }

    @ProxyMethod("stat")
    public static class stat extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Object invoke = null;
            try {
                invoke = method.invoke(who, args);
            } catch (Throwable e) {
                throw e.getCause();
            }
            Reflector.with(invoke).field("st_uid").set(getFakeUid(-1));
            return invoke;
        }
    }

    private static int getFakeUid(int callUid) {
        if (callUid > 0 && callUid <= Process.FIRST_APPLICATION_UID)
            return callUid;

        if (BActivityThread.isThreadInit() && BActivityThread.currentActivityThread().isInit()) {
            return BActivityThread.getBAppId();
        } else {
            return BlackBoxCore.getHostUid();
        }
    }
}
