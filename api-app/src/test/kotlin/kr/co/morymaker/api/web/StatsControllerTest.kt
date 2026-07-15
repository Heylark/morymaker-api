package kr.co.morymaker.api.web

import com.fasterxml.jackson.databind.ObjectMapper
import kr.co.morymaker.api.config.SecurityConfig
import org.apache.poi.xssf.usermodel.XSSFWorkbook
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
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 통계 API(§8) 통합 테스트 — 집계 4쿼리(registration+attendance·byZone·timeline·arrivals) 산식·
 * 취소자 제외·timeline/arrivals 의도적 비대칭·cross-tenant 격리·Excel export를 실 MariaDB로
 * 검증한다(02-architect.md §5 SQL·§6 서비스 조립이 검증 대상 — mock은 이 SQL 경로를 우회하므로
 * `~/.claude/rules-on-demand/anti-rationalization.md` Tester 절에 따라 실 DB 통합 테스트로 확인).
 *
 * §8-1 spec 예시(사전 200/현장 100/합계 300, 실참석 180/80/260)는 그대로 재현하면 비용이 크므로
 * 동일 분수 비율을 유지한 10배 축소값(사전 20/현장 10, 실참석 18/8)으로 산식을 검증한다 — HALF_UP
 * 2자리 반올림 결과는 기약분수 동치라 spec 예시(0.67/0.33/0.90/0.80/0.87)와 100% 동일하다.
 *
 * `GuestControllerTest`·`ParkingZoneControllerTest`·`LookupControllerTest`와 동일 컨벤션 —
 * `.with(jwt())` 직접 주입, `@Transactional` 자동 롤백. `checkin()`은 항상 `Instant.now()`를
 * 사용해 시간버킷을 통제할 수 없으므로, `LookupControllerTest`의 전례(전용 등록 API가 없는
 * seat_group/parking_record 직접 JDBC 삽입)를 따라 `visit_at`만 `JdbcTemplate`으로 조정한다 —
 * MockMvc와 스레드 바인딩 커넥션을 공유해야 하므로 `DataSource.connection` 대신 `JdbcTemplate`을
 * 사용한다(LookupControllerTest 주석 — 별도 커넥션 사용 시 자기 자신 행에 Lock wait 발생).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StatsControllerTest(
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

    private fun createEvent(name: String = "통계 테스트 행사"): String {
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

    private fun registerGuest(eid: String, name: String, src: String? = null): String {
        val body = objectMapper.writeValueAsString(mapOf("name" to name, "src" to src))
        val response = mockMvc.perform(
            post("/events/$eid/guests")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isCreated)
            .andReturn().response.contentAsString
        return objectMapper.readTree(response).get("data").get("id").asText()
    }

    private fun checkinGuest(eid: String, gid: String) {
        mockMvc.perform(
            post("/events/$eid/checkin")
                .with(authenticatedAs(roles = listOf("EVENT_STAFF"), eventIds = listOf(eid)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"guestId":"$gid"}"""),
        ).andExpect(status().isOk)
    }

    private fun cancelGuest(eid: String, gid: String) {
        mockMvc.perform(
            delete("/events/$eid/guests/$gid")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid))),
        ).andExpect(status().isOk)
    }

    /** checkin()은 항상 Instant.now()라 시간버킷을 통제할 수 없어 visit_at을 직접 조정한다. */
    private fun setVisitAt(gid: String, datetime: String) {
        jdbcTemplate.update("UPDATE guest SET visit_at = ? WHERE id = ?", datetime, gid)
    }

    /** 참석 확정 없이 "방문"(주차매핑) 전이만 재현 — timeline(참석 전용)엔 잡히지 않고 arrivals엔 잡혀야 한다. */
    private fun markVisitedOnly(gid: String, datetime: String) {
        jdbcTemplate.update("UPDATE guest SET status = '방문', visit_at = ? WHERE id = ?", datetime, gid)
    }

    private fun createZone(eid: String, part1: String, part2: String, startNo: Int = 1, slotCount: Int = 5): String {
        val response = mockMvc.perform(
            post("/events/$eid/parking-zones")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("part1" to part1, "part2" to part2, "startNo" to startNo, "slotCount" to slotCount))),
        )
            .andExpect(status().isCreated)
            .andReturn().response.contentAsString
        return objectMapper.readTree(response).get("data").get("id").asText()
    }

    /** 전용 등록 API가 승계 3분기를 캡슐화해 review_needed를 직접 조작할 수 없으므로(§6-6) — 순수 집계 SQL만
     * 검증 목적인 byZone 테스트는 `LookupControllerTest` 전례를 따라 parking_record를 직접 JDBC 삽입한다. */
    private fun insertParkingRecord(eid: String, zoneId: String, slotSig: String, status: String, reviewNeeded: Boolean) {
        jdbcTemplate.update(
            """
            INSERT INTO parking_record (id, event_id, zone_id, slot_sig, plate, registered_by, status, review_needed)
            VALUES (?, ?, ?, ?, ?, '요원', ?, ?)
            """.trimIndent(),
            UUID.randomUUID().toString(), eid, zoneId, slotSig, "12가${(1000..9999).random()}", status, if (reviewNeeded) 1 else 0,
        )
    }

    private fun getStats(eid: String, eventIds: List<String> = listOf(eid), roles: List<String> = listOf("EVENT_ADMIN")) =
        mockMvc.perform(get("/events/$eid/stats").with(authenticatedAs(roles = roles, eventIds = eventIds)))

    // ── TC-STATS-001: registration/attendance 산식(P1, 축소 worked example) ──────

    @Test
    fun `registration·attendance 산식은 축소 worked example에서 spec 8-1 예시와 동일한 반올림 결과를 낸다`() {
        val eid = createEvent()

        // 사전 20건 등록 → 18건 참석 확정 (200/180 축소, 0.90 유지)
        repeat(20) { i ->
            val gid = registerGuest(eid, "사전$i", src = "사전")
            if (i < 18) checkinGuest(eid, gid)
        }
        // 현장 10건 등록 → 8건 참석 확정 (100/80 축소, 0.80 유지)
        repeat(10) { i ->
            val gid = registerGuest(eid, "현장$i", src = "현장")
            if (i < 8) checkinGuest(eid, gid)
        }

        getStats(eid)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.registration.pre").value(20))
            .andExpect(jsonPath("$.data.registration.on").value(10))
            .andExpect(jsonPath("$.data.registration.total").value(30))
            .andExpect(jsonPath("$.data.registration.preRatio").value(0.67))
            .andExpect(jsonPath("$.data.registration.onRatio").value(0.33))
            .andExpect(jsonPath("$.data.attendance.preAtt").value(18))
            .andExpect(jsonPath("$.data.attendance.onAtt").value(8))
            .andExpect(jsonPath("$.data.attendance.totAtt").value(26))
            .andExpect(jsonPath("$.data.attendance.preRate").value(0.90))
            .andExpect(jsonPath("$.data.attendance.onRate").value(0.80))
            .andExpect(jsonPath("$.data.attendance.totRate").value(0.87))
    }

    // ── TC-STATS-002: 취소자 제외 + 분모 0 방어(P1) ───────────────────────

    @Test
    fun `취소된 참석자는 registration·attendance 전 집계에서 제외된다`() {
        val eid = createEvent()
        val kept = registerGuest(eid, "유지", src = "사전")
        checkinGuest(eid, kept)
        val cancelled = registerGuest(eid, "취소대상", src = "사전")
        checkinGuest(eid, cancelled)
        cancelGuest(eid, cancelled)

        getStats(eid)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.registration.pre").value(1))
            .andExpect(jsonPath("$.data.registration.total").value(1))
            .andExpect(jsonPath("$.data.attendance.preAtt").value(1))
            .andExpect(jsonPath("$.data.attendance.totAtt").value(1))
    }

    @Test
    fun `등록 0건 이벤트는 500 없이 ratio·rate가 0-0으로 방어된다`() {
        val eid = createEvent()

        getStats(eid)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.registration.total").value(0))
            .andExpect(jsonPath("$.data.registration.preRatio").value(0.0))
            .andExpect(jsonPath("$.data.registration.onRatio").value(0.0))
            .andExpect(jsonPath("$.data.attendance.preRate").value(0.0))
            .andExpect(jsonPath("$.data.attendance.totRate").value(0.0))
    }

    // ── TC-STATS-003: parking byZone 점유·확인필요(P1) ────────────────────

    @Test
    fun `byZone은 활성(주차중) 기록만 점유로 집계하고 parked는 byZone occupied 합과 같다`() {
        val eid = createEvent()
        val zoneA = createZone(eid, "지하 2층", "A구역", startNo = 1, slotCount = 5)
        val zoneB = createZone(eid, "지하 3층", "B구역", startNo = 1, slotCount = 3)

        // zoneA: 활성 2건(그중 1건 확인필요) + 출차 1건(미집계 대상)
        insertParkingRecord(eid, zoneA, "지하 2층·A구역·1", status = "주차중", reviewNeeded = true)
        insertParkingRecord(eid, zoneA, "지하 2층·A구역·2", status = "주차중", reviewNeeded = false)
        insertParkingRecord(eid, zoneA, "지하 2층·A구역·3", status = "출차", reviewNeeded = false)
        // zoneB: 활성 1건
        insertParkingRecord(eid, zoneB, "지하 3층·B구역·1", status = "주차중", reviewNeeded = false)

        val response = getStats(eid)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.parking.parked").value(3))
            .andReturn().response.getContentAsString(StandardCharsets.UTF_8)
        val byZone = objectMapper.readTree(response).get("data").get("parking").get("byZone")
        val zoneAJson = byZone.find { it.get("zoneId").asText() == zoneA }!!
        val zoneBJson = byZone.find { it.get("zoneId").asText() == zoneB }!!

        assertEquals(2, zoneAJson.get("occupied").asInt())
        assertEquals(1, zoneAJson.get("reviewNeeded").asInt())
        assertEquals("지하 2층 A구역", zoneAJson.get("zoneName").asText())
        assertEquals(1, zoneBJson.get("occupied").asInt())
        assertEquals(0, zoneBJson.get("reviewNeeded").asInt())
    }

    // ── TC-STATS-004: timeline(참석) vs arrivals(방문+참석) 의도적 비대칭(P2) ──

    @Test
    fun `timeline은 참석 확정 guest만 시간버킷 누적하고 arrivals는 방문·참석을 모두 도착순 포함한다`() {
        val eid = createEvent()

        val g1 = registerGuest(eid, "16시도착", src = "사전")
        checkinGuest(eid, g1)
        setVisitAt(g1, "2026-07-10 16:30:00")

        val g2 = registerGuest(eid, "17시도착", src = "현장")
        checkinGuest(eid, g2)
        setVisitAt(g2, "2026-07-10 17:15:00")

        // 참석 확정 없이 "방문"(주차매핑)만 — timeline엔 잡히지 않고 arrivals엔 잡혀야 한다.
        val g3 = registerGuest(eid, "18시방문만", src = "사전")
        markVisitedOnly(g3, "2026-07-10 18:00:00")

        val response = getStats(eid)
            .andExpect(status().isOk)
            .andReturn().response.getContentAsString(StandardCharsets.UTF_8)
        val data = objectMapper.readTree(response).get("data")

        val timeline = data.get("timeline")
        assertEquals(2, timeline.size(), "방문만 한 guest는 timeline에서 제외돼야 한다")
        assertEquals("16시", timeline[0].get("t").asText())
        assertEquals(1, timeline[0].get("cumulative").asInt())
        assertEquals("17시", timeline[1].get("t").asText())
        assertEquals(2, timeline[1].get("cumulative").asInt(), "누적(cumulative)은 단조증가해야 한다")

        val arrivals = data.get("arrivals")
        assertEquals(3, arrivals.size(), "방문+참석 모두 arrivals엔 포함돼야 한다")
        assertEquals(listOf("18시방문만", "17시도착", "16시도착"), arrivals.map { it.get("name").asText() }, "visit_at DESC 정렬이어야 한다")
    }

    // ── TC-STATS-005: cross-tenant 격리·인가 표면(P1) ─────────────────────

    @Test
    fun `EVENT_ADMIN이 담당 아닌 행사의 통계를 조회하면 403 EVENT_FORBIDDEN을 받는다`() {
        val eid = createEvent()

        getStats(eid, eventIds = listOf("다른-행사-id"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("EVENT_FORBIDDEN"))
    }

    @Test
    fun `EVENT_ADMIN이 담당 아닌 행사의 export를 요청하면 403 EVENT_FORBIDDEN을 받는다`() {
        val eid = createEvent()

        mockMvc.perform(
            get("/events/$eid/stats/export")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf("다른-행사-id"))),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("EVENT_FORBIDDEN"))
    }

    @Test
    fun `event_ids 클레임이 없는 EVENT_ADMIN은 fail-CLOSED로 통계 조회가 거부된다`() {
        val eid = createEvent()

        getStats(eid, eventIds = emptyList())
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("EVENT_FORBIDDEN"))
    }

    @Test
    fun `EVENT_STAFF는 관리자 콘솔 전용 통계 API에서 403 ROLE_FORBIDDEN을 받는다`() {
        val eid = createEvent()

        getStats(eid, roles = listOf("EVENT_STAFF"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("ROLE_FORBIDDEN"))
    }

    @Test
    fun `담당 행사의 EVENT_ADMIN은 정상적으로 통계를 조회한다(양성 대조 — 가드 실효성 확인)`() {
        val eid = createEvent()

        getStats(eid)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.registration.total").value(0))
    }

    // ── TC-STATS-006: Excel export(P2) ────────────────────────────────

    @Test
    fun `export는 4시트 xlsx를 반환하고 시트 값이 8-1 집계와 일치한다`() {
        val eid = createEvent()
        val zone = createZone(eid, "지하 2층", "A구역", startNo = 1, slotCount = 5)
        insertParkingRecord(eid, zone, "지하 2층·A구역·1", status = "주차중", reviewNeeded = true)
        val gid = registerGuest(eid, "김진우", src = "사전")
        checkinGuest(eid, gid)

        val result = mockMvc.perform(
            get("/events/$eid/stats/export")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid))),
        )
            .andExpect(status().isOk)
            .andReturn()

        assertEquals(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            result.response.contentType,
        )
        val disposition = result.response.getHeader("Content-Disposition")
        assertTrue(disposition!!.contains("filename*=UTF-8''"), "RFC 5987 한글 파일명 인코딩이어야 한다")

        XSSFWorkbook(ByteArrayInputStream(result.response.contentAsByteArray)).use { wb ->
            assertEquals(listOf("지표", "실참석", "도착순", "주차현황"), (0 until wb.numberOfSheets).map { wb.getSheetName(it) })

            val indicator = wb.getSheet("지표")
            assertEquals("사전", indicator.getRow(1).getCell(0).stringCellValue)
            assertEquals(1.0, indicator.getRow(1).getCell(1).numericCellValue)   // registration.pre = 1

            val attendance = wb.getSheet("실참석")
            assertEquals(1.0, attendance.getRow(1).getCell(1).numericCellValue)  // attendance.preAtt = 1

            val arrivals = wb.getSheet("도착순")
            assertEquals("김진우", arrivals.getRow(1).getCell(0).stringCellValue)

            val parking = wb.getSheet("주차현황")
            assertEquals(1.0, parking.getRow(1).getCell(2).numericCellValue)     // 전체 주차중 = parked = 1
            assertEquals("지하 2층 A구역", parking.getRow(2).getCell(0).stringCellValue)
            assertEquals(1.0, parking.getRow(2).getCell(3).numericCellValue)     // 확인필요 = 1
        }
    }

    // ── TC-STATS-007: 응답 contract(P1) ───────────────────────────────

    @Test
    fun `GET stats 응답은 8-1 shape과 1대1로 일치하며 인가는 관리자 콘솔 전용이다`() {
        val eid = createEvent()
        val zone = createZone(eid, "지하 2층", "A구역")
        insertParkingRecord(eid, zone, "지하 2층·A구역·1", status = "주차중", reviewNeeded = false)
        val gid = registerGuest(eid, "정예나", src = "사전")
        checkinGuest(eid, gid)

        getStats(eid)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.registration.pre").exists())
            .andExpect(jsonPath("$.data.registration.on").exists())
            .andExpect(jsonPath("$.data.registration.total").exists())
            .andExpect(jsonPath("$.data.registration.preRatio").exists())
            .andExpect(jsonPath("$.data.registration.onRatio").exists())
            .andExpect(jsonPath("$.data.attendance.preAtt").exists())
            .andExpect(jsonPath("$.data.attendance.onAtt").exists())
            .andExpect(jsonPath("$.data.attendance.totAtt").exists())
            .andExpect(jsonPath("$.data.attendance.preRate").exists())
            .andExpect(jsonPath("$.data.attendance.onRate").exists())
            .andExpect(jsonPath("$.data.attendance.totRate").exists())
            .andExpect(jsonPath("$.data.parking.parked").exists())
            .andExpect(jsonPath("$.data.parking.byZone[0].zoneId").exists())
            .andExpect(jsonPath("$.data.parking.byZone[0].zoneName").exists())
            .andExpect(jsonPath("$.data.parking.byZone[0].slotCount").exists())
            .andExpect(jsonPath("$.data.parking.byZone[0].occupied").exists())
            .andExpect(jsonPath("$.data.parking.byZone[0].reviewNeeded").exists())
            .andExpect(jsonPath("$.data.arrivals[0].guestId").exists())
            .andExpect(jsonPath("$.data.arrivals[0].name").value("정예나"))
            .andExpect(jsonPath("$.data.arrivals[0].visitAt").exists())
            .andExpect(jsonPath("$.data.timeline[0].t").exists())
            .andExpect(jsonPath("$.data.timeline[0].cumulative").exists())
    }

    @Test
    fun `refresh 파라미터는 계약 호환용으로 수용되며 결과에 영향 없다(no-op)`() {
        val eid = createEvent()

        mockMvc.perform(
            get("/events/$eid/stats?refresh=true")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.registration.total").value(0))
    }
}
