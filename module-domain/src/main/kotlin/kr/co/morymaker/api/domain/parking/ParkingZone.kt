package kr.co.morymaker.api.domain.parking

import java.time.Instant

/**
 * 주차 구획 — 개별 자리(slot)는 DB row로 저장하지 않고 `startNo`+`slotCount`로 파생한다(§6-2).
 *
 * Guest.kt·Event.kt와 동일 원칙: 일반 class + id 기반 equals/hashCode(data class 아님), DB 컬럼과
 * 1:1 매핑만 보유한다 — zoneName·outdoor 등 파생 필드는 [ParkingSlot] 파생 규칙 object가 계산한다.
 *
 * @param id 구획 PK(UUID)
 * @param eventId 소속 행사 PK — 모든 조회·변경의 격리 기준
 * @param part1 구분1(자유필드, 등록 시 필수 — outdoor 판정 "야외" 포함 여부의 기준)
 * @param part2 구분2
 * @param part3 구분3
 * @param part4 구분4
 * @param startNo 자리 시작 번호(DEFAULT 1)
 * @param slotCount 자리 개수(물리 slot row 미생성, 논리 파생)
 * @param createdAt 생성 시각
 */
class ParkingZone(
    val id: String,
    val eventId: String,
    val part1: String?,
    val part2: String?,
    val part3: String?,
    val part4: String?,
    val startNo: Int,
    val slotCount: Int,
    val createdAt: Instant,
) {
    // 수정(§6-3) — 새 객체 반환(Guest.with() 패턴).
    fun with(
        part1: String? = this.part1,
        part2: String? = this.part2,
        part3: String? = this.part3,
        part4: String? = this.part4,
        startNo: Int = this.startNo,
        slotCount: Int = this.slotCount,
    ): ParkingZone = ParkingZone(id, eventId, part1, part2, part3, part4, startNo, slotCount, createdAt)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ParkingZone) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "ParkingZone(id=$id, part1=$part1)"
}
