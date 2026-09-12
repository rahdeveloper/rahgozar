package com.rahgozar.app.util

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import com.rahgozar.app.dto.AppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AppManagerUtil {
    /**
     * Load the apps the user can pick for per-app routing.
     *
     * Enumerated through the MAIN/LAUNCHER intent, not `getInstalledPackages`.
     * The latter needs QUERY_ALL_PACKAGES on Android 11+, which Google does not
     * grant for a per-app VPN — so the picker is fed the set of launchable apps
     * declared in the manifest's <queries>, which is exactly the set a user
     * recognises and would ever choose to route. Apps with no launcher icon
     * (background-only services) are intentionally not offered.
     *
     * @param context The context to use.
     * @return A list of AppInfo objects representing the launchable applications.
     */
    suspend fun loadNetworkAppList(context: Context): ArrayList<AppInfo> =
        withContext(Dispatchers.IO) {
            val packageManager = context.packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = packageManager.queryIntentActivities(launcher, 0)

            val apps = ArrayList<AppInfo>()
            val seen = HashSet<String>()

            for (info in resolved) {
                val applicationInfo = info.activityInfo?.applicationInfo ?: continue
                val packageName = applicationInfo.packageName
                // One row per app: an app with several launcher activities
                // resolves more than once, and our own package is not routable.
                if (packageName == context.packageName) continue
                if (!seen.add(packageName)) continue

                val appName = applicationInfo.loadLabel(packageManager).toString()
                val appIcon = applicationInfo.loadIcon(packageManager) ?: continue
                val isSystemApp = applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM > 0

                apps.add(AppInfo(appName, packageName, appIcon, isSystemApp, 0))
            }

            return@withContext apps
        }

    fun getLastUpdateTime(context: Context): Long =
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime

}
