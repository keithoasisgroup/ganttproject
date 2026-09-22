/*
 * Copyright 2026 Oasis Project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package oasis.project.persistence

import oasis.project.model.OasisProjectState
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.Locator
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.ext.DefaultHandler2
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParserFactory
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException

/** Strict only inside Oasis; native sections keep their existing compatibility rules. */
class OasisXmlReader {
  internal data class ReadResult(val state: OasisProjectState, val isNativeProject: Boolean)

  @Throws(IOException::class)
  fun read(input: InputStream): OasisProjectState = read(input.readAllBytes()).state

  @Throws(IOException::class)
  internal fun read(bytes: ByteArray): ReadResult {
    val handler = Handler()
    try {
      val factory = SAXParserFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
      }
      factory.newSAXParser().xmlReader.apply {
        contentHandler = handler
        errorHandler = handler
        setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        // Reject at the lexical callback, before reading any subset/entity, while the declared root is available.
        setProperty("http://xml.org/sax/properties/lexical-handler", handler)
      }.parse(InputSource(bytes.inputStream()))
      return ReadResult(OasisSnapshotV1(handler.activities).toState(), handler.sawNativeProject)
    } catch (ex: OasisPersistenceException) {
      throw ex
    } catch (ex: SAXException) {
      throw xmlFailure(bytes, handler, ex)
    } catch (ex: javax.xml.parsers.ParserConfigurationException) {
      throw OasisPersistenceException("Cannot configure the Oasis XML reader", ex)
    }
  }

  private fun xmlFailure(bytes: ByteArray, handler: Handler, ex: SAXException): IOException {
    // Preserve startup fallback for non-native inputs (e.g. binary MS Project files).
    return if (handler.sawNativeProject || (nativeRootIntent(bytes) ?: handler.declaredNativeProject)) {
      OasisPersistenceException("Invalid native XML while reading Oasis: ${ex.message}", ex)
    } else IOException("Invalid XML: ${ex.message}", ex)
  }

  /** Failure-only classification: ignore DTD declarations without processing them, and inspect the actual root. */
  private fun nativeRootIntent(bytes: ByteArray): Boolean? {
    val factory = XMLInputFactory.newDefaultFactory().apply {
      setProperty(XMLInputFactory.SUPPORT_DTD, false)
      setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
      setXMLResolver { _, _, _, _ -> throw XMLStreamException("External XML resources are forbidden") }
    }
    try {
      // Do not let decoder read-ahead into malformed body bytes hide an otherwise identifiable root.
      val input = object : ByteArrayInputStream(bytes) {
        override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, minOf(len, 1))
      }
      val reader = factory.createXMLStreamReader(input)
      try {
        while (reader.hasNext()) {
          if (reader.next() == XMLStreamConstants.START_ELEMENT) {
            return reader.localName == "project" && reader.namespaceURI.isNullOrEmpty()
          }
        }
      } finally {
        reader.close()
      }
    } catch (_: XMLStreamException) {
      // An incomplete root cannot override a native DOCTYPE declaration already observed by SAX.
    }
    return null
  }

  private class Handler : DefaultHandler2() {
    val activities = ArrayList<ActivityDtoV1>()
    var sawNativeProject = false
    var sawOasis = false
    var declaredNativeProject = false
    private val path = ArrayList<String>()
    private var oasisDepth = 0
    private var sawActivities = false
    private var attributes = emptyMap<String, String>()
    private var fields = linkedMapOf<String, String>()
    private val text = StringBuilder()
    private var locator: Locator? = null

    override fun setDocumentLocator(locator: Locator) { this.locator = locator }
    override fun error(e: SAXParseException): Unit = throw e
    override fun fatalError(e: SAXParseException): Unit = throw e

    override fun startDTD(name: String, publicId: String?, systemId: String?) {
      declaredNativeProject = name == "project"
      // Always stop before the subset. Failure classification can inspect the root with DTD processing disabled.
      throw SAXException("DOCTYPE is forbidden: $name")
    }

    private fun fail(message: String): Nothing = throw OasisPersistenceException(
      "/${path.joinToString("/")}: $message (line ${locator?.lineNumber}, column ${locator?.columnNumber})"
    )

    private fun checkAttributes(attrs: Attributes, allowed: Set<String>, required: Set<String> = emptySet()): Map<String, String> {
      val result = linkedMapOf<String, String>()
      for (i in 0 until attrs.length) {
        val name = attrs.getQName(i)
        if (attrs.getURI(i).isNotEmpty() || name !in allowed) fail("unknown or namespaced attribute '$name'")
        result[name] = attrs.getValue(i)
      }
      required.forEach { if (it !in result) fail("missing required attribute '$it'") }
      return result
    }

    override fun startElement(uri: String, localName: String, qName: String, attrs: Attributes) {
      path.add(qName)
      if (path.size == 1 && qName == "project" && uri.isEmpty()) sawNativeProject = true
      // Only the actual unqualified native root establishes Oasis vocabulary, never a foreign descendant.
      if (!sawNativeProject) return
      if (oasisDepth == 0) {
        if (localName != "oasis") return
        if (sawOasis) fail("duplicate oasis section")
        sawOasis = true
        if (path.size != 2 || path[0] != "project" || !sawNativeProject) fail("oasis must be a direct child of project")
        oasisDepth = path.size
      }
      if (uri.isNotEmpty() || qName != localName) fail("unsupported namespace on '$qName'")
      when (path.size - oasisDepth) {
        0 -> {
          val version = checkAttributes(attrs, setOf("schema-version"), setOf("schema-version")).getValue("schema-version")
          if (version != "1") fail("unsupported or malformed schema-version '$version'; expected exactly '1'")
        }
        1 -> {
          if (qName != "activities") fail("unknown Oasis element '$qName'")
          if (sawActivities) fail("duplicate activities container")
          sawActivities = true
          checkAttributes(attrs, emptySet())
        }
        2 -> {
          if (qName != "activity") fail("expected activity, found '$qName'")
          attributes = checkAttributes(attrs, ACTIVITY_ATTRIBUTES, REQUIRED_ATTRIBUTES)
          fields = linkedMapOf()
        }
        3 -> {
          if (qName !in TEXT_FIELDS) fail("unknown Activity field '$qName'")
          if (qName in fields) fail("duplicate field '$qName'")
          checkAttributes(attrs, emptySet())
          text.setLength(0)
        }
        else -> fail("nested markup is not allowed in a text field")
      }
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
      if (oasisDepth == 0) return
      if (path.size - oasisDepth == 3) text.append(ch, start, length)
      else if ((start until start + length).any { ch[it] !in " \t\r\n" }) fail("unexpected structural text")
    }

    override fun endElement(uri: String, localName: String, qName: String) {
      if (oasisDepth != 0) {
        when (path.size - oasisDepth) {
          3 -> fields[qName] = text.toString()
          2 -> {
            REQUIRED_FIELDS.forEach { if (it !in fields) fail("missing required field '$it'") }
            activities.add(ActivityDtoV1(
              attributes.getValue("id"), fields.getValue("task-uid"), attributes.getValue("occurred-on"),
              fields.getValue("type-code"), fields.getValue("description"), attributes.getValue("created-at"),
              attributes.getValue("modified-at"), attributes["occurred-at"], fields["person-company"],
              fields["plan-summary"], fields["source-reference"]
            ))
          }
          0 -> {
            if (!sawActivities) fail("missing activities container")
            oasisDepth = 0
          }
        }
      }
      path.removeAt(path.lastIndex)
    }
  }

  companion object {
    private val REQUIRED_ATTRIBUTES = setOf("id", "occurred-on", "created-at", "modified-at")
    private val ACTIVITY_ATTRIBUTES = REQUIRED_ATTRIBUTES + "occurred-at"
    private val REQUIRED_FIELDS = setOf("task-uid", "type-code", "description")
    private val TEXT_FIELDS = REQUIRED_FIELDS + setOf("person-company", "plan-summary", "source-reference")
  }
}
