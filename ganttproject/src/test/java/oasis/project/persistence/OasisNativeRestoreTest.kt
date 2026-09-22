/*
 * Copyright 2026 Oasis Project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package oasis.project.persistence

import biz.ganttproject.app.Barrier
import net.sourceforge.ganttproject.ProjectEventListener
import net.sourceforge.ganttproject.document.Document
import net.sourceforge.ganttproject.document.DocumentManager
import net.sourceforge.ganttproject.language.GanttLanguage
import net.sourceforge.ganttproject.undo.UndoManagerImpl
import oasis.project.activity.ActivityService
import oasis.project.model.OasisProjectData
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class OasisNativeRestoreTest {
  @TempDir lateinit var dir: Path
  @BeforeEach fun initializeLocale() { GanttLanguage.getInstance() }
  private fun project(name: String) = NativePersistenceProject(dir.resolve("$name.gan"))

  private fun unchanged(data: OasisProjectData): () -> Unit {
    val state = data.state
    val index = data.activityIdsByTask
    val revision = data.revision
    return {
      assertSame(state, data.state)
      assertSame(index, data.activityIdsByTask)
      assertEquals(revision, data.revision)
    }
  }

  @Test
  fun `old real undo and redo snapshots cannot rewind edits resurrect deletions or erase additions`() {
    val project = project("current")
    val original = record(1, "task")
    val deleted = record(2, "task")
    project.seed(original, deleted)
    val task = project.taskManager.newTaskBuilder().withUid("task").withName("Before").build()
    val snapshots = mutableListOf<Path>()
    val documents = object : DocumentManager by project.documentManager {
      override fun getDocument(path: String): Document {
        snapshots.add(Path.of(path))
        return project.documentManager.getDocument(path)
      }
    }
    val undo = UndoManagerImpl(project, project, documents)
    try {
      undo.undoableEdit("Rename") { task.name = "After" }
      assertEquals(2, snapshots.size)
      snapshots.forEach { assertTrue(Files.readString(it).contains("<oasis schema-version=\"1\">")) }
      val service = ActivityService(project.oasisProjectData, Clock.fixed(Instant.parse("2026-09-20T00:00:00Z"), ZoneOffset.UTC)) { record(3).id }
      service.edit(original.id, original.content.copy(description = "Edited after snapshot"))
      service.delete(deleted.id)
      service.add(original.taskUid, original.content.copy(description = "Added after snapshot"))
      val assertUnchanged = unchanged(project.oasisProjectData)
      undo.undo()
      assertEquals("Before", project.taskManager.tasks.single().name)
      assertUnchanged()
      undo.redo()
      assertEquals("After", project.taskManager.tasks.single().name)
      assertUnchanged()
      // Recovery uses genuine open of those very same bytes, with a new project-local parser.
      val recovered = project("recovered")
      recovered.seed(record(9))
      recovered.close()
      recovered.open(recovered.documentManager.getDocument(snapshots.first().toString()))
      assertEquals(listOf(original, deleted), recovered.oasisProjectData.state.activities.values.toList())
      assertUnchanged()
    } finally {
      undo.die()
      snapshots.forEach { Files.deleteIfExists(it) }
    }
  }

  @Test
  fun `internal restore skips stale missing unsupported and semantically malformed Oasis`() {
    val project = project("current")
    project.seed(record(99))
    val assertUnchanged = unchanged(project.oasisProjectData)
    val payloads = listOf(
      "<project name=\"Legacy\"/>", projectXml(),
      projectXml().replace("schema-version=\"1\"", "schema-version=\"2\""),
      projectXml("<unexpected><anything/></unexpected>")
    )
    payloads.forEach { xml ->
      val physical = object : Document by project.document { override fun getInputStream() = xml.byteInputStream() }
      project.restore(project.documentManager.getProxyDocument(physical))
      assertUnchanged()
      assertFalse(project.oasisLifecycleBridge.isInternalRestore)
    }
    assertThrows<Document.DocumentException> { project.openXml(payloads[2]) }
    assertUnchanged()
    project.close()
    project.openXml(projectXml())
    assertEquals("text", project.oasisProjectData.state.activities.values.single().description)
  }

  @Test
  fun `UTF16 native restore decodes native text while bypassing unsupported Oasis`() {
    val project = project("current")
    project.seed(record(99))
    val assertUnchanged = unchanged(project.oasisProjectData)
    val source = project("source")
    source.taskManager.newTaskBuilder().withUid("task").withName("Café").build()
    source.seed(record(1).copy(description = "Café"))
    val xml = nativeXml(source).replace(Regex("encoding=\"[^\"]*\""), "encoding=\"UTF-16\"")
      .replace("schema-version=\"1\"", "schema-version=\"2\"")
    val bytes = xml.toByteArray(Charsets.UTF_16)
    val physical = object : Document by project.document { override fun getInputStream() = bytes.inputStream() }
    project.restore(project.documentManager.getProxyDocument(physical))
    assertEquals("Café", project.taskManager.tasks.single().name)
    assertUnchanged()
    assertFalse(project.oasisLifecycleBridge.isInternalRestore)
    val failure = assertThrows<Document.DocumentException> { project.openXml(bytes) }
    assertTrue(OasisPersistenceException.causedByOasis(failure))
    assertUnchanged()
  }

  @Test
  fun `windows1252 decoding failure in internal restore bypasses Oasis classification`() {
    val project = project("current")
    project.seed(record(99))
    val assertUnchanged = unchanged(project.oasisProjectData)
    val physical = object : Document by project.document {
      override fun getInputStream() = windows1252WithUndefinedByte().inputStream()
    }
    val failure = assertThrows<Document.DocumentException> { project.restore(project.documentManager.getProxyDocument(physical)) }
    assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it is java.nio.charset.UnmappableCharacterException })
    assertFalse(OasisPersistenceException.causedByOasis(failure))
    assertUnchanged()
    assertFalse(project.oasisLifecycleBridge.isInternalRestore)
  }

  @Test
  fun `malformed native restore releases scope without touching Oasis`() {
    val project = project("current")
    project.seed(record(99))
    val assertUnchanged = unchanged(project.oasisProjectData)
    val physical = object : Document by project.document {
      override fun getInputStream() = "<project><broken>".byteInputStream()
    }
    assertThrows<Document.DocumentException> { project.restore(project.documentManager.getProxyDocument(physical)) }
    assertUnchanged()
    assertFalse(project.oasisLifecycleBridge.isInternalRestore)
    project.close()
    assertTrue(project.oasisProjectData.state.activities.isEmpty())
    project.openXml(projectXml())
    assertEquals(1, project.oasisProjectData.state.activities.size)
  }

  @Test
  fun `nested failing restore keeps outer suppression and does not suppress a different project`() {
    val project = project("current")
    val other = project("other")
    project.seed(record(99))
    val assertUnchanged = unchanged(project.oasisProjectData)
    var entered = false
    project.addProjectEventListener(object : ProjectEventListener.Stub() {
      override fun projectRestoring(completion: Barrier<Document>) {
        if (entered) return
        entered = true
        val broken = object : Document by project.document { override fun getInputStream() = "<project>".byteInputStream() }
        assertThrows<Document.DocumentException> { project.restore(project.documentManager.getProxyDocument(broken)) }
        assertTrue(project.oasisLifecycleBridge.isInternalRestore)
        other.openXml(projectXml())
        assertEquals(1, other.oasisProjectData.state.activities.size)
      }
    })
    val unsupported = object : Document by project.document {
      override fun getInputStream() = projectXml().replace("schema-version=\"1\"", "schema-version=\"2\"").byteInputStream()
    }
    project.restore(project.documentManager.getProxyDocument(unsupported))
    assertUnchanged()
    assertFalse(project.oasisLifecycleBridge.isInternalRestore)
    project.close()
    assertTrue(project.oasisProjectData.state.activities.isEmpty())
    assertEquals(1, other.oasisProjectData.state.activities.size)
  }
}
