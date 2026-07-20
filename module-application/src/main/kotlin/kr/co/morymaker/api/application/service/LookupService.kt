package kr.co.morymaker.api.application.service

import kr.co.morymaker.api.application.port.`in`.LookupResult
import kr.co.morymaker.api.application.port.`in`.LookupUseCase
import kr.co.morymaker.api.application.security.EventScopeGuard
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * [LookupUseCase] 구현체 — 행사 스코프 검증([EventScopeGuard.assertAccess])만 수행하고, 실제
 * 검색 코어(이름 부분일치 ∪ 차량 뒷자리 매칭 + 좌석·주차 병기, §9-1)는
 * [LookupSearchSupport](가드-free, 공개 kiosk 경로와 공유하는 SSOT)에 위임한다 — 인증 여부만
 * 다른 두 진입점이 동일한 검색 로직을 재사용한다.
 */
@Service
internal class LookupService(
    private val lookupSearchSupport: LookupSearchSupport,
    private val eventScopeGuard: EventScopeGuard,
) : LookupUseCase {

    @Transactional(readOnly = true)
    override fun lookup(eventId: String, q: String): LookupResult {
        eventScopeGuard.assertAccess(eventId)
        return lookupSearchSupport.search(eventId, q)
    }
}
