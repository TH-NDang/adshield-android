package com.thndang.adshield

import android.content.Context
import java.io.File
import java.util.Locale

class DomainBlocklist private constructor(
    private val hashes: LongArray
) {
    val size: Int
        get() = hashes.size

    fun isBlocked(rawDomain: String): Boolean {
        var candidate = normalize(rawDomain)
        if (candidate.isEmpty()) return false

        while (true) {
            if (containsHash(hash64(candidate))) return true

            val dot = candidate.indexOf('.')
            if (dot < 0) return false

            candidate = candidate.substring(dot + 1)
        }
    }

    private fun containsHash(value: Long): Boolean =
        hashes.binarySearch(value) >= 0

    companion object {
        fun empty(): DomainBlocklist =
            DomainBlocklist(LongArray(0))

        fun load(
            context: Context,
            assetName: String,
            remoteFileName: String? = null
        ): DomainBlocklist {
            val builder = LongArrayBuilder()

            context.assets.open(assetName)
                .bufferedReader()
                .useLines { lines ->
                    lines.forEach { line ->
                        parseRule(line)?.let { domain ->
                            builder.add(hash64(domain))
                        }
                    }
                }

            if (remoteFileName != null) {
                val remote = File(
                    context.filesDir,
                    remoteFileName
                )

                if (remote.isFile) {
                    remote.bufferedReader()
                        .useLines { lines ->
                            lines.forEach { line ->
                                parseRule(line)?.let { domain ->
                                    builder.add(hash64(domain))
                                }
                            }
                        }
                }
            }

            return DomainBlocklist(
                builder.toSortedUniqueArray()
            )
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

        private fun looksLikeHostsPrefix(
            value: String
        ): Boolean =
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

        private fun hash64(value: String): Long {
            var hash = -3750763034362895579L
            val prime = 1099511628211L

            for (ch in value) {
                hash = hash xor ch.code.toLong()
                hash *= prime
            }

            return hash
        }
    }
}

private class LongArrayBuilder(
    initialCapacity: Int = 16_384
) {
    private var data = LongArray(initialCapacity)
    private var count = 0

    fun add(value: Long) {
        if (count == data.size) {
            data = data.copyOf(
                (data.size * 2)
                    .coerceAtLeast(data.size + 1)
            )
        }

        data[count] = value
        count++
    }

    fun toSortedUniqueArray(): LongArray {
        if (count == 0) return LongArray(0)

        val result = data.copyOf(count)
        result.sort()

        var write = 1

        for (read in 1 until result.size) {
            if (result[read] != result[write - 1]) {
                result[write] = result[read]
                write++
            }
        }

        return if (write == result.size) {
            result
        } else {
            result.copyOf(write)
        }
    }
}
