/*
 * Copyright 2026 Oasis Project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package net.sourceforge.ganttproject.gui

import kotlinx.coroutines.CoroutineScope
import net.sourceforge.ganttproject.ProjectOpenActivityFailed
import net.sourceforge.ganttproject.ProjectOpenStateMachine
import net.sourceforge.ganttproject.document.Document
import net.sourceforge.ganttproject.importer.Importer
import net.sourceforge.ganttproject.importer.ImporterBase
import net.sourceforge.ganttproject.language.GanttLanguage
import oasis.project.persistence.NativePersistenceProject
import oasis.project.persistence.projectXml
import oasis.project.persistence.malformedNativeXmlInputs
import oasis.project.persistence.OasisPersistenceException
import oasis.project.persistence.OasisXmlReader
import oasis.project.persistence.seed
import oasis.project.persistence.record
import oasis.project.persistence.windows1252WithUndefinedByte
import org.eclipse.core.runtime.Platform
import org.eclipse.core.runtime.IConfigurationElement
import org.eclipse.core.runtime.IExtensionRegistry
import org.easymock.EasyMock.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.osgi.service.prefs.Preferences
import java.nio.file.Path
import java.nio.charset.UnmappableCharacterException
import kotlin.coroutines.EmptyCoroutineContext

class OasisStartupFailureTest {
  @TempDir lateinit var dir: Path

  @Test
  fun `typed Oasis failure is reported instead of trying importers`() {
    verifyRouting(projectXml().replace("schema-version=\"1\"", "schema-version=\"2\""), terminal = true)
  }

  @TestFactory
  fun `prohibited or malformed native XML remains terminal through document wrapping`() = listOf(
    "<!DOCTYPE project>" + projectXml().replace("schema-version=\"1\"", "schema-version=\"2\""),
    "<!DOCTYPE project SYSTEM 'file:///must-not-be-read.dtd'>" + projectXml(),
    "<!DOCTYPE project [<!ENTITY bad SYSTEM 'file:///must-not-be-read'>]>" + projectXml(),
    "<!DOCTYPE different SYSTEM 'file:///must-not-be-read.dtd'>" + projectXml(),
    "<!DOCTYPE different [<!ENTITY text 'ignored'>]>" + projectXml().replace("schema-version=\"1\"", "schema-version=\"2\""),
    projectXml().dropLast(4)
  ).mapIndexed { index, xml -> dynamicTest("native failure $index") { verifyRouting(xml, terminal = true) } }

  @TestFactory
  fun `malformed native encoding remains terminal at startup`() = malformedNativeXmlInputs().map { (name, bytes) ->
    dynamicTest(name) { verifyRouting(bytes, terminal = true) }
  }

  @Test
  fun `unmappable bytes inside a foreign root remain eligible for fallback`() {
    val xml = "<?xml version=\"1.0\" encoding=\"US-ASCII\"?><foreign><oasis>Café</oasis></foreign>"
    verifyRouting(xml.toByteArray(Charsets.ISO_8859_1), terminal = false)
  }

  @TestFactory
  fun `foreign windows1252 decoder failures still reach importer`() = listOf(
    "<foreign><project><oasis>before~after</oasis></project></foreign>",
    "<project xmlns=\"urn:foreign\"><oasis xmlns=\"\">before~after</oasis></project>"
  ).mapIndexed { index, xml -> dynamicTest("foreign windows1252 $index") {
    val bytes = windows1252WithUndefinedByte(xml)
    assertTrue(OasisXmlReader().read(bytes.inputStream()).activities.isEmpty())
    verifyRouting(bytes, terminal = false, strictDecoderFailure = true)
  } }

  @Test
  fun `native windows1252 decoder failure is terminal after successful Oasis preflight`() {
    val bytes = windows1252WithUndefinedByte()
    // SAX recognizes the native root/Oasis section but accepts the undefined byte with replacement.
    val candidate = OasisXmlReader().read(bytes.inputStream())
    assertEquals("before\uFFFDafter", candidate.activities.values.single().description)
    verifyRouting(bytes, terminal = true, strictDecoderFailure = true)
  }

  @TestFactory
  fun `non-native formats still reach a registered importer after native open failure`() = listOf(
    "binary non-native input\u0000", "<!DOCTYPE foreign><foreign/>",
    "<!DOCTYPE foreign><foreign><![CDATA[<project><oasis schema-version=\"2\"/>]]></foreign>",
    "<!DOCTYPE project><project xmlns=\"urn:foreign-format\"/>",
    "<Project xmlns=\"urn:foreign-format\"><tasks>foreign scalar value</tasks></Project>",
    "<Project xmlns=\"urn:foreign-format\"><tasks>foreign scalar value</tasks><oasis/></Project>",
    "<foreign><tasks>foreign scalar value</tasks><x:oasis xmlns:x=\"urn:other\"/></foreign>",
    "<project xmlns=\"urn:foreign-format\"><tasks>foreign scalar value</tasks><oasis xmlns=\"\"/></project>",
    "<foreign><tasks>foreign scalar value</tasks><project><oasis/></project></foreign>"
  ).mapIndexed { index, xml -> dynamicTest("foreign input $index") { verifyRouting(xml, terminal = false) } }

  private fun verifyRouting(xml: String, terminal: Boolean) = verifyRouting(xml.toByteArray(Charsets.UTF_8), terminal)

  private fun verifyRouting(bytes: ByteArray, terminal: Boolean, strictDecoderFailure: Boolean = false) {
    GanttLanguage.getInstance()
    val project = NativePersistenceProject(dir.resolve("current.gan"))
    project.seed(record(9))
    val before = project.oasisProjectData.state
    val index = project.oasisProjectData.activityIdsByTask
    val revision = project.oasisProjectData.revision
    val failure = assertThrows<Document.DocumentException> {
      project.openXml(bytes)
    }
    assertSame(before, project.oasisProjectData.state)
    assertSame(index, project.oasisProjectData.activityIdsByTask)
    assertEquals(revision, project.oasisProjectData.revision)
    if (strictDecoderFailure) {
      assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it is UnmappableCharacterException }, failure.stackTraceToString())
    }
    val actualTerminal = OasisPersistenceException.causedByOasis(failure)
    val ui: UIFacade = createMock(UIFacade::class.java)
    val projectUi: ProjectUIFacade = createMock(ProjectUIFacade::class.java)
    val preferences: Preferences = createMock(Preferences::class.java)
    val stateMachine = ProjectOpenStateMachine(project, CoroutineScope(EmptyCoroutineContext))
    val path = dir.resolve("bad.gan").toString()
    expect(projectUi.openProject(project.documentManager.getDocument(path), project, null)).andReturn(stateMachine)
    var imported = 0
    val importer = object : ImporterBase("test") {
      override fun getFileNamePattern() = "gan"
      override fun run() { imported++ }
    }
    // Exercise the real PluginManager discovery and fallback loop with a scoped extension registry.
    val platform: Platform = createMock(Platform::class.java)
    val registry: IExtensionRegistry = createMock(IExtensionRegistry::class.java)
    val extension: IConfigurationElement = createMock(IConfigurationElement::class.java)
    val platformField = Platform::class.java.getDeclaredField("ourInstance").apply { isAccessible = true }
    val previousPlatform = platformField.get(null)
    try {
      platformField.set(null, platform)
      if (actualTerminal) ui.showErrorDialog(failure) else {
        expect(Platform.getExtensionRegistry()).andReturn(registry)
        expect(registry.getConfigurationElementsFor(Importer.EXTENSION_POINT_ID)).andReturn(arrayOf(extension))
        expect(extension.createExecutableExtension("class")).andReturn(importer)
      }
      replay(ui, projectUi, preferences, platform, registry, extension)
      CommandLineProjectOpenStrategy(project, project.documentManager, project.taskManager, ui, projectUi, preferences)
        .openStartupDocument(path)
      stateMachine.stateFailed.resolve(ProjectOpenActivityFailed("Open failed", "test input", failure))
      verify(ui, projectUi, preferences, platform, registry, extension)
      assertEquals(if (terminal) 0 else 1, imported)
      assertEquals(terminal, actualTerminal, failure.stackTraceToString())
    } finally {
      platformField.set(null, previousPlatform)
    }
  }
}
