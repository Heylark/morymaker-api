package kr.co.morymaker.api.application.port.out

/**
 * 파일 저장 경계(§11-3) — 실 구현은 계약 후 어댑터만 교체한다(`SmsSenderPort` 선례). 현재는
 * 무-네트워크 스텁(`FileStorageStubAdapter`, api-app)만 존재한다 — 실 바이트 저장·스토리지
 * 어댑터·원격 CMS는 범위 외(사용자 확정 이연).
 */
interface FileStoragePort {
    fun store(command: StoreFileCommand): StoredFile
}

/** [FileStoragePort.store] 입력. 확장 seam — 실 저장 도입 시 bytes 필드 additive 추가(`SmsSendResult`와 동일 정신). */
data class StoreFileCommand(val eventId: String, val name: String, val kind: String)

/** [FileStoragePort.store] 결과. 스텁은 항상 `fileUrl = null`. */
data class StoredFile(val fileUrl: String?)
