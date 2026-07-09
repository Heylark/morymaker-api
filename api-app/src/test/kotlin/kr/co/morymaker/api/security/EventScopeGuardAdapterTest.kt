package kr.co.morymaker.api.security

import kr.co.morymaker.api.application.security.EventAccessDeniedException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * [EventScopeGuardAdapter]를 SecurityContext에 JWT를 직접 주입해 단독 검증한다(MockMvc 불요 —
 * yulse `OrgManagementServiceOrgScopeTest` 매트릭스를 event 도메인으로 이식했다 — 설계 문서의
 * Tester 인계 절 참고).
 */
class EventScopeGuardAdapterTest {

    private val adapter = EventScopeGuardAdapter()

    @AfterEach
    fun clearContext() {
        SecurityContextHolder.clearContext()
    }

    private fun authenticateAs(roles: List<String>? = null, eventIds: List<String>? = null) {
        val builder = Jwt.withTokenValue("dummy-token")
            .header("alg", "RS256")
            .claim("iss", "http://localhost:30000")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(3600))
        roles?.let { builder.claim("roles", it) }
        eventIds?.let { builder.claim("event_ids", it) }
        val jwt = builder.build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
    }

    // ── assertAccess ────────────────────────────────────────────────────

    @Test
    fun `SCOPE-POSITIVE 담당 행사면 예외 없이 통과한다`() {
        authenticateAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf("ev1", "ev2"))
        adapter.assertAccess("ev1")
    }

    @Test
    fun `SCOPE-CROSS-TENANT 담당 아닌 행사면 EventAccessDeniedException을 던진다`() {
        authenticateAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf("ev1"))
        assertFailsWith<EventAccessDeniedException> { adapter.assertAccess("ev2") }
    }

    @Test
    fun `SCOPE-FAIL-CLOSED-NULL event_ids 클레임이 아예 없으면 거부한다`() {
        authenticateAs(roles = listOf("EVENT_ADMIN"), eventIds = null)
        assertFailsWith<EventAccessDeniedException> { adapter.assertAccess("ev1") }
    }

    @Test
    fun `event_ids가 빈 배열이면 거부한다`() {
        authenticateAs(roles = listOf("EVENT_STAFF"), eventIds = emptyList())
        assertFailsWith<EventAccessDeniedException> { adapter.assertAccess("ev1") }
    }

    @Test
    fun `SCOPE-SYSADMIN-PASS SYSTEM_ADMIN은 event_ids 클레임이 없어도 전체 허용된다`() {
        authenticateAs(roles = listOf("SYSTEM_ADMIN"), eventIds = null)
        adapter.assertAccess("아무-행사-id")
    }

    @Test
    fun `roles 클레임이 없으면 SYSTEM_ADMIN으로 판정하지 않는다`() {
        authenticateAs(roles = null, eventIds = listOf("ev1"))
        assertFailsWith<EventAccessDeniedException> { adapter.assertAccess("ev2") }
    }

    @Test
    fun `인증 정보가 없으면(JwtAuthenticationToken 아님) 거부한다`() {
        SecurityContextHolder.getContext().authentication =
            TestingAuthenticationToken("anonymous", null)
        assertFailsWith<EventAccessDeniedException> { adapter.assertAccess("ev1") }
    }

    // ── currentScopeOrNull ─────────────────────────────────────────────

    @Test
    fun `currentScopeOrNull은 SYSTEM_ADMIN이면 null을 반환한다`() {
        authenticateAs(roles = listOf("SYSTEM_ADMIN"), eventIds = null)
        assertNull(adapter.currentScopeOrNull())
    }

    @Test
    fun `currentScopeOrNull은 EVENT_ADMIN이면 event_ids 목록을 그대로 반환한다`() {
        authenticateAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf("ev1", "ev2"))
        assertEquals(listOf("ev1", "ev2"), adapter.currentScopeOrNull())
    }

    @Test
    fun `currentScopeOrNull은 event_ids 클레임이 없으면 빈 리스트를 반환한다(fail-CLOSED)`() {
        authenticateAs(roles = listOf("EVENT_STAFF"), eventIds = null)
        assertEquals(emptyList(), adapter.currentScopeOrNull())
    }
}
