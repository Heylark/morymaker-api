package kr.co.morymaker.api.web

import com.fasterxml.jackson.databind.ObjectMapper
import kr.co.morymaker.api.config.SecurityConfig
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import kotlin.test.assertTrue

/**
 * 대기화면 콘텐츠 키오스크 공개 조회·서빙 API(§11-2, M3) 통합 테스트 — 무인증 접근·
 * event_id 필터 격리·존재하지 않는 eid의 fail-open(빈 배열, 404 아님, 목록 한정)을
 * 실 MariaDB로 검증한다. 미디어 서빙(`/{cid}/file`)은 목록과 반대로 부재 시 404다.
 *
 * REQ-0030-01 T-011 항목 ①(cross-event 차단) ③(미인증 접근 200) 커버.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PublicIdleContentControllerTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) {

    private val validPng = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(16)

    private fun authenticatedAs(roles: List<String>? = null, eventIds: List<String>? = null): RequestPostProcessor =
        jwt()
            .jwt { builder ->
                roles?.let { builder.claim("roles", it) }
                eventIds?.let { builder.claim("event_ids", it) }
            }
            .authorities { jwtToken: Jwt -> SecurityConfig.grantedAuthorities(jwtToken) }

    private fun createEvent(name: String = "공개 조회 테스트 행사"): String {
        val response = mockMvc.perform(
            post("/events")
                .with(authenticatedAs(roles = listOf("SYSTEM_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"$name"}"""),
        )
            .andExpect(status().isCreated)
            .andReturn().response.getContentAsString(StandardCharsets.UTF_8)
        return objectMapper.readTree(response).get("data").get("id").asText()
    }

    /** file 파트 포함 등록(M3) — 실 미디어를 가진 콘텐츠를 만들고 cid를 반환한다. */
    private fun createContent(eid: String, name: String, sortOrder: Int): String {
        val response = mockMvc.perform(
            multipart("/events/$eid/idle-contents")
                .file(MockMultipartFile("file", "asset.png", "image/png", validPng))
                .param("name", name)
                .param("kind", "이미지")
                .param("sortOrder", sortOrder.toString())
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid))),
        )
            .andExpect(status().isCreated)
            .andReturn().response.getContentAsString(StandardCharsets.UTF_8)
        return objectMapper.readTree(response).get("data").get("id").asText()
    }

    // ── 목록(fail-open) ──────────────────────────────────────────

    @Test
    fun `키오스크는 인증 없이 대기화면 콘텐츠 목록을 sortOrder 순으로 조회할 수 있다`() {
        val eid = createEvent()
        createContent(eid, "두번째", 2)
        createContent(eid, "첫번째", 1)

        mockMvc.perform(get("/public/events/$eid/idle-contents"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].name").value("첫번째"))
            .andExpect(jsonPath("$.data[1].name").value("두번째"))
    }

    @Test
    fun `존재하지 않는 eid도 404가 아니라 빈 배열을 반환한다(fail-open)`() {
        mockMvc.perform(get("/public/events/존재하지-않는-id/idle-contents"))
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

        mockMvc.perform(get("/public/events/$eidA/idle-contents"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].name").value("A행사 콘텐츠"))
    }

    // ── ③ 미디어 서빙 — 미인증 접근 200 ─────────────────────────────

    @Test
    fun `키오스크는 인증 없이 미디어 파일을 스트리밍으로 조회할 수 있다`() {
        val eid = createEvent()
        val cid = createContent(eid, "홍보이미지.png", 1)

        val response = mockMvc.perform(get("/public/events/$eid/idle-contents/$cid/file"))
            .andExpect(status().isOk)
            .andExpect(header().string("Content-Type", "image/png"))
            .andExpect(header().exists("Cache-Control"))
            .andReturn().response

        assertTrue(response.contentAsByteArray.isNotEmpty(), "서빙 응답 바디가 비어 있으면 안 된다")
    }

    // ── ① 미디어 서빙 — 부재/cross-event는 404 ──────────────────────

    @Test
    fun `존재하지 않는 콘텐츠의 파일을 조회하면 404를 받는다`() {
        val eid = createEvent()

        mockMvc.perform(get("/public/events/$eid/idle-contents/존재하지-않는-id/file"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `다른 행사의 cid로 파일을 조회하면 404를 받는다(cross-event 차단, 미존재와 동일 응답)`() {
        val eidA = createEvent("A행사")
        val eidB = createEvent("B행사")
        val cidB = createContent(eidB, "B행사 미디어", 1)

        mockMvc.perform(get("/public/events/$eidA/idle-contents/$cidB/file"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
    }

    @Test
    fun `file_url이 없는 구 메타 전용 행은 서빙 요청 시 404를 받는다(하위호환)`() {
        // 등록 자체가 M3부터 file 필수라 정상 플로우로는 이 상태를 만들 수 없다 — nullable
        // 유지(ADR-007)가 보호하려는 대상은 마이그레이션 이전에 이미 존재하던 이런 행이므로
        // 직접 삽입으로 재현한다.
        val eid = createEvent()
        val cid = java.util.UUID.randomUUID().toString()
        jdbcTemplate.update(
            "INSERT INTO idle_content (id, event_id, name, kind, sort_order) VALUES (?, ?, ?, ?, ?)",
            cid, eid, "구 메타 전용 콘텐츠", "이미지", 0,
        )

        mockMvc.perform(get("/public/events/$eid/idle-contents/$cid/file"))
            .andExpect(status().isNotFound)
    }
}
