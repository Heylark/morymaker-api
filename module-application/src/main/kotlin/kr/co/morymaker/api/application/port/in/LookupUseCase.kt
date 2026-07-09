package kr.co.morymaker.api.application.port.`in`

import kr.co.morymaker.api.application.port.out.GuestListItem

/**
 * 실행자 통합 조회(Lookup) 유스케이스 포트-in(§9) — api-app의 `LookupController`가 호출한다.
 *
 * 명단 CRUD([GuestUseCase])·체크인([CheckinUseCase])과 분리하는 근거는 조회 전용(읽기 전용
 * 트랜잭션)이며, 안내데스크 실행자(STAFF)까지 호출하는 조회 API라 인가 표면도 다르기 때문이다.
 * 별도 도메인 상태 변경이 없어 GuestPort·ParkingLinkPort만 재사용하고 별도 Port-out은 두지 않는다.
 */
interface LookupUseCase {

    /** 이름 부분일치 ∪ 차량 뒷자리 매칭 통합 검색(§9-1). q는 필수(blank면 예외). */
    fun lookup(eventId: String, q: String): LookupResult
}

/**
 * [LookupUseCase.lookup] 결과.
 *
 * @param searchState 3상태(§9-1) — [GuestListResult.SEARCH_STATE_*] 상수 재사용(신규 상수 정의 금지).
 */
data class LookupResult(
    val items: List<LookupItem>,
    val total: Int,
    val searchState: String,
)

/** 검색 결과 1건 — 명단 정보 + 주차 병기(매핑 없으면 null). [ParkingView]는 CheckinUseCase.kt 재사용. */
data class LookupItem(val guest: GuestListItem, val parking: ParkingView?)
