/*
 * Copyright 2026 Oasis Project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package oasis.project.persistence

import biz.ganttproject.core.io.XmlProjectImporter
import net.sourceforge.ganttproject.GanttProjectImpl
import net.sourceforge.ganttproject.language.GanttLanguage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows
import java.nio.charset.Charset
import java.nio.charset.UnmappableCharacterException

class OasisEncodingTest {
  private val text = "Café déjà vu"
  @BeforeEach fun initializeLocale() { GanttLanguage.getInstance() }

  private fun xml(encoding: String) = "<?xml version=\"1.0\" encoding=\"$encoding\"?>" +
    projectXml(ACTIVITY_XML.replace(">text<", ">$text<")).replace("<project>", "<project name=\"$text\">")

  private fun verifyRoundTrip(project: GanttProjectImpl) {
    fun verify(value: GanttProjectImpl) {
      assertEquals(text, value.projectName)
      assertEquals(text, value.oasisProjectData.state.activities.values.single().description)
    }
    verify(project)
    val reopened = GanttProjectImpl()
    XmlProjectImporter(reopened).import(nativeXml(project).toByteArray(Charsets.UTF_8))
    verify(reopened)
    assertEquals(project.oasisProjectData.state, reopened.oasisProjectData.state)
  }

  @TestFactory
  fun `String declarations describe characters consistently for both parsers`() =
    listOf("UTF-8", "ISO-8859-1", "UTF-16").map { encoding -> dynamicTest("String $encoding") {
      val project = GanttProjectImpl()
      XmlProjectImporter(project).import(xml(encoding))
      verifyRoundTrip(project)
    } }

  @TestFactory
  fun `byte encodings are decoded according to their XML declaration and BOM`() =
    listOf("UTF-8", "ISO-8859-1", "UTF-16", "UTF-16LE", "UTF-16BE").map { encoding -> dynamicTest("Bytes $encoding") {
      val project = GanttProjectImpl()
      XmlProjectImporter(project).import(xml(encoding).toByteArray(Charset.forName(encoding)))
      verifyRoundTrip(project)
    } }

  @Test
  fun `malformed byte sequences and unsupported encodings fail before installation`() {
    val project = GanttProjectImpl()
    project.seed(record())
    val before = project.oasisProjectData.state
    val bytes = xml("UTF-8").toByteArray()
    val accent = bytes.indices.first { bytes[it] == 0xc3.toByte() }
    bytes[accent + 1] = 0x28
    assertThrows<OasisPersistenceException> { XmlProjectImporter(project).import(bytes) }
    assertThrows<OasisPersistenceException> { XmlProjectImporter(project).import(xml("unsupported-encoding").toByteArray()) }
    assertSame(before, project.oasisProjectData.state)
  }

  @Test
  fun `headless windows1252 decoder failure is typed before installation`() {
    val project = GanttProjectImpl()
    project.seed(record(9))
    val state = project.oasisProjectData.state
    val index = project.oasisProjectData.activityIdsByTask
    val revision = project.oasisProjectData.revision
    val failure = assertThrows<OasisPersistenceException> { XmlProjectImporter(project).import(windows1252WithUndefinedByte()) }
    assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it is UnmappableCharacterException })
    assertSame(state, project.oasisProjectData.state)
    assertSame(index, project.oasisProjectData.activityIdsByTask)
    assertEquals(revision, project.oasisProjectData.revision)
  }

  @Test
  fun `String validation rejects isolated surrogates even in a replaced declaration`() {
    assertThrows<OasisPersistenceException> { XmlProjectImporter().import(xml("UTF-8").replace(text, "bad\ud800")) }
    assertThrows<OasisPersistenceException> { XmlProjectImporter().import(xml("bad\ud800")) }
  }
}
