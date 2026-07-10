package kr.co.morymaker.api.application.port.`in`

import kr.co.morymaker.api.domain.event.Event

/**
 * 공개 자리 QR 유스케이스 포트-in(§10-3·§10-4) — api-app의 `PublicParkingController`가 호출한다.
 *
 * 무인증 공개 경로다 — `EventScopeGuard.assertAccess`를 호출하지 않는다. slotCode 자체의
 * 유효성(파싱 성공 + 구획 존재 + 자리 범위 이내)만으로 인가를 대체한다: 무효 slotCode는
 * [NoSuchElementException](404)으로 거부한다(enumeration-safe).
 *
 * viewType은 순수 공개(PUBLIC) 모델만 방출한다 — SELF_PARK_FORM/OCCUPIED_NOTICE 2분기뿐이며,
 * STAFF_SLOT_MANAGE 판정은 이 포트의 책임이 아니다(프론트 자기 세션 인지로 후속 web이 담당).
 */
interface PublicParkingUseCase {

    /** 자리 상태 조회(§10-3) — 점유 여부에 따라 viewType을 조립해 반환한다. */
    fun getSlotView(slotCode: String): PublicSlotView

    /**
     * 셀프 주차 등록(§10-4) — 승계 코어(`ParkingWriteSupport`, 인증 경로와 공유)를 재사용하고
     * `registeredBy`는 항상 셀프로 고정한다.
     */
    fun selfPark(slotCode: String, command: SelfParkCommand): RegisterParkingResult
}

/** [PublicParkingUseCase.getSlotView] 결과. */
data class PublicSlotView(
    val slotCode: String,
    val slotSig: String,
    val display: String,
    val viewType: String,
    val occupied: Boolean,
    val event: Event,
) {
    companion object {
        const val VIEW_TYPE_SELF_PARK_FORM = "SELF_PARK_FORM"
        const val VIEW_TYPE_OCCUPIED_NOTICE = "OCCUPIED_NOTICE"
    }
}

/**
 * [PublicParkingUseCase.selfPark] 입력(§10-4). `plate`만 필수 — `token`이 있으면(허브 경유)
 * 서버가 참석자를 확인해 `vipName`/`phone`을 프리필한다(이미 입력된 값은 덮지 않는다).
 */
data class SelfParkCommand(
    val plate: String,
    val vipName: String? = null,
    val phone: String? = null,
    val token: String? = null,
)
