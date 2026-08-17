package com.yokuli.anchorwatch.ui.about

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URI

enum class ExternalLinkResult { OPENED, INVALID, UNAVAILABLE }

object ExternalLinkPolicy {
    private val allowedSchemes = setOf("https", "mailto")

    fun isAllowed(value: String): Boolean {
        val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme !in allowedSchemes) return false
        return when (scheme) {
            "https" -> !uri.host.isNullOrBlank() && uri.userInfo == null
            "mailto" -> uri.schemeSpecificPart?.substringBefore('?')?.contains('@') == true
            else -> false
        }
    }
}

class ExternalLinkLauncher(private val context: Context) {
    fun open(url: String): ExternalLinkResult {
        if (!ExternalLinkPolicy.isAllowed(url)) return ExternalLinkResult.INVALID
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            ExternalLinkResult.OPENED
        } catch (_: ActivityNotFoundException) {
            ExternalLinkResult.UNAVAILABLE
        } catch (_: SecurityException) {
            ExternalLinkResult.UNAVAILABLE
        }
    }
}
