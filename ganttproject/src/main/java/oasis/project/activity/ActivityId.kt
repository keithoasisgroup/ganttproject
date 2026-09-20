/*
 * Copyright 2026 Oasis Project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package oasis.project.activity

import java.util.UUID

/** An activity's identity is independent of its task and occurrence date. */
data class ActivityId(val value: UUID)
