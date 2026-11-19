package com.cursorforandroid.data.repo

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.CursorApiException
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.domain.CursorUser
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * A cold start re-checks the stored key against `/v1/me`. A rejection is a sign-out: the key has to be gone, or the
 * next launch would sign back in with it. A server failure is not a rejection, and the stored key stays.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class SessionManagerRestoreAuthTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val cached = CursorUser(
        apiKeyName = "phone",
        email = "ada@example.com",
        firstName = "Ada",
        lastName = "Lovelace",
        userId = 7L,
    )

    @Test
    fun `a 401 on restore signs out and clears the stored key`() = runTest {
        val api = FakeCursorApi()
        api.failMe = CursorApiException(401, "unauthorized", "That key was revoked.")
        val keyStore = keyStore("restore-401")
        val prefs = prefs("restore-401")
        keyStore.setApiKey("key_stored")
        prefs.setCachedUser(cached)
        val scope = sessionJob()
        try {
            val session = session(api, keyStore, prefs, scope)
            session.restore()
            scope.children.toList().forEach { it.join() }

            assertThat(api.meCalls).isEqualTo(1)
            assertThat(session.state.value).isEqualTo(SessionState.SignedOut)
            assertThat(keyStore.apiKey()).isNull()
            assertThat(prefs.sessionSnapshot().cachedUser).isNull()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a 5xx on restore stays signed in and keeps the stored key`() = runTest {
        val api = FakeCursorApi()
        api.failMe = CursorApiException(500, "internal", "Cursor is down.")
        val keyStore = keyStore("restore-500")
        val prefs = prefs("restore-500")
        keyStore.setApiKey("key_stored")
        prefs.setCachedUser(cached)
        val scope = sessionJob()
        try {
            val session = session(api, keyStore, prefs, scope)
            session.restore()
            scope.children.toList().forEach { it.join() }

            assertThat(api.meCalls).isEqualTo(1)
            val signedIn = session.state.value as SessionState.SignedIn
            assertThat(signedIn.isDemo).isFalse()
            assertThat(signedIn.user).isEqualTo(cached)
            assertThat(keyStore.apiKey()).isEqualTo("key_stored")
            assertThat(prefs.sessionSnapshot().cachedUser).isEqualTo(cached)
        } finally {
            scope.cancel()
        }
    }

    private fun keyStore(name: String) = SecureKeyStore(context) {
        context.getSharedPreferences(name, Context.MODE_PRIVATE)
    }

    /** A settings file this test owns, so a sign-out cannot wipe the process-wide store other tests read. */
    private fun prefs(name: String): PreferencesStore {
        val file = File(context.cacheDir, "$name.preferences_pb")
        val store = PreferenceDataStoreFactory.create(produceFile = { file })
        return PreferencesStore(context, store)
    }

    /**
     * Not a child of the test: a [SupervisorJob] stays active for the life of the session, and [runTest] would
     * otherwise wait it out. Callers cancel it. Children are joined so `/v1/me` has finished before the asserts.
     */
    private fun sessionJob(): CompletableJob = SupervisorJob()

    private fun kotlinx.coroutines.test.TestScope.session(
        api: FakeCursorApi,
        keyStore: SecureKeyStore,
        prefs: PreferencesStore,
        job: CompletableJob,
    ): SessionManager {
        val backend = CursorBackend(api, FakeRunStreamer(), isDemo = false)
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return SessionManager(
            keyStore,
            prefs,
            backend,
            CursorBackend(api, FakeRunStreamer(), isDemo = true),
            scope = CoroutineScope(job + dispatcher),
        )
    }
}
