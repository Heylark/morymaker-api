package kr.co.morymaker.api.application.service

import kr.co.morymaker.api.application.port.`in`.CheckinResult
import kr.co.morymaker.api.application.port.`in`.CheckinTarget
import kr.co.morymaker.api.application.port.`in`.CheckinUseCase
import kr.co.morymaker.api.application.port.out.GuestListItem
import kr.co.morymaker.api.application.security.EventScopeGuard
import kr.co.morymaker.api.domain.guest.Guest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * [CheckinUseCase] 구현체 — §5-1(SCN 경로만)·§5-3 상태 전이를 담당한다.
 *
 * `GuestService`와 분리하는 근거는 인가 표면(체크인 확정 SCN은 STAFF 포함, 명단 CRUD는 ADMIN
 * 전용)과 멱등 재조회 응답 계약이 CRUD와 달라서다.
 *
 * 행사 스코프 검증([EventScopeGuard.assertAccess])만 수행하고, 실제 상태 전이 코어(§5-3 +
 * 동시성 방어)는 [CheckinSupport](가드-free, 공개 kiosk 경로와 공유하는 SSOT)에 위임한다 —
 * 인증 여부만 다른 두 진입점이 동일한 체크인 로직을 재사용한다.
 */
@Service
internal class CheckinService(
    private val checkinSupport: CheckinSupport,
    private val eventScopeGuard: EventScopeGuard,
) : CheckinUseCase {

    @Transactional
    override fun checkin(eventId: String, target: CheckinTarget): CheckinResult {
        eventScopeGuard.assertAccess(eventId)
        return checkinSupport.checkin(eventId, target)
    }

    @Transactional(readOnly = true)
    override fun scanPreview(eventId: String, token: String): GuestListItem {
        eventScopeGuard.assertAccess(eventId)
        return checkinSupport.scanPreview(eventId, token)
    }

    @Transactional
    override fun cancelCheckin(eventId: String, gid: String): Guest {
        eventScopeGuard.assertAccess(eventId)
        return checkinSupport.cancelCheckin(eventId, gid)
    }
}
