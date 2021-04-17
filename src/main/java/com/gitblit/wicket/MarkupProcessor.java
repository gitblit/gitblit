/*
 * Copyright 2013 gitblit.com.
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
package com.gitblit.wicket;

import static org.pegdown.FastEncoder.encode;

import java.io.Serializable;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.wicket.Page;
import org.apache.wicket.RequestCycle;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.mylyn.wikitext.confluence.core.ConfluenceLanguage;
import org.eclipse.mylyn.wikitext.core.parser.Attributes;
import org.eclipse.mylyn.wikitext.core.parser.MarkupParser;
import org.eclipse.mylyn.wikitext.core.parser.builder.HtmlDocumentBuilder;
import org.eclipse.mylyn.wikitext.core.parser.markup.MarkupLanguage;
import org.eclipse.mylyn.wikitext.mediawiki.core.MediaWikiLanguage;
import org.eclipse.mylyn.wikitext.textile.core.TextileLanguage;
import org.eclipse.mylyn.wikitext.tracwiki.core.TracWikiLanguage;
import org.eclipse.mylyn.wikitext.twiki.core.TWikiLanguage;
import org.pegdown.DefaultVerbatimSerializer;
import org.pegdown.LinkRenderer;
import org.pegdown.ToHtmlSerializer;
import org.pegdown.VerbatimSerializer;
import org.pegdown.ast.ExpImageNode;
import org.pegdown.ast.ExpLinkNode;
import org.pegdown.ast.RefImageNode;
import org.pegdown.ast.WikiLinkNode;
import org.pegdown.plugins.ToHtmlSerializerPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gitblit.IStoredSettings;
import com.gitblit.Keys;
import com.gitblit.markdown.TableOfContentsGenerator;
import com.gitblit.markdown.Utils;
import com.gitblit.models.PathModel;
import com.gitblit.servlet.RawServlet;
import com.gitblit.utils.JGitUtils;
import com.gitblit.utils.MarkdownUtils;
import com.gitblit.utils.StringUtils;
import com.gitblit.utils.XssFilter;
import com.gitblit.wicket.pages.DocPage;
import com.google.common.base.Joiner;

/**
 * Processes markup content and generates html with repository-relative page and
 * image linking.
 *
 * @author James Moger
 *
 */
public class MarkupProcessor {

	public enum MarkupSyntax {
		PLAIN, MARKDOWN, TWIKI, TRACWIKI, TEXTILE, MEDIAWIKI, CONFLUENCE
	}

	private Logger logger = LoggerFactory.getLogger(getClass());

	private final IStoredSettings settings;

	private final XssFilter xssFilter;

	public static List<String> getMarkupExtensions(IStoredSettings settings) {
		List<String> list = new ArrayList<String>();
		list.addAll(settings.getStrings(Keys.web.confluenceExtensions));
		list.addAll(settings.getStrings(Keys.web.markdownExtensions));
		list.addAll(settings.getStrings(Keys.web.mediawikiExtensions));
		list.addAll(settings.getStrings(Keys.web.textileExtensions));
		list.addAll(settings.getStrings(Keys.web.tracwikiExtensions));
		list.addAll(settings.getStrings(Keys.web.twikiExtensions));
		return list;
	}

	public MarkupProcessor(IStoredSettings settings, XssFilter xssFilter) {
		this.settings = settings;
		this.xssFilter = xssFilter;
	}

	public List<String> getMarkupExtensions() {
		return getMarkupExtensions(settings);
	}

	public List<String> getAllExtensions() {
		List<String> list = getMarkupExtensions(settings);
		list.add("txt");
		list.add("TXT");
		return list;
	}

	private List<String> getRoots() {
		return settings.getStrings(Keys.web.documents);
	}

	private String [] getEncodings() {
		return settings.getStrings(Keys.web.blobEncodings).toArray(new String[0]);
	}

