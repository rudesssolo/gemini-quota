package dev.geminiquota.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.core.content.getSystemService

/**
 * Reads cookie text the user copied in another app. Android shows its own
 * "pasted from clipboard" notice, so this is only called from an explicit tap.
 */
object ClipboardCookies {

    fun read(context: Context): String? = try {
        context.getSystemService<ClipboardManager>()
            ?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
            ?.takeIf { it.isNotBlank() }
    } catch (e: RuntimeException) {
        null // clipboard can be unavailable (background restrictions, OEM quirks)
    }
}
