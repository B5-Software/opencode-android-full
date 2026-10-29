package io.github.opencodeandroid.runtime

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class WorkspaceSelectionStoreTest {
    private fun fixture(test: (android.content.SharedPreferences) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "workspace-resume-fixture-${UUID.randomUUID()}"
        try { test(context.getSharedPreferences(name, Context.MODE_PRIVATE)) }
        finally { context.deleteSharedPreferences(name) }
    }
    @Test fun firstLaunchAndLegacyDaemonHomeUseHelloWorld() = fixture { preferences ->
        val store = WorkspaceSelectionStore(preferences)
        assertEquals(WorkspaceSelection("/workspace/helloworld"), store.read())
        for (directory in listOf("/root", "/")) {
            preferences.edit().putString("directory", directory).commit()
            assertEquals(WorkspaceSelection("/workspace/helloworld"), store.read())
        }
        preferences.edit().putString("directory", "/workspace/existing-project").commit()
        assertEquals("/workspace/existing-project", store.read().directory)
    }
    @Test fun projectAndSessionArePersistedTogetherForNewStore() = fixture { preferences ->
        val directory = "/workspace/项目 ' dollar ${'$'} and spaces"
        WorkspaceSelectionStore(preferences).remember(directory, "session_fixture_1")
        assertEquals(WorkspaceSelection(directory, "session_fixture_1"), WorkspaceSelectionStore(preferences).read())
        assertEquals(setOf("directory", "session-id"), preferences.all.keys)
    }
    @Test fun changingProjectDropsTheOldSession() = fixture { preferences ->
        val store = WorkspaceSelectionStore(preferences)
        store.remember("/workspace/a", "session_a"); store.select("/workspace/b")
        assertEquals(WorkspaceSelection("/workspace/b"), store.read())
    }
    @Test fun invalidPersistedValuesDoNotBecomeNavigationUrls() = fixture { preferences ->
        preferences.edit().putString("directory", "relative").putString("session-id", "https://fixture.invalid").commit()
        assertEquals(WorkspaceSelection(WorkspaceContext.STARTER_DIRECTORY), WorkspaceSelectionStore(preferences).read())
        preferences.edit().putInt("directory", 42).putInt("session-id", 42).commit()
        assertEquals(WorkspaceSelection(WorkspaceContext.STARTER_DIRECTORY), WorkspaceSelectionStore(preferences).read())
    }
}
