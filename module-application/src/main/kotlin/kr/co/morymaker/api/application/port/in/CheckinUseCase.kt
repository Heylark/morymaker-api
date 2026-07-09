package kr.co.morymaker.api.application.port.`in`

import kr.co.morymaker.api.application.port.out.GuestListItem
import kr.co.morymaker.api.domain.guest.Guest

/**
 * 체크인 유스케이스 포트-in(§5) — api-app의 `CheckinController`가 호출한다.
 *
 * Guest CRUD([GuestUseCase])와 분리하는 근거는 02-architect ADR-CHECKIN-STRUCTURE 참조 —
 * 인가 표면이 다르고(§5-1 SCN은 STAFF 포함) 멱등 재조회 응답 계약이 CRUD와 다르다. 동일
 * Guest 애그리게잇의 상태 전이이므로 별도 CheckinPort 없이 `GuestPort`+`ParkingLinkPort`를
 * 재사용한다.
 */
interface CheckinUseCase {

    /** 체크인 확정(§5-1, SCN 경로만 — KIO는 D2 결정 후 별도 태스크). 이미 참석이면 멱등 재조회. */
    fun checkin(eventId: String, target: CheckinTarget): CheckinResult

    /** 스캔 프리뷰(§5-2, 선택) — 조회만, 상태 변경 없음. */
    fun scanPreview(eventId: String, token: String): GuestListItem

    /** 체크인 취소(§5-3) — 참석→대기, visit_at 초기화. */
    fun cancelCheckin(eventId: String, gid: String): Guest
}

/** 체크인 대상 식별 — token(QR/스캔) 또는 guestId(수동 선택) 중 하나. */
sealed class CheckinTarget {
    data class ByToken(val token: String) : CheckinTarget()
    data class ByGuestId(val gid: String) : CheckinTarget()
}

/**
 * @param resultCode `CHECKED_IN`(신규 확정) 또는 `ALREADY_CHECKED_IN`(멱등 재조회, HTTP 200 유지)
 */
data class CheckinResult(
    val resultCode: String,
    val guest: GuestListItem,
    val parking: ParkingView?,
) {
    companion object {
        const val CHECKED_IN = "CHECKED_IN"
        const val ALREADY_CHECKED_IN = "ALREADY_CHECKED_IN"
    }
}

/** 체크인 응답 주차 위치 병기용 — display는 slotSig 구분자 최소 정규화(완전 포맷팅은 §6 이연). */
data class ParkingView(val slotSig: String, val display: String)
