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
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 문자 도메인 API(§7) 통합 테스트 — gid 단위 치환(이름 매칭 금지)·QR 링크 단일 스킴·발송
 * 게이트 집합연산·발송 오케스트레이션·재발송·이력·cross-tenant 격리·GuestService.cancelGuest
 * 연동 삭제(M2)를 실 MariaDB로 검증한다.
 *
 * Developer 단위 테스트(`SmsRendererTest`·`SmsServiceTest`·`GuestServiceTest`)는 각 포트를 mock으로
 * 대체해 오케스트레이션 분기(게이트 판정·confirm 검증·부분 실패 흡수)를 검증했다 — 이 테스트는
 * mock이 우회하는 실 SQL 경로(`SmsTemplateMapper` upsert·`SmsLogMapper` 게이트 집계·deleteByGuest
 * hard delete)와 컨트롤러 인가·cross-tenant 표면을 재검증한다
 * (`~/.claude/rules-on-demand/anti-rationalization.md` Tester 절 — mock은 mapper XML 실 SQL을
 * 우회하므로 DB결합 로직은 실 DB 통합 테스트 의무).
 *
 * 부분 발송 실패(옵션 A 부분성 모델)는 스텁이 항상 성공을 반환하므로 통합 테스트로 재현할 수
 * 없다 — `SmsServiceTest`의 mock 예외 주입 테스트가 그 분기를 담당하고, 여기서는 성공 경로의
 * `sms_log` INSERT 컬럼값(body_snapshot·name_snapshot)만 실 DB로 확인한다.
 *
 * `StatsControllerTest`·`GuestControllerTest`와 동일 컨벤션 — `.with(jwt())` 직접 주입,
 * `@Transactional` 자동 롤백, `event-base-url` 기본값(`http://localhost:3000`, application.yml)을
 * 그대로 사용한다(`PublicHubControllerTest`가 같은 기본값으로 QR URL을 검증하는 선례와 정합).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SmsControllerTest(
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

    private fun createEvent(name: String = "문자 테스트 행사", place: String? = null): String {
        val body = objectMapper.writeValueAsString(mapOf("name" to name, "place" to place))
        val response = mockMvc.perform(
            post("/api/events")
                .with(authenticatedAs(roles = listOf("SYSTEM_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isCreated)
            .andReturn().response.getContentAsString(StandardCharsets.UTF_8)
        return objectMapper.readTree(response).get("data").get("id").asText()
    }

    private fun registerGuest(eid: String, name: String, org: String? = null, phone: String? = "010-1234-5678"): String {
        val body = objectMapper.writeValueAsString(mapOf("name" to name, "org" to org, "phone" to phone))
        val response = mockMvc.perform(
            post("/api/events/$eid/guests")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isCreated)
            .andReturn().response.getContentAsString(StandardCharsets.UTF_8)
        return objectMapper.readTree(response).get("data").get("id").asText()
    }

    private fun guestToken(gid: String): String =
        jdbcTemplate.queryForObject("SELECT token FROM guest WHERE id = ?", String::class.java, gid)!!

    private fun cancelGuest(eid: String, gid: String, deleteSmsLog: Boolean = false) {
        mockMvc.perform(
            delete("/api/events/$eid/guests/$gid")
                .param("deleteSmsLog", deleteSmsLog.toString())
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid))),
        ).andExpect(status().isOk)
    }

    private fun upsertTemplate(eid: String, body: String, roles: List<String> = listOf("EVENT_ADMIN"), eventIds: List<String> = listOf(eid)) =
        mockMvc.perform(
            put("/api/events/$eid/sms-template")
                .with(authenticatedAs(roles = roles, eventIds = eventIds))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("body" to body))),
        )

    private fun getTemplate(eid: String, roles: List<String> = listOf("EVENT_ADMIN"), eventIds: List<String> = listOf(eid)) =
        mockMvc.perform(
            get("/api/events/$eid/sms-template").with(authenticatedAs(roles = roles, eventIds = eventIds)),
        )

    private fun preview(eid: String, guestId: String) =
        mockMvc.perform(
            post("/api/events/$eid/sms-template/preview")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("guestId" to guestId))),
        )

    private fun gate(eid: String, excludeAlreadySent: Boolean = true, roles: List<String> = listOf("EVENT_ADMIN"), eventIds: List<String> = listOf(eid)) =
        mockMvc.perform(
            post("/api/events/$eid/sms/send/gate")
                .with(authenticatedAs(roles = roles, eventIds = eventIds))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("excludeAlreadySent" to excludeAlreadySent))),
        )

    private fun send(eid: String, excludeAlreadySent: Boolean = true, confirm: Boolean, roles: List<String> = listOf("EVENT_ADMIN"), eventIds: List<String> = listOf(eid)) =
        mockMvc.perform(
            post("/api/events/$eid/sms/send")
                .with(authenticatedAs(roles = roles, eventIds = eventIds))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("excludeAlreadySent" to excludeAlreadySent, "confirm" to confirm))),
        )

    private fun resend(eid: String, guestId: String, confirm: Boolean) =
        mockMvc.perform(
            post("/api/events/$eid/sms/resend")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("guestId" to guestId, "confirm" to confirm))),
        )

    private fun listLog(eid: String, roles: List<String> = listOf("EVENT_ADMIN"), eventIds: List<String> = listOf(eid)) =
        mockMvc.perform(
            get("/api/events/$eid/sms-log").with(authenticatedAs(roles = roles, eventIds = eventIds)),
        )

    private fun smsLogCount(eid: String): Int =
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sms_log WHERE event_id = ?", Int::class.java, eid)!!

    private val defaultBody = "[\$참석자]님, [\$행사명]에 초대합니다. 소속: [\$소속] / 장소: [\$장소] / 일시: [\$일시] / 링크: [\$QR링크]"

    // ── TC-SMS-016: 템플릿 미설정 초기 상태(P3) ──────────────────────

    @Test
    fun `템플릿을 설정하기 전에는 빈 본문과 6종 변수 목록을 반환한다`() {
        val eid = createEvent()

        getTemplate(eid)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.body").value(""))
            .andExpect(jsonPath("$.data.updatedAt").doesNotExist())
            .andExpect(jsonPath("$.data.variables.length()").value(6))
    }

    // ── TC-SMS-008: 템플릿 upsert — event_id UNIQUE(P2·M5) ───────────

    @Test
    fun `PUT sms-template은 event_id UNIQUE upsert로 동일 행사에 단일 행만 유지하고 id를 보존한다`() {
        val eid = createEvent()

        upsertTemplate(eid, "첫 본문 [\$참석자]").andExpect(status().isOk)
        val firstId = jdbcTemplate.queryForObject("SELECT id FROM sms_template WHERE event_id = ?", String::class.java, eid)

        upsertTemplate(eid, "두번째 본문 [\$참석자]")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.body").value("두번째 본문 [\$참석자]"))

        val rowCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sms_template WHERE event_id = ?", Int::class.java, eid)
        val secondId = jdbcTemplate.queryForObject("SELECT id FROM sms_template WHERE event_id = ?", String::class.java, eid)

        assertEquals(1, rowCount, "event_id UNIQUE — upsert 반복해도 행 1건만 유지되어야 한다")
        assertEquals(firstId, secondId, "충돌(기존 행) 시 id는 보존되고 body만 갱신되어야 한다")
    }

    // ── TC-SMS-001: gid 단위 치환 — 동명이인 실증(P1) ─────────────────

    @Test
    fun `preview는 동명이인이어도 gid로 정확히 해당 참석자 정보만 치환한다(이름 매칭 금지 실증)`() {
        val eid = createEvent()
        upsertTemplate(eid, defaultBody)
        val gid1 = registerGuest(eid, "김민준", org = "가그룹", phone = "010-1111-0001")
        val gid2 = registerGuest(eid, "김민준", org = "나그룹", phone = "010-2222-0002")
        val token1 = guestToken(gid1)
        val token2 = guestToken(gid2)

        val rendered1 = objectMapper.readTree(
            preview(eid, gid1).andExpect(status().isOk).andReturn().response.getContentAsString(StandardCharsets.UTF_8),
        ).get("data").get("rendered").asText()
        val rendered2 = objectMapper.readTree(
            preview(eid, gid2).andExpect(status().isOk).andReturn().response.getContentAsString(StandardCharsets.UTF_8),
        ).get("data").get("rendered").asText()

        assertTrue(rendered1.contains("가그룹"), "gid1 결과에는 gid1 소속(가그룹)만 나와야 한다")
        assertFalse(rendered1.contains("나그룹"), "이름이 같아도 gid2 소속(나그룹)이 섞이면 안 된다")
        assertTrue(rendered1.contains("http://localhost:3000/u/$token1"))
        assertFalse(rendered1.contains(token2), "gid1 결과에 gid2 토큰이 섞이면 안 된다")

        assertTrue(rendered2.contains("나그룹"))
        assertFalse(rendered2.contains("가그룹"))
        assertTrue(rendered2.contains("http://localhost:3000/u/$token2"))
        assertFalse(rendered2.contains(token1))
    }

    // ── TC-SMS-002: QR링크 단일 스킴(P1) ─────────────────────────────

    @Test
    fun `QR링크 토큰은 이중 스킴 없이 단일 스킴 풀 URL로 치환된다`() {
        val eid = createEvent()
        upsertTemplate(eid, "확인: [\$QR링크]")
        val gid = registerGuest(eid, "정하은")
        val token = guestToken(gid)

        val rendered = objectMapper.readTree(
            preview(eid, gid).andExpect(status().isOk).andReturn().response.getContentAsString(StandardCharsets.UTF_8),
        ).get("data").get("rendered").asText()

        assertEquals("확인: http://localhost:3000/u/$token", rendered)
        assertFalse(rendered.contains("http://http://"), "이중 스킴이 발생하면 안 된다")
    }

    // ── TC-SMS-015: null 필드 빈 문자열 치환(P3) ─────────────────────

    @Test
    fun `소속이 없는 참석자는 소속 토큰이 빈 문자열로 치환된다`() {
        val eid = createEvent()
        upsertTemplate(eid, "소속:[[\$소속]]")
        val gid = registerGuest(eid, "박서연", org = null)

        val rendered = objectMapper.readTree(
            preview(eid, gid).andExpect(status().isOk).andReturn().response.getContentAsString(StandardCharsets.UTF_8),
        ).get("data").get("rendered").asText()

        assertEquals("소속:[]", rendered)
    }

    // ── TC-SMS-003·004: 발송 게이트 집합연산(P1·M5) ───────────────────

    @Test
    fun `gate는 전화번호 누락 참석자를 blocked로 분류하고 candidates에서 제외한다`() {
        val eid = createEvent()
        upsertTemplate(eid, defaultBody)
        registerGuest(eid, "정상1", phone = "010-1111-1111")
        registerGuest(eid, "정상2", phone = "010-2222-2222")
        registerGuest(eid, "전화누락", phone = null)

        gate(eid, excludeAlreadySent = true)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.candidates").value(2))
            .andExpect(jsonPath("$.data.blocked.length()").value(1))
            .andExpect(jsonPath("$.data.blocked[0].missing[0]").value("전화번호"))
            .andExpect(jsonPath("$.data.canSend").value(false))
    }

    @Test
    fun `gate는 excludeAlreadySent=true면 이미 발송한 gid를 후보에서 제외하되 alreadySent는 후보 전체 기준으로 센다`() {
        val eid = createEvent()
        upsertTemplate(eid, defaultBody)
        registerGuest(eid, "발송대상", phone = "010-1111-1111")

        // 이 시점엔 "발송대상"만 존재 — send는 유효 대상 전원에게 나가므로 먼저 보내고
        // 나서 "미발송대상"을 등록해야 "이미 보낸 1명·안 보낸 1명" 구도가 만들어진다.
        send(eid, excludeAlreadySent = true, confirm = true).andExpect(status().isOk)
        registerGuest(eid, "미발송대상", phone = "010-2222-2222")

        gate(eid, excludeAlreadySent = true)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.candidates").value(1))
            .andExpect(jsonPath("$.data.alreadySent").value(1))
            .andExpect(jsonPath("$.data.canSend").value(true))

        gate(eid, excludeAlreadySent = false)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.candidates").value(2))
            .andExpect(jsonPath("$.data.alreadySent").value(1))
    }

    // ── TC-SMS-005·006·007: 발송 오케스트레이션(P1·M4·M6) ─────────────

    @Test
    fun `send는 confirm=false면 400을 반환한다`() {
        val eid = createEvent()
        upsertTemplate(eid, defaultBody)
        registerGuest(eid, "대상자", phone = "010-1111-1111")

        send(eid, confirm = false).andExpect(status().isBadRequest)
    }

    @Test
    fun `send는 게이트에 누락자가 있으면 confirm=true여도 409 SMS_SEND_BLOCKED를 반환한다`() {
        val eid = createEvent()
        upsertTemplate(eid, defaultBody)
        registerGuest(eid, "전화누락", phone = null)

        send(eid, confirm = true)
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.code").value("SMS_SEND_BLOCKED"))

        assertEquals(0, smsLogCount(eid), "차단된 발송 시도는 sms_log에 기록되면 안 된다")
    }

    @Test
    fun `send는 유효 대상 전원에게 발송하고 sms_log에 발송시점 스냅샷을 기록한다`() {
        val eid = createEvent()
        upsertTemplate(eid, defaultBody)
        val gid = registerGuest(eid, "김진우", org = "모리메이커", phone = "010-9999-8888")

        send(eid, confirm = true)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.sent").value(1))
            .andExpect(jsonPath("$.data.failed").value(0))
            .andExpect(jsonPath("$.data.results[0].status").value("성공"))

        val row = jdbcTemplate.queryForMap(
            "SELECT name_snapshot, body_snapshot, status, phone FROM sms_log WHERE event_id = ? AND guest_id = ?",
            eid, gid,
        )
        assertEquals("김진우(모리메이커)", row["name_snapshot"])
        assertEquals("성공", row["status"])
        assertEquals("010-9999-8888", row["phone"])
        val bodySnapshot = row["body_snapshot"] as String
        assertTrue(bodySnapshot.contains("김진우님"))
        assertTrue(bodySnapshot.contains("모리메이커"))
    }

    // ── TC-SMS-009: 재발송(P2·M6) ─────────────────────────────────

    @Test
    fun `resend는 이미 발송 이력이 있어도 게이트 확인 없이 신규 로그를 추가한다`() {
        val eid = createEvent()
        upsertTemplate(eid, defaultBody)
        val gid = registerGuest(eid, "이도현", phone = "010-3333-4444")

        send(eid, confirm = true).andExpect(status().isOk)
        assertEquals(1, smsLogCount(eid))

        resend(eid, gid, confirm = true)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("성공"))

        assertEquals(2, smsLogCount(eid), "재발송은 기존 이력을 대체하지 않고 신규 행을 추가해야 한다")
    }

    @Test
    fun `resend는 confirm이 false면 400을 반환한다`() {
        val eid = createEvent()
        upsertTemplate(eid, defaultBody)
        val gid = registerGuest(eid, "재발송대상", phone = "010-3333-4444")

        resend(eid, gid, confirm = false).andExpect(status().isBadRequest)
    }

    // ── TC-SMS-010: 이력 조회(P2) ────────────────────────────────

    @Test
    fun `sms-log 조회는 발송 이력을 스냅샷 shape 그대로 최신순으로 반환한다`() {
        val eid = createEvent()
        upsertTemplate(eid, defaultBody)
        val gid = registerGuest(eid, "정예나", org = "본사", phone = "010-5555-6666")

        send(eid, confirm = true).andExpect(status().isOk)
        resend(eid, gid, confirm = true).andExpect(status().isOk)

        listLog(eid)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].guestId").value(gid))
            .andExpect(jsonPath("$.data[0].nameSnapshot").value("정예나(본사)"))
            .andExpect(jsonPath("$.data[0].status").value("성공"))
            .andExpect(jsonPath("$.data[0].bodySnapshot").exists())
            .andExpect(jsonPath("$.data[0].sentAt").exists())
    }

    // ── TC-SMS-011: cross-tenant 격리(P1) ─────────────────────────

    @Test
    fun `EVENT_ADMIN이 담당 아닌 행사의 문자 템플릿을 조회하면 403 EVENT_FORBIDDEN을 받는다`() {
        val eid = createEvent()

        getTemplate(eid, eventIds = listOf("다른-행사-id"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("EVENT_FORBIDDEN"))
    }

    @Test
    fun `EVENT_ADMIN이 담당 아닌 행사에 발송을 시도하면 403 EVENT_FORBIDDEN을 받는다`() {
        val eid = createEvent()

        send(eid, confirm = true, eventIds = listOf("다른-행사-id"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("EVENT_FORBIDDEN"))
    }

    @Test
    fun `EVENT_ADMIN이 담당 아닌 행사의 발송 이력을 조회하면 403 EVENT_FORBIDDEN을 받는다`() {
        val eid = createEvent()

        listLog(eid, eventIds = listOf("다른-행사-id"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("EVENT_FORBIDDEN"))
    }

    @Test
    fun `event_ids 클레임이 없는 EVENT_ADMIN은 fail-CLOSED로 문자 템플릿 조회가 거부된다`() {
        val eid = createEvent()

        getTemplate(eid, eventIds = emptyList())
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("EVENT_FORBIDDEN"))
    }

    // ── TC-SMS-012: 실행자 권한 차단(P2) ───────────────────────────

    @Test
    fun `EVENT_STAFF는 문자 템플릿 조회에서 403 ROLE_FORBIDDEN을 받는다(관리자 콘솔 전용)`() {
        val eid = createEvent()

        getTemplate(eid, roles = listOf("EVENT_STAFF"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("ROLE_FORBIDDEN"))
    }

    // ── TC-SMS-013·014: GuestService.cancelGuest 연동 삭제 회귀(P1 최우선·M2) ──

    @Test
    fun `cancelGuest는 deleteSmsLog=true면 sms_log 레코드를 실제로 삭제한다(DB 실증)`() {
        val eid = createEvent()
        upsertTemplate(eid, defaultBody)
        val gid = registerGuest(eid, "취소예정1", phone = "010-7777-1111")
        send(eid, confirm = true).andExpect(status().isOk)
        assertEquals(1, smsLogCount(eid))

        cancelGuest(eid, gid, deleteSmsLog = true)

        assertEquals(0, smsLogCount(eid), "deleteSmsLog=true면 해당 참석자의 sms_log가 실제로 삭제되어야 한다")
    }

    @Test
    fun `cancelGuest는 deleteSmsLog=false면 sms_log 레코드를 보존한다(byte-identical 경로)`() {
        val eid = createEvent()
        upsertTemplate(eid, defaultBody)
        val gid = registerGuest(eid, "취소예정2", phone = "010-7777-2222")
        send(eid, confirm = true).andExpect(status().isOk)
        assertEquals(1, smsLogCount(eid))

        cancelGuest(eid, gid, deleteSmsLog = false)

        assertEquals(1, smsLogCount(eid), "deleteSmsLog=false(디폴트)는 발송 이력을 건드리면 안 된다")
    }

    @Test
    fun `cancelGuest는 deleteSmsLog 파라미터 없이 호출하면 디폴트 false로 동작해 sms_log를 보존한다`() {
        val eid = createEvent()
        upsertTemplate(eid, defaultBody)
        val gid = registerGuest(eid, "취소예정3", phone = "010-7777-3333")
        send(eid, confirm = true).andExpect(status().isOk)

        mockMvc.perform(
            delete("/api/events/$eid/guests/$gid")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid))),
        ).andExpect(status().isOk)

        assertEquals(1, smsLogCount(eid), "쿼리 파라미터 생략 시 기존 GuestControllerTest 회귀와 동일하게 디폴트 false여야 한다")
    }
}
