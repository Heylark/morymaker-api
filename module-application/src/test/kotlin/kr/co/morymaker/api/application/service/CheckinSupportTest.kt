package kr.co.morymaker.api.application.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.co.morymaker.api.application.port.`in`.CheckinResult
import kr.co.morymaker.api.application.port.`in`.CheckinTarget
import kr.co.morymaker.api.application.port.out.GuestListItem
import kr.co.morymaker.api.application.port.out.GuestPort
import kr.co.morymaker.api.application.port.out.ParkingLinkPort
import kr.co.morymaker.api.application.port.out.ParkingSlotRef
import kr.co.morymaker.api.domain.guest.Guest
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * [CheckinSupport] 단위 테스트 — 상태 전이(§5-3)·멱등성·조건부 UPDATE 동시성 방어를
 * 검증한다. 가드-free 추출 이전 `CheckinServiceTest`에 있던 동일 assertion을 이전한 것(동작
 * 변경 없음, 검증 자산 이전) — 인증 경로([CheckinService])·공개 kiosk 경로
 * ([PublicKioskService])가 공유하는 SSOT라 이 테스트가 두 경로 공통 동작의 단일 검증 지점이다.
 *
 * `checkin`은 조건부 UPDATE를 먼저 blind로 시도하고 그 다음에만 조회한다(ER_CHECKREAD 회피 —
 * 클래스 KDoc 참조) — 그래서 각 TC는 `markAttendedIfNotAttended*`를 항상 stub하고, 대상 존재
 * 여부와 성공/경쟁패배 여부를 affected rows + 이어지는 조회 결과 조합으로 표현한다.
 *
 * 실 DB 동시 더블탭(N회 물리 독립 스레드) 검증은 Tester 담당 실 DB 통합 테스트 — 여기서는
 * `markAttendedIfNotAttended*`의 반환값(affected rows) 분기만 mock으로 검증한다.
 */
class CheckinSupportTest {

    private val guestPort = mockk<GuestPort>()
    private val parkingLinkPort = mockk<ParkingLinkPort>()
    private val support = CheckinSupport(guestPort, parkingLinkPort)

    private fun sampleGuestListItem(
        id: String = "g1",
        status: String = Guest.STATUS_ATTENDED,
        visitAt: Instant? = Instant.now(),
    ) = GuestListItem(
        id = id,
        eventId = "ev1",
        name = "김진우",
        org = null,
        title = null,
        phone = null,
        plate = null,
        seatGroupId = null,
        status = status,
        src = Guest.SRC_PRE,
        visitAt = visitAt,
        token = "sample-token",
        createdAt = Instant.now(),
        seatLabel = "A-12",
    )

    @Test
    fun `checkin은 조건부 UPDATE가 1행 갱신하면 CHECKED_IN을 반환한다(token 대상)`() {
        every { guestPort.markAttendedIfNotAttendedByToken("ev1", "tok1", any()) } returns 1
        every { guestPort.fetchDetailByToken("ev1", "tok1") } returns sampleGuestListItem()
        every { parkingLinkPort.findActiveSlotByGuestId("ev1", "g1") } returns null

        val result = support.checkin("ev1", CheckinTarget.ByToken("tok1"))

        assertEquals(CheckinResult.CHECKED_IN, result.resultCode)
        verify(exactly = 1) { guestPort.markAttendedIfNotAttendedByToken("ev1", "tok1", any()) }
        verify(exactly = 0) { guestPort.update(any()) }
    }

    @Test
    fun `checkin은 조건부 UPDATE가 1행 갱신하면 CHECKED_IN을 반환한다(guestId 대상)`() {
        every { guestPort.markAttendedIfNotAttended("ev1", "g1", any()) } returns 1
        every { guestPort.fetchDetailById("ev1", "g1") } returns sampleGuestListItem()
        every { parkingLinkPort.findActiveSlotByGuestId("ev1", "g1") } returns null

        val result = support.checkin("ev1", CheckinTarget.ByGuestId("g1"))

        assertEquals(CheckinResult.CHECKED_IN, result.resultCode)
        verify(exactly = 1) { guestPort.markAttendedIfNotAttended("ev1", "g1", any()) }
    }

