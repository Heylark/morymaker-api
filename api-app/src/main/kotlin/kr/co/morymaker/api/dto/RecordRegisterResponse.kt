package kr.co.morymaker.api.dto

import com.fasterxml.jackson.annotation.JsonInclude
import kr.co.morymaker.api.application.port.`in`.MappingResult
import kr.co.morymaker.api.application.port.`in`.RegisterParkingResult
import kr.co.morymaker.api.application.port.`in`.SupersededInfo

/**
 * 주차 등록 결과 응답 DTO(§6-6) — 승계 3분기(`result`)에 따라 `supersededRecord`·`message`가
 * 선택적으로 채워진다. `mapping`(3-7 매핑 결과)은 매칭 실패(`matched=false`)여도 항상 채워진다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class RecordRegisterResponse(
    val result: String,
    val record: RecordResponse,
    val mapping: MappingView?,
    val supersededRecord: SupersededView?,
    val message: String?,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class MappingView(
    val matched: Boolean,
    val guestId: String? = null,
    val guestName: String? = null,
    val guestStatus: String? = null,
)

data class SupersededView(val id: String, val status: String, val note: String)

fun RegisterParkingResult.toResponse(): RecordRegisterResponse = RecordRegisterResponse(
    result = result,
    record = record.toResponse(),
    mapping = mapping.toView(),
    supersededRecord = supersededRecord?.toView(),
    message = message,
)

private fun MappingResult.toView(): MappingView = MappingView(
    matched = matched,
    guestId = guestId,
    guestName = guestName,
    guestStatus = guestStatus,
)

private fun SupersededInfo.toView(): SupersededView = SupersededView(id = id, status = status, note = note)
