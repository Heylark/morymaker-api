package kr.co.morymaker.api.web

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.common.HybridBinarizer
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 주차 구획 API(§6-1~6-4) 통합 테스트 — CRUD·titleOverrides delete-insert·qr-zip 생성(P4)·
 * cross-tenant 격리를 실 MariaDB로 검증한다.
 *
 * qr-zip은 실제 PNG를 zxing으로 디코드해 payload가 `{qr-base-url}/p/{slotCode}`(P2 slotCode
 * base=slot_no 절대 번호) 계약과 일치하는지까지 확인한다 — ZIP 구조만 확인하면 QR 내용이 깨져도
 * 통과하므로 부족(Tester 합리화 방지 표).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ParkingZoneControllerTest(
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

    private fun createEvent(name: String = "주차 구획 테스트 행사"): String {
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

    private fun createZoneRaw(eid: String, body: Map<String, Any?>, eventIds: List<String> = listOf(eid)) =
        mockMvc.perform(
            post("/api/events/$eid/parking-zones")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = eventIds))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)),
        )

    private fun createZone(eid: String, part1: String = "지하 2층", part2: String? = "A구역", startNo: Int = 1, slotCount: Int = 12): String {
        val response = createZoneRaw(eid, mapOf("part1" to part1, "part2" to part2, "startNo" to startNo, "slotCount" to slotCount))
            .andExpect(status().isCreated)
            .andReturn().response.getContentAsString(StandardCharsets.UTF_8)
        return objectMapper.readTree(response).get("data").get("id").asText()
    }

    // ── CRUD(§6-1~6-3) ────────────────────────────────────────────────

    @Test
    fun `create는 구획을 생성하고 zoneName·outdoor를 파생해 응답한다`() {
        val eid = createEvent()

        createZoneRaw(eid, mapOf("part1" to "야외", "part2" to "C구역", "startNo" to 1, "slotCount" to 8))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.zoneName").value("야외 C구역"))
            .andExpect(jsonPath("$.data.outdoor").value(true))
            .andExpect(jsonPath("$.data.slotCount").value(8))
            .andExpect(jsonPath("$.data.titleOverrides").isEmpty)
    }

    @Test
    fun `create는 part1이 비어 있으면 400 VALIDATION_FAILED를 받는다`() {
        val eid = createEvent()

        createZoneRaw(eid, mapOf("part1" to "", "startNo" to 1, "slotCount" to 8))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
    }

    @Test
    fun `list는 구획별 titleOverrides를 조인해 반환한다`() {
        val eid = createEvent()
        val zid = createZone(eid)
        mockMvc.perform(
            put("/api/events/$eid/parking-zones/$zid")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"part1":"지하 2층","part2":"A구역","startNo":1,"slotCount":12,"titleOverrides":{"3":"귀빈석"}}"""),
        ).andExpect(status().isOk)

        mockMvc.perform(
            get("/api/events/$eid/parking-zones")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].titleOverrides.3").value("귀빈석"))
    }

    @Test
    fun `update는 titleOverrides가 오면 전삭제 후 재삽입하고, null이면 유지한다`() {
        val eid = createEvent()
        val zid = createZone(eid)

        mockMvc.perform(
            put("/api/events/$eid/parking-zones/$zid")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"part1":"지하 2층","part2":"A구역","startNo":1,"slotCount":12,"titleOverrides":{"3":"귀빈석","5":"VIP"}}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.titleOverrides.length()").value(2))

        // titleOverrides=null인 재수정은 기존 타이틀을 건드리지 않는다.
        mockMvc.perform(
            put("/api/events/$eid/parking-zones/$zid")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"part1":"지하 2층","part2":"A구역","startNo":1,"slotCount":15}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.slotCount").value(15))
            .andExpect(jsonPath("$.data.titleOverrides.length()").value(2))
    }

    // ── cross-tenant 격리(P1) ────────────────────────────────────────

    @Test
    fun `EVENT_ADMIN이 담당 아닌 행사의 구획 목록을 조회하면 403 EVENT_FORBIDDEN을 받는다`() {
        val eid = createEvent()

        mockMvc.perform(
            get("/api/events/$eid/parking-zones")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf("다른-행사-id"))),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("EVENT_FORBIDDEN"))
    }

    @Test
    fun `EVENT_ADMIN이 담당 아닌 행사에 구획을 생성하면 403 EVENT_FORBIDDEN을 받는다`() {
        val eid = createEvent()

        createZoneRaw(eid, mapOf("part1" to "지하 2층", "startNo" to 1, "slotCount" to 8), eventIds = listOf("다른-행사-id"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("EVENT_FORBIDDEN"))
    }

    @Test
    fun `EVENT_STAFF는 관리자 콘솔 전용 구획 API에서 403 ROLE_FORBIDDEN을 받는다`() {
        val eid = createEvent()

        mockMvc.perform(
            get("/api/events/$eid/parking-zones")
                .with(authenticatedAs(roles = listOf("EVENT_STAFF"), eventIds = listOf(eid))),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("ROLE_FORBIDDEN"))
    }

    // ── qr-zip(§6-4a, P4) ──────────────────────────────────────────────

    @Test
    fun `qr-zip은 자리별 PNG를 slot_no 절대번호 파일명으로 묶고 QR payload는 slotCode 자리중립 URL이다`() {
        val eid = createEvent()
        val zid = createZone(eid, part1 = "지하 2층", part2 = "A구역", startNo = 1, slotCount = 3)

        val result = mockMvc.perform(
            get("/api/events/$eid/parking-zones/$zid/qr-zip")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid))),
        )
            .andExpect(status().isOk)
            .andReturn()

        val disposition = result.response.getHeader("Content-Disposition")
        assertTrue(disposition!!.contains("filename*=UTF-8''"))
        assertEquals("application/zip", result.response.contentType)

        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(result.response.contentAsByteArray)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                entries[entry.name] = zis.readBytes()
                entry = zis.nextEntry
            }
        }

        assertEquals(3, entries.size)
        assertEquals(setOf("지하 2층 A구역 1.png", "지하 2층 A구역 2.png", "지하 2층 A구역 3.png"), entries.keys)

        val decoded = decodeQr(entries["지하 2층 A구역 1.png"]!!)
        assertEquals("https://park.morymaker.co.kr/p/$zid-01", decoded)
    }

    private fun decodeQr(png: ByteArray): String {
        val image = ImageIO.read(ByteArrayInputStream(png))
        val bitmap = BinaryBitmap(HybridBinarizer(BufferedImageLuminanceSource(image)))
        return MultiFormatReader().decode(bitmap).text
    }
}
