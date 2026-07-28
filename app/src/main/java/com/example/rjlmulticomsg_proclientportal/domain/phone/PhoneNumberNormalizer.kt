package com.example.rjlmulticomsg_proclientportal.domain.phone

/**
 * Canonical telephone handling for GSM whitelist and property numbers.
 *
 * Storage format: E.164 (e.g. +61412345678).
 * Australian mobiles beginning with 0 convert to +61…
 */
object PhoneNumberNormalizer {

    private val WITHHELD = setOf(
        "private", "withheld", "unknown", "restricted", "anonymous",
        "unavailable", "not available", "no caller id", "nocallerid",
        "hidden", "blocked", "null", "none", "n/a", "na"
    )

    sealed class Result {
        data class Valid(val e164: String) : Result()
        data class Invalid(val reason: String) : Result()
    }

    /**
     * Normalise raw input to E.164, or [Result.Invalid] with a reason.
     * Default region: Australia (AU / +61) for bare national numbers.
     */
    fun normalize(
        raw: String?,
        defaultRegion: String = "AU"
    ): Result {
        if (raw.isNullOrBlank()) {
            return Result.Invalid("Number is empty")
        }
        val trimmed = raw.trim()
        val lower = trimmed.lowercase()
        if (lower in WITHHELD || WITHHELD.any { lower.contains(it) }) {
            return Result.Invalid("Private or withheld number")
        }

        // Strip spaces, brackets, hyphens, dots, and other punctuation (keep + and digits)
        val cleaned = buildString {
            for (c in trimmed) {
                when {
                    c.isDigit() -> append(c)
                    c == '+' && isEmpty() -> append(c)
                }
            }
        }
        if (cleaned.isEmpty() || cleaned == "+") {
            return Result.Invalid("No digits found")
        }

        // Reject impossible short / long lengths early
        val digitsOnly = cleaned.filter { it.isDigit() }
        if (digitsOnly.length < 8) {
            return Result.Invalid("Number too short")
        }
        if (digitsOnly.length > 15) {
            return Result.Invalid("Number too long for E.164")
        }

        val e164 = when {
            cleaned.startsWith("+") -> {
                if (digitsOnly.length < 8 || digitsOnly.length > 15) {
                    return Result.Invalid("Invalid international number length")
                }
                "+$digitsOnly"
            }
            // 00 international prefix
            cleaned.startsWith("00") && cleaned.length > 4 -> {
                val rest = cleaned.drop(2)
                if (rest.length < 8) return Result.Invalid("Invalid international number")
                "+$rest"
            }
            // Australian national: 0XXXXXXXXX
            defaultRegion.equals("AU", ignoreCase = true) && cleaned.startsWith("0") -> {
                val national = cleaned.drop(1)
                if (national.length !in 8..10) {
                    return Result.Invalid("Invalid Australian national number")
                }
                // Mobile AU is 9 digits after 0 (04xx xxx xxx); landlines 8–9 after 0
                "+61$national"
            }
            // Bare AU mobile without leading 0: 4xxxxxxxx (9 digits)
            defaultRegion.equals("AU", ignoreCase = true) &&
                cleaned.startsWith("4") && cleaned.length == 9 -> {
                "+61$cleaned"
            }
            // Already country code without + for AU (61…)
            defaultRegion.equals("AU", ignoreCase = true) &&
                cleaned.startsWith("61") && cleaned.length in 10..12 -> {
                "+$cleaned"
            }
            // Generic: treat as international without +
            cleaned.length in 10..15 -> "+$cleaned"
            else -> return Result.Invalid("Malformed or unsupported number")
        }

        return validateE164(e164)
    }

    fun validateE164(e164: String): Result {
        if (!e164.startsWith("+")) {
            return Result.Invalid("E.164 must start with +")
        }
        val digits = e164.drop(1)
        if (digits.any { !it.isDigit() }) {
            return Result.Invalid("E.164 contains non-digits")
        }
        if (digits.length !in 8..15) {
            return Result.Invalid("E.164 length out of range")
        }
        // AU mobile: +614xxxxxxxx (11 digits after +, country 61 + 9 national mobile)
        if (digits.startsWith("61")) {
            val national = digits.drop(2)
            if (national.isEmpty() || national.startsWith("0")) {
                return Result.Invalid("Invalid Australian country-code form")
            }
            if (national.length !in 8..10) {
                return Result.Invalid("Invalid Australian number length")
            }
        }
        return Result.Valid("+$digits")
    }

    /** True when [raw] is private / withheld / unknown style. */
    fun isWithheldOrPrivate(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return true
        val lower = raw.trim().lowercase()
        return lower in WITHHELD || WITHHELD.any { lower.contains(it) }
    }

    /**
     * Friendly display for AU mobiles: +61 412 345 678 style,
     * otherwise spaced E.164 groups.
     */
    fun formatDisplay(e164: String): String {
        val valid = when (val r = validateE164(e164)) {
            is Result.Valid -> r.e164
            is Result.Invalid -> return e164
        }
        val digits = valid.drop(1)
        return if (digits.startsWith("61") && digits.length == 11) {
            val n = digits.drop(2) // 9 digits mobile typically
            "+61 ${n.take(3)} ${n.drop(3).take(3)} ${n.drop(6)}"
        } else if (digits.startsWith("61") && digits.length == 10) {
            val n = digits.drop(2)
            "+61 ${n.take(1)} ${n.drop(1).take(4)} ${n.drop(5)}"
        } else {
            // Group remaining digits in 3s after country-ish prefix
            "+$digits"
        }
    }

    /** Mask for logs: keep country + last 3 digits. */
    fun maskForLog(e164: String): String {
        val digits = e164.filter { it.isDigit() }
        if (digits.length <= 4) return "••••"
        return "+•••${digits.takeLast(3)}"
    }

    fun requireValid(raw: String?, label: String = "Phone number"): String {
        return when (val r = normalize(raw)) {
            is Result.Valid -> r.e164
            is Result.Invalid -> throw IllegalArgumentException("$label: ${r.reason}")
        }
    }
}
