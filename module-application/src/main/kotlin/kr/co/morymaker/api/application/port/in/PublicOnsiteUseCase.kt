package kr.co.morymaker.api.application.port.`in`

import kr.co.morymaker.api.domain.event.Event
import kr.co.morymaker.api.domain.guest.Guest

/**
 * 현장등록 유스케이스 포트-in(§10-5·§10-6) — api-app의 `PublicOnsiteController`가 호출한다.
 *
 * 무인증 공개 경로다 — `EventScopeGuard.assertAccess`를 호출하지 않는다. eventCode는
 * `event.id`(UUID) 재사용(D2, 마이그레이션 0)이라 별도 코드 해석 없이 그대로 `EventPort.fetch`에
 * 전달된다. 무효 eventCode는 [NoSuchElementException](404), 종료된 행사는
 * [kr.co.morymaker.api.application.service.EventNotOpenException](409)으로 거부한다 — 두
 * 메서드(폼 조회·등록) 모두 동일 게이트를 적용한다(§10-5·§10-6 "동일 게이트").
 */
interface PublicOnsiteUseCase {

    /** 현장등록 폼 진입(§10-5) — 폼 렌더용 행사 브랜딩만 반환한다(민감정보 0). */
    fun getOnsiteForm(eventCode: String): Event

    /** 현장등록 실행(§10-6) — 명단 추가 + 체크인 토큰 즉시 발급. */
    fun registerOnsite(eventCode: String, command: OnsiteRegisterCommand): Guest
}

/** [PublicOnsiteUseCase.registerOnsite] 입력(§10-6). `name`만 필수 — dev-default로 연락처도 수집한다. */
data class OnsiteRegisterCommand(
    val name: String,
    val org: String?,
    val phone: String?,
    val plate: String?,
)
