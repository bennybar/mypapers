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
import com.bennybar.mypapers.ai.AiAnswers
import com.bennybar.mypapers.ai.AiNotifications
import com.bennybar.mypapers.data.DocumentDao
import com.bennybar.mypapers.gmail.GmailImporter
import com.bennybar.mypapers.gmail.GmailNotifications
import com.bennybar.mypapers.gmail.GmailSettings
import com.bennybar.mypapers.data.DocumentFiles
import com.bennybar.mypapers.data.FormatSettings
import com.bennybar.mypapers.data.DocumentRepository
import com.bennybar.mypapers.data.PapersDatabase
import com.bennybar.mypapers.data.DriveSettings
import com.bennybar.mypapers.data.ServerSettings
import com.bennybar.mypapers.reminders.ExpiryReminders
import com.bennybar.mypapers.sync.Account
import com.bennybar.mypapers.sync.CouchClient
import com.bennybar.mypapers.sync.DriveAuth
import com.bennybar.mypapers.sync.DriveClient
import com.bennybar.mypapers.sync.DriveSync
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
    /** The old CouchDB server, only to move papers from it to Drive. */
    lateinit var server: ServerSettings
    private lateinit var couch: SyncEngine
    lateinit var drive: DriveSettings
    lateinit var repository: DocumentRepository
    lateinit var sync: DriveSync
    lateinit var account: Account
    lateinit var ai: AiSettings
    /** Answers from Ask AI jobs, waiting to be seen. */
    lateinit var answers: AiAnswers
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
        drive = DriveSettings(this)
        account = Account()
        ai = AiSettings(this)
        answers = AiAnswers(this)
        repository = DocumentRepository(
            dao, files, contentResolver, ai, openAi,
            onChanged = { requestSync() },
            onNeedsReading = { ExtractWorker.enqueue(this, it) },
            onReadAll = { all -> ReadAllWorker.start(this, all) },
        )
        couch = SyncEngine(dao, files, server) {
            server.session.value?.takeUnless { it.isExpired() }
                ?.let { CouchClient(ServerSettings.SERVER_URL, it.db, "Bearer ${it.token}") }
        }
        sync = DriveSync(dao, files, drive) {
            drive.email.value?.let { email ->
                DriveClient(
                    token = { DriveAuth.token(this, email) },
                    clearToken = { DriveClient.clearCachedToken(this, it) },
                )
            }
        }
        gmail = GmailImporter(this, db.gmailCandidates(), GmailSettings(this), repository)
        GmailNotifications.createChannels(this)
        ExpiryReminders.createChannel(this)
        AiNotifications.createChannels(this)
        ExpiryReminders.schedule(this)
        gmail.scheduleSweep()
        // Signed out by an older version, which left Gmail connected.
        if (drive.email.value == null && server.session.value == null && gmail.settings.config.value.isConnected) scope.launch { gmail.disconnect() }
        SyncWorker.schedule(this)
        // Catch up whenever the app comes to the front; with automatic reading on, also read papers
        // that came in while there was no key, or synced from elsewhere (not on every background start).
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                requestSync(debounceMs = 0)
                scope.launch {
                    repository.purgeTrash()
                    repository.fillFullText()
                    ExpiryReminders.check(this@PapersApp)
                }
                if (ai.config.value.autoRead) scope.launch { if (repository.unreadIds().isNotEmpty()) repository.readUnread() }
            }
        })
    }

    /**
     * Signed in, with Drive access allowed. An account new to this phone: read all of its Drive,
     * and upload what's here (papers added before signing in). Coming from the old server: first
     * bring everything from it to this phone (the server keeps its copy), so nothing is left behind.
     */
    suspend fun connectDrive(email: String) {
        if (server.session.value != null) {
            if (server.session.value?.isExpired() == false) couch.sync().getOrThrow()
            check(dao.missingFiles().isEmpty()) { "Some files are still only on the old server. Try again when online." }
        }
        sync.whileIdle {
            if (drive.syncedEmail != email) {
                drive.pageToken = null
                drive.lastSyncAt = 0
                dao.resetSync()
                drive.syncedEmail = email
            }
            server.saveSession(null)
            drive.saveEmail(email)
        }
        requestSync(debounceMs = 0)
    }

    /**
     * Signs out and removes this account's papers from the phone (they stay in its Drive), and
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
                server.saveSession(null)
                drive.pageToken = null
                drive.lastSyncAt = 0
                drive.syncedEmail = null
                drive.saveEmail(null)
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
