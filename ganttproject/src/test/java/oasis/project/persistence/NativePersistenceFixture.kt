/*
 * Copyright 2026 Oasis Project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package oasis.project.persistence

import net.sourceforge.ganttproject.GanttProjectImpl
import net.sourceforge.ganttproject.document.Document
import net.sourceforge.ganttproject.document.FileDocument
import net.sourceforge.ganttproject.export.ConsoleUIFacade
import net.sourceforge.ganttproject.importer.BufferProject
import net.sourceforge.ganttproject.io.GanttXMLSaver
import oasis.project.activity.*
import oasis.project.model.OasisProjectState
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/** Real native document factory/parser/saver and close-event wiring, without a desktop window. */
internal open class NativePersistenceProject(path: Path) : BufferProject(GanttProjectImpl(), ConsoleUIFacade(null)) {
  override var document: Document = documentManager.getProxyDocument(FileDocument(path.toFile()).also { it.create() })

  init { addProjectEventListener(taskManager.projectListener) }

  override fun close() { fireProjectClosed() }

  override fun open(document: Document) {
    document.read()
    this.document = document
  }

  fun openXml(xml: String) = openXml(xml.toByteArray(Charsets.UTF_8))

  fun openXml(bytes: ByteArray) {
    val source = object : Document by document {
      override fun getInputStream() = bytes.inputStream()
    }
    open(documentManager.getProxyDocument(source))
  }
}

internal fun nativeXml(project: GanttProjectImpl): String = ByteArrayOutputStream().also {
  GanttXMLSaver(project).save(it)
}.toString(Charsets.UTF_8)

internal fun record(number: Long = 1, uid: String = "unresolved-uid") = ActivityRecord(
  ActivityId(UUID(0, number)), TaskUid(uid), LocalDate.of(2020, 2, 29), "unknown-type", "Description $number",
  Instant.parse("2026-09-19T10:11:12.123456789Z"), Instant.parse("2025-01-01T01:02:03.987654321Z"),
  LocalTime.of(9, 30, 0, 123456789), "Person", "Plan", "Source"
)

internal fun GanttProjectImpl.seed(vararg records: ActivityRecord) = oasisProjectData.replaceState(OasisProjectState(records.toList()))

internal const val ACTIVITY_XML = """<activity id="00000000-0000-0000-0000-000000000001" occurred-on="2020-02-29" created-at="2026-09-19T10:11:12.123456789Z" modified-at="2025-01-01T01:02:03.987654321Z"><task-uid>opaque uid</task-uid><type-code>future</type-code><description>text</description></activity>"""
internal fun projectXml(activity: String = ACTIVITY_XML) = """<project><oasis schema-version="1"><activities>$activity</activities></oasis></project>"""

/** Insert the undefined byte directly; a test-side charset encoder must not replace it. */
internal fun windows1252WithUndefinedByte(xml: String = projectXml(ACTIVITY_XML.replace(">text<", ">before~after<"))): ByteArray {
  val bytes = ("<?xml version=\"1.0\" encoding=\"windows-1252\"?>" + xml).toByteArray(Charsets.US_ASCII)
  bytes[bytes.indexOf('~'.code.toByte())] = 0x81.toByte()
  return bytes
}
