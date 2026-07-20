package kr.co.morymaker.api.security

import kr.co.morymaker.api.application.security.EventAccessDeniedException
import kr.co.morymaker.api.application.security.EventScopeGuard
import kr.co.morymaker.api.domain.security.MoryRoles
import org.slf4j.LoggerFactory
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component

/**
 * [EventScopeGuard] 구현체 — SecurityContext의 JWT 클레임을 읽어 행사 스코프를 판정한다.
 *
 * 헥사고날 레이어: api-app(adapter). `internal`: module-application은 [EventScopeGuard]
 * 인터페이스만 의존한다.
 *
 * fail-CLOSED 핵심 불변식(반드시 준수 — 근거는 클래스 내 각 메서드 주석):
 * 1. SYSTEM_ADMIN 판정은 `roles` 클레임에 실제 포함 여부로만 — `event_ids` 부재만으로
 *    전체 허용을 단정하지 않는다.
 * 2. null/부재/빈배열/타입손상은 모두 거부로 수렴한다.
 */
@Component
internal class EventScopeGuardAdapter : EventScopeGuard {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun assertAccess(eid: String) {
        val jwt = currentJwtOrNull() ?: throw EventAccessDeniedException(eid)
        val scope = scopeFor(jwt) ?: return // SYSTEM_ADMIN — 전체 허용
        if (eid !in scope) {
            log.warn("행사 스코프 거부 — event={}", eid.take(8))
            throw EventAccessDeniedException(eid)
        }
    }

    override fun currentScopeOrNull(): List<String>? {
        // 인증 없이 이 지점에 도달하는 정상 경로는 없다(필터 레벨 401이 선행) — 방어적으로만 거부.
        val jwt = currentJwtOrNull() ?: throw EventAccessDeniedException("")
        return scopeFor(jwt)
    }

    /** null = SYSTEM_ADMIN(전체 허용). 그 외에는 `event_ids` 클레임(부재 시 빈 리스트 — fail-CLOSED). */
    private fun scopeFor(jwt: Jwt): List<String>? {
        val roles = jwt.getClaimAsStringList("roles") ?: emptyList()
        if (MoryRoles.SYSTEM_ADMIN in roles) return null
        return jwt.getClaimAsStringList("event_ids") ?: emptyList()
    }

    private fun currentJwtOrNull(): Jwt? {
        val authentication = SecurityContextHolder.getContext().authentication
        return (authentication as? JwtAuthenticationToken)?.token
    }
}
