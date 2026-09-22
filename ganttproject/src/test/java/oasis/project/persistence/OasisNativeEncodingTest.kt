/*
 * Copyright 2026 Oasis Project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package oasis.project.persistence

import biz.ganttproject.core.io.XmlProject
import net.sourceforge.ganttproject.GanttProjectImpl
import net.sourceforge.ganttproject.document.Document
import net.sourceforge.ganttproject.language.GanttLanguage
import net.sourceforge.ganttproject.parser.AbstractTagHandler
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.Charset
import java.nio.file.Path

class OasisNativeEncodingTest {
  @TempDir lateinit var dir: Path
  @BeforeEach fun initializeLocale() { GanttLanguage.getInstance() }
  private val text = "Café déjà vu"

  private fun bytes(encoding: String): ByteArray {
    val source = GanttProjectImpl()
    source.projectName = text
    source.taskManager.newTaskBuilder().withUid("task").withName(text).build()
    source.seed(record(uid = "task").copy(description = text))
    return nativeXml(source).replace(Regex("encoding=\"[^\"]*\""), "encoding=\"$encoding\"")
      .toByteArray(Charset.forName(encoding))
  }

  @TestFactory
  fun `normal document open and native save reload preserve native and Oasis characters`() =
    listOf("UTF-8", "ISO-8859-1", "UTF-16", "UTF-16LE", "UTF-16BE", "UTF-8 BOM").map { encoding ->
      dynamicTest("ProxyDocument $encoding") {
        var parsedProjectName: String? = null
        val target = object : NativePersistenceProject(dir.resolve("target.gan")) {
          override fun newParser() = super.newParser().also { parser ->
            parser.addTagHandler(object : AbstractTagHandler("unused") {
              override fun process(xmlProject: XmlProject) { parsedProjectName = xmlProject.name }
            })
          }
        }
        val input = if (encoding == "UTF-8 BOM") byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + bytes("UTF-8")
          else bytes(encoding)
        target.openXml(input)
        assertEquals(text, parsedProjectName)
        fun verify(project: GanttProjectImpl) {
          assertEquals(text, project.taskManager.tasks.single().name)
          assertEquals(text, project.oasisProjectData.state.activities.values.single().description)
        }
        verify(target)
        target.document.write()
        val reopened = NativePersistenceProject(dir.resolve("reopened.gan"))
        reopened.open(reopened.documentManager.getDocument(dir.resolve("target.gan").toString()))
        verify(reopened)
        assertEquals(target.oasisProjectData.state, reopened.oasisProjectData.state)
      }
    }

  @TestFactory
  fun `normal document open rejects malformed bytes without installing a candidate`() =
    malformedNativeXmlInputs().map { (name, input) -> dynamicTest(name) {
      val target = NativePersistenceProject(dir.resolve("target.gan"))
      target.seed(record(9))
      val state = target.oasisProjectData.state
      val index = target.oasisProjectData.activityIdsByTask
      val revision = target.oasisProjectData.revision
      val failure = assertThrows<Document.DocumentException> { target.openXml(input) }
      assertTrue(OasisPersistenceException.causedByOasis(failure), failure.stackTraceToString())
      assertSame(state, target.oasisProjectData.state)
      assertSame(index, target.oasisProjectData.activityIdsByTask)
      assertEquals(revision, target.oasisProjectData.revision)
    } }

  @Test
  fun `native processing failure after UTF16 decoding leaves candidate uninstalled`() {
    var nativeProcessed = false
    val target = object : NativePersistenceProject(dir.resolve("target.gan")) {
      override fun newParser() = super.newParser().also { parser ->
        parser.addTagHandler(object : AbstractTagHandler("unused") {
          override fun process(xmlProject: XmlProject) {
            assertEquals(text, xmlProject.name)
            nativeProcessed = true
            throw IllegalStateException("native processing failure")
          }
        })
      }
    }
    target.seed(record(9))
    val state = target.oasisProjectData.state
    val index = target.oasisProjectData.activityIdsByTask
    val revision = target.oasisProjectData.revision
    assertThrows<Document.DocumentException> { target.openXml(bytes("UTF-16")) }
    assertTrue(nativeProcessed, "Failure must occur after decoding, in the actual native handler")
    assertSame(state, target.oasisProjectData.state)
    assertSame(index, target.oasisProjectData.activityIdsByTask)
    assertEquals(revision, target.oasisProjectData.revision)
  }
}

internal fun malformedNativeXmlInputs(): Map<String, ByteArray> {
  fun xml(encoding: String, description: String) = "<?xml version=\"1.0\" encoding=\"$encoding\"?>" +
    projectXml(ACTIVITY_XML.replace(">text<", ">$description<"))
  val utf8 = xml("UTF-8", "Café").toByteArray(Charsets.UTF_8)
  utf8[utf8.indices.first { utf8[it] == 0xc3.toByte() } + 1] = 0x28
  val utf16 = xml("UTF-16", "Café").toByteArray(Charsets.UTF_16)
  val accent = (0 until utf16.lastIndex).first { utf16[it] == 0.toByte() && utf16[it + 1] == 0xe9.toByte() }
  utf16[accent] = 0xd8.toByte()
  utf16[accent + 1] = 0
  return linkedMapOf(
    "malformed UTF-8" to utf8,
    "unmappable US-ASCII" to xml("US-ASCII", "Café").toByteArray(Charsets.ISO_8859_1),
    "unpaired UTF-16 surrogate" to utf16
  )
}
