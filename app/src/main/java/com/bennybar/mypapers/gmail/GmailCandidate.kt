package com.bennybar.mypapers.gmail

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Something found in Gmail that may be a paper: an attachment, or a receipt
 * written in the email itself (imported as a PDF of the email). Local to this
 * phone — what gets imported becomes a normal, synced document.
 */
@Entity(tableName = "gmail_candidates")
data class GmailCandidate(
    /** "<Gmail message id>:<part id>". */
    @PrimaryKey val key: String,
    /** The Gmail API's message id. */
    val gmailId: String,
    val subject: String,
    val sender: String,
    /** When the email was sent (epoch ms). */
    val sentAt: Long,
    /** [KIND_ATTACHMENT] or [KIND_EMAIL]. */
    val kind: String,
    /** The part's id in the Gmail API's message payload (e.g. "1"); "" for the email as a whole. */
    val partId: String,
    val fileName: String,
    val mimeType: String,
    val size: Long,
    /** [STATE_NEW], [STATE_QUEUED], [STATE_IMPORTED], [STATE_DISMISSED] or [STATE_FAILED]. */
    val state: String = STATE_NEW,
    val error: String? = null,
    /** The document it became. */
    val documentId: String? = null,
    val foundAt: Long,
) {
    companion object {
        const val KIND_ATTACHMENT = "attachment"
        const val KIND_EMAIL = "email"
        const val STATE_NEW = "new"
        const val STATE_QUEUED = "queued"
        const val STATE_IMPORTED = "imported"
        const val STATE_DISMISSED = "dismissed"
        const val STATE_FAILED = "failed"
    }
}

/** An email the scan already looked at, so later scans (or a resumed one) don't fetch it again. */
@Entity(tableName = "gmail_seen")
data class GmailSeen(@PrimaryKey val gmailId: String, val seenAt: Long)

@Dao
interface GmailCandidateDao {
    /** What's waiting for a decision (and what failed, to retry), newest email first. */
    @Query("SELECT * FROM gmail_candidates WHERE state IN ('new', 'failed', 'queued') ORDER BY sentAt DESC, key")
    fun observePending(): Flow<List<GmailCandidate>>

    @Query("SELECT COUNT(*) FROM gmail_candidates WHERE state = 'new'")
    fun observeNewCount(): Flow<Int>

    @Query("SELECT * FROM gmail_candidates WHERE state = 'queued' ORDER BY sentAt")
    suspend fun queued(): List<GmailCandidate>

    /** Keeps what's already known (and its state) — a rescan doesn't bring back dismissed items. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNew(candidates: List<GmailCandidate>): List<Long>

    @Query("UPDATE gmail_candidates SET state = :state, error = :error, documentId = :documentId WHERE key = :key")
    suspend fun setState(key: String, state: String, error: String? = null, documentId: String? = null)

    @Query("UPDATE gmail_candidates SET state = :state WHERE key IN (:keys)")
    suspend fun setStates(keys: List<String>, state: String)

    @Query("SELECT gmailId FROM gmail_seen")
    suspend fun seenIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun markSeen(seen: List<GmailSeen>)

    @Query("DELETE FROM gmail_candidates")
    suspend fun clear()

    @Query("DELETE FROM gmail_seen")
    suspend fun clearSeen()
}
