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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 좌석 그룹 API(§12-1~3) 통합 테스트 — CRUD·assignedCount/slotCount 집계·numbering 토글
 * 재해석(M4)·cross-tenant 격리를 실 MariaDB로 검증한다.
 *
 * `ParkingZoneControllerTest`/`GuestControllerTest`와 동일 컨벤션 — `.with(jwt())` 직접 주입,
 * `@Transactional` 자동 롤백. M4(numbering 토글)의 오케스트레이션 로직 자체는
 * `SeatGroupServiceTest`(mock)가 이미 검증했다 — 이 파일은 실 DB read-back으로 SQL(배치
 * UPDATE·정렬 조회)이 실제로 의도대로 동작하는지만 추가 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SeatGroupControllerTest(
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

    private fun createEvent(name: String = "좌석 그룹 테스트 행사"): String {
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

    private fun createGroupRaw(eid: String, label: String, numbering: Boolean, eventIds: List<String> = listOf(eid)) =
        mockMvc.perform(
            post("/api/events/$eid/seat-groups")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = eventIds))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("label" to label, "numbering" to numbering))),
        )

    private fun createGroup(eid: String, label: String, numbering: Boolean): String {
        val response = createGroupRaw(eid, label, numbering)
            .andExpect(status().isCreated)
            .andReturn().response.contentAsString
        return objectMapper.readTree(response).get("data").get("id").asText()
    }

    private fun replaceAssignments(eid: String, groupNo: Int, assignments: List<Map<String, Any?>>) =
        mockMvc.perform(
            put("/api/events/$eid/seat-assignments")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("groupNo" to groupNo, "assignments" to assignments))),
        ).andExpect(status().isOk)

    private fun groupNoOf(eid: String, gid: String): Int {
        val response = mockMvc.perform(
            get("/api/events/$eid/seat-groups")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid))),
        ).andReturn().response.contentAsString
        val data = objectMapper.readTree(response).get("data")
        return data.first { it.get("id").asText() == gid }.get("groupNo").asInt()
    }

    // ── CRUD(§12-1~3) ────────────────────────────────────────────────

    @Test
    fun `create는 그룹을 생성하고 groupNo·sortOrder를 서버가 채번하며 배정 0건으로 응답한다`() {
        val eid = createEvent()

        createGroupRaw(eid, "A열", numbering = true)
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.label").value("A열"))
            .andExpect(jsonPath("$.data.numbering").value(true))
            .andExpect(jsonPath("$.data.groupNo").value(1))
            .andExpect(jsonPath("$.data.assignedCount").value(0))
            .andExpect(jsonPath("$.data.slotCount").value(0))
    }

    @Test
    fun `create는 numbering OFF 그룹에서 slotCount 키 자체를 생략한다`() {
        val eid = createEvent()

        createGroupRaw(eid, "1번 테이블", numbering = false)
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.slotCount").doesNotExist())
    }

    @Test
    fun `create는 label이 비어 있으면 400 VALIDATION_FAILED를 받는다`() {
        val eid = createEvent()

        createGroupRaw(eid, "", numbering = true)
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
    }

    @Test
    fun `list는 assignedCount·slotCount를 배정 실적과 함께 집계해 반환한다`() {
        val eid = createEvent()
        val gid = createGroup(eid, "A열", numbering = true)
        val g1 = registerGuest(eid, "이서연")
        val groupNo = groupNoOf(eid, gid)

        // 2석 배정(ord=1 착석, ord=2 빈좌석) — slotCount=2, assignedCount=1이어야 한다.
        replaceAssignments(eid, groupNo, listOf(mapOf("ord" to 1, "guestId" to g1), mapOf("ord" to 2, "guestId" to null)))

        mockMvc.perform(
            get("/api/events/$eid/seat-groups")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].assignedCount").value(1))
            .andExpect(jsonPath("$.data[0].slotCount").value(2))
    }

    @Test
    fun `update는 label만 바꾸면 배정을 재해석하지 않는다`() {
        val eid = createEvent()
        val gid = createGroup(eid, "A열", numbering = true)
        val g1 = registerGuest(eid, "박서연")
        val groupNo = groupNoOf(eid, gid)
        replaceAssignments(eid, groupNo, listOf(mapOf("ord" to 1, "guestId" to g1)))

        mockMvc.perform(
            put("/api/events/$eid/seat-groups/$gid")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"label":"A열 변경","numbering":true}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.label").value("A열 변경"))
            .andExpect(jsonPath("$.data.assignedCount").value(1))
            .andExpect(jsonPath("$.data.slotCount").value(1))
    }

    @Test
    fun `update는 존재하지 않는 그룹이면 404 NOT_FOUND를 받는다`() {
        val eid = createEvent()

        mockMvc.perform(
            put("/api/events/$eid/seat-groups/ghost-id")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"label":"X","numbering":true}"""),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
    }

    // ── M4(numbering 토글 재해석) — 실 DB read-back ───────────────────

    @Test
    fun `update는 ON에서 OFF로 토글하면 빈좌석을 삭제하고 남은 멤버 ord를 9999로 일괄 갱신한다`() {
        val eid = createEvent()
        val gid = createGroup(eid, "A열", numbering = true)
        val g1 = registerGuest(eid, "김민준")
        val groupNo = groupNoOf(eid, gid)
        // 3석 중 1석만 배정, 2석은 빈좌석(ord=2,3)
        replaceAssignments(
            eid, groupNo,
            listOf(
                mapOf("ord" to 1, "guestId" to g1),
                mapOf("ord" to 2, "guestId" to null),
                mapOf("ord" to 3, "guestId" to null),
            ),
        )

        mockMvc.perform(
            put("/api/events/$eid/seat-groups/$gid")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"label":"A열","numbering":false}"""),
        ).andExpect(status().isOk)

        val rows = jdbcTemplate.queryForList(
            "SELECT ord, guest_id FROM seat_assignment WHERE seat_group_id = ?",
            gid,
        )
        assertEquals(1, rows.size, "빈좌석 2건은 삭제되고 멤버 1건만 남아야 한다")
        assertEquals(9999, (rows.first()["ord"] as Number).toInt())
        assertEquals(g1, rows.first()["guest_id"])
    }

    @Test
    fun `update는 OFF에서 ON으로 토글하면 멤버를 이름 오름차순으로 1부터 재채번한다`() {
        val eid = createEvent()
        val gid = createGroup(eid, "1번 테이블", numbering = false)
        val gDong = registerGuest(eid, "박동현")
        val gAh = registerGuest(eid, "김아름")
        val groupNo = groupNoOf(eid, gid)
        replaceAssignments(
            eid, groupNo,
            listOf(mapOf("ord" to 0, "guestId" to gDong), mapOf("ord" to 0, "guestId" to gAh)),
        )

        mockMvc.perform(
            put("/api/events/$eid/seat-groups/$gid")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"label":"1번 테이블","numbering":true}"""),
        ).andExpect(status().isOk)

        val rows = jdbcTemplate.queryForList(
            "SELECT ord, guest_id FROM seat_assignment WHERE seat_group_id = ? ORDER BY ord ASC",
            gid,
        )
        assertEquals(2, rows.size)
        // 이름 오름차순(가나다) — "김아름"이 "박동현"보다 먼저.
        assertEquals(gAh, rows[0]["guest_id"])
        assertEquals(1, (rows[0]["ord"] as Number).toInt())
        assertEquals(gDong, rows[1]["guest_id"])
        assertEquals(2, (rows[1]["ord"] as Number).toInt())
    }

    // ── DELETE — CASCADE·SET NULL 동시 확인 ───────────────────────────

    @Test
    fun `delete는 seat_assignment를 CASCADE 삭제하고 guest_seat_group_id를 NULL로 되돌린다`() {
        val eid = createEvent()
        val gid = createGroup(eid, "A열", numbering = true)
        val g1 = registerGuest(eid, "이도현")
        val groupNo = groupNoOf(eid, gid)
        replaceAssignments(eid, groupNo, listOf(mapOf("ord" to 1, "guestId" to g1)))

        mockMvc.perform(
            delete("/api/events/$eid/seat-groups/$gid")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid))),
        ).andExpect(status().isOk)

        val assignmentCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM seat_assignment WHERE seat_group_id = ?",
            Int::class.java,
            gid,
        )
        assertEquals(0, assignmentCount)
        // queryForObject(String::class.java)는 결과가 NULL일 때 Kotlin extension이 `!!`로 언박싱해
        // NPE를 던진다 — queryForList로 Map을 받아 null-safe하게 확인한다.
        val guestSeatGroupId = jdbcTemplate.queryForList("SELECT seat_group_id FROM guest WHERE id = ?", g1).first()["seat_group_id"]
        assertNull(guestSeatGroupId)
    }

    @Test
    fun `delete는 존재하지 않는 그룹이면 404 NOT_FOUND를 받는다`() {
        val eid = createEvent()

        mockMvc.perform(
            delete("/api/events/$eid/seat-groups/ghost-id")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid))),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
    }

    // ── cross-tenant 격리(P1) ────────────────────────────────────────

    @Test
    fun `EVENT_ADMIN이 담당 아닌 행사의 그룹 목록을 조회하면 403 EVENT_FORBIDDEN을 받는다`() {
        val eid = createEvent()

        mockMvc.perform(
            get("/api/events/$eid/seat-groups")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf("다른-행사-id"))),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("EVENT_FORBIDDEN"))
    }

    @Test
    fun `EVENT_ADMIN이 담당 아닌 행사에 그룹을 생성하면 403 EVENT_FORBIDDEN을 받는다`() {
        val eid = createEvent()

        createGroupRaw(eid, "A열", numbering = true, eventIds = listOf("다른-행사-id"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("EVENT_FORBIDDEN"))
    }

    @Test
    fun `EVENT_STAFF는 관리자 콘솔 전용 좌석그룹 API에서 403 ROLE_FORBIDDEN을 받는다`() {
        val eid = createEvent()

        mockMvc.perform(
            get("/api/events/$eid/seat-groups")
                .with(authenticatedAs(roles = listOf("EVENT_STAFF"), eventIds = listOf(eid))),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("ROLE_FORBIDDEN"))
    }
}
