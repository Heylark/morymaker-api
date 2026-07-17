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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 대기화면 콘텐츠 관리자 API(§11-2~4) 통합 테스트 — CRUD·multipart 등록(M3, `file` 파트 필수)·
 * cross-tenant 격리를 실 MariaDB로 검증한다. 키오스크 무인증 조회·서빙은
 * `PublicIdleContentControllerTest` 별도(무인증 표면 분리).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class IdleContentControllerTest(
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

    private fun createEvent(name: String = "대기화면 테스트 행사"): String {
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

    private fun createContentRaw(
        eid: String,
        fields: Map<String, String?>,
        eventIds: List<String> = listOf(eid),
        fileBytes: ByteArray? = validPng,
        fileContentType: String = "image/png",
    ) = mockMvc.perform(
        multipart("/events/$eid/idle-contents")
            .apply { fileBytes?.let { file(MockMultipartFile("file", "asset.png", fileContentType, it)) } }
            .apply { fields.forEach { (key, value) -> value?.let { param(key, it) } } }
            .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = eventIds)),
    )

    private fun createContent(eid: String, name: String = "키비주얼_A.png", kind: String = "이미지", sortOrder: Int = 1): String {
        val response = createContentRaw(eid, mapOf("name" to name, "kind" to kind, "mode" to "branded", "play" to "8초 롤링", "sortOrder" to sortOrder.toString()))
            .andExpect(status().isCreated)
            .andReturn().response.getContentAsString(StandardCharsets.UTF_8)
        return objectMapper.readTree(response).get("data").get("id").asText()
    }

    // ── create(§11-3, M3 — file 파트 필수) ──────────────────────────

    @Test
    fun `create는 파일을 저장하고 fileUrl에 서빙 URL이 채워진다(DB엔 스토리지 키만 저장)`() {
        val eid = createEvent()

        val response = createContentRaw(
            eid,
            mapOf("name" to "행사 홍보이미지.png", "kind" to "이미지", "mode" to "fullbleed", "play" to "8초 롤링", "sortOrder" to "1"),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.name").value("행사 홍보이미지.png"))
            .andExpect(jsonPath("$.data.kind").value("이미지"))
            .andReturn().response.getContentAsString(StandardCharsets.UTF_8)

        val data = objectMapper.readTree(response).get("data")
        val cid = data.get("id").asText()
        val fileUrl = data.get("fileUrl").asText()
        assertTrue(fileUrl.endsWith("/api/public/events/$eid/idle-contents/$cid/file"), "fileUrl=$fileUrl")

        val storedKey = jdbcTemplate.queryForObject(
            "SELECT file_url FROM idle_content WHERE id = ?", String::class.java, cid,
        )
        assertEquals("$eid/$cid", storedKey, "DB엔 절대 URL이 아니라 스토리지 키만 저장돼야 한다")

        // fileUrl로 노출한 경로 자체가 실제로 서빙되는지 왕복 확인(좌표계 역전은 스키마 단언만으론
        // 못 잡는다 — MockMvc는 context-path 미적용이므로 /api 접두 없이 호출한다).
        mockMvc.perform(get("/public/events/$eid/idle-contents/$cid/file"))
            .andExpect(status().isOk)
    }

    @Test
    fun `create는 name이 비어 있으면 400 VALIDATION_FAILED를 받는다`() {
        val eid = createEvent()

        createContentRaw(eid, mapOf("name" to "", "kind" to "이미지"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
    }

    @Test
    fun `create는 file 파트가 없으면 400 MISSING_FILE_PART를 받는다`() {
        val eid = createEvent()

        createContentRaw(eid, mapOf("name" to "파일없는 등록 시도", "kind" to "이미지"), fileBytes = null)
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("MISSING_FILE_PART"))
    }

    @Test
    fun `create는 확장자 위장(시그니처 불일치) 파일을 400 VALIDATION_FAILED로 거부한다`() {
        val eid = createEvent()
        val garbage = ByteArray(16) { 0x00 }

        createContentRaw(
            eid,
            mapOf("name" to "위장 파일", "kind" to "이미지"),
            fileBytes = garbage,
            fileContentType = "image/jpeg",
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
    }

    @Test
    fun `create는 JSON 요청이면 415 UNSUPPORTED_MEDIA_TYPE을 받는다(멀티파트 전용 전환)`() {
        val eid = createEvent()

        mockMvc.perform(
            post("/events/$eid/idle-contents")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"구 JSON 경로","kind":"이미지"}"""),
        )
            .andExpect(status().isUnsupportedMediaType)
            .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_MEDIA_TYPE"))
    }

    // ── list / update ────────────────────────────────────────────

    @Test
    fun `list는 sortOrder 순으로 콘텐츠를 반환한다`() {
        val eid = createEvent()
        createContent(eid, name = "두번째", sortOrder = 2)
        createContent(eid, name = "첫번째", sortOrder = 1)

        mockMvc.perform(
            get("/events/$eid/idle-contents")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].name").value("첫번째"))
            .andExpect(jsonPath("$.data[1].name").value("두번째"))
    }

    @Test
    fun `update는 mode·play·sortOrder만 수정하고 name·kind는 불변으로 유지한다`() {
        val eid = createEvent()
        val cid = createContent(eid)

        mockMvc.perform(
            put("/events/$eid/idle-contents/$cid")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"mode":"fullbleed","play":"10초 롤링","sortOrder":5}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.mode").value("fullbleed"))
            .andExpect(jsonPath("$.data.play").value("10초 롤링"))
            .andExpect(jsonPath("$.data.sortOrder").value(5))
            .andExpect(jsonPath("$.data.name").value("키비주얼_A.png"))
    }

    @Test
    fun `update는 콘텐츠가 없으면 404 NOT_FOUND를 받는다`() {
        val eid = createEvent()

        mockMvc.perform(
            put("/events/$eid/idle-contents/존재하지-않는-id")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"mode":"branded","play":null,"sortOrder":0}"""),
        )
            .andExpect(status().isNotFound)
    }

    // ── cross-tenant 격리(P1) ────────────────────────────────────────

    @Test
    fun `EVENT_ADMIN이 담당 아닌 행사의 콘텐츠 목록을 조회하면 403 EVENT_FORBIDDEN을 받는다`() {
        val eid = createEvent()

        mockMvc.perform(
            get("/events/$eid/idle-contents")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf("다른-행사-id"))),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("EVENT_FORBIDDEN"))
    }

    @Test
    fun `EVENT_ADMIN이 담당 아닌 행사에 콘텐츠를 등록하면 403 EVENT_FORBIDDEN을 받는다`() {
        val eid = createEvent()

        createContentRaw(eid, mapOf("name" to "무단 등록 시도", "kind" to "이미지"), eventIds = listOf("다른-행사-id"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("EVENT_FORBIDDEN"))
    }

    @Test
    fun `EVENT_STAFF는 관리자 콘솔 전용 콘텐츠 API에서 403 ROLE_FORBIDDEN을 받는다`() {
        val eid = createEvent()

        mockMvc.perform(
            get("/events/$eid/idle-contents")
                .with(authenticatedAs(roles = listOf("EVENT_STAFF"), eventIds = listOf(eid))),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("ROLE_FORBIDDEN"))
    }

    @Test
    fun `EVENT_ADMIN이 담당 아닌 행사의 콘텐츠를 수정하면 403 EVENT_FORBIDDEN을 받는다(cross-tenant PUT)`() {
        val eid = createEvent()
        val cid = createContent(eid)

        mockMvc.perform(
            put("/events/$eid/idle-contents/$cid")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf("다른-행사-id")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"mode":"fullbleed","play":"침입 시도","sortOrder":9}"""),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("EVENT_FORBIDDEN"))

        // 거부된 수정 시도가 실제로 반영되지 않았는지 실 DB로 재확인(담당 행사 관점 재조회).
        mockMvc.perform(
            get("/events/$eid/idle-contents")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].play").value("8초 롤링"))
    }
}
