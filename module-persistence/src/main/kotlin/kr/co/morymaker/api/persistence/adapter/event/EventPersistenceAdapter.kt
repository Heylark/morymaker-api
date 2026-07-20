package kr.co.morymaker.api.persistence.adapter.event

import kr.co.morymaker.api.application.port.out.EventPort
import kr.co.morymaker.api.domain.event.Event
import kr.co.morymaker.api.persistence.mapper.EventMapper
import org.springframework.stereotype.Component

/**
 * [EventPort] 구현체 — MyBatis 매퍼 위임.
 *
 * 헥사고날 레이어: Persistence(adapter). `internal`: application 계층은 [EventPort] 인터페이스만
 * 의존한다 — 이 클래스를 module-persistence 외부에서 직접 참조하는 것은 레이어 의존 방향 위반이다.
 */
@Component
internal class EventPersistenceAdapter(
    private val eventMapper: EventMapper,
) : EventPort {

    override fun fetch(id: String): Event? = eventMapper.selectById(id)

    override fun search(eventIds: List<String>?): List<Event> = eventMapper.search(eventIds)

    override fun insert(event: Event) = eventMapper.insert(event)

    override fun update(event: Event) = eventMapper.update(event)

    override fun updateBranding(event: Event) = eventMapper.updateBranding(event)
}
