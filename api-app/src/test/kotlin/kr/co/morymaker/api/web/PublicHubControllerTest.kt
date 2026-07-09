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

/**
 * 개인 허브 공개 API(§10-1·§10-2) 통합 테스트 — 무인증 접근(`Authorization` 헤더 없이 호출 —
 * `assertAccess` 우회 실증) · 무효 token 404 단일코드(enumeration 방지) · read-only(체크인
 * 부작용 0) · 차량 사전등록 백필을 실 MariaDB로 검증한다.
 *
 * 명단 등록·조회는 `GuestControllerTest`와 동일 컨벤션(`.with(jwt())`)으로 관리자 API를 먼저
 * 호출해 준비하고, 공개 엔드포인트 자체는 인증 없이 호출한다 — 이 비대칭이 곧 D1(무인증 쓰기
 * 인가 모델)의 핵심 실증이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PublicHubControllerTest(
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

    private fun createEvent(name: String = "공개 허브 테스트 행사"): String {
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

    /** @return (guestId, token) */
    private fun registerGuest(eid: String, name: String, plate: String? = null): Pair<String, String> {
        val body = objectMapper.writeValueAsString(mapOf("name" to name, "plate" to plate))
        val response = mockMvc.perform(
            post("/api/events/$eid/guests")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isCreated)
            .andReturn().response.contentAsString
        val data = objectMapper.readTree(response).get("data")
        return data.get("id").asText() to data.get("token").asText()
    }

    // ── 무인증 접근(P1 — D1 핵심 실증) ────────────────────────────────

    @Test
    fun `인증 헤더 없이 유효 token으로 개인 허브를 조회하면 200과 4요소를 받는다`() {
        val eid = createEvent()
        val (_, token) = registerGuest(eid, "이서연", plate = "78다9012")

        mockMvc.perform(get("/api/public/u/$token"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.guest.name").value("이서연"))
            .andExpect(jsonPath("$.data.guest.plate").value("78다9012"))
            .andExpect(jsonPath("$.data.checkinQr.token").value(token))
            .andExpect(jsonPath("$.data.checkinQr.url").value("http://localhost:3000/u/$token"))
            .andExpect(jsonPath("$.data.prereg.plateRegistered").value(true))
            .andExpect(jsonPath("$.data.parkingEntry.scanUrl").exists())
    }

    @Test
    fun `무효 token은 404 NOT_FOUND를 받는다(enumeration 방지 — 단일 코드)`() {
        mockMvc.perform(get("/api/public/u/no-such-token"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
    }

    @Test
    fun `두 행사에 걸친 token은 각자의 event로만 해석된다(cross-event 미노출)`() {
        val eidA = createEvent("행사 A")
        val eidB = createEvent("행사 B")
        val (_, tokenA) = registerGuest(eidA, "김진우")
        val (_, tokenB) = registerGuest(eidB, "박서연")

        mockMvc.perform(get("/api/public/u/$tokenA"))
            .andExpect(jsonPath("$.data.event.name").value("행사 A"))
            .andExpect(jsonPath("$.data.guest.name").value("김진우"))

        mockMvc.perform(get("/api/public/u/$tokenB"))
            .andExpect(jsonPath("$.data.event.name").value("행사 B"))
            .andExpect(jsonPath("$.data.guest.name").value("박서연"))
    }

    @Test
    fun `개인 허브 조회는 체크인 부작용이 없다(다회 조회해도 상태 불변 — 링크 오픈 다체크인)`() {
        val eid = createEvent()
        val (_, token) = registerGuest(eid, "최유나")

        repeat(3) {
            mockMvc.perform(get("/api/public/u/$token"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.guest.status").value("대기"))
        }
    }

    // ── 차량 사전등록(§10-2, P2) ─────────────────────────────────────

    @Test
    fun `사전 차량등록은 plate를 백필하고 이후 조회에 반영된다`() {
        val eid = createEvent()
        val (_, token) = registerGuest(eid, "정하은")

        mockMvc.perform(
            post("/api/public/u/$token/prereg-plate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"plate":"12가3456"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.plate").value("12가3456"))

        mockMvc.perform(get("/api/public/u/$token"))
            .andExpect(jsonPath("$.data.guest.plate").value("12가3456"))
            .andExpect(jsonPath("$.data.prereg.plateRegistered").value(true))
    }

    @Test
    fun `무효 token으로 사전등록을 시도하면 404를 받는다`() {
        mockMvc.perform(
            post("/api/public/u/no-such-token/prereg-plate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"plate":"12가3456"}"""),
        )
            .andExpect(status().isNotFound)
    }

    @Test
    fun `plate 미기입 사전등록 요청은 400 VALIDATION_FAILED를 받는다`() {
        val eid = createEvent()
        val (_, token) = registerGuest(eid, "홍길동")

        mockMvc.perform(
            post("/api/public/u/$token/prereg-plate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"plate":""}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
    }
}
