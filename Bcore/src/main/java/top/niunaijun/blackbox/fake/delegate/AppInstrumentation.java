package top.niunaijun.blackbox.fake.delegate;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.util.Log;

import java.lang.reflect.Field;

import black.android.app.BRActivity;
import black.android.app.BRActivityThread;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.fake.hook.HookManager;
import top.niunaijun.blackbox.fake.hook.IInjectHook;
import top.niunaijun.blackbox.fake.service.HCallbackProxy;
import top.niunaijun.blackbox.fake.service.IActivityClientProxy;
import top.niunaijun.blackbox.utils.HackAppUtils;
import top.niunaijun.blackbox.utils.compat.ActivityCompat;
import top.niunaijun.blackbox.utils.compat.ActivityManagerCompat;
import top.niunaijun.blackbox.utils.compat.ContextCompat;

public final class AppInstrumentation extends BaseInstrumentationDelegate implements IInjectHook {

    private static final String TAG = AppInstrumentation.class.getSimpleName();

    private static AppInstrumentation sAppInstrumentation;

    public static AppInstrumentation get() {
        if (sAppInstrumentation == null) {
            synchronized (AppInstrumentation.class) {
                if (sAppInstrumentation == null) {
                    sAppInstrumentation = new AppInstrumentation();
                }
            }
        }
        return sAppInstrumentation;
    }

    public AppInstrumentation() {
    }

    @Override
    public void injectHook() {
        try {
            Instrumentation mInstrumentation = getCurrInstrumentation();
            if (mInstrumentation == this || checkInstrumentation(mInstrumentation))
                return;
            mBaseInstrumentation = (Instrumentation) mInstrumentation;
            BRActivityThread.get(BlackBoxCore.mainThread())._set_mInstrumentation(this);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private Instrumentation getCurrInstrumentation() {
        Object currentActivityThread = BlackBoxCore.mainThread();
        return BRActivityThread.get(currentActivityThread).mInstrumentation();
    }

    @Override
    public boolean isBadEnv() {
        return !checkInstrumentation(getCurrInstrumentation());
    }

    private boolean checkInstrumentation(Instrumentation instrumentation) {
        if (instrumentation instanceof AppInstrumentation) {
            return true;
        }
        Class<?> clazz = instrumentation.getClass();
        if (Instrumentation.class.equals(clazz)) {
            return false;
        }
        do {
            assert clazz != null;
            Field[] fields = clazz.getDeclaredFields();
            for (Field field : fields) {
                if (Instrumentation.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    try {
                        Object obj = field.get(instrumentation);
                        if ((obj instanceof AppInstrumentation)) {
                            return true;
                        }
                    } catch (Exception e) {
                        return false;
                    }
                }
            }
            clazz = clazz.getSuperclass();
        } while (!Instrumentation.class.equals(clazz));
        return false;
    }

    private void checkHCallback() {
        HookManager.get().checkEnv(HCallbackProxy.class);
    }

    private void checkActivity(Activity activity) {
        Log.d(TAG, "callActivityOnCreate: " + activity.getClass().getName());
        HackAppUtils.enableQQLogOutput(activity.getPackageName(), activity.getClassLoader());
        checkHCallback();
        HookManager.get().checkEnv(IActivityClientProxy.class);
        ActivityInfo info = BRActivity.get(activity).mActivityInfo();
        ContextCompat.fix(activity);
        ActivityCompat.fix(activity);
        if (info.theme != 0) {
            activity.getTheme().applyStyle(info.theme, true);
        }
        ActivityManagerCompat.setActivityOrientation(activity, info.screenOrientation);
    }

    @Override
    public Application newApplication(ClassLoader cl, String className, Context context) throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        ContextCompat.fix(context);
        Log.d(TAG, "newApplication called for class: " + className);
        try {
            Application app = super.newApplication(cl, className, context);
            Log.d(TAG, "newApplication created successfully: " + app);
            return app;
        } catch (Throwable t) {
            Log.e(TAG, "newApplication failed for class: " + className, t);
            throw t;
        }
    }

    @Override
    public boolean onException(Object obj, Throwable e) {
        Log.e(TAG, "onException in Instrumentation for obj: " + obj, e);
        return mBaseInstrumentation.onException(obj, e);
    }

    @Override
    public void callActivityOnCreate(Activity activity, Bundle icicle, PersistableBundle persistentState) {
        checkActivity(activity);
        super.callActivityOnCreate(activity, icicle, persistentState);
        top.niunaijun.blackbox.utils.compat.TaskDescriptionCompat.apply(activity);
    }

    @Override
    public void callActivityOnCreate(Activity activity, Bundle icicle) {
        checkActivity(activity);
        super.callActivityOnCreate(activity, icicle);
        top.niunaijun.blackbox.utils.compat.TaskDescriptionCompat.apply(activity);
    }

    @Override
    public void callActivityOnResume(Activity activity) {
        super.callActivityOnResume(activity);
        top.niunaijun.blackbox.utils.compat.TaskDescriptionCompat.apply(activity);
    }

    @Override
    public void callApplicationOnCreate(Application app) {
        checkHCallback();
        super.callApplicationOnCreate(app);
    }

    public Activity newActivity(ClassLoader cl, String className, Intent intent) throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        try {
            return super.newActivity(cl, className, intent);
        } catch (ClassNotFoundException e) {
            // A virtual package can retain the launcher alias but lose its targetActivity
            // in ActivityInfo after a package/profile data reset. Android then asks us to
            // instantiate the alias name, which is not a Java class. Resolve only a
            // declared alias in this guest's own APK; keep the original Intent component.
            String aliasTarget = findDeclaredAliasTarget(className, intent);
            if (aliasTarget != null) {
                return mBaseInstrumentation.newActivity(cl, aliasTarget, intent);
            }
            return mBaseInstrumentation.newActivity(cl, className, intent);
        }
    }

    private String findDeclaredAliasTarget(String className, Intent intent) {
        ComponentName component = intent == null ? null : intent.getComponent();
        if (component == null || !component.getClassName().equals(className)) {
            return null;
        }
        try {
            ActivityInfo virtualAlias = BlackBoxCore.getBPackageManager().getActivityInfo(
                    component, 0, BActivityThread.getUserId());
            if (virtualAlias == null || virtualAlias.applicationInfo == null
                    || !component.getPackageName().equals(virtualAlias.packageName)) {
                return null;
            }
            String sourceDir = virtualAlias.applicationInfo.sourceDir;
            PackageInfo archive = BlackBoxCore.getContext().getPackageManager()
                    .getPackageArchiveInfo(sourceDir, PackageManager.GET_ACTIVITIES);
            if (archive == null || archive.activities == null
                    || !component.getPackageName().equals(archive.packageName)) {
                return null;
            }
            String target = null;
            for (ActivityInfo activity : archive.activities) {
                if (className.equals(activity.name)) {
                    target = activity.targetActivity;
                    break;
                }
            }
            if (target == null || target.equals(className)) {
                return null;
            }
            ComponentName targetComponent = new ComponentName(component.getPackageName(), target);
            ActivityInfo virtualTarget = BlackBoxCore.getBPackageManager().getActivityInfo(
                    targetComponent, 0, BActivityThread.getUserId());
            if (virtualTarget != null && target.equals(virtualTarget.name)
                    && virtualTarget.targetActivity == null) {
                Log.i(TAG, "Resolved guest activity alias " + className + " to declared target " + target);
                return target;
            }
        } catch (RuntimeException exception) {
            Log.w(TAG, "Could not resolve guest activity alias " + className, exception);
        }
        return null;
    }
}
