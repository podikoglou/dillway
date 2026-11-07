package com.dillway.agent

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/** Locks the object-name rule that decides what a compaction run may touch. */
class RecordingCompactorTest {

    @Test
    fun `a source object is a parquet without the shard prefix`() {
        assertTrue(
            RecordingCompactor.isSourceObject("recordings/legit/1674590916014.parquet"),
        )
        assertFalse(
            RecordingCompactor.isSourceObject(
                "recordings/legit/compacted-1674590916014-1674590999999.parquet",
            ),
        )
        assertFalse(RecordingCompactor.isSourceObject("recordings/legit/notes.txt"))
    }

    @Test
    fun `file name is the last key segment`() {
        assertEquals(
            "1674590916014.parquet",
            RecordingCompactor.fileName("recordings/legit/1674590916014.parquet"),
        )
    }
}
