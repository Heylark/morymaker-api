package kr.co.morymaker.api.config

import kr.co.morymaker.api.security.EventScopeInterceptor
import kr.co.morymaker.api.web.PublicRateLimitInterceptor
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

// EventScopeInterceptor(행사 스코프 게이트 Layer2a)를 /api/events 이하 전체 경로에 등록한다.
//
// /api/events·/api/events/{eid} 모두 이 패턴에 매칭된다(Ant 스타일 와일드카드는 0개 이상의
// 경로 세그먼트를 매칭) — 실제 게이트 적용 여부는 인터셉터 내부의 {eid} 존재 여부가 결정한다.
@Configuration
class WebMvcConfig(
    private val eventScopeInterceptor: EventScopeInterceptor,
    private val publicRateLimitInterceptor: PublicRateLimitInterceptor,
) : WebMvcConfigurer {

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(eventScopeInterceptor).addPathPatterns("/api/events/**")
        // 현장등록 GET(폼 렌더)·POST(등록) 모두 이 패턴에 매칭되지만, 인터셉터 내부에서 POST만
        // 실제로 검사한다(폼 조회·개인허브(/u)는 스팸 위협이 낮아 대상이 아니다).
        registry.addInterceptor(publicRateLimitInterceptor).addPathPatterns("/api/public/r/**")
        // 자리 QR 셀프 주차(§10-4) — GET(상태 조회)·POST(셀프 등록) 모두 매칭되지만 위와 동일하게
        // 인터셉터 내부에서 POST만 검사한다. 단측 additive(이 파일 외 공유 설정 변경 없음).
        registry.addInterceptor(publicRateLimitInterceptor).addPathPatterns("/api/public/p/**")
        // kiosk 개별 엔드포인트만 명시 등록(broad "/api/public/events/**" 아님) — idle-contents 등
        // 그 외 kiosk 하위 공개 조회는 이 인터셉터 자체가 호출되지 않는다. 이름검색·주차검색은
        // GET이지만(인터셉터 내부 KIOSK_PATH_PREFIX 분기로 검사 대상), 체크인은 POST라 이미
        // 검사 대상이다 — 3개를 모두 명시해 등록 경로를 한눈에 드러낸다.
        registry.addInterceptor(publicRateLimitInterceptor).addPathPatterns(
            "/api/public/events/*/attendees/**",
            "/api/public/events/*/checkin/**",
            "/api/public/events/*/parking-search/**",
        )
    }
}
