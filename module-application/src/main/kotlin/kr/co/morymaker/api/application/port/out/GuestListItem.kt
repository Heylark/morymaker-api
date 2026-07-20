package kr.co.morymaker.api.application.port.out

import kr.co.morymaker.api.domain.guest.Guest
import java.time.Instant

/**
 * 참석자 목록/상세 read model — Guest 전 필드 + `seatLabel`(seat_group.label 조인).
 *
 * hexagonal-arch §5 원칙: JOIN 결과는 도메인 엔티티([Guest])에 넣지 않고 별도 read model로
 * 분리한다. 위치는 port/out — 매퍼가 반환하는 SPI 응답 타입이라 도메인 패키지가 아닌 여기 둔다.
 *
 * 생성자 파라미터 순서는 GuestMapper.xml의 `DetailResultMap` constructor 선언 순서와 정확히
 * 일치해야 한다(positional index 금지 원칙 — 컬럼명 기반 매핑이라 순서 자체는 안전하지만
 * 생성자 시그니처는 XML과 항상 짝을 맞춘다).
 */
class GuestListItem(
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
    val seatLabel: String?,
)

/** read model → 쓰기 대상 순수 도메인 변환(체크인·취소 등 상태 전이 시 사용). */
fun GuestListItem.toGuest(): Guest = Guest(
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
