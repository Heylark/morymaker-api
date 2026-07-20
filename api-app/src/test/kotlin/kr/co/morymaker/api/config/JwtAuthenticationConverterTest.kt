package kr.co.morymaker.api.config

import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.jwt.Jwt
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `SecurityConfig.grantedAuthorities(jwt)`를 직접 호출해 클레임 → 권한 변환 로직을 검증한다.
 * `roles`/`authorities` 두 클레임의 접두사 처리가 뒤바뀌면 `@PreAuthorize(hasRole(...))`가
 * 조용히 항상 거부되는 조합이라 이 매핑은 회귀에 민감하다.
 */
class JwtAuthenticationConverterTest {

    private fun buildJwt(roles: List<String>? = null, authorities: List<String>? = null): Jwt {
        val builder = Jwt.withTokenValue("dummy-token")
            .header("alg", "RS256")
            .claim("iss", "http://localhost:30000")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(3600))
        roles?.let { builder.claim("roles", it) }
        authorities?.let { builder.claim("authorities", it) }
        return builder.build()
    }

    @Test
    fun `roles 클레임은 ROLE_ 접두사가 붙은 권한으로 변환된다`() {
        val jwt = buildJwt(roles = listOf("SYSTEM_ADMIN", "EVENT_ADMIN"))
        val result = SecurityConfig.grantedAuthorities(jwt).map { it.authority }
        assertTrue(result.contains("ROLE_SYSTEM_ADMIN"))
        assertTrue(result.contains("ROLE_EVENT_ADMIN"))
    }

    @Test
    fun `authorities 클레임은 접두사 없이 그대로 매핑된다`() {
        val jwt = buildJwt(authorities = listOf("event.read"))
        val result = SecurityConfig.grantedAuthorities(jwt).map { it.authority }
        assertTrue(result.contains("event.read"))
        assertTrue(result.none { it.startsWith("ROLE_event") })
    }

    @Test
    fun `roles와 authorities 클레임이 모두 없으면 권한 없이 빈 목록을 반환한다`() {
        val jwt = buildJwt()
        val result = SecurityConfig.grantedAuthorities(jwt)
        assertEquals(emptyList(), result)
    }

    @Test
    fun `roles 클레임이 배열이 아닌 손상된 형태여도 예외를 던지지 않는다`() {
        // 실측(개발 중 확인): getClaimAsStringList는 배열이 아닌 값도 null 대신
        // 단일 원소 목록으로 감싸 반환한다. 실제 역할 문자열과 일치할 수 없는 값이 나오므로
        // 인가 체크는 자연히 거부되지만("ROLE_{...}" 등은 hasRole과 매치 불가), 여기서는
        // 최소한 예외 없이 처리가 끝난다는 사실만 확인한다(fail-closed의 "죽지 않는다" 축).
        val jwt = Jwt.withTokenValue("dummy-token")
            .header("alg", "RS256")
            .claim("iss", "http://localhost:30000")
            .claim("roles", mapOf("unexpected" to "shape"))
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(3600))
            .build()

        val result = SecurityConfig.grantedAuthorities(jwt)
        assertTrue(result.none { it.authority == "ROLE_SYSTEM_ADMIN" || it.authority == "ROLE_EVENT_ADMIN" })
    }
}
