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
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 공개(비로그인) 경로 적대적 보안 테스트(Tester 독립 검증) — Developer 통합 테스트
 * (`PublicHubControllerTest`·`PublicOnsiteControllerTest`)가 응답 코드만 확인한 지점을 실 DB
 * 행 단위로 재검증한다: 무효 capability 쓰기 시도가 실제로 DB에 흔적을 남기지 않는지,
 * 타 행사 주차 데이터가 유출되지 않는지.
 *
 * `LookupControllerTest`와 동일 컨벤션 — 전용 등록 API가 없는 좌석·주차 데이터는 `JdbcTemplate`
 * 직접 삽입으로 준비하고, `@Transactional`이 테스트 종료 시 자동 롤백한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PublicSecurityAdversarialTest(
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

    private fun createEvent(name: String = "적대적 테스트 행사"): String {
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

    /** @return (guestId, token) */
    private fun registerGuest(eid: String, name: String): Pair<String, String> {
        val response = mockMvc.perform(
            post("/events/$eid/guests")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"$name"}"""),
        )
            .andExpect(status().isCreated)
            .andReturn().response.contentAsString
        val data = objectMapper.readTree(response).get("data")
        return data.get("id").asText() to data.get("token").asText()
    }

    private fun closeEvent(eid: String) {
        jdbcTemplate.update("UPDATE event SET status = ? WHERE id = ?", "종료", eid)
    }

    private fun guestCountByEvent(eid: String): Int =
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM guest WHERE event_id = ?", Int::class.java, eid) ?: 0

    private fun guestCountGlobal(): Int =
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM guest", Int::class.java) ?: 0

    /**
     * nullable 단일 컬럼 조회 — `JdbcTemplate.queryForObject(sql, Class<T>, args)`(제네릭 반환)는
     * Kotlin이 Java 제네릭 반환 타입에 non-null 어서션을 삽입해 실제 컬럼값이 NULL이면
     * `NullPointerException: queryForObject(...) must not be null`을 던진다(플랫폼 타입 함정 —
     * `guest_id`가 아직 연결 안 된 정상 케이스를 검증해야 하는 이 테스트에서는 회피 필수).
     * `RowMapper` 람다 경유는 이 어서션을 우회한다.
     */
    private fun singleNullableString(sql: String, vararg args: Any): String? =
        jdbcTemplate.query(sql, { rs, _ -> rs.getString(1) }, *args).firstOrNull()

    /** 활성(주차중) 주차기록 직접 삽입, guest 미배정 — 전용 등록 API는 별도 REQ 범위(LookupControllerTest와 동일 컨벤션). */
    private fun insertParkedRecord(eid: String, plate: String, slotSig: String = "z1-01"): String {
        val zoneId = UUID.randomUUID().toString()
        val recordId = UUID.randomUUID().toString()
        jdbcTemplate.update(
            "INSERT INTO parking_zone (id, event_id, start_no, slot_count) VALUES (?, ?, 1, 10)",
            zoneId,
            eid,
        )
        jdbcTemplate.update(
            """
            INSERT INTO parking_record (id, event_id, zone_id, slot_sig, plate, guest_id, registered_by, status)
            VALUES (?, ?, ?, ?, ?, NULL, '요원', '주차중')
            """.trimIndent(),
            recordId,
            eid,
            zoneId,
            slotSig,
            plate,
        )
        return recordId
    }

    // ── 무인증 쓰기 취약점 부재(적대적) — 쓰기 前 거부, DB에 안 남음 ─────────

    @Test
    fun `무효 token으로 사전등록을 시도해도 guest 테이블에 흔적이 남지 않는다`() {
        val eid = createEvent()
        val beforeCount = guestCountByEvent(eid)

        mockMvc.perform(
            post("/public/u/no-such-token-adversarial/prereg-plate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"plate":"99하9999"}"""),
        ).andExpect(status().isNotFound)

        assertEquals(beforeCount, guestCountByEvent(eid), "무효 token 쓰기 시도가 guest 행을 남기면 안 된다")
        val tokenRowCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM guest WHERE token = ?",
            Int::class.java,
            "no-such-token-adversarial",
        ) ?: 0
        assertEquals(0, tokenRowCount, "무효 token 문자열 그대로가 신규 guest.token으로 삽입되면 안 된다")
    }

    @Test
    fun `무효 eventCode 현장등록 시도는 guest 테이블에 삽입 0건이다`() {
        val before = guestCountGlobal()

        mockMvc.perform(
            post("/public/r/${UUID.randomUUID()}")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"적대적 침입 시도"}"""),
        ).andExpect(status().isNotFound)

        assertEquals(before, guestCountGlobal(), "무효 eventCode 쓰기 시도가 guest 행을 남기면 안 된다")
    }

    @Test
    fun `종료된 행사 현장등록 시도는 409 이전에 이미 guest 삽입이 없다`() {
        val eid = createEvent()
        closeEvent(eid)
        val before = guestCountByEvent(eid)

        mockMvc.perform(
            post("/public/r/$eid")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"종료행사 등록시도"}"""),
        ).andExpect(status().isConflict)

        assertEquals(before, guestCountByEvent(eid), "종료 event 게이트는 쓰기 이전에 거부해야 한다(409 이후 삽입 흔적 0)")
    }

    // ── cross-event 격리(적대적) — 타 행사 주차기록 유출 방지 ─────────────────

    @Test
    fun `타 행사의 활성 주차기록과 같은 차량번호로 사전등록해도 연결되지 않는다(cross-event 유출 방지)`() {
        val eidA = createEvent("행사 A")
        val eidB = createEvent("행사 B")
        val sharedPlate = "77가7777"
        val recordIdB = insertParkedRecord(eidB, sharedPlate)
        val (_, tokenA) = registerGuest(eidA, "적대적 시나리오 참석자")

        mockMvc.perform(
            post("/public/u/$tokenA/prereg-plate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"plate":"$sharedPlate"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.plate").value(sharedPlate))

        // event A guest는 plate만 백필되고 방문 전이는 없어야 한다(같은 event 안에는 매칭 기록이 없으므로).
        mockMvc.perform(get("/public/u/$tokenA"))
            .andExpect(jsonPath("$.data.guest.status").value("대기"))

        // event B의 주차기록은 여전히 guest_id가 비어 있어야 한다 — cross-event로 연결되면 안 된다.
        val linkedGuestId = singleNullableString("SELECT guest_id FROM parking_record WHERE id = ?", recordIdB)
        assertNull(linkedGuestId, "타 행사(event B) 주차기록에 event A guest가 연결되면 안 된다")
    }

    @Test
    fun `한 guest의 사전등록은 같은 행사의 다른 guest 데이터에 영향을 주지 않는다(scope=token 격리)`() {
        val eid = createEvent()
        val (_, tokenA) = registerGuest(eid, "참석자A")
        val (guestIdB, _) = registerGuest(eid, "참석자B")

        mockMvc.perform(
            post("/public/u/$tokenA/prereg-plate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"plate":"11나1111"}"""),
        ).andExpect(status().isOk)

        // B는 A의 사전등록과 무관하게 plate가 여전히 비어 있어야 한다(scope=token — 요청 경로에
        // 지정된 token의 guest 단건만 변경돼야 하고, 같은 event의 다른 guest는 절대 영향받지 않는다).
        val plateB = singleNullableString("SELECT plate FROM guest WHERE id = ?", guestIdB)
        assertNull(plateB, "A의 사전등록이 같은 event의 다른 guest(B)의 plate를 건드리면 안 된다")
    }
}
