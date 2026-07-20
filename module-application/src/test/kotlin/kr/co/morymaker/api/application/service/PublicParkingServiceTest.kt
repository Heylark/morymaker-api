package kr.co.morymaker.api.application.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kr.co.morymaker.api.application.port.`in`.MappingResult
import kr.co.morymaker.api.application.port.`in`.PublicSlotView
import kr.co.morymaker.api.application.port.`in`.RegisterParkingCommand
import kr.co.morymaker.api.application.port.`in`.RegisterParkingResult
import kr.co.morymaker.api.application.port.`in`.SelfParkCommand
import kr.co.morymaker.api.application.port.out.EventPort
import kr.co.morymaker.api.application.port.out.GuestListItem
import kr.co.morymaker.api.application.port.out.GuestPort
import kr.co.morymaker.api.application.port.out.ParkingRecordPort
import kr.co.morymaker.api.application.port.out.ParkingZonePort
import kr.co.morymaker.api.domain.event.Event
import kr.co.morymaker.api.domain.parking.ParkingRecord
import kr.co.morymaker.api.domain.parking.ParkingZone
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [PublicParkingService] 단위 테스트 — slotCode 역파싱·viewType 2분기(순수 공개 모델)·
 * 셀프 등록 위임(zoneId/slotSig 재계산 + registeredBy 고정)·token 프리필을 mock으로 검증한다.
 *
 * 승계 코어 자체(무결성 3-5)는 [ParkingWriteSupportTest]가 이미 검증하므로 여기서는
 * [ParkingWriteSupport]를 mock으로 대체해 위임 인자 조립만 확인한다.
 */
class PublicParkingServiceTest {

    private val zonePort = mockk<ParkingZonePort>()
    private val recordPort = mockk<ParkingRecordPort>()
    private val eventPort = mockk<EventPort>()
    private val guestPort = mockk<GuestPort>()
    private val parkingWriteSupport = mockk<ParkingWriteSupport>()
    private val service = PublicParkingService(zonePort, recordPort, eventPort, guestPort, parkingWriteSupport)

    private fun sampleZone(
        id: String = "z-uuid-1",
        eventId: String = "ev1",
        startNo: Int = 1,
        slotCount: Int = 12,
    ) = ParkingZone(
        id = id, eventId = eventId, part1 = "지하 2층", part2 = "A구역", part3 = null, part4 = null,
        startNo = startNo, slotCount = slotCount, createdAt = Instant.now(),
    )

    private fun sampleEvent(id: String = "ev1") = Event(
        id = id, name = "2026 신년 VIP 만찬", eventDate = null, place = null, type = null,
        status = Event.STATUS_PREPARING, active = true, bgColor = "#0c1322", pointColor = "#c9a24a",
        titleColor = null, bodyColor = null, kv = null, smsPolicy = null, createdAt = Instant.now(),
    )

    private fun sampleRecord(plate: String = "12가3456") = ParkingRecord(
        id = "r1", eventId = "ev1", zoneId = "z-uuid-1", slotSig = "지하 2층·A구역·8", plate = plate,
        phone = null, vipName = null, guestId = null, registeredBy = ParkingRecord.REGISTERED_BY_SELF,
        registeredAt = Instant.now(), status = ParkingRecord.STATUS_PARKED, reviewNeeded = false,
    )

    private fun sampleGuest(token: String = "t1004") = GuestListItem(
        id = "g1", eventId = "ev1", name = "최지우", org = null, title = null, phone = "010-9999-8888",
        plate = null, seatGroupId = null, status = "대기", src = "사전", visitAt = null,
        token = token, createdAt = Instant.now(), seatLabel = null,
    )

    // ── getSlotView — viewType 2분기(순수 공개 모델) ───────────────────────────

    @Test
    fun `getSlotView는 빈 자리면 SELF_PARK_FORM을 반환한다`() {
        every { zonePort.findById("z-uuid-1") } returns sampleZone()
        every { eventPort.fetch("ev1") } returns sampleEvent()
        every { recordPort.selectActiveBySlot("ev1", "지하 2층·A구역·8") } returns null

        val result = service.getSlotView("z-uuid-1-08")

        assertEquals(PublicSlotView.VIEW_TYPE_SELF_PARK_FORM, result.viewType)
        assertFalse(result.occupied)
        assertEquals("지하 2층·A구역·8", result.slotSig)
    }

    @Test
    fun `getSlotView는 주차중이면 OCCUPIED_NOTICE를 반환한다`() {
        every { zonePort.findById("z-uuid-1") } returns sampleZone()
        every { eventPort.fetch("ev1") } returns sampleEvent()
        every { recordPort.selectActiveBySlot("ev1", "지하 2층·A구역·8") } returns sampleRecord()

        val result = service.getSlotView("z-uuid-1-08")

        assertEquals(PublicSlotView.VIEW_TYPE_OCCUPIED_NOTICE, result.viewType)
        assertTrue(result.occupied)
    }

