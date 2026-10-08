package top.niunaijun.blackbox.fake.service.context.providers;

import android.os.IInterface;

import java.lang.reflect.Method;

import black.android.content.BRAttributionSource;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.fake.hook.ClassInvocationStub;
import top.niunaijun.blackbox.utils.compat.ContextCompat;


public class SystemProviderStub extends ClassInvocationStub implements BContentProvider {
    private IInterface mBase;

    @Override
    public IInterface wrapper(IInterface contentProviderProxy, String appPkg) {
        mBase = contentProviderProxy;
        injectHook();
        return (IInterface) getProxyInvocation();
    }

    @Override
    protected Object getWho() {
        return mBase;
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {

    }

    @Override
    protected void onBindMethod() {

    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if ("asBinder".equals(method.getName())) {
            return method.invoke(mBase, args);
        }
        
        String methodName = method.getName();
        
        
        
        if ("call".equals(methodName)) {
            Object debugResult = top.niunaijun.blackbox.utils.DebugStatePolicy.interceptSettingsCall(args);
            if (debugResult != top.niunaijun.blackbox.utils.DebugStatePolicy.UNHANDLED) return debugResult;
            if (args != null) {
                Class<?> attributionSourceClass = BRAttributionSource.getRealClass();
                for (int i = 0; i < args.length; i++) {
                    Object arg = args[i];
                    if (arg != null && (arg.getClass().getName().contains("AttributionSource")
                            || (attributionSourceClass != null && arg.getClass().getName().equals(attributionSourceClass.getName())))) {
                        ContextCompat.fixAttributionSourceState(arg, BlackBoxCore.getHostUid());
                    }
                }
            }
            Object result;
            try {
                result = method.invoke(mBase, args);
            } catch (Throwable t) {
                result = null;
            }
            if (args != null) {
                for (Object arg : args) {
                    if ("location_mode".equals(arg)) {
                        android.os.Bundle b = (result instanceof android.os.Bundle) ? (android.os.Bundle) result : new android.os.Bundle();
                        b.putString("value", "3");
                        return b;
                    } else if ("location_providers_allowed".equals(arg)) {
                        android.os.Bundle b = (result instanceof android.os.Bundle) ? (android.os.Bundle) result : new android.os.Bundle();
                        b.putString("value", "gps,network");
                        return b;
                    }
                }
            }
            return result;
        }
        
        
        if (args != null && args.length > 0) {
            Object arg = args[0];
            if (arg instanceof String) {
                String authority = (String) arg;
                
                if (!isSystemProviderAuthority(authority)) {
                    args[0] = BlackBoxCore.getHostPkg();
                }
            } else if (arg != null) {
                Class<?> attrSourceClass = BRAttributionSource.getRealClass();
                
                if (attrSourceClass != null && arg.getClass().getName().equals(attrSourceClass.getName())) {
                    ContextCompat.fixAttributionSourceState(arg, BlackBoxCore.getHostUid());
                }
            }
        }
        Object result = method.invoke(mBase, args);
        if ("query".equals(methodName) && result instanceof android.database.Cursor
                && top.niunaijun.blackbox.utils.DebugStatePolicy.isEnabled() && args != null) {
            for (Object arg : args) {
                if (arg instanceof android.net.Uri
                        && top.niunaijun.blackbox.utils.DebugStatePolicy.isSettingsUri((android.net.Uri) arg)) {
                    return new DebugSettingsCursor((android.database.Cursor) result,
                            SettingsProviderStub.querySettingName(args, (android.net.Uri) arg));
                }
            }
        }
        return result;
    }

    private boolean isSystemProviderAuthority(String authority) {
        if (authority == null) return false;
        
        return authority.equals("settings") || 
               authority.equals("media") || 
               authority.equals("downloads") || 
               authority.equals("contacts") || 
               authority.equals("call_log") || 
               authority.equals("telephony") || 
               authority.equals("calendar") || 
               authority.equals("browser") || 
               authority.equals("user_dictionary") || 
               authority.equals("applications") ||
               authority.startsWith("com.android.") ||
               authority.startsWith("android.");
    }
}
