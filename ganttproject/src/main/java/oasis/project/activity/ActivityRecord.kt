/*
 * Copyright 2026 Oasis Project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package oasis.project.activity

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Immutable historical record. Occurrence values describe the real-world event;
 * audit timestamps describe the record and can be restored exactly in a snapshot.
 * No native Task or scheduling object is retained here.
 */
data class ActivityRecord(
  val id: ActivityId,
  val taskUid: TaskUid,
  val occurredOn: LocalDate,
  val typeCode: String,
  val description: String,
  val createdAt: Instant,
  val modifiedAt: Instant,
  val occurredAt: LocalTime? = null,
  val personCompany: String? = null,
  val planSummary: String? = null,
  val sourceReference: String? = null
) {
  init {
    require(typeCode.isNotBlank()) { "Activity type code must not be blank" }
    require(description.isNotBlank()) { "Activity description must not be blank" }
    require(listOf(personCompany, planSummary, sourceReference).all { it == null || it.isNotBlank() }) {
      "Blank optional activity fields must be represented as null"
    }
  }

  val content: ActivityContent
    get() = ActivityContent(occurredOn, typeCode, description, occurredAt, personCompany, planSummary, sourceReference)
}

/** Editable input, without identity or audit fields. The service normalizes blank optional text to null. */
data class ActivityContent(
  val occurredOn: LocalDate,
  val typeCode: String,
  val description: String,
  val occurredAt: LocalTime? = null,
  val personCompany: String? = null,
  val planSummary: String? = null,
  val sourceReference: String? = null
) {
  internal fun toRecord(id: ActivityId, taskUid: TaskUid, createdAt: Instant, modifiedAt: Instant) = ActivityRecord(
    id = id,
    taskUid = taskUid,
    occurredOn = occurredOn,
    typeCode = typeCode,
    description = description,
    createdAt = createdAt,
    modifiedAt = modifiedAt,
    occurredAt = occurredAt,
    personCompany = personCompany?.takeUnless { it.isBlank() },
    planSummary = planSummary?.takeUnless { it.isBlank() },
    sourceReference = sourceReference?.takeUnless { it.isBlank() }
  )
}
