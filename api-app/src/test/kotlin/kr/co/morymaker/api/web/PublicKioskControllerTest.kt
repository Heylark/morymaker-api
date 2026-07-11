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
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * kiosk 공개 조회 API(REQ-0019, KIO-02·04·05) 통합 테스트 — 비로그인 접근·최소필드 구조적
 * 미노출(D-A)·eid capability 게이트(D-I)·체크인 멱등성(D-F)을 실 MariaDB로 검증한다.
 *
 * `LookupControllerTest`·`PublicSecurityAdversarialTest`와 동일 컨벤션 — 좌석·주차 기록은
 * 전용 등록 API가 없어 `JdbcTemplate` 직접 삽입, `@Transactional`이 종료 시 자동 롤백한다.
 *
 * `@TestPropertySource`로 rate limit 임계를 크게 올려 별도 Spring 컨텍스트로 격리한다
 * (`PublicRateLimitEndToEndTest`와 동일 원리 — 프로퍼티 소스가 다르면 컨텍스트 캐시 키가
 * 달라져 별도 인터셉터 빈 인스턴스를 받는다). 이 클래스는 이름검색·주차검색 GET(D-B로 신규
 * rate limit 대상)·체크인 POST를 다수 호출하는 기능 테스트라, 다른 테스트 클래스와 인메모리
 * rate limit 윈도(IP 키 싱글턴 상태)를 공유하면 순서에 따라 429가 섞여 함께 깨질 수 있다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = ["morymaker.public.rate-limit.limit=1000"])
