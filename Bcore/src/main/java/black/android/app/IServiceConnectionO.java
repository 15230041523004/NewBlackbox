package black.android.app;

import android.content.ComponentName;
import android.app.IBinderSession;
import android.os.IBinder;

import top.niunaijun.blackreflection.annotation.BClassName;
import top.niunaijun.blackreflection.annotation.BMethod;

@BClassName("android.app.IServiceConnection")
public interface IServiceConnectionO {
    @BMethod
    void connected(ComponentName ComponentName0, IBinder IBinder1, boolean boolean2);

    /** Android 16 added the service-session argument to the callback. */
    @BMethod
    void connected(ComponentName ComponentName0, IBinder IBinder1, IBinderSession IBinderSession2, boolean boolean3);
}
