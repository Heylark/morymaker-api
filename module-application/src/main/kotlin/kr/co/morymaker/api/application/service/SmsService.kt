package kr.co.morymaker.api.application.service

import kr.co.morymaker.api.application.port.`in`.SmsBlockedGuest
import kr.co.morymaker.api.application.port.`in`.SmsGateView
import kr.co.morymaker.api.application.port.`in`.SmsLogView
import kr.co.morymaker.api.application.port.`in`.SmsPreviewView
import kr.co.morymaker.api.application.port.`in`.SmsSendCommand
import kr.co.morymaker.api.application.port.`in`.SmsSendItem
import kr.co.morymaker.api.application.port.`in`.SmsSendResultView
import kr.co.morymaker.api.application.port.`in`.SmsTemplateView
import kr.co.morymaker.api.application.port.`in`.SmsUseCase
import kr.co.morymaker.api.application.port.out.EventPort
import kr.co.morymaker.api.application.port.out.GuestListItem
import kr.co.morymaker.api.application.port.out.GuestPort
import kr.co.morymaker.api.application.port.out.GuestSearchQuery
import kr.co.morymaker.api.application.port.out.SmsLogPort
import kr.co.morymaker.api.application.port.out.SmsSenderPort
import kr.co.morymaker.api.application.port.out.SmsTemplatePort
import kr.co.morymaker.api.application.security.EventScopeGuard
import kr.co.morymaker.api.domain.sms.SmsLog
import kr.co.morymaker.api.domain.sms.SmsTemplate
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * [SmsUseCase] 구현체 — 템플릿 CRUD·미리보기·발송 게이트·일괄 발송·재발송·이력을 담당한다(§7).
 * gid 단위 치환은 [SmsRenderer]에 위임하고, 게이트 판정(누락자·이미 발송 수)은 이 클래스가
 * [GuestPort] 조회 결과와 [SmsLogPort]의 발송완료 gid 집합을 조합하는 in-memory 집합 연산으로
 * 수행한다 — guest×sms_log JOIN 없이 각 매퍼가 단일 테이블만 소유하는 경계를 지킨다.
 *
 * 헥사고날 레이어: application(service). `internal`: api-app은 [SmsUseCase] 인터페이스만
 * 의존한다.
 */
