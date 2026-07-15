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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 주차 기록 API(§6-5~6-8) 통합 테스트 — 등록 코어 무결성 3-5 승계 5케이스(02-architect §4-1)
 * · plateTail 뒷자리 검색(§6-5) · parking→guest 매핑(3-7) · cross-tenant 격리를 실 MariaDB로
 * 검증한다.
 *
 * `GuestControllerTest`/`CheckinControllerTest`와 동일 컨벤션 — `.with(jwt())` 직접 주입,
 * `@Transactional` 자동 롤백. 동시성(active_key UNIQUE→409)은 물리 트랜잭션 격리가 필요해
 * `ParkingRecordConcurrencyIntegrationTest`(module-application)가 별도 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ParkingRecordControllerTest(
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

    private fun createEvent(name: String = "주차 기록 테스트 행사"): String {
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

    /** @return zoneId — part1="지하 2층", part2="A구역", startNo=1, slotCount=12 고정. */
    private fun createZone(eid: String): String {
        val response = mockMvc.perform(
            post("/events/$eid/parking-zones")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"part1":"지하 2층","part2":"A구역","startNo":1,"slotCount":12}"""),
        )
            .andExpect(status().isCreated)
            .andReturn().response.getContentAsString(StandardCharsets.UTF_8)
        return objectMapper.readTree(response).get("data").get("id").asText()
    }

    /** 파생 규칙(ParkingSlot.slotSig) 재현 — createZone 고정 part1·part2 기준. */
    private fun slotSig(slotNo: Int) = "지하 2층·A구역·$slotNo"

    private fun registerGuest(eid: String, name: String, phone: String? = null, plate: String? = null): String {
        val body = objectMapper.writeValueAsString(mapOf("name" to name, "phone" to phone, "plate" to plate))
        val response = mockMvc.perform(
            post("/events/$eid/guests")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isCreated)
            .andReturn().response.getContentAsString(StandardCharsets.UTF_8)
        return objectMapper.readTree(response).get("data").get("id").asText()
    }

    private fun registerRecordRaw(
        eid: String,
        zoneId: String,
        slotSig: String,
        plate: String,
        phone: String? = null,
        vipName: String? = null,
        registeredBy: String = "요원",
        eventIds: List<String> = listOf(eid),
    ) = mockMvc.perform(
        post("/events/$eid/parking-records")
            .with(authenticatedAs(roles = listOf("EVENT_STAFF"), eventIds = eventIds))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                objectMapper.writeValueAsString(
                    mapOf(
                        "slotSig" to slotSig, "zoneId" to zoneId, "plate" to plate,
                        "phone" to phone, "vipName" to vipName, "registeredBy" to registeredBy,
                    ),
                ),
            ),
    )

    // ── 승계 무결성 5케이스(02-architect §4-1) ──────────────────────

    @Test
    fun `케이스E 빈 자리·미주차 차량이면 201 PARKED로 신규 삽입한다`() {
        val eid = createEvent()
        val zid = createZone(eid)

        registerRecordRaw(eid, zid, slotSig(1), "12가3456")
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.result").value("PARKED"))
            .andExpect(jsonPath("$.data.record.status").value("주차중"))
            .andExpect(jsonPath("$.data.record.reviewNeeded").value(false))
            .andExpect(jsonPath("$.data.supersededRecord").doesNotExist())
    }

    @Test
    fun `케이스A 본인이 같은 자리를 재등록하면 200 RE_REGISTERED로 위치만 갱신한다`() {
        val eid = createEvent()
        val zid = createZone(eid)
        val first = registerRecordRaw(eid, zid, slotSig(1), "12가3456")
            .andExpect(status().isCreated)
            .andReturn().response.getContentAsString(StandardCharsets.UTF_8)
        val firstId = objectMapper.readTree(first).get("data").get("record").get("id").asText()

        registerRecordRaw(eid, zid, slotSig(1), "12가3456")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.result").value("RE_REGISTERED"))
            .andExpect(jsonPath("$.data.record.id").value(firstId))
            .andExpect(jsonPath("$.data.supersededRecord").doesNotExist())

        // 2건 생성 금지 — 같은 자리 목록에 1건만 존재해야 한다.
        val list = mockMvc.perform(
            get("/events/$eid/parking-records")
                .with(authenticatedAs(roles = listOf("EVENT_STAFF"), eventIds = listOf(eid)))
                .param("zoneId", zid),
        ).andExpect(status().isOk).andReturn().response.getContentAsString(StandardCharsets.UTF_8)
        assertEquals(1, objectMapper.readTree(list).get("data").size())
    }

    @Test
    fun `케이스B 점유 자리에 타 차량을 등록하면 대상을 출차시키고 200 SUPERSEDED로 신규 삽입한다`() {
        val eid = createEvent()
        val zid = createZone(eid)
        registerRecordRaw(eid, zid, slotSig(1), "99나9999").andExpect(status().isCreated)

        registerRecordRaw(eid, zid, slotSig(1), "12가3456")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.result").value("SUPERSEDED"))
            .andExpect(jsonPath("$.data.record.plate").value("12가3456"))
            .andExpect(jsonPath("$.data.record.reviewNeeded").value(true))
            .andExpect(jsonPath("$.data.supersededRecord.status").value("출차"))
    }

    @Test
    fun `케이스C 점유 자리 승계와 동시에 내 차량의 기존 기록을 이동시킨다(중복 생성 없음)`() {
        val eid = createEvent()
        val zid = createZone(eid)
        registerRecordRaw(eid, zid, slotSig(1), "99나9999").andExpect(status().isCreated) // 대상 점유자
        registerRecordRaw(eid, zid, slotSig(9), "12가3456").andExpect(status().isCreated) // 내 차량 타-자리

        registerRecordRaw(eid, zid, slotSig(1), "12가3456")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.result").value("SUPERSEDED"))
            .andExpect(jsonPath("$.data.record.slotSig").value(slotSig(1)))
            .andExpect(jsonPath("$.data.record.reviewNeeded").value(true))
            .andExpect(jsonPath("$.data.supersededRecord.status").value("출차"))

        // 내 차량은 이동만 — 전체 목록에서 plate=12가3456 활성 기록은 1건이어야 한다(신규 insert 없음).
        val list = mockMvc.perform(
            get("/events/$eid/parking-records")
                .with(authenticatedAs(roles = listOf("EVENT_STAFF"), eventIds = listOf(eid)))
                .param("status", "주차중"),
        ).andExpect(status().isOk).andReturn().response.getContentAsString(StandardCharsets.UTF_8)
        val mine = objectMapper.readTree(list).get("data").filter { it.get("plate").asText() == "12가3456" }
        assertEquals(1, mine.size)
        assertEquals(slotSig(1), mine.first().get("slotSig").asText())
    }

    @Test
    fun `케이스D 빈 자리로 내 차량을 이동시키면 200 RE_REGISTERED로 기존 기록만 갱신한다(신규 생성 없음)`() {
        val eid = createEvent()
        val zid = createZone(eid)
        registerRecordRaw(eid, zid, slotSig(9), "12가3456").andExpect(status().isCreated)

        registerRecordRaw(eid, zid, slotSig(1), "12가3456")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.result").value("RE_REGISTERED"))
            .andExpect(jsonPath("$.data.record.slotSig").value(slotSig(1)))
            .andExpect(jsonPath("$.data.record.reviewNeeded").value(false))
            .andExpect(jsonPath("$.data.supersededRecord").doesNotExist())

        val list = mockMvc.perform(
            get("/events/$eid/parking-records")
                .with(authenticatedAs(roles = listOf("EVENT_STAFF"), eventIds = listOf(eid)))
                .param("status", "주차중"),
        ).andExpect(status().isOk).andReturn().response.getContentAsString(StandardCharsets.UTF_8)
        assertEquals(1, objectMapper.readTree(list).get("data").size())
    }

    @Test
    fun `register는 registeredBy가 셀프·요원이 아니면 400 VALIDATION_FAILED를 받는다`() {
        val eid = createEvent()
        val zid = createZone(eid)

        registerRecordRaw(eid, zid, slotSig(1), "12가3456", registeredBy = "대행")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
    }

    // ── cross-tenant 격리(P1) ────────────────────────────────────────

    @Test
    fun `EVENT_STAFF가 담당 아닌 행사의 주차 기록 목록을 조회하면 403 EVENT_FORBIDDEN을 받는다`() {
        val eid = createEvent()

        mockMvc.perform(
            get("/events/$eid/parking-records")
                .with(authenticatedAs(roles = listOf("EVENT_STAFF"), eventIds = listOf("다른-행사-id"))),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("EVENT_FORBIDDEN"))
    }

    @Test
    fun `EVENT_STAFF가 담당 아닌 행사에 주차 기록을 등록하면 403 EVENT_FORBIDDEN을 받는다`() {
        val eid = createEvent()
        val zid = createZone(eid)

        registerRecordRaw(eid, zid, slotSig(1), "12가3456", eventIds = listOf("다른-행사-id"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("EVENT_FORBIDDEN"))
    }

    @Test
    fun `EVENT_STAFF가 담당 아닌 행사에서 출차·리뷰해제를 시도하면 403 EVENT_FORBIDDEN을 받는다`() {
        val eid = createEvent()
        val zid = createZone(eid)
        val created = registerRecordRaw(eid, zid, slotSig(1), "12가3456")
            .andExpect(status().isCreated).andReturn().response.getContentAsString(StandardCharsets.UTF_8)
        val id = objectMapper.readTree(created).get("data").get("record").get("id").asText()

        mockMvc.perform(
            post("/events/$eid/parking-records/$id/checkout")
                .with(authenticatedAs(roles = listOf("EVENT_STAFF"), eventIds = listOf("다른-행사-id"))),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("EVENT_FORBIDDEN"))

        mockMvc.perform(
            post("/events/$eid/parking-records/$id/review-clear")
                .with(authenticatedAs(roles = listOf("EVENT_STAFF"), eventIds = listOf("다른-행사-id"))),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("EVENT_FORBIDDEN"))
    }

    // ── plateTail 뒷자리 검색(§6-5, P2) ───────────────────────────────

    @Test
    fun `list는 plateTail로 뒷자리 검색을 3상태(1건·다건·없음)로 지원한다`() {
        val eid = createEvent()
        val zid = createZone(eid)
        registerRecordRaw(eid, zid, slotSig(1), "12가1234").andExpect(status().isCreated)
        registerRecordRaw(eid, zid, slotSig(2), "34나1234").andExpect(status().isCreated)
        registerRecordRaw(eid, zid, slotSig(3), "56다5678").andExpect(status().isCreated)

        fun search(tail: String) = mockMvc.perform(
            get("/events/$eid/parking-records")
                .with(authenticatedAs(roles = listOf("EVENT_STAFF"), eventIds = listOf(eid)))
                .param("plateTail", tail),
        ).andExpect(status().isOk).andReturn().response.getContentAsString(StandardCharsets.UTF_8)

        assertEquals(2, objectMapper.readTree(search("1234")).get("data").size()) // 다건
        assertEquals(1, objectMapper.readTree(search("5678")).get("data").size()) // 1건
        assertTrue(objectMapper.readTree(search("0000")).get("data").isEmpty)     // 없음
    }

    // ── parking→guest 매핑(3-7, P3) ───────────────────────────────────

    private fun guestStatus(eid: String, gid: String): String {
        val list = mockMvc.perform(
            get("/events/$eid/guests")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid))),
        ).andExpect(status().isOk).andReturn().response.getContentAsString(StandardCharsets.UTF_8)
        val item = objectMapper.readTree(list).get("data").first { it.get("id").asText() == gid }
        return item.get("status").asText()
    }

    @Test
    fun `register는 plate 완전일치 참석자를 phone 매칭 대상보다 우선한다`() {
        val eid = createEvent()
        val zid = createZone(eid)
        val phoneOnlyGuest = registerGuest(eid, "김민준", phone = "010-1111-2222") // plate 없음
        val plateGuest = registerGuest(eid, "박서연", plate = "34나1234") // plate 완전일치 대상

        // 요청 phone이 phoneOnlyGuest와도 일치하지만, plate 완전일치(plateGuest)가 우선해야 한다.
        // record.guestId는 매핑 이전에 구성된 스냅샷이라 응답에는 반영되지 않는다(ParkingRecordService.
        // mapGuestForRecord가 recordPort.linkGuest로 DB만 갱신 — 링크 결과의 단일 진실 소스는 mapping
        // 필드다. Developer 단위 테스트도 동일 전제로 record.guestId를 검증하지 않는다).
        registerRecordRaw(eid, zid, slotSig(1), "34나1234", phone = "010-1111-2222")
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.mapping.matched").value(true))
            .andExpect(jsonPath("$.data.mapping.guestId").value(plateGuest))
            .andExpect(jsonPath("$.data.mapping.guestStatus").value("방문"))

        assertEquals("방문", guestStatus(eid, plateGuest))
        // phone만 일치하는 게스트는 plate 우선 매칭에 밀려 무변경(대기)이어야 한다.
        assertEquals("대기", guestStatus(eid, phoneOnlyGuest))
    }

    @Test
    fun `register는 plate 매칭 실패 시 phone 보조 매칭으로 대기를 방문으로 전이한다`() {
        val eid = createEvent()
        val zid = createZone(eid)
        val gid = registerGuest(eid, "이도현", phone = "010-2222-3333")

        registerRecordRaw(eid, zid, slotSig(1), "77하7777", phone = "010-2222-3333")
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.mapping.matched").value(true))
            .andExpect(jsonPath("$.data.mapping.guestId").value(gid))

        // 매칭 성공 시 plate가 비어 있던 게스트는 백필된다(3-7).
        val list = mockMvc.perform(
            get("/events/$eid/guests")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid))),
        ).andExpect(status().isOk).andReturn().response.getContentAsString(StandardCharsets.UTF_8)
        val item = objectMapper.readTree(list).get("data").first { it.get("id").asText() == gid }
        assertEquals("77하7777", item.get("plate").asText())
    }

    @Test
    fun `register는 매칭되는 참석자가 없으면 mapping matched가 false이고 guestId는 null이다`() {
        val eid = createEvent()
        val zid = createZone(eid)

        registerRecordRaw(eid, zid, slotSig(1), "88허8888")
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.mapping.matched").value(false))
            .andExpect(jsonPath("$.data.record.guestId").doesNotExist())
    }

    // ── checkout(§6-7)·review-clear(§6-8) ─────────────────────────────

    @Test
    fun `checkout은 주차중 기록을 출차로 전환하고 재호출해도 멱등하게 출차를 유지한다`() {
        val eid = createEvent()
        val zid = createZone(eid)
        val created = registerRecordRaw(eid, zid, slotSig(1), "12가3456")
            .andExpect(status().isCreated).andReturn().response.getContentAsString(StandardCharsets.UTF_8)
        val id = objectMapper.readTree(created).get("data").get("record").get("id").asText()

        mockMvc.perform(
            post("/events/$eid/parking-records/$id/checkout")
                .with(authenticatedAs(roles = listOf("EVENT_STAFF"), eventIds = listOf(eid))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("출차"))

        mockMvc.perform(
            post("/events/$eid/parking-records/$id/checkout")
                .with(authenticatedAs(roles = listOf("EVENT_STAFF"), eventIds = listOf(eid))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("출차"))

        // 출차 후 같은 자리는 즉시 재입차 가능해야 한다(active_key NULL화 실증).
        registerRecordRaw(eid, zid, slotSig(1), "99나9999").andExpect(status().isCreated)
    }

    @Test
    fun `review-clear는 상태 변경 없이 승계 확인 배지만 해제한다`() {
        val eid = createEvent()
        val zid = createZone(eid)
        registerRecordRaw(eid, zid, slotSig(1), "99나9999").andExpect(status().isCreated)
        val superseded = registerRecordRaw(eid, zid, slotSig(1), "12가3456")
            .andExpect(status().isOk).andReturn().response.getContentAsString(StandardCharsets.UTF_8)
        val id = objectMapper.readTree(superseded).get("data").get("record").get("id").asText()

        mockMvc.perform(
            post("/events/$eid/parking-records/$id/review-clear")
                .with(authenticatedAs(roles = listOf("EVENT_STAFF"), eventIds = listOf(eid))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.reviewNeeded").value(false))
    }
}
