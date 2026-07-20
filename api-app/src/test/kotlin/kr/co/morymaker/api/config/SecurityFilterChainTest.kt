package kr.co.morymaker.api.config

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * SecurityFilterChain 전체 배선 회귀 테스트 — 이번 범위에는 실 컨트롤러가 없어(EventController는
 * 후속 범위) `/probe`처럼 매핑되지 않은 보호 경로를 사용한다. Spring Security는 컨트롤러
 * 매핑보다 먼저 필터 체인에서 인증 여부를 판단하므로, 핸들러 부재와 무관하게 인증 실패/통과를
 * 검증할 수 있다(인증 실패 시 401이 필터 단계에서 즉시 반환됨).
 *
 * `.with(jwt())`는 실제 JwtDecoder/JWKS를 거치지 않고 SecurityContext에 인증된 주체를 직접
 * 주입한다 — 디코더·validator 자체 검증은 [JwtValidatorCompositionTest]가 별도로 담당한다.
 *
 * 로컬 MariaDB(infra/docker-compose.yml) 기동이 선행돼야 한다 — module-persistence 배선으로
 * 전체 컨텍스트 로딩 시 Flyway 마이그레이션이 실행된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SecurityFilterChainTest(
    @Autowired private val mockMvc: MockMvc,
) {

    @Test
    fun `Authorization 헤더 없이 보호 경로 접근 시 401과 UNAUTHENTICATED를 반환한다`() {
        mockMvc.perform(get("/probe"))
            .andExpect(status().isUnauthorized)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
    }

    @Test
    fun `유효한 JWT로 요청하면 인증 필터를 통과한다 (401이 아님)`() {
        mockMvc.perform(
            get("/probe").with(jwt().jwt { it.claim("roles", listOf("SYSTEM_ADMIN")) }),
        )
            // 컨트롤러가 아직 없어 404이지만, 401/403이 아니라는 사실 자체가 인증 통과의 증거다.
            .andExpect(status().isNotFound)
    }

    @Test
    fun `actuator health는 인증 없이 접근 가능하다`() {
        mockMvc.perform(get("/actuator/health"))
            .andExpect(status().isOk)
    }
}
