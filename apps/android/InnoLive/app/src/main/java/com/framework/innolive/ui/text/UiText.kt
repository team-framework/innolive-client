package com.framework.innolive.ui.text

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
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

/**
 * Keeps presentation state locale-independent across activity recreation. The
 * values currently used as resource arguments are Android-saveable primitives
 * (for example a channel title); arbitrary objects must not be passed here.
 */
val UiTextSaver: Saver<UiText, Any> = listSaver(
    save = { text ->
        when (text) {
            is UiText.Resource -> listOf("resource", text.id, ArrayList(text.args))
            is UiText.Plural -> listOf("plural", text.id, text.quantity, ArrayList(text.args))
            is UiText.Dynamic -> listOf("dynamic", text.value)
        }
    },
    restore = { values ->
        when (values.firstOrNull()) {
            "resource" -> UiText.Resource(
                id = values[1] as Int,
                args = (values[2] as ArrayList<*>).filterIsInstance<Any>(),
            )
            "plural" -> UiText.Plural(
                id = values[1] as Int,
                quantity = values[2] as Int,
                args = (values[3] as ArrayList<*>).filterIsInstance<Any>(),
            )
            "dynamic" -> UiText.Dynamic(values[1] as String)
            else -> null
        }
    },
)

/** Saves an optional retained message, such as the status preceding OAuth. */
val NullableUiTextSaver: Saver<UiText?, Any> = listSaver(
    save = { text ->
        when (text) {
            null -> listOf("null")
            is UiText.Resource -> listOf("resource", text.id, ArrayList(text.args))
            is UiText.Plural -> listOf("plural", text.id, text.quantity, ArrayList(text.args))
            is UiText.Dynamic -> listOf("dynamic", text.value)
        }
    },
    restore = { values ->
        when (values.firstOrNull()) {
            "null" -> null
            "resource" -> UiText.Resource(
                id = values[1] as Int,
                args = (values[2] as ArrayList<*>).filterIsInstance<Any>(),
            )
            "plural" -> UiText.Plural(
                id = values[1] as Int,
                quantity = values[2] as Int,
                args = (values[3] as ArrayList<*>).filterIsInstance<Any>(),
            )
            "dynamic" -> UiText.Dynamic(values[1] as String)
            else -> null
        }
    },
)

fun UiText.resolve(context: Context): String = when (this) {
    is UiText.Resource -> context.getString(id, *args.resolveArguments(context))
    is UiText.Plural -> context.resources.getQuantityString(id, quantity, *args.resolveArguments(context))
    is UiText.Dynamic -> value
}

private fun List<Any>.resolveArguments(context: Context): Array<Any> = map { argument ->
    if (argument is UiText) argument.resolve(context) else argument
}.toTypedArray()

@Composable
fun UiText.asString(): String = resolve(LocalContext.current)
