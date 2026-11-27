package com.example

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val workspaceUri = Uri.parse("https://alifatemii.ir/safesize/")

/** Open the same HTTPS workspace with the user's browser session. No credentials,
 * exchange API keys, Room records, or JS bridge cross the native/web boundary. */
private fun launchWorkspace(context: Context, customTab: Boolean): Boolean {
    if (customTab) {
        try {
            CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setUrlBarHidingEnabled(false)
                .build()
                .launchUrl(context, workspaceUri)
            return true
        } catch (_: ActivityNotFoundException) {
            // A regular HTTPS browser is the safe fallback.
        } catch (_: SecurityException) {
        }
    }
    return try {
        context.startActivity(Intent(Intent.ACTION_VIEW, workspaceUri))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

@Composable
fun OnlineWorkspaceScreen() {
    val context = LocalContext.current
    var unavailable by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("فضای کاری مشترک SafeSize", style = MaterialTheme.typography.headlineSmall)
        Text("همان نمودارها، ابزارهای ترسیم، اندیکاتورها، ماشین‌حساب، Replay و ژورنال آزمایشی نسخه وب؛ بدون نیاز به انتشار APK برای هر تغییر وب.")
        Button(
            onClick = { unavailable = !launchWorkspace(context, true) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("باز کردن فضای کاری آنلاین") }
        OutlinedButton(
            onClick = { unavailable = !launchWorkspace(context, false) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("باز کردن در مرورگر برای اعلان و دانلود") }
        if (unavailable) Text("مرورگر HTTPS در دستگاه پیدا نشد.", color = MaterialTheme.colorScheme.error)
        Text("ورود و همگام‌سازی", style = MaterialTheme.typography.titleMedium)
        Text("با همان حساب وردپرس وارد شوید. در فضای کاری گزینه همگام‌سازی را باز کنید. برای اعلان پس‌زمینه، مجوز اعلان مرورگر را فعال کنید. پشتیبانی اعلان به مرورگر و تنظیمات Android وابسته است.")
        HorizontalDivider()
        Text("اطلاعات محلی قبلی", style = MaterialTheme.typography.titleMedium)
        Text("داشبورد، ماشین‌حساب و ژورنال بومی قبلی حذف نشده‌اند. داده‌های Room به‌صورت خودکار به ژورنال آزمایشی وب منتقل نمی‌شوند. برای کار مشترک بین دستگاه‌ها از فضای کاری آنلاین استفاده کنید.")
    }
}
