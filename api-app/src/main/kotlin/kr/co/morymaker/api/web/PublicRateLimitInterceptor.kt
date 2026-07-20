package kr.co.morymaker.api.web

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kr.co.morymaker.api.config.PublicProperties
import org.springframework.http.HttpMethod
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 공개 POST(현장등록·셀프주차) + kiosk GET 검색 rate limit — 인메모리 고정윈도(client IP 키).
 * 초기 단일 인스턴스 운영을 전제로 한다(다중 인스턴스로 확장되면 분산 카운터가 필요 — 이번
 * 범위 밖, 변경 시점 등급).
 *
 * `WebMvcConfig`가 현장등록/셀프주차 경로 하위 전체(GET 폼·POST 등록 모두 매칭)에 이 인터셉터를
 * 등록하므로, GET(폼 렌더)·`/u`(개인허브) 조회는 스팸 위협이 낮아 대상이 아니라는 점을 여기서
 * method 분기로 직접 걸러낸다. kiosk 이름검색·주차검색은 GET이지만 참석자 명부를 열람하는
 * enumeration 표면이라 — [KIOSK_PATH_PREFIX] 하위 GET만 예외적으로 검사 대상에 포함한다
 * (idle-contents 등 그 외 공개 GET은 여전히 무제한 — `WebMvcConfig`가 애초에 이 인터셉터를
 * 등록하지 않은 경로는 preHandle 자체가 호출되지 않는다).
 */
@Component
class PublicRateLimitInterceptor(
    private val properties: PublicProperties,
) : HandlerInterceptor {

    private class Window(@Volatile var windowStartEpochSecond: Long, val count: AtomicInteger)

    private val windows = ConcurrentHashMap<String, Window>()

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        val isPost = request.method == HttpMethod.POST.name()
        val isKioskGet = request.method == HttpMethod.GET.name() && request.servletPath.startsWith(KIOSK_PATH_PREFIX)
        if (!isPost && !isKioskGet) return true

        val key = request.remoteAddr ?: return true
        val nowEpochSecond = Instant.now().epochSecond
        val windowSeconds = properties.rateLimit.windowSeconds.toLong()

        val window = windows.compute(key) { _, existing ->
            if (existing == null || nowEpochSecond - existing.windowStartEpochSecond >= windowSeconds) {
                Window(nowEpochSecond, AtomicInteger(1))
            } else {
                existing.count.incrementAndGet()
                existing
            }
        }!!

        if (window.count.get() > properties.rateLimit.limit) {
            throw RateLimitExceededException("요청이 너무 많습니다. 잠시 후 다시 시도해 주세요")
        }
        return true
    }

    companion object {
        /** kiosk 공개 조회 경로 접두사 — `WebMvcConfig`의 kiosk 개별 등록 패턴과 짝을 이룬다. */
        const val KIOSK_PATH_PREFIX = "/public/events/"
    }
}