class PublicKioskControllerTest(
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

    private fun createEvent(name: String = "kiosk 공개 API 테스트 행사"): String {
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

    private fun registerGuest(eid: String, name: String, plate: String? = null): String {
        val body = objectMapper.writeValueAsString(mapOf("name" to name, "plate" to plate))
        val response = mockMvc.perform(
            post("/api/events/$eid/guests")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isCreated)
            .andReturn().response.contentAsString
        return objectMapper.readTree(response).get("data").get("id").asText()
    }

    /** 좌석 그룹 배정 — 전용 등록 API는 별도 REQ 범위(LookupControllerTest와 동일 컨벤션). */
    private fun assignSeat(eid: String, gid: String, seatLabel: String) {
        val seatGroupId = UUID.randomUUID().toString()
        jdbcTemplate.update(
            "INSERT INTO seat_group (id, event_id, group_no, label, numbering, sort_order) VALUES (?, ?, ?, ?, 0, 0)",
            seatGroupId, eid, 1, seatLabel,
        )
        jdbcTemplate.update("UPDATE guest SET seat_group_id = ? WHERE id = ?", seatGroupId, gid)
    }

    /** 활성(주차중) 주차기록 직접 삽입 + guest_id 연결. */
    private fun insertParkedRecord(eid: String, plate: String, gid: String? = null, slotSig: String = "z1-01"): String {
        val zoneId = UUID.randomUUID().toString()
        val recordId = UUID.randomUUID().toString()
        jdbcTemplate.update(
            "INSERT INTO parking_zone (id, event_id, start_no, slot_count) VALUES (?, ?, 1, 10)",
            zoneId, eid,
        )
        jdbcTemplate.update(
            """
            INSERT INTO parking_record (id, event_id, zone_id, slot_sig, plate, guest_id, registered_by, status)
            VALUES (?, ?, ?, ?, ?, ?, '요원', '주차중')
            """.trimIndent(),
            recordId, eid, zoneId, slotSig, plate, gid,
        )
        return recordId
    }

    // ── 비로그인 접근(무흔적) — 인증 헤더 없이 정상 응답 ─────────────────

    @Test
    fun `이름검색은 인증 헤더 없이 200을 반환한다`() {
        val eid = createEvent()
        registerGuest(eid, "김진우")

        mockMvc.perform(get("/api/public/events/$eid/attendees?name=김진우"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(1))
    }

    // ── eid capability 게이트(D-I) ───────────────────────────────────

    @Test
    fun `존재하지 않는 eid로 이름검색하면 404를 받는다`() {
        mockMvc.perform(get("/api/public/events/${UUID.randomUUID()}/attendees?name=김진우"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `종료된 행사에 이름검색하면 409를 받는다`() {
        val eid = createEvent()
        closeEvent(eid)

        mockMvc.perform(get("/api/public/events/$eid/attendees?name=김진우"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.code").value("EVENT_CLOSED"))
    }

    // ── KIO-02 이름검색 — 3상태 + 최소필드 구조적 미노출(D-A) ──────────────

    @Test
    fun `이름검색은 매칭 1건이면 searchState ONE과 최소필드만 반환한다`() {
        val eid = createEvent()
        val gid = registerGuest(eid, "김진우", plate = "12가3456")
        assignSeat(eid, gid, "A-12")

        mockMvc.perform(get("/api/public/events/$eid/attendees?name=김진우"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.meta.searchState").value("ONE"))
            .andExpect(jsonPath("$.data[0].guestId").value(gid))
            .andExpect(jsonPath("$.data[0].name").value("김진우"))
            .andExpect(jsonPath("$.data[0].seatLabel").value("A-12"))
            .andExpect(jsonPath("$.data[0].phone").doesNotExist())
            .andExpect(jsonPath("$.data[0].plate").doesNotExist())
            .andExpect(jsonPath("$.data[0].title").doesNotExist())
            .andExpect(jsonPath("$.data[0].token").doesNotExist())
            .andExpect(jsonPath("$.data[0].parking").doesNotExist())
    }

    @Test
    fun `이름이 2자 미만이면 400을 받는다(단일문자 열거 차단)`() {
        val eid = createEvent()

        mockMvc.perform(get("/api/public/events/$eid/attendees?name=김"))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `매칭 0건이면 searchState NONE과 빈 data를 반환한다`() {
        val eid = createEvent()
        registerGuest(eid, "김진우")

        mockMvc.perform(get("/api/public/events/$eid/attendees?name=없는이름"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.meta.searchState").value("NONE"))
            .andExpect(jsonPath("$.data.length()").value(0))
    }

    @Test
    fun `타 행사 참석자는 이름검색 결과에 노출되지 않는다(cross-event 격리)`() {
        val eidA = createEvent("행사 A")
        val eidB = createEvent("행사 B")
        registerGuest(eidB, "김진우")

        mockMvc.perform(get("/api/public/events/$eidA/attendees?name=김진우"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(0))
    }

    // ── KIO-04 체크인 — 상태전이·멱등성·최소필드(D-A) ────────────────────

    @Test
    fun `체크인은 대기중 참석자를 참석으로 확정하고 좌석을 병기한다`() {
        val eid = createEvent()
        val gid = registerGuest(eid, "박서연")
        assignSeat(eid, gid, "B-07")

        mockMvc.perform(
            post("/api/public/events/$eid/checkin")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"guestId":"$gid"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.resultCode").value("CHECKED_IN"))
            .andExpect(jsonPath("$.data.guest.name").value("박서연"))
            .andExpect(jsonPath("$.data.guest.status").value("참석"))
            .andExpect(jsonPath("$.data.guest.seatLabel").value("B-07"))
            .andExpect(jsonPath("$.data.guest.id").doesNotExist())
            .andExpect(jsonPath("$.data.guest.visitAt").doesNotExist())
            .andExpect(jsonPath("$.data.guest.phone").doesNotExist())
    }

    @Test
    fun `체크인은 좌석·주차를 함께 병기한다`() {
        val eid = createEvent()
        val gid = registerGuest(eid, "이도현")
        assignSeat(eid, gid, "C-03")
        insertParkedRecord(eid, "12가3456", gid = gid, slotSig = "지하 2층·A구역·3")

        mockMvc.perform(
            post("/api/public/events/$eid/checkin")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"guestId":"$gid"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.parking.display").value("지하 2층 A구역 3"))
            .andExpect(jsonPath("$.data.parking.slotSig").doesNotExist())
    }

    @Test
    fun `이미 체크인된 참석자를 재호출하면 상태 재변경 없이 멱등 200을 받는다`() {
        val eid = createEvent()
        val gid = registerGuest(eid, "정하은")

        mockMvc.perform(
            post("/api/public/events/$eid/checkin")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"guestId":"$gid"}"""),
        ).andExpect(status().isOk).andExpect(jsonPath("$.data.resultCode").value("CHECKED_IN"))

        mockMvc.perform(
            post("/api/public/events/$eid/checkin")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"guestId":"$gid"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.resultCode").value("ALREADY_CHECKED_IN"))
    }

    @Test
    fun `guestId가 없는 요청은 400을 받는다`() {
        val eid = createEvent()

        mockMvc.perform(
            post("/api/public/events/$eid/checkin")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{}"""),
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `타 행사의 guestId로 체크인을 시도하면 404를 받는다(cross-event 격리)`() {
        val eidA = createEvent("행사 A")
        val eidB = createEvent("행사 B")
        val gidB = registerGuest(eidB, "타행사참석자")

        mockMvc.perform(
            post("/api/public/events/$eidA/checkin")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"guestId":"$gidB"}"""),
        ).andExpect(status().isNotFound)
    }

    // ── KIO-05 주차검색 — plateTail 게이트·활성만·최소필드(D-A·H) ───────────

    @Test
    fun `주차검색은 뒷자리 4자리 매칭 시 plate와 slotDisplay만 반환한다`() {
        val eid = createEvent()
        insertParkedRecord(eid, "12가3456", slotSig = "지하 2층·A구역·3")

        mockMvc.perform(get("/api/public/events/$eid/parking-search?plateTail=3456"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].plate").value("12가3456"))
            .andExpect(jsonPath("$.data[0].slotDisplay").value("지하 2층 A구역 3번"))
            .andExpect(jsonPath("$.data[0].phone").doesNotExist())
            .andExpect(jsonPath("$.data[0].vipName").doesNotExist())
            .andExpect(jsonPath("$.data[0].guestId").doesNotExist())
            .andExpect(jsonPath("$.data[0].registeredBy").doesNotExist())
    }

    @Test
    fun `plateTail이 4자리가 아니면 400을 받는다`() {
        val eid = createEvent()

        mockMvc.perform(get("/api/public/events/$eid/parking-search?plateTail=123"))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `출차된 기록은 주차검색 결과에서 제외된다`() {
        val eid = createEvent()
        val recordId = insertParkedRecord(eid, "99하9999")
        jdbcTemplate.update("UPDATE parking_record SET status = '출차' WHERE id = ?", recordId)

        mockMvc.perform(get("/api/public/events/$eid/parking-search?plateTail=9999"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(0))
    }

    @Test
    fun `타 행사의 주차기록은 검색 결과에 노출되지 않는다(cross-event 격리)`() {
        val eidA = createEvent("행사 A")
        val eidB = createEvent("행사 B")
        insertParkedRecord(eidB, "77가7777")

        mockMvc.perform(get("/api/public/events/$eidA/parking-search?plateTail=7777"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(0))
    }
}
