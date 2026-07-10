package kr.co.morymaker.api.domain.seat

/**
 * 좌석 배정 — 그룹 1:N, 실좌석 SSOT(§6). numbering ON 그룹에서는 `guestId`가 비어도 번호를 점유한
 * 실좌석 1석을 의미한다(무결성 §b).
 *
 * @param id 배정 PK(UUID)
 * @param eventId 소속 행사 PK — 격리 기준(조회 편의 비정규화)
 * @param seatGroupId 소속 그룹 PK
 * @param ord numbering ON=좌석번호(1..N 연속) / OFF=순서 무의미([ORD_UNNUMBERED])
 * @param guestId 배정 참석자 PK(nullable = 빈 좌석)
 */
class SeatAssignment(
    val id: String,
    val eventId: String,
    val seatGroupId: String,
    val ord: Int,
    val guestId: String?,
) {
    // 상태 전이 — 새 객체 반환(Guest.with() 패턴). ord·guestId만 변경 대상(그룹 소속은 재배정 아닌
    // 원자 교체로 이동한다 — §12-5 M1).
    fun with(
        ord: Int = this.ord,
        guestId: String? = this.guestId,
    ): SeatAssignment = SeatAssignment(id, eventId, seatGroupId, ord, guestId)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SeatAssignment) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "SeatAssignment(id=$id, seatGroupId=$seatGroupId, ord=$ord, guestId=$guestId)"

    companion object {
        // numbering OFF 그룹의 ord는 순서 무의미 — proto data.js 리터럴과 동일한 9999로 고정(무결성 §b).
        const val ORD_UNNUMBERED = 9999
    }
}
