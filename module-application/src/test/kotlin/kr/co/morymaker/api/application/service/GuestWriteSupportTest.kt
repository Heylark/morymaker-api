package kr.co.morymaker.api.application.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.co.morymaker.api.application.port.`in`.RegisterGuestCommand
import kr.co.morymaker.api.application.port.out.GuestPort
import kr.co.morymaker.api.application.port.out.ParkingLinkPort
import kr.co.morymaker.api.domain.guest.Guest
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.reflect.full.primaryConstructor
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [GuestWriteSupport] 단위 테스트 — 가드-free 쓰기 코어의 신규 생성·차량 백필·주차 지연매칭
 * (§4-10)·토큰 발급 로직을 검증한다. 인증 경로([GuestService])·공개 경로([PublicHubService]·
 * [PublicOnsiteService])가 공유하는 SSOT이므로, 이 테스트가 두 경로 공통 동작의 단일 검증
 * 지점이다.
 */
class GuestWriteSupportTest {

    private val guestPort = mockk<GuestPort>()
    private val parkingLinkPort = mockk<ParkingLinkPort>()
    private val support = GuestWriteSupport(guestPort, parkingLinkPort, GuestTokenGenerator())

    private fun sampleGuest(
        id: String = "g1",
        plate: String? = null,
        status: String = Guest.STATUS_WAITING,
    ) = Guest(
        id = id,
        eventId = "ev1",
        name = "김진우",
        org = null,
        title = null,
        phone = null,
        plate = plate,
        seatGroupId = null,
        status = status,
        src = Guest.SRC_PRE,
        visitAt = null,
        token = "sample-token",
        createdAt = Instant.now(),
    )

    // ── createGuest ────────────────────────────────────────────────

    @Test
    fun `createGuest는 대기 상태·현장 등록 기본값으로 저장하고 plate 없으면 주차매핑을 시도하지 않는다`() {
        every { guestPort.existsByToken(any()) } returns false
        every { guestPort.insert(any()) } returns Unit

        val command = RegisterGuestCommand(
            name = "김진우", org = "모리메이커", title = "대표", phone = "010-1111-2222",
            plate = null, seatGroupId = null, src = null,
        )
        val result = support.createGuest("ev1", command)

        assertEquals(Guest.STATUS_WAITING, result.status)
        assertEquals(Guest.SRC_ONSITE, result.src)
        assertEquals("김진우", result.name)
        verify(exactly = 1) { guestPort.insert(any()) }
        verify(exactly = 0) { parkingLinkPort.findActiveRecordIdByPlate(any(), any()) }
    }

    @Test
    fun `createGuest는 plate가 매칭되면 방문 상태로 전이하고 parking_record를 백필한다`() {
        every { guestPort.existsByToken(any()) } returns false
        every { guestPort.insert(any()) } returns Unit
        every { parkingLinkPort.findActiveRecordIdByPlate("ev1", "123가4568") } returns "record-1"
        every { parkingLinkPort.linkGuest("record-1", any()) } returns Unit
        every { guestPort.update(any()) } returns Unit

        val command = RegisterGuestCommand(
            name = "박서연", org = null, title = null, phone = null,
            plate = "123가 4568", seatGroupId = null, src = "사전",
        )
        val result = support.createGuest("ev1", command)

        assertEquals(Guest.STATUS_VISITED, result.status)
        assertNotNull(result.visitAt)
        verify(exactly = 1) { parkingLinkPort.linkGuest("record-1", result.id) }
        verify(exactly = 1) { guestPort.update(any()) }
    }

    @Test
    fun `createGuest는 plate 매칭 실패 시 대기 상태를 유지하고 update를 호출하지 않는다`() {
        every { guestPort.existsByToken(any()) } returns false
        every { guestPort.insert(any()) } returns Unit
        every { parkingLinkPort.findActiveRecordIdByPlate("ev1", any()) } returns null

        val command = RegisterGuestCommand(
            name = "이도현", org = null, title = null, phone = null,
            plate = "999나9999", seatGroupId = null, src = null,
        )
        val result = support.createGuest("ev1", command)

        assertEquals(Guest.STATUS_WAITING, result.status)
        verify(exactly = 0) { guestPort.update(any()) }
    }

    @Test
    fun `createGuest는 토큰 충돌 시 재생성해 유일한 토큰을 발급한다`() {
        every { guestPort.existsByToken(any()) } returnsMany listOf(true, false)
        every { guestPort.insert(any()) } returns Unit

        val command = RegisterGuestCommand(
            name = "정하은", org = null, title = null, phone = null,
            plate = null, seatGroupId = null, src = null,
        )
        val result = support.createGuest("ev1", command)

        assertTrue(result.token.isNotBlank())
        verify(exactly = 2) { guestPort.existsByToken(any()) }
    }

    // ── backfillPlate(§10-2 공개 경로) ─────────────────────────────

    @Test
    fun `backfillPlate는 필드를 먼저 저장한 뒤 매칭되면 방문 상태로 전이한다`() {
        val existing = sampleGuest(plate = null, status = Guest.STATUS_WAITING)
        every { guestPort.update(any()) } returns Unit
        every { parkingLinkPort.findActiveRecordIdByPlate("ev1", "12가3456") } returns "record-2"
        every { parkingLinkPort.linkGuest("record-2", "g1") } returns Unit

        val result = support.backfillPlate("ev1", existing, "12가3456")

        assertEquals("12가3456", result.plate)
        assertEquals(Guest.STATUS_VISITED, result.status)
        // 1) 필드 저장(plate 백필) 2) 매칭 성공 후 상태 전이 저장 — 총 2회.
        verify(exactly = 2) { guestPort.update(any()) }
    }

    @Test
    fun `backfillPlate는 매칭 실패 시 plate만 백필하고 대기 상태를 유지한다`() {
        val existing = sampleGuest(plate = null, status = Guest.STATUS_WAITING)
        every { guestPort.update(any()) } returns Unit
        every { parkingLinkPort.findActiveRecordIdByPlate("ev1", any()) } returns null

        val result = support.backfillPlate("ev1", existing, "99나9999")

        assertEquals("99나9999", result.plate)
        assertEquals(Guest.STATUS_WAITING, result.status)
        verify(exactly = 1) { guestPort.update(any()) }
    }

    // ── 구조적 무인증 보장 ────────────────────────────────────────

    @Test
    fun `GuestWriteSupport는 EventScopeGuard에 의존하지 않는다(구조적으로 assertAccess 호출 불가)`() {
        val constructorParamTypes = GuestWriteSupport::class.primaryConstructor
            ?.parameters
            ?.map { it.type.toString() }
            .orEmpty()

        assertFalse(
            constructorParamTypes.any { it.contains("EventScopeGuard") },
            "GuestWriteSupport 생성자에 EventScopeGuard가 있으면 안 된다 — " +
                "공개 경로 재사용 시 실수로 assertAccess를 호출할 여지가 생긴다: $constructorParamTypes",
        )
    }
}
