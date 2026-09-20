/*
 * Copyright 2026 Oasis Project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package oasis.project.model

import oasis.project.activity.ActivityId
import oasis.project.activity.ActivityRecord
import java.util.Collections

/** Immutable, defensively copied project snapshot. The task index is deliberately not part of it. */
class OasisProjectState(activities: Collection<ActivityRecord> = emptyList()) {
  val activities: Map<ActivityId, ActivityRecord>

  init {
    val byId = LinkedHashMap<ActivityId, ActivityRecord>()
    activities.forEach {
      require(byId.putIfAbsent(it.id, it) == null) { "Duplicate activity ID: ${it.id}" }
    }
    this.activities = Collections.unmodifiableMap(byId)
  }

  override fun equals(other: Any?): Boolean = other is OasisProjectState && activities == other.activities

  override fun hashCode(): Int = activities.hashCode()
}
