package kr.co.morymaker.api.application.service

import kr.co.morymaker.api.application.port.`in`.GuestListResult
import kr.co.morymaker.api.application.port.`in`.LookupItem
import kr.co.morymaker.api.application.port.`in`.LookupResult
import kr.co.morymaker.api.application.port.`in`.ParkingView
import kr.co.morymaker.api.application.port.out.GuestPort
import kr.co.morymaker.api.application.port.out.GuestSearchQuery
import kr.co.morymaker.api.application.port.out.ParkingLinkPort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 가드-free 통합조회 코어(SSOT) — 인증 경로([LookupService])와 공개 kiosk 경로
 * ([PublicKioskService])가 공유하는 이름 부분일치 ∪ 차량 뒷자리 매칭(§9-1) + 좌석·주차 병기
 * 로직을 한 곳에 모은다([ParkingWriteSupport] 정확한 선례).
 *
 * `EventScopeGuard`를 의도적으로 의존하지 않는다 — 이 클래스는 인가를 판단하지 않고 조회만
 * 수행하며, 인가는 항상 호출자 책임이다(인증 경로는 [LookupService]가 `assertAccess`를 먼저
 * 호출한 뒤에만 이 클래스를 호출하고, 공개 kiosk 경로는 eid capability(`fetchOpenEvent`) 유효성
 * 검증으로 인가를 대체한 뒤에만 호출한다). 생성자에 `EventScopeGuard` 참조 자체가 없으므로 이
 * 클래스 내부에서는 구조적으로 그 가드를 호출할 수 없다.
 */
@Service
internal class LookupSearchSupport(
    private val guestPort: GuestPort,
    private val parkingLinkPort: ParkingLinkPort,
) {

    @Transactional(readOnly = true)
    fun search(eventId: String, q: String): LookupResult {
        val trimmedQ = q.trim()
        require(trimmedQ.isNotBlank()) { "검색어(q)는 필수입니다" }

        // 차량 뒷자리 매칭용 숫자만 추출 — 순수 한글 검색어처럼 숫자가 0개면 뒤에서 plate 매칭
        // 분기 자체를 건너뛴다. 가드가 없으면 모든 plate가 "빈 문자열로 끝남" 판정되어 전 참석자가
        // 오매칭되는 버그가 발생한다(digitsOnly(plate).endsWith("")는 항상 true).
        val qDigits = digitsOnly(trimmedQ)

        val liveGuests = guestPort.search(eventId, GuestSearchQuery(includeCancelled = false, paging = false))
        val matched = liveGuests.filter { guest ->
            guest.name.contains(trimmedQ) ||
                (qDigits.isNotBlank() && digitsOnly(guest.plate ?: "").endsWith(qDigits))
        }

        val items = matched.map { guest ->
            val parking = parkingLinkPort.findActiveSlotByGuestId(eventId, guest.id)
                ?.let { ParkingView(slotSig = it.slotSig, display = ParkingDisplay.derive(it.slotSig)) }
            LookupItem(guest = guest, parking = parking)
        }

        val searchState = when {
            items.isEmpty() -> GuestListResult.SEARCH_STATE_NONE
            items.size == 1 -> GuestListResult.SEARCH_STATE_ONE
            else -> GuestListResult.SEARCH_STATE_MANY
        }
        return LookupResult(items = items, total = items.size, searchState = searchState)
    }

    companion object {
        private fun digitsOnly(s: String): String = s.filter { it.isDigit() }
    }
}
