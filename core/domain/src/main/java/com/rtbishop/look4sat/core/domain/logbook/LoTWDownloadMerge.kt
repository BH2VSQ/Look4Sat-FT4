package com.rtbishop.look4sat.core.domain.logbook

/** Optional atomic download commit implemented by the source-scoped storage contribution. */
interface ITransactionalLoTWSync {
    suspend fun mergeLoTW(records: List<QsoRecord>, sourceKey: String, replaceExisting: Boolean): AdifImportResult
}

/** Standalone foreground sync remains non-destructive; combined with atomic storage it replaces the source. */
suspend fun IQsoRepository.mergeDownloadedLoTW(
    records: List<QsoRecord>, sourceKey: String, replaceExisting: Boolean
): AdifImportResult = if (this is ITransactionalLoTWSync) {
    mergeLoTW(records, sourceKey, replaceExisting)
} else {
    mergeLoTW(records)
}
