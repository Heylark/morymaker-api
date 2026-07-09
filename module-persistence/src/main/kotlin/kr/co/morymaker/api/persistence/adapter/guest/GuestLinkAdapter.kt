package kr.co.morymaker.api.persistence.adapter.guest

import kr.co.morymaker.api.application.port.out.GuestLink
import kr.co.morymaker.api.application.port.out.GuestLinkPort
import kr.co.morymaker.api.persistence.mapper.GuestMapper
import org.springframework.stereotype.Component

/**
 * [GuestLinkPort] 구현체 — MyBatis 매퍼 위임 + DB 언어(Guest) → 포트 타입([GuestLink]) 번역
 * (ACL, mybatis-advanced.md §4). `ParkingLinkAdapter`(guest→parking 방향, 동결)의 대칭이다.
 *
 * 헥사고날 레이어: Persistence(adapter). `internal`: application 계층은 [GuestLinkPort]
 * 인터페이스만 의존한다.
 */
@Component
internal class GuestLinkAdapter(
    private val guestMapper: GuestMapper,
) : GuestLinkPort {

    override fun findGuestByPlateOrPhone(eventId: String, plate: String, phone: String?): GuestLink? {
        val matched = guestMapper.selectActiveByPlate(eventId, plate)
            ?: phone?.takeIf { it.isNotBlank() }?.let { guestMapper.selectActiveByPhone(eventId, it) }
        return matched?.let { GuestLink(guestId = it.id, guestName = it.name, guestStatus = it.status) }
    }

    override fun markVisitedAndBackfillPlate(guestId: String, plate: String) {
        guestMapper.markVisitedIfWaiting(guestId)
        guestMapper.backfillPlateIfEmpty(guestId, plate)
    }
}
