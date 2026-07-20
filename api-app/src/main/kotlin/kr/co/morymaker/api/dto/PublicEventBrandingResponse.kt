package kr.co.morymaker.api.dto

import kr.co.morymaker.api.domain.event.Event

/**
 * 공개 키오스크 이벤트 브랜딩 응답 — 키오스크가 실제 소비하는 액센트(pointColor)와 기본
 * 대기화면 표시방식(defaultIdleMode)만 노출한다. name·bgColor는 키오스크 화면이 다크 고정이라
 * 불요하고, title/body/kv는 현재 관리자 전용 정보라 신규 무인증 노출을 피하기 위해 의도적으로
 * 제외한다(최소 노출 범위로 사용자 승인 완료).
 *
 * `@JsonInclude` 미부착 — null 필드도 그대로 직렬화한다(방문자 `EventBrandingView`와 동일 관행).
 * 미설정 이벤트의 폴백 색 파생은 서버가 하지 않고 소비자(키오스크 화면)가 담당한다.
 *
 * `defaultIdleMode`는 현재 다른 공개 응답 어디에도 없던 필드이나, 표시방식 라벨(`branded`/
 * `fullbleed`)일 뿐 개인정보·소유권 정보를 담지 않아 민감 노출 표면은 늘지 않는다. 키오스크가
 * 대기화면 폴백값으로 실제 소비하므로 노출이 정당하다.
 */
data class PublicEventBrandingResponse(
    val pointColor: String?,
    val defaultIdleMode: String?,
)

fun Event.toPublicEventBrandingResponse(): PublicEventBrandingResponse =
    PublicEventBrandingResponse(pointColor = pointColor, defaultIdleMode = defaultIdleMode)
