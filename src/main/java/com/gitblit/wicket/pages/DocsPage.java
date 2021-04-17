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

import java.util.List;

import org.apache.wicket.Component;
import org.apache.wicket.PageParameters;
import org.apache.wicket.behavior.SimpleAttributeModifier;
import org.apache.wicket.markup.html.basic.Label;
import org.apache.wicket.markup.html.link.BookmarkablePageLink;
import org.apache.wicket.markup.html.link.ExternalLink;
import org.apache.wicket.markup.html.panel.Fragment;
import org.apache.wicket.markup.repeater.Item;
import org.apache.wicket.markup.repeater.data.DataView;
import org.apache.wicket.markup.repeater.data.ListDataProvider;
import org.apache.wicket.model.StringResourceModel;
import org.eclipse.jgit.diff.DiffEntry.ChangeType;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;

import com.gitblit.models.PathModel;
import com.gitblit.models.UserModel;
import com.gitblit.servlet.RawServlet;
import com.gitblit.utils.ByteFormat;
import com.gitblit.utils.JGitUtils;
import com.gitblit.utils.StringUtils;
import com.gitblit.wicket.CacheControl;
import com.gitblit.wicket.GitBlitWebSession;
import com.gitblit.wicket.CacheControl.LastModified;
import com.gitblit.wicket.MarkupProcessor;
import com.gitblit.wicket.MarkupProcessor.MarkupDocument;
import com.gitblit.wicket.MarkupProcessor.MarkupSyntax;
import com.gitblit.wicket.WicketUtils;
import com.gitblit.wicket.panels.LinkPanel;

@CacheControl(LastModified.REPOSITORY)
public class DocsPage extends RepositoryPage {

	public DocsPage(PageParameters params) {
		super(params);

		String objectId = WicketUtils.getObject(params);

		MarkupProcessor processor = new MarkupProcessor(app().settings(), app().xssFilter());

		Repository r = getRepository();
		UserModel currentUser = (GitBlitWebSession.get().getUser() != null) ? GitBlitWebSession.get().getUser() : UserModel.ANONYMOUS;
		final boolean userCanEdit = currentUser.canEdit(getRepositoryModel());
		
		RevCommit head = JGitUtils.getCommit(r, objectId);
		if (head == null) {
			setResponsePage(NoDocsPage.class, params);
			return;
		}
		final String commitId = getBestCommitId(head);

		List<String> extensions = processor.getAllExtensions();
		List<PathModel> paths = JGitUtils.getDocuments(r, extensions, objectId);

		List<MarkupDocument> roots = processor.getRootDocs(r, repositoryName, commitId);
		Fragment fragment = null;
		if (roots.isEmpty()) {
			// no identified root documents
			fragment = new Fragment("docs", "noIndexFragment", this);
			setResponsePage(NoDocsPage.class, params);
		} else {
			// root documents, use tabbed ui of index/root and document list
			fragment = new Fragment("docs", "tabsFragment", this);
			ListDataProvider<MarkupDocument> docDp = new ListDataProvider<MarkupDocument>(roots);

			// tab titles
			DataView<MarkupDocument> tabTitles = new DataView<MarkupDocument>("tabTitle", docDp) {
				private static final long serialVersionUID = 1L;
				int counter;

				@Override
				public void populateItem(final Item<MarkupDocument> item) {
					MarkupDocument doc = item.getModelObject();
					String file = StringUtils.getLastPathElement(doc.documentPath);
					file = StringUtils.stripFileExtension(file);
					String name = file.replace('_', ' ').replace('-',  ' ');

					ExternalLink link = new ExternalLink("link", "#" + file);
					link.add(new Label("label", name.toUpperCase()).setRenderBodyOnly(true));
					item.add(link);
					if (counter == 0) {
						counter++;
						item.add(new SimpleAttributeModifier("class", "active"));
					}
				}
			};
			fragment.add(tabTitles);

			// tab content
			DataView<MarkupDocument> tabsView = new DataView<MarkupDocument>("tabContent", docDp) {
				private static final long serialVersionUID = 1L;
				int counter;

				@Override
				public void populateItem(final Item<MarkupDocument> item) {
					MarkupDocument doc = item.getModelObject();
					
					item.add(new BookmarkablePageLink<Void>("editLink", EditFilePage.class,
							WicketUtils.newPathParameter(repositoryName, commitId, doc.documentPath))
							.setEnabled(userCanEdit));
					
					// document page links					
					item.add(new BookmarkablePageLink<Void>("blameLink", BlamePage.class,
							WicketUtils.newPathParameter(repositoryName, commitId, doc.documentPath)));
					item.add(new BookmarkablePageLink<Void>("historyLink", HistoryPage.class,
							WicketUtils.newPathParameter(repositoryName, commitId, doc.documentPath)));
					String rawUrl = RawServlet.asLink(getContextUrl(), repositoryName, commitId, doc.documentPath);
					item.add(new ExternalLink("rawLink", rawUrl));

					// document content
					String file = StringUtils.getLastPathElement(doc.documentPath);
					file = StringUtils.stripFileExtension(file);
					Component content = new Label("content", doc.html)
						.setEscapeModelStrings(false);
					if (!MarkupSyntax.PLAIN.equals(doc.syntax)) {
						content.add(new SimpleAttributeModifier("class", "markdown"));
					}
					item.add(content);
					item.add(new SimpleAttributeModifier("id", file));
					if (counter == 0) {
						counter++;
						item.add(new SimpleAttributeModifier("class", "tab-pane active"));
					}
				}
			};
			fragment.add(tabsView);
		}

		// document list
		final ByteFormat byteFormat = new ByteFormat();
		Fragment docs = new Fragment("documents", "documentsFragment", this);
		ListDataProvider<PathModel> pathsDp = new ListDataProvider<PathModel>(paths);
		DataView<PathModel> pathsView = new DataView<PathModel>("document", pathsDp) {
			private static final long serialVersionUID = 1L;
			int counter;

			@Override
			public void populateItem(final Item<PathModel> item) {
				PathModel entry = item.getModelObject();
				item.add(WicketUtils.newImage("docIcon", "file_world_16x16.png"));
				item.add(new Label("docSize", byteFormat.format(entry.size)));
				item.add(new LinkPanel("docName", "list", StringUtils.stripFileExtension(entry.name),
						DocPage.class, WicketUtils.newPathParameter(repositoryName, commitId, entry.path)));

				// links
				item.add(new BookmarkablePageLink<Void>("view", DocPage.class, WicketUtils
						.newPathParameter(repositoryName, commitId, entry.path)));
				item.add(new BookmarkablePageLink<Void>("edit", EditFilePage.class, WicketUtils
						.newPathParameter(repositoryName, commitId, entry.path))
						.setEnabled(userCanEdit));
				String rawUrl = RawServlet.asLink(getContextUrl(), repositoryName, commitId, entry.path);
				item.add(new ExternalLink("raw", rawUrl));
				item.add(new BookmarkablePageLink<Void>("blame", BlamePage.class, WicketUtils
						.newPathParameter(repositoryName, commitId, entry.path)));
				item.add(new BookmarkablePageLink<Void>("history", HistoryPage.class, WicketUtils
						.newPathParameter(repositoryName, commitId, entry.path)));
				WicketUtils.setAlternatingBackground(item, counter);
				counter++;
			}
		};
		docs.add(pathsView);
		fragment.add(docs);
		add(fragment);
	}

	@Override
	protected String getPageName() {
		return getString("gb.docs");
	}

	@Override
	protected boolean isCommitPage() {
		return true;
	}

}
