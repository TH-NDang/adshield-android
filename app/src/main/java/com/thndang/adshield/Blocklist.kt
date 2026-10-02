package com.thndang.adshield

import android.content.Context
import java.io.File
import java.util.Locale

class DomainBlocklist private constructor(
    private val domains: Set<String>,
    private val wildcardRules: List<Regex>
) {
    val ruleCount: Int
        get() = domains.size + wildcardRules.size

    fun isBlocked(rawDomain: String): Boolean {
        var candidate = normalizeDomain(rawDomain)
        if (candidate.isEmpty()) return false

        while (true) {
            if (domains.contains(candidate)) return true
            if (wildcardRules.any { it.matches(candidate) }) return true

            val dot = candidate.indexOf('.')
            if (dot < 0) return false
            candidate = candidate.substring(dot + 1)
        }
    }

    companion object {
        const val REMOTE_FILE_NAME = "blocklist_remote.txt"

        fun load(context: Context): DomainBlocklist {
            val exact = LinkedHashSet<String>()
            val wildcard = LinkedHashSet<String>()

            context.assets.open("blocklist.txt")
                .bufferedReader()
                .useLines { lines ->
                    lines.forEach { addParsedRule(it, exact, wildcard) }
                }

            val remote = File(context.filesDir, REMOTE_FILE_NAME)
            if (remote.isFile) {
                remote.bufferedReader().useLines { lines ->
                    lines.forEach { addParsedRule(it, exact, wildcard) }
                }
            }

            val regexes = wildcard.mapNotNull(::wildcardToRegex)
            return DomainBlocklist(exact, regexes)
        }

        fun parseRule(line: String): String? {
            var clean = line.trim()
            if (
                clean.isEmpty() ||
                clean.startsWith("#") ||
                clean.startsWith("!") ||
                clean.startsWith("[") ||
                clean.startsWith("@@")
            ) {
                return null
            }

            if (clean.startsWith("||")) {
                clean = clean.substring(2)
                    .substringBefore("^")
                    .substringBefore("$")
                    .substringBefore("/")
            } else {
                clean = clean.substringBefore("#").trim()
                val parts = clean.split(Regex("\\s+"))
                if (parts.size >= 2 && looksLikeIp(parts.first())) {
                    clean = parts[1]
                } else {
                    clean = parts.firstOrNull().orEmpty()
                }

                clean = clean.substringBefore("$")
                    .substringBefore("^")
                    .substringBefore("/")
            }

            clean = clean
                .removePrefix("|")
                .removePrefix(".")
                .trim()
                .trimEnd('.')
                .lowercase(Locale.US)

            if (
                clean.isEmpty() ||
                clean == "localhost" ||
                clean.contains(":") ||
                !clean.contains(".")
            ) {
                return null
            }

            if (!clean.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '*' || it == '_' }) {
                return null
            }

            return clean
        }

        private fun addParsedRule(
            line: String,
            exact: MutableSet<String>,
            wildcard: MutableSet<String>
        ) {
            val rule = parseRule(line) ?: return
            if ('*' in rule) wildcard += rule else exact += rule
        }

        private fun looksLikeIp(value: String): Boolean =
            value == "0" ||
                value == "::" ||
                value.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))

        private fun normalizeDomain(value: String): String =
            value.trim().trimEnd('.').lowercase(Locale.US)

        private fun wildcardToRegex(rule: String): Regex? {
            return try {
                val escaped = Regex.escape(rule)
                    .replace("\\*", "[^.]*")
                Regex("^$escaped$", RegexOption.IGNORE_CASE)
            } catch (_: Exception) {
                null
            }
        }
    }
}
