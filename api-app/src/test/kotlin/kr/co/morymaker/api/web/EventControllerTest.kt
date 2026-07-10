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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
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

    // ── update(§2-4) ───────────────────────────────────────────────

    @Test
    fun `EVENT_ADMIN은 담당 행사의 일반 필드를 수정할 수 있다`() {
        val id = createEventAsSystemAdmin("수정 대상 행사")

        mockMvc.perform(
            put("/api/events/$id")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(id)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"수정된 행사명","place":"새 장소","status":"운영중","active":true}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.name").value("수정된 행사명"))
            .andExpect(jsonPath("$.data.place").value("새 장소"))
            .andExpect(jsonPath("$.data.status").value("운영중"))
            .andExpect(jsonPath("$.data.active").value(true))
    }

    @Test
    fun `PUT §2-4 요청에 bgColor를 포함해도 무시되고 저장된 브랜딩 컬러는 변하지 않는다(저장 게이트 회귀)`() {
        val id = createEventAsSystemAdmin("게이트 회귀 대상")
        // 최초 브랜딩 저장(§11-1) — 이후 §2-4가 이 값을 건드리지 않아야 한다.
        mockMvc.perform(
            put("/api/events/$id/branding")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(id)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"bgColor":"#0c1322","pointColor":"#c9a24a","titleColor":"#ffffff","bodyColor":"#d9d9d9"}"""),
        ).andExpect(status().isOk)

        // §2-4 PUT에 bgColor를 얹어 보내도(EventUpdateRequest엔 필드 자체가 없어 역직렬화 시 무시) 컬러는 불변.
        mockMvc.perform(
            put("/api/events/$id")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(id)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"게이트 회귀 대상","status":"준비","active":false,"bgColor":"#ff0000"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.bgColor").value("#0c1322"))

        mockMvc.perform(
            get("/api/events/$id").with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(id))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.bgColor").value("#0c1322"))
            .andExpect(jsonPath("$.data.pointColor").value("#c9a24a"))
    }

    @Test
    fun `name 없이 수정 요청하면 400 VALIDATION_FAILED를 반환한다`() {
        val id = createEventAsSystemAdmin("검증 대상")

        mockMvc.perform(
            put("/api/events/$id")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(id)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"status":"준비","active":false}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
    }

    @Test
    fun `EVENT_ADMIN이 담당 아닌 행사를 수정하면 403 EVENT_FORBIDDEN을 받는다`() {
        val id = createEventAsSystemAdmin("타 담당자 수정 대상")

        mockMvc.perform(
            put("/api/events/$id")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf("다른-행사-id")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"타 담당자 수정 대상","status":"준비","active":false}"""),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("EVENT_FORBIDDEN"))
    }

    // ── updateBranding(§11-1, ADM-04 명시 저장 게이트) ───────────────

    @Test
    fun `EVENT_ADMIN은 브랜딩 컬러4종·kv·defaultIdleMode를 저장할 수 있다`() {
        val id = createEventAsSystemAdmin("브랜딩 저장 대상")

        mockMvc.perform(
            put("/api/events/$id/branding")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(id)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"bgColor":"#0c1322","pointColor":"#c9a24a","titleColor":"#ffffff",""" +
                        """"bodyColor":"#d9d9d9","kv":"2026 NEW YEAR GALA","defaultIdleMode":"branded"}""",
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.bgColor").value("#0c1322"))
            .andExpect(jsonPath("$.data.kv").value("2026 NEW YEAR GALA"))
            .andExpect(jsonPath("$.data.defaultIdleMode").value("branded"))
    }

    @Test
    fun `브랜딩 저장 요청에 컬러 형식이 RRGGBB가 아니면 400 VALIDATION_FAILED를 받는다`() {
        val id = createEventAsSystemAdmin("컬러 검증 대상")

        mockMvc.perform(
            put("/api/events/$id/branding")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(id)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"bgColor":"blue"}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
    }

    @Test
    fun `브랜딩 저장 요청은 컬러 필드가 모두 null이어도 kv·defaultIdleMode만 저장할 수 있다`() {
        val id = createEventAsSystemAdmin("kv만 수정 대상")

        mockMvc.perform(
            put("/api/events/$id/branding")
                .with(authenticatedAs(roles = listOf("EVENT_ADMIN"), eventIds = listOf(id)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"kv":"업데이트된 KV","defaultIdleMode":"fullbleed"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.kv").value("업데이트된 KV"))
            .andExpect(jsonPath("$.data.defaultIdleMode").value("fullbleed"))
    }
}
