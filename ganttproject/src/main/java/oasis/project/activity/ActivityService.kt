/*
 * Copyright 2026 Oasis Project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package oasis.project.activity

import oasis.project.model.OasisProjectData
import oasis.project.model.OasisProjectState
import java.time.Clock
import java.util.Collections
import java.util.UUID

/**
 * Commits validated changes only to Oasis data, without accessing native tasks.
 * Task UIDs are opaque links; existence and import mapping belong to integration layers.
 */
class ActivityService(
  private val data: OasisProjectData,
  private val clock: Clock = Clock.systemUTC(),
  private val idSupplier: () -> ActivityId = { ActivityId(UUID.randomUUID()) }
) {
  fun get(id: ActivityId): ActivityRecord? = data.state.activities[id]

  /** In insertion order; occurrence-time presentation sorting belongs to the caller. */
  fun forTask(taskUid: TaskUid): List<ActivityRecord> = Collections.unmodifiableList(
    data.activityIdsByTask[taskUid].orEmpty().map { data.state.activities.getValue(it) }
  )

  fun add(taskUid: TaskUid, content: ActivityContent): ActivityRecord {
    val id = idSupplier()
    require(id !in data.state.activities) { "Activity ID already exists: $id" }
    val now = clock.instant()
    val record = content.toRecord(id, taskUid, now, now)
    data.replaceState(OasisProjectState(data.state.activities.values + record))
    return record
  }

  /** An edit cannot change ownership, identity, or creation time. Normalized no-ops do not commit. */
  fun edit(id: ActivityId, content: ActivityContent): ActivityRecord {
    val previous = requireNotNull(get(id)) { "Unknown activity ID: $id" }
    val candidate = content.toRecord(id, previous.taskUid, previous.createdAt, previous.modifiedAt)
    if (candidate == previous) {
      return previous
    }
    val updated = candidate.copy(modifiedAt = clock.instant())
    data.replaceState(OasisProjectState(data.state.activities.values.map { if (it.id == id) updated else it }))
    return updated
  }

  /** Returns false for an already absent ID, without changing the revision. */
  fun delete(id: ActivityId): Boolean {
    if (get(id) == null) {
      return false
    }
    data.replaceState(OasisProjectState(data.state.activities.values.filterNot { it.id == id }))
    return true
  }
}
