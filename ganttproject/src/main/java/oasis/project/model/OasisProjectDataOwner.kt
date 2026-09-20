/*
 * Copyright 2026 Oasis Project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package oasis.project.model

/** Optional project capability; native Task and IGanttProject contracts stay unchanged. */
interface OasisProjectDataOwner {
  val oasisProjectData: OasisProjectData
}
