package kr.co.morymaker.api.persistence.adapter.guest

import kr.co.morymaker.api.application.port.out.GuestListItem
import kr.co.morymaker.api.application.port.out.GuestPort
import kr.co.morymaker.api.application.port.out.GuestSearchQuery
import kr.co.morymaker.api.domain.guest.Guest
import kr.co.morymaker.api.persistence.mapper.GuestMapper
import org.springframework.stereotype.Component

/**
 * [GuestPort] 구현체 — MyBatis 매퍼 위임.
 *
 * 헥사고날 레이어: Persistence(adapter). `internal`: application 계층은 [GuestPort]
 * 인터페이스만 의존한다.
 */
@Component
internal class GuestPersistenceAdapter(
    private val guestMapper: GuestMapper,
) : GuestPort {

    override fun fetchById(eventId: String, gid: String): Guest? = guestMapper.selectById(eventId, gid)

    override fun fetchDetailById(eventId: String, gid: String): GuestListItem? =
        guestMapper.selectDetailById(eventId, gid)

    override fun fetchDetailByToken(eventId: String, token: String): GuestListItem? =
        guestMapper.selectDetailByToken(eventId, token)

    override fun search(eventId: String, query: GuestSearchQuery): List<GuestListItem> =
        guestMapper.search(eventId, query)

    override fun countSearch(eventId: String, query: GuestSearchQuery): Int =
        guestMapper.countSearch(eventId, query)

    override fun existsByToken(token: String): Boolean = guestMapper.existsByToken(token)

    override fun insert(guest: Guest) = guestMapper.insert(guest)

    override fun update(guest: Guest) = guestMapper.update(guest)
}