	private MarkupSyntax determineSyntax(String documentPath) {
		String ext = StringUtils.getFileExtension(documentPath).toLowerCase();
		if (StringUtils.isEmpty(ext)) {
			return MarkupSyntax.PLAIN;
		}

		if (settings.getStrings(Keys.web.confluenceExtensions).contains(ext)) {
			return MarkupSyntax.CONFLUENCE;
		} else if (settings.getStrings(Keys.web.markdownExtensions).contains(ext)) {
			return MarkupSyntax.MARKDOWN;
		} else if (settings.getStrings(Keys.web.mediawikiExtensions).contains(ext)) {
			return MarkupSyntax.MEDIAWIKI;
		} else if (settings.getStrings(Keys.web.textileExtensions).contains(ext)) {
			return MarkupSyntax.TEXTILE;
		} else if (settings.getStrings(Keys.web.tracwikiExtensions).contains(ext)) {
			return MarkupSyntax.TRACWIKI;
		} else if (settings.getStrings(Keys.web.twikiExtensions).contains(ext)) {
			return MarkupSyntax.TWIKI;
		}

		return MarkupSyntax.PLAIN;
	}

	public boolean hasRootDocs(Repository r) {
		List<String> roots = getRoots();
		List<String> extensions = getAllExtensions();
		List<PathModel> paths = JGitUtils.getFilesInPath(r, null, null);
		for (PathModel path : paths) {
			if (!path.isTree()) {
				String ext = StringUtils.getFileExtension(path.name).toLowerCase();
				String name = StringUtils.stripFileExtension(path.name).toLowerCase();

				if (roots.contains(name)) {
					if (StringUtils.isEmpty(ext) || extensions.contains(ext)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	public List<MarkupDocument> getRootDocs(Repository r, String repositoryName, String commitId) {
		List<String> roots = getRoots();
		List<MarkupDocument> list = getDocs(r, repositoryName, commitId, roots);
		return list;
	}

	public MarkupDocument getReadme(Repository r, String repositoryName, String commitId) {
		List<MarkupDocument> list = getDocs(r, repositoryName, commitId, Arrays.asList("readme"));
		if (list.isEmpty()) {
			return null;
		}
		return list.get(0);
	}

	private List<MarkupDocument> getDocs(Repository r, String repositoryName, String commitId, List<String> names) {
		List<String> extensions = getAllExtensions();
		String [] encodings = getEncodings();
		Map<String, MarkupDocument> map = new HashMap<String, MarkupDocument>();
		RevCommit commit = JGitUtils.getCommit(r, commitId);
		List<PathModel> paths = JGitUtils.getFilesInPath(r, null, commit);
		for (PathModel path : paths) {
			if (!path.isTree()) {
				String ext = StringUtils.getFileExtension(path.name).toLowerCase();
				String name = StringUtils.stripFileExtension(path.name).toLowerCase();

				if (names.contains(name)) {
					if (StringUtils.isEmpty(ext) || extensions.contains(ext)) {
						String markup = JGitUtils.getStringContent(r, commit.getTree(), path.name, encodings);
						MarkupDocument doc = parse(repositoryName, commitId, path.name, markup);
						map.put(name, doc);
					}
				}
			}
		}
		// return document list in requested order
		List<MarkupDocument> list = new ArrayList<MarkupDocument>();
		for (String name : names) {
			if (map.containsKey(name)) {
				list.add(map.get(name));
			}
		}
		return list;
	}

	public MarkupDocument parse(String repositoryName, String commitId, String documentPath, String markupText) {
		final MarkupSyntax syntax = determineSyntax(documentPath);
		final MarkupDocument doc = new MarkupDocument(documentPath, markupText, syntax);

		if (markupText != null) {
			try {
				switch (syntax){
				case CONFLUENCE:
					parse(doc, repositoryName, commitId, new ConfluenceLanguage());
					break;
				case MARKDOWN:
					parse(doc, repositoryName, commitId);
					break;
				case MEDIAWIKI:
					parse(doc, repositoryName, commitId, new MediaWikiLanguage());
					break;
				case TEXTILE:
					parse(doc, repositoryName, commitId, new TextileLanguage());
					break;
				case TRACWIKI:
					parse(doc, repositoryName, commitId, new TracWikiLanguage());
					break;
				case TWIKI:
					parse(doc, repositoryName, commitId, new TWikiLanguage());
					break;
				default:
					doc.html = MarkdownUtils.transformPlainText(markupText);
					break;
				}
			} catch (Exception e) {
				logger.error("failed to transform " + syntax, e);
			}
		}

		if (doc.html == null) {
			// failed to transform markup
			if (markupText == null) {
				markupText = String.format("Document <b>%1$s</b> not found in <em>%2$s</em>", documentPath, repositoryName);
			}
			markupText = MessageFormat.format("<div class=\"alert alert-error\"><strong>{0}:</strong> {1}</div>{2}", "Error", "failed to parse markup", markupText);
			doc.html = StringUtils.breakLinesForHtml(markupText);
		}

		return doc;
	}

	/**
	 * Parses the markup using the specified markup language
	 *
	 * @param doc
	 * @param repositoryName
	 * @param commitId
	 * @param lang
	 */
	private void parse(final MarkupDocument doc, final String repositoryName, final String commitId, MarkupLanguage lang) {
		StringWriter writer = new StringWriter();
		HtmlDocumentBuilder builder = new HtmlDocumentBuilder(writer) {

			@Override
			public void image(Attributes attributes, String imagePath) {
				String url;
				if (imagePath.indexOf("://") == -1) {
					// relative image
					String path = doc.getRelativePath(imagePath);
					String contextUrl = RequestCycle.get().getRequest().getRelativePathPrefixToContextRoot();
					url = RawServlet.asLink(contextUrl, repositoryName, commitId, path);
				} else {
					// absolute image
					url = imagePath;
				}
				super.image(attributes, url);
			}

			@Override
			public void link(Attributes attributes, String hrefOrHashName, String text) {
				String url;
				if (hrefOrHashName.charAt(0) != '#') {
					if (hrefOrHashName.indexOf("://") == -1) {
						// relative link
						String path = doc.getRelativePath(hrefOrHashName);
						url = getWicketUrl(DocPage.class, repositoryName, commitId, path);
					} else {
						// absolute link
						url = hrefOrHashName;
					}
				} else {
					// page-relative hash link
					url = hrefOrHashName;
				}
				super.link(attributes, url, text);
			}
		};

		// avoid the <html> and <body> tags
		builder.setEmitAsDocument(false);

		MarkupParser parser = new MarkupParser(lang);
		parser.setBuilder(builder);
		parser.parse(doc.markup);

		final String content = writer.toString();
		final String safeContent = xssFilter.relaxed(content);

		doc.html = safeContent;
	}

	/**
	 * Parses the document as Markdown using Pegdown.
	 *
	 * @param doc
	 * @param repositoryName
	 * @param commitId
	 */
	private void parse(final MarkupDocument doc, final String repositoryName, final String commitId) {
		LinkRenderer renderer = new LinkRenderer() {

			@Override
			public Rendering render(ExpImageNode node, String text) {
				if (node.url.indexOf("://") == -1) {
					// repository-relative image link
					String path = doc.getRelativePath(node.url);
					String contextUrl = RequestCycle.get().getRequest().getRelativePathPrefixToContextRoot();
					String url = RawServlet.asLink(contextUrl, repositoryName, commitId, path);
					return new Rendering(url, text);
				}
				// absolute image link
				return new Rendering(node.url, text);
			}

			@Override
			public Rendering render(RefImageNode node, String url, String title, String alt) {
				Rendering rendering;
				if (url.indexOf("://") == -1) {
					// repository-relative image link
					String path = doc.getRelativePath(url);
					String contextUrl = RequestCycle.get().getRequest().getRelativePathPrefixToContextRoot();
					String wurl = RawServlet.asLink(contextUrl, repositoryName, commitId, path);
					rendering = new Rendering(wurl, alt);
				} else {
					// absolute image link
					rendering = new Rendering(url, alt);
				}
				return StringUtils.isEmpty(title) ? rendering : rendering.withAttribute("title", encode(title));
			}

			@Override
			public Rendering render(WikiLinkNode node) {
				String path = doc.getRelativePath(node.getText());
				String name = getDocumentName(path);
				String url = getWicketUrl(DocPage.class, repositoryName, commitId, path);
				return new Rendering(url, name);
			}

			@Override
			public Rendering render(ExpLinkNode node, String text) {
				String path = doc.getRelativePath(node.url);
				String url = getWicketUrl(DocPage.class, repositoryName, commitId, path);
				return new Rendering(url, text);
			}
		};

		String markdown = new TableOfContentsGenerator(doc.markup).start();
		String content = MarkdownUtils.transformMarkdown(markdown, renderer);

		content = generateHeaderIds(content);
		
		final String safeContent = xssFilter.relaxed(content);

		doc.html = safeContent;
	}
	
	private String generateHeaderIds(String content) {
		Pattern insideHeader = Pattern.compile("<h\\d>(.*?)<\\/h\\d>");
		Matcher m = insideHeader.matcher(content);

		while (m.find()) {
			String id = Utils.normalize(m.group().replaceAll("<h\\d>", "").replaceAll("<\\/h\\d>", "")); 
			Pattern iniTag = Pattern.compile("<h\\d");
			Matcher lookTag = iniTag.matcher(m.group());
			String tag = "";
			if (lookTag.find()) {
				tag = lookTag.group();
			}
			String finalTag = m.group().replace(tag, tag + " id=\"" + id + "\"");

			content = content.replace(m.group(), finalTag);
		}
		return content;
	}

	private String getWicketUrl(Class<? extends Page> pageClass, final String repositoryName, final String commitId, final String document) {
		String fsc = settings.getString(Keys.web.forwardSlashCharacter, "/");
		String encodedPath = document.replace(' ', '-');
		try {
			encodedPath = URLEncoder.encode(encodedPath, "UTF-8");
		} catch (UnsupportedEncodingException e) {
			logger.error(null, e);
		}
		encodedPath = encodedPath.replace("/", fsc).replace("%2F", fsc);

		String url = RequestCycle.get().urlFor(pageClass, WicketUtils.newPathParameter(repositoryName, commitId, encodedPath)).toString();
		return url;
	}

	private String getDocumentName(final String document) {
		// extract document name
		String name = StringUtils.stripFileExtension(document);
		name = name.replace('_', ' ');
		if (name.indexOf('/') > -1) {
			name = name.substring(name.lastIndexOf('/') + 1);
		}
		return name;
	}

	public static class MarkupDocument implements Serializable {

		private static final long serialVersionUID = 1L;

		public final String documentPath;
		public final String markup;
		public final MarkupSyntax syntax;
		public String html;

		MarkupDocument(String documentPath, String markup, MarkupSyntax syntax) {
			this.documentPath = documentPath;
			this.markup = markup;
			this.syntax = syntax;
		}

		String getCurrentPath() {
			String basePath = "";
			if (documentPath.indexOf('/') > -1) {
				basePath = documentPath.substring(0, documentPath.lastIndexOf('/') + 1);
				if (basePath.charAt(0) == '/') {
					return basePath.substring(1);
				}
			}
			return basePath;
		}

		String getRelativePath(String ref) {
			if (ref.charAt(0) == '/') {
				// absolute path in repository
				return ref.substring(1);
			} else {
				// resolve relative repository path
				String cp = getCurrentPath();
				if (StringUtils.isEmpty(cp)) {
					return ref;
				}
				// this is a simple relative path resolver
				List<String> currPathStrings = new ArrayList<String>(Arrays.asList(cp.split("/")));
				String file = ref;
				while (file.startsWith("../")) {
					// strip ../ from the file reference
					// drop the last path element
					file = file.substring(3);
					currPathStrings.remove(currPathStrings.size() - 1);
				}
				currPathStrings.add(file);
				String path = Joiner.on("/").join(currPathStrings);
				return path;
			}
		}
	}

	/**
	 * This class implements a workaround for a bug reported in issue-379.
	 * The bug was introduced by my own pegdown pull request #115.
	 *
	 * @author James Moger
	 *
	 */
	public static class WorkaroundHtmlSerializer extends ToHtmlSerializer {

		 public WorkaroundHtmlSerializer(final LinkRenderer linkRenderer) {
			 super(linkRenderer,
					 Collections.<String, VerbatimSerializer>singletonMap(VerbatimSerializer.DEFAULT, DefaultVerbatimSerializer.INSTANCE),
					 Collections.<ToHtmlSerializerPlugin>emptyList());
		    }
	    private void printAttribute(String name, String value) {
	        printer.print(' ').print(name).print('=').print('"').print(value).print('"');
	    }

	    /* Reimplement print image tag to eliminate a trailing double-quote */
		@Override
	    protected void printImageTag(LinkRenderer.Rendering rendering) {
	        printer.print("<img");
	        printAttribute("src", rendering.href);
	        printAttribute("alt", rendering.text);
	        for (LinkRenderer.Attribute attr : rendering.attributes) {
	            printAttribute(attr.name, attr.value);
	        }
	        printer.print("/>");
	    }
	}
}
