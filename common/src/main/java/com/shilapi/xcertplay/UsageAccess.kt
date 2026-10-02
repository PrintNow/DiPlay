package com.shilapi.xcertplay

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.Process
import androidx.core.content.ContextCompat

/** The owner-enabled Usage Access that the cluster and home-screen monitors need. */
internal object UsageAccess {
    /** Always false before API 22, which has no public Usage Access. */
    fun granted(context: Context): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1 &&
        ContextCompat.getSystemService(context, AppOpsManager::class.java)
        ?.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
}
