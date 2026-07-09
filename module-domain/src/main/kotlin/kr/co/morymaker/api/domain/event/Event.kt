package kr.co.morymaker.api.domain.event

import java.time.Instant

/**
 * 행사 도메인 엔티티 — 모든 참석확인·좌석·주차 데이터의 최상위 격리 단위.
 *
 * 일반 class + id 기반 equals(data class 아님): 다른 필드가 갱신 도중 달라도 같은 id면
 * 여전히 "같은 행사"로 취급해야 한다(auth `Account` 도메인과 동일한 설계 결정).
 *
 * @param id 행사 PK(UUID). 생성 시 서비스가 발급한다.
 * @param name 행사명 (필수)
 * @param eventDate 행사 일시 (미정 상태를 허용하기 위해 nullable)
 * @param place 장소
 * @param type 행사 타입 라벨(연회식/극장식 지정석/극장식 자유석) — 분류용일 뿐 좌석 구성 자체를 결정하지 않는다
 * @param status `준비`/`운영중`/`종료`
 * @param active 키오스크·공개 화면이 참조하는 현재 노출 행사 플래그
 * @param bgColor 배경색 `#RRGGBB`
 * @param pointColor 포인트색 `#RRGGBB`
 * @param titleColor 제목 글자색
 * @param bodyColor 본문 글자색
 * @param kv 키비주얼 텍스트/식별자
 * @param smsPolicy 문자 정책 표기
 * @param createdAt 생성 시각
 */
class Event(
    val id: String,
    val name: String,
    val eventDate: Instant?,
    val place: String?,
    val type: String?,
    val status: String,
    val active: Boolean,
    val bgColor: String?,
    val pointColor: String?,
    val titleColor: String?,
    val bodyColor: String?,
    val kv: String?,
    val smsPolicy: String?,
    val createdAt: Instant,
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Event) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "Event(id=$id, name=$name, status=$status, active=$active)"

    companion object {
        /** 신규 생성 행사의 기본 상태(DB 컬럼 기본값과 동일 — V1__init.sql `status` DEFAULT). */
        const val STATUS_PREPARING = "준비"

        /** 종료된 행사 — 현장등록 status 게이트(공개 경로)가 이 상태만 거부한다. */
        const val STATUS_CLOSED = "종료"

        /** 신규 생성 행사의 기본 문자 정책 표기(DB 컬럼 기본값과 동일 — V1__init.sql `sms_policy` DEFAULT). */
        const val DEFAULT_SMS_POLICY = "초대 1회만 (기타 미발송)"
    }
}
