package com.thndang.adshield

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object FilterUpdater {

    const val MAIN_REMOTE_FILE = "filters_aggressive.txt"
    const val POPUP_REMOTE_FILE = "filters_popup.txt"
    const val UPDATE_INTERVAL_MS = 24L * 60L * 60L * 1000L

    data class Result(
        val downloadedRules: Int,
        val popupRules: Int,
        val sourceSummary: String
    )

    private data class Source(
        val name: String,
        val urls: List<String>,
        val destination: Destination,
        val required: Boolean = false
    )

    private enum class Destination {
        MAIN,
        POPUP
    }

    private val sources = listOf(
        Source(
            name = "HaGeZi Ultimate",
            urls = listOf(
                "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/wildcard/ultimate-onlydomains.txt",
                "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/ultimate-onlydomains.txt"
            ),
            destination = Destination.MAIN,
            required = true
        ),
        Source(
            name = "HaGeZi TIF Mini",
            urls = listOf(
                "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/wildcard/tif.mini-onlydomains.txt",
                "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/tif.mini-onlydomains.txt"
            ),
            destination = Destination.MAIN
        ),
        Source(
            name = "HaGeZi DoH bypass",
            urls = listOf(
                "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/wildcard/doh-onlydomains.txt",
                "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/doh-onlydomains.txt"
            ),
            destination = Destination.MAIN
        ),
        Source(
            name = "hostsVN",
            urls = listOf(
                "https://raw.githubusercontent.com/bigdargon/hostsVN/master/option/domain.txt"
            ),
            destination = Destination.MAIN
        ),
        Source(
            name = "ABPVN domain",
            urls = listOf(
                "https://raw.githubusercontent.com/abpvn/abpvn/master/filter/src/abpvn_ad_domain.txt"
            ),
            destination = Destination.MAIN
        ),
        Source(
            name = "HaGeZi Pop-Up Ads",
            urls = listOf(
                "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/wildcard/popupads-onlydomains.txt",
                "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/popupads-onlydomains.txt"
            ),
            destination = Destination.POPUP,
            required = true
        )
    )

    fun isUpdateNeeded(context: Context): Boolean {
        val prefs = context.getSharedPreferences(
            MainActivity.PREFS,
            Context.MODE_PRIVATE
        )
        val last = prefs.getLong(
            MainActivity.KEY_FILTER_UPDATED_AT,
            0L
        )
        val mainFile = File(context.filesDir, MAIN_REMOTE_FILE)
        val popupFile = File(context.filesDir, POPUP_REMOTE_FILE)

        if (!mainFile.isFile || !popupFile.isFile) return true
        if (last <= 0L) return true

        return System.currentTimeMillis() - last >=
            UPDATE_INTERVAL_MS
    }

    fun update(context: Context): Result {
        val mainTemp =
            File(context.filesDir, MAIN_REMOTE_FILE + ".tmp")
        val popupTemp =
            File(context.filesDir, POPUP_REMOTE_FILE + ".tmp")

        mainTemp.delete()
        popupTemp.delete()

        var mainCount = 0
        var popupCount = 0
        val okSources = mutableListOf<String>()
        val failedSources = mutableListOf<String>()

        try {
            mainTemp.bufferedWriter().use { mainWriter ->
                popupTemp.bufferedWriter().use { popupWriter ->
                    for (source in sources) {
                        val success = runCatching {
                            downloadFirstAvailable(
                                source.urls
                            ) { line ->
                                val domain =
                                    DomainBlocklist.parseRule(line)
                                        ?: return@downloadFirstAvailable

                                when (source.destination) {
                                    Destination.MAIN -> {
                                        mainWriter.append(domain)
                                        mainWriter.newLine()
                                        mainCount++
                                    }

                                    Destination.POPUP -> {
                                        popupWriter.append(domain)
                                        popupWriter.newLine()
                                        popupCount++
                                    }
                                }
                            }
                        }.isSuccess

                        if (success) {
                            okSources += source.name
                        } else {
                            failedSources += source.name
                            if (source.required) {
                                throw IllegalStateException(
                                    "Không tải được nguồn bắt buộc: " +
                                        source.name
                                )
                            }
                        }
                    }
                }
            }

            if (mainCount < MIN_MAIN_RULES) {
                throw IllegalStateException(
                    "Bộ lọc tải về quá nhỏ: " +
                        mainCount +
                        " rules"
                )
            }

            if (popupCount < MIN_POPUP_RULES) {
                throw IllegalStateException(
                    "Bộ lọc popup tải về quá nhỏ: " +
                        popupCount +
                        " rules"
                )
            }

            atomicReplace(
                mainTemp,
                File(context.filesDir, MAIN_REMOTE_FILE)
            )
            atomicReplace(
                popupTemp,
                File(context.filesDir, POPUP_REMOTE_FILE)
            )

            val summary = buildString {
                append(okSources.joinToString(", "))
                if (failedSources.isNotEmpty()) {
                    append(" | lỗi: ")
                    append(failedSources.joinToString(", "))
                }
            }

            context.getSharedPreferences(
                MainActivity.PREFS,
                Context.MODE_PRIVATE
            )
                .edit()
                .putLong(
                    MainActivity.KEY_FILTER_UPDATED_AT,
                    System.currentTimeMillis()
                )
                .putInt(
                    MainActivity.KEY_FILTER_DOWNLOADED_RULES,
                    mainCount
                )
                .putInt(
                    MainActivity.KEY_POPUP_DOWNLOADED_RULES,
                    popupCount
                )
                .putString(
                    MainActivity.KEY_FILTER_SOURCE_STATUS,
                    summary
                )
                .remove(MainActivity.KEY_FILTER_UPDATE_ERROR)
                .apply()

            return Result(
                downloadedRules = mainCount,
                popupRules = popupCount,
                sourceSummary = summary
            )
        } catch (error: Exception) {
            mainTemp.delete()
            popupTemp.delete()

            context.getSharedPreferences(
                MainActivity.PREFS,
                Context.MODE_PRIVATE
            )
                .edit()
                .putString(
                    MainActivity.KEY_FILTER_UPDATE_ERROR,
                    error.message ?: error.javaClass.simpleName
                )
                .apply()

            throw error
        }
    }

    private fun downloadFirstAvailable(
        urls: List<String>,
        onLine: (String) -> Unit
    ) {
        var lastError: Throwable? = null

        for (url in urls) {
            try {
                download(url, onLine)
                return
            } catch (error: Throwable) {
                lastError = error
            }
        }

        throw IllegalStateException(
            lastError?.message ?: "Không tải được nguồn"
        )
    }

    private fun download(
        url: String,
        onLine: (String) -> Unit
    ) {
        val connection =
            URL(url).openConnection() as HttpURLConnection

        try {
            connection.connectTimeout = 12_000
            connection.readTimeout = 35_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty(
                "User-Agent",
                "AdShield-Android/0.3"
            )
            connection.setRequestProperty(
                "Accept",
                "text/plain,*/*"
            )

            val response = connection.responseCode
            if (response !in 200..299) {
                throw IllegalStateException(
                    "HTTP " +
                        response +
                        " từ " +
                        URL(url).host
                )
            }

            connection.inputStream.bufferedReader()
                .useLines { lines ->
                    lines.forEach(onLine)
                }
        } finally {
            connection.disconnect()
        }
    }

    private fun atomicReplace(
        source: File,
        target: File
    ) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: Exception) {
            source.copyTo(target, overwrite = true)
            source.delete()
        }
    }

    private const val MIN_MAIN_RULES = 200_000
    private const val MIN_POPUP_RULES = 10_000
}