@Service
internal class SmsService(
    private val smsTemplatePort: SmsTemplatePort,
    private val smsLogPort: SmsLogPort,
    private val smsSenderPort: SmsSenderPort,
    private val guestPort: GuestPort,
    private val eventPort: EventPort,
    private val eventScopeGuard: EventScopeGuard,
) : SmsUseCase {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    override fun getTemplate(eventId: String): SmsTemplateView {
        eventScopeGuard.assertAccess(eventId)
        val template = smsTemplatePort.fetchByEvent(eventId)
        return SmsTemplateView(
            eventId = eventId,
            body = template?.body ?: "",
            variables = SmsRenderer.VARIABLES,
            updatedAt = template?.updatedAt?.let(::toKstIso),
        )
    }

    @Transactional
    override fun upsertTemplate(eventId: String, body: String): SmsTemplateView {
        eventScopeGuard.assertAccess(eventId)
        smsTemplatePort.upsert(
            SmsTemplate(id = UUID.randomUUID().toString(), eventId = eventId, body = body, updatedAt = Instant.now()),
        )
        // upsert에 넘긴 updatedAt은 신규행 INSERT 분기에서만 의미가 있고, 기존행 UPDATE 분기는
        // DB CURRENT_TIMESTAMP가 관장한다(SmsTemplateMapper.xml) — 재조회로 실제 저장값을 반환한다.
        // 방금 저장한 행이 없다면 클라이언트 입력 문제가 아니라 서버 장애이므로 상태 검증으로 드러낸다.
        val saved = checkNotNull(smsTemplatePort.fetchByEvent(eventId)) { "템플릿 저장에 실패했습니다" }
        return SmsTemplateView(
            eventId = eventId, body = saved.body, variables = SmsRenderer.VARIABLES,
            updatedAt = toKstIso(saved.updatedAt),
        )
    }

    @Transactional(readOnly = true)
    override fun preview(eventId: String, guestId: String, eventBaseUrl: String): SmsPreviewView {
        eventScopeGuard.assertAccess(eventId)
        val template = smsTemplatePort.fetchByEvent(eventId)
            ?: throw NoSuchElementException("문자 템플릿이 설정되지 않았습니다")
        val guest = guestPort.fetchDetailById(eventId, guestId)
            ?: throw NoSuchElementException("참석자를 찾을 수 없습니다")
        val event = eventPort.fetch(eventId) ?: throw NoSuchElementException("행사를 찾을 수 없습니다")
        return SmsPreviewView(rendered = SmsRenderer.render(template.body, guest, event, eventBaseUrl))
    }

    @Transactional(readOnly = true)
    override fun gate(eventId: String, excludeAlreadySent: Boolean, eventBaseUrl: String): SmsGateView {
        eventScopeGuard.assertAccess(eventId)
        val template = smsTemplatePort.fetchByEvent(eventId)
        val computation = computeGate(eventId, excludeAlreadySent)
        val appliedTemplate = when {
            template == null -> ""
            computation.validTargets.isEmpty() -> template.body
            else -> {
                val event = eventPort.fetch(eventId) ?: throw NoSuchElementException("행사를 찾을 수 없습니다")
                SmsRenderer.render(template.body, computation.validTargets.first(), event, eventBaseUrl)
            }
        }
        return SmsGateView(
            candidates = computation.validTargets.size,
            blocked = computation.blocked,
            alreadySent = computation.alreadySent,
            canSend = computation.canSend,
            appliedTemplate = appliedTemplate,
        )
    }

    @Transactional
    override fun send(eventId: String, command: SmsSendCommand, eventBaseUrl: String): SmsSendResultView {
        eventScopeGuard.assertAccess(eventId)
        require(command.confirm) { "confirm=true 필수" }
        val computation = computeGate(eventId, command.excludeAlreadySent)
        if (!computation.canSend) {
            throw SmsSendBlockedException("발송할 수 없는 참석자가 있습니다(누락 항목을 확인해 주세요)")
        }
        val template = smsTemplatePort.fetchByEvent(eventId)
            ?: throw NoSuchElementException("문자 템플릿이 설정되지 않았습니다")
        val event = eventPort.fetch(eventId) ?: throw NoSuchElementException("행사를 찾을 수 없습니다")

        val results = mutableListOf<SmsSendItem>()
        var sentCount = 0
        var failedCount = 0
        for (guest in computation.validTargets) {
            val phone = checkNotNull(guest.phone) { "게이트 검증을 통과한 대상의 전화번호가 없습니다" }
            val body = SmsRenderer.render(template.body, guest, event, eventBaseUrl)
            val status = sendOne(phone, body)
            if (status == SmsLog.STATUS_SUCCESS) sentCount++ else failedCount++
            val smsLog = SmsLog(
                id = UUID.randomUUID().toString(), eventId = eventId, guestId = guest.id,
                nameSnapshot = nameSnapshot(guest), phone = phone, sentAt = Instant.now(),
                status = status, bodySnapshot = body,
            )
            smsLogPort.insert(smsLog)
            results += SmsSendItem(guestId = guest.id, phone = phone, status = status, smsLogId = smsLog.id)
        }
        return SmsSendResultView(sent = sentCount, failed = failedCount, results = results)
    }

    @Transactional
    override fun resend(eventId: String, guestId: String, confirm: Boolean, eventBaseUrl: String): SmsSendItem {
        eventScopeGuard.assertAccess(eventId)
        require(confirm) { "confirm=true 필수" }
        val guest = guestPort.fetchDetailById(eventId, guestId)
            ?: throw NoSuchElementException("참석자를 찾을 수 없습니다")
        val template = smsTemplatePort.fetchByEvent(eventId)
            ?: throw NoSuchElementException("문자 템플릿이 설정되지 않았습니다")
        val event = eventPort.fetch(eventId) ?: throw NoSuchElementException("행사를 찾을 수 없습니다")

        val phone = guest.phone
        val body = SmsRenderer.render(template.body, guest, event, eventBaseUrl)
        val status = if (phone.isNullOrBlank()) SmsLog.STATUS_FAILED else sendOne(phone, body)
        val smsLog = SmsLog(
            id = UUID.randomUUID().toString(), eventId = eventId, guestId = guest.id,
            nameSnapshot = nameSnapshot(guest), phone = phone, sentAt = Instant.now(),
            status = status, bodySnapshot = body,
        )
        smsLogPort.insert(smsLog)
        return SmsSendItem(guestId = guest.id, phone = phone, status = status, smsLogId = smsLog.id)
    }

    @Transactional(readOnly = true)
    override fun listLog(eventId: String): List<SmsLogView> {
        eventScopeGuard.assertAccess(eventId)
        return smsLogPort.selectByEvent(eventId).map {
            SmsLogView(
                id = it.id, guestId = it.guestId, nameSnapshot = it.nameSnapshot, phone = it.phone,
                sentAt = toKstIso(it.sentAt), status = it.status, bodySnapshot = it.bodySnapshot,
            )
        }
    }

    /**
     * 외부 발송 호출 + 실패 흡수 — 스텁은 무-I/O·항상 성공이라 이 catch가 실질적으로 발동하지
     * 않는다. 실 네트워크 발송사로 교체되면 예외가 나도 이미 처리한 앞선 참석자들의 sms_log는
     * 그대로 남아야 하므로(무엇을 실제로 보냈는지 재현) 여기서 흡수해 status=실패로 기록하고
     * 다음 참석자로 계속 진행한다. 그 시점부터는 발송과 로그 기록을 각각 독립 트랜잭션으로
     * 커밋하는 구조(오케스트레이터 분리)로 전환해야 배치 도중 예외가 앞서 성공한 이력까지
     * 함께 롤백시키는 위험이 없다 — 지금은 스텁 구간에 외부 I/O가 없어 그 복잡도를 미리
     * 들이지 않는다.
     */
    private fun sendOne(phone: String, body: String): String = try {
        smsSenderPort.send(phone, body).status
    } catch (e: Exception) {
        log.warn("문자 발송 실패 phone={}", maskPhone(phone), e)
        SmsLog.STATUS_FAILED
    }

    /** 발송 대상 산정 — 누락자(blocked)·유효 대상(validTargets)·이미 발송 수(alreadySent)를 한 번에 계산한다. */
    private fun computeGate(eventId: String, excludeAlreadySent: Boolean): GateComputation {
        val liveGuests = guestPort.search(eventId, GuestSearchQuery(includeCancelled = false, paging = false))
        val sentGuestIds = smsLogPort.selectSentGuestIds(eventId).toSet()
        val alreadySent = liveGuests.count { it.id in sentGuestIds }
        val pool = if (excludeAlreadySent) liveGuests.filterNot { it.id in sentGuestIds } else liveGuests

        val blocked = mutableListOf<SmsBlockedGuest>()
        val validTargets = mutableListOf<GuestListItem>()
        for (guest in pool) {
            val missing = mutableListOf<String>()
            if (guest.name.isBlank()) missing += "이름"
            if (guest.phone.isNullOrBlank()) missing += "전화번호"
            if (missing.isEmpty()) validTargets += guest else blocked += SmsBlockedGuest(guest.id, guest.name, missing)
        }
        return GateComputation(
            validTargets = validTargets, blocked = blocked, alreadySent = alreadySent, canSend = blocked.isEmpty(),
        )
    }

    /** 발송 시점 이름 스냅샷 — "이름(소속)", 소속 없으면 이름만(spec proto `김민준(○○그룹)` 정합). */
    private fun nameSnapshot(guest: GuestListItem): String =
        if (guest.org.isNullOrBlank()) guest.name else "${guest.name}(${guest.org})"

    /** 로그에 전화번호 원문 노출 금지 — 뒤 4자리만 남기고 마스킹(PII). */
    private fun maskPhone(phone: String): String = phone.takeLast(4).padStart(phone.length, '*')

    private fun toKstIso(instant: Instant): String = instant.atZone(RESPONSE_ZONE).format(RESPONSE_FORMATTER)

    private data class GateComputation(
        val validTargets: List<GuestListItem>,
        val blocked: List<SmsBlockedGuest>,
        val alreadySent: Int,
        val canSend: Boolean,
    )

    companion object {
        private val RESPONSE_ZONE = ZoneId.of("Asia/Seoul")
        private val RESPONSE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx")
    }
}
