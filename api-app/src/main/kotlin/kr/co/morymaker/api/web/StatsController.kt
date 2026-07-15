package kr.co.morymaker.api.web

import kr.co.morymaker.api.application.port.`in`.StatsUseCase
import kr.co.morymaker.api.domain.security.MoryRoles
import kr.co.morymaker.api.dto.ApiResponse
import kr.co.morymaker.api.dto.StatsResponse
import kr.co.morymaker.api.dto.toResponse
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * 행사 통계 API(§8) — 전 메서드 `EVENT_ADMIN`(관리자 콘솔, 실행자 제외 — ParkingZoneController와
 * 동일 패턴).
 *
 * `{eid}` 경로변수명은 고정 — `EventScopeInterceptor`가 "eid" 키만 검사한다(ParkingZoneController
 * 주석 근거). 두 엔드포인트 모두 동일 집계([StatsUseCase.getStats])를 경유하므로 export도 별도
 * 가드 없이 서비스 첫 줄 `assertAccess(eid)`로 격리된다.
 */
@RestController
@RequestMapping("/events/{eid}/stats")
class StatsController(
    private val statsUseCase: StatsUseCase,
) {

    @GetMapping(value = ["", "/"])
    @PreAuthorize(MoryRoles.HAS_ADMIN_CONSOLE)
    fun stats(
        @PathVariable eid: String,
        @RequestParam(name = "refresh", required = false) refresh: Boolean?, // S4: 수용만·무시(no-op)
    ): ApiResponse<StatsResponse> =
        ApiResponse(statsUseCase.getStats(eid).toResponse())

    /** Excel export(§8-2) — 8-1 집계 재사용(별도 쿼리 없음). 파일명은 고정 `행사통계.xlsx`(YAGNI). */
    @GetMapping("/export")
    @PreAuthorize(MoryRoles.HAS_ADMIN_CONSOLE)
    fun export(@PathVariable eid: String): ResponseEntity<ByteArray> {
        val view = statsUseCase.getStats(eid)
        val bytes = StatsExcelWriter.write(view)
        // 한글 파일명(RFC 5987) — URLEncoder는 공백을 '+'로 인코딩하므로 '%20'으로 치환한다(qrZip 선례 동일).
        val encoded = URLEncoder.encode("행사통계.xlsx", StandardCharsets.UTF_8).replace("+", "%20")
        return ResponseEntity.ok()
            .contentType(
                MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            )
            .header("Content-Disposition", "attachment; filename*=UTF-8''$encoded")
            .body(bytes)
    }
}
