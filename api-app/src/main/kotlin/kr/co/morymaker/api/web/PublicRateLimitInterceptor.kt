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
 * 현장등록 공개 POST 전용 rate limit(D4) — 인메모리 고정윈도(client IP 키). 초기 단일 인스턴스
 * 운영을 전제로 한다(다중 인스턴스로 확장되면 분산 카운터가 필요 — 이번 범위 밖, 변경 시점 등급).
 *
 * `WebMvcConfig`가 현장등록 경로 하위 전체(GET 폼·POST 등록 모두 매칭)에 이 인터셉터를 등록하므로,
 * GET(폼 렌더)·`/u`(개인허브) 조회는 스팸 위협이 낮아 대상이 아니라는 점을 여기서 method 분기로
 * 직접 걸러낸다.
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
        if (request.method != HttpMethod.POST.name()) return true

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
            throw RateLimitExceededException("현장등록 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요")
        }
        return true
    }
}
