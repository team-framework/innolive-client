package com.framework.innolive.feature.live

import androidx.compose.runtime.saveable.Saver

internal val ChzzkSettingsSaver = Saver<ChzzkBroadcastSettings, List<String>>(
    save = { listOf(it.title, it.categoryType, it.categoryId) + it.tags },
    restore = { values ->
        ChzzkBroadcastSettings(values[0], values[1], values[2], values.drop(3))
    },
)
