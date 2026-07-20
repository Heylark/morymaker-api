package kr.co.morymaker.api.domain.guest

import java.time.Instant

/**
 * 참석자(VIP) 도메인 엔티티 — 명단 관리·체크인·좌석/주차 매핑의 중심.
 *
 * Event.kt와 동일 원칙: 일반 class + id 기반 equals/hashCode(data class 아님), DB 컬럼 기본값
 * 미러 상수를 companion에 둔다.
 *
 * 상태 전이(대기→방문→참석→취소)는 이 클래스가 캡슐화하지 않는다 — 전이 판정은 서비스 조건
 * 분기가 담당한다(상태머신 엔티티 메서드화는 YAGNI, 02-architect §2 결정).
 *
 * @param id 참석자 PK(UUID)
 * @param eventId 소속 행사 PK — 모든 조회·변경의 격리 기준
 * @param name 이름(필수)
 * @param org 소속
 * @param title 직함
 * @param phone 연락처 — 체크인 SMS/이름검색 보조키로도 쓰인다
 * @param plate 차량번호 — 주차 지연매칭(mapGuestParking) 키
 * @param seatGroupId 좌석 그룹 FK(nullable) — 실좌석 SSOT는 별도 REQ, 여기서는 조회 편의 FK만 보유
 * @param status `대기`/`방문`/`참석`/`취소`
 * @param src `사전`(엑셀/사전 등록) / `현장`(개별/현장 등록)
 * @param visitAt 체크인(참석 확정) 또는 주차 매핑(방문 전이) 시각
 * @param token 체크인 QR/스캔용 추측 불가 토큰(UNIQUE)
 * @param createdAt 생성 시각
 */
class Guest(
    val id: String,
    val eventId: String,
    val name: String,
    val org: String?,
    val title: String?,
    val phone: String?,
    val plate: String?,
    val seatGroupId: String?,
    val status: String,
    val src: String,
    val visitAt: Instant?,
    val token: String,
    val createdAt: Instant,
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Guest) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "Guest(id=$id, name=$name, status=$status)"

    companion object {
        // DB 컬럼 기본값 미러 (V1__init.sql guest.status DEFAULT '대기', src DEFAULT '사전')
        const val STATUS_WAITING = "대기" // 등록 직후
        const val STATUS_VISITED = "방문" // 주차매핑 성공(mapGuestParking)
        const val STATUS_ATTENDED = "참석" // 체크인 확정(SCN/KIO)
        const val STATUS_CANCELLED = "취소" // 명단 제외(보존·검색제외)

        const val SRC_PRE = "사전" // 엑셀/사전 등록
        const val SRC_ONSITE = "현장" // 개별/현장 등록
    }
}
