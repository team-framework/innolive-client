package com.framework.innolive.feature.youtube

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdministratorStatusTest {
    @Test fun forbiddenAndMissingAdminApiMeanNotAdministrator() {
        assertTrue(isNotAdministratorStatus(403))
        assertTrue(isNotAdministratorStatus(404))
        // 네트워크·서버 오류는 판정하지 않고 예외로 알려 다음 화면 진입에서 다시 확인합니다.
        assertFalse(isNotAdministratorStatus(null))
        assertFalse(isNotAdministratorStatus(500))
        assertFalse(isNotAdministratorStatus(401))
    }
}
