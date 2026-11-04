package com.apex.backup

import com.apex.backup.diagnostics.RootDiagnosticsRunner
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun applicationId_isExpected() {
        assertEquals("com.apex.backup", BuildConfig.APPLICATION_ID)
    }

    @Test
    fun rootIdentity_acceptsUidZero() {
        assertTrue(RootDiagnosticsRunner.isRootIdentity("uid=0(root) gid=0(root)"))
        assertTrue(RootDiagnosticsRunner.isRootIdentity("uid=0 gid=0"))
    }

    @Test
    fun rootIdentity_rejectsNonRootUid() {
        assertFalse(RootDiagnosticsRunner.isRootIdentity("uid=2000(shell) gid=2000(shell)"))
        assertFalse(RootDiagnosticsRunner.isRootIdentity("command not found"))
    }
}
