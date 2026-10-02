package com.thndang.adshield

import android.content.Context
import java.util.Locale

class DomainBlocklist private constructor(
    private val domains: Set<String>
) {
    fun isBlocked(rawDomain: String): Boolean {
        var candidate = normalize(rawDomain)
        if (candidate.isEmpty()) return false

        while (true) {
            if (domains.contains(candidate)) return true
            val dot = candidate.indexOf('.')
            if (dot < 0) return false
            candidate = candidate.substring(dot + 1)
        }
    }

    companion object {
        fun load(context: Context): DomainBlocklist {
            val domains = context.assets.open("blocklist.txt")
                .bufferedReader()
                .useLines { lines ->
                    lines.mapNotNull { line ->
                        val clean = line.substringBefore('#').trim()
                        if (clean.isEmpty()) {
                            null
                        } else {
                            val token = clean.split(Regex("\\s+")).last()
                            normalize(token).takeIf { it.contains('.') }
                        }
                    }.toSet()
                }
            return DomainBlocklist(domains)
        }

        private fun normalize(value: String): String =
            value.trim()
                .trimEnd('.')
                .lowercase(Locale.US)
    }
}
