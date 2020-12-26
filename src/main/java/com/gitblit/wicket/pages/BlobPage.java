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
package com.gitblit.wicket.pages;

import java.text.MessageFormat;
import java.util.HashMap;
import java.util.Map;

import org.apache.wicket.Component;
import org.apache.wicket.PageParameters;
import org.apache.wicket.RedirectException;
import org.apache.wicket.markup.html.basic.Label;
import org.apache.wicket.markup.html.image.Image;
import org.apache.wicket.markup.html.link.BookmarkablePageLink;
import org.apache.wicket.markup.html.link.ExternalLink;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;

import com.gitblit.Keys;
import com.gitblit.servlet.RawServlet;
import com.gitblit.utils.JGitUtils;
import com.gitblit.utils.StringUtils;
import com.gitblit.wicket.CacheControl;
import com.gitblit.wicket.CacheControl.LastModified;
import com.gitblit.wicket.ExternalImage;
import com.gitblit.wicket.MarkupProcessor;
import com.gitblit.wicket.WicketUtils;
import com.gitblit.wicket.panels.CommitHeaderPanel;
import com.gitblit.wicket.panels.PathBreadcrumbsPanel;

@CacheControl(LastModified.BOOT)
public class BlobPage extends RepositoryPage {

	protected String fileExtension;

	public BlobPage(PageParameters params) {
		super(params);

		Repository r = getRepository();
		final String blobPath = WicketUtils.getPath(params);
		String [] encodings = getEncodings();

		if (StringUtils.isEmpty(objectId) && StringUtils.isEmpty(blobPath)) {
			throw new RedirectException(TreePage.class, WicketUtils.newRepositoryParameter(repositoryName));
		}

		//add blob edit link
		BookmarkablePageLink<EditFilePage> editDocLink = new BookmarkablePageLink<>("editDocLink", EditFilePage.class, 
				WicketUtils.newPathParameter(repositoryName, objectId, blobPath));
		editDocLink.setEnabled(false);
		add(editDocLink);
		
		if (StringUtils.isEmpty(blobPath)) {
			// blob by objectid

			add(new BookmarkablePageLink<Void>("blameLink", BlamePage.class,
					WicketUtils.newPathParameter(repositoryName, objectId, blobPath))
					.setEnabled(false));
			add(new BookmarkablePageLink<Void>("historyLink", HistoryPage.class).setEnabled(false));
			String rawUrl = RawServlet.asLink(getContextUrl(), repositoryName, objectId, blobPath);
			add(new ExternalLink("rawLink",  rawUrl));
			add(new CommitHeaderPanel("commitHeader", objectId));
			add(new PathBreadcrumbsPanel("breadcrumbs", repositoryName, blobPath, objectId));
			Component c = new Label("blobText", JGitUtils.getStringContent(r, objectId, encodings));
			WicketUtils.setCssClass(c, "plainprint");
			add(c);
		} else {
			// standard blob view
			String extension = null;
			if (blobPath.lastIndexOf('.') > -1) {
				extension = blobPath.substring(blobPath.lastIndexOf('.') + 1).toLowerCase();
			}

			// see if we should redirect to the doc page
			MarkupProcessor processor = new MarkupProcessor(app().settings(), app().xssFilter());
			for (String ext : processor.getMarkupExtensions()) {
				if (ext.equals(extension)) {
					setResponsePage(DocPage.class, params);
					return;
				}
			}

			RevCommit commit = getCommit();

			// blob page links
			add(new BookmarkablePageLink<Void>("blameLink", BlamePage.class,
					WicketUtils.newPathParameter(repositoryName, objectId, blobPath)));
			add(new BookmarkablePageLink<Void>("historyLink", HistoryPage.class,
					WicketUtils.newPathParameter(repositoryName, objectId, blobPath)));
			String rawUrl = RawServlet.asLink(getContextUrl(), repositoryName, objectId, blobPath);
			add(new ExternalLink("rawLink", rawUrl));

			add(new CommitHeaderPanel("commitHeader", repositoryName, commit));

			add(new PathBreadcrumbsPanel("breadcrumbs", repositoryName, blobPath, objectId));

			// Map the extensions to types
			Map<String, Integer> map = new HashMap<String, Integer>();
			for (String ext : app().settings().getStrings(Keys.web.prettyPrintExtensions)) {
				map.put(ext.toLowerCase(), 1);
			}
			for (String ext : app().settings().getStrings(Keys.web.imageExtensions)) {
				map.put(ext.toLowerCase(), 2);
			}
			for (String ext : app().settings().getStrings(Keys.web.binaryExtensions)) {
				map.put(ext.toLowerCase(), 3);
			}

			if (extension != null) {
				int type = 0;
				if (map.containsKey(extension)) {
					type = map.get(extension);
				}
				switch (type) {
				case 2:
					// image blobs
					add(new Label("blobText").setVisible(false));
					add(new ExternalImage("blobImage", rawUrl));
					break;
				case 3:
					// binary blobs
					add(new Label("blobText", "Binary File"));
					add(new Image("blobImage").setVisible(false));
					break;
				default:
					// plain text
					String source = JGitUtils.getStringContent(r, commit.getTree(), blobPath, encodings);
					String table;
					if (source == null) {
						table = missingBlob(blobPath, commit);
					} else {
						table = generateSourceView(source, extension, type == 1);
						addBottomScriptInline("jQuery(prettyPrint);");
					}
					add(new Label("blobText", table).setEscapeModelStrings(false));
					add(new Image("blobImage").setVisible(false));
					fileExtension = extension;
					//set blob edit link enable
					editDocLink.setEnabled(true);
				}
			} else {
				// plain text
				String source = JGitUtils.getStringContent(r, commit.getTree(), blobPath, encodings);
				String table;
				if (source == null) {
					table = missingBlob(blobPath, commit);
				} else {
					table = generateSourceView(source, null, false);
					addBottomScriptInline("jQuery(prettyPrint);");
				}
				add(new Label("blobText", table).setEscapeModelStrings(false));
				add(new Image("blobImage").setVisible(false));
				//set blob edit link enable
				editDocLink.setEnabled(true);
			}
		}
	}

