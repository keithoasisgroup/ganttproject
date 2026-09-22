/*
 * Copyright 2026 Oasis Project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package oasis.project.persistence

import biz.ganttproject.core.calendar.GPCalendar
import biz.ganttproject.core.io.parseXmlProject
import biz.ganttproject.core.io.XmlProjectImporter
import biz.ganttproject.customproperty.CustomPropertyClass
import biz.ganttproject.impex.csv.GanttCSVExport
import biz.ganttproject.impex.csv.SpreadsheetFormat
import net.sourceforge.ganttproject.GanttPreviousState
import net.sourceforge.ganttproject.GanttPreviousStateTask
import net.sourceforge.ganttproject.io.CSVOptions
import net.sourceforge.ganttproject.language.GanttLanguage
import net.sourceforge.ganttproject.resource.HumanResource
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Path

class OasisNativeCompatibilityTest {
  @TempDir lateinit var dir: Path
  @BeforeEach fun initializeLocale() { GanttLanguage.getInstance() }

  @Test
  fun `native tasks resources allocations calendars baselines and metadata retain semantic values`() {
    val source = NativePersistenceProject(dir.resolve("source.gan"))
    source.seed(record())
    source.projectName = "Native project"
    source.organization = "Company"
    source.description = "Project description"
    source.webLink = "https://example.com"
    val parent = source.taskManager.newTaskBuilder().withUid("parent").withName("Parent").build()
    val task = source.taskManager.newTaskBuilder().withUid("task").withName("Task").withNotes("Native notes").withParent(parent).build()
    task.duration = source.taskManager.createLength(3)
    task.completionPercentage = 35
    val customProperty = source.taskCustomColumnManager.createDefinition(CustomPropertyClass.TEXT, "Native custom value")
    task.customValues.setValue(customProperty, "Preserved & unchanged")
    val predecessor = source.taskManager.newTaskBuilder().withUid("predecessor").withName("Predecessor").build()
    source.taskManager.dependencyCollection.createDependency(task, predecessor)
    val resource = HumanResource("Builder", 1, source.humanResourceManager)
    resource.role = source.roleManager.defaultRole
    source.humanResourceManager.add(resource)
    task.assignmentCollection.addAssignment(resource).load = 75f
    source.activeCalendar.setWeekDayType(6, GPCalendar.DayType.WEEKEND)
    source.baselines.add(GanttPreviousState("Checkpoint", listOf(GanttPreviousStateTask(task.taskID, task.start, 3, false, false))).apply {
      init()
      saveFile()
    })
    val before = parseXmlProject(nativeXml(source))
    val target = NativePersistenceProject(dir.resolve("target.gan"))
    target.openXml(nativeXml(source))
    val after = parseXmlProject(nativeXml(target))
    assertEquals(before.tasks, after.tasks)
    assertEquals(before.resources, after.resources)
    assertEquals(before.allocations, after.allocations)
    assertEquals(before.calendars, after.calendars)
    assertEquals(before.baselines, after.baselines)
    assertEquals(before.roles, after.roles)
    assertEquals(source.oasisProjectData.state, target.oasisProjectData.state)
    val headless = XmlProjectImporter().import(nativeXml(source))
    assertEquals(source.projectName, headless.projectName)
    assertEquals(source.organization, headless.organization)
    assertEquals(source.description, headless.description)
    assertEquals(source.webLink, headless.webLink)
  }

  @Test
  fun `real CSV export and failed export do not mutate Oasis`() {
    val project = NativePersistenceProject(dir.resolve("source.gan"))
    project.taskManager.newTaskBuilder().withName("Task exported").build()
    project.seed(record())
    val state = project.oasisProjectData.state
    val index = project.oasisProjectData.activityIdsByTask
    val revision = project.oasisProjectData.revision
    val exporter = GanttCSVExport(project, CSVOptions())
    val output = ByteArrayOutputStream()
    exporter.createWriter(output, SpreadsheetFormat.CSV).use { exporter.save(it) }
    assertTrue(output.toString(Charsets.UTF_8).contains("Task exported"))
    val broken = object : OutputStream() { override fun write(b: Int) { throw IOException("export failed") } }
    assertThrows<IOException> { exporter.createWriter(broken, SpreadsheetFormat.CSV).use { exporter.save(it) } }
    assertSame(state, project.oasisProjectData.state)
    assertSame(index, project.oasisProjectData.activityIdsByTask)
    assertEquals(revision, project.oasisProjectData.revision)
  }
}
