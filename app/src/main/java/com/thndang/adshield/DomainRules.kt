package com.thndang.adshield

import android.content.Context
import java.util.Locale

object DomainRules {
    private const val KEY_CUSTOM_BLOCKS = "custom_block_domains"
    private const val KEY_CUSTOM_ALLOWS = "custom_allow_domains"
    private const val KEY_WARNED_DOMAINS = "redirect_warned_domains"

    fun normalize(raw: String): String =
        raw.trim()
            .trimEnd('.')
            .lowercase(Locale.US)

    fun isBlocked(context: Context, rawDomain: String): Boolean =
        matches(customBlocks(context), normalize(rawDomain))

    fun isAllowed(context: Context, rawDomain: String): Boolean =
        matches(customAllows(context), normalize(rawDomain))

    fun customBlocks(context: Context): Set<String> =
        readSet(context, KEY_CUSTOM_BLOCKS)

    fun customAllows(context: Context): Set<String> =
        readSet(context, KEY_CUSTOM_ALLOWS)

    fun addBlock(context: Context, rawDomain: String) {
        val domain = normalize(rawDomain)
        if (domain.isEmpty()) return

        val blocks = customBlocks(context).toMutableSet()
        val allows = customAllows(context).toMutableSet()
        blocks += domain
        allows -= domain

        writeSet(context, KEY_CUSTOM_BLOCKS, blocks)
        writeSet(context, KEY_CUSTOM_ALLOWS, allows)
    }

    fun addAllow(context: Context, rawDomain: String) {
        val domain = normalize(rawDomain)
        if (domain.isEmpty()) return

        val blocks = customBlocks(context).toMutableSet()
        val allows = customAllows(context).toMutableSet()
        allows += domain
        blocks -= domain

        writeSet(context, KEY_CUSTOM_BLOCKS, blocks)
        writeSet(context, KEY_CUSTOM_ALLOWS, allows)
    }

    fun removeBlock(context: Context, rawDomain: String) {
        val domain = normalize(rawDomain)
        val blocks = customBlocks(context).toMutableSet()
        blocks -= domain
        writeSet(context, KEY_CUSTOM_BLOCKS, blocks)
        forgetWarning(context, domain)
    }

    fun removeAllow(context: Context, rawDomain: String) {
        val domain = normalize(rawDomain)
        val allows = customAllows(context).toMutableSet()
        allows -= domain
        writeSet(context, KEY_CUSTOM_ALLOWS, allows)
        forgetWarning(context, domain)
    }

    fun wasWarned(context: Context, rawDomain: String): Boolean =
        readSet(context, KEY_WARNED_DOMAINS)
            .contains(normalize(rawDomain))

    fun markWarned(context: Context, rawDomain: String) {
        val domain = normalize(rawDomain)
        if (domain.isEmpty()) return
        val domains = readSet(context, KEY_WARNED_DOMAINS).toMutableSet()
        domains += domain
        writeSet(context, KEY_WARNED_DOMAINS, domains)
    }

    fun forgetWarning(context: Context, rawDomain: String) {
        val domain = normalize(rawDomain)
        val domains = readSet(context, KEY_WARNED_DOMAINS).toMutableSet()
        domains -= domain
        writeSet(context, KEY_WARNED_DOMAINS, domains)
    }

    private fun matches(rules: Set<String>, rawDomain: String): Boolean {
        var candidate = normalize(rawDomain)
        if (candidate.isEmpty()) return false

        while (true) {
            if (candidate in rules) return true
            val dot = candidate.indexOf('.')
            if (dot < 0) return false
            candidate = candidate.substring(dot + 1)
        }
    }

    private fun readSet(context: Context, key: String): Set<String> =
        context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
            .getStringSet(key, emptySet())
            ?.toSet()
            ?: emptySet()

    private fun writeSet(
        context: Context,
        key: String,
        values: Set<String>
    ) {
        context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(key, values.toSet())
            .apply()
    }
}
