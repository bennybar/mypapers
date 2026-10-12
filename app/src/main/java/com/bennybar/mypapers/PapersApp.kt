package com.bennybar.mypapers

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.WorkManager
import com.bennybar.mypapers.ai.AiSettings
import com.bennybar.mypapers.ai.ExtractWorker
import com.bennybar.mypapers.ai.OpenAiClient
import com.bennybar.mypapers.ai.ReadAllWorker
import com.bennybar.mypapers.data.DocumentDao
import com.bennybar.mypapers.gmail.GmailImporter
import com.bennybar.mypapers.gmail.GmailNotifications
import com.bennybar.mypapers.gmail.GmailSettings
import com.bennybar.mypapers.data.DocumentFiles
import com.bennybar.mypapers.data.FormatSettings
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
    lateinit var gmail: GmailImporter
    lateinit var formats: FormatSettings
    val openAi = OpenAiClient()
    private lateinit var dao: DocumentDao
    private lateinit var files: DocumentFiles
    private var pendingSync: Job? = null

    override fun onCreate() {
        super.onCreate()
        app = this
        formats = FormatSettings(this)
        val db = PapersDatabase.open(this)
        dao = db.documents()
        files = DocumentFiles(filesDir)
        server = ServerSettings(this)
        account = Account()
        ai = AiSettings(this)
        repository = DocumentRepository(
            dao, files, contentResolver, ai, openAi,
            onChanged = { requestSync() },
            onNeedsReading = { ExtractWorker.enqueue(this, it) },
            onReadAll = { all -> ReadAllWorker.start(this, all) },
        )
        sync = SyncEngine(dao, files, server) {
            server.session.value?.takeUnless { it.isExpired() }
                ?.let { CouchClient(ServerSettings.SERVER_URL, it.db, "Bearer ${it.token}") }
        }
        gmail = GmailImporter(this, db.gmailCandidates(), GmailSettings(this), repository)
        GmailNotifications.createChannels(this)
        gmail.scheduleSweep()
        // Signed out by an older version, which left Gmail connected.
        if (server.session.value == null && gmail.settings.config.value.isConnected) scope.launch { gmail.disconnect() }
        SyncWorker.schedule(this)
        // Catch up whenever the app comes to the front; with automatic reading on, also read papers
        // that came in while there was no key, or synced from elsewhere (not on every background start).
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                requestSync(debounceMs = 0)
                if (ai.config.value.autoRead) scope.launch { if (repository.unreadIds().isNotEmpty()) repository.readUnread() }
            }
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

    /**
     * Signs out and removes this account's papers from the phone (they stay on the server), and
     * disconnects Gmail. In the app's scope, so leaving the screen can't stop it halfway; the papers
     * go first, so none can be uploaded into the next account.
     */
    fun signOut() {
        scope.launch {
            val work = WorkManager.getInstance(this@PapersApp)
            work.cancelUniqueWork(ReadAllWorker.NAME)
            work.cancelAllWorkByTag(ExtractWorker.TAG)
            gmail.disconnect()
            sync.whileIdle {
                dao.clear()
                files.deleteAll()
                server.lastSeq = "0"
                server.syncedDb = null
                server.saveSession(null)
            }
        }
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
