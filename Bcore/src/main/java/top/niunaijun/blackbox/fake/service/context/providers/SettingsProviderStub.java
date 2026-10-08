package top.niunaijun.blackbox.fake.service.context.providers;

import java.lang.reflect.Method;
import top.niunaijun.blackbox.utils.DebugStatePolicy;

/** Legacy call signatures omit the authority, so retain the provider's identity explicitly. */
public final class SettingsProviderStub extends SystemProviderStub {
    static String querySettingName(Object[] args, android.net.Uri uri) {
        String pathKey = uri.getLastPathSegment();
        if (DebugStatePolicy.isDebugSetting(pathKey)) return pathKey;
        if (args != null) for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof android.os.Bundle) {
                android.os.Bundle query = (android.os.Bundle) args[i];
                String[] selectionArgs = query.getStringArray("android:query-arg-sql-selection-args");
                String selection = query.getString("android:query-arg-sql-selection");
                String key = selectedName(selection, selectionArgs);
                if (key != null) return key;
            } else if (args[i] instanceof String && i + 1 < args.length && args[i + 1] instanceof String[]) {
                String key = selectedName((String) args[i], (String[]) args[i + 1]);
                if (key != null) return key;
            }
        }
        return pathKey;
    }

    private static String selectedName(String selection, String[] args) {
        if (selection == null || !selection.matches("(?i)\\s*name\\s*=\\s*\\?\\s*")) return null;
        return args != null && args.length == 1 ? args[0] : null;
    }

    @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if ("call".equals(method.getName())) {
            Object result = DebugStatePolicy.interceptSettingsCall(args, true);
            if (result != DebugStatePolicy.UNHANDLED) return result;
        }
        return super.invoke(proxy, method, args);
    }
}
