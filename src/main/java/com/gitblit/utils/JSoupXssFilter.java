/*
 * Copyright 2014 gitblit.com.
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

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Whitelist;

import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Implementation of an XSS filter based on JSoup.
 *
 * @author James Moger
 *
 */
@Singleton
public class JSoupXssFilter implements XssFilter {

	 private final Cleaner none;

	 private final Cleaner relaxed;

	 @Inject
	 public JSoupXssFilter() {
		 none = new Cleaner(Whitelist.none());
		 relaxed = new Cleaner(getRelaxedWhiteList());
	}

	@Override
	public String none(String input) {
		return clean(input, none);
	}

	@Override
	public String relaxed(String input) {
		return clean(input, relaxed);
	}

	protected String clean(String input, Cleaner cleaner) {
		Document unsafe = Jsoup.parse(input);
		Document safe = cleaner.clean(unsafe);
		return safe.body().html();
	}

	/**
	 * Builds & returns a loose HTML whitelist similar to Github.
	 *
	 * https://github.com/github/markup/tree/master#html-sanitization
	 * @return a loose HTML whitelist
	 */
	protected Whitelist getRelaxedWhiteList() {
		return new Whitelist()
        .addTags(
                "a", "b", "blockquote", "br", "caption", "cite", "code", "col",
                "colgroup", "dd", "del", "div", "dl", "dt", "em", "h1", "h2", "h3", "h4", "h5", "h6", "hr",
                "i", "img", "ins", "kbd", "li", "ol", "p", "pre", "q", "samp", "small", "strike", "strong",
                "sub", "sup", "table", "tbody", "td", "tfoot", "th", "thead", "tr", "tt", "u",
                "ul", "var")

        .addAttributes("a", "class", "href", "style", "target", "title")
        .addAttributes("blockquote", "cite")
        .addAttributes("col", "span", "width")
        .addAttributes("colgroup", "span", "width")
        .addAttributes("div", "class", "style")
        .addAttributes("img", "align", "alt", "height", "src", "title", "width")
        .addAttributes("ol", "start", "type")
        .addAttributes("q", "cite")
        .addAttributes(":all", "id")
        .addAttributes("span", "class", "style")
        .addAttributes("table", "class", "style", "summary", "width")
        .addAttributes("td", "abbr", "axis", "class", "colspan", "rowspan", "style", "width")
        .addAttributes("th", "abbr", "axis", "class", "colspan", "rowspan", "scope", "style", "width")
        .addAttributes("ul", "type")

        .addEnforcedAttribute("a", "rel", "nofollow")
        ;
	}

}