    @Test
    fun `getSlotView는 slotCode 형식이 무효면 NoSuchElementException을 던진다`() {
        assertFailsWith<NoSuchElementException> { service.getSlotView("nohyphencode") }
    }

    @Test
    fun `getSlotView는 구획이 존재하지 않으면 NoSuchElementException을 던진다`() {
        every { zonePort.findById("ghost-zone") } returns null

        assertFailsWith<NoSuchElementException> { service.getSlotView("ghost-zone-01") }
    }

    @Test
    fun `getSlotView는 자리 번호가 구획 범위 밖이면 NoSuchElementException을 던진다`() {
        every { zonePort.findById("z-uuid-1") } returns sampleZone(startNo = 1, slotCount = 12)

        assertFailsWith<NoSuchElementException> { service.getSlotView("z-uuid-1-13") }
    }

    // ── selfPark — 등록 위임 인자 조립 ───────────────────────────────

    @Test
    fun `selfPark는 zoneId·slotSig를 재계산하고 registeredBy를 셀프로 고정해 위임한다`() {
        every { zonePort.findById("z-uuid-1") } returns sampleZone()
        val expected = RegisterParkingResult(
            result = RegisterParkingResult.RESULT_PARKED, record = sampleRecord(),
            mapping = MappingResult(matched = false), supersededRecord = null, message = null,
        )
        every {
            parkingWriteSupport.register(
                "ev1",
                RegisterParkingCommand(
                    slotSig = "지하 2층·A구역·8", zoneId = "z-uuid-1", plate = "12가3456",
                    phone = null, vipName = null, registeredBy = ParkingRecord.REGISTERED_BY_SELF,
                ),
            )
        } returns expected

        val result = service.selfPark("z-uuid-1-08", SelfParkCommand(plate = "12가3456"))

        assertEquals(expected, result)
    }

    @Test
    fun `selfPark는 token이 있으면 미입력 필드만 참석자 정보로 보강한다`() {
        every { zonePort.findById("z-uuid-1") } returns sampleZone()
        every { guestPort.findByToken("t1004") } returns sampleGuest()
        val captured = slot<RegisterParkingCommand>()
        every { parkingWriteSupport.register("ev1", capture(captured)) } returns RegisterParkingResult(
            result = RegisterParkingResult.RESULT_PARKED, record = sampleRecord(),
            mapping = MappingResult(matched = false), supersededRecord = null, message = null,
        )

        service.selfPark("z-uuid-1-08", SelfParkCommand(plate = "90마3456", vipName = null, phone = null, token = "t1004"))

        assertEquals("최지우", captured.captured.vipName)
        assertEquals("010-9999-8888", captured.captured.phone)
    }

    @Test
    fun `selfPark는 token이 있어도 이미 입력된 필드는 덮어쓰지 않는다`() {
        every { zonePort.findById("z-uuid-1") } returns sampleZone()
        every { guestPort.findByToken("t1004") } returns sampleGuest()
        val captured = slot<RegisterParkingCommand>()
        every { parkingWriteSupport.register("ev1", capture(captured)) } returns RegisterParkingResult(
            result = RegisterParkingResult.RESULT_PARKED, record = sampleRecord(),
            mapping = MappingResult(matched = false), supersededRecord = null, message = null,
        )

        service.selfPark(
            "z-uuid-1-08",
            SelfParkCommand(plate = "90마3456", vipName = "직접입력", phone = "010-1111-2222", token = "t1004"),
        )

        assertEquals("직접입력", captured.captured.vipName)
        assertEquals("010-1111-2222", captured.captured.phone)
    }

    @Test
    fun `selfPark는 token이 무효(참석자 없음)면 프리필 없이 입력값 그대로 위임한다`() {
        every { zonePort.findById("z-uuid-1") } returns sampleZone()
        every { guestPort.findByToken("ghost-token") } returns null
        val captured = slot<RegisterParkingCommand>()
        every { parkingWriteSupport.register("ev1", capture(captured)) } returns RegisterParkingResult(
            result = RegisterParkingResult.RESULT_PARKED, record = sampleRecord(),
            mapping = MappingResult(matched = false), supersededRecord = null, message = null,
        )

        service.selfPark("z-uuid-1-08", SelfParkCommand(plate = "90마3456", token = "ghost-token"))

        assertEquals(null, captured.captured.vipName)
        assertEquals(null, captured.captured.phone)
    }
}
