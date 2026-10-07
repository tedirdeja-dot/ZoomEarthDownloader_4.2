package com.satellite.wallpaper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class UnlockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_USER_PRESENT ||
            intent.action == Intent.ACTION_BOOT_COMPLETED
        ) {
            if (intent.action == Intent.ACTION_BOOT_COMPLETED) UnlockService.start(context)
            WallpaperRepository.refresh(context, source = "manifiesto")
        }
    }
}
