package kr.co.morymaker.api.application.port.`in`

/**
 * 문자 도메인 유스케이스 포트-in(§7) — api-app의 `SmsController`가 호출한다.
 *
 * `eventBaseUrl`은 QR링크 토큰 렌더링에 필요한 값이다. api-app이 소유하는 설정(`PublicProperties`)을
 * 컨트롤러가 인수로 전달한다 — 서비스 계층은 URL 관심사를 갖지 않는 기존 경계(`PublicHubResponse`
 * 조립 방식과 동일)를 그대로 보존한다. 템플릿 CRUD(7-1·7-2)·이력(7-6)은 렌더링이 없어 인수가 없다.
 */
interface SmsUseCase {

    /** 행사당 단일 템플릿 조회(7-1) — 아직 설정 전이면 body="" · updatedAt=null. */
    fun getTemplate(eventId: String): SmsTemplateView

    /** 템플릿 본문 upsert(7-2, event_id UNIQUE). */
    fun upsertTemplate(eventId: String, body: String): SmsTemplateView

    /** gid 단위 치환 미리보기(7-2a) — 이름 문자열 매칭 없이 guestId로 확정된 대상만 치환한다. */
    fun preview(eventId: String, guestId: String, eventBaseUrl: String): SmsPreviewView

    /** 발송 전 검증 게이트(7-3) — 누락자 목록 + 이미 발송 수 + 발송 가능 여부. */
    fun gate(eventId: String, excludeAlreadySent: Boolean, eventBaseUrl: String): SmsGateView

    /** 초대 문자 일괄 발송(7-4) — confirm 필수 + 게이트 재검증 + gid 치환 + body_snapshot 기록. */
    fun send(eventId: String, command: SmsSendCommand, eventBaseUrl: String): SmsSendResultView

    /** 개별 재발송(7-5) — 게이트(중복 방지)를 무시하고 신규 로그를 남기는 명시적 override. */
    fun resend(eventId: String, guestId: String, confirm: Boolean, eventBaseUrl: String): SmsSendItem

    /** 발송 이력(7-6, sent_at DESC). */
    fun listLog(eventId: String): List<SmsLogView>
}

data class SmsSendCommand(val excludeAlreadySent: Boolean, val confirm: Boolean)

/** [SmsUseCase.getTemplate]/[SmsUseCase.upsertTemplate] 결과 — §7-1 shape과 1:1. */
data class SmsTemplateView(val eventId: String, val body: String, val variables: List<String>, val updatedAt: String?)

data class SmsPreviewView(val rendered: String)

/** [SmsUseCase.gate] 결과 — §7-3 shape과 1:1. */
data class SmsGateView(
    val candidates: Int,
    val blocked: List<SmsBlockedGuest>,
    val alreadySent: Int,
    val canSend: Boolean,
    val appliedTemplate: String,
)

data class SmsBlockedGuest(val guestId: String, val name: String, val missing: List<String>)

/** [SmsUseCase.send] 결과 — §7-4 shape과 1:1. */
data class SmsSendResultView(val sent: Int, val failed: Int, val results: List<SmsSendItem>)

data class SmsSendItem(val guestId: String, val phone: String?, val status: String, val smsLogId: String)

/** [SmsUseCase.listLog] 항목 — §7-6 shape과 1:1. `sentAt`은 KST ISO-8601 문자열(SmsService 조립). */
data class SmsLogView(
    val id: String,
    val guestId: String?,
    val nameSnapshot: String?,
    val phone: String?,
    val sentAt: String,
    val status: String,
    val bodySnapshot: String?,
)
