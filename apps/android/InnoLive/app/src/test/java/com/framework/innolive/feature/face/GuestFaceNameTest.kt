package com.framework.innolive.feature.face

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class GuestFaceNameTest {
    private val seoul = TimeZone.getTimeZone("Asia/Seoul")
    private val date = Calendar.getInstance(seoul).apply {
        set(2026, Calendar.OCTOBER, 17, 15, 0, 0)
    }.time

    @Test
    fun koreanNameUsesMonthDayHourAndGuestSuffix() {
        // 안드로이드 `MMMdj` 스켈레톤이 한국어에서 돌려주는 형식입니다.
        val name = GuestFaceName.make(
            suffix = "게스트",
            pattern = "M월 d일 a h시",
            date = date,
            locale = Locale.KOREA,
            timeZone = seoul,
        )

        assertEquals("10월 17일 오후 3시 - 게스트", name)
    }

    @Test
    fun englishNameFollowsTheLocalePattern() {
        val name = GuestFaceName.make(
            suffix = "Guest",
            pattern = "MMM d, h a",
            date = date,
            locale = Locale.US,
            timeZone = seoul,
        )

        assertEquals("Oct 17, 3 PM - Guest", name)
    }
}
