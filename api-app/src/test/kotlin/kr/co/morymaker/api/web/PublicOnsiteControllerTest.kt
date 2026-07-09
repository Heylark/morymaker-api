package kr.co.morymaker.api.web

import com.fasterxml.jackson.databind.ObjectMapper
import kr.co.morymaker.api.config.SecurityConfig
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional

/**
 * 현장등록 공개 API(§10-5·§10-6) 통합 테스트 — 무인증 접근·eventCode(=event.id, D2) 존재·
 * status 게이트(D5, 종료만 거부)·즉시 체크인 QR 발급을 실 MariaDB로 검증한다.
 *
 * 종료 상태 전이는 관리 API가 아직 없어(행사 상태 변경은 이번 REQ 범위 외) `JdbcTemplate`으로
 * 직접 DB 행을 갱신한다 — `@Transactional` 롤백 범위 안이라 다른 테스트에 영향을 남기지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PublicOnsiteControllerTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) {

    private fun authenticatedAs(roles: List<String>? = null): RequestPostProcessor =
        jwt()
            .jwt { builder -> roles?.let { builder.claim("roles", it) } }
            .authorities { jwtToken: Jwt -> SecurityConfig.grantedAuthorities(jwtToken) }

    private fun createEvent(name: String = "현장등록 테스트 행사"): String {
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

    private fun closeEvent(eid: String) {
        jdbcTemplate.update("UPDATE event SET status = ? WHERE id = ?", "종료", eid)
    }

    // ── 현장등록 폼 진입(§10-5) ───────────────────────────────────────

    @Test
    fun `현장등록 폼은 인증 헤더 없이 행사 브랜딩만 반환한다`() {
        val eid = createEvent("브랜딩 테스트 행사")

        mockMvc.perform(get("/api/public/r/$eid"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.event.name").value("브랜딩 테스트 행사"))
    }

    @Test
    fun `무효 eventCode는 404를 받는다`() {
        mockMvc.perform(get("/api/public/r/no-such-event"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
    }

    @Test
    fun `종료된 행사는 폼 조회도 409 EVENT_CLOSED를 받는다`() {
        val eid = createEvent()
        closeEvent(eid)

        mockMvc.perform(get("/api/public/r/$eid"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.code").value("EVENT_CLOSED"))
    }

    // ── 현장등록 실행(§10-6, P2) ──────────────────────────────────────

    @Test
    fun `현장등록은 명단 추가와 함께 체크인 QR을 즉시 발급한다`() {
        val eid = createEvent()

        val response = mockMvc.perform(
            post("/api/public/r/$eid")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"홍길동","org":"○○사","phone":"010-1111-2222"}"""),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.guestId").exists())
            .andExpect(jsonPath("$.data.token").exists())
            .andExpect(jsonPath("$.data.checkinQr.url").exists())
            .andReturn().response.contentAsString
        val token = objectMapper.readTree(response).get("data").get("token").asText()

        // 명단에도 반영됐는지 관리자 API로 확인 — src=현장, status=대기(즉시 체크인은 §5-1에서만).
        mockMvc.perform(
            get("/api/events/$eid/guests")
                .with(authenticatedAs(roles = listOf("SYSTEM_ADMIN"))),
        )
            .andExpect(jsonPath("$.data[0].src").value("현장"))
            .andExpect(jsonPath("$.data[0].status").value("대기"))
            .andExpect(jsonPath("$.data[0].token").value(token))
    }

    @Test
    fun `이름 누락 현장등록은 400 VALIDATION_FAILED를 받는다`() {
        val eid = createEvent()

        mockMvc.perform(
            post("/api/public/r/$eid")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"org":"이름없음"}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
    }

    @Test
    fun `무효 eventCode 현장등록은 404를 받는다`() {
        mockMvc.perform(
            post("/api/public/r/no-such-event")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"홍길동"}"""),
        )
            .andExpect(status().isNotFound)
    }

    @Test
    fun `종료된 행사에 현장등록을 시도하면 409 EVENT_CLOSED를 받는다`() {
        val eid = createEvent()
        closeEvent(eid)

        mockMvc.perform(
            post("/api/public/r/$eid")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"홍길동"}"""),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.code").value("EVENT_CLOSED"))
    }
}
