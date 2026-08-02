package de.shakie.tubenext.browser

import android.content.Context
import android.content.Intent
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi

data class YouTubeLinkAssociationState(
    val enabledHosts: Set<String>,
    val inspectionAvailable: Boolean
) {
    val enabledHostCount: Int
        get() = enabledHosts.size

    val supportedHostCount: Int
        get() = YouTubeLinkAssociation.supportedHosts.size

    val allHostsEnabled: Boolean
        get() = inspectionAvailable &&
            enabledHosts.containsAll(YouTubeLinkAssociation.supportedHosts)
}

object YouTubeLinkAssociation {
    internal val supportedHosts = setOf(
        "youtube.com",
        "www.youtube.com",
        "m.youtube.com",
        "youtu.be"
    )

    fun inspect(context: Context): YouTubeLinkAssociationState {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return YouTubeLinkAssociationState(emptySet(), inspectionAvailable = false)
        }
        return inspectApi31(context)
    }

    fun settingsIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS,
            Uri.parse("package:${context.packageName}")
        )
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun inspectApi31(context: Context): YouTubeLinkAssociationState {
        val hostStates = runCatching {
            context.getSystemService(DomainVerificationManager::class.java)
                ?.getDomainVerificationUserState(context.packageName)
                ?.hostToStateMap
        }.getOrNull() ?: return YouTubeLinkAssociationState(
            enabledHosts = emptySet(),
            inspectionAvailable = false
        )
        return fromHostStates(hostStates)
    }

    internal fun fromHostStates(hostStates: Map<String, Int>): YouTubeLinkAssociationState {
        val enabledHosts = hostStates
            .filterValues { state ->
                state == DomainVerificationUserState.DOMAIN_STATE_SELECTED ||
                    state == DomainVerificationUserState.DOMAIN_STATE_VERIFIED
            }
            .keys
            .map(String::lowercase)
            .filterTo(linkedSetOf()) { host -> host in supportedHosts }
        return YouTubeLinkAssociationState(
            enabledHosts = enabledHosts,
            inspectionAvailable = true
        )
    }
}
