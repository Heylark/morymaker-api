package kr.co.morymaker.api.application.service

import kr.co.morymaker.api.application.port.`in`.PublicHubResult
import kr.co.morymaker.api.application.port.`in`.PublicHubUseCase
import kr.co.morymaker.api.application.port.out.EventPort
import kr.co.morymaker.api.application.port.out.GuestPort
import kr.co.morymaker.api.application.port.out.toGuest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * [PublicHubUseCase] 구현체(§10-1·§10-2) — 무인증 공개 경로다.
 *
 * 생성자에 `EventScopeGuard`가 없다 — 행사 스코프 게이트를 호출할 수단 자체를 갖지 않는
 * 구조적 설계다(런타임 실수로 호출되는 경로가 없음을 컴파일 타임에 보장). 인가는 token이
 * 전역 UNIQUE(`uk_guest_token`)라는 성질에 의존한다 — token 소지가 곧 단일 guest의 자연
 * 스코프이며, 요청자가 event를 별도로 지정할 여지 자체가 없다. 무효 token은 쓰기·조회 前
 * [NoSuchElementException]으로 즉시 거부한다.
 */
@Service
internal class PublicHubService(
    private val guestPort: GuestPort,
    private val eventPort: EventPort,
    private val guestWriteSupport: GuestWriteSupport,
) : PublicHubUseCase {

    @Transactional(readOnly = true)
    override fun getHub(token: String): PublicHubResult = resolveHub(token)

    @Transactional
    override fun updatePrereg(token: String, plate: String): PublicHubResult {
        val guest = guestPort.findByToken(token) ?: throw NoSuchElementException("유효하지 않은 링크입니다")
        guestWriteSupport.backfillPlate(guest.eventId, guest.toGuest(), plate)
        return resolveHub(token)
    }

    private fun resolveHub(token: String): PublicHubResult {
        val guest = guestPort.findByToken(token) ?: throw NoSuchElementException("유효하지 않은 링크입니다")
        // guest.eventId는 FK(fk_guest_event)로 event 존재가 보장되므로 이 분기는 방어적 코드다
        // (EventService.getEvent와 동일 원칙).
        val event = eventPort.fetch(guest.eventId) ?: throw NoSuchElementException("행사를 찾을 수 없습니다")
        return PublicHubResult(guest, event)
    }
}
