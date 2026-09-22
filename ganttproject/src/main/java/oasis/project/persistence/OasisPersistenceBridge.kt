/*
 * Copyright 2026 Oasis Project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package oasis.project.persistence

import biz.ganttproject.core.io.XmlInputEncoding
import oasis.project.lifecycle.OasisLifecycleBridge
import oasis.project.model.OasisProjectData
import oasis.project.model.OasisProjectState
import java.io.IOException
import java.io.InputStream

/** One collaborator per parser/project. All state installation occurs on the project's model thread. */
class OasisPersistenceBridge(private val data: OasisProjectData, private val lifecycle: OasisLifecycleBridge) {
  fun interface NativeLoader {
    @Throws(IOException::class)
    fun load(input: InputStream)
  }

  private sealed interface PreparedLoad {
    data object Skip : PreparedLoad
    data class Replace(val state: OasisProjectState, val isNativeProject: Boolean) : PreparedLoad
  }

  @Throws(IOException::class)
  fun load(input: InputStream, nativeLoader: NativeLoader) {
    // Check at load time. Internal restores never invoke the Oasis reader, even for an unsupported schema.
    val prepared: PreparedLoad
    val nativeInput: InputStream
    if (lifecycle.isInternalRestore) {
      prepared = PreparedLoad.Skip
      nativeInput = input
    } else {
      // Read a possibly one-shot source exactly once; both parsers observe these same immutable contents.
      val bytes = input.readAllBytes()
      val candidate = OasisXmlReader().read(bytes)
      prepared = PreparedLoad.Replace(candidate.state, candidate.isNativeProject)
      nativeInput = bytes.inputStream()
    }
    try {
      nativeLoader.load(nativeInput)
    } catch (ex: XmlInputEncoding.DecodingException) {
      // A successful SAX preflight may have accepted a byte that strict decoding rejects later.
      // Only its actual native-root evidence makes this terminal; foreign input and restores keep their routing.
      if (prepared is PreparedLoad.Replace && prepared.isNativeProject) {
        throw OasisPersistenceException("Invalid byte encoding in native project XML", ex)
      }
      throw ex
    }
    when (prepared) {
      PreparedLoad.Skip -> Unit
      is PreparedLoad.Replace -> data.replaceState(prepared.state)
    }
  }
}
