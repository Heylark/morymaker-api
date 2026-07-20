package kr.co.morymaker.api.domain.security

/**
 * 역할 상수 + Spring Security SpEL 표현식.
 *
 * auth(인증 서버) 쪽 동일 객체와 문자열을 완전히 동기해서 유지한다 — 두 서버는 별개 배포이므로
 * 컴파일 타임에 값이 어긋나는 것을 막을 방법이 없고, 어긋나면 발급된 역할 클레임과 이 서버의
 * 권한 체크가 조용히 어긋난다. `@PreAuthorize` 등에서 하드코딩 없이 참조한다.
 */
object MoryRoles {
    const val SYSTEM_ADMIN = "SYSTEM_ADMIN"
    const val EVENT_ADMIN = "EVENT_ADMIN"
    const val EVENT_STAFF = "EVENT_STAFF"

    /** 관리자 콘솔 진입 (실행자 제외). */
    const val HAS_ADMIN_CONSOLE = "hasAnyRole('SYSTEM_ADMIN','EVENT_ADMIN')"

    /** 시스템 관리 전용 (행사 생성·계정 관리). */
    const val HAS_SYSTEM_ADMIN = "hasRole('SYSTEM_ADMIN')"

    /** 실행자 웹 + 관리자 (행사 스코프 검증은 별도 — EventScopeGuard 몫). */
    const val HAS_EVENT_ACCESS = "hasAnyRole('SYSTEM_ADMIN','EVENT_ADMIN','EVENT_STAFF')"
}
