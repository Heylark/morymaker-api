package kr.co.morymaker.api.domain.parking

import java.time.Instant

/**
 * 주차 기록 — 등록(§6-6, 무결성 3-5 승계)·출차(§6-7)·승계 확인 해제(§6-8)의 중심 엔티티.
 *
 * `active_key`(DB 생성 컬럼 — `status='주차중'`일 때만 `event_id|slot_sig` 값, UNIQUE)는 도메인
 * 필드가 아니다 — DB 내부 무결성 장치라 이 클래스·BaseResultMap·insert/update 어디에도 읽거나
 * 쓰지 않는다.
 *
 * @param id 기록 PK(UUID)
 * @param eventId 소속 행사 PK — 격리 기준
 * @param zoneId 소속 구획 PK
 * @param slotSig 자리 시그(파생값, "지하 2층·A구역·3") — 조회 편의를 위해 중복 저장
 * @param plate 차량번호(정규화 저장 — 공백 제거)
 * @param phone 연락처
 * @param vipName VIP 이름(표기용)
 * @param guestId 매핑 성공 시 연결된 참석자 PK(nullable)
 * @param registeredBy `셀프` / `요원`
 * @param registeredAt 등록 시각
 * @param status `주차중` / `출차`
 * @param reviewNeeded 승계 발생으로 요원 확인이 필요한지(§6-8로 해제)
 */
class ParkingRecord(
    val id: String,
    val eventId: String,
    val zoneId: String,
    val slotSig: String,
    val plate: String,
    val phone: String?,
    val vipName: String?,
    val guestId: String?,
    val registeredBy: String,
    val registeredAt: Instant,
    val status: String,
    val reviewNeeded: Boolean,
) {
    // 상태 전이 — 새 객체 반환(Guest.with() 패턴).
    fun with(
        slotSig: String = this.slotSig,
        zoneId: String = this.zoneId,
        guestId: String? = this.guestId,
        status: String = this.status,
        reviewNeeded: Boolean = this.reviewNeeded,
    ): ParkingRecord = ParkingRecord(
        id, eventId, zoneId, slotSig, plate, phone, vipName, guestId, registeredBy, registeredAt, status, reviewNeeded,
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ParkingRecord) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "ParkingRecord(id=$id, slotSig=$slotSig, status=$status)"

    companion object {
        // DB 컬럼 기본값 미러(V1__init.sql parking_record.status DEFAULT '주차중')
        const val STATUS_PARKED = "주차중"
        const val STATUS_CHECKED_OUT = "출차"

        const val REGISTERED_BY_SELF = "셀프"
        const val REGISTERED_BY_STAFF = "요원"
    }
}
