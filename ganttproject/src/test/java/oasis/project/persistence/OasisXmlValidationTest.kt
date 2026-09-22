/*
 * Copyright 2026 Oasis Project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package oasis.project.persistence

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.io.InputStream

class OasisXmlValidationTest {
  private fun read(xml: String) = OasisXmlReader().read(xml.byteInputStream())

  @TestFactory
  fun `schema version is exactly one`() = listOf("", " ", "+1", "-1", "1.0", "01", " 1", "1 ", "0", "2", "future", "99999999999999999999999")
    .map { version -> dynamicTest("version '$version'") {
      val ex = assertThrows<OasisPersistenceException> { read(projectXml().replace("schema-version=\"1\"", "schema-version=\"$version\"")) }
      assertTrue(ex.message!!.contains("schema-version"))
    } }

  @TestFactory
  fun `reject incompatible structure`() = linkedMapOf(
    "missing version" to projectXml().replace(" schema-version=\"1\"", ""),
    "duplicate oasis" to projectXml().replace("</project>", "<oasis schema-version=\"1\"><activities/></oasis></project>"),
    "missing activities" to "<project><oasis schema-version=\"1\"/></project>",
    "duplicate activities" to projectXml().replace("</oasis>", "<activities/></oasis>"),
    "unknown oasis child" to projectXml().replace("<activities>", "<unknown/><activities>"),
    "unknown collection child" to projectXml("<unknown/>"),
    "unknown field" to projectXml(ACTIVITY_XML.replace("</activity>", "<unknown/></activity>")),
    "unknown oasis attribute" to projectXml().replace("schema-version=", "extra=\"x\" schema-version="),
    "unknown collection attribute" to projectXml().replace("<activities>", "<activities extra=\"x\">"),
    "unknown activity attribute" to projectXml(ACTIVITY_XML.replace("<activity ", "<activity extra=\"x\" ")),
    "unknown field attribute" to projectXml(ACTIVITY_XML.replace("<description>", "<description extra=\"x\">")),
    "duplicate field" to projectXml(ACTIVITY_XML.replace("</activity>", "<description>again</description></activity>")),
    "nested text markup" to projectXml(ACTIVITY_XML.replace(">text<", ">text<b/>more<")),
    "structural text" to projectXml().replace("<activities>", "<activities>wrong"),
    "non XML whitespace" to projectXml().replace("<activities>", "<activities>\u00a0"),
    "namespaced oasis" to projectXml().replace("<oasis ", "<oasis xmlns=\"urn:unexpected\" "),
    "namespaced field" to projectXml().replace("<description>", "<description xmlns=\"urn:unexpected\">"),
    "namespaced attribute" to projectXml().replace("schema-version=", "xmlns:x=\"urn:x\" x:extra=\"1\" schema-version="),
    "misplaced oasis" to projectXml().replace("<project>", "<project><tasks>").replace("</project>", "</tasks></project>"),
    "duplicate id" to projectXml(ACTIVITY_XML + ACTIVITY_XML),
    "malformed XML" to projectXml().dropLast(2)
  ).map { (name, xml) -> dynamicTest(name) { assertThrows<OasisPersistenceException> { read(xml) } } }

  @TestFactory
  fun `required fields and domain constraints`() = buildList<Pair<String, String>> {
    listOf("id", "occurred-on", "created-at", "modified-at").forEach { attr ->
      add("missing $attr" to ACTIVITY_XML.replace(Regex(" $attr=\"[^\"]*\""), ""))
    }
    listOf("task-uid", "type-code", "description").forEach { field ->
      add("missing $field" to ACTIVITY_XML.replace(Regex("<$field>.*?</$field>"), ""))
      add("blank $field" to ACTIVITY_XML.replace(Regex("<$field>.*?</$field>"), "<$field> \t\n</$field>"))
    }
    listOf("person-company", "plan-summary", "source-reference").forEach { field ->
      add("blank $field" to ACTIVITY_XML.replace("</activity>", "<$field> </$field></activity>"))
    }
    listOf("1-1-1-1-1", "invalid", "00000000-0000-0000-0000-00000000000g").forEach {
      add("UUID $it" to ACTIVITY_XML.replace("00000000-0000-0000-0000-000000000001", it))
    }
    listOf("2023-02-29", "2020-13-01", " 2020-02-29", "2020-2-29").forEach {
      add("date $it" to ACTIVITY_XML.replace("2020-02-29", it))
    }
    listOf("garbage", "2026-09-19T24:00:00Z", "2026-09-19T23:59:60Z", "2026-09-19T10:11:12.1234567890Z", "2026-02-30T10:11:12Z").forEach {
      add("instant $it" to ACTIVITY_XML.replace("2026-09-19T10:11:12.123456789Z", it))
    }
    listOf("", "24:00", "09:30:60", "09:30:00.", "09:30:00.1234567890", "09:30Z").forEach {
      add("time $it" to ACTIVITY_XML.replace("<activity ", "<activity occurred-at=\"$it\" "))
    }
  }.map { (name, activity) -> dynamicTest(name) { assertThrows<OasisPersistenceException> { read(projectXml(activity)) } } }

  @Test
  fun `DTD is forbidden and never resolved`() {
    assertThrows<OasisPersistenceException> { read("<!DOCTYPE project [<!ENTITY text 'value'>]>" + projectXml()) }
    assertThrows<OasisPersistenceException> { read("<!DOCTYPE project SYSTEM 'file:///must-not-be-read.dtd'>" + projectXml()) }
    val foreign = assertThrows<IOException> { read("<!DOCTYPE foreign><foreign/>") }
    assertFalse(OasisPersistenceException.causedByOasis(foreign))
  }

  @Test
  fun `ordinary text CDATA comments and split callbacks accumulate without trimming`() {
    val xml = projectXml(ACTIVITY_XML.replace(">text<", "> a&amp;<![CDATA[<b>]]><!--ignored--><?ignored value?>&#13;\nend <"))
    val bytes = xml.toByteArray()
    var index = 0
    val chunked = object : InputStream() {
      override fun read(): Int = if (index == bytes.size) -1 else bytes[index++].toInt() and 255
      override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val next = read()
        if (next == -1) return -1
        b[off] = next.toByte()
        return 1
      }
    }
    assertEquals(" a&<b>\r\nend ", OasisXmlReader().read(chunked).activities.values.single().description)
  }

  @Test
  fun `native unknown structures stay permissive and legacy means empty`() {
    assertTrue(read("<project extra=\"legacy\"><unrelated><anything/></unrelated></project>").activities.isEmpty())
    assertEquals(1, read(projectXml().replace("<project>", "<project extra=\"legacy\"><unrelated/> ")).activities.size)
  }
}
