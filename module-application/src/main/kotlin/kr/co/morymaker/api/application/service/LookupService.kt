package kr.co.morymaker.api.application.service

import kr.co.morymaker.api.application.port.`in`.GuestListResult
import kr.co.morymaker.api.application.port.`in`.LookupItem
import kr.co.morymaker.api.application.port.`in`.LookupResult
import kr.co.morymaker.api.application.port.`in`.LookupUseCase
import kr.co.morymaker.api.application.port.`in`.ParkingView
import kr.co.morymaker.api.application.port.out.GuestPort
import kr.co.morymaker.api.application.port.out.GuestSearchQuery
import kr.co.morymaker.api.application.port.out.ParkingLinkPort
import kr.co.morymaker.api.application.security.EventScopeGuard
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * [LookupUseCase] 구현체 — 이름 부분일치 ∪ 차량 뒷자리 매칭 통합 검색(§9-1)에 좌석·주차를 동시
 * 병기한다.
 *
 * 신규 SQL/매퍼를 추가하지 않는다 — `GuestPort.search(paging=false)`로 전체 live guest를 가져와
 * 애플리케이션 레벨에서 필터링한다(`GuestService.classifyImportRows`와 동일한 "전체 조회 후 필터"
 * 패턴이며, 02-api-spec §7이 이미 이 스케일의 전체 스캔을 승인했다 — VIP 수백 규모에서는 실질
 * 영향이 낮아 신규 tech-debt 등록 대상이 아니다).
 */
@Service
internal class LookupService(
    private val guestPort: GuestPort,
    private val parkingLinkPort: ParkingLinkPort,
    private val eventScopeGuard: EventScopeGuard,
) : LookupUseCase {

    @Transactional(readOnly = true)
    override fun lookup(eventId: String, q: String): LookupResult {
        eventScopeGuard.assertAccess(eventId)
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
