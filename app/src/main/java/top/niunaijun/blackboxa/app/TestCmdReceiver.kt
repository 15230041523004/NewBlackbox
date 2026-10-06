package top.niunaijun.blackboxa.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File
import top.niunaijun.blackbox.BlackBoxCore

class TestCmdReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BlackTwinTestCmd"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent == null) return
        val cmd = intent.getStringExtra("cmd") ?: return
        val userId = intent.getIntExtra("userId", 0)
        val pkg = intent.getStringExtra("pkg") ?: ""
        val apk = intent.getStringExtra("apk") ?: ""

        Log.i(TAG, "START_CMD: cmd=$cmd userId=$userId pkg=$pkg apk=$apk")
        try {
            when (cmd) {
                "create_user" -> {
                    val res = BlackBoxCore.get().createUser(userId)
                    Log.i(TAG, "RESULT_CMD: createUser $userId -> $res")
                }
                "install" -> {
                    val file = File(apk)
                    if (!file.exists()) {
                        Log.e(TAG, "RESULT_CMD: APK file does not exist: $apk")
                        return
                    }
                    val res = BlackBoxCore.get().installPackageAsUser(file, userId)
                    Log.i(TAG, "RESULT_CMD: install $apk user=$userId success=${res.success} msg=${res.msg}")
                }
                "launch" -> {
                    val res = BlackBoxCore.get().launchApk(pkg, userId)
                    Log.i(TAG, "RESULT_CMD: launch $pkg user=$userId -> $res")
                }
                "stop" -> {
                    BlackBoxCore.get().stopPackage(pkg, userId)
                    Log.i(TAG, "RESULT_CMD: stop $pkg user=$userId -> done")
                }
                "uninstall" -> {
                    BlackBoxCore.get().uninstallPackageAsUser(pkg, userId)
                    Log.i(TAG, "RESULT_CMD: uninstall $pkg user=$userId -> done")
                }
                "status" -> {
                    val users = BlackBoxCore.get().users.map { it.id }
                    val installed = if (pkg.isNotEmpty()) BlackBoxCore.get().isInstalled(pkg, userId) else false
                    Log.i(TAG, "RESULT_CMD: users=$users pkg=$pkg user=$userId installed=$installed")
                }
                "is_support_gms" -> {
                    val supported = BlackBoxCore.get().isSupportGms
                    Log.i(TAG, "RESULT_CMD: is_support_gms -> $supported")
                }
                "is_install_gms" -> {
                    val installed = BlackBoxCore.get().isInstallGms(userId)
                    Log.i(TAG, "RESULT_CMD: is_install_gms user=$userId -> $installed")
                }
                "install_gms" -> {
                    val res = BlackBoxCore.get().installGms(userId)
                    Log.i(TAG, "RESULT_CMD: install_gms user=$userId success=${res.success} msg=${res.msg}")
                }
                "uninstall_gms" -> {
                    val res = BlackBoxCore.get().uninstallGms(userId)
                    Log.i(TAG, "RESULT_CMD: uninstall_gms user=$userId -> $res")
                }
                "list_packages" -> {
                    val pkgs = BlackBoxCore.get().getInstalledPackages(0, userId).map { it.packageName }
                    Log.i(TAG, "RESULT_CMD: list_packages user=$userId pkgs=$pkgs")
                }
                else -> {
                    Log.w(TAG, "RESULT_CMD: Unknown cmd: $cmd")
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "ERROR_CMD: cmd=$cmd exception=${t.message}", t)
        }
    }
}

