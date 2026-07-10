package kr.co.morymaker.api.persistence.adapter.seat

import kr.co.morymaker.api.application.port.out.SeatGroupPort
import kr.co.morymaker.api.domain.seat.SeatGroup
import kr.co.morymaker.api.persistence.mapper.SeatGroupMapper
import org.springframework.stereotype.Component

/**
 * [SeatGroupPort] 구현체 — MyBatis 매퍼 위임.
 *
 * 헥사고날 레이어: Persistence(adapter). `internal`: application 계층은 [SeatGroupPort]
 * 인터페이스만 의존한다.
 */
@Component
internal class SeatGroupPersistenceAdapter(
    private val groupMapper: SeatGroupMapper,
) : SeatGroupPort {

    override fun findByEvent(eventId: String): List<SeatGroup> = groupMapper.findByEvent(eventId)

    override fun fetchById(eventId: String, id: String): SeatGroup? = groupMapper.fetchById(eventId, id)

    override fun fetchByGroupNo(eventId: String, groupNo: Int): SeatGroup? = groupMapper.fetchByGroupNo(eventId, groupNo)

    override fun nextGroupNo(eventId: String): Int = groupMapper.nextGroupNo(eventId)

    override fun nextSortOrder(eventId: String): Int = groupMapper.nextSortOrder(eventId)

    override fun insert(group: SeatGroup) = groupMapper.insert(group)

    override fun update(group: SeatGroup) = groupMapper.update(group)

    override fun delete(eventId: String, id: String) = groupMapper.delete(eventId, id)
}
