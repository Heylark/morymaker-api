package kr.co.morymaker.api.domain.parking

/**
 * [ParkingSlot.parse] 결과 VO — 자리코드에서 역파싱된 (구획 PK, 자리 번호).
 *
 * 순수 값객체(Spring 무의존) — 공개 자리 QR 경로가 slotCode만으로 구획을 찾아 eventId를
 * 역으로 파생하기 위한 중간 표현이다.
 */
data class SlotCodeRef(val zoneId: String, val slotNo: Int)
