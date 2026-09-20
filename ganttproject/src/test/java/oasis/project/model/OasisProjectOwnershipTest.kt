/*
 * Copyright 2026 Oasis Project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package oasis.project.model

import net.sourceforge.ganttproject.GanttProjectImpl
import net.sourceforge.ganttproject.chart.gantt.ClipboardContents
import net.sourceforge.ganttproject.chart.gantt.ClipboardTaskProcessor
import net.sourceforge.ganttproject.gui.UIFacade
import net.sourceforge.ganttproject.importer.BufferProject
import net.sourceforge.ganttproject.language.GanttLanguage
import net.sourceforge.ganttproject.resource.HumanResource
import net.sourceforge.ganttproject.task.Task
import oasis.project.activity.ActivityContent
import oasis.project.activity.ActivityService
import oasis.project.activity.TaskUid
import org.easymock.EasyMock
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate

class OasisProjectOwnershipTest {
  private val content = ActivityContent(LocalDate.of(2026, 9, 19), "note", "Historical event")

  @BeforeEach
  fun initializeNativeCalendarLocale() {
    GanttLanguage.getInstance()
  }

  @Test
  fun `real project instances own isolated data even for equal native UIDs`() {
    val first = GanttProjectImpl()
    val second = GanttProjectImpl()
    val firstOwner: OasisProjectDataOwner = first
    assertSame(first.oasisProjectData, firstOwner.oasisProjectData)
    assertNotSame(first.oasisProjectData, second.oasisProjectData)
    val uid = TaskUid("same-native-uid")
    val record = ActivityService(first.oasisProjectData).add(uid, content)
    val otherService = ActivityService(second.oasisProjectData)
    assertTrue(otherService.forTask(uid).isEmpty())
    assertNull(otherService.get(record.id))
    otherService.add(uid, content.copy(description = "Second project"))
    first.close()
    assertTrue(first.oasisProjectData.state.activities.isEmpty())
    assertEquals("Second project", otherService.forTask(uid).single().description)
  }

  @Test
  fun `buffer projects have their own holder independent of the target and each other`() {
    val target = GanttProjectImpl()
    val ui: UIFacade = EasyMock.createNiceMock(UIFacade::class.java)
    val first = BufferProject(target, ui)
    val second = BufferProject(target, ui)
    val uid = TaskUid("same-native-uid")
    ActivityService(target.oasisProjectData).add(uid, content)
    assertNotSame(target.oasisProjectData, first.oasisProjectData)
    assertNotSame(first.oasisProjectData, second.oasisProjectData)
    assertTrue(first.oasisProjectData.state.activities.isEmpty())
    assertTrue(second.oasisProjectData.state.activities.isEmpty())
    ActivityService(first.oasisProjectData).add(uid, content.copy(description = "Buffer only"))
    first.close()
    assertTrue(first.oasisProjectData.state.activities.isEmpty())
    assertEquals(content.description, ActivityService(target.oasisProjectData).forTask(uid).single().description)
  }

  @Test
  fun `desktop project close event clears history without replacing its holder`() {
    val project = object : GanttProjectImpl() {
      fun closeFromDesktop() = fireProjectClosed()
    }
    val holder = project.oasisProjectData
    ActivityService(holder).add(TaskUid("task"), content)
    project.closeFromDesktop()
    assertSame(holder, project.oasisProjectData)
    assertTrue(holder.state.activities.isEmpty())
    assertTrue(holder.activityIdsByTask.isEmpty())
  }

  @Test
  fun `all activity operations leave native task scheduling notes and assignments unchanged`() {
    val project = GanttProjectImpl()
    val manager = project.taskManager
    val parent = manager.newTaskBuilder().withName("Parent").build()
    val task = manager.newTaskBuilder().withName("Task").withNotes("Existing native notes").build()
    task.move(parent)
    task.duration = manager.createLength(3)
    task.completionPercentage = 35
    val predecessor = manager.newTaskBuilder().withName("Predecessor").build()
    manager.dependencyCollection.createDependency(task, predecessor)
    val milestone = manager.newTaskBuilder().withName("Milestone").build()
    milestone.isMilestone = true
    val resource = HumanResource("Builder", 1, project.humanResourceManager)
    project.humanResourceManager.add(resource)
    task.assignmentCollection.addAssignment(resource).load = 75f
    val tasks = listOf(parent, task, predecessor, milestone)
    val before = tasks.map(::nativeSnapshot)
    val algorithms = manager.algorithmCollection
    val schedulingFlags = listOf(algorithms.scheduler.isEnabled, algorithms.recalculateTaskScheduleAlgorithm.isEnabled,
      algorithms.adjustTaskBoundsAlgorithm.isEnabled)
    val service = ActivityService(project.oasisProjectData)

    fun assertNativeState() {
      assertEquals(before, tasks.map(::nativeSnapshot))
      assertEquals(schedulingFlags, listOf(algorithms.scheduler.isEnabled,
        algorithms.recalculateTaskScheduleAlgorithm.isEnabled, algorithms.adjustTaskBoundsAlgorithm.isEnabled))
    }
    val activity = service.add(TaskUid(task.uid), content)
    assertNativeState()
    service.edit(activity.id, content.copy(occurredOn = LocalDate.of(1990, 1, 1), description = "Backdated correction"))
    assertNativeState()
    service.edit(activity.id, service.get(activity.id)!!.content)
    service.forTask(TaskUid(task.uid))
    assertNativeState()
    service.delete(activity.id)
    assertNativeState()
  }

  @Test
  fun `native task duplication does not copy activity history`() {
    val project = GanttProjectImpl()
    val manager = project.taskManager
    val original = manager.newTaskBuilder().withName("Original").build()
    val service = ActivityService(project.oasisProjectData)
    val record = service.add(TaskUid(original.uid), content)
    val clipboard = ClipboardContents(manager)
    clipboard.addTasks(listOf(original))
    clipboard.copy()
    val duplicate = ClipboardTaskProcessor(manager).pasteAsSibling(original, clipboard).single()
    assertNotEquals(original.uid, duplicate.uid)
    assertTrue(service.forTask(TaskUid(duplicate.uid)).isEmpty())
    assertEquals(listOf(record), service.forTask(TaskUid(original.uid)))
  }

  private fun nativeSnapshot(task: Task): List<Any?> = listOf(
    task.uid, task.name, task.start.toXMLString(), task.end.toXMLString(), task.duration.length,
    task.completionPercentage, task.isMilestone, task.supertask?.uid, task.nestedTasks.map { it.uid }, task.notes,
    task.dependencies.toArray().map { listOf(it.dependee.uid, it.dependant.uid, it.constraint.type, it.difference, it.hardness) },
    task.assignments.map { listOf(it.resource, it.load, it.isCoordinator, it.roleForAssignment) }
  )
}
