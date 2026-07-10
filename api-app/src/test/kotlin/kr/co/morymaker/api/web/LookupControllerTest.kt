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
import java.util.UUID

/**
 * 실행자 통합조회 API(§9-1, HLP-01) 통합 테스트 — cross-tenant 격리·searchAny(이름∪차량 뒷자리)·
 * 좌석+주차 동시 병기·취소자 제외·q 파라미터 누락 오분류 방지를 실 MariaDB로 검증한다.
 *
 * `GuestControllerTest`와 동일 컨벤션 — `.with(jwt())` 직접 주입, `@Transactional` 자동 롤백.
 * 좌석 그룹·주차 기록은 아직 전용 등록 API가 없어(별도 REQ 범위) 이 테스트가 직접 JDBC로
 * seat_group/parking_record를 삽입한다. `GuestImportIntegrationTest`와 달리 `JdbcTemplate`을
 * 사용한다 — 이 클래스는 클래스 레벨 `@Transactional`이라 MockMvc 호출과 같은 스레드 바인딩
 * 커넥션을 공유해야 하는데, `DataSource.connection`으로 직접 커넥션을 얻으면 커넥션 풀에서 새
 * 커넥션을 받아와 같은 guest 행에 락 대기(Lock wait timeout)가 발생한다(실측 확인).
 * `JdbcTemplate`은 스레드에 바인딩된 트랜잭션 커넥션을 자동으로 재사용한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LookupControllerTest(
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

    private fun createEvent(name: String = "통합조회 테스트 행사"): String {
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

    /** 좌석 그룹 배정 + 활성 주차기록(guest_id 백필)을 직접 JDBC로 삽입 — 전용 등록 API는 별도 REQ 범위. */
    private fun assignSeatAndParking(eid: String, gid: String, seatLabel: String, plate: String, slotSig: String) {
        val seatGroupId = UUID.randomUUID().toString()
        val zoneId = UUID.randomUUID().toString()
        val recordId = UUID.randomUUID().toString()
        jdbcTemplate.update(
            "INSERT INTO seat_group (id, event_id, group_no, label, numbering, sort_order) VALUES (?, ?, ?, ?, 0, 0)",
            seatGroupId, eid, 1, seatLabel,
        )
        jdbcTemplate.update("UPDATE guest SET seat_group_id = ? WHERE id = ?", seatGroupId, gid)
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
    }

    // ── cross-tenant 격리·인가 표면(P1) ─────────────────────────────

    @Test
    fun `EVENT_ADMIN이 담당 아닌 행사에서 조회하면 403 EVENT_FORBIDDEN을 받는다`() {
        val eid = createEvent()

        mockMvc.perform(
            get("/api/events/$eid/lookup?q=김진우")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf("다른-행사-id"))),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("EVENT_FORBIDDEN"))
    }

    @Test
    fun `event_ids 클레임이 없는 EVENT_ADMIN은 fail-CLOSED로 조회가 거부된다`() {
        val eid = createEvent()

        mockMvc.perform(
            get("/api/events/$eid/lookup?q=김진우").with(authenticatedAs(roles = listOf("EVENT_ADMIN"))),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("EVENT_FORBIDDEN"))
    }

    @Test
    fun `EVENT_STAFF는 명단 CRUD와 달리 통합조회를 정상 호출할 수 있다(관리자 콘솔 전용 아님)`() {
        val eid = createEvent()
        registerGuest(eid, "김진우")

        mockMvc.perform(
            get("/api/events/$eid/lookup?q=김진우")
                .with(authenticatedAs(roles = listOf("EVENT_STAFF"), eventIds = listOf(eid))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(1))
    }

    // ── searchAny — 이름·차량 뒷자리 3상태(P1) ────────────────────────

    @Test
    fun `이름검색은 매칭 1건이면 searchState ONE을 반환한다`() {
        val eid = createEvent()
        registerGuest(eid, "김진우")
        registerGuest(eid, "박서연")

        mockMvc.perform(
            get("/api/events/$eid/lookup?q=김진우")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.meta.searchState").value("ONE"))
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].name").value("김진우"))
    }

    @Test
    fun `이름검색은 중간일치 매칭 2건 이상이면 searchState MANY를 반환한다`() {
        val eid = createEvent()
        registerGuest(eid, "김진우")
        registerGuest(eid, "김진호")

        mockMvc.perform(
            get("/api/events/$eid/lookup?q=김진")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.meta.searchState").value("MANY"))
            .andExpect(jsonPath("$.data.length()").value(2))
    }

    @Test
    fun `매칭 0건이면 searchState NONE과 빈 data를 반환한다`() {
        val eid = createEvent()
        registerGuest(eid, "김진우")

        mockMvc.perform(
            get("/api/events/$eid/lookup?q=없는이름")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.meta.searchState").value("NONE"))
            .andExpect(jsonPath("$.data.length()").value(0))
    }

    @Test
    fun `차량 뒷자리 검색은 이름이 q와 무관한 참석자도 매칭한다`() {
        val eid = createEvent()
        registerGuest(eid, "이도현", plate = "12가3456")

        mockMvc.perform(
            get("/api/events/$eid/lookup?q=3456")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].name").value("이도현"))
            .andExpect(jsonPath("$.data[0].plate").value("12가3456"))
    }

    @Test
    fun `취소된 참석자는 통합조회 결과에서 제외된다`() {
        val eid = createEvent()
        val gid = registerGuest(eid, "정하은")
        mockMvc.perform(
            delete("/api/events/$eid/guests/$gid")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid))),
        ).andExpect(status().isOk)

        mockMvc.perform(
            get("/api/events/$eid/lookup?q=정하은")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.meta.searchState").value("NONE"))
    }

    // ── 좌석+주차 동시 병기(P1, 실 DB read-back) ───────────────────────

    @Test
    fun `좌석과 주차가 모두 매핑된 참석자는 응답에 seatLabel과 parking이 동시에 채워진다`() {
        val eid = createEvent()
        val gid = registerGuest(eid, "최유나", plate = "78다9012")
        assignSeatAndParking(eid, gid, seatLabel = "3번 테이블", plate = "78다9012", slotSig = "지하 2층·B구역·7")

        mockMvc.perform(
            get("/api/events/$eid/lookup?q=최유나")
                .with(authenticatedAs(roles = listOf("EVENT_STAFF"), eventIds = listOf(eid))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].seatLabel").value("3번 테이블"))
            .andExpect(jsonPath("$.data[0].parking.slotSig").value("지하 2층·B구역·7"))
            .andExpect(jsonPath("$.data[0].parking.display").value("지하 2층 B구역 7"))
    }

    // ── seatLabel 실좌석 승격(§12-6) 회귀 — numbering ON + 배정 ────────

    @Test
    fun `numbering ON 그룹에 배정된 참석자는 통합조회 seatLabel이 라벨과 번호로 병기된다`() {
        val eid = createEvent()
        val gid = registerGuest(eid, "김서준")
        val groupResponse = mockMvc.perform(
            post("/api/events/$eid/seat-groups")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"label":"C열","numbering":true}"""),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val groupNo = objectMapper.readTree(groupResponse).get("data").get("groupNo").asInt()
        // numbering ON 그룹의 §12-5 PUT은 그룹 전체 슬롯 세트를 원자 교체한다 — ord는 1..N 연속·
        // 유일해야 하므로 목표 ord(3번)까지의 빈좌석(1·2번)도 함께 제출해야 한다.
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
            get("/api/events/$eid/lookup?q=김서준")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].seatLabel").value("C열 3번"))
    }

    // ── q 파라미터 오분류 방지(P1) ────────────────────────────────────

    @Test
    fun `q 파라미터가 완전히 누락되면 500이 아닌 400 VALIDATION_FAILED를 받는다`() {
        val eid = createEvent()

        mockMvc.perform(
            get("/api/events/$eid/lookup").with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid))),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
    }

    @Test
    fun `q가 빈 문자열이면 400 VALIDATION_FAILED를 받는다`() {
        val eid = createEvent()

        mockMvc.perform(
            get("/api/events/$eid/lookup?q=")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid))),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
    }
}
