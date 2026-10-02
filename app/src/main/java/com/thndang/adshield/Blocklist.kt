package com.thndang.adshield

import android.content.Context
import java.util.Locale

class DomainBlocklist private constructor(
    private val domains: Set<String>
) {
    val size: Int
        get() = domains.size

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
        fun load(context: Context, assetName: String): DomainBlocklist {
            val domains = context.assets.open(assetName)
                .bufferedReader()
                .useLines { lines ->
                    lines.mapNotNull(::parseLine).toSet()
                }

            return DomainBlocklist(domains)
        }

        private fun parseLine(line: String): String? {
            val clean = line.substringBefore('#').trim()
            if (clean.isEmpty()) return null

            val token = clean.split(Regex("\\s+")).last()
            return normalize(token)
                .takeIf { it.contains('.') && !it.contains('/') }
        }

        private fun normalize(value: String): String =
            value.trim()
                .trimEnd('.')
                .lowercase(Locale.US)
    }
}
