package top.niunaijun.blackbox.utils.compat;

import android.content.Context;
import android.content.ContextWrapper;
import android.util.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import black.android.app.BRContextImpl;
import black.android.app.BRContextImplKitkat;
import black.android.content.AttributionSourceStateContext;
import black.android.content.BRAttributionSource;
import black.android.content.BRAttributionSourceState;
import black.android.content.BRContentResolver;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.utils.Slog;

public class ContextCompat {
    public static final String TAG = "ContextCompat";

    public static void fixAttributionSourceState(Object obj, int uid) {
        fixAttributionSourceState(obj, uid, BlackBoxCore.getHostPkg());
    }

    public static void fixAttributionSourceState(Object obj, int uid, String packageName) {
        if (obj == null) return;
        try {
            if (BRAttributionSource.getRealClass() != null && BRAttributionSource.getRealClass().isInstance(obj)) {
                if (BRAttributionSource.get(obj)._check_mAttributionSourceState() != null) {
                    Object mAttributionSourceState = BRAttributionSource.get(obj).mAttributionSourceState();
                    if (mAttributionSourceState != null) {
                        AttributionSourceStateContext attributionSourceStateContext = BRAttributionSourceState.get(mAttributionSourceState);
                        attributionSourceStateContext._set_packageName(packageName);
                        attributionSourceStateContext._set_uid(uid);
                        try {
                            attributionSourceStateContext._set_attributionTag(null);
                        } catch (Throwable ignored) {
                        }
                    }
                }
                try {
                    fixAttributionSourceState(BRAttributionSource.get(obj).getNext(), uid, packageName);
                } catch (Throwable ignored) {
                }
                return;
            }

            Class<?> clazz = obj.getClass();
            if (clazz.getName().contains("AttributionSourceState")) {
                try {
                    Field pkgField = clazz.getField("packageName");
                    pkgField.setAccessible(true);
                    pkgField.set(obj, packageName);
                } catch (Throwable ignored) {}
                try {
                    Field uidField = clazz.getField("uid");
                    uidField.setAccessible(true);
                    uidField.setInt(obj, uid);
                } catch (Throwable ignored) {}
                try {
                    Field tagField = clazz.getField("attributionTag");
                    tagField.setAccessible(true);
                    tagField.set(obj, null);
                } catch (Throwable ignored) {}
                try {
                    Field nextField = clazz.getField("next");
                    nextField.setAccessible(true);
                    Object[] nextArr = (Object[]) nextField.get(obj);
                    if (nextArr != null) {
                        for (Object nextItem : nextArr) {
                            fixAttributionSourceState(nextItem, uid, packageName);
                        }
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            Slog.w(TAG, "fixAttributionSourceState failed: " + t.getMessage());
        }
    }

    public static void fix(Context context) {
        try {
            if (context == null) {
                Slog.w(TAG, "Context is null, skipping ContextCompat.fix");
                return;
            }
            
            int deep = 0;
            while (context instanceof ContextWrapper) {
                context = ((ContextWrapper) context).getBaseContext();
                deep++;
                if (deep >= 10) {
                    return;
                }
            }
            
            if (context == null) {
                Slog.w(TAG, "Base context is null after unwrapping, skipping ContextCompat.fix");
                return;
            }
            
            BRContextImpl.get(context)._set_mPackageManager(null);
            try {
                context.getPackageManager();
            } catch (Throwable e) {
                e.printStackTrace();
            }

            BRContextImpl.get(context)._set_mBasePackageName(BlackBoxCore.getHostPkg());
            BRContextImplKitkat.get(context)._set_mOpPackageName(BlackBoxCore.getHostPkg());
            
            try {
                BRContentResolver.get(context.getContentResolver())._set_mPackageName(BlackBoxCore.getHostPkg());
            } catch (Exception e) {
                Slog.w(TAG, "Failed to fix content resolver: " + e.getMessage());
            }

            if (BuildCompat.isS()) {
                try {
                    Field tagField = context.getClass().getDeclaredField("mAttributionTag");
                    tagField.setAccessible(true);
                    tagField.set(context, null);
                } catch (Throwable ignored) {
                }
                try {
                    Field uidField = context.getClass().getDeclaredField("mAttributionSourceUid");
                    uidField.setAccessible(true);
                    uidField.setInt(context, BlackBoxCore.getHostUid());
                } catch (Throwable ignored) {
                }
                try {
                    Object currentSource = null;
                    try {
                        currentSource = BRContextImpl.get(context).getAttributionSource();
                    } catch (Throwable ignored) {
                    }
                    if (currentSource != null) {
                        fixAttributionSourceState(currentSource, BlackBoxCore.getHostUid(), BlackBoxCore.getHostPkg());
                    } else {
                        Object safeSource = top.niunaijun.blackbox.utils.AttributionSourceUtils.createSafeAttributionSource();
                        if (safeSource != null) {
                            Field srcField = context.getClass().getDeclaredField("mAttributionSource");
                            srcField.setAccessible(true);
                            srcField.set(context, safeSource);
                        }
                    }
                } catch (Exception e) {
                    Slog.w(TAG, "Failed to fix attribution source state: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            Slog.e(TAG, "Error in ContextCompat.fix: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
