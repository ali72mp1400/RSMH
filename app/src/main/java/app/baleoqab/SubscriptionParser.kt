package app.baleoqab

import android.util.Base64
import java.nio.charset.StandardCharsets

object SubscriptionParser {

    fun parseConfig(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return ""

        // بررسی اینکه آیا لینک Base64 است یا متن معمولی
        return if (isBase64(trimmed)) {
            try {
                val decodedBytes = Base64.decode(trimmed, Base64.DEFAULT)
                String(decodedBytes, StandardCharsets.UTF_8)
            } catch (e: Exception) {
                trimmed
            }
        } else {
            trimmed
        }
    }

    private fun isBase64(str: String): Boolean {
        // یک بررسی ساده برای فرمت Base64
        return if (str.contains("://") || str.contains("\n")) {
            false
        } else {
            try {
                Base64.decode(str, Base64.DEFAULT)
                true
            } catch (e: IllegalArgumentException) {
                false
            }
        }
    }
}
