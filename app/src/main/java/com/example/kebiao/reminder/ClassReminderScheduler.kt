package com.example.kebiao.reminder

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.kebiao.MainActivity
import com.example.kebiao.R
import com.example.kebiao.model.Course
import com.example.kebiao.model.DEFAULT_PERIOD_TIMES
import com.example.kebiao.storage.ScheduleStore
import java.util.Calendar

object ClassReminderScheduler {

    private const val CHANNEL_ID = "class_reminders"
    private const val ACTION_REMIND = "com.example.kebiao.action.CLASS_REMIND"
    private const val EXTRA_TITLE = "extra_title"
    private const val EXTRA_TEXT = "extra_text"
    private const val EXTRA_REQUEST_CODE = "extra_request_code"
    private const val TEST_REQUEST_CODE = 9999

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.notification_channel_description)
            }
            manager.createNotificationChannel(channel)
        }
    }

    fun schedule(
        context: Context,
        courses: List<Course>,
        periodTimes: List<String>,
        leadMinutes: Int
    ) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        cancelAll(context)
        createChannel(context)
        if (leadMinutes <= 0 || courses.isEmpty()) return

        val now = System.currentTimeMillis()
        val slots = courses.groupBy { it.dayIndex to it.startPeriod }
        for ((slot, slotCourses) in slots) {
            val dayIndex = slot.first
            val period = slot.second
            val (hour, minute) = parseStartTime(periodTimes, period)
            val calendar = Calendar.getInstance().apply {
                set(Calendar.DAY_OF_WEEK, DAY_OF_WEEK_BY_INDEX[dayIndex])
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            var triggerAt = calendar.timeInMillis - leadMinutes * 60_000L
            if (triggerAt < now) {
                calendar.add(Calendar.DAY_OF_YEAR, 7)
                triggerAt = calendar.timeInMillis - leadMinutes * 60_000L
            }
            val requestCode = dayIndex * 100 + period
            val title = context.getString(R.string.notification_title)
            val text = buildText(context, slotCourses, period)
            val intent = Intent(context, ClassReminderReceiver::class.java)
                .setAction(ACTION_REMIND)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_TEXT, text)
                .putExtra(EXTRA_REQUEST_CODE, requestCode)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.setRepeating(
                AlarmManager.RTC_WAKEUP,
                triggerAt,
                AlarmManager.INTERVAL_DAY * 7,
                pendingIntent
            )
        }
    }

    fun cancelAll(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        for (dayIndex in 0..6) {
            for (period in 1..13) {
                val requestCode = dayIndex * 100 + period
                val intent = Intent(context, ClassReminderReceiver::class.java)
                    .setAction(ACTION_REMIND)
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    requestCode,
                    intent,
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                )
                if (pendingIntent != null) {
                    alarmManager.cancel(pendingIntent)
                    pendingIntent.cancel()
                }
            }
        }
    }

    fun rescheduleFromStorage(context: Context) {
        val store = ScheduleStore(context)
        val saved = store.loadSchedule() ?: return
        schedule(context, saved.first, saved.second, store.loadReminderMinutes())
    }

    fun showNotification(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        createChannel(context)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: context.getString(R.string.notification_title)
        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
        val requestCode = intent.getIntExtra(EXTRA_REQUEST_CODE, 0)
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bell)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        NotificationManagerCompat.from(context).notify(requestCode, notification)
    }

    fun sendTestNotification(context: Context, leadMinutes: Int) {
        val text = if (leadMinutes > 0) {
            context.getString(R.string.reminder_test_text, leadMinutes)
        } else {
            context.getString(R.string.reminder_test_off_text)
        }
        val intent = Intent(context, ClassReminderReceiver::class.java)
            .putExtra(EXTRA_TITLE, context.getString(R.string.notification_title))
            .putExtra(EXTRA_TEXT, text)
            .putExtra(EXTRA_REQUEST_CODE, TEST_REQUEST_CODE)
        showNotification(context, intent)
    }

    private fun buildText(context: Context, courses: List<Course>, period: Int): String {
        val names = courses.map { it.displayName }.distinct().joinToString("、")
        val location = courses.firstOrNull { it.location.isNotBlank() }?.location
        val periodText = context.getString(R.string.notification_period, period)
        return if (location.isNullOrBlank()) {
            context.getString(R.string.notification_text_no_location, names, periodText)
        } else {
            context.getString(R.string.notification_text, names, periodText, location)
        }
    }

    private fun parseStartTime(periodTimes: List<String>, period: Int): Pair<Int, Int> {
        val label = periodTimes.getOrNull(period - 1)
            ?: DEFAULT_PERIOD_TIMES.getOrNull(period - 1)
            ?: ""
        val match = TIME_REGEX.find(label)
        if (match != null) {
            val hour = match.groupValues[1].toIntOrNull() ?: 8
            val minute = match.groupValues[2].toIntOrNull() ?: 0
            return hour to minute
        }
        return 8 to 0
    }

    private val TIME_REGEX = Regex("(\\d{1,2}):(\\d{2})")

    private val DAY_OF_WEEK_BY_INDEX = intArrayOf(
        Calendar.MONDAY,
        Calendar.TUESDAY,
        Calendar.WEDNESDAY,
        Calendar.THURSDAY,
        Calendar.FRIDAY,
        Calendar.SATURDAY,
        Calendar.SUNDAY
    )
}
