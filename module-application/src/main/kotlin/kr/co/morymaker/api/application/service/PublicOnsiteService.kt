package kr.co.morymaker.api.application.service

import kr.co.morymaker.api.application.port.`in`.OnsiteRegisterCommand
import kr.co.morymaker.api.application.port.`in`.PublicOnsiteUseCase
import kr.co.morymaker.api.application.port.`in`.RegisterGuestCommand
import kr.co.morymaker.api.application.port.out.EventPort
import kr.co.morymaker.api.domain.event.Event
import kr.co.morymaker.api.domain.guest.Guest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * [PublicOnsiteUseCase] 구현체(§10-5·§10-6) — 무인증 공개 경로다.
 *
 * 생성자에 `EventScopeGuard`가 없다 — [PublicHubService]와 동일한 구조적 설계로, 행사 스코프
 * 게이트를 호출할 수단 자체가 없다. eventCode(=event.id를 그대로 재사용)의 존재·상태만으로
 * 인가를 대체한다: 무효 eventCode는 [NoSuchElementException](404), 종료 행사는
 * [EventNotOpenException](409)으로 거부한다.
 */
@Service
internal class PublicOnsiteService(
    private val eventPort: EventPort,
    private val guestWriteSupport: GuestWriteSupport,
) : PublicOnsiteUseCase {

    @Transactional(readOnly = true)
    override fun getOnsiteForm(eventCode: String): Event = fetchOpenEvent(eventCode)

    @Transactional
    override fun registerOnsite(eventCode: String, command: OnsiteRegisterCommand): Guest {
        val event = fetchOpenEvent(eventCode)
        val registerCommand = RegisterGuestCommand(
            name = command.name,
            org = command.org,
            title = null,
            phone = command.phone,
            plate = command.plate,
            seatGroupId = null,
            src = Guest.SRC_ONSITE,
        )
        return guestWriteSupport.createGuest(event.id, registerCommand)
    }

    /** eventCode(=event.id) 존재 확인 + status 게이트(종료만 거부) — 폼 조회·등록 공통. */
    private fun fetchOpenEvent(eventCode: String): Event {
        val event = eventPort.fetch(eventCode) ?: throw NoSuchElementException("행사를 찾을 수 없습니다")
        if (event.status == Event.STATUS_CLOSED) {
            throw EventNotOpenException("종료된 행사에는 현장 등록할 수 없습니다")
        }
        return event
    }
}
