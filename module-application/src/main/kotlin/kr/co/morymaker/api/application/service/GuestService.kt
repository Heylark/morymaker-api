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
import kr.co.morymaker.api.application.port.out.ParkingLinkPort
import kr.co.morymaker.api.application.port.out.toGuest
import kr.co.morymaker.api.application.security.EventScopeGuard
import kr.co.morymaker.api.domain.guest.Guest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * [GuestUseCase] 구현체 — 명단 CRUD(§4-1~4-4)·엑셀 병합(§4-5·4-6)·주차 지연매칭(§4-10)을 담당한다.
 *
 * 헥사고날 레이어: application(service). `internal`: api-app은 [GuestUseCase] 인터페이스만
 * 의존한다.
 */
@Service
internal class GuestService(
    private val guestPort: GuestPort,
    private val parkingLinkPort: ParkingLinkPort,
    private val eventScopeGuard: EventScopeGuard,
    private val tokenGenerator: GuestTokenGenerator,
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
        val guest = Guest(
            id = UUID.randomUUID().toString(),
            eventId = eventId,
            name = command.name,
            org = command.org,
            title = command.title,
            phone = command.phone,
            plate = command.plate,
            seatGroupId = command.seatGroupId,
            status = Guest.STATUS_WAITING,
            src = command.src ?: Guest.SRC_ONSITE,
            visitAt = null,
            token = generateUniqueToken(),
            createdAt = Instant.now(),
        )
        guestPort.insert(guest)
        return if (!guest.plate.isNullOrBlank()) mapGuestParking(eventId, guest) else guest
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
        return if (plateChanged && !merged.plate.isNullOrBlank()) mapGuestParking(eventId, merged) else merged
    }

    @Transactional
    override fun cancelGuest(eventId: String, gid: String, deleteSmsLog: Boolean): Guest {
        eventScopeGuard.assertAccess(eventId)
        val existing = guestPort.fetchById(eventId, gid) ?: throw NoSuchElementException("참석자를 찾을 수 없습니다")
        val cancelled = existing.with(status = Guest.STATUS_CANCELLED)
        guestPort.update(cancelled)
        // deleteSmsLog=true 케이스의 sms_log 동반 삭제는 문자 도메인이 아직 이 서버에 없어(§7
        // 후속 REQ) 이번 범위에서는 파라미터만 수용하고 실제 삭제는 수행하지 않는다 — 참석자
        // 취소 처리 자체는 그대로 완료된다.
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
                token = generateUniqueToken(),
                createdAt = Instant.now(),
            )
            guestPort.insert(guest)
            if (!guest.plate.isNullOrBlank()) mapGuestParking(eventId, guest)
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
            if (plateChanged && !merged.plate.isNullOrBlank()) mapGuestParking(eventId, merged)
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
     * 주차↔참석자 역방향 지연매칭(§4-10, D5 guest 방향 최소 범위) — plate 완전일치로 활성
     * 주차기록을 찾아 guest_id를 백필하고, 대기 상태였다면 방문으로 전이한다.
     *
     * 매칭 실패(활성 기록 없음)는 정상 상태이며 아무 것도 갱신하지 않는다 — plate 없는 손님과
     * 동일하게 취급한다.
     */
    private fun mapGuestParking(eventId: String, guest: Guest): Guest {
        val plate = guest.plate
        if (plate.isNullOrBlank()) return guest
        val recordId = parkingLinkPort.findActiveRecordIdByPlate(eventId, normalizePlate(plate)) ?: return guest
        parkingLinkPort.linkGuest(recordId, guest.id)
        val updated = if (guest.status == Guest.STATUS_WAITING) {
            guest.with(status = Guest.STATUS_VISITED, visitAt = Instant.now())
        } else {
            guest
        }
        guestPort.update(updated)
        return updated
    }

    private fun generateUniqueToken(): String {
        repeat(MAX_TOKEN_RETRY) {
            val candidate = tokenGenerator.generate()
            if (!guestPort.existsByToken(candidate)) return candidate
        }
        throw IllegalStateException("체크인 토큰 생성에 반복 실패했습니다")
    }

    /**
     * 엑셀 업로드 행 분류(§4-5·§4-6 공유 로직, drift 방지) — preview는 이 결과만 요약해 반환하고
     * confirm은 동일 결과로 실제 insert/update/취소를 수행한다.
     *
     * 매칭 키(D1, ADR-IMPORT-MATCH-KEY) = (정규화 name, 정규화 phone) 완전일치. phone 없는 행은
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
        private const val MAX_TOKEN_RETRY = 5

        private fun normalizeName(name: String): String = name.trim()
        private fun normalizePhone(phone: String): String = phone.filter { it.isDigit() }
        private fun normalizePlate(plate: String): String = plate.replace(" ", "").trim()
    }
}
