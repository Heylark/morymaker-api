package kr.co.morymaker.api.web

import com.fasterxml.jackson.databind.ObjectMapper
import kr.co.morymaker.api.config.SecurityConfig
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional

/**
 * 현장등록 rate limit 종단(end-to-end) 테스트 — Developer의 `PublicRateLimitInterceptorTest`는
 * `PublicRateLimitInterceptor`를 직접 인스턴스화해 호출하는 단위 테스트라 `WebMvcConfig`의 실제
 * 경로 등록(현장등록 공개 경로 하위 전체)과 `GlobalExceptionHandler`의 429 변환까지는 검증하지 못한다.
 * 이 클래스는 실 HTTP 요청 경로로 그 배선 전체가 살아있는지 실증한다.
 *
 * 임계를 낮게 오버라이드해 별도 Spring 컨텍스트로 격리한다 — `@TestPropertySource`가 프로퍼티
 * 소스를 바꾸면 컨텍스트 캐시 키가 달라져 다른 테스트 클래스와 별도 컨텍스트(별도 인터셉터 빈
 * 인스턴스)를 받으므로, 다른 클래스의 rate limit 윈도 상태와 공유되지 않는다. 클래스 내부에서도
 * 인터셉터가 클라이언트 IP 키의 싱글턴 상태를 유지하므로, 순서 의존을 피하기 위해 통과·초과·
 * GET 예외 확인을 단일 테스트 메서드로 묶는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = ["morymaker.public.rate-limit.limit=3", "morymaker.public.rate-limit.window-seconds=60"])
class PublicRateLimitEndToEndTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
) {

    private fun authenticatedAs(roles: List<String>? = null): RequestPostProcessor =
        jwt()
            .jwt { builder -> roles?.let { builder.claim("roles", it) } }
            .authorities { jwtToken: Jwt -> SecurityConfig.grantedAuthorities(jwtToken) }

    private fun createEvent(name: String = "rate limit 종단 테스트 행사"): String {
        val response = mockMvc.perform(
            post("/api/events")
                .with(authenticatedAs(roles = listOf("SYSTEM_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"$name"}"""),
        )
            .andExpect(status().isCreated)
            .andReturn().response.contentAsString
        return objectMapper.readTree(response).get("data").get("id").asText()
    }

    @Test
    fun `임계(3회) 이내 현장등록은 통과하고 4번째 요청은 실 HTTP 경로에서 429를 받으며 GET 폼조회는 한도 무관 계속 통과한다`() {
        val eid = createEvent()

        repeat(3) { i ->
            mockMvc.perform(
                post("/api/public/r/$eid")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"현장등록-$i"}"""),
            ).andExpect(status().isCreated)
        }

        mockMvc.perform(
            post("/api/public/r/$eid")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"현장등록-초과"}"""),
        )
            .andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.error.code").value("RATE_LIMIT_EXCEEDED"))

        // GET(폼 조회)은 정책상 rate limit 대상이 아니다 — 같은 IP의 POST가 막힌 뒤에도 계속 통과해야 한다.
        mockMvc.perform(get("/api/public/r/$eid"))
            .andExpect(status().isOk)
    }
}
