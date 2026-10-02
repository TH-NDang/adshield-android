package com.thndang.adshield

import android.content.Context
import java.io.File
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
        fun load(
            context: Context,
            assetName: String,
            remoteFileName: String? = null
        ): DomainBlocklist {
            val domains = HashSet<String>(131_072)

            context.assets.open(assetName)
                .bufferedReader()
                .useLines { lines ->
                    lines.forEach { line ->
                        parseRule(line)?.let(domains::add)
                    }
                }

            if (remoteFileName != null) {
                val remote = File(context.filesDir, remoteFileName)
                if (remote.isFile) {
                    remote.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            parseRule(line)?.let(domains::add)
                        }
                    }
                }
            }

            return DomainBlocklist(domains)
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
                if (
                    parts.size >= 2 &&
                    looksLikeHostsPrefix(parts.first())
                ) {
                    clean = parts[1]
                } else {
                    clean = parts.firstOrNull().orEmpty()
                }

                clean = clean
                    .substringBefore("$")
                    .substringBefore("^")
                    .substringBefore("/")
            }

            clean = normalize(
                clean
                    .removePrefix("|")
                    .removePrefix(".")
            )

            if (
                clean.isEmpty() ||
                clean == "localhost" ||
                clean.contains(":") ||
                clean.contains("*") ||
                !clean.contains(".")
            ) {
                return null
            }

            if (
                !clean.all {
                    it.isLetterOrDigit() ||
                        it == '.' ||
                        it == '-' ||
                        it == '_'
                }
            ) {
                return null
            }

            return clean
        }

        private fun looksLikeHostsPrefix(value: String): Boolean =
            value == "0" ||
                value == "::" ||
                value == "localhost" ||
                value.matches(
                    Regex("""\d{1,3}(\.\d{1,3}){3}""")
                )

        private fun normalize(value: String): String =
            value.trim()
                .trimEnd('.')
                .lowercase(Locale.US)
    }
}
