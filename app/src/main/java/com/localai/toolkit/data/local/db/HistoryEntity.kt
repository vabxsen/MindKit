package com.localai.toolkit.data.local.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.localai.toolkit.domain.model.HistoryItem
import com.localai.toolkit.domain.model.HistoryType

/**
 * One stored result.
 *
 * The type is persisted as its enum *name* rather than its ordinal so that reordering
 * [HistoryType] can never silently re-label existing rows.
 */
@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "type")
    val type: String,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "input_preview")
    val inputPreview: String,

    @ColumnInfo(name = "output")
    val output: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "metadata")
    val metadata: String? = null,
)

fun HistoryEntity.toDomain(): HistoryItem = HistoryItem(
    id = id,
    // An unrecognised name means the row was written by a newer build; show it rather
    // than dropping the user's data.
    type = runCatching { HistoryType.valueOf(type) }.getOrDefault(HistoryType.ASK),
    title = title,
    inputPreview = inputPreview,
    output = output,
    createdAtEpochMillis = createdAt,
    metadata = metadata,
)

fun HistoryItem.toEntity(): HistoryEntity = HistoryEntity(
    id = id,
    type = type.name,
    title = title,
    inputPreview = inputPreview,
    output = output,
    createdAt = createdAtEpochMillis,
    metadata = metadata,
)
