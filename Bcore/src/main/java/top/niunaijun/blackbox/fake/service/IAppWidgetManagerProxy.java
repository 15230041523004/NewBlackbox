package top.niunaijun.blackbox.fake.service;

import android.content.Context;

import java.lang.reflect.Method;

import black.android.os.BRServiceManager;
import black.com.android.internal.appwidget.BRIAppWidgetServiceStub;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.fake.service.base.ValueMethodProxy;
import top.niunaijun.blackbox.utils.MethodParameterUtils;
import top.niunaijun.blackbox.utils.Slog;


public class IAppWidgetManagerProxy extends BinderInvocationStub {
    private static final String TAG = "IAppWidgetManagerProxy";

    public IAppWidgetManagerProxy() {
        super(BRServiceManager.get().getService(Context.APPWIDGET_SERVICE));
    }

    @Override
    protected Object getWho() {
        return BRIAppWidgetServiceStub.get().asInterface(BRServiceManager.get().getService(Context.APPWIDGET_SERVICE));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(Context.APPWIDGET_SERVICE);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        MethodParameterUtils.replaceAllAppPkg(args);
        try {
            return super.invoke(proxy, method, args);
        } catch (Throwable thrown) {
            Throwable cause = thrown;
            if (cause instanceof java.lang.reflect.InvocationTargetException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            // AppWidgetService checks the provider package against the host uid.
            // A guest ComponentName fails that check, and the exception cancels
            // the caller's main-thread coroutine. String rewriting does not see it.
            if (cause instanceof SecurityException) {
                Slog.w(TAG, "AppWidget " + method.getName() + " rejected the guest package");
                return denied(method);
            }
            throw thrown;
        }
    }

    /**
     * Preview APIs compare {@code ComponentName.getPackageName()} with the
     * calling uid. The guest package can never belong to the host uid.
     */
    @ProxyMethod("setWidgetPreview")
    public static class SetWidgetPreview extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return denied(method);
        }
    }

    @ProxyMethod("removeWidgetPreview")
    public static class RemoveWidgetPreview extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return denied(method);
        }
    }

    @ProxyMethod("getWidgetPreview")
    public static class GetWidgetPreview extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return null;
        }
    }

    static Object denied(Method method) {
        if (method == null) {
            return false;
        }
        Class<?> type = method.getReturnType();
        if (type == boolean.class || type == Boolean.class) {
            return false;
        }
        if (type == int.class || type == Integer.class) {
            return 0;
        }
        return null;
    }

    @Override
    protected void onBindMethod() {
        super.onBindMethod();
        addMethodHook(new ValueMethodProxy("startListening", new int[0]));
        addMethodHook(new ValueMethodProxy("stopListening", 0));
        addMethodHook(new ValueMethodProxy("allocateAppWidgetId", 0));
        addMethodHook(new ValueMethodProxy("deleteAppWidgetId", 0));
        addMethodHook(new ValueMethodProxy("deleteHost", 0));
        addMethodHook(new ValueMethodProxy("deleteAllHosts", 0));
        addMethodHook(new ValueMethodProxy("getAppWidgetViews", null));
        addMethodHook(new ValueMethodProxy("getAppWidgetIdsForHost", null));
        addMethodHook(new ValueMethodProxy("createAppWidgetConfigIntentSender", null));
        addMethodHook(new ValueMethodProxy("updateAppWidgetIds", 0));
        addMethodHook(new ValueMethodProxy("updateAppWidgetOptions", 0));
        addMethodHook(new ValueMethodProxy("getAppWidgetOptions", null));
        addMethodHook(new ValueMethodProxy("partiallyUpdateAppWidgetIds", 0));
        addMethodHook(new ValueMethodProxy("updateAppWidgetProvider", 0));
        addMethodHook(new ValueMethodProxy("notifyAppWidgetViewDataChanged", 0));
        addMethodHook(new ValueMethodProxy("getInstalledProvidersForProfile", null));
        addMethodHook(new ValueMethodProxy("getAppWidgetInfo", null));
        addMethodHook(new ValueMethodProxy("hasBindAppWidgetPermission", false));
        addMethodHook(new ValueMethodProxy("setBindAppWidgetPermission", 0));
        addMethodHook(new ValueMethodProxy("bindAppWidgetId", false));
        addMethodHook(new ValueMethodProxy("bindRemoteViewsService", 0));
        addMethodHook(new ValueMethodProxy("unbindRemoteViewsService", 0));
        addMethodHook(new ValueMethodProxy("getAppWidgetIds", new int[0]));
        addMethodHook(new ValueMethodProxy("isBoundWidgetPackage", false));
    }
}
