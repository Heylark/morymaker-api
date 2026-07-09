package kr.co.morymaker.api.domain.parking

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [ParkingSlot] 파생 규칙 단위 테스트 — proto/data.js 정합(§6-1·§6-4·§6-5 3개 소비처가 공유하는
 * 단일 진실 소스)을 순수 함수 단위로 검증한다.
 */
class ParkingSlotTest {

    @Test
    fun `zoneName은 빈값을 제외하고 part1~4를 공백으로 결합한다`() {
        assertEquals("지하 2층 A구역", ParkingSlot.zoneName("지하 2층", "A구역", "", null))
        assertEquals("야외 C구역", ParkingSlot.zoneName("야외", "C구역", null, null))
    }

    @Test
    fun `outdoor는 part1에 야외가 포함된 경우만 true다`() {
        assertTrue(ParkingSlot.outdoor("야외"))
        assertTrue(ParkingSlot.outdoor("지상 야외 A"))
        assertFalse(ParkingSlot.outdoor("지하 2층"))
        assertFalse(ParkingSlot.outdoor(null))
    }

    @Test
    fun `slotNo는 시작 번호에 0-based 인덱스를 더한다`() {
        assertEquals(1, ParkingSlot.slotNo(startNo = 1, index = 0))
        assertEquals(8, ParkingSlot.slotNo(startNo = 1, index = 7))
        assertEquals(11, ParkingSlot.slotNo(startNo = 3, index = 8))
    }

    @Test
    fun `slotSig는 part들과 자리 번호를 가운데점으로 결합한다`() {
        assertEquals("지하 2층·A구역·3", ParkingSlot.slotSig("지하 2층", "A구역", "", null, 3))
    }

    @Test
    fun `slotTitle은 override가 있으면 그 값을 없으면 번호 문자열을 반환한다`() {
        assertEquals("귀빈석", ParkingSlot.slotTitle("귀빈석", 3))
        assertEquals("3", ParkingSlot.slotTitle(null, 3))
        assertEquals("3", ParkingSlot.slotTitle("", 3))
    }

    @Test
    fun `slotFullName은 zoneName과 slotTitle을 공백으로 결합한다`() {
        assertEquals("지하 2층 A구역 3", ParkingSlot.slotFullName("지하 2층 A구역", "3"))
        assertEquals("귀빈석", ParkingSlot.slotFullName("", "귀빈석"))
    }

    @Test
    fun `slotDisplay는 사이니지 시그를 한국어 번 표기로 변환한다`() {
        assertEquals("지하 2층 A구역 3번", ParkingSlot.slotDisplay("지하 2층·A구역·3"))
        assertEquals("야외 8번", ParkingSlot.slotDisplay("야외·8"))
    }

    @Test
    fun `slotCode는 zoneId와 2자리 제로패드 slot_no를 하이픈으로 결합한다(spec z1-08 예시 정합)`() {
        assertEquals("z1-08", ParkingSlot.slotCode("z1", 8))
        assertEquals("z1-01", ParkingSlot.slotCode("z1", 1))
    }

    @Test
    fun `slotCode는 slot_no가 99를 넘으면 3자리로 자연 확장된다`() {
        assertEquals("z1-100", ParkingSlot.slotCode("z1", 100))
    }
}
