/*
 * Copyright 2026 Oasis Project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package oasis.project.persistence

import java.io.IOException

/** A native file's Oasis payload cannot be read or written without losing information. */
class OasisPersistenceException(message: String, cause: Throwable? = null) : IOException(message, cause) {
  companion object {
    /** Document wrappers retain causes. Do not reinterpret an incompatible Oasis file as another format. */
    fun causedByOasis(error: Throwable): Boolean = generateSequence(error) { it.cause }
      .any { it is OasisPersistenceException }
  }
}
