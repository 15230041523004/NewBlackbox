package top.niunaijun.blackbox.core.system.pm;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.content.pm.ApplicationInfo;
import android.os.Parcel;
import android.os.Process;
import android.os.Build;
import android.util.ArrayMap;
import android.util.AtomicFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.core.system.BProcessManagerService;
import top.niunaijun.blackbox.entity.pm.InstallOption;
import top.niunaijun.blackbox.utils.FileUtils;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.compat.PackageParserCompat;


 class Settings {
    public static final String TAG = "Settings";
    private static final String PACKAGE_CACHE_MARKER = "package-cache-runtime";
    private static final String PACKAGE_CACHE_SCHEMA = "bpackage-parcel-v1\n";

    final ArrayMap<String, BPackageSettings> mPackages = new ArrayMap<>();
    private final Map<String, Integer> mAppIds = new HashMap<>();
    private final Map<String, SharedUserSetting> mSharedUsers = SharedUserSetting.sSharedUsers;
    private int mCurrUid = 0;

    public Settings() {
        synchronized (mPackages) {
            loadUidLP();
            SharedUserSetting.loadSharedUsers();
        }
    }

    BPackageSettings getPackageLPw(String name, PackageParser.Package aPackage, InstallOption installOption) {
        BPackageSettings pkgSettings;
        BPackageSettings origSettings = new BPackageSettings();
        origSettings.pkg = new BPackage(aPackage);
        origSettings.pkg.installOption = installOption;
        origSettings.installOption = installOption;
        origSettings.pkg.mExtras = origSettings;
        origSettings.pkg.applicationInfo = PackageManagerCompat.generateApplicationInfo(origSettings.pkg, 0, BPackageUserState.create(), 0);
        synchronized (mPackages) {
            pkgSettings = mPackages.get(name);
            if (pkgSettings != null) {
                origSettings.appId = pkgSettings.appId;
                origSettings.userState = pkgSettings.userState;
            } else {
                boolean b = registerAppIdLPw(origSettings);
                if (!b) {
                    throw new RuntimeException("registerAppIdLPw err.");
                }
            }
        }
        return origSettings;
    }

    boolean registerAppIdLPw(BPackageSettings p) {
        boolean createdNew = false;
        String sharedUserId = p.pkg.mSharedUserId;
        SharedUserSetting sharedUserSetting = null;
        if (sharedUserId != null) {
            sharedUserSetting = mSharedUsers.get(sharedUserId);
            if (sharedUserSetting == null) {
                sharedUserSetting = new SharedUserSetting(sharedUserId);
                sharedUserSetting.userId = acquireAndRegisterNewAppIdLPw(p);
                mSharedUsers.put(sharedUserId, sharedUserSetting);
            }
        }
        if (sharedUserSetting != null) {
            p.appId = sharedUserSetting.userId;
            Slog.d(TAG, p.pkg.packageName + " sharedUserId = " + sharedUserId + ", setAppId = " + p.appId);
        }
        if (p.appId == 0) {
            
            p.appId = acquireAndRegisterNewAppIdLPw(p);
        }
        if (p.appId < 0) {
            createdNew = false;




        } else {
            createdNew = true;
        }
        saveUidLP();
        SharedUserSetting.saveSharedUsers();
        return createdNew;
    }

    private int acquireAndRegisterNewAppIdLPw(BPackageSettings obj) {
        
        Integer integer = mAppIds.get(obj.pkg.packageName);
        if (integer != null)
            return integer;

        if (mCurrUid >= Process.LAST_APPLICATION_UID) {
            return -1;
        }
        mCurrUid++;
        mAppIds.put(obj.pkg.packageName, mCurrUid);
        return Process.FIRST_APPLICATION_UID + mCurrUid;
    }

    private void saveUidLP() {
        Parcel parcel = Parcel.obtain();
        FileOutputStream fileOutputStream = null;
        AtomicFile atomicFile = new AtomicFile(BEnvironment.getUidConf());
        try {
            Set<String> pkgName = mPackages.keySet();
            for (String s : new HashSet<>(mAppIds.keySet())) {
                if (!pkgName.contains(s)) {
                    mAppIds.remove(s);
                }
            }
            parcel.writeInt(mCurrUid);
            parcel.writeMap(mAppIds);

            fileOutputStream = atomicFile.startWrite();
            FileUtils.writeParcelToOutput(parcel, fileOutputStream);
            atomicFile.finishWrite(fileOutputStream);
        } catch (Exception e) {
            e.printStackTrace();
            atomicFile.failWrite(fileOutputStream);
        } finally {
            parcel.recycle();
        }
    }

    private void loadUidLP() {
        Parcel parcel = Parcel.obtain();
        try {
            byte[] uidBytes = FileUtils.toByteArray(BEnvironment.getUidConf());
            if (uidBytes == null || uidBytes.length == 0) {
                
                return;
            }
            
            parcel.unmarshall(uidBytes, 0, uidBytes.length);
            parcel.setDataPosition(0);

            mCurrUid = parcel.readInt();
            HashMap hashMap = parcel.readHashMap(HashMap.class.getClassLoader());
            synchronized (mAppIds) {
                mAppIds.clear();
                mAppIds.putAll(hashMap);
            }
        } catch (Exception e) {
            
            try {
                
                BEnvironment.getUidConf().delete();
            } catch (Exception deleteException) {
                
            }
            
            
            mCurrUid = 0;
            synchronized (mAppIds) {
                mAppIds.clear();
            }
        } finally {
            parcel.recycle();
        }
    }

    public void scanPackage() {
        synchronized (mPackages) {
            File appRootDir = BEnvironment.getAppRootDir();
            FileUtils.mkdirs(appRootDir);
            File[] apps = appRootDir.listFiles();
            if (apps == null) {
                Slog.e(TAG, "PACKAGE_SCAN_ABORT reason=APP_ROOT_UNAVAILABLE");
                return;
            }
            boolean runtimeCompatible = isPackageCacheRuntimeCompatible();
            boolean allLoaded = true;
            for (File app : apps) {
                if (!app.isDirectory()) {
                    continue;
                }
                allLoaded &= updatePackageLP(app, !runtimeCompatible);
            }
            if (allLoaded && !runtimeCompatible) {
                writePackageCacheRuntimeMarker();
            }
        }
    }

    public void scanPackage(String packageName) {
        synchronized (mPackages) {
            updatePackageLP(BEnvironment.getAppDir(packageName), !isPackageCacheRuntimeCompatible());
        }
    }

    private boolean updatePackageLP(File app, boolean migrateForRuntime) {
        String packageName = app.getName();
        File packageConf = BEnvironment.getPackageConf(packageName);
        byte[] packageSettingsBytes;
        try {
            packageSettingsBytes = FileUtils.toByteArray(packageConf);
            if (packageSettingsBytes == null || packageSettingsBytes.length == 0) {
                Slog.e(TAG, "PACKAGE_SCAN_ITEM_FAILED package=" + packageName + " reason=EMPTY_SETTINGS");
                return false;
            }
        } catch (Throwable error) {
            Slog.e(TAG, "PACKAGE_SCAN_ITEM_FAILED package=" + packageName +
                    " reason=SETTINGS_READ error=" + error.getClass().getSimpleName());
            return false;
        }

        PersistedPackageConfMetadata persisted = PersistedPackageConfMetadata.read(packageSettingsBytes);
        if (migrateForRuntime) {
            if (persisted == null) {
                Slog.e(TAG, "PACKAGE_CACHE_MIGRATION_SKIPPED package=" + packageName +
                        " reason=LEGACY_METADATA_UNREADABLE dataPreserved=true");
                return false;
            }
            if (rebuildPackageFromCurrentApk(packageName, persisted)) {
                Slog.i(TAG, "PACKAGE_CACHE_MIGRATED package=" + packageName +
                        " appIdPreserved=true userStatePreserved=true dataPreserved=true");
                return true;
            }
            Slog.e(TAG, "PACKAGE_CACHE_MIGRATION_FAILED package=" + packageName +
                    " dataPreserved=true");
            return false;
        }

        Parcel packageSettingsIn = Parcel.obtain();
        try {
            packageSettingsIn.unmarshall(packageSettingsBytes, 0, packageSettingsBytes.length);
            packageSettingsIn.setDataPosition(0);
            BPackageSettings bPackageSettings = new BPackageSettings(packageSettingsIn);
            bPackageSettings.pkg.mExtras = bPackageSettings;
            if (bPackageSettings.installOption.isFlag(InstallOption.FLAG_SYSTEM)) {
                PackageInfo packageInfo = BlackBoxCore.getPackageManager().getPackageInfo(packageName, PackageManager.GET_META_DATA);
                String currPackageSourcePath = packageInfo == null || packageInfo.applicationInfo == null
                        ? null : packageInfo.applicationInfo.sourceDir;
                if (currPackageSourcePath == null) {
                    Slog.e(TAG, "PACKAGE_SCAN_ITEM_FAILED package=" + packageName +
                            " reason=SYSTEM_APK_UNAVAILABLE dataPreserved=true");
                    return false;
                }
                if (!currPackageSourcePath.equals(bPackageSettings.pkg.baseCodePath)) {
                    BProcessManagerService.get().killAllByPackageName(bPackageSettings.pkg.packageName);
                    PersistedPackageConfMetadata decoded = persisted != null ? persisted :
                            new PersistedPackageConfMetadata(bPackageSettings.appId,
                                    bPackageSettings.installOption, new HashMap<>(bPackageSettings.userState));
                    BPackageSettings rebuilt = rebuildPackageFromApk(packageName, currPackageSourcePath,
                            packageInfo.applicationInfo, decoded);
                    if (rebuilt == null || !rebuilt.save()) {
                        Slog.e(TAG, "PACKAGE_SCAN_ITEM_FAILED package=" + packageName +
                                " reason=SYSTEM_APK_REBUILD dataPreserved=true");
                        return false;
                    }
                    bPackageSettings = rebuilt;
                }
            } else {
                bPackageSettings.pkg.applicationInfo = PackageManagerCompat.generateApplicationInfo(bPackageSettings.pkg, 0, BPackageUserState.create(), 0);
            }
            mPackages.put(bPackageSettings.pkg.packageName, bPackageSettings);
            Slog.d(TAG, "loaded Package: " + packageName);
            return true;
        } catch (Throwable e) {
            if (persisted != null && persisted.installOption.isFlag(InstallOption.FLAG_SYSTEM)) {
                try {
                    PackageInfo packageInfo = BlackBoxCore.getPackageManager().getPackageInfo(
                            packageName, PackageManager.GET_META_DATA);
                    if (packageInfo != null && packageInfo.applicationInfo != null) {
                        BPackageSettings rebuilt = rebuildPackageFromApk(packageName,
                                packageInfo.applicationInfo.sourceDir, packageInfo.applicationInfo, persisted);
                        if (rebuilt != null && rebuilt.save()) {
                            mPackages.put(packageName, rebuilt);
                            Slog.i(TAG, "PACKAGE_CACHE_REPAIRED package=" + packageName +
                                    " reason=PARCEL_DECODE_FAILED dataPreserved=true");
                            return true;
                        }
                    }
                } catch (Throwable repairError) {
                    Slog.e(TAG, "PACKAGE_CACHE_REPAIR_ERROR package=" + packageName +
                            " error=" + repairError.getClass().getSimpleName());
                }
            }
            Slog.e(TAG, "PACKAGE_SCAN_ITEM_FAILED package=" + packageName +
                    " reason=PARCEL_DECODE error=" + e.getClass().getSimpleName() +
                    " dataPreserved=true");
            return false;
        } finally {
            packageSettingsIn.recycle();
        }
    }

    private boolean rebuildPackageFromCurrentApk(String packageName,
                                                  PersistedPackageConfMetadata metadata) {
        try {
            String apkPath;
            ApplicationInfo hostApplicationInfo = null;
            if (metadata.installOption.isFlag(InstallOption.FLAG_SYSTEM)) {
                PackageInfo hostPackage = BlackBoxCore.getPackageManager().getPackageInfo(
                        packageName, PackageManager.GET_META_DATA);
                if (hostPackage == null || hostPackage.applicationInfo == null ||
                        !packageName.equals(hostPackage.packageName)) {
                    return false;
                }
                hostApplicationInfo = hostPackage.applicationInfo;
                apkPath = hostApplicationInfo.sourceDir;
            } else if (metadata.installOption.isFlag(InstallOption.FLAG_STORAGE)) {
                apkPath = BEnvironment.getBaseApkDir(packageName).getAbsolutePath();
            } else {
                return false;
            }
            BPackageSettings rebuilt = rebuildPackageFromApk(
                    packageName, apkPath, hostApplicationInfo, metadata);
            if (rebuilt == null || !rebuilt.save()) {
                return false;
            }
            mPackages.put(packageName, rebuilt);
            return true;
        } catch (Throwable error) {
            Slog.e(TAG, "PACKAGE_CACHE_MIGRATION_ERROR package=" + packageName +
                    " error=" + error.getClass().getSimpleName());
            return false;
        }
    }

    private BPackageSettings rebuildPackageFromApk(String packageName, String apkPath,
                                                     ApplicationInfo hostApplicationInfo,
                                                     PersistedPackageConfMetadata metadata) {
        if (apkPath == null || apkPath.isEmpty() || !new File(apkPath).isFile()) {
            return null;
        }
        PackageParser.Package parsed = parserApk(apkPath);
        if (parsed == null || !packageName.equals(parsed.packageName)) {
            return null;
        }
        if (hostApplicationInfo != null) {
            parsed.applicationInfo = hostApplicationInfo;
        }
        BPackageSettings settings = new BPackageSettings();
        settings.pkg = new BPackage(parsed);
        settings.appId = metadata.appId;
        settings.installOption = metadata.installOption;
        settings.userState = new HashMap<>(metadata.userState);
        settings.pkg.installOption = metadata.installOption;
        settings.pkg.mExtras = settings;
        settings.pkg.applicationInfo = PackageManagerCompat.generateApplicationInfo(
                settings.pkg, 0, BPackageUserState.create(), 0);
        return settings;
    }

    private boolean isPackageCacheRuntimeCompatible() {
        try {
            byte[] marker = FileUtils.toByteArray(new File(BEnvironment.getSystemDir(), PACKAGE_CACHE_MARKER));
            if (marker == null) return false;
            String expected = PACKAGE_CACHE_SCHEMA + Build.FINGERPRINT;
            return expected.equals(new String(marker, StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            return false;
        }
    }

    private void writePackageCacheRuntimeMarker() {
        AtomicFile atomicFile = new AtomicFile(new File(BEnvironment.getSystemDir(), PACKAGE_CACHE_MARKER));
        FileOutputStream output = null;
        try {
            output = atomicFile.startWrite();
            output.write((PACKAGE_CACHE_SCHEMA + Build.FINGERPRINT).getBytes(StandardCharsets.UTF_8));
            atomicFile.finishWrite(output);
        } catch (IOException error) {
            if (output != null) {
                atomicFile.failWrite(output);
            }
            Slog.e(TAG, "PACKAGE_CACHE_MARKER_WRITE_FAILED error=" + error.getClass().getSimpleName());
        }
    }

    private BPackageSettings reInstallBySystem(PackageInfo systemPackageInfo, InstallOption option) throws Exception {
        Slog.d(TAG, "reInstallBySystem: " + systemPackageInfo.packageName);
        PackageParser.Package aPackage = parserApk(systemPackageInfo.applicationInfo.sourceDir);
        if (aPackage == null) {
            throw new RuntimeException("parser apk error.");
        }
        aPackage.applicationInfo = BlackBoxCore.getPackageManager().getPackageInfo(aPackage.packageName, 0).applicationInfo;
        return getPackageLPw(aPackage.packageName, aPackage, option);
    }

    public void removePackage(String packageName) {
        mPackages.remove(packageName);
    }

    private PackageParser.Package parserApk(String file) {
        try {
            PackageParser parser = PackageParserCompat.createParser(new File(file));
            PackageParser.Package aPackage = PackageParserCompat.parsePackage(parser, new File(file), 0);
            PackageParserCompat.collectCertificates(parser, aPackage, 0);
            return aPackage;
        } catch (Throwable t) {
            t.printStackTrace();
        }
        return null;
    }
}
