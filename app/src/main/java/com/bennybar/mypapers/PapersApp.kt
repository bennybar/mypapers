package com.bennybar.mypapers

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.bennybar.mypapers.ai.AiSettings
import com.bennybar.mypapers.ai.ExtractWorker
import com.bennybar.mypapers.ai.OpenAiClient
import com.bennybar.mypapers.data.DocumentDao
import com.bennybar.mypapers.data.DocumentFiles
import com.bennybar.mypapers.data.DocumentRepository
import com.bennybar.mypapers.data.PapersDatabase
import com.bennybar.mypapers.data.ServerSettings
import com.bennybar.mypapers.data.Session
import com.bennybar.mypapers.sync.Account
import com.bennybar.mypapers.sync.CouchClient
import com.bennybar.mypapers.sync.SyncEngine
import com.bennybar.mypapers.sync.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The app-wide objects (manual DI, as in Ilay). */
lateinit var app: PapersApp
    private set

class PapersApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var server: ServerSettings
    lateinit var repository: DocumentRepository
    lateinit var sync: SyncEngine
    lateinit var account: Account
    lateinit var ai: AiSettings
    val openAi = OpenAiClient()
    private lateinit var dao: DocumentDao
    private lateinit var files: DocumentFiles
    private var pendingSync: Job? = null

    override fun onCreate() {
        super.onCreate()
        app = this
        dao = PapersDatabase.open(this).documents()
        files = DocumentFiles(filesDir)
        server = ServerSettings(this)
        account = Account()
        ai = AiSettings(this)
        repository = DocumentRepository(
            dao, files, contentResolver, ai, openAi,
            onChanged = { requestSync() },
            onNeedsReading = { ExtractWorker.enqueue(this, it) },
        )
        sync = SyncEngine(dao, files, server) {
            server.session.value?.takeUnless { it.isExpired() }
                ?.let { CouchClient(ServerSettings.SERVER_URL, it.db, "Bearer ${it.token}") }
        }
        SyncWorker.schedule(this)
        // Catch up whenever the app comes to the front.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = requestSync(debounceMs = 0)
        })
    }

    /**
     * Signed in (or the token renewed). A database new to this phone: pull it
     * all, and upload what's here (papers added before signing in).
     */
    suspend fun onSignedIn(session: Session) {
        sync.whileIdle {
            if (server.syncedDb != session.db) {
                server.lastSeq = "0"
                dao.resetSync()
                server.syncedDb = session.db
            }
            server.saveSession(session)
        }
        requestSync(debounceMs = 0)
    }

    /** Signs out and removes this account's papers from the phone (they stay on the server). */
    suspend fun signOut() = sync.whileIdle {
        server.saveSession(null)
        dao.clear()
        files.deleteAll()
        server.lastSeq = "0"
        server.syncedDb = null
    }

    /** Syncs shortly after the last of a burst of local changes. */
    fun requestSync(debounceMs: Long = 2000) {
        pendingSync?.cancel()
        pendingSync = scope.launch {
            delay(debounceMs)
            // Its own job: a newer request cancels only the wait, never a running sync.
            scope.launch { sync.sync() }
        }
    }
}
