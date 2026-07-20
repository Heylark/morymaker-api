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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional

/**
 * 참석자 명단 API(§4) 통합 테스트 — cross-tenant 격리(EventScopeGuard)·ApiResponse meta 확장
 * 회귀(D4)·부분 갱신·취소 보존·이름검색 3상태(§4-9)를 실 MariaDB로 검증한다.
 *
 * `EventControllerTest`와 동일 컨벤션 — `.with(jwt())`로 SecurityContext에 직접 인증 주체를
 * 주입(디코더·JWKS 미경유), `@Transactional`로 테스트 종료 시 자동 롤백.
 *
 * 엑셀 병합(§4-5·4-6, D1 매칭키·부분 실패 롤백)은 `GuestImportIntegrationTest`가 서비스 계층을
 * 직접 호출해 검증한다(MultipartFile 파싱은 여기서 다루지 않음).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class GuestControllerTest(
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

    private fun createEvent(name: String = "명단 테스트 행사"): String {
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

    private fun registerGuest(eid: String, name: String, phone: String? = null): String {
        val body = objectMapper.writeValueAsString(mapOf("name" to name, "phone" to phone))
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

    // ── cross-tenant 격리(P1) ────────────────────────────────────────

    @Test
    fun `EVENT_ADMIN이 담당 아닌 행사의 명단을 조회하면 403 EVENT_FORBIDDEN을 받는다`() {
        val eid = createEvent()

        mockMvc.perform(
            get("/events/$eid/guests")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf("다른-행사-id"))),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("EVENT_FORBIDDEN"))
    }

    @Test
    fun `SYSTEM_ADMIN은 담당 무관하게 명단을 등록·조회할 수 있다`() {
        val eid = createEvent()

        mockMvc.perform(
            post("/events/$eid/guests")
                .with(authenticatedAs(roles = listOf("SYSTEM_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"시스템관리자 등록"}"""),
        ).andExpect(status().isCreated)

        mockMvc.perform(
            get("/events/$eid/guests").with(authenticatedAs(roles = listOf("SYSTEM_ADMIN"))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(1))
    }

    @Test
    fun `event_ids 클레임이 없는 EVENT_ADMIN은 fail-CLOSED로 명단 조회가 거부된다`() {
        val eid = createEvent()

        mockMvc.perform(
            get("/events/$eid/guests").with(authenticatedAs(roles = listOf("EVENT_ADMIN"))),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("EVENT_FORBIDDEN"))
    }

    @Test
    fun `EVENT_STAFF는 명단 등록에서 403 ROLE_FORBIDDEN을 받는다(관리자 콘솔 전용)`() {
        val eid = createEvent()

        mockMvc.perform(
            post("/events/$eid/guests")
                .with(authenticatedAs(roles = listOf("EVENT_STAFF"), eventIds = listOf(eid)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"실행자 시도"}"""),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("ROLE_FORBIDDEN"))
    }

    // ── ApiResponse meta 확장(D4) byte-identical 회귀(P1) ─────────────

    @Test
    fun `guest 목록 응답은 meta total을 포함하고 q 없으면 searchState 키 자체가 없다`() {
        val eid = createEvent()
        registerGuest(eid, "김진우")

        mockMvc.perform(
            get("/events/$eid/guests").with(authenticatedAs(roles = listOf("SYSTEM_ADMIN"))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.meta.total").value(1))
            .andExpect(jsonPath("$.meta.searchState").doesNotExist())
    }

    @Test
    fun `기존 EventController 단건 조회 응답에는 meta 키가 추가되지 않는다(byte-identical 회귀)`() {
        val eid = createEvent("meta 회귀 확인용")

        mockMvc.perform(
            get("/events/$eid").with(authenticatedAs(roles = listOf("SYSTEM_ADMIN"))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.meta").doesNotExist())
    }

    // ── 부분 갱신·취소 보존(§4-3·§4-4, P2) ─────────────────────────────

    @Test
    fun `updateGuest는 미지정 필드를 보존한 채 부분 갱신한다`() {
        val eid = createEvent()
        val gid = registerGuest(eid, "박서연", phone = "010-1111-2222")

        mockMvc.perform(
            put("/events/$eid/guests/$gid")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"org":"새소속"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.org").value("새소속"))
            .andExpect(jsonPath("$.data.name").value("박서연"))
            .andExpect(jsonPath("$.data.phone").value("010-1111-2222"))
    }

    @Test
    fun `cancelGuest는 삭제가 아닌 취소 상태 전환이며 레코드가 보존된다(기본 검색 제외)`() {
        val eid = createEvent()
        val gid = registerGuest(eid, "이도현")

        mockMvc.perform(
            delete("/events/$eid/guests/$gid")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("취소"))

        mockMvc.perform(
            get("/events/$eid/guests").with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid))),
        )
            .andExpect(jsonPath("$.data.length()").value(0))

        mockMvc.perform(
            get("/events/$eid/guests?includeCancelled=true")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid))),
        )
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].status").value("취소"))
    }

    // ── 이름검색 3상태(§4-9, P2) ────────────────────────────────────────

    @Test
    fun `이름검색은 매칭 0건이면 searchState NONE을 반환한다`() {
        val eid = createEvent()
        registerGuest(eid, "김진우")

        mockMvc.perform(
            get("/events/$eid/guests?q=없는이름")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid))),
        )
            .andExpect(jsonPath("$.meta.searchState").value("NONE"))
            .andExpect(jsonPath("$.data.length()").value(0))
    }

    @Test
    fun `이름검색은 매칭 1건이면 searchState ONE을 반환한다`() {
        val eid = createEvent()
        registerGuest(eid, "김진우")
        registerGuest(eid, "박서연")

        mockMvc.perform(
            get("/events/$eid/guests?q=김진우")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid))),
        )
            .andExpect(jsonPath("$.meta.searchState").value("ONE"))
            .andExpect(jsonPath("$.data.length()").value(1))
    }

    @Test
    fun `이름검색은 중간일치 매칭 2건 이상이면 searchState MANY를 반환한다`() {
        val eid = createEvent()
        registerGuest(eid, "김진우")
        registerGuest(eid, "김진호")

        mockMvc.perform(
            get("/events/$eid/guests?q=김진")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid))),
        )
            .andExpect(jsonPath("$.meta.searchState").value("MANY"))
            .andExpect(jsonPath("$.data.length()").value(2))
    }

    @Test
    fun `취소된 참석자는 기본 이름검색에서 제외된다`() {
        val eid = createEvent()
        val gid = registerGuest(eid, "정하은")
        mockMvc.perform(
            delete("/events/$eid/guests/$gid")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid))),
        ).andExpect(status().isOk)

        mockMvc.perform(
            get("/events/$eid/guests?q=정하은")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid))),
        )
            .andExpect(jsonPath("$.meta.searchState").value("NONE"))
    }

    // ── seatLabel 실좌석 승격(§12-6) 회귀 — numbering ON + 배정 ────────

    @Test
    fun `numbering ON 그룹에서 좌석 배정된 참석자는 명단 조회에서 seatLabel이 라벨과 번호로 병기된다`() {
        val eid = createEvent()
        val gid = registerGuest(eid, "김아름")
        val groupResponse = mockMvc.perform(
            post("/events/$eid/seat-groups")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"label":"A열","numbering":true}"""),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val groupNo = objectMapper.readTree(groupResponse).get("data").get("groupNo").asInt()

        // numbering ON 그룹의 §12-5 PUT은 그룹 전체 슬롯 세트를 원자 교체한다 — ord는 1..N 연속·
        // 유일해야 하므로 목표 ord(3번)까지의 빈좌석(1·2번)도 함께 제출해야 한다.
        mockMvc.perform(
            put("/events/$eid/seat-assignments")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"groupNo":$groupNo,"assignments":[
                        {"ord":1,"guestId":null},{"ord":2,"guestId":null},{"ord":3,"guestId":"$gid"}
                    ]}""",
                ),
        ).andExpect(status().isOk)

        mockMvc.perform(
            get("/events/$eid/guests?q=김아름")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].seatLabel").value("A열 3번"))
    }

    @Test
    fun `좌석 그룹이 전혀 없는 참석자는 seatLabel이 null이다`() {
        val eid = createEvent()
        registerGuest(eid, "미배정참석자")

        mockMvc.perform(
            get("/events/$eid/guests?q=미배정참석자")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(eid))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].seatLabel").doesNotExist())
    }
}
