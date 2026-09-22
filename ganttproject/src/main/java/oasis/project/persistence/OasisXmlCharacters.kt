/*
 * Copyright 2026 Oasis Project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package oasis.project.persistence

/** V1 always writes XML 1.0, including values read from XML 1.1 documents. */
internal fun validateOasisXmlText(value: String, path: String) {
  var index = 0
  while (index < value.length) {
    val codePoint = value.codePointAt(index)
    if (codePoint != 9 && codePoint != 10 && codePoint != 13 &&
      codePoint !in 0x20..0xD7FF && codePoint !in 0xE000..0xFFFD && codePoint !in 0x10000..0x10FFFF) {
      throw OasisPersistenceException("$path: XML 1.0 cannot represent U+${codePoint.toString(16).uppercase()} at character $index")
    }
    index += Character.charCount(codePoint)
  }
}
