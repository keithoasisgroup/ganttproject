/*
 * Copyright 2026 Oasis Project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package oasis.project.persistence

import oasis.project.activity.ActivityId
import oasis.project.activity.ActivityRecord
import oasis.project.activity.TaskUid
import oasis.project.model.OasisProjectState
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.Collections
import java.util.UUID

/** Wire values only: no revision, derived index, task objects or type labels. */
class OasisSnapshotV1(activities: Collection<ActivityDtoV1>) {
  val activities: List<ActivityDtoV1> = Collections.unmodifiableList(ArrayList(activities))

  fun toState(): OasisProjectState {
    val ids = HashSet<ActivityId>()
    val records = activities.mapIndexed { index, dto ->
      val path = "/project/oasis/activities/activity[${index + 1}]"
      dto.toRecord(path).also {
        if (!ids.add(it.id)) throw OasisPersistenceException("$path/@id: duplicate ActivityId ${dto.id}")
      }
    }
    return OasisProjectState(records)
  }

  companion object {
    fun fromState(state: OasisProjectState) = OasisSnapshotV1(state.activities.values.map(ActivityDtoV1::fromRecord))
  }
}

data class ActivityDtoV1(
  val id: String,
  val taskUid: String,
  val occurredOn: String,
  val typeCode: String,
  val description: String,
  val createdAt: String,
  val modifiedAt: String,
  val occurredAt: String? = null,
  val personCompany: String? = null,
  val planSummary: String? = null,
  val sourceReference: String? = null
) {
  internal fun textFields(): Map<String, String?> = linkedMapOf(
    "task-uid" to taskUid, "type-code" to typeCode, "description" to description,
    "person-company" to personCompany, "plan-summary" to planSummary, "source-reference" to sourceReference
  )

  internal fun attributes(): Map<String, String?> = linkedMapOf(
    "id" to id, "occurred-on" to occurredOn, "occurred-at" to occurredAt,
    "created-at" to createdAt, "modified-at" to modifiedAt
  )

  internal fun toRecord(path: String): ActivityRecord {
    fun <T> field(name: String, value: String, parse: (String) -> T): T = try {
      parse(value)
    } catch (ex: IllegalArgumentException) {
      throw OasisPersistenceException("$path/$name: invalid value", ex)
    } catch (ex: java.time.DateTimeException) {
      throw OasisPersistenceException("$path/$name: invalid temporal value '$value'", ex)
    }
    textFields().forEach { (name, value) ->
      if (value != null) validateOasisXmlText(value, "$path/$name")
      if (value != null && value.isBlank()) throw OasisPersistenceException("$path/$name: must not be blank")
    }
    val uuid = field("@id", id) {
      require(UUID_PATTERN.matches(it)) { "Expected a full hyphenated UUID" }
      UUID.fromString(it)
    }
    fun instant(name: String, value: String) = field(name, value) {
      // Instant.parse accepts leap seconds and 24:00 by normalizing them. V1 must not silently normalize input.
      require(INSTANT_PATTERN.matches(it)) { "Expected an ISO instant with seconds and UTC Z" }
      Instant.parse(it)
    }
    return ActivityRecord(
      ActivityId(uuid), TaskUid(taskUid), field("@occurred-on", occurredOn, LocalDate::parse), typeCode, description,
      instant("@created-at", createdAt), instant("@modified-at", modifiedAt),
      occurredAt?.let { value -> field("@occurred-at", value) {
        require(TIME_PATTERN.matches(it)) { "Expected an ISO local time" }
        LocalTime.parse(it)
      } }, personCompany, planSummary, sourceReference
    )
  }

  companion object {
    private val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val TIME_PATTERN = Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9](?::[0-5][0-9](?:\\.[0-9]{1,9})?)?")
    private val INSTANT_PATTERN = Regex("(?:[0-9]{4}|-[0-9]{4,10}|\\+[0-9]{5,10})-[0-9]{2}-[0-9]{2}T(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](?:\\.[0-9]{1,9})?Z")

    fun fromRecord(record: ActivityRecord) = ActivityDtoV1(
      record.id.value.toString(), record.taskUid.value, record.occurredOn.toString(), record.typeCode, record.description,
      record.createdAt.toString(), record.modifiedAt.toString(), record.occurredAt?.toString(),
      record.personCompany, record.planSummary, record.sourceReference
    )
  }
}
