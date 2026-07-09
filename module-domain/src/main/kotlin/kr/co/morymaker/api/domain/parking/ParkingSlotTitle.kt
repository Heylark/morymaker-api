package kr.co.morymaker.api.domain.parking

/**
 * 자리 타이틀 override 값객체(§6-3) — `parking_slot_title`의 복합 자연키(zone_id, slot_no)를
 * 그대로 반영한다. 개별 수정분만 저장하며, 미지정 자리는 [ParkingSlot.slotTitle]이 번호를
 * 기본 타이틀로 취급한다.
 */
data class ParkingSlotTitle(val zoneId: String, val slotNo: Int, val titleOverride: String)
