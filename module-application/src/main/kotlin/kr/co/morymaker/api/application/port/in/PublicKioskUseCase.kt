package kr.co.morymaker.api.application.port.`in`

import kr.co.morymaker.api.domain.parking.ParkingRecord

/**
 * kiosk 공개 조회 유스케이스 포트-in(KIO-02·04·05) — api-app의 `PublicKioskController`가
 * 호출한다. 무인증 공개 표면이라 인가는 eid capability(`fetchOpenEvent` 게이트) + 최소필드
 * 응답이 대체한다 — `EventScopeGuard`는 관여하지 않는다.
 *
 * 반환 타입은 신규 정의하지 않고 기존 [LookupUseCase]·[CheckinUseCase]의 [LookupResult]·
 * [CheckinResult]를 재사용한다(최소필드 응답 변환은 api-app dto 레이어의 별도 확장 함수가
 * 담당 — 이 포트 자체는 SCN 조회와 동일 shape을 반환).
 */
interface PublicKioskUseCase {

    /** KIO-02 이름검색 — name은 trim 후 2자 이상 필수(단일 문자 대량 열거 차단). */
    fun searchAttendees(eventId: String, name: String): LookupResult

    /** KIO-04 체크인 — guestId 기반. 이미 참석이면 멱등 재조회. */
    fun checkin(eventId: String, guestId: String): CheckinResult

    /** KIO-05 주차검색 — plateTail은 정확히 4자리 숫자 필수. 활성(주차중) 기록만. */
    fun searchParking(eventId: String, plateTail: String): List<ParkingRecord>
}
