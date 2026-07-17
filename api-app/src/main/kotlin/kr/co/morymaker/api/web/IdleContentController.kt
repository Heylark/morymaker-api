package kr.co.morymaker.api.web

import jakarta.validation.Valid
import kr.co.morymaker.api.application.port.`in`.IdleContentCreateCommand
import kr.co.morymaker.api.application.port.`in`.IdleContentUpdateCommand
import kr.co.morymaker.api.application.port.`in`.IdleContentUseCase
import kr.co.morymaker.api.domain.security.MoryRoles
import kr.co.morymaker.api.dto.ApiResponse
import kr.co.morymaker.api.dto.IdleContentCreateRequest
import kr.co.morymaker.api.dto.IdleContentDeleteResponse
import kr.co.morymaker.api.dto.IdleContentResponse
import kr.co.morymaker.api.dto.IdleContentUpdateRequest
import kr.co.morymaker.api.dto.toResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

/**
 * 대기화면 콘텐츠 관리자 API(§11-2~4) — 전 메서드 `EVENT_ADMIN`(관리자 콘솔, 실행자 제외).
 *
 * `{eid}` 경로변수명은 고정 — `EventScopeInterceptor`가 "eid" 키만 검사한다(00-research 발견 3,
 * ParkingZoneController와 동일 원칙). 키오스크 무인증 조회·서빙은 `PublicIdleContentController`
 * (`/public/events/{eid}/idle-contents`)가 별도로 담당한다.
 *
 * 등록(POST)은 M3부터 multipart 전용이다(`file` 파트 필수) — 메타만 있고 파일이 없는 중간
 * 상태를 구조적으로 불가능하게 만드는 것이, 파일 없이 등록된 콘텐츠가 대기화면에 파일명
 * 텍스트만 렌더되던 조용한 실패의 근본 수정이다.
 */
@RestController
@RequestMapping("/events/{eid}/idle-contents")
class IdleContentController(
    private val idleContentUseCase: IdleContentUseCase,
) {

    @Value("\${morymaker.media.base-url}")
    private lateinit var mediaBaseUrl: String

    @GetMapping(value = ["", "/"])
    @PreAuthorize(MoryRoles.HAS_ADMIN_CONSOLE)
    fun list(@PathVariable eid: String): ApiResponse<List<IdleContentResponse>> =
        ApiResponse(idleContentUseCase.list(eid).map { it.toResponse(mediaBaseUrl) })

    @PostMapping(value = ["", "/"], consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @PreAuthorize(MoryRoles.HAS_ADMIN_CONSOLE)
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @PathVariable eid: String,
        @RequestPart("file") file: MultipartFile,
        @Valid @ModelAttribute request: IdleContentCreateRequest,
    ): ApiResponse<IdleContentResponse> = file.inputStream.use { stream ->
        // 스트림 소유권은 이 컨트롤러(호출자)가 갖는다 — use{}가 유스케이스 호출을 감싸 종료
        // 시점에 close한다. 어댑터는 close하지 않는다(FileStoragePort 계약).
        val command = IdleContentCreateCommand(
            name = request.name,
            kind = request.kind,
            mode = request.mode,
            play = request.play,
            sortOrder = request.sortOrder,
            source = stream,
            size = file.size,
        )
        ApiResponse(idleContentUseCase.create(eid, command).toResponse(mediaBaseUrl))
    }

    @PutMapping("/{cid}")
    @PreAuthorize(MoryRoles.HAS_ADMIN_CONSOLE)
    fun update(
        @PathVariable eid: String,
        @PathVariable cid: String,
        @Valid @RequestBody request: IdleContentUpdateRequest,
    ): ApiResponse<IdleContentResponse> {
        val command = IdleContentUpdateCommand(
            mode = request.mode,
            play = request.play,
            sortOrder = request.sortOrder,
        )
        return ApiResponse(idleContentUseCase.update(eid, cid, command).toResponse(mediaBaseUrl))
    }

    @DeleteMapping("/{cid}")
    @PreAuthorize(MoryRoles.HAS_ADMIN_CONSOLE)
    fun delete(@PathVariable eid: String, @PathVariable cid: String): ApiResponse<IdleContentDeleteResponse> {
        idleContentUseCase.delete(eid, cid)
        return ApiResponse(IdleContentDeleteResponse(cid))
    }
}