	protected String missingBlob(String blobPath, RevCommit commit) {
		StringBuilder sb = new StringBuilder();
		sb.append("<div class=\"alert alert-error\">");
		String pattern = getString("gb.doesNotExistInTree").replace("{0}", "<b>{0}</b>").replace("{1}", "<b>{1}</b>");
		sb.append(MessageFormat.format(pattern, blobPath, commit.getTree().getId().getName()));
		sb.append("</div>");
		return sb.toString();
	}

	protected String generateSourceView(String source, String extension, boolean prettyPrint) {
		String [] lines = source.split("\n");

		StringBuilder sb = new StringBuilder();
		sb.append("<!-- start blob table -->");
		sb.append("<table width=\"100%\"><tbody><tr>");

		// nums column
		sb.append("<!-- start nums column -->");
		sb.append("<td id=\"nums\">");
		sb.append("<pre>");
		String numPattern = "<span id=\"L{0}\" class=\"jump\"></span><a href=\"#L{0}\">{0}</a>\n";
		for (int i = 0; i < lines.length; i++) {
			sb.append(MessageFormat.format(numPattern, "" + (i + 1)));
		}
		sb.append("</pre>");
		sb.append("<!-- end nums column -->");
		sb.append("</td>");

		sb.append("<!-- start lines column -->");
		sb.append("<td id=\"lines\">");
		sb.append("<div class=\"sourceview\">");
		if (prettyPrint) {
			sb.append("<pre class=\"prettyprint lang-" + extension + "\">");
		} else {
			sb.append("<pre class=\"plainprint\">");
		}
		final int tabLength = app().settings().getInteger(Keys.web.tabLength, 4);
		lines = StringUtils.escapeForHtml(source, true, tabLength).split("\n");

		sb.append("<table width=\"100%\"><tbody>");

		String linePattern = "<tr class=\"{0}\"><td><div><span class=\"line\">{1}</span></div>\r</tr>";
		for (int i = 0; i < lines.length; i++) {
			String line = lines[i].replace('\r', ' ');
			String cssClass = (i % 2 == 0) ? "even" : "odd";
			if (StringUtils.isEmpty(line.trim())) {
				line = "&nbsp;";
			}
			sb.append(MessageFormat.format(linePattern, cssClass, line, "" + (i + 1)));
		}
		sb.append("</tbody></table></pre>");
		sb.append("</pre>");
		sb.append("</div>");
		sb.append("</td>");
		sb.append("<!-- end lines column -->");

		sb.append("</tr></tbody></table>");
		sb.append("<!-- end blob table -->");

		return sb.toString();
	}

	@Override
	protected String getPageName() {
		return getString("gb.view");
	}

	@Override
	protected boolean isCommitPage() {
		return true;
	}

	@Override
	protected Class<? extends BasePage> getRepoNavPageClass() {
		return TreePage.class;
	}
}
