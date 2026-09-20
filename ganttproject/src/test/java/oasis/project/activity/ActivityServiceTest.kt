/*
 * Copyright 2026 Oasis Project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package oasis.project.activity

import oasis.project.model.OasisProjectData
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID

class ActivityServiceTest {
  private val data = OasisProjectData()
  private val now = Instant.parse("2026-09-19T10:00:00Z")
  private val fixedId = ActivityId(UUID.fromString("ca30aeda-f8cf-4d71-87bb-85e1a785cd6b"))
  private val taskUid = TaskUid("native-task:opaque/123")
  private val content = ActivityContent(LocalDate.of(2020, 2, 29), "note", "Called the site manager")
  private val service = ActivityService(data, Clock.fixed(now, ZoneOffset.UTC)) { fixedId }

  @Test
  fun `create backdated activity with independent identity and audit timestamps`() {
    val record = service.add(taskUid, content)
    assertEquals(fixedId, record.id)
    assertEquals(taskUid, record.taskUid)
    assertEquals(content, record.content)
    assertEquals(now, record.createdAt)
    assertEquals(now, record.modifiedAt)
    assertNull(record.occurredAt)
    assertNull(record.personCompany)
    assertNull(record.planSummary)
    assertNull(record.sourceReference)
    assertSame(record, service.get(fixedId))
    assertEquals(1L, data.revision)
  }

  @Test
  fun `blank required fields cannot commit`() {
    listOf("", " ", "\t\n").forEach { blank ->
      assertThrows<IllegalArgumentException> { service.add(taskUid, content.copy(description = blank)) }
      assertThrows<IllegalArgumentException> { service.add(taskUid, content.copy(typeCode = blank)) }
    }
    assertTrue(data.state.activities.isEmpty())
    assertTrue(data.activityIdsByTask.isEmpty())
    assertEquals(0L, data.revision)
  }

  @Test
  fun `required fields reject null from JVM callers`() {
    assertThrows<NullPointerException> { content.copy(occurredOn = javaNull()) }
    assertThrows<NullPointerException> { content.copy(typeCode = javaNull()) }
    assertThrows<NullPointerException> { content.copy(description = javaNull()) }
  }

  @Test
  fun `optional values and nonblank text are preserved verbatim`() {
    val fullContent = content.copy(
      occurredAt = LocalTime.of(15, 45), personCompany = "  Jane / Builder Co  ",
      planSummary = "Arrange inspection\nConfirm access", sourceReference = "Email #123",
      description = "  Preserve the author's formatting\n"
    )
    assertEquals(fullContent, service.add(taskUid, fullContent).content)
  }

  @Test
  fun `blank optional fields normalize to null`() {
    val record = service.add(taskUid, content.copy(personCompany = "", planSummary = " \t", sourceReference = "\n"))
    assertEquals(content, record.content)
  }

  @Test
  fun `edits preserve ID task and creation time while updating modification time`() {
    val original = service.add(taskUid, content)
    val later = now.plusSeconds(60)
    val editor = ActivityService(data, Clock.fixed(later, ZoneOffset.UTC)) { error("An edit must not generate an ID") }
    val changed = content.copy(occurredOn = LocalDate.of(1999, 12, 31), typeCode = "meeting", description = "Corrected")
    val updated = editor.edit(original.id, changed)
    assertEquals(original.id, updated.id)
    assertEquals(original.taskUid, updated.taskUid)
    assertEquals(original.createdAt, updated.createdAt)
    assertEquals(later, updated.modifiedAt)
    assertEquals(changed, updated.content)
    assertEquals(content, original.content)
    assertEquals(now, original.modifiedAt)
    assertEquals(2L, data.revision)
  }

  @Test
  fun `every editable field can be changed and optional values can be cleared`() {
    val original = service.add(taskUid, content)
    val changed = content.copy(
      occurredOn = content.occurredOn.plusDays(1), occurredAt = LocalTime.NOON,
      typeCode = "email", personCompany = "Company", description = "Revised",
      planSummary = "Next steps", sourceReference = "Reference"
    )
    assertEquals(changed, service.edit(original.id, changed).content)
    assertEquals(content, service.edit(original.id, content).content)
    assertEquals(3L, data.revision)
  }

  @Test
  fun `normalized no-op edit preserves record snapshot index timestamp and revision`() {
    val original = service.add(taskUid, content)
    val snapshot = data.state
    val index = data.activityIdsByTask
    val laterEditor = ActivityService(data, Clock.fixed(now.plusSeconds(60), ZoneOffset.UTC))
    assertSame(original, laterEditor.edit(original.id, content))
    assertSame(original, laterEditor.edit(original.id, content.copy(personCompany = " ", planSummary = "", sourceReference = "\n")))
    assertSame(snapshot, data.state)
    assertSame(index, data.activityIdsByTask)
    assertEquals(now, service.get(original.id)!!.modifiedAt)
    assertEquals(1L, data.revision)
  }

  @Test
  fun `failed edit and unknown ID leave state untouched`() {
    val original = service.add(taskUid, content)
    val snapshot = data.state
    assertThrows<IllegalArgumentException> { service.edit(original.id, content.copy(description = " ")) }
    assertThrows<IllegalArgumentException> { service.edit(original.id, content.copy(typeCode = "")) }
    assertThrows<IllegalArgumentException> { service.edit(ActivityId(UUID.randomUUID()), content) }
    assertSame(snapshot, data.state)
    assertEquals(1L, data.revision)
  }

  @Test
  fun `generated ID collisions cannot overwrite existing activities`() {
    service.add(taskUid, content)
    val snapshot = data.state
    assertThrows<IllegalArgumentException> { service.add(TaskUid("another-task"), content) }
    assertSame(snapshot, data.state)
    assertEquals(1L, data.revision)
  }

  @Test
  fun `queries and deletes keep multiple task histories and index consistent`() {
    val many = ActivityService(data, Clock.fixed(now, ZoneOffset.UTC))
    val otherUid = TaskUid("another-task")
    val first = many.add(taskUid, content)
    val second = many.add(taskUid, content.copy(typeCode = "phone_call"))
    val other = many.add(otherUid, content)
    assertNotEquals(first.id, second.id)
    assertEquals(listOf(first, second), many.forTask(taskUid))
    assertEquals(listOf(other), many.forTask(otherUid))
    assertTrue(many.forTask(TaskUid("absent")).isEmpty())
    assertNull(many.get(ActivityId(UUID.randomUUID())))
    assertEquals(mapOf(taskUid to listOf(first.id, second.id), otherUid to listOf(other.id)), data.activityIdsByTask)

    many.edit(second.id, content.copy(description = "Edited"))
    assertEquals(listOf(first.id, second.id), data.activityIdsByTask[taskUid])
    assertTrue(many.delete(first.id))
    assertNull(many.get(first.id))
    assertEquals(listOf(second.id), data.activityIdsByTask[taskUid])
    assertTrue(many.delete(second.id))
    assertTrue(many.forTask(taskUid).isEmpty())
    assertFalse(taskUid in data.activityIdsByTask)
    assertEquals(listOf(other), many.forTask(otherUid))
    val revision = data.revision
    assertFalse(many.delete(second.id))
    assertEquals(revision, data.revision)
  }

  @Test
  fun `query results are immutable and remain snapshots after edits`() {
    val original = service.add(taskUid, content)
    val results = service.forTask(taskUid)
    assertThrows<UnsupportedOperationException> { (results as MutableList).clear() }
    service.edit(original.id, content.copy(description = "New"))
    assertEquals(listOf(original), results)
  }

  @Test
  fun `registry includes built-ins and can recognize additional codes without constraining records`() {
    val registry = ActivityTypeRegistry()
    assertEquals(setOf("note", "email", "phone_call", "meeting", "site_visit", "decision", "other"), registry.codes)
    registry.codes.forEach { assertTrue(registry.isKnown(it)) }
    val unknown = "future:inspection/v2"
    assertFalse(registry.isKnown(unknown))
    val record = service.add(taskUid, content.copy(typeCode = unknown))
    assertEquals(unknown, record.typeCode)
    assertEquals(unknown, service.edit(record.id, record.content.copy(description = "Edited")).typeCode)
    assertTrue(ActivityTypeRegistry(registry.codes + unknown).isKnown(unknown))
    assertThrows<IllegalArgumentException> { ActivityTypeRegistry(listOf(" ")) }
  }

  @Test
  fun `task UIDs are opaque case-sensitive identifiers`() {
    assertEquals(" Native/UID:123 ", TaskUid(" Native/UID:123 ").value)
    assertNotEquals(TaskUid("TASK"), TaskUid("task"))
    assertThrows<IllegalArgumentException> { TaskUid("\t ") }
  }

  @Test
  fun `direct record construction and copy cannot bypass content invariants`() {
    val record = service.add(taskUid, content)
    assertThrows<IllegalArgumentException> { record.copy(description = "") }
    assertThrows<IllegalArgumentException> { record.copy(typeCode = " ") }
    assertThrows<IllegalArgumentException> { record.copy(personCompany = " ") }
    assertThrows<IllegalArgumentException> { record.copy(planSummary = " ") }
    assertThrows<IllegalArgumentException> { record.copy(sourceReference = " ") }
  }

  // Models a Java caller passing null through a JVM signature, without weakening the Kotlin API.
  @Suppress("UNCHECKED_CAST")
  private fun <T> javaNull(): T = null as T
}
