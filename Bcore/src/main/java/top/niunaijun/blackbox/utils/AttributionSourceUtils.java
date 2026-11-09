package top.niunaijun.blackbox.utils;

import top.niunaijun.blackbox.BlackBoxCore;


public class AttributionSourceUtils {
    private static final String TAG = "AttributionSourceUtils";

    
    public static void fixAttributionSourceInArgs(Object[] args) {
        fixAttributionSourceInArgs(args, BlackBoxCore.getHostUid(), BlackBoxCore.getHostPkg());
    }

    public static void fixAttributionSourceInArgs(Object[] args, int targetUid, String targetPkg) {
        if (args == null) return;
        
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            if (arg != null && arg.getClass().getName().contains("AttributionSource")) {
                try {
                    fixAttributionSourceUid(arg, targetUid, targetPkg);
                    Slog.d(TAG, "Fixed AttributionSource UID in method arguments");
                } catch (Exception e) {
                    Slog.w(TAG, "Failed to fix AttributionSource in args: " + e.getMessage());
                }
            } else if (arg instanceof String && ((String) arg).isEmpty()) {
                args[i] = null;
            }
        }
        
        
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            if (arg != null && arg.getClass().getName().contains("Bundle")) {
                try {
                    fixAttributionSourceInBundle(arg, targetUid, targetPkg);
                } catch (Exception e) {
                    Slog.w(TAG, "Failed to fix AttributionSource in Bundle: " + e.getMessage());
                }
            }
        }
    }

    
    public static void fixAttributionSourceUid(Object attributionSource) {
        fixAttributionSourceUid(attributionSource, BlackBoxCore.getHostUid(), BlackBoxCore.getHostPkg());
    }

    public static void fixAttributionSourceUid(Object attributionSource, int targetUid, String targetPkg) {
        try {
            if (attributionSource == null) return;
            
            // 1. Modern Android (API 31+): Delegation to AttributionSourceState
            try {
                top.niunaijun.blackbox.utils.compat.ContextCompat.fixAttributionSourceState(attributionSource, targetUid, targetPkg);
            } catch (Throwable t) {
                // ignore
            }

            Class<?> attributionSourceClass = attributionSource.getClass();
            
            String[] uidFieldNames = {"mUid", "uid", "mCallingUid", "callingUid", "mSourceUid", "sourceUid"};
            
            for (String fieldName : uidFieldNames) {
                try {
                    java.lang.reflect.Field uidField = attributionSourceClass.getDeclaredField(fieldName);
                    uidField.setAccessible(true);
                    uidField.set(attributionSource, targetUid);
                    Slog.d(TAG, "Fixed AttributionSource UID via field: " + fieldName);
                    break;
                } catch (Throwable ignored) {
                }
            }
            
            try {
                java.lang.reflect.Method setUidMethod = attributionSourceClass.getDeclaredMethod("setUid", int.class);
                setUidMethod.setAccessible(true);
                setUidMethod.invoke(attributionSource, targetUid);
                Slog.d(TAG, "Fixed AttributionSource UID via setter method");
            } catch (Throwable ignored) {
            }
            
            String[] packageFieldNames = {"mPackageName", "packageName", "mSourcePackage", "sourcePackage"};
            
            for (String fieldName : packageFieldNames) {
                try {
                    java.lang.reflect.Field packageField = attributionSourceClass.getDeclaredField(fieldName);
                    packageField.setAccessible(true);
                    packageField.set(attributionSource, targetPkg);
                    Slog.d(TAG, "Fixed AttributionSource package name via field: " + fieldName);
                    break;
                } catch (Throwable ignored) {
                }
            }

            String[] tagFieldNames = {"mAttributionTag", "attributionTag"};
            for (String fieldName : tagFieldNames) {
                try {
                    java.lang.reflect.Field tagField = attributionSourceClass.getDeclaredField(fieldName);
                    tagField.setAccessible(true);
                    tagField.set(attributionSource, null);
                } catch (Throwable ignored) {
                }
            }
            
        } catch (Throwable e) {
            Slog.w(TAG, "Error fixing AttributionSource UID: " + e.getMessage());
        }
    }

    
    public static void fixAttributionSourceInBundle(Object bundle) {
        fixAttributionSourceInBundle(bundle, BlackBoxCore.getHostUid(), BlackBoxCore.getHostPkg());
    }

    public static void fixAttributionSourceInBundle(Object bundle, int targetUid, String targetPkg) {
        try {
            if (bundle == null) return;
            
            
            java.lang.reflect.Method keySetMethod = bundle.getClass().getMethod("keySet");
            java.util.Set<String> keys = (java.util.Set<String>) keySetMethod.invoke(bundle);
            
            for (String key : keys) {
                try {
                    java.lang.reflect.Method getMethod = bundle.getClass().getMethod("get", String.class);
                    Object value = getMethod.invoke(bundle, key);
                    
                    if (value != null && value.getClass().getName().contains("AttributionSource")) {
                        fixAttributionSourceUid(value, targetUid, targetPkg);
                        Slog.d(TAG, "Fixed AttributionSource UID in Bundle key: " + key);
                    }
                } catch (Exception e) {
                    
                }
            }
        } catch (Exception e) {
            Slog.w(TAG, "Error fixing AttributionSource in Bundle: " + e.getMessage());
        }
    }

    
    public static Object createSafeAttributionSource() {
        try {
            Class<?> builderClass = Class.forName("android.content.AttributionSource$Builder");
            java.lang.reflect.Constructor<?> builderCtor = builderClass.getDeclaredConstructor(int.class);
            builderCtor.setAccessible(true);
            Object builder = builderCtor.newInstance(BlackBoxCore.getHostUid());
            java.lang.reflect.Method setPkgMethod = builderClass.getMethod("setPackageName", String.class);
            setPkgMethod.invoke(builder, BlackBoxCore.getHostPkg());
            java.lang.reflect.Method buildMethod = builderClass.getMethod("build");
            Object source = buildMethod.invoke(builder);
            if (source != null) {
                fixAttributionSourceUid(source);
                return source;
            }
        } catch (Throwable ignored) {
        }
        try {
            Class<?> attributionSourceClass = Class.forName("android.content.AttributionSource");
            java.lang.reflect.Constructor<?> constructor = null;
            try {
                constructor = attributionSourceClass.getDeclaredConstructor(int.class, String.class, String.class);
                constructor.setAccessible(true);
                return constructor.newInstance(BlackBoxCore.getHostUid(), BlackBoxCore.getHostPkg(), null);
            } catch (Throwable t) {
                try {
                    constructor = attributionSourceClass.getDeclaredConstructor(int.class, String.class);
                    constructor.setAccessible(true);
                    return constructor.newInstance(BlackBoxCore.getHostUid(), BlackBoxCore.getHostPkg());
                } catch (Throwable t2) {
                    constructor = attributionSourceClass.getDeclaredConstructor();
                    constructor.setAccessible(true);
                    Object obj = constructor.newInstance();
                    fixAttributionSourceUid(obj);
                    return obj;
                }
            }
        } catch (Exception e) {
            Slog.w(TAG, "Error creating safe AttributionSource: " + e.getMessage());
            return null;
        }
    }

    
    public static boolean validateAttributionSource(Object attributionSource) {
        try {
            if (attributionSource == null) return false;
            
            
            Class<?> attributionSourceClass = attributionSource.getClass();
            String[] uidFieldNames = {"mUid", "uid", "mCallingUid", "callingUid", "mSourceUid", "sourceUid"};
            
            for (String fieldName : uidFieldNames) {
                try {
                    java.lang.reflect.Field uidField = attributionSourceClass.getDeclaredField(fieldName);
                    uidField.setAccessible(true);
                    Object uidValue = uidField.get(attributionSource);
                    if (uidValue instanceof Integer) {
                        int uid = (Integer) uidValue;
                        if (uid > 0) {
                            Slog.d(TAG, "AttributionSource UID validation passed: " + uid);
                            return true;
                        }
                    }
                } catch (Exception e) {
                    
                }
            }
            
            
            Slog.w(TAG, "AttributionSource validation failed, attempting to fix");
            fixAttributionSourceUid(attributionSource);
            return true;
            
        } catch (Exception e) {
            Slog.w(TAG, "Error validating AttributionSource: " + e.getMessage());
            return false;
        }
    }
}
