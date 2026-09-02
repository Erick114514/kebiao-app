package com.example.kebiao.storage

import android.content.Context
import com.example.kebiao.model.Course
import com.example.kebiao.model.DEFAULT_PERIOD_TIMES
import org.json.JSONArray
import org.json.JSONObject

class ScheduleStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveSchedule(courses: List<Course>, periodTimes: List<String>) {
        val coursesJson = JSONArray()
        courses.forEach { course ->
            coursesJson.put(
                JSONObject().apply {
                    put("name", course.name)
                    put("teacher", course.teacher)
                    put("weeks", course.weeks)
                    put("location", course.location)
                    put("day", course.dayIndex)
                    put("start", course.startPeriod)
                    put("end", course.endPeriod)
                }
            )
        }
        val timesJson = JSONArray()
        periodTimes.forEach { timesJson.put(it) }
        prefs.edit()
            .putString(KEY_COURSES, coursesJson.toString())
            .putString(KEY_TIMES, timesJson.toString())
            .apply()
    }

    fun loadSchedule(): Pair<List<Course>, List<String>>? {
        val rawCourses = prefs.getString(KEY_COURSES, null) ?: return null
        val rawTimes = prefs.getString(KEY_TIMES, null)
        val courses = mutableListOf<Course>()
        val coursesArray = JSONArray(rawCourses)
        for (i in 0 until coursesArray.length()) {
            val item = coursesArray.getJSONObject(i)
            courses += Course(
                name = item.optString("name"),
                teacher = item.optString("teacher"),
                weeks = item.optString("weeks"),
                location = item.optString("location"),
                dayIndex = item.optInt("day", 0),
                startPeriod = item.optInt("start", 1),
                endPeriod = item.optInt("end", 1)
            )
        }
        val times = if (rawTimes.isNullOrBlank()) {
            DEFAULT_PERIOD_TIMES
        } else {
            val timesArray = JSONArray(rawTimes)
            (0 until timesArray.length()).map { timesArray.getString(it) }
        }
        return courses to times
    }

    fun clearSchedule() {
        prefs.edit()
            .remove(KEY_COURSES)
            .remove(KEY_TIMES)
            .apply()
    }

    fun saveReminderMinutes(minutes: Int) {
        prefs.edit().putInt(KEY_REMINDER_MINUTES, minutes).apply()
    }

    fun loadReminderMinutes(defaultValue: Int = DEFAULT_REMINDER_MINUTES): Int {
        return prefs.getInt(KEY_REMINDER_MINUTES, defaultValue)
    }

    companion object {
        private const val PREFS_NAME = "kebiao_schedule"
        private const val KEY_COURSES = "courses"
        private const val KEY_TIMES = "period_times"
        private const val KEY_REMINDER_MINUTES = "reminder_minutes"
        const val DEFAULT_REMINDER_MINUTES = 15
    }
}
