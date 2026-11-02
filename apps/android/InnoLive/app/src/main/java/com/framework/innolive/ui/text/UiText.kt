package com.framework.innolive.ui.text

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.platform.LocalContext

/**
 * 로케일과 무관한 화면 표시 내용입니다. 렌더링 시점에만 해석해야
 * 구성 변경 뒤 보존된 상태에 이전 로케일 문자열이 남지 않습니다.
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

    /** 그대로 보여 줘야 하는 사용자·서버 제공 값입니다. */
    data class Dynamic(val value: String) : UiText
}

/**
 * 액티비티가 다시 생성돼도 표시 상태를 로케일과 무관하게 유지합니다. 현재 리소스 인자로 쓰는
 * 값은 Android에서 저장 가능한 기본형(예: 채널 제목)이며, 임의 객체를 넘기면 안 됩니다.
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

/** OAuth 직전 상태처럼 선택적으로 보존하는 메시지를 저장합니다. */
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
