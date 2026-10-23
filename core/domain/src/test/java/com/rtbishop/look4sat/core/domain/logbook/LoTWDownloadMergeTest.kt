package com.rtbishop.look4sat.core.domain.logbook

import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class LoTWDownloadMergeTest {
    private fun legacy(onMerge: () -> Unit): IQsoRepository =
        Proxy.newProxyInstance(IQsoRepository::class.java.classLoader, arrayOf(IQsoRepository::class.java)) { _, method, _ ->
            check(method.name == "mergeLoTW")
            onMerge()
            AdifImportResult(1, 0, 0)
        } as IQsoRepository

    @Test fun standaloneForegroundSyncRetainsTheNonDestructiveMerge() = runTest {
        var called = false
        legacy { called = true }.mergeDownloadedLoTW(emptyList(), "BA7OPF", true)
        assertTrue(called)
    }

    @Test fun combinedStorageReceivesTheAccountAndFullReplacementFlag() = runTest {
        var seen: Pair<String, Boolean>? = null
        val repository = object : IQsoRepository by (legacy { fail("Legacy merge must not run") }), ITransactionalLoTWSync {
            override suspend fun mergeLoTW(records: List<QsoRecord>, sourceKey: String, replaceExisting: Boolean): AdifImportResult {
                seen = sourceKey to replaceExisting
                return AdifImportResult(0, 0, 0)
            }
        }
        repository.mergeDownloadedLoTW(emptyList(), "BA7OPF", true)
        assertEquals("BA7OPF" to true, seen)
    }

    @Test fun atomicFailureDoesNotFallBackToAnotherWrite() = runTest {
        val repository = object : IQsoRepository by (legacy { fail("Failure must not fall back") }), ITransactionalLoTWSync {
            override suspend fun mergeLoTW(records: List<QsoRecord>, sourceKey: String, replaceExisting: Boolean): AdifImportResult {
                throw java.io.IOException("simulated rollback")
            }
        }
        assertTrue(runCatching { repository.mergeDownloadedLoTW(emptyList(), "BA7OPF", true) }.exceptionOrNull() is java.io.IOException)
    }
}
