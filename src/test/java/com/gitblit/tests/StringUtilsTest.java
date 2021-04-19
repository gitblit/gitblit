/*
 * Copyright 2011 gitblit.com.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.gitblit.tests;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com.gitblit.utils.StringUtils;

public class StringUtilsTest extends GitblitUnitTest {

	@Test
	public void testIsEmpty() throws Exception {
		assertTrue(StringUtils.isEmpty((String)null));
		assertTrue(StringUtils.isEmpty(""));
		assertTrue(StringUtils.isEmpty("  "));
		assertFalse(StringUtils.isEmpty("A"));
	}

	@Test
	public void testIsEmptyCharArray() throws Exception {
		assertTrue(StringUtils.isEmpty((char[])null));
		assertTrue(StringUtils.isEmpty(new char[0]));
		assertTrue(StringUtils.isEmpty(new char[]{ ' ' }));
		assertTrue(StringUtils.isEmpty(new char[]{ '	'}));
		assertTrue(StringUtils.isEmpty(new char[]{ ' ', ' ' }));
		assertTrue(StringUtils.isEmpty(new char[]{ '	',  ' ', '	' }));
		assertFalse(StringUtils.isEmpty(new char[]{ '\u0020', 'f' }));
		assertFalse(StringUtils.isEmpty(new char[]{ '\u0148', '\u0020' }));
		assertFalse(StringUtils.isEmpty(new char[]{ 'A' }));
	}

	@Test
	public void testBreakLinesForHtml() throws Exception {
		String input = "this\nis\r\na\rtest\r\n\r\nof\n\nline\r\rbreaking";
		String output = "this<br/>is<br/>a<br/>test<br/><br/>of<br/><br/>line<br/><br/>breaking";
		assertEquals(output, StringUtils.breakLinesForHtml(input));
	}

	@Test
	public void testEncodeUrl() throws Exception {
		String input = "test /";
		String output = "test%20%2F";
		assertEquals(output, StringUtils.encodeURL(input));
	}

	@Test
	public void testEscapeForHtml() throws Exception {
		String input = "& < > \"";
		String outputNoChange = "&amp; &lt; &gt; &quot;";
		String outputChange = "&amp;&nbsp;&lt;&nbsp;&gt;&nbsp;&quot;";
		assertEquals(outputNoChange, StringUtils.escapeForHtml(input, false));
		assertEquals(outputChange, StringUtils.escapeForHtml(input, true));

		String tabs = "\t";
		int tabSpaces;
		int expectedLength;
		for (int i = 0; i < 50; i++) {
			tabSpaces = 4 - i % 4;
			expectedLength = (i + tabSpaces) * 6; // &nbsp; = 6 chars
			assertEquals(expectedLength, StringUtils.escapeForHtml(tabs, true).length());
			tabs = " " + tabs;
		}
	}

	@Test
	public void testDecodeForHtml() throws Exception {
		String input = "&amp; &lt; &gt; &quot;";
		String output = "& < > \"";
		assertEquals(output, StringUtils.decodeFromHtml(input));
	}

	@Test
	public void testFlattenStrings() throws Exception {
		String[] strings = { "A", "B", "C", "D" };
		assertEquals("A B C D", StringUtils.flattenStrings(Arrays.asList(strings)));
	}

	@Test
	public void testTrim() throws Exception {
		String input = "123456789 123456789 123456789 123456789 123456789 123456789 123456789 ";
		String output = "123456789 123456789 123456789 123456789 123456789 1234567...";
		assertEquals(output, StringUtils.trimString(input, 60));
		assertEquals(input, StringUtils.trimString(input, input.length()));
	}

	@Test
	public void testPadding() throws Exception {
		String input = "test";
		assertEquals("      test", StringUtils.leftPad(input, 6 + input.length(), ' '));
		assertEquals("test      ", StringUtils.rightPad(input, 6 + input.length(), ' '));

		assertEquals(input, StringUtils.leftPad(input, input.length(), ' '));
		assertEquals(input, StringUtils.rightPad(input, input.length(), ' '));
	}

	@Test
	public void testSHA1() throws Exception {
		assertEquals("bd9dbf5aae1a3862dd1526723246b20206e5fc37",
				StringUtils.getSHA1("blob 16\000what is up, doc?"));
	}

	@Test
	public void testMD5() throws Exception {
		assertEquals("77fb8d95331f0d557472f6776d3aedf6",
				StringUtils.getMD5("blob 16\000what is up, doc?"));
	}

	@Test
	public void testRootPath() throws Exception {
		String input = "/nested/path/to/repository";
		String output = "/nested/path/to";
		assertEquals(output, StringUtils.getRootPath(input));
		assertEquals("", StringUtils.getRootPath("repository"));
	}

	@Test
	public void testStringsFromValue() throws Exception {
        List<String> strings = StringUtils.getStringsFromValue("\"A A \" B \"C C\" D \"\" \"E\"");
        assertEquals(6, strings.size());
        assertEquals("A A", strings.get(0));
        assertEquals("B", strings.get(1));
        assertEquals("C C", strings.get(2));
        assertEquals("D", strings.get(3));
        assertEquals("", strings.get(4));
        assertEquals("E", strings.get(5));

        strings = StringUtils.getStringsFromValue("\"A A \", B, \"C C\", D, \"\", \"E\"", ",");
        assertEquals(6, strings.size());
        assertEquals("A A", strings.get(0));
        assertEquals("B", strings.get(1));
        assertEquals("C C", strings.get(2));
        assertEquals("D", strings.get(3));
        assertEquals("", strings.get(4));
        assertEquals("E", strings.get(5));
    }

	@Test
	public void testStringsFromValue2() throws Exception {
		List<String> strings = StringUtils.getStringsFromValue("common/* libraries/*");
		assertEquals(2, strings.size());
		assertEquals("common/*", strings.get(0));
		assertEquals("libraries/*", strings.get(1));
	}

	@Test
	public void testFuzzyMatching() throws Exception {
		assertTrue(StringUtils.fuzzyMatch("12345", "12345"));
		assertTrue(StringUtils.fuzzyMatch("AbCdEf", "abcdef"));
		assertTrue(StringUtils.fuzzyMatch("AbCdEf", "abc*"));
		assertTrue(StringUtils.fuzzyMatch("AbCdEf", "*def"));
		assertTrue(StringUtils.fuzzyMatch("AbCdEfHIJ", "abc*hij"));

		assertFalse(StringUtils.fuzzyMatch("123", "12345"));
		assertFalse(StringUtils.fuzzyMatch("AbCdEfHIJ", "abc*hhh"));
	}

	@Test
	public void testGetRepositoryPath() throws Exception {
		assertEquals("gitblit/gitblit.git", StringUtils.extractRepositoryPath("git://github.com/gitblit/gitblit.git", new String [] { ".*?://github.com/(.*)" }));
		assertEquals("gitblit.git", StringUtils.extractRepositoryPath("git://github.com/gitblit/gitblit.git", new String [] { ".*?://github.com/[^/].*?/(.*)" }));
		assertEquals("gitblit.git", StringUtils.extractRepositoryPath("git://github.com/gitblit/gitblit.git"));
	}
}
