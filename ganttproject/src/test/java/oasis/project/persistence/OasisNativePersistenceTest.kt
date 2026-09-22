/*
 * Copyright 2026 Oasis Project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package oasis.project.persistence

import biz.ganttproject.core.io.XmlProjectImporter
import net.sourceforge.ganttproject.GanttProjectImpl
import net.sourceforge.ganttproject.document.Document
import net.sourceforge.ganttproject.language.GanttLanguage
import net.sourceforge.ganttproject.parser.AbstractTagHandler
import net.sourceforge.ganttproject.resource.HumanResourceMerger
import oasis.project.model.OasisProjectState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Path

class OasisNativePersistenceTest {
  @TempDir lateinit var dir: Path
  @BeforeEach fun initializeLocale() { GanttLanguage.getInstance() }
  private fun project(name: String) = NativePersistenceProject(dir.resolve("$name.gan"))

  @Test
  fun `empty state and legacy file load as empty`() {
    val source = project("empty")
    source.document.write()
    val xml = dir.resolve("empty.gan").toFile().readText()
    assertTrue(xml.contains("<oasis schema-version=\"1\">"))
    val target = project("target")
    target.open(target.documentManager.getDocument(dir.resolve("empty.gan").toString()))
    assertEquals(0, target.oasisProjectData.revision)
    target.seed(record())
    target.close()
    target.openXml("<project name=\"Legacy\"/>")
    assertTrue(target.oasisProjectData.state.activities.isEmpty())
    assertTrue(target.oasisProjectData.activityIdsByTask.isEmpty())
    assertEquals(2, target.oasisProjectData.revision)
  }

  @Test
  fun `native transformer preserves every field text code point order and nanosecond`() {
    val source = project("source")
    val text = " \tCafé e\u0301 建設 \uD83D\uDE80 <>&\"' ]]>\nLF\rCR\r\nCRLF\t end "
    val first = record(8, "opaque $text").copy(typeCode = "future $text", description = text, personCompany = text, planSummary = text, sourceReference = text)
    val second = record(2, first.taskUid.value).copy(occurredAt = null, personCompany = null, planSummary = null, sourceReference = null)
    val third = record(4, "another unresolved task")
    source.seed(first, second, third)
    val before = source.oasisProjectData.state
    val revision = source.oasisProjectData.revision
    val xml = nativeXml(source)
    assertTrue(xml.contains("&#13;"), "The real TransformerHandler must escape CR")
    assertFalse(xml.contains("activityIdsByTask"))
    assertFalse(xml.contains("revision="))
    assertEquals(xml, nativeXml(source))
    assertSame(before, source.oasisProjectData.state)
    assertEquals(revision, source.oasisProjectData.revision)
    val target = project("target")
    target.openXml(xml)
    assertEquals(listOf(first, second, third), target.oasisProjectData.state.activities.values.toList())
    assertEquals(listOf(first.id, second.id), target.oasisProjectData.activityIdsByTask[first.taskUid])
    assertEquals(1, target.oasisProjectData.revision)
    assertEquals(xml, nativeXml(target))
    target.openXml(xml)
    assertEquals(1, target.oasisProjectData.revision)
  }

  @Test
  fun `one shot input is opened once and both readers see the same contents`() {
    val target = project("target")
    val bytes = projectXml().toByteArray()
    var opens = 0
    val input = bytes.inputStream()
    val physical = object : Document by target.document {
      override fun getInputStream(): java.io.InputStream {
        check(++opens == 1) { "Input reopened" }
        return input
      }
    }
    target.documentManager.getProxyDocument(physical).read()
    assertEquals(1, opens)
    assertEquals("opaque uid", target.oasisProjectData.state.activities.values.single().taskUid.value)
  }

  @Test
  fun `invalid later record never installs an earlier record`() {
    val target = project("target")
    target.seed(record(5))
    val state = target.oasisProjectData.state
    val index = target.oasisProjectData.activityIdsByTask
    val rev = target.oasisProjectData.revision
    val ex = assertThrows<Document.DocumentException> { target.openXml(projectXml(ACTIVITY_XML + ACTIVITY_XML.replace("2020-02-29", "invalid"))) }
    assertTrue(OasisPersistenceException.causedByOasis(ex))
    assertSame(state, target.oasisProjectData.state)
    assertSame(index, target.oasisProjectData.activityIdsByTask)
    assertEquals(rev, target.oasisProjectData.revision)
  }

  @Test
  fun `native processing failure after preflight does not commit candidate`() {
    val target = project("target")
    target.seed(record(5))
    val before = target.oasisProjectData.state
    val parser = target.newParser()
    parser.addTagHandler(object : AbstractTagHandler("unused") {
      override fun process(xmlProject: biz.ganttproject.core.io.XmlProject) { throw IllegalStateException("native failure") }
    })
    assertThrows<IOException> { parser.load(projectXml().byteInputStream()) }
    assertSame(before, target.oasisProjectData.state)
    assertEquals(1, target.oasisProjectData.revision)
  }

  @Test
  fun `close open replacement and buffers remain project local`() {
    val first = project("first")
    val second = project("second")
    first.seed(record(1)); second.seed(record(2))
    val holder = first.oasisProjectData
    val buffer = net.sourceforge.ganttproject.importer.BufferProject(first, net.sourceforge.ganttproject.export.ConsoleUIFacade(null))
    buffer.newParser().load(nativeXml(second).byteInputStream())
    assertEquals(second.oasisProjectData.state, buffer.oasisProjectData.state)
    assertNotSame(holder, buffer.oasisProjectData)
    assertEquals(listOf(record(1)), first.oasisProjectData.state.activities.values.toList())
    first.importProject(buffer, HumanResourceMerger.MergeResourcesOption(), null, false)
    assertEquals(listOf(record(1)), first.oasisProjectData.state.activities.values.toList())
    first.close()
    assertTrue(holder.state.activities.isEmpty())
    first.openXml(nativeXml(second))
    assertSame(holder, first.oasisProjectData)
    assertEquals(second.oasisProjectData.state, holder.state)
    assertEquals(3, holder.revision)
    buffer.close()
    assertEquals(listOf(record(2)), second.oasisProjectData.state.activities.values.toList())
  }

  @Test
  fun `alternate headless importer preserves Oasis without shared state`() {
    val source = project("source")
    source.seed(record(1), record(2))
    val first = GanttProjectImpl()
    val second = GanttProjectImpl()
    XmlProjectImporter(first).import(nativeXml(source))
    XmlProjectImporter(second).import("<project name=\"Legacy\"/>")
    assertEquals(source.oasisProjectData.state, first.oasisProjectData.state)
    assertTrue(second.oasisProjectData.state.activities.isEmpty())
    val reopened = project("reopened")
    reopened.openXml(nativeXml(first))
    assertEquals(source.oasisProjectData.state, reopened.oasisProjectData.state)
    assertThrows<OasisPersistenceException> { XmlProjectImporter(first).import(projectXml().replace("schema-version=\"1\"", "schema-version=\"2\"")) }
    assertEquals(source.oasisProjectData.state, first.oasisProjectData.state)
  }

  @Test
  fun `loading equal records with different insertion order installs the file order`() {
    val source = project("source")
    val target = project("target")
    source.seed(record(2), record(1))
    target.seed(record(1), record(2))
    target.openXml(nativeXml(source))
    assertEquals(listOf(record(2), record(1)), target.oasisProjectData.state.activities.values.toList())
    assertEquals(listOf(record(2).id, record(1).id), target.oasisProjectData.activityIdsByTask[record(1).taskUid])
    assertEquals(2, target.oasisProjectData.revision)
    target.openXml(nativeXml(source))
    assertEquals(2, target.oasisProjectData.revision)
  }

  @Test
  fun `Java temporal range endpoints and invalid Unicode input are handled losslessly`() {
    val source = project("source")
    val first = record(1).copy(createdAt = java.time.Instant.MIN, modifiedAt = java.time.Instant.MAX,
      occurredOn = java.time.LocalDate.MIN, occurredAt = java.time.LocalTime.MIN)
    val second = record(2).copy(occurredOn = java.time.LocalDate.MAX, occurredAt = java.time.LocalTime.MAX)
    val third = record(3).copy(createdAt = java.time.Instant.parse("-0001-01-01T00:00:00Z"))
    source.seed(first, second, third)
    val target = project("target")
    target.openXml(nativeXml(source))
    assertEquals(listOf(first, second, third), target.oasisProjectData.state.activities.values.toList())
    assertThrows<OasisPersistenceException> { XmlProjectImporter().import(projectXml().replace(">text<", ">bad\ud800<")) }
  }

  @Test
  fun `unrepresentable text fails before physical output is requested`() {
    val source = project("source")
    source.document.write()
    val previousBytes = dir.resolve("source.gan").toFile().readBytes()
    for (invalid in listOf("\u0000", "\u000b", "\ud800", "\udc00", "\ufffe", "\uffff")) {
      source.seed(record().copy(description = "invalid $invalid"))
      var physicalWrites = 0
      val physical = object : Document by source.document {
        override fun getOutputStream(): java.io.OutputStream { physicalWrites++; return ByteArrayOutputStream() }
      }
      assertThrows<IOException> { source.documentManager.getProxyDocument(physical).write() }
      assertEquals(0, physicalWrites)
      assertArrayEquals(previousBytes, dir.resolve("source.gan").toFile().readBytes())
    }
  }
}
