package com.framework.innolive.app

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppBackStackTest {
    @Test fun doubleTapOnASettingsRowAddsTheSubpageOnce() {
        val backStack = mutableListOf<AppRoute>(LiveRoute, SettingsRoute)

        backStack.pushIfNotTop(BroadcastSettingRoute)
        backStack.pushIfNotTop(BroadcastSettingRoute)

        assertEquals(listOf(LiveRoute, SettingsRoute, BroadcastSettingRoute), backStack)
    }

    @Test fun optionRoutesWithTheSameTypeAreTreatedAsTheSameScreen() {
        val backStack = mutableListOf<AppRoute>(LiveRoute, SettingsRoute, BroadcastSettingRoute)

        backStack.pushIfNotTop(SettingOptionRoute(SettingOptionType.BROADCAST_PRIVACY))
        backStack.pushIfNotTop(SettingOptionRoute(SettingOptionType.BROADCAST_PRIVACY))
        backStack.pushIfNotTop(SettingOptionRoute(SettingOptionType.BROADCAST_AUDIENCE))

        assertEquals(
            listOf(
                LiveRoute,
                SettingsRoute,
                BroadcastSettingRoute,
                SettingOptionRoute(SettingOptionType.BROADCAST_PRIVACY),
                SettingOptionRoute(SettingOptionType.BROADCAST_AUDIENCE),
            ),
            backStack,
        )
    }

    @Test fun backFromAScreenThatAlreadyClosedDoesNotCloseTheScreenBelow() {
        val backStack = mutableListOf<AppRoute>(LiveRoute, SettingsRoute, BroadcastSettingRoute)
        val option = SettingOptionRoute(SettingOptionType.BROADCAST_PRIVACY)
        backStack.add(option)

        assertTrue(backStack.acceptsBackFrom(option))
        backStack.removeAt(backStack.lastIndex)

        // 닫히는 동안 같은 옵션 화면을 한 번 더 눌러도 방송 정보 화면은 남아 있어야 합니다.
        assertFalse(backStack.acceptsBackFrom(option))
        assertTrue(backStack.acceptsBackFrom(BroadcastSettingRoute))
    }

    @Test fun onlyAndroid11MovesTheTaskBackFromTheRootScreen() {
        assertTrue(movesTaskToBackOnRootBack(Build.VERSION_CODES.R))
        assertFalse(movesTaskToBackOnRootBack(Build.VERSION_CODES.S))
        assertFalse(movesTaskToBackOnRootBack(Build.VERSION_CODES.TIRAMISU))
    }
}
