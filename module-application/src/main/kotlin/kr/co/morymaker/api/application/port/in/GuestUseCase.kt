package kr.co.morymaker.api.application.port.`in`

import kr.co.morymaker.api.application.port.out.GuestListItem
import kr.co.morymaker.api.domain.guest.Guest

/**
 * 참석자 명단 유스케이스 포트-in — api-app의 `GuestController`가 호출한다(§4 명단).
 *
 * 체크인(§5)은 [CheckinUseCase]로 분리한다 — 인가 표면(STAFF 포함 여부)과 응답 계약이 다르다.
 */
interface GuestUseCase {

    /** 목록/검색(§4-1·§4-9). q 있으면 이름검색 3상태(searchState)도 함께 계산. */
    fun listGuests(eventId: String, query: GuestSearchCommand): GuestListResult

    /** 단건 상세(seatLabel 포함). */
    fun getGuest(eventId: String, gid: String): GuestListItem

    /** 개별 등록(§4-2). plate 있으면 등록 직후 지연매칭(mapGuestParking) 시도. */
    fun registerGuest(eventId: String, command: RegisterGuestCommand): Guest

    /** 부분 수정(§4-3). plate 변경 시 지연매칭 재시도. */
    fun updateGuest(eventId: String, gid: String, command: UpdateGuestCommand): Guest

    /** 취소 전환(§4-4) — 삭제 아닌 status→취소 보존. */
    fun cancelGuest(eventId: String, gid: String, deleteSmsLog: Boolean): Guest

    /** 엑셀 병합 미리보기(§4-5) — DB 미변경. */
    fun previewImport(eventId: String, rows: List<GuestImportRow>): ImportPreviewResult

    /** 엑셀 병합 확정(§4-6) — 트랜잭션 단위, 부분 실패 전체 롤백. */
    fun confirmImport(eventId: String, rows: List<GuestImportRow>): ImportConfirmResult
}

/** [GuestUseCase.listGuests] 입력(§0-6·§4-1 쿼리). */
data class GuestSearchCommand(
    val q: String? = null,
    val status: String? = null,
    val src: String? = null,
    val includeCancelled: Boolean = false,
    val page: Int = 1,
    val size: Int = 50,
)

/**
 * [GuestUseCase.listGuests] 결과.
 *
 * @param searchState 이름검색 3상태(§4-9) — `q`가 있을 때만 계산: NONE(0)/ONE(1)/MANY(2+). `q` 없으면 null.
 */
data class GuestListResult(
    val items: List<GuestListItem>,
    val total: Int,
    val searchState: String?,
) {
    companion object {
        const val SEARCH_STATE_NONE = "NONE"
        const val SEARCH_STATE_ONE = "ONE"
        const val SEARCH_STATE_MANY = "MANY"
    }
}

/** [GuestUseCase.registerGuest] 입력(§4-2). `name`만 필수, `src` 미지정 시 현장 등록으로 간주. */
data class RegisterGuestCommand(
    val name: String,
    val org: String?,
    val title: String?,
    val phone: String?,
    val plate: String?,
    val seatGroupId: String?,
    val src: String? = null,
)

/** [GuestUseCase.updateGuest] 입력(§4-3) — 부분 갱신. 상태 되돌리기는 §5-3(체크인 취소)로 분리. */
data class UpdateGuestCommand(
    val name: String?,
    val org: String?,
    val title: String?,
    val phone: String?,
    val plate: String?,
    val seatGroupId: String?,
)

/**
 * 엑셀 업로드 1행 원시 데이터(A-1 기본 컬럼셋) — 컨트롤러가 MultipartFile을 파싱해 전달한다
 * (application 레이어가 Apache POI/multipart에 오염되지 않도록 02-architect §10 권고 준수).
 *
 * `seatGroupLabel`은 원문 텍스트만 보존한다 — 좌석그룹 라벨→ID 해석 로직은 좌석 SSOT가 후속
 * REQ(D3)로 이연된 현재 범위에 없어 이번 REQ의 import는 좌석 배정을 수행하지 않는다.
 */
data class GuestImportRow(
    val rowNumber: Int,
    val name: String?,
    val org: String?,
    val title: String?,
    val phone: String?,
    val plate: String?,
    val seatGroupLabel: String?,
)

/** [GuestUseCase.previewImport] 결과(§4-5) — 분류만 수행, DB 미변경. */
data class ImportPreviewResult(
    val newCount: Int,
    val updatedCount: Int,
    val excludedCount: Int,
    val invalidRows: List<InvalidImportRow>,
)

/** 매칭 불가·검증 실패 행(D1 — name 누락 등). */
data class InvalidImportRow(val rowNumber: Int, val reason: String)

/** [GuestUseCase.confirmImport] 결과(§4-6). */
data class ImportConfirmResult(
    val newCount: Int,
    val updatedCount: Int,
    val cancelledCount: Int,
    val invalidRows: List<InvalidImportRow>,
    val tokenPreserved: Boolean = true,
)
