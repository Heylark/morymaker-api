package kr.co.morymaker.api.application.port.`in`

import kr.co.morymaker.api.application.port.out.GuestListItem
import kr.co.morymaker.api.domain.event.Event

/**
 * 개인 허브 유스케이스 포트-in(§10-1·§10-2) — api-app의 `PublicHubController`가 호출한다.
 *
 * 무인증 공개 경로다 — `EventScopeGuard.assertAccess`를 호출하지 않는다. 인가는 token
 * capability 유효성 검증으로 대체한다: 무효 token은 두 메서드 모두 [NoSuchElementException]으로
 * 거부한다(api-app의 `GlobalExceptionHandler`가 404로 변환, enumeration 방지 — 존재 유무를
 * 다른 응답으로 노출하지 않는다).
 */
interface PublicHubUseCase {

    /** 개인 허브 조회(§10-1) — read-only, 체크인 부작용 0(링크 오픈 ≠ 체크인, §5-1만 참석 확정). */
    fun getHub(token: String): PublicHubResult

    /** 차량 사전등록/수정(§10-2) — token scope 내 본인 plate만 백필한다(타 guest 조작 구조적 불가). */
    fun updatePrereg(token: String, plate: String): PublicHubResult
}

/**
 * [PublicHubUseCase] 조회/갱신 결과 — URL 조립(체크인 QR·주차 진입 스캔 URL)은 api-app이
 * `PublicProperties` 설정값을 더해 응답 DTO로 완성한다(이 레이어는 URL 조립 관심사를 갖지 않는다).
 */
data class PublicHubResult(val guest: GuestListItem, val event: Event)
