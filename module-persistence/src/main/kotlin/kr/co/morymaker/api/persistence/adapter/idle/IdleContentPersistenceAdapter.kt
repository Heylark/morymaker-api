package kr.co.morymaker.api.persistence.adapter.idle

import kr.co.morymaker.api.application.port.out.IdleContentPort
import kr.co.morymaker.api.domain.idle.IdleContent
import kr.co.morymaker.api.persistence.mapper.IdleContentMapper
import org.springframework.stereotype.Component

/**
 * [IdleContentPort] 구현체 — MyBatis 매퍼 위임.
 *
 * 헥사고날 레이어: Persistence(adapter). `internal`: application 계층은 [IdleContentPort]
 * 인터페이스만 의존한다 — 이 클래스를 module-persistence 외부에서 직접 참조하는 것은 레이어
 * 의존 방향 위반이다.
 */
@Component
internal class IdleContentPersistenceAdapter(
    private val idleContentMapper: IdleContentMapper,
) : IdleContentPort {

    override fun findByEvent(eventId: String): List<IdleContent> = idleContentMapper.findByEvent(eventId)

    override fun fetchById(eventId: String, id: String): IdleContent? = idleContentMapper.fetchById(eventId, id)

    override fun insert(content: IdleContent) = idleContentMapper.insert(content)

    override fun update(content: IdleContent) = idleContentMapper.update(content)
}