    @Test
    fun `checkin은 이미 참석 상태(affected 0)면 재변경 없이 ALREADY_CHECKED_IN을 반환한다`() {
        every { guestPort.markAttendedIfNotAttended("ev1", "g1", any()) } returns 0
        every { guestPort.fetchDetailById("ev1", "g1") } returns sampleGuestListItem(status = Guest.STATUS_ATTENDED)
        every { parkingLinkPort.findActiveSlotByGuestId("ev1", "g1") } returns null

        val result = support.checkin("ev1", CheckinTarget.ByGuestId("g1"))

        assertEquals(CheckinResult.ALREADY_CHECKED_IN, result.resultCode)
    }

    @Test
    fun `checkin은 조건부 UPDATE가 경쟁에 패배(affected 0)해도 뒤이은 조회로 ALREADY_CHECKED_IN으로 수렴한다`() {
        // 동시 더블탭에서 경쟁자가 먼저 커밋해 이 트랜잭션의 조건부 UPDATE가 매치 0건인 케이스 —
        // 정확히 1건만 CHECKED_IN이어야 하는 불변식의 핵심 분기.
        every { guestPort.markAttendedIfNotAttended("ev1", "g1", any()) } returns 0
        every { guestPort.fetchDetailById("ev1", "g1") } returns sampleGuestListItem(status = Guest.STATUS_ATTENDED)
        every { parkingLinkPort.findActiveSlotByGuestId("ev1", "g1") } returns null

        val result = support.checkin("ev1", CheckinTarget.ByGuestId("g1"))

        assertEquals(CheckinResult.ALREADY_CHECKED_IN, result.resultCode)
    }

    @Test
    fun `checkin은 대상을 찾지 못하면 NoSuchElementException을 던진다`() {
        every { guestPort.markAttendedIfNotAttendedByToken("ev1", "ghost", any()) } returns 0
        every { guestPort.fetchDetailByToken("ev1", "ghost") } returns null

        assertFailsWith<NoSuchElementException> { support.checkin("ev1", CheckinTarget.ByToken("ghost")) }
    }

    @Test
    fun `checkin은 연결된 활성 주차 슬롯이 있으면 구분자를 정규화해 병기한다`() {
        every { guestPort.markAttendedIfNotAttended("ev1", "g1", any()) } returns 0
        every { guestPort.fetchDetailById("ev1", "g1") } returns sampleGuestListItem(status = Guest.STATUS_ATTENDED)
        every { parkingLinkPort.findActiveSlotByGuestId("ev1", "g1") } returns ParkingSlotRef("지하 2층·A구역·3")

        val result = support.checkin("ev1", CheckinTarget.ByGuestId("g1"))

        assertEquals("지하 2층·A구역·3", result.parking?.slotSig)
        assertEquals("지하 2층 A구역 3", result.parking?.display)
    }

    @Test
    fun `cancelCheckin은 참석을 대기로 되돌리고 visit_at을 초기화한다`() {
        val existing = Guest(
            id = "g1", eventId = "ev1", name = "김진우", org = null, title = null, phone = null,
            plate = null, seatGroupId = null, status = Guest.STATUS_ATTENDED, src = Guest.SRC_PRE,
            visitAt = Instant.now(), token = "sample-token", createdAt = Instant.now(),
        )
        every { guestPort.fetchById("ev1", "g1") } returns existing
        every { guestPort.update(any()) } returns Unit

        val result = support.cancelCheckin("ev1", "g1")

        assertEquals(Guest.STATUS_WAITING, result.status)
        assertNull(result.visitAt)
    }

    @Test
    fun `scanPreview는 조회만 하고 상태를 변경하지 않는다`() {
        val guest = sampleGuestListItem(status = Guest.STATUS_WAITING, visitAt = null)
        every { guestPort.fetchDetailByToken("ev1", "tok1") } returns guest

        val result = support.scanPreview("ev1", "tok1")

        assertEquals(Guest.STATUS_WAITING, result.status)
        verify(exactly = 0) { guestPort.update(any()) }
        verify(exactly = 0) { guestPort.markAttendedIfNotAttended(any(), any(), any()) }
        verify(exactly = 0) { guestPort.markAttendedIfNotAttendedByToken(any(), any(), any()) }
    }
}
