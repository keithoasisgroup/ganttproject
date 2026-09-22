/*
 * Copyright 2026 Oasis Project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package net.sourceforge.ganttproject.gui

import biz.ganttproject.app.Barrier
import biz.ganttproject.app.SimpleBarrier
import biz.ganttproject.storage.saveAsDocument
import biz.ganttproject.storage.ForbiddenException
import biz.ganttproject.storage.StorageDialogBuilder
import biz.ganttproject.storage.cloud.GPCloudDocument
import biz.ganttproject.storage.cloud.GPCloudStorageOptions
import biz.ganttproject.app.DialogController
import javafx.application.Platform
import javafx.stage.Stage
import javafx.stage.Window
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.javafx.JavaFx
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import net.sourceforge.ganttproject.ProjectOpenActivityFactory
import net.sourceforge.ganttproject.chart.Chart
import net.sourceforge.ganttproject.document.Document
import net.sourceforge.ganttproject.document.DocumentManager
import net.sourceforge.ganttproject.document.FileDocument
import net.sourceforge.ganttproject.language.GanttLanguage
import net.sourceforge.ganttproject.undo.GPUndoManager
import oasis.project.persistence.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.File
import java.nio.file.Path
import java.util.function.Consumer
import javax.swing.SwingUtilities
import kotlin.coroutines.resume
import org.easymock.EasyMock.*

class OasisSaveFailureTest {
  @TempDir lateinit var dir: Path
  @BeforeEach fun initializeLocale() { GanttLanguage.getInstance() }
  private fun project() = NativePersistenceProject(dir.resolve("current.gan")).also {
    it.seed(record())
    it.isModified = true
  }

  private fun save(project: NativePersistenceProject, errors: MutableList<Exception>): Barrier<Boolean> {
    val barrier = SimpleBarrier<Boolean>()
    ProjectSaveFlow(project, barrier, { fail("Unexpected authentication") }, errors::add, { fail("Unexpected Save As") }).run()
    return barrier
  }

  @Test
  fun `ordinary save serialization failure returns false and keeps dirty state`() {
    val project = project()
    project.seed(record().copy(description = "bad\u0000text"))
    val errors = mutableListOf<Exception>()
    val completions = mutableListOf<Boolean>()
    save(project, errors).await(completions::add)
    assertEquals(listOf(false), completions)
    assertEquals(1, errors.size)
    assertTrue(OasisPersistenceException.causedByOasis(errors.single()))
    assertTrue(project.isModified)
    assertEquals(0, dir.resolve("current.gan").toFile().length())
  }

  @Test
  fun `physical write and close failures return false and never signal success`() {
    for (onClose in listOf(false, true)) {
      val project = project()
      val failure = IOException(if (onClose) "close failed" else "write failed")
      val physical = object : Document by project.document {
        override fun getOutputStream(): OutputStream = object : OutputStream() {
          override fun write(b: Int) { if (!onClose) throw failure }
          override fun close() { if (onClose) throw failure }
        }
      }
      project.document = project.documentManager.getProxyDocument(physical)
      val errors = mutableListOf<Exception>()
      val completions = mutableListOf<Boolean>()
      save(project, errors).await(completions::add)
      assertEquals(listOf(false), completions)
      assertSame(failure, errors.single())
      assertTrue(project.isModified)
    }
  }

  @Test
  fun `Save As round trip uses current holder and native saver`() {
    val project = project()
    val holder = project.oasisProjectData
    val before = holder.state
    val revision = holder.revision
    val destination = FileDocument(dir.resolve("copy.gan").toFile())
    val completions = mutableListOf<Boolean>()
    val errors = mutableListOf<Exception>()
    saveAsDocument(project, destination, project.documentManager, { save(project, errors) }, completions::add, errors::add)
    assertEquals(listOf(true), completions)
    assertTrue(errors.isEmpty())
    assertEquals(destination.path, project.document.path)
    val reopened = NativePersistenceProject(dir.resolve("reopened.gan"))
    reopened.open(reopened.documentManager.getDocument(destination.path))
    assertEquals(before, reopened.oasisProjectData.state)
    assertSame(holder, project.oasisProjectData)
    assertSame(before, holder.state)
    assertEquals(revision, holder.revision)
  }

  @Test
  fun `Save As serialization failure creates no destination and preserves association`() {
    val project = project()
    val original = project.document
    project.seed(record().copy(description = "bad\ud800text"))
    val destination = FileDocument(dir.resolve("copy.gan").toFile())
    val completions = mutableListOf<Boolean>()
    val errors = mutableListOf<Exception>()
    saveAsDocument(project, destination, project.documentManager, { fail("Must not start physical save") }, completions::add, errors::add)
    assertEquals(listOf(false), completions)
    assertEquals(1, errors.size)
    assertSame(original, project.document)
    assertFalse(destination.file.exists())
    assertTrue(project.isModified)
  }

  @Test
  fun `Save As write failure restores previous association and keeps storage open`() {
    val project = project()
    val original = project.document
    val physical = object : Document by original {
      override fun getOutputStream(): OutputStream = object : ByteArrayOutputStream() {
        override fun close() { throw IOException("destination failed") }
      }
    }
    val completions = mutableListOf<Boolean>()
    val errors = mutableListOf<Exception>()
    saveAsDocument(project, physical, project.documentManager, { save(project, errors) }, completions::add, errors::add)
    assertEquals(listOf(false), completions)
    assertEquals(1, errors.size)
    assertSame(original, project.document)
    assertTrue(project.isModified)
  }

  @Test
  fun `pending Save As does not report completion and restores association on delayed failure`() {
    val project = project()
    val original = project.document
    val pending = SimpleBarrier<Boolean>()
    val completions = mutableListOf<Boolean>()
    saveAsDocument(project, FileDocument(dir.resolve("copy.gan").toFile()), project.documentManager,
      { pending }, completions::add, { throw AssertionError(it) })
    assertTrue(completions.isEmpty())
    assertNotSame(original, project.document)
    pending.resolve(false)
    assertEquals(listOf(false), completions)
    assertSame(original, project.document)
    assertTrue(project.isModified)
  }

  @TestFactory
  fun `facade applies successful save bookkeeping exactly once and never on failure`() =
    listOf("success", "serialization", "write", "close").map { outcome -> dynamicTest(outcome) {
      runBlocking { withContext(Dispatchers.JavaFx) {
        // These tests close real error windows. Keep the shared test-worker FX runtime alive for later tests.
        Platform.setImplicitExit(false)
        val project = project()
        if (outcome == "serialization") project.seed(record().copy(description = "bad\u0001"))
        if (outcome == "write" || outcome == "close") {
          val physical = object : Document by project.document {
            override fun getOutputStream(): OutputStream = object : OutputStream() {
              override fun write(b: Int) { if (outcome == "write") throw IOException("write failed") }
              override fun close() { if (outcome == "close") throw IOException("close failed") }
            }
          }
          project.document = project.documentManager.getProxyDocument(physical)
        }
        var recent = 0
        var directory = 0
        val documents = object : DocumentManager by project.documentManager {
          override fun addToRecentDocuments(document: Document) { recent++; assertSame(project.document, document) }
          override fun changeWorkingDirectory(parentFile: File) { directory++; assertEquals(dir.toFile(), parentFile) }
        }
        val ui: UIFacade = createMock(UIFacade::class.java)
        val chart: Chart = createMock(Chart::class.java)
        val undo: GPUndoManager = createMock(GPUndoManager::class.java)
        expect(ui.activeChart).andReturn(chart)
        chart.focus()
        if (outcome == "success") ui.setWorkbenchTitle(GanttLanguage.getInstance().getText("appliTitle") + " [current.gan]")
        replay(ui, chart, undo)
        val builders = ProjectOpenActivityFactory::class.java.getDeclaredField("builders").apply { isAccessible = true }
          .get(ProjectOpenActivityFactory) as MutableList<*>
        val builderCount = builders.size
        val dialogs = Class.forName("biz.ganttproject.app.DialogKt").getDeclaredField("dialogStack").apply { isAccessible = true }
          .get(null) as MutableList<*>
        val dialogCount = dialogs.size
        val windows = Window.getWindows().toSet()
        try {
          val facade = ProjectUIFacadeImpl(Stage(), ui, documents, undo, project)
          val completions = mutableListOf<Boolean>()
          facade.saveProject(project).await(completions::add)
          val success = outcome == "success"
          assertEquals(listOf(success), completions)
          assertEquals(!success, project.isModified)
          assertEquals(if (success) 1 else 0, recent)
          assertEquals(if (success) 1 else 0, directory)
          verify(ui, chart, undo)
        } finally {
          // Drain the real facade's queued error UI, then dispose only this test's windows/registrations.
          suspendCancellableCoroutine<Unit> { continuation -> Platform.runLater { continuation.resume(Unit) } }
          Window.getWindows().filter { it !in windows }.forEach { it.hide() }
          while (dialogs.size > dialogCount) dialogs.removeAt(dialogs.lastIndex)
          while (builders.size > builderCount) builders.removeAt(builders.lastIndex)
        }
      } }
    } }

  @TestFactory
  fun `storage completion after native authentication retry keeps failure UI and association truthful`() =
    listOf(false, true).map { switchDocument -> dynamicTest("candidate replaced=$switchDocument") {
      val project = project()
      val original = project.document
      val destination = GPCloudDocument("test-team", "Test", "slice2-save-failure", "Copy", null)
      val originalProxyFactory = destination.proxyDocumentFactory
      val originalOfflineFactory = destination.offlineDocumentFactory
      var attempts = 0
      val failure = IOException("retry output close failed")
      destination.httpClientFactory = {
        attempts++
        if (attempts == 1) throw ForbiddenException() else throw failure
      }
      var retry: (() -> Unit)? = null
      val errors = mutableListOf<Exception>()
      val completions = mutableListOf<Boolean>()
      val ui: ProjectUIFacade = createMock(ProjectUIFacade::class.java)
      expect(ui.saveProject(project)).andAnswer {
        SimpleBarrier<Boolean>().also { barrier ->
          barrier.await(completions::add)
          ProjectSaveFlow(project, barrier, { retry = it }, errors::add, { fail("Unexpected Save As") }).run()
        }
      }
      var progressStopped = 0
      val dialog: DialogController = createMock(DialogController::class.java)
      expect(dialog.toggleProgress(true)).andReturn { progressStopped++ }
      // No hide() is expected: a failed save must leave the real storage completion path open.
      replay(ui, dialog)
      val builder = StorageDialogBuilder(project, ui, project.documentManager, GPCloudStorageOptions(), dialog)
      @Suppress("UNCHECKED_CAST")
      val update = StorageDialogBuilder::class.java.getDeclaredField("myDocumentUpdater").apply { isAccessible = true }
        .get(builder) as Consumer<Document>
      update.accept(destination)
      assertEquals(1, attempts)
      assertNotNull(retry)
      assertTrue(completions.isEmpty())
      assertEquals(0, progressStopped)
      val replacement = project.documentManager.getProxyDocument(FileDocument(dir.resolve("another.gan").toFile()))
      if (switchDocument) project.document = replacement
      SwingUtilities.invokeAndWait { retry!!.invoke() }
      assertEquals(2, attempts)
      assertEquals(listOf(false), completions)
      assertEquals(listOf(failure), errors)
      assertEquals(1, progressStopped)
      assertSame(if (switchDocument) replacement else original, project.document)
      assertSame(originalProxyFactory, destination.proxyDocumentFactory, "No success-only onboarding")
      assertSame(originalOfflineFactory, destination.offlineDocumentFactory, "No success-only onboarding")
      assertTrue(project.isModified)
      verify(ui, dialog)
    } }
}
