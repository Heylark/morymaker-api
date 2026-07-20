package kr.co.morymaker.api.storage

import kr.co.morymaker.api.application.port.out.FileStoragePort
import kr.co.morymaker.api.application.port.out.StoreFileCommand
import kr.co.morymaker.api.application.port.out.StoredFile
import kr.co.morymaker.api.config.MediaProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.regex.Pattern

/**
 * [FileStoragePort] 실 구현 — 서버 로컬 디스크 저장 + 스트리밍(§11-3, `FileStorageStubAdapter`
 * 대체). 저장 절차: magic byte 판별 → 크기 상한 → 디스크 스풀 → `ATOMIC_MOVE` 원자 커밋 →
 * 롤백 보상 등록. 어느 단계든 실패하면 `{root}/.tmp`에 흔적을 남기지 않는다(02-architect.md §6).
 *
 * 헥사고날 레이어: api-app(adapter). `internal`: application 계층은 [FileStoragePort]
 * 인터페이스만 의존한다.
 */
@Component
internal class LocalFileStorageAdapter(
    private val properties: MediaProperties,
) : FileStoragePort {

    private val log = LoggerFactory.getLogger(javaClass)

    private val root: Path
        get() = Path.of(properties.root).toAbsolutePath().normalize()

    override fun store(command: StoreFileCommand): StoredFile {
        require(UUID_PATTERN.matcher(command.eventId).matches()) { "행사 식별자 형식이 올바르지 않습니다" }
        require(UUID_PATTERN.matcher(command.contentId).matches()) { "콘텐츠 식별자 형식이 올바르지 않습니다" }

        // 선두만 읽는다(전체 로드 아님) — 확정 제약 3(서빙·저장 전체 로드 금지) 준수.
        val head = command.source.readNBytes(MediaSignature.HEAD_BYTES)
        val signature = MediaSignature.detect(head)
            ?: throw IllegalArgumentException("지원하지 않는 파일 형식입니다")
        require(signature.kind == command.declaredKind) { "선택한 콘텐츠 종류와 파일 형식이 일치하지 않습니다" }
        if (command.size > signature.maxBytes) {
            throw MediaTooLargeException("파일 용량이 상한(${signature.maxBytes / 1024 / 1024}MB)을 초과했습니다")
        }
        // 여기까지 통과해야 디스크 쓰기를 시작한다 — 위반 파일은 디스크에 흔적을 남기지 않는다.

        val root = this.root
        val tempDir = root.resolve(TEMP_DIR_NAME)
        Files.createDirectories(tempDir)
        val temp = Files.createTempFile(tempDir, "upload-", ".part")
        try {
            Files.newOutputStream(temp).use { out ->
                out.write(head)
                command.source.copyTo(out)
            }
            val target = resolveTarget(root, command.eventId, command.contentId)
            Files.createDirectories(target.parent)
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE)
            registerRollbackCompensation(target)
            log.info(
                "미디어 저장 완료 event={} content={} name={} kind={}",
                command.eventId.take(8),
                command.contentId.take(8),
                command.originalName,
                signature.kind,
            )
            return StoredFile(storageKey = "${command.eventId}/${command.contentId}", contentType = signature.mimeType)
        } finally {
            // 성공 경로에서는 이미 move로 사라진 뒤라 no-op, 실패 경로에서는 이 정리가 유일한 회수 지점이다.
            Files.deleteIfExists(temp)
        }
    }

    // DB의 storageKey도 신뢰하지 않는다 — eventId 접두 대조 + normalize 봉인을 여기서도 다시 적용한다
    // (cross-event 방어심층, CLAUDE.md "cross-event 방어심층 — 읽기 확장" 정합).
    override fun resolve(eventId: String, storageKey: String): Path? {
        val candidate = sealPath(eventId, storageKey) ?: return null
        if (!Files.isRegularFile(candidate)) {
            // 저장 루트 볼륨 미마운트 또는 파일 유실이 유일한 도달 경로 — "업로드 200인데 화면엔
            // 안 나옴"이라는 증상이 이 REQ가 고치려는 것과 구분 불가하므로 진단 로그가 유일한 단서다.
            log.error("미디어 파일 부재 storageKey={} root={}", storageKey, root)
            return null
        }
        return candidate
    }

    // 삭제는 부재(idempotent 재실행·구 메타 전용 행)가 정상 경로다 — resolve()의 ERROR 로그를
    // 그대로 재사용하면 정상 삭제마다 오탐 진단 로그가 쌓여 진짜 서빙 이상 신호가 묻힌다.
    // 트랜잭션 동기화가 활성 상태면 DB 메타 삭제가 커밋 확정된 뒤(afterCommit)에만 물리 파일을
    // 지운다 — 파일을 먼저 지우면 메타 삭제가 롤백됐을 때 "파일 없는데 메타 있음"(dangling
    // 메타)이 발생하기 때문이다. 트랜잭션 밖(배치·테스트)에서는 기다릴 커밋이 없으므로 즉시 삭제.
    override fun delete(eventId: String, storageKey: String) {
        val target = sealPath(eventId, storageKey) ?: return
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() {
                        deleteQuietly(target)
                    }
                },
            )
        } else {
            deleteQuietly(target)
        }
    }

    // resolve()·delete() 공통 경로 봉인(cross-event 접두 대조 + normalize + root 포함 검사) —
    // 파일 존재 검사·ERROR 로그는 포함하지 않는다(순수 경로 판정만, resolve 전용 진단은 호출부가 담당).
    private fun sealPath(eventId: String, storageKey: String): Path? {
        if (!storageKey.startsWith("$eventId/")) return null
        val root = this.root
        val candidate = root.resolve(storageKey).normalize()
        return if (candidate.startsWith(root)) candidate else null
    }

    private fun deleteQuietly(target: Path) {
        try {
            Files.deleteIfExists(target) // idempotent — 부재는 정상 경로(로그 없음)
        } catch (e: IOException) {
            // 메타는 이미 커밋 확정 — 파일 삭제 실패로 DB를 되돌리지 않는다(orphan 파일 < dangling 메타).
            // 재실행 가능한 디스크 잔여이므로 WARN(오류 아님)으로 남기고 삼킨다.
            log.warn("미디어 물리 삭제 실패(메타는 삭제 확정 완료) target={}", target, e)
        }
    }

    private fun resolveTarget(root: Path, eventId: String, contentId: String): Path {
        // 물리 경로는 서버 생성 UUID만으로 조립한다 — 사용자 입력(name)은 절대 관여하지 않는다.
        val target = root.resolve(eventId).resolve(contentId).normalize()
        check(target.startsWith(root)) { "저장 경로가 허용 범위를 벗어났습니다" }
        return target
    }

    // insert 실패 시 파일이 orphan으로 남지 않도록 자기 부작용을 자기 안에서 보상한다 —
    // 파일시스템 write는 DB 트랜잭션이 롤백돼도 되돌아가지 않기 때문이다. 포트 계약은 무변화
    // (application은 보상을 알 필요가 없다). 트랜잭션 동기화가 비활성이면(배치·테스트 등
    // 트랜잭션 밖 호출) 보상 등록 자체를 생략한다.
    private fun registerRollbackCompensation(target: Path) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCompletion(status: Int) {
                    if (status != TransactionSynchronization.STATUS_COMMITTED) {
                        Files.deleteIfExists(target)
                    }
                }
            },
        )
    }

    companion object {
        private const val TEMP_DIR_NAME = ".tmp"
        private val UUID_PATTERN: Pattern = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
        )
    }
}
