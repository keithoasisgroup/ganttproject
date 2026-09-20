/*
 * Copyright 2026 Oasis Project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package oasis.project.activity

import java.util.Collections

/** Known type codes for discovery, not a whitelist for stored records. */
class ActivityTypeRegistry(typeCodes: Collection<String> = BUILT_IN_CODES) {
  val codes: Set<String> = Collections.unmodifiableSet(LinkedHashSet(typeCodes))

  init {
    require(codes.all { it.isNotBlank() }) { "Activity type codes must not be blank" }
  }

  fun isKnown(typeCode: String): Boolean = typeCode in codes

  companion object {
    val BUILT_IN_CODES: Set<String> = Collections.unmodifiableSet(linkedSetOf(
      "note", "email", "phone_call", "meeting", "site_visit", "decision", "other"
    ))
  }
}
