package kr.co.morymaker.api.application.service

import kr.co.morymaker.api.application.port.`in`.CheckinResult
import kr.co.morymaker.api.application.port.`in`.CheckinTarget
import kr.co.morymaker.api.application.port.`in`.ParkingView
import kr.co.morymaker.api.application.port.out.GuestListItem
import kr.co.morymaker.api.application.port.out.GuestPort
import kr.co.morymaker.api.application.port.out.ParkingLinkPort
import kr.co.morymaker.api.domain.guest.Guest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * 가드-free 체크인 코어(SSOT) — 인증 경로([CheckinService])와 공개 kiosk 경로
 * ([PublicKioskService])가 공유하는 상태 전이(§5-3)·동시성 방어 로직을 한 곳에 모은다
 * ([ParkingWriteSupport] 정확한 선례).
 *
 * `EventScopeGuard`를 의도적으로 의존하지 않는다 — 이 클래스는 인가를 판단하지 않고 상태
 * 전이만 수행하며, 인가는 항상 호출자 책임이다(인증 경로는 [CheckinService]가 `assertAccess`를
 * 먼저 호출한 뒤에만 이 클래스를 호출하고, 공개 kiosk 경로는 eid capability(`fetchOpenEvent`)
 * 유효성 검증으로 인가를 대체한 뒤에만 호출한다). 생성자에 `EventScopeGuard` 참조 자체가
 * 없으므로 이 클래스 내부에서는 구조적으로 그 가드를 호출할 수 없다.
 *
 * `checkin`은 무조건 update(`guestPort.update`) 대신 조건부 UPDATE(`markAttendedIfNotAttended`/
 * `markAttendedIfNotAttendedByToken`)로 상태를 전이한다 — 무인 kiosk 더블탭 동시 요청에서도
 * 정확히 1건만 CHECKED_IN으로 확정되도록 InnoDB PK 대상 단일 행 X-lock으로 직렬화한다
 * (`SELECT FOR UPDATE`는 매치 0건일 때 갭락 데드락을 일으킬 수 있어 의도적으로 회피).
 *
 * ⚠️ **조건부 UPDATE를 먼저 blind로 시도하고, 그 다음에만 대상을 조회한다** — 이 순서를 뒤집어
 * "먼저 조회해 이미 참석인지 확인한 뒤 조건부로 쓴다" 형태로 되돌리면 안 된다. MariaDB InnoDB
 * 스냅샷 격리(REPEATABLE READ)에서 같은 트랜잭션 안에 이 행을 먼저 읽어 스냅샷을 확정하면,
 * 그 사이 경쟁 트랜잭션이 커밋 변경한 뒤의 UPDATE가 MySQL의 semi-consistent read처럼 최신값을
 * 재평가하지 않고 `ER_CHECKREAD`(1020, "Record has changed since last read")로 즉시 실패한다 —
 * 실 DB 동시성 검증(N-스레드 동시 체크인)에서 직접 재현·확인된 함정이며 [GuestPort]의 두 조건부
 * 전이 메서드 문서에도 동일 호출 순서 계약이 명시돼 있다.
 */
@Service
internal class CheckinSupport(
    private val guestPort: GuestPort,
    private val parkingLinkPort: ParkingLinkPort,
) {

    @Transactional
    fun checkin(eventId: String, target: CheckinTarget): CheckinResult {
        // blind 조건부 UPDATE 먼저 — 이 트랜잭션은 아직 대상 행을 한 번도 읽지 않은 상태라
        // ER_CHECKREAD 함정에 노출되지 않는다. 이미 참석 상태거나 대상이 아예 없으면 자연히
        // affected == 0(둘 중 어느 경우인지는 뒤이은 조회로 구분한다).
        val now = Instant.now()
        val affected = when (target) {
            is CheckinTarget.ByGuestId -> guestPort.markAttendedIfNotAttended(eventId, target.gid, now)
            is CheckinTarget.ByToken -> guestPort.markAttendedIfNotAttendedByToken(eventId, target.token, now)
        }

        // UPDATE 이후 첫 조회 — 이 트랜잭션에서 이 행을 읽는 최초 시점이라 안전하다. null이면
        // affected도 항상 0이었을 것(대상 자체가 없음) — 404로 번역한다.
        val resultGuest = fetchTarget(eventId, target) ?: throw NoSuchElementException("참석자를 찾을 수 없습니다")
        val resultCode = if (affected == 1) CheckinResult.CHECKED_IN else CheckinResult.ALREADY_CHECKED_IN

        val parking = parkingLinkPort.findActiveSlotByGuestId(eventId, resultGuest.id)
            ?.let { ParkingView(slotSig = it.slotSig, display = ParkingDisplay.derive(it.slotSig)) }
        return CheckinResult(resultCode, resultGuest, parking)
    }

    @Transactional(readOnly = true)
    fun scanPreview(eventId: String, token: String): GuestListItem =
        guestPort.fetchDetailByToken(eventId, token)
            ?: throw NoSuchElementException("참석자를 찾을 수 없습니다")

    @Transactional
    fun cancelCheckin(eventId: String, gid: String): Guest {
        val existing = guestPort.fetchById(eventId, gid) ?: throw NoSuchElementException("참석자를 찾을 수 없습니다")
        val reverted = existing.with(status = Guest.STATUS_WAITING, visitAt = null)
        guestPort.update(reverted)
        return reverted
    }

    private fun fetchTarget(eventId: String, target: CheckinTarget): GuestListItem? = when (target) {
        is CheckinTarget.ByToken -> guestPort.fetchDetailByToken(eventId, target.token)
        is CheckinTarget.ByGuestId -> guestPort.fetchDetailById(eventId, target.gid)
    }
}
