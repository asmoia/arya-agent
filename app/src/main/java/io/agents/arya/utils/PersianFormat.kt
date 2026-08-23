package io.agents.arya.utils

/**
 * Overflow #9 — unify Persian/English numeral and date-ish formatting.
 * Pure JVM.
 */
object PersianFormat {
    private val toFa = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')
    private val fromFa = mapOf(
        '۰' to '0', '۱' to '1', '۲' to '2', '۳' to '3', '۴' to '4',
        '۵' to '5', '۶' to '6', '۷' to '7', '۸' to '8', '۹' to '9',
        '٠' to '0', '١' to '1', '٢' to '2', '٣' to '3', '٤' to '4',
        '٥' to '5', '٦' to '6', '٧' to '7', '٨' to '8', '٩' to '9',
    )

    fun toPersianDigits(input: String): String = buildString(input.length) {
        input.forEach { ch ->
            append(if (ch in '0'..'9') toFa[ch - '0'] else ch)
        }
    }

    fun toLatinDigits(input: String): String = buildString(input.length) {
        input.forEach { ch -> append(fromFa[ch] ?: ch) }
    }

    fun formatPercent(value: Int, persian: Boolean): String {
        val body = "$value%"
        return if (persian) toPersianDigits(body) else body
    }

    fun formatFileSize(bytes: Long, persian: Boolean): String {
        val mb = bytes / (1024.0 * 1024.0)
        val text = if (mb >= 1024) String.format("%.1f GB", mb / 1024.0) else String.format("%.0f MB", mb)
        return if (persian) toPersianDigits(text) else text
    }
}
