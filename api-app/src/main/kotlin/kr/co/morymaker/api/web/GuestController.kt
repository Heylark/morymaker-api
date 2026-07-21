package kr.co.morymaker.api.web

import jakarta.validation.Valid
import kr.co.morymaker.api.application.port.`in`.GuestSearchCommand
import kr.co.morymaker.api.application.port.`in`.GuestUseCase
import kr.co.morymaker.api.application.port.`in`.RegisterGuestCommand
import kr.co.morymaker.api.application.port.`in`.UpdateGuestCommand
import kr.co.morymaker.api.domain.security.MoryRoles
import kr.co.morymaker.api.dto.ApiResponse
import kr.co.morymaker.api.dto.GuestCreateRequest
import kr.co.morymaker.api.dto.GuestImportPreviewResponse
import kr.co.morymaker.api.dto.GuestImportResultResponse
import kr.co.morymaker.api.dto.GuestResponse
import kr.co.morymaker.api.dto.GuestUpdateRequest
import kr.co.morymaker.api.dto.Meta
import kr.co.morymaker.api.dto.toResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * 참석자 명단 API(§4) — 전 메서드 `EVENT_ADMIN`(관리자 콘솔, 실행자 제외).
 *
 * `{eid}` 경로변수명은 고정 — `EventScopeInterceptor`가 "eid" 키만 검사한다(00-research 발견 3).
 */
@RestController
@RequestMapping("/events/{eid}/guests")
class GuestController(
    private val guestUseCase: GuestUseCase,
) {

    @GetMapping(value = ["", "/"])
    @PreAuthorize(MoryRoles.HAS_ADMIN_CONSOLE)
    fun list(
        @PathVariable eid: String,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) src: String?,
        @RequestParam(defaultValue = "false") includeCancelled: Boolean,
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
    ): ApiResponse<List<GuestResponse>> {
        val result = guestUseCase.listGuests(
            eid,
            GuestSearchCommand(
                q = q,
                status = status,
                src = src,
                includeCancelled = includeCancelled,
                page = page,
                size = size,
            ),
        )
        return ApiResponse(
            data = result.items.map { it.toResponse() },
            meta = Meta(total = result.total, searchState = result.searchState, page = page, size = size),
        )
    }

    @PostMapping(value = ["", "/"])
    @PreAuthorize(MoryRoles.HAS_ADMIN_CONSOLE)
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @PathVariable eid: String,
        @Valid @RequestBody request: GuestCreateRequest,
    ): ApiResponse<GuestResponse> {
        val command = RegisterGuestCommand(
            name = request.name,
            org = request.org,
            title = request.title,
            phone = request.phone,
            plate = request.plate,
            seatGroupId = request.seatGroupId,
            src = request.src,
        )
        return ApiResponse(guestUseCase.registerGuest(eid, command).toResponse())
    }

    @PutMapping("/{gid}")
    @PreAuthorize(MoryRoles.HAS_ADMIN_CONSOLE)
    fun update(
        @PathVariable eid: String,
        @PathVariable gid: String,
        @RequestBody request: GuestUpdateRequest,
    ): ApiResponse<GuestResponse> {
        val command = UpdateGuestCommand(
            name = request.name,
            org = request.org,
            title = request.title,
            phone = request.phone,
            plate = request.plate,
            seatGroupId = request.seatGroupId,
        )
        return ApiResponse(guestUseCase.updateGuest(eid, gid, command).toResponse())
    }

    @DeleteMapping("/{gid}")
    @PreAuthorize(MoryRoles.HAS_ADMIN_CONSOLE)
    fun cancel(
        @PathVariable eid: String,
        @PathVariable gid: String,
        @RequestParam(defaultValue = "false") deleteSmsLog: Boolean,
    ): ApiResponse<GuestResponse> = ApiResponse(guestUseCase.cancelGuest(eid, gid, deleteSmsLog).toResponse())

    /**
     * 업로드 양식(§4-5) 다운로드 — 파서와 같은 컬럼 계약([GuestImportColumn])에서 생성한다.
     * 파일명 고정(`StatsController.export` 선례). `eid`는 스코프 가드([EventScopeInterceptor])용으로만
     * 쓰이고 본문 생성에는 사용되지 않는다 — 응답 바이트는 행사와 무관한 상수다. 좌석그룹 실값
     * 안내 시트가 도입되면 이 가드는 실제 데이터 가드로 승격된다.
     */
    @GetMapping("/import/template")
    @PreAuthorize(MoryRoles.HAS_ADMIN_CONSOLE)
    fun downloadImportTemplate(@PathVariable eid: String): ResponseEntity<ByteArray> {
        val bytes = GuestImportTemplateWriter.write()
        val encoded = URLEncoder.encode("명단업로드양식.xlsx", StandardCharsets.UTF_8).replace("+", "%20")
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .header("Content-Disposition", "attachment; filename*=UTF-8''$encoded")
            .body(bytes)
    }

    @PostMapping("/import/preview")
    @PreAuthorize(MoryRoles.HAS_ADMIN_CONSOLE)
    fun previewImport(
        @PathVariable eid: String,
        @RequestPart("file") file: MultipartFile,
    ): ApiResponse<GuestImportPreviewResponse> {
        val rows = GuestExcelParser.parse(file)
        return ApiResponse(guestUseCase.previewImport(eid, rows).toResponse())
    }

    @PostMapping("/import")
    @PreAuthorize(MoryRoles.HAS_ADMIN_CONSOLE)
    fun confirmImport(
        @PathVariable eid: String,
        @RequestPart("file") file: MultipartFile,
    ): ApiResponse<GuestImportResultResponse> {
        val rows = GuestExcelParser.parse(file)
        return ApiResponse(guestUseCase.confirmImport(eid, rows).toResponse())
    }
}
