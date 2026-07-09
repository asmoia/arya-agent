// Copyright 2026 Arya Agent. Licensed under the Apache License, Version 2.0.
// Fork of PokeClaw — https://github.com/agents-io/PokeClaw

package io.agents.arya.tool.impl

import io.agents.arya.tool.BaseTool
import io.agents.arya.tool.ToolParameter
import io.agents.arya.tool.ToolResult
import java.util.Calendar

/**
 * Shamsi (Persian/Solar) calendar tool.
 */
class ShamsiCalendarTool : BaseTool() {

    override fun getName(): String = "shamsi_calendar"
    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter("action", "string", "Action: today, now, convert", true),
        ToolParameter("year", "integer", "Year for conversion", false),
        ToolParameter("month", "integer", "Month for conversion", false),
        ToolParameter("day", "integer", "Day for conversion", false),
        ToolParameter("direction", "string", "to_shamsi or to_gregorian (default: to_shamsi)", false)
    )
    override fun getDescriptionEN(): String = "Get Persian/Shamsi calendar date and convert between Gregorian and Shamsi dates"
    override fun getDescriptionCN(): String = "获取波斯/沙姆西日历日期并在公历和沙姆西日期之间转换"

    override fun execute(params: Map<String, Any>): ToolResult {
        val action = params["action"]?.toString()?.lowercase() ?: "today"
        return when (action) {
            "today" -> getTodayShamsi()
            "now" -> getNow()
            "convert" -> convertDate(params)
            else -> ToolResult.error("Unknown action: $action. Valid: today, now, convert")
        }
    }

    private fun getTodayShamsi(): ToolResult {
        val now = Calendar.getInstance()
        val shamsi = gregorianToShamsi(now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH))
        val dayOfWeek = getShamsiDayOfWeek(now.get(Calendar.DAY_OF_WEEK))
        val monthName = getShamsiMonthName(shamsi.month)
        return ToolResult.success("📅 امروز $dayOfWeek ${shamsi.day} $monthName ${shamsi.year}")
    }

    private fun getNow(): ToolResult {
        val now = Calendar.getInstance()
        val shamsi = gregorianToShamsi(now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH))
        val hour = now.get(Calendar.HOUR_OF_DAY)
        val minute = now.get(Calendar.MINUTE)
        val dayOfWeek = getShamsiDayOfWeek(now.get(Calendar.DAY_OF_WEEK))
        val monthName = getShamsiMonthName(shamsi.month)
        return ToolResult.success("📅 $dayOfWeek ${shamsi.day} $monthName ${shamsi.year} — 🕐 ${"%02d".format(hour)}:${"%02d".format(minute)}")
    }

    private fun convertDate(params: Map<String, Any>): ToolResult {
        val year = optionalInt(params, "year", 0)
        val month = optionalInt(params, "month", 0)
        val day = optionalInt(params, "day", 0)
        if (year == 0 || month == 0 || day == 0) return ToolResult.error("year, month, day required for conversion")
        val direction = optionalString(params, "direction", "to_shamsi")
        return if (direction == "to_shamsi") {
            val shamsi = gregorianToShamsi(year, month, day)
            ToolResult.success("$year/$month/$day (Gregorian) = ${shamsi.year}/${shamsi.month}/${shamsi.day} (Shamsi)")
        } else {
            val greg = shamsiToGregorian(year, month, day)
            ToolResult.success("$year/$month/$day (Shamsi) = ${greg.year}/${greg.month}/${greg.day} (Gregorian)")
        }
    }

    data class ShamsiDate(val year: Int, val month: Int, val day: Int)

    private fun gregorianToShamsi(gy: Int, gm: Int, gd: Int): ShamsiDate {
        val gDaysInMonth = intArrayOf(0, 31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        val jDaysInMonth = intArrayOf(0, 31, 31, 31, 31, 31, 31, 30, 30, 30, 30, 29, 30)
        var gy2 = gy - 1600; var gm2 = gm - 1; var gd2 = gd - 1
        var gDayNo = 365 * gy2 + (gy2 + 3) / 4 - (gy2 + 99) / 100 + (gy2 + 399) / 400
        for (i in 0 until gm2) gDayNo += gDaysInMonth[i + 1]
        if (gm2 > 1 && (gy2 % 4 == 0 && gy2 % 100 != 0) || (gy2 % 400 == 0)) gDayNo++
        gDayNo += gd2
        var jDayNo = gDayNo - 79
        val jNp = jDayNo / 12053; jDayNo %= 12053
        var jy = 979 + 33 * jNp + 4 * (jDayNo / 1461); jDayNo %= 1461
        if (jDayNo >= 366) { jy += (jDayNo - 1) / 365; jDayNo = (jDayNo - 1) % 365 }
        for (i in 1..12) { if (jDayNo < jDaysInMonth[i]) return ShamsiDate(jy, i, jDayNo + 1); jDayNo -= jDaysInMonth[i] }
        return ShamsiDate(jy, 12, 30)
    }

    private fun shamsiToGregorian(jy: Int, jm: Int, jd: Int): ShamsiDate {
        val gDaysInMonth = intArrayOf(0, 31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        var jy2 = jy - 979; var jm2 = jm - 1; var jd2 = jd - 1
        var jDayNo = 365 * jy2 + jy2 / 33 * 8 + (jy2 % 33 + 3) / 4
        for (i in 0 until jm2) jDayNo += intArrayOf(0, 31, 31, 31, 31, 31, 31, 30, 30, 30, 30, 29, 30)[i + 1]
        jDayNo += jd2
        var gDayNo = jDayNo + 79
        var gy2 = 1600 + 400 * (gDayNo / 146097); gDayNo %= 146097
        if (gDayNo >= 36525) { gDayNo--; gy2 += 100 * (gDayNo / 36524); gDayNo %= 36524; if (gDayNo >= 365) gDayNo++ }
        gy2 += 4 * (gDayNo / 1461); gDayNo %= 1461
        if (gDayNo >= 366) { gy2++; gDayNo -= 365 }
        for (i in 1..12) {
            val leap = (gy2 % 4 == 0 && gy2 % 100 != 0) || (gy2 % 400 == 0)
            val dim = if (i == 2 && leap) 29 else gDaysInMonth[i]
            if (gDayNo < dim) return ShamsiDate(gy2, i, gDayNo + 1)
            gDayNo -= dim
        }
        return ShamsiDate(gy2, 12, 31)
    }

    private fun getShamsiDayOfWeek(dayOfWeek: Int): String = when (dayOfWeek) {
        Calendar.SUNDAY -> "یکشنبه"; Calendar.MONDAY -> "دوشنبه"; Calendar.TUESDAY -> "سه‌شنبه"
        Calendar.WEDNESDAY -> "چهارشنبه"; Calendar.THURSDAY -> "پنجشنبه"; Calendar.FRIDAY -> "جمعه"
        Calendar.SATURDAY -> "شنبه"; else -> ""
    }

    private fun getShamsiMonthName(month: Int): String = when (month) {
        1 -> "فروردین"; 2 -> "اردیبهشت"; 3 -> "خرداد"; 4 -> "تیر"; 5 -> "مرداد"
        6 -> "شهریور"; 7 -> "مهر"; 8 -> "آبان"; 9 -> "آذر"; 10 -> "دی"
        11 -> "بهمن"; 12 -> "اسفند"; else -> ""
    }
}
