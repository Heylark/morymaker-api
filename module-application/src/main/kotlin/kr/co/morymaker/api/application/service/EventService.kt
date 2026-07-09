package kr.co.morymaker.api.application.service

import kr.co.morymaker.api.application.port.`in`.CreateEventCommand
import kr.co.morymaker.api.application.port.`in`.EventUseCase
import kr.co.morymaker.api.application.port.out.EventPort
import kr.co.morymaker.api.application.security.EventScopeGuard
import kr.co.morymaker.api.domain.event.Event
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * [EventUseCase] 구현체 — Layer2b(서비스 재검증)를 여기서 수행한다.
 *
 * 헥사고날 레이어: application(service). `internal`: api-app은 [EventUseCase] 인터페이스만
 * 의존한다 — 이 클래스를 module-application 외부에서 직접 참조하는 것은 레이어 의존 방향 위반이다.
 */
@Service
internal class EventService(
    private val eventPort: EventPort,
    private val eventScopeGuard: EventScopeGuard,
) : EventUseCase {

    @Transactional(readOnly = true)
    override fun listEvents(): List<Event> {
        // 결과 필터링(assertAccess 아님) — SYSTEM_ADMIN은 null을 받아 전체 조회.
        val scope = eventScopeGuard.currentScopeOrNull()
        return eventPort.search(scope)
    }

    @Transactional(readOnly = true)
    override fun getEvent(eid: String): Event {
        // Layer2b — 인터셉터(Layer2a)를 우회하는 미래의 비-HTTP 호출 경로 방어(방어심층).
        eventScopeGuard.assertAccess(eid)
        return eventPort.fetch(eid) ?: throw NoSuchElementException("행사를 찾을 수 없습니다")
    }

    @Transactional
    override fun createEvent(command: CreateEventCommand): Event {
        val event = Event(
            id = UUID.randomUUID().toString(),
            name = command.name,
            eventDate = command.eventDate,
            place = command.place,
            type = command.type,
            status = Event.STATUS_PREPARING,
            active = false,
            bgColor = command.bgColor,
            pointColor = command.pointColor,
            titleColor = command.titleColor,
            bodyColor = command.bodyColor,
            kv = command.kv,
            smsPolicy = Event.DEFAULT_SMS_POLICY,
            createdAt = Instant.now(),
        )
        eventPort.insert(event)
        return event
    }
}
