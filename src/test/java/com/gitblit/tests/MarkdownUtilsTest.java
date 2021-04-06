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

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com.gitblit.IStoredSettings;
import com.gitblit.Keys;
import com.gitblit.tests.mock.MemorySettings;
import com.gitblit.utils.MarkdownUtils;

public class MarkdownUtilsTest extends GitblitUnitTest {

	@Test
	public void testMarkdown() throws Exception {
		assertEquals("<h1>H1</h1>", MarkdownUtils.transformMarkdown("# H1"));
		assertEquals("<h2>H2</h2>", MarkdownUtils.transformMarkdown("## H2"));
		assertEquals("<p><strong>THIS</strong> is a test</p>",
				MarkdownUtils.transformMarkdown("**THIS** is a test"));
		assertEquals("<p>** THIS ** is a test</p>",
				MarkdownUtils.transformMarkdown("** THIS ** is a test"));
		assertEquals("<p>**THIS ** is a test</p>",
				MarkdownUtils.transformMarkdown("**THIS ** is a test"));
		assertEquals("<p>** THIS** is a test</p>",
				MarkdownUtils.transformMarkdown("** THIS** is a test"));
		assertEquals("<p>This is also a test</p>",
				MarkdownUtils.transformMarkdown("This is also\na test"));

		assertEquals("<table><tr><td>test</td></tr></table>",
				MarkdownUtils.transformMarkdown("<table><tr><td>test</td></tr></table>"));
		assertEquals("<table><tr><td>&lt;test&gt;</td></tr></table>",
				MarkdownUtils.transformMarkdown("<table><tr><td>&lt;test&gt;</td></tr></table>"));
	}


	@Test
	public void testUserMentions() {
		IStoredSettings settings = getSettings();
		String repositoryName = "test3";
		String mentionHtml = "<strong><a href=\"http://localhost/user/%1$s\">@%1$s</a></strong>";

		String input = "@j.doe";
		String output = "<p>" + String.format(mentionHtml, "j.doe") + "</p>";
		assertEquals(output, MarkdownUtils.transformGFM(settings, input, repositoryName));

		input = " @j.doe";
		output = "<p>" + String.format(mentionHtml, "j.doe") + "</p>";
		assertEquals(output, MarkdownUtils.transformGFM(settings, input, repositoryName));

		input = "@j.doe.";
		output = "<p>" + String.format(mentionHtml, "j.doe") + ".</p>";
		assertEquals(output, MarkdownUtils.transformGFM(settings, input, repositoryName));

		input = "To @j.doe: ask @jim.beam!";
		output = "<p>To " + String.format(mentionHtml, "j.doe")
				+ ": ask " + String.format(mentionHtml, "jim.beam") + "!</p>";
		assertEquals(output, MarkdownUtils.transformGFM(settings, input, repositoryName));

		input =   "@sta.rt\n"
				+ "\n"
				+ "User mentions in tickets are broken.\n"
				+ "So:\n"
				+ "@mc_guyver can fix this.\n"
				+ "@j.doe, can you test after the fix by @m+guyver?\n"
				+ "Please review this, @jim.beam!\n"
				+ "Was reported by @jill and @j!doe from jane@doe yesterday.\n"
				+ "\n"
				+ "@jack.daniels can vote for john@wayne.name hopefully.\n"
				+ "@en.de";
		output =  "<p>"	+ String.format(mentionHtml, "sta.rt") + "</p>"
				+ "<p>"	+ "User mentions in tickets are broken.<br/>"
				+ "So:<br/>"
				+ String.format(mentionHtml, "mc_guyver") + " can fix this.<br/>"
				+ String.format(mentionHtml, "j.doe") + ", can you test after the fix by " + String.format(mentionHtml, "m+guyver") + "?<br/>"
				+ "Please review this, " + String.format(mentionHtml, "jim.beam") + "!<br/>"
				+ "Was reported by " + String.format(mentionHtml, "jill")
				+ " and " + String.format(mentionHtml, "j!doe")
				+ " from <a href=\"mailto:&#106;a&#110;&#x65;&#x40;&#x64;&#x6f;&#101;\">&#106;a&#110;&#x65;&#x40;&#x64;&#x6f;&#101;</a> yesterday." 
				+ "</p>"
				+ "<p>" + String.format(mentionHtml, "jack.daniels") + " can vote for "
				+ "<a href=\"mailto:&#x6a;&#x6f;h&#110;&#x40;&#119;a&#121;&#110;&#101;.&#110;a&#x6d;&#101;\">&#x6a;&#x6f;h&#110;&#x40;&#119;a&#121;&#110;&#101;.&#110;a&#x6d;&#101;</a> hopefully.<br/>"
				+ String.format(mentionHtml, "en.de")
				+ "</p>";
		assertEquals(output, MarkdownUtils.transformGFM(settings, input, repositoryName));

	}




	private MemorySettings getSettings() {
		Map<String, Object> backingMap = new HashMap<String, Object>();

		backingMap.put(Keys.web.canonicalUrl, "http://localhost");
		backingMap.put(Keys.web.shortCommitIdLength, "7");

		MemorySettings ms = new MemorySettings(backingMap);
		return ms;
	}
}
