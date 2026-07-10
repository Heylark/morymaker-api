package kr.co.morymaker.api.web

import jakarta.validation.Valid
import kr.co.morymaker.api.application.port.`in`.ParkingZoneUseCase
import kr.co.morymaker.api.application.port.`in`.ZoneCreateCommand
import kr.co.morymaker.api.application.port.`in`.ZoneUpdateCommand
import kr.co.morymaker.api.domain.security.MoryRoles
import kr.co.morymaker.api.dto.ApiResponse
import kr.co.morymaker.api.dto.SlotResponse
import kr.co.morymaker.api.dto.ZoneCreateRequest
import kr.co.morymaker.api.dto.ZoneResponse
import kr.co.morymaker.api.dto.ZoneUpdateRequest
import kr.co.morymaker.api.dto.toResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * 주차 구획 API — 목록·등록·수정·자리 QR ZIP 일괄 다운로드. 전 메서드 `EVENT_ADMIN`(관리자 콘솔, 실행자 제외).
 *
 * `{eid}` 경로변수명은 고정 — `EventScopeInterceptor`가 "eid" 키만 검사한다(00-research 발견 3).
 */
@RestController
@RequestMapping("/api/events/{eid}/parking-zones")
class ParkingZoneController(
    private val zoneUseCase: ParkingZoneUseCase,
) {

    @Value("\${morymaker.parking.qr-base-url}")
    private lateinit var qrBaseUrl: String

    @GetMapping(value = ["", "/"])
    @PreAuthorize(MoryRoles.HAS_ADMIN_CONSOLE)
    fun list(@PathVariable eid: String): ApiResponse<List<ZoneResponse>> =
        ApiResponse(zoneUseCase.listZones(eid).map { it.toResponse() })

    @PostMapping(value = ["", "/"])
    @PreAuthorize(MoryRoles.HAS_ADMIN_CONSOLE)
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @PathVariable eid: String,
        @Valid @RequestBody request: ZoneCreateRequest,
    ): ApiResponse<ZoneResponse> {
        val command = ZoneCreateCommand(
            part1 = request.part1,
            part2 = request.part2,
            part3 = request.part3,
            part4 = request.part4,
            startNo = request.startNo,
            slotCount = request.slotCount,
        )
        return ApiResponse(zoneUseCase.createZone(eid, command).toResponse())
    }

    @PutMapping("/{zid}")
    @PreAuthorize(MoryRoles.HAS_ADMIN_CONSOLE)
    fun update(
        @PathVariable eid: String,
        @PathVariable zid: String,
        @Valid @RequestBody request: ZoneUpdateRequest,
    ): ApiResponse<ZoneResponse> {
        val command = ZoneUpdateCommand(
            part1 = request.part1,
            part2 = request.part2,
            part3 = request.part3,
            part4 = request.part4,
            startNo = request.startNo,
            slotCount = request.slotCount,
            titleOverrides = request.titleOverrides,
        )
        return ApiResponse(zoneUseCase.updateZone(eid, zid, command).toResponse())
    }

    /** 자리 목록(§6-4b 신설) — scanUrl 포함 JSON. web ADM-07 QrPreview가 이 값을 그대로 인코딩한다. */
    @GetMapping("/{zid}/slots")
    @PreAuthorize(MoryRoles.HAS_ADMIN_CONSOLE)
    fun slots(@PathVariable eid: String, @PathVariable zid: String): ApiResponse<List<SlotResponse>> =
        ApiResponse(zoneUseCase.getSlotsForQr(eid, zid).slots.map { it.toResponse(qrBaseUrl) })

    /** 자리별 QR PNG 일괄 ZIP 다운로드(§6-4a). QR payload는 자리 중립 URL(3-6, 재인쇄 불요). */
    @GetMapping("/{zid}/qr-zip")
    @PreAuthorize(MoryRoles.HAS_ADMIN_CONSOLE)
    fun qrZip(@PathVariable eid: String, @PathVariable zid: String): ResponseEntity<ByteArray> {
        val bundle = zoneUseCase.getSlotsForQr(eid, zid)
        val entries = bundle.slots.map { slot ->
            "${slot.slotFullName}.png" to QrCodeGenerator.encode(slot.toResponse(qrBaseUrl).scanUrl)
        }
        val zipBytes = ZipBuilder.zip(entries)
        // 한글 파일명(RFC 5987) — URLEncoder는 공백을 '+'로 인코딩하므로 '%20'으로 치환한다.
        val encodedName = URLEncoder.encode("${bundle.zoneName}_QR.zip", StandardCharsets.UTF_8).replace("+", "%20")
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/zip"))
            .header("Content-Disposition", "attachment; filename*=UTF-8''$encodedName")
            .body(zipBytes)
    }
}
