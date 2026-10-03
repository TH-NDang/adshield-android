package com.thndang.adshield

import android.content.Context

object VpnAppRules {

    private const val KEY_BYPASS_PACKAGES =
        "vpn_bypass_packages"

    fun bypassPackages(
        context: Context
    ): Set<String> =
        context.getSharedPreferences(
            MainActivity.PREFS,
            Context.MODE_PRIVATE
        )
            .getStringSet(
                KEY_BYPASS_PACKAGES,
                emptySet()
            )
            ?.toSet()
            ?: emptySet()

    fun isBypassed(
        context: Context,
        packageName: String
    ): Boolean =
        bypassPackages(context)
            .contains(packageName)

    fun setBypassed(
        context: Context,
        packageName: String,
        bypass: Boolean
    ) {
        val next =
            bypassPackages(context)
                .toMutableSet()

        if (bypass) {
            next.add(packageName)
        } else {
            next.remove(packageName)
        }

        context.getSharedPreferences(
            MainActivity.PREFS,
            Context.MODE_PRIVATE
        )
            .edit()
            .putStringSet(
                KEY_BYPASS_PACKAGES,
                next
            )
            .apply()
    }
}
