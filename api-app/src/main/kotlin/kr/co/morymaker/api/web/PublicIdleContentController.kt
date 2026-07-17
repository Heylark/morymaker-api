package kr.co.morymaker.api.web

import kr.co.morymaker.api.application.port.`in`.PublicIdleContentUseCase
import kr.co.morymaker.api.dto.ApiResponse
import kr.co.morymaker.api.dto.IdleContentResponse
import kr.co.morymaker.api.dto.toResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.FileSystemResource
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * 대기화면 콘텐츠 키오스크 공개 조회·서빙 API(§11-2, M3) — 무인증. `SecurityConfig`의
 * `/public` 하위 전체 permitAll 규칙이 인증을 면제하며, 이 컨트롤러는 `@PreAuthorize`를
 * 두지 않는다(역할 게이트 대상 자체가 아니다 — `PublicOnsiteController`·`PublicHubController`와
 * 동일 원칙).
 *
 * `/public/events` 하위 경로는 `WebMvcConfig`가 `EventScopeInterceptor`를 등록한
 * `/events` 하위 패턴 밖이라(prefix 상이) 스코프 게이트도 적용되지 않는다 — 의도된 설계다.
 * 격리는 `PublicIdleContentUseCase`가 위임하는 `event_id` WHERE 필터로만 제공한다.
 *
 * 존재하지 않는 eid도 404가 아니라 빈 배열(200, fail-open)로 응답한다 — 무인증 디스플레이
 * 기기에 오류 처리를 요구하지 않기 위함이며, UUID eid + 기존 공개 폼이 이미 event 존재를
 * 노출하므로 enumeration 신규 노출도 없다.
 *
 * 미디어 서빙(`/{cid}/file`)은 이와 반대로 **부재 시 404**다(§11-2, M3 신설) — 미존재 cid와
 * 타 행사 cid가 동일 404이므로 oracle이 생기지 않는다(CP-2 결정 — 무인증 공개 + rate limit
 * 미등록. `WebMvcConfig`가 이 경로에 `PublicRateLimitInterceptor`를 등록하지 않는다 — 등록하면
 * 행사장 단일 NAT에서 키오스크 미디어 GET이 현장등록 POST 예산을 소진해 정상 등록이 429로
 * 거부된다, 실측 근거는 02-architect.md ADR-004).
 */
@RestController
@RequestMapping("/public/events/{eid}/idle-contents")
class PublicIdleContentController(
    private val publicIdleContentUseCase: PublicIdleContentUseCase,
) {

    @Value("\${morymaker.media.base-url}")
    private lateinit var mediaBaseUrl: String

    @GetMapping(value = ["", "/"])
    fun listForKiosk(@PathVariable eid: String): ApiResponse<List<IdleContentResponse>> =
        ApiResponse(publicIdleContentUseCase.listForKiosk(eid).map { it.toResponse(mediaBaseUrl) })

    /**
     * 미디어 스트리밍 — `ResponseEntity<Resource>` + `FileSystemResource` 반환이 Range(206)
     * 자동 처리의 전제다. `InputStreamResource`로 바꾸면 Spring이 Range를 명시 배제해 조용히
     * 200만 반환한다(육안 재생·소용량 TC로는 드러나지 않음 — 02-architect.md §1 V1 실측).
     * `Content-Disposition: inline`(재생 목적, 다운로드 아님) + `Cache-Control: immutable`
     * (`{cid}` 파일은 생애 불변 — DELETE 없음·업데이트가 file_url을 갱신하지 않음).
     */
    @GetMapping("/{cid}/file")
    fun serveFile(@PathVariable eid: String, @PathVariable cid: String): ResponseEntity<FileSystemResource> {
        val media = publicIdleContentUseCase.fetchMediaForKiosk(eid, cid)
            ?: throw NoSuchElementException("미디어를 찾을 수 없습니다")
        val encodedName = URLEncoder.encode(media.downloadName, StandardCharsets.UTF_8).replace("+", "%20")
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(media.contentType))
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''$encodedName")
            .header(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable")
            .body(FileSystemResource(media.path))
    }
}
