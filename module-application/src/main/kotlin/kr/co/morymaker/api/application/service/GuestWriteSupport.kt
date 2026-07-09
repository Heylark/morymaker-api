package kr.co.morymaker.api.application.service

import kr.co.morymaker.api.application.port.`in`.RegisterGuestCommand
import kr.co.morymaker.api.application.port.out.GuestPort
import kr.co.morymaker.api.application.port.out.ParkingLinkPort
import kr.co.morymaker.api.domain.guest.Guest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * 가드-free 참석자 쓰기 코어(SSOT) — 인증 경로(`GuestService`)와 공개 경로(`PublicHubService`·
 * `PublicOnsiteService`)가 공유하는 등록·차량 백필·주차 지연매칭(§4-10) 로직을 한 곳에 모은다.
 *
 * `EventScopeGuard`를 의도적으로 의존하지 않는다 — 이 클래스는 인가를 판단하지 않고 쓰기만
 * 수행하며, 인가는 항상 호출자 책임이다(인증 경로는 `GuestService`가 `assertAccess`를 먼저
 * 호출한 뒤에만 이 클래스를 호출하고, 공개 경로는 token/eventCode capability 유효성 검증으로
 * 인가를 대체한 뒤에만 호출한다). 생성자에 `EventScopeGuard` 참조 자체가 없으므로 이 클래스
 * 내부에서는 구조적으로 그 가드를 호출할 수 없다.
 */
@Service
internal class GuestWriteSupport(
    private val guestPort: GuestPort,
    private val parkingLinkPort: ParkingLinkPort,
    private val tokenGenerator: GuestTokenGenerator,
) {

    /** 참석자 신규 생성(§4-2) — 토큰 발급 + insert + plate 있으면 즉시 지연매칭 시도. */
    @Transactional
    fun createGuest(eventId: String, command: RegisterGuestCommand): Guest {
        val guest = Guest(
            id = UUID.randomUUID().toString(),
            eventId = eventId,
            name = command.name,
            org = command.org,
            title = command.title,
            phone = command.phone,
            plate = command.plate,
            seatGroupId = command.seatGroupId,
            status = Guest.STATUS_WAITING,
            src = command.src ?: Guest.SRC_ONSITE,
            visitAt = null,
            token = generateUniqueToken(),
            createdAt = Instant.now(),
        )
        guestPort.insert(guest)
        return if (!guest.plate.isNullOrBlank()) mapGuestParking(eventId, guest) else guest
    }

    /** 차량 사전등록/수정(§10-2) — plate 백필 후 지연매칭 재시도. */
    @Transactional
    fun backfillPlate(eventId: String, existing: Guest, plate: String): Guest {
        val merged = existing.with(plate = plate)
        guestPort.update(merged)
        return mapGuestParking(eventId, merged)
    }

    /**
     * 주차↔참석자 역방향 지연매칭(§4-10) — plate 완전일치로 활성 주차기록을 찾아 guest_id를
     * 백필하고, 대기 상태였다면 방문으로 전이한다.
     *
     * 매칭 실패(활성 기록 없음)는 정상 상태이며 아무 것도 갱신하지 않는다 — plate 없는 손님과
     * 동일하게 취급한다.
     */
    fun mapGuestParking(eventId: String, guest: Guest): Guest {
        val plate = guest.plate
        if (plate.isNullOrBlank()) return guest
        val recordId = parkingLinkPort.findActiveRecordIdByPlate(eventId, normalizePlate(plate)) ?: return guest
        parkingLinkPort.linkGuest(recordId, guest.id)
        val updated = if (guest.status == Guest.STATUS_WAITING) {
            guest.with(status = Guest.STATUS_VISITED, visitAt = Instant.now())
        } else {
            guest
        }
        guestPort.update(updated)
        return updated
    }

    fun generateUniqueToken(): String {
        repeat(MAX_TOKEN_RETRY) {
            val candidate = tokenGenerator.generate()
            if (!guestPort.existsByToken(candidate)) return candidate
        }
        throw IllegalStateException("체크인 토큰 생성에 반복 실패했습니다")
    }

    private fun normalizePlate(plate: String): String = plate.replace(" ", "").trim()

    companion object {
        private const val MAX_TOKEN_RETRY = 5
    }
}
