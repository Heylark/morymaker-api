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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import kotlin.test.assertNull

/**
 * 좌석 배정 API(§12-4~5) 통합 테스트 — 조회 page+Meta·일괄 교체(원자 재정렬)·payload 검증(M1)·
 * assignedElsewhere 사전검사·cross-tenant 격리를 실 MariaDB로 검증한다.
 *
 * 동시성 최종 방어(cross-group UNIQUE(guest_id) 경쟁·동일 그룹 동시 교체)는
 * `SeatAssignmentConcurrencyIntegrationTest`가 별도 물리 스레드로 검증한다 — 이 파일은 단일
 * 스레드 순차 호출 기준 정상/오류 경로만 다룬다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SeatAssignmentControllerTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) {

    private fun authenticatedAs(roles: List<String>? = null, eventIds: List<String>? = null): RequestPostProcessor =
        jwt()
            .jwt { builder ->
                roles?.let { builder.claim("roles", it) }
                eventIds?.let { builder.claim("event_ids", it) }
            }
            .authorities { jwtToken: Jwt -> SecurityConfig.grantedAuthorities(jwtToken) }

    private fun createEvent(name: String = "좌석 배정 테스트 행사"): String {
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

    private fun registerGuest(eid: String, name: String): String {
        val response = mockMvc.perform(
            post("/api/events/$eid/guests")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"$name"}"""),
        )
            .andExpect(status().isCreated)
            .andReturn().response.contentAsString
        return objectMapper.readTree(response).get("data").get("id").asText()
    }

    private fun createGroup(eid: String, label: String, numbering: Boolean): Pair<String, Int> {
        val response = mockMvc.perform(
            post("/api/events/$eid/seat-groups")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("label" to label, "numbering" to numbering))),
        )
            .andExpect(status().isCreated)
            .andReturn().response.contentAsString
        val data = objectMapper.readTree(response).get("data")
        return data.get("id").asText() to data.get("groupNo").asInt()
    }

    private fun replaceRaw(eid: String, groupNo: Int, assignments: List<Map<String, Any?>>, eventIds: List<String> = listOf(eid)) =
        mockMvc.perform(
            put("/api/events/$eid/seat-assignments")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = eventIds))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("groupNo" to groupNo, "assignments" to assignments))),
        )

    // ── §12-5 일괄 교체 정상 흐름 ────────────────────────────────────

    @Test
    fun `replace는 numbering ON 그룹에서 빈좌석을 포함해 원자 교체한다`() {
        val eid = createEvent()
        val (_, groupNo) = createGroup(eid, "A열", numbering = true)
        val g1 = registerGuest(eid, "이서연")

        replaceRaw(
            eid, groupNo,
            listOf(mapOf("ord" to 1, "guestId" to g1), mapOf("ord" to 2, "guestId" to null)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].guestId").value(g1))
            .andExpect(jsonPath("$.data[0].guestName").value("이서연"))
            .andExpect(jsonPath("$.data[1].guestId").isEmpty)
            .andExpect(jsonPath("$.data[1].empty").value(true))
    }

    @Test
    fun `replace는 numbering OFF 그룹에서 payload ord를 무시하고 9999로 저장한다`() {
        val eid = createEvent()
        val (gid, groupNo) = createGroup(eid, "1번 테이블", numbering = false)
        val g1 = registerGuest(eid, "박서연")

        replaceRaw(eid, groupNo, listOf(mapOf("ord" to 5, "guestId" to g1)))
            .andExpect(status().isOk)

        val ord = jdbcTemplate.queryForObject(
            "SELECT ord FROM seat_assignment WHERE seat_group_id = ? AND guest_id = ?",
            Int::class.java,
            gid, g1,
        )
        kotlin.test.assertEquals(9999, ord)
    }

    @Test
    fun `replace는 빈 assignments면 그룹을 전체 비우고 guest 동기화를 해제한다`() {
        val eid = createEvent()
        val (gid, groupNo) = createGroup(eid, "A열", numbering = true)
        val g1 = registerGuest(eid, "최유나")
        replaceRaw(eid, groupNo, listOf(mapOf("ord" to 1, "guestId" to g1))).andExpect(status().isOk)

        replaceRaw(eid, groupNo, emptyList())
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(0))

        val count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM seat_assignment WHERE seat_group_id = ?",
            Int::class.java,
            gid,
        )
        kotlin.test.assertEquals(0, count)
        // queryForObject(String::class.java)는 결과가 NULL일 때 Kotlin extension이 `!!`로 언박싱해
        // NPE를 던진다 — queryForList로 Map을 받아 null-safe하게 확인한다.
        val guestSeatGroupId = jdbcTemplate.queryForList("SELECT seat_group_id FROM guest WHERE id = ?", g1).first()["seat_group_id"]
        assertNull(guestSeatGroupId)
    }

    // ── §12-4 page+Meta ────────────────────────────────────────────

    @Test
    fun `list는 page·size에 따라 잘라 반환하고 meta total은 그룹 전체 건수다`() {
        val eid = createEvent()
        val (_, groupNo) = createGroup(eid, "자유석", numbering = false)
        val guestIds = (1..5).map { registerGuest(eid, "참석자$it") }
        replaceRaw(eid, groupNo, guestIds.map { mapOf("ord" to 0, "guestId" to it) }).andExpect(status().isOk)

        mockMvc.perform(
            get("/api/events/$eid/seat-assignments?groupNo=$groupNo&page=2&size=2")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.meta.total").value(5))
            .andExpect(jsonPath("$.meta.page").value(2))
            .andExpect(jsonPath("$.meta.size").value(2))
    }

    @Test
    fun `list는 존재하지 않는 groupNo면 404 NOT_FOUND를 받는다`() {
        val eid = createEvent()

        mockMvc.perform(
            get("/api/events/$eid/seat-assignments?groupNo=999")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid))),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
    }

    // ── payload 검증(M1) — 400 ─────────────────────────────────────

    @Test
    fun `replace는 numbering ON에서 ord가 1부터 연속되지 않으면 400 VALIDATION_FAILED를 받는다`() {
        val eid = createEvent()
        val (_, groupNo) = createGroup(eid, "A열", numbering = true)
        val g1 = registerGuest(eid, "김민준")

        replaceRaw(eid, groupNo, listOf(mapOf("ord" to 1, "guestId" to g1), mapOf("ord" to 3, "guestId" to null)))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
    }

    @Test
    fun `replace는 동일 groupNo payload에 같은 ord가 중복되면 400 VALIDATION_FAILED를 받는다`() {
        val eid = createEvent()
        val (_, groupNo) = createGroup(eid, "A열", numbering = true)
        val g1 = registerGuest(eid, "김민준")
        val g2 = registerGuest(eid, "이도현")

        replaceRaw(eid, groupNo, listOf(mapOf("ord" to 1, "guestId" to g1), mapOf("ord" to 1, "guestId" to g2)))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
    }

    @Test
    fun `replace는 같은 guestId가 payload에 중복되면 400 VALIDATION_FAILED를 받는다`() {
        val eid = createEvent()
        val (_, groupNo) = createGroup(eid, "1번 테이블", numbering = false)
        val g1 = registerGuest(eid, "박서연")

        replaceRaw(eid, groupNo, listOf(mapOf("ord" to 0, "guestId" to g1), mapOf("ord" to 0, "guestId" to g1)))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
    }

    @Test
    fun `replace는 event 소속이 아닌 guestId가 포함되면 400 VALIDATION_FAILED를 받는다`() {
        val eid = createEvent()
        val (_, groupNo) = createGroup(eid, "1번 테이블", numbering = false)

        replaceRaw(eid, groupNo, listOf(mapOf("ord" to 0, "guestId" to "존재하지-않는-guest-id")))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
    }

    @Test
    fun `replace는 ord가 숫자가 아닌 문자열이면 400 VALIDATION_FAILED를 받는다`() {
        val eid = createEvent()
        val (_, groupNo) = createGroup(eid, "A열", numbering = true)
        val g1 = registerGuest(eid, "박서연")

        mockMvc.perform(
            put("/api/events/$eid/seat-assignments")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"groupNo":$groupNo,"assignments":[{"ord":"가나다","guestId":"$g1"}]}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
    }

    // 발견(설계 가정 이탈, tech-debt 등록) — 02-architect §4는 "소수·문자·범위밖 ord
    // 거부(DTO 타입이 Int라 원천 거부)"를 주장하나, 실측 결과 Jackson 기본 설정(ACCEPT_FLOAT_AS_INT
    // 활성)이 소수를 거부하지 않고 **말단 절삭**한다(1.5 → 1). "문자"·터무니없는 범위 초과(Long
    // 오버플로 등)는 실제로 400을 받지만 "소수"만 절삭되어 통과한다 — DB 컬럼은 항상 유효 정수만
    // 저장되므로 데이터 무결성 위반은 없다(단일 배정 등 우연히 유효값으로 절삭되는 경우 200으로
    // 통과할 뿐, 저장값이 깨지지는 않음).
    @Test
    fun `replace는 소수 ord를 거부하지 않고 절삭된 정수로 저장한다(설계 가정과 실제 동작 불일치 — tech-debt 등록)`() {
        val eid = createEvent()
        val (gid, groupNo) = createGroup(eid, "A열", numbering = true)
        val g1 = registerGuest(eid, "박서연")

        mockMvc.perform(
            put("/api/events/$eid/seat-assignments")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"groupNo":$groupNo,"assignments":[{"ord":1.9,"guestId":"$g1"}]}"""),
        ).andExpect(status().isOk)

        val ord = jdbcTemplate.queryForObject(
            "SELECT ord FROM seat_assignment WHERE seat_group_id = ? AND guest_id = ?",
            Int::class.java,
            gid, g1,
        )
        kotlin.test.assertEquals(1, ord, "1.9는 거부되지 않고 1로 절삭 저장된다(Jackson ACCEPT_FLOAT_AS_INT 기본값)")
    }

    // ── assignedElsewhere 사전검사 — 409 ────────────────────────────

    @Test
    fun `replace는 다른 그룹에 이미 배정된 참석자를 재배정하려 하면 409 SEAT_CONFLICT를 받는다`() {
        val eid = createEvent()
        val (_, groupNoA) = createGroup(eid, "A열", numbering = true)
        val (_, groupNoB) = createGroup(eid, "B열", numbering = true)
        val g1 = registerGuest(eid, "이서연")
        replaceRaw(eid, groupNoA, listOf(mapOf("ord" to 1, "guestId" to g1))).andExpect(status().isOk)

        replaceRaw(eid, groupNoB, listOf(mapOf("ord" to 1, "guestId" to g1)))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.code").value("SEAT_CONFLICT"))
    }

    // ── cross-tenant 격리(P1) ────────────────────────────────────────

    @Test
    fun `EVENT_ADMIN이 담당 아닌 행사의 배정 목록을 조회하면 403 EVENT_FORBIDDEN을 받는다`() {
        val eid = createEvent()
        val (_, groupNo) = createGroup(eid, "A열", numbering = true)

        mockMvc.perform(
            get("/api/events/$eid/seat-assignments?groupNo=$groupNo")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf("다른-행사-id"))),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("EVENT_FORBIDDEN"))
    }

    @Test
    fun `EVENT_ADMIN이 담당 아닌 행사의 배정을 교체하려 하면 403 EVENT_FORBIDDEN을 받는다`() {
        val eid = createEvent()
        val (_, groupNo) = createGroup(eid, "A열", numbering = true)

        replaceRaw(eid, groupNo, emptyList(), eventIds = listOf("다른-행사-id"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("EVENT_FORBIDDEN"))
    }

    @Test
    fun `EVENT_STAFF는 관리자 콘솔 전용 좌석배정 API에서 403 ROLE_FORBIDDEN을 받는다`() {
        val eid = createEvent()
        val (_, groupNo) = createGroup(eid, "A열", numbering = true)

        mockMvc.perform(
            get("/api/events/$eid/seat-assignments?groupNo=$groupNo")
                .with(authenticatedAs(roles = listOf("EVENT_STAFF"), eventIds = listOf(eid))),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("ROLE_FORBIDDEN"))
    }
}
