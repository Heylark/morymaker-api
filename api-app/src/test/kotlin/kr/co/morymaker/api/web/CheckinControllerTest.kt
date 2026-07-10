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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import kotlin.test.assertEquals

/**
 * 체크인 API(§5, SCN 경로만) 통합 테스트 — 체크인 멱등성(§5-1)·cross-tenant 격리·인가 표면
 * 분리(§5-1 STAFF 포함 vs §5-3 취소 ADMIN 전용)를 실 MariaDB로 검증한다.
 *
 * `EventControllerTest`/`GuestControllerTest`와 동일 컨벤션 — `.with(jwt())` 직접 주입,
 * `@Transactional` 자동 롤백. KIO(무인 키오스크) 경로는 D2 결정 이연으로 이 REQ 범위 밖.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CheckinControllerTest(
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

    private fun createEvent(name: String = "체크인 테스트 행사"): String {
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

    /** @return (guestId, token) */
    private fun registerGuest(eid: String, name: String): Pair<String, String> {
        val response = mockMvc.perform(
            post("/api/events/$eid/guests")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"$name"}"""),
        )
            .andExpect(status().isCreated)
            .andReturn().response.contentAsString
        val data = objectMapper.readTree(response).get("data")
        return data.get("id").asText() to data.get("token").asText()
    }

    // ── 체크인 확정·멱등성(§5-1, P1) ─────────────────────────────────

    @Test
    fun `EVENT_STAFF는 토큰으로 체크인을 확정하고 CHECKED_IN을 반환한다`() {
        val eid = createEvent()
        val (_, token) = registerGuest(eid, "김진우")

        mockMvc.perform(
            post("/api/events/$eid/checkin")
                .with(authenticatedAs(roles = listOf("EVENT_STAFF"), eventIds = listOf(eid)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"token":"$token"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.resultCode").value("CHECKED_IN"))
            .andExpect(jsonPath("$.data.guest.status").value("참석"))
    }

    @Test
    fun `이미 참석한 대상을 재체크인하면 상태 재변경 없이 ALREADY_CHECKED_IN을 반환한다(멱등)`() {
        val eid = createEvent()
        val (gid, _) = registerGuest(eid, "박서연")

        val first = mockMvc.perform(
            post("/api/events/$eid/checkin")
                .with(authenticatedAs(roles = listOf("EVENT_STAFF"), eventIds = listOf(eid)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"guestId":"$gid"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.resultCode").value("CHECKED_IN"))
            .andReturn().response.contentAsString
        val firstVisitAt = objectMapper.readTree(first).get("data").get("guest").get("visitAt").asText()

        val second = mockMvc.perform(
            post("/api/events/$eid/checkin")
                .with(authenticatedAs(roles = listOf("EVENT_STAFF"), eventIds = listOf(eid)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"guestId":"$gid"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.resultCode").value("ALREADY_CHECKED_IN"))
            .andReturn().response.contentAsString
        val secondVisitAt = objectMapper.readTree(second).get("data").get("guest").get("visitAt").asText()

        // 재체크인이 상태를 재변경하지 않았음을 visit_at 불변으로 확인(단순 재조회여야 함).
        assertEquals(firstVisitAt, secondVisitAt)
    }

    // ── cross-tenant 격리·인가 표면(P1) ─────────────────────────────

    @Test
    fun `EVENT_STAFF가 담당 아닌 행사에서 체크인을 시도하면 403 EVENT_FORBIDDEN을 받는다`() {
        val eid = createEvent()
        val (_, token) = registerGuest(eid, "이도현")

        mockMvc.perform(
            post("/api/events/$eid/checkin")
                .with(authenticatedAs(roles = listOf("EVENT_STAFF"), eventIds = listOf("다른-행사-id")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"token":"$token"}"""),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("EVENT_FORBIDDEN"))
    }

    @Test
    fun `token과 guestId가 모두 없으면 400 VALIDATION_FAILED를 받는다`() {
        val eid = createEvent()

        mockMvc.perform(
            post("/api/events/$eid/checkin")
                .with(authenticatedAs(roles = listOf("EVENT_STAFF"), eventIds = listOf(eid)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
    }

    @Test
    fun `EVENT_STAFF는 체크인 취소에서 403 ROLE_FORBIDDEN을 받는다(ADMIN 전용)`() {
        val eid = createEvent()
        val (gid, _) = registerGuest(eid, "정하은")

        mockMvc.perform(
            post("/api/events/$eid/checkin/cancel")
                .with(authenticatedAs(roles = listOf("EVENT_STAFF"), eventIds = listOf(eid)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"guestId":"$gid"}"""),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("ROLE_FORBIDDEN"))
    }

    @Test
    fun `EVENT_ADMIN은 체크인을 취소해 참석을 대기로 되돌린다`() {
        val eid = createEvent()
        val (gid, token) = registerGuest(eid, "최유나")
        mockMvc.perform(
            post("/api/events/$eid/checkin")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"token":"$token"}"""),
        ).andExpect(status().isOk)

        mockMvc.perform(
            post("/api/events/$eid/checkin/cancel")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"guestId":"$gid"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("대기"))
    }

    // ── seatLabel 실좌석 승격(§12-6) 회귀 — numbering ON + 배정 ────────

    @Test
    fun `numbering ON 그룹에 배정된 참석자는 체크인 응답 guest에 A열 12번 형식 seatLabel이 채워진다`() {
        val eid = createEvent()
        val (gid, token) = registerGuest(eid, "박동현")
        val groupResponse = mockMvc.perform(
            post("/api/events/$eid/seat-groups")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"label":"A열","numbering":true}"""),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val groupNo = objectMapper.readTree(groupResponse).get("data").get("groupNo").asInt()
        // numbering ON 그룹의 §12-5 PUT은 그룹 전체 슬롯 세트를 원자 교체한다 — ord는 1..N 연속·
        // 유일해야 하므로 목표 ord(3번)까지의 빈좌석(1·2번)도 함께 제출해야 한다(단일 entry로
        // ord=12만 보내면 "1..N 연속" 검증에 걸려 400 VALIDATION_FAILED).
        mockMvc.perform(
            put("/api/events/$eid/seat-assignments")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"groupNo":$groupNo,"assignments":[
                        {"ord":1,"guestId":null},{"ord":2,"guestId":null},{"ord":3,"guestId":"$gid"}
                    ]}""",
                ),
        ).andExpect(status().isOk)

        mockMvc.perform(
            post("/api/events/$eid/checkin")
                .with(authenticatedAs(roles = listOf("EVENT_STAFF"), eventIds = listOf(eid)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"token":"$token"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.guest.seatLabel").value("A열 3번"))
    }
}
