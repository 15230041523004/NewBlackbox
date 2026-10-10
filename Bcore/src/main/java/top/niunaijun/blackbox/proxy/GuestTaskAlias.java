package top.niunaijun.blackbox.proxy;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import top.niunaijun.blackbox.BlackBoxCore;

/**
 * Maps a guest package and proxy slot to a host activity-alias.
 * The alias list is generated into res/raw/guest_alias_packages.txt.
 * PackageManager cannot be used here: the server process hook would miss host aliases.
 */
public final class GuestTaskAlias {
    private static volatile Set<String> sPackages = Collections.emptySet();
    private static volatile int sSlots;
    private static volatile boolean sLoaded;

    private GuestTaskAlias() {
    }

    public static String component(String packageName, int vpid) {
        if (packageName == null || vpid < 0) {
            return null;
        }
        ensureLoaded();
        if (vpid >= sSlots || !sPackages.contains(packageName)) {
            return null;
        }
        return "com.blacktwin.alias." + packageName.replace('.', '_') + "_P" + vpid;
    }

    private static void ensureLoaded() {
        if (sLoaded) {
            return;
        }
        synchronized (GuestTaskAlias.class) {
            if (sLoaded) {
                return;
            }
            Set<String> packages = new HashSet<>();
            int slots = 0;
            try {
                Context context = BlackBoxCore.getContext();
                int id = context.getResources().getIdentifier(
                        "guest_alias_packages", "raw", BlackBoxCore.getHostPkg());
                if (id != 0) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(
                            context.getResources().openRawResource(id), StandardCharsets.UTF_8));
                    try {
                        String header = reader.readLine();
                        if (header != null) {
                            slots = Integer.parseInt(header.trim());
                        }
                        String line;
                        while ((line = reader.readLine()) != null) {
                            line = line.trim();
                            if (line.length() > 0) {
                                packages.add(line);
                            }
                        }
                    } finally {
                        reader.close();
                    }
                }
            } catch (Throwable ignored) {
                packages.clear();
                slots = 0;
            }
            sPackages = packages;
            sSlots = slots;
            sLoaded = true;
        }
    }
}
