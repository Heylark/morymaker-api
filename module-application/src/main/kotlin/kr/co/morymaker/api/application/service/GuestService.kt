package kr.co.morymaker.api.application.service

import kr.co.morymaker.api.application.port.`in`.GuestImportRow
import kr.co.morymaker.api.application.port.`in`.GuestListResult
import kr.co.morymaker.api.application.port.`in`.GuestSearchCommand
import kr.co.morymaker.api.application.port.`in`.GuestUseCase
import kr.co.morymaker.api.application.port.`in`.ImportConfirmResult
import kr.co.morymaker.api.application.port.`in`.ImportPreviewResult
import kr.co.morymaker.api.application.port.`in`.InvalidImportRow
import kr.co.morymaker.api.application.port.`in`.RegisterGuestCommand
import kr.co.morymaker.api.application.port.`in`.UpdateGuestCommand
import kr.co.morymaker.api.application.port.out.GuestListItem
import kr.co.morymaker.api.application.port.out.GuestPort
import kr.co.morymaker.api.application.port.out.GuestSearchQuery
import kr.co.morymaker.api.application.port.out.SmsLogPort
import kr.co.morymaker.api.application.port.out.toGuest
import kr.co.morymaker.api.application.security.EventScopeGuard
import kr.co.morymaker.api.domain.guest.Guest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * [GuestUseCase] 구현체 — 명단 CRUD(§4-1~4-4)·엑셀 병합(§4-5·4-6)을 담당한다.
 *
 * 신규 생성·주차 지연매칭(§4-10)·토큰 발급의 실제 쓰기 코어는 [GuestWriteSupport]로 위임한다
 * (공개 경로 서비스와 공유하는 SSOT). 이 클래스는 `assertAccess`(행사 스코프 게이트)를 항상
 * 먼저 호출한 뒤에만 위임하므로 인증 경로의 동작은 리팩터 전과 byte-identical하다.
 *
 * 헥사고날 레이어: application(service). `internal`: api-app은 [GuestUseCase] 인터페이스만
 * 의존한다.
 */
