package kr.co.morymaker.api.config

import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.jwt.Jwt
import java.time.Instant
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `SecurityConfig.issuerTimestampValidator(issuer)`를 직접 호출해 프로덕션 JwtDecoder가
 * 실제로 사용하는 validator 합성을 검증한다(복제가 아니라 프로덕션 경로 자체를 실행).
 *
 * 만료 검증 TC는 timestamp validator가 합성에서 누락되는 회귀를 직접 잡아낸다 — 커스텀
 * JwtDecoder를 만들면 Boot 자동구성이 채워주던 timestamp validator가 조용히 사라지기 쉽다.
 */
class JwtValidatorCompositionTest {

    private val issuer = "http://localhost:30000"

    private val validator = SecurityConfig.issuerTimestampValidator(issuer)

    private fun buildJwt(
        iss: String = issuer,
        issuedAt: Instant = Instant.now().minusSeconds(10),
        expiresAt: Instant = Instant.now().plusSeconds(3600),
    ): Jwt = Jwt.withTokenValue("dummy-token")
        .header("alg", "RS256")
        .claim("iss", iss)
        .issuedAt(issuedAt)
        .expiresAt(expiresAt)
        .build()

    @Test
    fun `issuer가 다른 JWT는 거부된다`() {
        val jwt = buildJwt(iss = "http://attacker.example.com")
        val result = validator.validate(jwt)
        assertTrue(result.hasErrors(), "잘못된 issuer를 가진 JWT가 통과됨 — issuer validator가 동작하지 않음")
    }

    @Test
    fun `만료된 JWT는 거부된다`() {
        val jwt = buildJwt(
            issuedAt = Instant.now().minusSeconds(7200),
            expiresAt = Instant.now().minusSeconds(3600),
        )
        val result = validator.validate(jwt)
        assertTrue(result.hasErrors(), "만료된 JWT가 통과됨 — timestamp validator가 합성에서 누락됐을 가능성")
    }

    @Test
    fun `정상 issuer와 미만료 JWT는 통과된다`() {
        val jwt = buildJwt()
        val result = validator.validate(jwt)
        assertFalse(result.hasErrors(), "정상 JWT가 거부됨: ${result.errors}")
    }
}
