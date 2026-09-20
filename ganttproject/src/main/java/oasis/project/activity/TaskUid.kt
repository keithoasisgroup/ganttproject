/*
 * Copyright 2026 Oasis Project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package oasis.project.activity

/** A native GanttProject UID, preserved verbatim rather than parsed as a UUID. */
data class TaskUid(val value: String) {
  init {
    require(value.isNotBlank()) { "Task UID must not be blank" }
  }
}