@Service
internal class GuestService(
    private val guestPort: GuestPort,
    private val eventScopeGuard: EventScopeGuard,
    private val guestWriteSupport: GuestWriteSupport,
    private val smsLogPort: SmsLogPort,
) : GuestUseCase {

    @Transactional(readOnly = true)
    override fun listGuests(eventId: String, query: GuestSearchCommand): GuestListResult {
        eventScopeGuard.assertAccess(eventId)
        val searchQuery = GuestSearchQuery(
            q = query.q?.trim()?.takeIf { it.isNotBlank() },
            status = query.status,
            src = query.src,
            includeCancelled = query.includeCancelled,
            pageNo = query.page,
            pageSize = query.size,
            paging = true,
        )
        val items = guestPort.search(eventId, searchQuery)
        val total = guestPort.countSearch(eventId, searchQuery.copy(paging = false))
        // searchState(§4-9)는 q가 있을 때만 계산 — 전체 매칭 건수(total) 기준(현재 페이지 건수 아님).
        val searchState = searchQuery.q?.let {
            when {
                total == 0 -> GuestListResult.SEARCH_STATE_NONE
                total == 1 -> GuestListResult.SEARCH_STATE_ONE
                else -> GuestListResult.SEARCH_STATE_MANY
            }
        }
        return GuestListResult(items = items, total = total, searchState = searchState)
    }

    @Transactional(readOnly = true)
    override fun getGuest(eventId: String, gid: String): GuestListItem {
        eventScopeGuard.assertAccess(eventId)
        return guestPort.fetchDetailById(eventId, gid) ?: throw NoSuchElementException("참석자를 찾을 수 없습니다")
    }

    @Transactional
    override fun registerGuest(eventId: String, command: RegisterGuestCommand): Guest {
        eventScopeGuard.assertAccess(eventId)
        return guestWriteSupport.createGuest(eventId, command)
    }

    @Transactional
    override fun updateGuest(eventId: String, gid: String, command: UpdateGuestCommand): Guest {
        eventScopeGuard.assertAccess(eventId)
        val existing = guestPort.fetchById(eventId, gid) ?: throw NoSuchElementException("참석자를 찾을 수 없습니다")
        val merged = existing.with(
            name = command.name ?: existing.name,
            org = command.org ?: existing.org,
            title = command.title ?: existing.title,
            phone = command.phone ?: existing.phone,
            plate = command.plate ?: existing.plate,
            seatGroupId = command.seatGroupId ?: existing.seatGroupId,
        )
        // 필드 변경분을 먼저 저장 — mapGuestParking은 매칭 성공 시에만 자체적으로 저장하므로,
        // plate가 바뀌었는데 매칭에 실패한 경우에도 나머지 필드 변경은 반드시 반영돼야 한다.
        guestPort.update(merged)
        val plateChanged = command.plate != null && command.plate != existing.plate
        return if (plateChanged && !merged.plate.isNullOrBlank()) {
            guestWriteSupport.mapGuestParking(eventId, merged)
        } else {
            merged
        }
    }

    @Transactional
    override fun cancelGuest(eventId: String, gid: String, deleteSmsLog: Boolean): Guest {
        eventScopeGuard.assertAccess(eventId)
        val existing = guestPort.fetchById(eventId, gid) ?: throw NoSuchElementException("참석자를 찾을 수 없습니다")
        val cancelled = existing.with(status = Guest.STATUS_CANCELLED)
        guestPort.update(cancelled)
        // deleteSmsLog=false(디폴트)면 이 호출 자체가 없다 — 참석자 취소 처리만 수행하고
        // 발송 이력은 그대로 보존한다. true일 때만 같은 트랜잭션 안에서 함께 삭제한다.
        if (deleteSmsLog) smsLogPort.deleteByGuest(eventId, gid)
        return cancelled
    }

    @Transactional(readOnly = true)
    override fun previewImport(eventId: String, rows: List<GuestImportRow>): ImportPreviewResult {
        eventScopeGuard.assertAccess(eventId)
        val classification = classifyImportRows(eventId, rows)
        return ImportPreviewResult(
            newCount = classification.newRows.size,
            updatedCount = classification.updatedPairs.size,
            excludedCount = classification.excludedGuests.size,
            invalidRows = classification.invalidRows,
        )
    }

    @Transactional
    override fun confirmImport(eventId: String, rows: List<GuestImportRow>): ImportConfirmResult {
        eventScopeGuard.assertAccess(eventId)
        val classification = classifyImportRows(eventId, rows)

        classification.newRows.forEach { row ->
            val guest = Guest(
                id = UUID.randomUUID().toString(),
                eventId = eventId,
                name = requireNotNull(row.name).trim(),
                org = row.org,
                title = row.title,
                phone = row.phone,
                plate = row.plate,
                seatGroupId = null,
                status = Guest.STATUS_WAITING,
                src = Guest.SRC_PRE,
                visitAt = null,
                token = guestWriteSupport.generateUniqueToken(),
                createdAt = Instant.now(),
            )
            guestPort.insert(guest)
            if (!guest.plate.isNullOrBlank()) guestWriteSupport.mapGuestParking(eventId, guest)
        }

        classification.updatedPairs.forEach { (existing, row) ->
            // token·status는 보존(D1 — 재업로드 시 기존 신원 유지, §4-6).
            val merged = existing.with(
                name = row.name?.trim() ?: existing.name,
                org = row.org ?: existing.org,
                title = row.title ?: existing.title,
                phone = row.phone ?: existing.phone,
                plate = row.plate ?: existing.plate,
            )
            guestPort.update(merged)
            val plateChanged = row.plate != null && row.plate != existing.plate
            if (plateChanged && !merged.plate.isNullOrBlank()) guestWriteSupport.mapGuestParking(eventId, merged)
        }

        classification.excludedGuests.forEach { guest ->
            guestPort.update(guest.with(status = Guest.STATUS_CANCELLED))
        }

        return ImportConfirmResult(
            newCount = classification.newRows.size,
            updatedCount = classification.updatedPairs.size,
            cancelledCount = classification.excludedGuests.size,
            invalidRows = classification.invalidRows,
            tokenPreserved = true,
        )
    }

    /**
     * 엑셀 업로드 행 분류(§4-5·§4-6 공유 로직, drift 방지) — preview는 이 결과만 요약해 반환하고
     * confirm은 동일 결과로 실제 insert/update/취소를 수행한다.
     *
     * 매칭 키 = (정규화 name, 정규화 phone) 완전일치. phone 없는 행은
     * 이름 단독 매칭(동명이인 오판 위험)을 피하기 위해 항상 신규로 분류한다.
     */
    private fun classifyImportRows(eventId: String, rows: List<GuestImportRow>): ImportClassification {
        val liveGuests = guestPort
            .search(eventId, GuestSearchQuery(includeCancelled = false, paging = false))
            .map { it.toGuest() }
        val byMatchKey = liveGuests
            .filter { !it.phone.isNullOrBlank() }
            .associateBy { normalizeName(it.name) to normalizePhone(it.phone!!) }

        val invalidRows = mutableListOf<InvalidImportRow>()
        val newRows = mutableListOf<GuestImportRow>()
        val updatedPairs = mutableListOf<Pair<Guest, GuestImportRow>>()
        val matchedGuestIds = mutableSetOf<String>()

        for (row in rows) {
            val name = row.name?.trim()
            if (name.isNullOrBlank()) {
                invalidRows += InvalidImportRow(row.rowNumber, "이름 누락")
                continue
            }
            val phone = row.phone?.trim()
            val matched = if (phone.isNullOrBlank()) null else byMatchKey[normalizeName(name) to normalizePhone(phone)]
            if (matched == null) {
                newRows += row
            } else {
                updatedPairs += matched to row
                matchedGuestIds += matched.id
            }
        }

        val excludedGuests = liveGuests.filter { it.id !in matchedGuestIds }
        return ImportClassification(newRows, updatedPairs, excludedGuests, invalidRows)
    }

    private data class ImportClassification(
        val newRows: List<GuestImportRow>,
        val updatedPairs: List<Pair<Guest, GuestImportRow>>,
        val excludedGuests: List<Guest>,
        val invalidRows: List<InvalidImportRow>,
    )

    companion object {
        private fun normalizeName(name: String): String = name.trim()
        private fun normalizePhone(phone: String): String = phone.filter { it.isDigit() }
    }
}
