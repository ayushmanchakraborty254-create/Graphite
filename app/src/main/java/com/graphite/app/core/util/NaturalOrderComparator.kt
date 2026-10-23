package com.graphite.app.core.util

import java.util.Comparator

/**
 * Natural / Alphanumeric Order Comparator.
 *
 * Compares strings chunk-by-chunk such that embedded numerical sequences
 * (e.g., "The Adventures of Tintin 24" vs "The Adventures of Tintin 5")
 * are evaluated by numerical magnitude rather than ASCII character codes.
 */
object NaturalOrderComparator : Comparator<String> {
    private val chunkPattern = Regex("(\\d+)|(\\D+)")

    override fun compare(s1: String?, s2: String?): Int {
        if (s1 == null && s2 == null) return 0
        if (s1 == null) return -1
        if (s2 == null) return 1

        val chunks1 = chunkPattern.findAll(s1).map { it.value }.toList()
        val chunks2 = chunkPattern.findAll(s2).map { it.value }.toList()

        val minLen = minOf(chunks1.size, chunks2.size)
        for (i in 0 until minLen) {
            val c1 = chunks1[i]
            val c2 = chunks2[i]

            val isDigit1 = c1[0].isDigit()
            val isDigit2 = c2[0].isDigit()

            if (isDigit1 && isDigit2) {
                val num1 = c1.toBigIntegerOrNull() ?: java.math.BigInteger.ZERO
                val num2 = c2.toBigIntegerOrNull() ?: java.math.BigInteger.ZERO
                val cmp = num1.compareTo(num2)
                if (cmp != 0) return cmp
            } else {
                val cmp = String.CASE_INSENSITIVE_ORDER.compare(c1, c2)
                if (cmp != 0) return cmp
            }
        }
        return chunks1.size.compareTo(chunks2.size)
    }
}
