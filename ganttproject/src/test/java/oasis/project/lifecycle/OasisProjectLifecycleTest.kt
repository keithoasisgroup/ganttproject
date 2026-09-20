/*
 * Copyright 2026 Oasis Project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package oasis.project.lifecycle

import biz.ganttproject.app.Barrier
import net.sourceforge.ganttproject.GanttProjectImpl
import net.sourceforge.ganttproject.ProjectEventListener
import net.sourceforge.ganttproject.document.Document
import net.sourceforge.ganttproject.document.DocumentManager
import net.sourceforge.ganttproject.document.FileDocument
import net.sourceforge.ganttproject.export.ConsoleUIFacade
import net.sourceforge.ganttproject.importer.BufferProject
import net.sourceforge.ganttproject.language.GanttLanguage
import net.sourceforge.ganttproject.resource.HumanResourceMerger
import net.sourceforge.ganttproject.restoreProject
import net.sourceforge.ganttproject.undo.GPUndoManager
import net.sourceforge.ganttproject.undo.UndoManagerImpl
import oasis.project.activity.ActivityContent
import oasis.project.activity.ActivityId
import oasis.project.activity.ActivityRecord
import oasis.project.activity.ActivityService
import oasis.project.activity.TaskUid
import oasis.project.model.OasisProjectData
import oasis.project.model.OasisProjectDataOwner
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID

class OasisProjectLifecycleTest {
  @TempDir
  lateinit var tempDir: Path

  @BeforeEach
  fun initializeNativeCalendarLocale() {
    GanttLanguage.getInstance()
  }

  @Test
  fun `native undo and redo preserve Oasis history while restoring an unrelated task rename`() {
    val target = GanttProjectImpl()
    val project = RestorableProject(target, tempDir.resolve("current.gan"))
    val task = project.taskManager.newTaskBuilder().withUid("native-task-1").withName("Before").build()
    fun restoredTask() = project.taskManager.tasks.single { it.uid == task.uid }
    project.taskManager.newTaskBuilder().withUid("native-task-2").withName("Other").build()
    val before = seedHistory(project)
    val currentDocument = project.document
    val autosaves = mutableListOf<Path>()
    // Keep the real autosave manager, ProxyDocument, XML saver and parser; track only this test's files for cleanup.
    val documents = object : DocumentManager by project.documentManager {
      override fun getDocument(path: String): Document {
        autosaves.add(Path.of(path))
        return project.documentManager.getDocument(path)
      }
    }
    val undo: GPUndoManager = UndoManagerImpl(project, project, documents)
    try {
      assertHistory(before, project)
      undo.undoableEdit("Rename native task") { task.name = "After" }
      assertTrue(undo.canUndo())
      assertEquals("After", restoredTask().name)
      assertHistory(before, project)

      undo.undo()
      assertEquals("Before", restoredTask().name)
      assertNotSame(task, restoredTask())
      assertEquals(1, project.closeCount)
      assertSame(currentDocument, project.document)
      assertHistory(before, project)

      assertTrue(undo.canRedo())
      undo.redo()
      assertEquals("After", restoredTask().name)
      assertEquals(2, project.closeCount)
      assertSame(currentDocument, project.document)
      assertHistory(before, project)
      assertNotSame(target.oasisProjectData, project.oasisProjectData)
      assertTrue(target.oasisProjectData.state.activities.isEmpty())
    } finally {
      undo.die()
      autosaves.forEach { Files.deleteIfExists(it) }
    }
  }

  @Test
  fun `genuine close after restoration clears history and index exactly once`() {
    val project = RestorableProject(GanttProjectImpl(), tempDir.resolve("current.gan"))
    val before = seedHistory(project)
    project.restore(noOpDocument(project))
    assertHistory(before, project)
    assertClearedByClose(before, project)
  }

  @Test
  fun `replacement import clears old history despite using the same restoring lifecycle`() {
    val project = RestorableProject(GanttProjectImpl(), tempDir.resolve("current.gan"))
    val before = seedHistory(project)
    project.taskManager.newTaskBuilder().withName("Old project task").build()
    val imported = BufferProject(project, ConsoleUIFacade(null))
    imported.taskManager.newTaskBuilder().withName("Replacement task").build()
    // The same generic restoration wrapper used by GanttProjectBase.importProject.
    project.restoreProject(project.listeners, closeCurrentProject = true) {
      project.importProject(imported, HumanResourceMerger.MergeResourcesOption(), null, true)
    }
    assertEquals(listOf("Replacement task"), project.taskManager.tasks.map { it.name })
    assertEquals(1, project.closeCount)
    assertCleared(before, project)
  }

  @Test
  fun `failed native document restoration releases preservation scope`() {
    val project = RestorableProject(GanttProjectImpl(), tempDir.resolve("current.gan"))
    val before = seedHistory(project)
    val failure = IOException("Cannot read snapshot")
    val broken = object : Document by project.document {
      override fun read() { throw failure }
    }
    assertSame(failure, assertThrows<IOException> { project.restore(broken) })
    assertEquals(1, project.closeCount)
    assertHistory(before, project)
    assertClearedByClose(before, project)
  }

  @Test
  fun `nested native restoration preserves the outer scope until it returns`() {
    val project = RestorableProject(GanttProjectImpl(), tempDir.resolve("current.gan"))
    val before = seedHistory(project)
    var nested = false
    project.addProjectEventListener(object : ProjectEventListener.Stub() {
      override fun projectRestoring(completion: Barrier<Document>) {
        if (!nested) {
          nested = true
          project.restore(noOpDocument(project))
        }
      }
    })
    project.restore(noOpDocument(project))
    assertEquals(2, project.closeCount)
    assertHistory(before, project)
    assertClearedByClose(before, project)
  }

  private fun noOpDocument(project: RestorableProject) = object : Document by project.document {
    override fun read() {}
  }

  private fun seedHistory(project: OasisProjectDataOwner): History {
    val holder = project.oasisProjectData
    val ids = (1L..3L).map { ActivityId(UUID(0, it)) }.iterator()
    val created = Instant.parse("2026-09-19T10:11:12.123456789Z")
    val service = ActivityService(holder, Clock.fixed(created, ZoneOffset.UTC)) { ids.next() }
    val content = ActivityContent(LocalDate.of(2020, 2, 29), "note", "Historical event", LocalTime.of(9, 30))
    val first = service.add(TaskUid("native-task-1"), content)
    service.add(TaskUid("native-task-1"), content.copy(typeCode = "email", description = "Second event"))
    service.add(TaskUid("native-task-2"), content.copy(description = "Another task's event"))
    ActivityService(holder, Clock.fixed(created.plusSeconds(60), ZoneOffset.UTC))
      .edit(first.id, content.copy(description = "Corrected history"))
    assertEquals(4L, holder.revision)
    return History(holder, holder.state.activities.toMap(), holder.activityIdsByTask.mapValues { it.value.toList() }, holder.revision)
  }

  private fun assertHistory(before: History, project: OasisProjectDataOwner) {
    val holder = project.oasisProjectData
    assertSame(before.holder, holder)
    assertEquals(before.records, holder.state.activities)
    assertEquals(before.records.keys, holder.state.activities.keys)
    before.records.forEach { (id, original) ->
      val restored = holder.state.activities.getValue(id)
      assertEquals(original.id, restored.id)
      assertEquals(original.createdAt, restored.createdAt)
      assertEquals(original.modifiedAt, restored.modifiedAt)
      assertEquals(original.occurredOn, restored.occurredOn)
      assertEquals(original.occurredAt, restored.occurredAt)
      assertEquals(original.taskUid, restored.taskUid)
    }
    assertEquals(before.index, holder.activityIdsByTask)
    assertEquals(before.index.mapValues { it.value.toSet() }, holder.activityIdsByTask.mapValues { it.value.toSet() })
    assertEquals(before.revision, holder.revision)
  }

  private fun assertClearedByClose(before: History, project: RestorableProject) {
    project.close()
    assertCleared(before, project)
    project.close()
    assertCleared(before, project)
  }

  private fun assertCleared(before: History, project: OasisProjectDataOwner) {
    assertSame(before.holder, project.oasisProjectData)
    assertTrue(project.oasisProjectData.state.activities.isEmpty())
    assertTrue(project.oasisProjectData.activityIdsByTask.isEmpty())
    assertEquals(before.revision + 1, project.oasisProjectData.revision)
  }

  private data class History(
    val holder: OasisProjectData,
    val records: Map<ActivityId, ActivityRecord>,
    val index: Map<TaskUid, List<ActivityId>>,
    val revision: Long
  )

  /** Headless native XML fixture: inherited restore(Document), plus the desktop close-event wiring. */
  private class RestorableProject(target: GanttProjectImpl, path: Path) : BufferProject(target, ConsoleUIFacade(null)) {
    override var document: Document = FileDocument(path.toFile())
    var closeCount = 0
      private set

    init {
      addProjectEventListener(taskManager.projectListener)
    }

    override fun close() {
      closeCount++
      super.close()
      fireProjectClosed()
    }
  }
}
