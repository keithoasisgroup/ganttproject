/*
 * Copyright 2026 Oasis Project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package oasis.project.lifecycle

import net.sourceforge.ganttproject.IGanttProject
import net.sourceforge.ganttproject.ProjectEventListener
import net.sourceforge.ganttproject.document.Document
import net.sourceforge.ganttproject.restoreProject
import oasis.project.model.OasisProjectData
import java.io.IOException

/** Project-scoped lifecycle state, accessed on the project's model thread. */
class OasisLifecycleBridge(private val data: OasisProjectData) {
  private var internalRestoreDepth = 0

  /**
   * Native Undo/Redo reloads a document by closing the native model first. Its XML
   * snapshots do not contain Oasis history, so keep that history throughout this
   * internal restore. Replacement imports also use restoreProject/projectRestoring
   * but must not enter this scope.
   */
  @Throws(Document.DocumentException::class, IOException::class)
  fun restoreDocument(project: IGanttProject, fromDocument: Document, listeners: List<ProjectEventListener>) {
    internalRestoreDepth++
    try {
      project.restoreProject(fromDocument, listeners)
    } finally {
      internalRestoreDepth--
    }
  }

  fun projectClosed() {
    if (internalRestoreDepth == 0) {
      data.clear()
    }
  }
}
