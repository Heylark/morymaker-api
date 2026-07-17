package kr.co.morymaker.api.application.port.`in`

import java.nio.file.Path

/**
 * 대기화면 콘텐츠 키오스크 공개 조회 유스케이스 포트-in(§11-2, M3) — api-app의
 * `PublicIdleContentController`가 호출한다.
 *
 * 무인증 공개 경로다 — `EventScopeGuard.assertAccess`를 호출하지 않는다([PublicOnsiteUseCase]·
 * `PublicHubUseCase`와 동일한 구조적 설계, 생성자에 EventScopeGuard 자체가 없다). 격리는
 * `event_id` WHERE 필터로만 제공한다 — 존재하지 않는 eid도 빈 배열(fail-open)로 응답한다.
 */
interface PublicIdleContentUseCase {

    /** 키오스크 대기화면 재생 목록(§11-2) — sortOrder 순, 존재하지 않는 eid는 빈 배열. */
    fun listForKiosk(eventId: String): List<IdleContentView>

    /**
     * 키오스크 미디어 서빙(§11-2, 신설) — 무인증 표면 전용이라 assertAccess를 호출하지 않는다
     * (listForKiosk와 동일 구조). 조회는 기존 `IdleContentPort.fetchById(eventId, contentId)`를
     * 재사용한다 — id만 받는 조회 포트를 새로 만들면 SQL에 event_id 스코프가 없는 조회 경로가
     * 생기고, 이 표면은 무인증이라 그 결함이 곧바로 타 행사 미디어 유출로 실현된다.
     *
     * @return 콘텐츠 부재·소속 행사 불일치·파일 미보유(구 메타 전용 행)·물리 파일 유실 전부 null.
     */
    fun fetchMediaForKiosk(eventId: String, contentId: String): IdleContentMedia?
}

/**
 * [PublicIdleContentUseCase.fetchMediaForKiosk] 결과 — JDK 타입만 사용한다
 * (`Resource`·`MediaType`은 api-app 컨트롤러에서 감싼다).
 */
data class IdleContentMedia(val path: Path, val contentType: String, val downloadName: String)
