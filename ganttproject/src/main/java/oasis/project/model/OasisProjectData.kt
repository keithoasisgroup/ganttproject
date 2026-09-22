/*
 * Copyright 2026 Oasis Project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package oasis.project.model

import oasis.project.activity.ActivityId
import oasis.project.activity.TaskUid
import java.util.Collections

/**
 * One holder per project, accessed on the project's model thread.
 * Replacing a snapshot rebuilds the derived index and preserves stored audit timestamps.
 * Revision is a local change counter, not persisted history.
 */
class OasisProjectData(initialState: OasisProjectState = OasisProjectState()) {
  var state: OasisProjectState = initialState
    private set
  var revision: Long = 0
    private set
  var activityIdsByTask: Map<TaskUid, List<ActivityId>> = index(initialState)
    private set

  fun replaceState(newState: OasisProjectState) {
    // Map equality ignores insertion order, which is part of the persisted Activity history.
    if (state == newState && state.activities.keys.toList() == newState.activities.keys.toList()) {
      return
    }
    val newIndex = index(newState)
    state = newState
    activityIdsByTask = newIndex
    revision++
  }

  fun clear() {
    replaceState(OasisProjectState())
  }

  private fun index(snapshot: OasisProjectState): Map<TaskUid, List<ActivityId>> = Collections.unmodifiableMap(
    snapshot.activities.values.groupBy { it.taskUid }.mapValues { (_, records) ->
      Collections.unmodifiableList(records.map { it.id })
    }
  )
}
