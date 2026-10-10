package top.niunaijun.blackbox.utils.compat;


import android.app.Activity;
import android.app.WallpaperManager;
import android.content.res.TypedArray;
import android.view.WindowManager;

import black.android.app.BRActivity;
import black.com.android.internal.BRRstyleable;


public class ActivityCompat {

    public static void fix(Activity activity) {
        
        BRActivity.get(activity).mActivityInfo();

        try {
            TypedArray typedArray = activity.obtainStyledAttributes((BRRstyleable.get().Window()));
            if (typedArray != null) {
                boolean showWallpaper = typedArray.getBoolean(BRRstyleable.get().Window_windowShowWallpaper(),
                        false);
                if (showWallpaper) {
                    activity.getWindow().setBackgroundDrawable(WallpaperManager.getInstance(activity).getDrawable());
                }
                boolean fullscreen = typedArray.getBoolean(BRRstyleable.get().Window_windowFullscreen(), false);
                if (fullscreen) {
                    activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                }
                typedArray.recycle();
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }

        TaskDescriptionCompat.apply(activity);
    }
}
