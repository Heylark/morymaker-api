package kr.co.morymaker.api.web

import com.fasterxml.jackson.databind.ObjectMapper
import kr.co.morymaker.api.config.SecurityConfig
import kr.co.morymaker.api.domain.parking.ParkingSlot
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
import kotlin.test.assertFalse

/**
 * 자리 QR 공개 API(§10-3·§10-4) 통합 테스트 — slotCode viewType 2분기(순수 공개 모델)·
 * 셀프 등록의 승계 코어 재사용·무효 slotCode 404(enumeration-safe)를 실 MariaDB로 검증한다.
 *
 * 동시성(active_key UNIQUE→409)은 물리 트랜잭션 격리가 필요해
 * `PublicParkingConcurrencyIntegrationTest`(module-application)가 별도 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PublicParkingControllerTest(
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

    private fun createEvent(name: String = "자리 QR 테스트 행사"): String {
        val response = mockMvc.perform(
            post("/api/events")
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
            post("/api/events/$eid/parking-zones")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"part1":"지하 2층","part2":"A구역","startNo":1,"slotCount":12}"""),
        )
            .andExpect(status().isCreated)
            .andReturn().response.getContentAsString(StandardCharsets.UTF_8)
        return objectMapper.readTree(response).get("data").get("id").asText()
    }

    private fun parkRequest(slotCode: String, plate: String, vipName: String? = null, phone: String? = null, token: String? = null) =
        mockMvc.perform(
            post("/api/public/p/$slotCode/park")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf("plate" to plate, "vipName" to vipName, "phone" to phone, "token" to token),
                    ),
                ),
        )

    // ── GET viewType(§10-3) ────────────────────────────────────────

    @Test
    fun `빈 자리는 인증 헤더 없이 SELF_PARK_FORM을 반환한다`() {
        val eid = createEvent()
        val zid = createZone(eid)
        val slotCode = ParkingSlot.slotCode(zid, 1)

        mockMvc.perform(get("/api/public/p/$slotCode"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.viewType").value("SELF_PARK_FORM"))
            .andExpect(jsonPath("$.data.occupied").value(false))
            .andExpect(jsonPath("$.data.slot.slotCode").value(slotCode))
            .andExpect(jsonPath("$.data.event.name").value("자리 QR 테스트 행사"))
    }

    @Test
    fun `주차중인 자리는 OCCUPIED_NOTICE를 반환하고 타인 차량번호를 노출하지 않는다`() {
        val eid = createEvent()
        val zid = createZone(eid)
        val slotCode = ParkingSlot.slotCode(zid, 2)
        parkRequest(slotCode, "12가3456").andExpect(status().isCreated)

        val response = mockMvc.perform(get("/api/public/p/$slotCode"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.viewType").value("OCCUPIED_NOTICE"))
            .andExpect(jsonPath("$.data.occupied").value(true))
            .andReturn().response.getContentAsString(StandardCharsets.UTF_8)

        assertFalse(response.contains("12가3456"), "OCCUPIED_NOTICE 응답에 타인 차량번호가 노출되면 안 된다: $response")
    }

    @Test
    fun `무효 slotCode는 404를 받는다`() {
        mockMvc.perform(get("/api/public/p/no-such-zone-01"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
    }

    @Test
    fun `구획 범위를 벗어난 자리번호는 404를 받는다`() {
        val eid = createEvent()
        val zid = createZone(eid)
        val outOfRange = ParkingSlot.slotCode(zid, 99)

        mockMvc.perform(get("/api/public/p/$outOfRange"))
            .andExpect(status().isNotFound)
    }

    // ── POST 셀프 주차(§10-4) ───────────────────────────────────────

    @Test
    fun `빈 자리에 셀프 등록하면 201 PARKED로 등록하고 registeredBy는 셀프로 고정된다`() {
        val eid = createEvent()
        val zid = createZone(eid)
        val slotCode = ParkingSlot.slotCode(zid, 3)

        parkRequest(slotCode, "12가3456", vipName = "최지우")
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.result").value("PARKED"))
            .andExpect(jsonPath("$.data.record.registeredBy").value("셀프"))
            .andExpect(jsonPath("$.data.record.reviewNeeded").value(false))
    }

    @Test
    fun `주차중인 자리에 타 차량이 셀프 등록하면 대상을 출차시키고 200 SUPERSEDED로 review_needed 표시한다`() {
        val eid = createEvent()
        val zid = createZone(eid)
        val slotCode = ParkingSlot.slotCode(zid, 4)
        parkRequest(slotCode, "99나9999").andExpect(status().isCreated)

        parkRequest(slotCode, "12가3456")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.result").value("SUPERSEDED"))
            .andExpect(jsonPath("$.data.record.reviewNeeded").value(true))
            .andExpect(jsonPath("$.data.supersededRecord.status").value("출차"))
    }

    @Test
    fun `차량번호 누락 셀프 등록은 400 VALIDATION_FAILED를 받는다`() {
        val eid = createEvent()
        val zid = createZone(eid)
        val slotCode = ParkingSlot.slotCode(zid, 5)

        mockMvc.perform(
            post("/api/public/p/$slotCode/park")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"vipName":"최지우"}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
    }

    @Test
    fun `무효 slotCode 셀프 등록은 404를 받는다`() {
        parkRequest("no-such-zone-01", "12가3456").andExpect(status().isNotFound)
    }
}
