/*
 * Copyright 2026 Oasis Project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package oasis.project.model

import oasis.project.activity.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class OasisProjectDataTest {
  private val uid = TaskUid("task-1")
  private val record = ActivityRecord(
    ActivityId(UUID.randomUUID()), uid, LocalDate.of(2001, 2, 3), "unknown-future-type", "Original",
    Instant.parse("2024-01-01T00:00:00.123456789Z"), Instant.parse("2025-01-01T00:00:00.987654321Z")
  )

  @Test
  fun `snapshot copies input and exposes immutable records and index collections`() {
    val input = mutableListOf(record)
    val state = OasisProjectState(input)
    val data = OasisProjectData(state)
    input.clear()
    assertEquals(mapOf(record.id to record), state.activities)
    assertThrows<UnsupportedOperationException> { (state.activities as MutableMap).clear() }
    assertThrows<UnsupportedOperationException> { (data.activityIdsByTask as MutableMap).clear() }
    assertThrows<UnsupportedOperationException> { (data.activityIdsByTask.getValue(uid) as MutableList).clear() }
    assertEquals(mapOf(uid to listOf(record.id)), data.activityIdsByTask)
  }

  @Test
  fun `duplicate IDs are rejected even when records belong to different tasks`() {
    assertThrows<IllegalArgumentException> { OasisProjectState(listOf(record, record)) }
    assertThrows<IllegalArgumentException> { OasisProjectState(listOf(record, record.copy(taskUid = TaskUid("task-2")))) }
  }

  @Test
  fun `snapshot replacement rebuilds index preserves exact timestamps and increments revision only on change`() {
    val original = OasisProjectState(listOf(record))
    val data = OasisProjectData(original)
    val originalIndex = data.activityIdsByTask
    val moved = record.copy(taskUid = TaskUid("explicitly-mapped-task"))
    data.replaceState(OasisProjectState(listOf(moved)))
    assertEquals(mapOf(moved.taskUid to listOf(record.id)), data.activityIdsByTask)
    assertEquals(mapOf(uid to listOf(record.id)), originalIndex)
    assertEquals(mapOf(record.id to record), original.activities)
    assertEquals(1L, data.revision)
    data.replaceState(original)
    assertSame(original, data.state)
    assertEquals(record.createdAt, data.state.activities.getValue(record.id).createdAt)
    assertEquals(record.modifiedAt, data.state.activities.getValue(record.id).modifiedAt)
    assertEquals(mapOf(uid to listOf(record.id)), data.activityIdsByTask)
    assertEquals(2L, data.revision)
    data.replaceState(OasisProjectState(listOf(record)))
    assertSame(original, data.state)
    assertEquals(2L, data.revision)
    data.clear()
    assertTrue(data.state.activities.isEmpty())
    assertTrue(data.activityIdsByTask.isEmpty())
    assertEquals(3L, data.revision)
    data.clear()
    assertEquals(3L, data.revision)
  }

  @Test
  fun `reordering equal records replaces the snapshot and index once`() {
    val second = record.copy(id = ActivityId(UUID.randomUUID()))
    val data = OasisProjectData(OasisProjectState(listOf(record, second)))
    val originalIndex = data.activityIdsByTask
    val reordered = OasisProjectState(listOf(second, record))
    data.replaceState(reordered)
    assertSame(reordered, data.state)
    assertEquals(listOf(second.id, record.id), data.activityIdsByTask.getValue(uid))
    assertEquals(listOf(record.id, second.id), originalIndex.getValue(uid))
    assertNotSame(originalIndex, data.activityIdsByTask)
    assertEquals(1L, data.revision)
    val reorderedIndex = data.activityIdsByTask
    data.replaceState(OasisProjectState(listOf(second, record)))
    assertSame(reordered, data.state)
    assertSame(reorderedIndex, data.activityIdsByTask)
    assertEquals(1L, data.revision)
  }

  @Test
  fun `holders initialized from the same immutable snapshot evolve independently`() {
    val snapshot = OasisProjectState(listOf(record))
    val first = OasisProjectData(snapshot)
    val second = OasisProjectData(snapshot)
    ActivityService(first).edit(record.id, record.content.copy(description = "Only first"))
    assertEquals(record, second.state.activities[record.id])
    second.clear()
    assertEquals("Only first", first.state.activities[record.id]!!.description)
    assertEquals(snapshot.activities, mapOf(record.id to record))
  }
}
