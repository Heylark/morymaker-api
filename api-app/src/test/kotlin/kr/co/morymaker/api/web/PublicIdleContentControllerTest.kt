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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets

/**
 * 대기화면 콘텐츠 키오스크 공개 조회 API(§11-2, M3 — ADR-003) 통합 테스트 — 무인증 접근·
 * event_id 필터 격리·존재하지 않는 eid의 fail-open(빈 배열, 404 아님)을 실 MariaDB로 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PublicIdleContentControllerTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
) {

    private fun authenticatedAs(roles: List<String>? = null, eventIds: List<String>? = null): RequestPostProcessor =
        jwt()
            .jwt { builder ->
                roles?.let { builder.claim("roles", it) }
                eventIds?.let { builder.claim("event_ids", it) }
            }
            .authorities { jwtToken: Jwt -> SecurityConfig.grantedAuthorities(jwtToken) }

    private fun createEvent(name: String = "공개 조회 테스트 행사"): String {
        val response = mockMvc.perform(
            post("/api/events")
                .with(authenticatedAs(roles = listOf("SYSTEM_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"$name"}"""),
        )
            .andExpect(status().isCreated)
            .andReturn().response.getContentAsString(StandardCharsets.UTF_8)
        return objectMapper.readTree(response).get("data").get("id").asText()
    }

    private fun createContent(eid: String, name: String, sortOrder: Int) {
        mockMvc.perform(
            post("/api/events/$eid/idle-contents")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("name" to name, "kind" to "이미지", "sortOrder" to sortOrder))),
        ).andExpect(status().isCreated)
    }

    @Test
    fun `키오스크는 인증 없이 대기화면 콘텐츠 목록을 sortOrder 순으로 조회할 수 있다`() {
        val eid = createEvent()
        createContent(eid, "두번째", 2)
        createContent(eid, "첫번째", 1)

        mockMvc.perform(get("/api/public/events/$eid/idle-contents"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].name").value("첫번째"))
            .andExpect(jsonPath("$.data[1].name").value("두번째"))
    }

    @Test
    fun `존재하지 않는 eid도 404가 아니라 빈 배열을 반환한다(fail-open)`() {
        mockMvc.perform(get("/api/public/events/존재하지-않는-id/idle-contents"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data").isArray)
            .andExpect(jsonPath("$.data.length()").value(0))
    }

    @Test
    fun `다른 행사의 콘텐츠는 조회 결과에 섞이지 않는다(event_id 필터 격리)`() {
        val eidA = createEvent("A행사")
        val eidB = createEvent("B행사")
        createContent(eidA, "A행사 콘텐츠", 1)
        createContent(eidB, "B행사 콘텐츠", 1)

        mockMvc.perform(get("/api/public/events/$eidA/idle-contents"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].name").value("A행사 콘텐츠"))
    }
}
