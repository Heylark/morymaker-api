package kr.co.morymaker.api.domain.security

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class MoryRolesTest {

    @Test
    fun `역할 상수 3종은 auth 서버와 동일한 문자열을 사용한다`() {
        assertEquals("SYSTEM_ADMIN", MoryRoles.SYSTEM_ADMIN)
        assertEquals("EVENT_ADMIN", MoryRoles.EVENT_ADMIN)
        assertEquals("EVENT_STAFF", MoryRoles.EVENT_STAFF)
    }

    @Test
    fun `SpEL 표현식 3종은 hasRole·hasAnyRole 형식을 따른다`() {
        assertEquals("hasAnyRole('SYSTEM_ADMIN','EVENT_ADMIN')", MoryRoles.HAS_ADMIN_CONSOLE)
        assertEquals("hasRole('SYSTEM_ADMIN')", MoryRoles.HAS_SYSTEM_ADMIN)
        assertEquals("hasAnyRole('SYSTEM_ADMIN','EVENT_ADMIN','EVENT_STAFF')", MoryRoles.HAS_EVENT_ACCESS)
    }
}
