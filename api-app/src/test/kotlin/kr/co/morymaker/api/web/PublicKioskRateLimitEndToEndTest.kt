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
 * kiosk 이름검색 GET rate limit 종단(end-to-end) 테스트 — `PublicRateLimitInterceptorTest`는
 * 인터셉터를 직접 인스턴스화해 호출하는 단위 테스트라 `WebMvcConfig`의 실제 경로 등록과 MockMvc가
 * 계산하는 실 servletPath까지는 검증하지 못한다. 이 클래스는 실 HTTP 요청 경로로 KIOSK_PATH_PREFIX
 * 판정(servletPath 기준)이 살아있는지 실증한다 — sed 일괄 치환 등으로 상수가 오염되면(예:
 * "/api/public/events/"로 되돌아가면) 참석자 명부 열람(이름검색·주차검색) GET의 rate limit이
 * 조용히 전면 무력화되는 사고를 이 테스트가 막는다.
 *
 * 임계를 낮게 오버라이드해 별도 Spring 컨텍스트로 격리한다 — `PublicRateLimitEndToEndTest`와
 * 다른 프로퍼티 값을 사용해야 컨텍스트 캐시 키가 갈라져 별도 인터셉터 빈 인스턴스(별도 윈도 상태)를
 * 받는다. 인터셉터의 윈도 카운터는 경로가 아니라 클라이언트 IP만을 키로 삼으므로, 같은 컨텍스트를
 * 공유하면 POST 종단 테스트의 카운트가 이 kiosk GET 테스트로 새어 들어온다.
 *
 * `MockMvc` 요청은 `servletPath`를 실 컨테이너처럼 자동으로 계산해 주지 않는다(디스패처 매핑
 * 판정 자체는 전체 경로 기준 `PathPattern`으로 하므로 내부적으로 필요 없다) — 명시적으로
 * `.servletPath(...)`를 설정하지 않으면 빈 문자열로 남아 이 인터셉터의 `servletPath` 기준
 * kiosk 판정이 항상 거짓이 된다(rate limit 자체가 발동하지 않는다). 실 배포 환경(컨텍스트
 * 경로 아래 서빙)에서는 컨테이너가 이 값을 정확히 계산해 주므로 문제없다 — 아래 요청 빌더에서
 * 프로덕션 형상 그대로 명시 설정한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = ["morymaker.public.rate-limit.limit=2", "morymaker.public.rate-limit.window-seconds=60"])
class PublicKioskRateLimitEndToEndTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
) {

    private fun authenticatedAs(roles: List<String>? = null): RequestPostProcessor =
        jwt()
            .jwt { builder -> roles?.let { builder.claim("roles", it) } }
            .authorities { jwtToken: Jwt -> SecurityConfig.grantedAuthorities(jwtToken) }

    private fun createEvent(name: String = "kiosk rate limit 종단 테스트 행사"): String {
        val response = mockMvc.perform(
            post("/events")
                .with(authenticatedAs(roles = listOf("SYSTEM_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"$name"}"""),
        )
            .andExpect(status().isCreated)
            .andReturn().response.contentAsString
        return objectMapper.readTree(response).get("data").get("id").asText()
    }

    // 프로덕션 컨테이너가 계산하는 servletPath(= contextPath 제외 앱 내부 경로)를 그대로
    // 재현한다 — MockMvc는 이 값을 자동으로 채우지 않으므로 명시하지 않으면 kiosk 판정이
    // 항상 거짓이 된다(§ 클래스 KDoc 참조).
    private fun kioskGet(appPath: String) = get(appPath).servletPath(appPath)

    @Test
    fun `kiosk 이름검색 GET은 임계(2회) 초과 시 실 HTTP 경로에서 429를 받고, 등록되지 않은 공개 GET은 한도 무관 계속 통과한다`() {
        val eid = createEvent()

        repeat(2) {
            mockMvc.perform(kioskGet("/public/events/$eid/attendees").param("name", "참석자"))
                .andExpect(status().isOk)
        }

        mockMvc.perform(kioskGet("/public/events/$eid/attendees").param("name", "참석자"))
            .andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.error.code").value("RATE_LIMIT_EXCEEDED"))

        // idle-contents는 WebMvcConfig가 이 인터셉터를 등록하지 않은 별도 경로다 — kiosk GET
        // rate limit이 옆 공개 GET 경로까지 등록 범위를 넓히지 않았음을 함께 확인한다.
        mockMvc.perform(kioskGet("/public/events/$eid/idle-contents"))
            .andExpect(status().isOk)
    }
}
