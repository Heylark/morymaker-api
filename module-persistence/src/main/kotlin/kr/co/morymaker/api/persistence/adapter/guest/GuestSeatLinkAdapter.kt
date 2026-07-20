package kr.co.morymaker.api.persistence.adapter.guest

import kr.co.morymaker.api.application.port.out.GuestSeatLinkPort
import kr.co.morymaker.api.persistence.mapper.GuestMapper
import org.springframework.stereotype.Component

/**
 * [GuestSeatLinkPort] 구현체 — MyBatis 매퍼 위임. `GuestLinkAdapter`(parking→guest 방향)의
 * 대칭이다.
 *
 * 헥사고날 레이어: Persistence(adapter). `internal`: application 계층은 [GuestSeatLinkPort]
 * 인터페이스만 의존한다.
 */
@Component
internal class GuestSeatLinkAdapter(
    private val guestMapper: GuestMapper,
) : GuestSeatLinkPort {

    override fun filterExistingIds(eventId: String, guestIds: List<String>): Set<String> {
        // foreach IN절은 빈 리스트면 문법 오류 — 호출 전 가드.
        if (guestIds.isEmpty()) return emptySet()
        return guestMapper.selectExistingIds(eventId, guestIds).toSet()
    }

    override fun updateSeatGroupId(eventId: String, guestIds: List<String>, seatGroupId: String?) {
        if (guestIds.isNotEmpty()) guestMapper.updateSeatGroupId(eventId, guestIds, seatGroupId)
    }
}
