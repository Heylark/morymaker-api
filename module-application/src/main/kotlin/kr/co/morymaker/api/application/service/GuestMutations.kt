package kr.co.morymaker.api.application.service

import kr.co.morymaker.api.domain.guest.Guest
import java.time.Instant

/**
 * [Guest] 필드 단위 갱신 헬퍼 — application 레이어 내부 전용(`internal`).
 *
 * 02-architect §2 결정에 따라 상태 전이 판정은 서비스가 담당하고 [Guest] 자체는 상태머신을
 * 캡슐화하지 않는다(YAGNI). 다만 13개 필드를 매번 전부 나열하는 것은 실수를 유발하므로,
 * "도메인 엔티티에는 메서드를 두지 않는다"는 원칙을 지키면서도 반복을 줄이기 위해 이 확장
 * 함수를 [GuestService]/`CheckinService`가 공유하는 서비스 계층 유틸리티로 둔다(도메인
 * 클래스 자체의 메서드가 아니므로 엔티티 캡슐화 회피 결정과 배치되지 않는다).
 */
internal fun Guest.with(
    name: String = this.name,
    org: String? = this.org,
    title: String? = this.title,
    phone: String? = this.phone,
    plate: String? = this.plate,
    seatGroupId: String? = this.seatGroupId,
    status: String = this.status,
    src: String = this.src,
    visitAt: Instant? = this.visitAt,
    token: String = this.token,
): Guest = Guest(
    id = id,
    eventId = eventId,
    name = name,
    org = org,
    title = title,
    phone = phone,
    plate = plate,
    seatGroupId = seatGroupId,
    status = status,
    src = src,
    visitAt = visitAt,
    token = token,
    createdAt = createdAt,
)
