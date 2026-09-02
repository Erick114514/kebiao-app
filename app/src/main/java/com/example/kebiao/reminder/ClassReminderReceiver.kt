package com.example.kebiao.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ClassReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            ClassReminderScheduler.rescheduleFromStorage(context)
            return
        }
        ClassReminderScheduler.showNotification(context, intent)
    }
}
