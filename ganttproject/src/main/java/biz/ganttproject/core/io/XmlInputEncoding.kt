/*
 * Copyright 2026 Oasis Project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package biz.ganttproject.core.io

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import javax.xml.stream.XMLInputFactory

/** Shared byte/character boundary for document opening and the headless importer. */
object XmlInputEncoding {
  /** Identifies this byte-decoding boundary without assigning native/Oasis intent to foreign input. */
  class DecodingException(cause: Exception) : IOException("Cannot decode native XML using its declared byte encoding", cause)

  @JvmStatic
  @Throws(IOException::class)
  fun decode(bytes: ByteArray): String = try {
    // Read only the XML declaration to identify encoding; no DTD or external resource is processed.
    val factory = XMLInputFactory.newDefaultFactory().apply {
      setProperty(XMLInputFactory.SUPPORT_DTD, false)
      setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
    }
    val reader = factory.createXMLStreamReader(bytes.inputStream())
    val encoding = try { reader.encoding ?: "UTF-8" } finally { reader.close() }
    Charset.forName(encoding).newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
      .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
  } catch (ex: Exception) {
    throw DecodingException(ex)
  }

  @Throws(IOException::class)
  fun canonicalUtf8(xml: String): ByteArray = try {
    // Validate before replacing the declaration, so a surrogate in that declaration cannot be concealed.
    xml.encodeToByteArray(throwOnInvalidSequence = true)
    val text = xml.removePrefix("\uFEFF")
    val declaration = XML_DECLARATION.find(text)
    val normalized = if (declaration == null) text else text.replaceRange(declaration.range,
      XML_ENCODING.replace(declaration.value) { "${it.groupValues[1]}\"UTF-8\"" })
    normalized.encodeToByteArray(throwOnInvalidSequence = true)
  } catch (ex: CharacterCodingException) {
    throw IOException("Invalid Unicode in native XML input", ex)
  }

  private val XML_DECLARATION = Regex("\\A<\\?xml(?=[ \\t\\r\\n])[^?]*\\?>")
  private val XML_ENCODING = Regex("([ \\t\\r\\n]encoding[ \\t\\r\\n]*=[ \\t\\r\\n]*)([\"'])[^\"']*\\2")
}
