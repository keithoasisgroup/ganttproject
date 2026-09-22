/*
 * Copyright 2026 Oasis Project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package oasis.project.persistence

import net.sourceforge.ganttproject.document.Document
import net.sourceforge.ganttproject.language.GanttLanguage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class OasisXmlVersionTest {
  @TempDir lateinit var dir: Path
  @BeforeEach fun initializeLocale() { GanttLanguage.getInstance() }

  @TestFactory
  fun `XML 1_1 only controls in any persisted text field fail before native processing or installation`() =
    listOf("task-uid", "type-code", "description", "person-company", "plan-summary", "source-reference").map { field ->
      dynamicTest(field) {
        val project = NativePersistenceProject(dir.resolve("current.gan"))
        project.seed(record())
        val state = project.oasisProjectData.state
        val index = project.oasisProjectData.activityIdsByTask
        val revision = project.oasisProjectData.revision
        val activity = if (field in listOf("task-uid", "type-code", "description"))
          ACTIVITY_XML.replace(Regex("<$field>.*?</$field>"), "<$field>before&#x1;after</$field>")
        else ACTIVITY_XML.replace("</activity>", "<$field>before&#x1;after</$field></activity>")
        val failure = assertThrows<Document.DocumentException> {
          project.openXml("<?xml version=\"1.1\"?>" + projectXml(activity))
        }
        assertTrue(OasisPersistenceException.causedByOasis(failure))
        assertSame(state, project.oasisProjectData.state)
        assertSame(index, project.oasisProjectData.activityIdsByTask)
        assertEquals(revision, project.oasisProjectData.revision)
        project.document.write()
      }
    }

  @Test
  fun `XML 1_1 with XML 1_0 representable values loads and resaves through native machinery`() {
    val project = NativePersistenceProject(dir.resolve("accepted.gan"))
    project.openXml("<?xml version=\"1.1\"?>" + projectXml(ACTIVITY_XML.replace(">text<", ">Café &#x85; &#13; 🚀<")))
    assertEquals("Café \u0085 \r 🚀", project.oasisProjectData.state.activities.values.single().description)
    val saved = nativeXml(project)
    assertTrue(saved.contains("version=\"1.0\""))
    val reopened = NativePersistenceProject(dir.resolve("reopened.gan"))
    reopened.openXml(saved)
    assertEquals(project.oasisProjectData.state, reopened.oasisProjectData.state)
    reopened.document.write()
  }
}
