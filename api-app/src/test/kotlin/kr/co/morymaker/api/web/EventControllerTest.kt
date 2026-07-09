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
 * event 최소 검증 API 3종(02-api-spec §2-1/§2-2/§2-3) 통합 테스트 — 실 MariaDB(module-persistence
 * 배선)와 `.with(jwt())`로 SecurityContext에 직접 주입한 인증 주체를 사용한다(디코더·JWKS는 거치지
 * 않음 — `SecurityFilterChainTest`와 동일 원칙).
 *
 * 역할게이트(L1)·행사 스코프게이트(L2, 인터셉터+서비스 2중) 모두 이 슬라이스에서 함께 검증된다.
 * 실 auth 서버가 발급한 토큰을 사용한 cross-tenant 종단 검증은 Tester 단계(auth 선기동)가 별도 수행.
 *
 * `@Transactional`: POST로 생성한 행이 테스트 종료 시 자동 롤백된다 — MockMvc는 테스트와 같은
 * 스레드에서 실행되어 트랜잭션 컨텍스트를 공유하므로 안전하다(auth `RefreshTokenRotationIntegrationTest`와
 * 동일 컨벤션). 반복 실행 시 로컬 개발 DB에 검증용 행이 무한히 쌓이는 것을 막는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class EventControllerTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
) {

    /**
     * `.with(jwt())`의 기본 GrantedAuthoritiesConverter는 "scope"/"scp" 클레임만 읽는다 — 이 앱의
     * `roles` 클레임을 실제로 권한으로 승격하려면 프로덕션과 동일한 [SecurityConfig.grantedAuthorities]를
     * 명시적으로 연결해야 한다(연결하지 않으면 `@PreAuthorize`가 항상 거부된다 — 실측 확인).
     */
    private fun authenticatedAs(roles: List<String>? = null, eventIds: List<String>? = null): RequestPostProcessor =
        jwt()
            .jwt { builder ->
                roles?.let { builder.claim("roles", it) }
                eventIds?.let { builder.claim("event_ids", it) }
            }
            .authorities { jwt: Jwt -> SecurityConfig.grantedAuthorities(jwt) }

    private fun createEventAsSystemAdmin(name: String): String {
        val body = """{"name":"$name"}"""
        val response = mockMvc.perform(
            post("/api/events")
                .with(authenticatedAs(roles = listOf("SYSTEM_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isCreated)
            .andReturn().response.contentAsString
        val root = objectMapper.readTree(response)
        return root.get("data").get("id").asText()
    }

    @Test
    fun `SYSTEM_ADMIN은 행사를 생성할 수 있고 기본값이 준비중 active false로 채워진다`() {
        mockMvc.perform(
            post("/api/events")
                .with(authenticatedAs(roles = listOf("SYSTEM_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"창립 30주년 기념식"}"""),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.name").value("창립 30주년 기념식"))
            .andExpect(jsonPath("$.data.status").value("준비"))
            .andExpect(jsonPath("$.data.active").value(false))
    }

    @Test
    fun `name 없이 생성 요청하면 400 VALIDATION_FAILED를 반환한다`() {
        mockMvc.perform(
            post("/api/events")
                .with(authenticatedAs(roles = listOf("SYSTEM_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.error.field").value("name"))
    }

    @Test
    fun `EVENT_STAFF는 행사 생성 시 403 ROLE_FORBIDDEN을 받는다`() {
        mockMvc.perform(
            post("/api/events")
                .with(authenticatedAs(roles = listOf("EVENT_STAFF")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"권한 없는 시도"}"""),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("ROLE_FORBIDDEN"))
    }

    @Test
    fun `SYSTEM_ADMIN은 담당 무관하게 단건 조회할 수 있다`() {
        val id = createEventAsSystemAdmin("SYSTEM_ADMIN 단건 조회 대상")

        mockMvc.perform(
            get("/api/events/$id").with(authenticatedAs(roles = listOf("SYSTEM_ADMIN"))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.id").value(id))
    }

    @Test
    fun `EVENT_ADMIN이 담당 행사를 조회하면 200을 받는다`() {
        val id = createEventAsSystemAdmin("EVENT_ADMIN 담당 행사")

        mockMvc.perform(
            get("/api/events/$id")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(id))),
        )
            .andExpect(status().isOk)
    }

    @Test
    fun `EVENT_ADMIN이 담당 아닌 행사를 조회하면 403 EVENT_FORBIDDEN을 받는다(cross-tenant)`() {
        val id = createEventAsSystemAdmin("다른 담당자 행사")

        mockMvc.perform(
            get("/api/events/$id")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf("완전히-다른-행사-id"))),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("EVENT_FORBIDDEN"))
    }

    @Test
    fun `event_ids 클레임이 없는 EVENT_ADMIN은 fail-CLOSED로 거부된다`() {
        val id = createEventAsSystemAdmin("클레임 부재 검증 대상")

        mockMvc.perform(
            get("/api/events/$id").with(authenticatedAs(roles = listOf("EVENT_ADMIN"))),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("EVENT_FORBIDDEN"))
    }

    @Test
    fun `존재하지 않는 행사를 SYSTEM_ADMIN이 조회하면 404를 받는다`() {
        mockMvc.perform(
            get("/api/events/존재하지-않는-id").with(authenticatedAs(roles = listOf("SYSTEM_ADMIN"))),
        )
            .andExpect(status().isNotFound)
    }

    @Test
    fun `SYSTEM_ADMIN 목록 조회는 필터 없이 전체를 반환한다`() {
        createEventAsSystemAdmin("목록 노출 확인용")

        mockMvc.perform(
            get("/api/events").with(authenticatedAs(roles = listOf("SYSTEM_ADMIN"))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data").isArray)
    }

    @Test
    fun `담당 행사가 없는 EVENT_ADMIN의 목록 조회는 빈 배열을 반환한다`() {
        mockMvc.perform(
            get("/api/events")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = emptyList())),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data").isArray)
            .andExpect(jsonPath("$.data.length()").value(0))
    }
}
