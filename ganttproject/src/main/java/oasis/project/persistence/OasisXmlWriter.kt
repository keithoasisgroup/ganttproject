/*
 * Copyright 2026 Oasis Project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package oasis.project.persistence

import oasis.project.model.OasisProjectState
import org.xml.sax.SAXException
import org.xml.sax.helpers.AttributesImpl
import javax.xml.transform.sax.TransformerHandler

class OasisXmlWriter {
  /** Capture and validate before the native saver emits any bytes. */
  @Throws(OasisPersistenceException::class)
  fun prepare(state: OasisProjectState): OasisSnapshotV1 = OasisSnapshotV1.fromState(state).also { snapshot ->
    snapshot.activities.forEachIndexed { index, activity ->
      activity.textFields().forEach { (field, value) ->
        value?.let { validateOasisXmlText(it, "/project/oasis/activities/activity[${index + 1}]/$field") }
      }
    }
  }

  @Throws(SAXException::class)
  fun write(snapshot: OasisSnapshotV1, handler: TransformerHandler) {
    fun start(name: String, attributes: Map<String, String?> = emptyMap()) {
      val attrs = AttributesImpl()
      attributes.forEach { (key, value) -> if (value != null) attrs.addAttribute("", key, key, "CDATA", value) }
      handler.startElement("", name, name, attrs)
    }
    fun end(name: String) = handler.endElement("", name, name)
    start("oasis", mapOf("schema-version" to "1"))
    start("activities")
    snapshot.activities.forEach { activity ->
      start("activity", activity.attributes())
      activity.textFields().forEach { (name, value) ->
        if (value != null) {
          start(name)
          // Ordinary characters let the XML serializer escape metacharacters and CR as character references.
          handler.characters(value.toCharArray(), 0, value.length)
          end(name)
        }
      }
      end("activity")
    }
    end("activities")
    end("oasis")
  }

}
