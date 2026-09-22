package com.framework.innolive.ui.text

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Locale-independent presentation content. Resolve it only when rendering so a
 * configuration change never leaves an earlier locale in a retained state.
 */
sealed interface UiText {
    data class Resource(
        @param:StringRes val id: Int,
        val args: List<Any> = emptyList(),
    ) : UiText

    data class Plural(
        @param:PluralsRes val id: Int,
        val quantity: Int,
        val args: List<Any> = listOf(quantity),
    ) : UiText

    /** User and server supplied values that must remain verbatim. */
    data class Dynamic(val value: String) : UiText
}

fun UiText.resolve(context: Context): String = when (this) {
    is UiText.Resource -> context.getString(id, *args.toTypedArray())
    is UiText.Plural -> context.resources.getQuantityString(id, quantity, *args.toTypedArray())
    is UiText.Dynamic -> value
}

@Composable
fun UiText.asString(): String = resolve(LocalContext.current)
