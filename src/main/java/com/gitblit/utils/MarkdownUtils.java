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
package com.gitblit.utils;

import static org.pegdown.Extensions.ALL;
import static org.pegdown.Extensions.ANCHORLINKS;
import static org.pegdown.Extensions.HARDWRAPS;
import static org.pegdown.Extensions.SMARTYPANTS;

import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.text.MessageFormat;

import org.apache.commons.io.IOUtils;
import org.pegdown.LinkRenderer;
import org.pegdown.ParsingTimeoutException;
import org.pegdown.PegDownProcessor;
import org.pegdown.ast.RootNode;

import com.gitblit.Constants;
import com.gitblit.IStoredSettings;
import com.gitblit.Keys;
import com.gitblit.wicket.MarkupProcessor.WorkaroundHtmlSerializer;

/**
 * Utility methods for transforming raw markdown text to html.
 *
 * @author James Moger
 *
 */
public class MarkdownUtils {

	/**
	 * Returns the html version of the plain source text.
	 *
	 * @param text
	 * @return html version of plain text
	 * @throws java.text.ParseException
	 */
	public static String transformPlainText(String text) {
		// url auto-linking
		text = text.replaceAll("((http|https)://[0-9A-Za-z-_=\\?\\.\\$#&/]*)", "<a href=\"$1\">$1</a>");
		String html = "<pre>" + text + "</pre>";
		return html;
	}


	/**
	 * Returns the html version of the markdown source text.
	 *
	 * @param markdown
	 * @return html version of markdown text
	 * @throws java.text.ParseException
	 */
	public static String transformMarkdown(String markdown) {
		return transformMarkdown(markdown, null);
	}

	/**
	 * Returns the html version of the markdown source text.
	 *
	 * @param markdown
	 * @return html version of markdown text
	 * @throws java.text.ParseException
	 */
	public static String transformMarkdown(String markdown, LinkRenderer linkRenderer) {
		try {
			PegDownProcessor pd = new PegDownProcessor(ALL & ~SMARTYPANTS & ~ANCHORLINKS & ~HARDWRAPS);
			RootNode astRoot = pd.parseMarkdown(markdown.toCharArray());
			return new WorkaroundHtmlSerializer(linkRenderer == null ? new LinkRenderer() : linkRenderer).toHtml(astRoot);
		} catch (ParsingTimeoutException e) {
			return null;
		}
	}

	/**
	 * Returns the html version of the markdown source reader. The reader is
	 * closed regardless of success or failure.
	 *
	 * @param markdownReader
	 * @return html version of the markdown text
	 * @throws java.text.ParseException
	 */
	public static String transformMarkdown(Reader markdownReader) throws IOException {
		// Read raw markdown content and transform it to html
		StringWriter writer = new StringWriter();
		try {
			IOUtils.copy(markdownReader, writer);
			String markdown = writer.toString();
			return transformMarkdown(markdown);
		} finally {
			try {
				writer.close();
			} catch (IOException e) {
				// IGNORE
			}
		}
	}


	/**
	 * Transforms GFM (Github Flavored Markdown) to html.
	 * Gitblit does not support the complete GFM specification.
	 *
	 * @param input
	 * @param repositoryName
	 * @return html
	 */
	public static String transformGFM(IStoredSettings settings, String input, String repositoryName) {
		String text = input;

		// strikethrough
		text = text.replaceAll("~~(.*)~~", "<s>$1</s>");
		text = text.replaceAll("\\{(?:-){2}(.*)(?:-){2}}", "<s>$1</s>");

		// underline
		text = text.replaceAll("\\{(?:\\+){2}(.*)(?:\\+){2}}", "<u>$1</u>");

		// strikethrough, replacement
		text = text.replaceAll("\\{~~(.*)~>(.*)~~}", "<s>$1</s><u>$2</u>");

		// highlight
		text = text.replaceAll("\\{==(.*)==}", "<span class='highlight'>$1</span>");

		String canonicalUrl = settings.getString(Keys.web.canonicalUrl, "https://localhost:8443");

		// emphasize and link mentions
		String mentionReplacement = String.format("**[@${user}](%1s/user/${user})**", canonicalUrl);
		text = text.replaceAll(Constants.REGEX_TICKET_MENTION, mentionReplacement);

		// link ticket refs
		String ticketReplacement = MessageFormat.format("$1[#$2]({0}/tickets?r={1}&h=$2)$3", canonicalUrl, repositoryName);
		text = text.replaceAll("([\\s,]+)#(\\d+)([\\s,:\\.\\n])", ticketReplacement);

		// link commit shas
		int shaLen = settings.getInteger(Keys.web.shortCommitIdLength, 6);
		String commitPattern = MessageFormat.format("\\s([A-Fa-f0-9]'{'{0}'}')([A-Fa-f0-9]'{'{1}'}')", shaLen, 40 - shaLen);
		String commitReplacement = String.format(" [`$1`](%1$s/commit?r=%2$s&h=$1$2)", canonicalUrl, repositoryName);
		text = text.replaceAll(commitPattern, commitReplacement);

		String html = transformMarkdown(text);
		return html;
	}
}
