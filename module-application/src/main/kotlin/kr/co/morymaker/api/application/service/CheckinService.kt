package kr.co.morymaker.api.application.service

import kr.co.morymaker.api.application.port.`in`.CheckinResult
import kr.co.morymaker.api.application.port.`in`.CheckinTarget
import kr.co.morymaker.api.application.port.`in`.CheckinUseCase
import kr.co.morymaker.api.application.port.`in`.ParkingView
import kr.co.morymaker.api.application.port.out.GuestListItem
import kr.co.morymaker.api.application.port.out.GuestPort
import kr.co.morymaker.api.application.port.out.ParkingLinkPort
import kr.co.morymaker.api.application.port.out.toGuest
import kr.co.morymaker.api.application.security.EventScopeGuard
import kr.co.morymaker.api.domain.guest.Guest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * [CheckinUseCase] 구현체 — §5-1(SCN 경로만)·§5-3 상태 전이를 담당한다.
 *
 * `GuestService`와 분리하는 근거는 02-architect ADR-CHECKIN-STRUCTURE 참조(인가 표면·응답
 * 계약 상이). 동일 Guest 애그리게잇의 상태 전이이므로 별도 CheckinPort 없이 [GuestPort]와
 * [ParkingLinkPort]를 재사용한다(애그리게잇 1개·포트 2개 공유).
 *
 * KIO(무인 키오스크) 경로는 D2 결정(사용자 결정 위임, CP-2 트리거 ⑥) 후 별도 태스크로
 * 추가한다 — 이 클래스는 인증된 실행자(STAFF/ADMIN)만 호출하는 SCN 경로만 구현한다.
 */
@Service
internal class CheckinService(
    private val guestPort: GuestPort,
    private val parkingLinkPort: ParkingLinkPort,
    private val eventScopeGuard: EventScopeGuard,
) : CheckinUseCase {

    @Transactional
    override fun checkin(eventId: String, target: CheckinTarget): CheckinResult {
        eventScopeGuard.assertAccess(eventId)
        val guest = fetchTarget(eventId, target)
            ?: throw NoSuchElementException("참석자를 찾을 수 없습니다")

        val resultGuest: GuestListItem
        val resultCode: String
        if (guest.status == Guest.STATUS_ATTENDED) {
            // 멱등 재조회 — 상태·visit_at 재변경 없이 200으로 현재 정보만 다시 내려준다.
            resultCode = CheckinResult.ALREADY_CHECKED_IN
            resultGuest = guest
        } else {
            val updated = guest.toGuest().with(status = Guest.STATUS_ATTENDED, visitAt = Instant.now())
            guestPort.update(updated)
            resultCode = CheckinResult.CHECKED_IN
            resultGuest = guestPort.fetchDetailById(eventId, guest.id) ?: guest
        }

        val parking = parkingLinkPort.findActiveSlotByGuestId(eventId, guest.id)
            ?.let { ParkingView(slotSig = it.slotSig, display = deriveParkingDisplay(it.slotSig)) }
        return CheckinResult(resultCode, resultGuest, parking)
    }

    @Transactional(readOnly = true)
    override fun scanPreview(eventId: String, token: String): GuestListItem {
        eventScopeGuard.assertAccess(eventId)
        return guestPort.fetchDetailByToken(eventId, token)
            ?: throw NoSuchElementException("참석자를 찾을 수 없습니다")
    }

    @Transactional
    override fun cancelCheckin(eventId: String, gid: String): Guest {
        eventScopeGuard.assertAccess(eventId)
        val existing = guestPort.fetchById(eventId, gid) ?: throw NoSuchElementException("참석자를 찾을 수 없습니다")
        val reverted = existing.with(status = Guest.STATUS_WAITING, visitAt = null)
        guestPort.update(reverted)
        return reverted
    }

    private fun fetchTarget(eventId: String, target: CheckinTarget): GuestListItem? = when (target) {
        is CheckinTarget.ByToken -> guestPort.fetchDetailByToken(eventId, target.token)
        is CheckinTarget.ByGuestId -> guestPort.fetchDetailById(eventId, target.gid)
    }

    companion object {
        /** slotSig("지하 2층·A구역·3")의 `·` 구분자만 공백으로 정규화 — 완전 포맷팅은 §6 이연. */
        private fun deriveParkingDisplay(slotSig: String): String = slotSig.replace("·", " ")
    }
}
