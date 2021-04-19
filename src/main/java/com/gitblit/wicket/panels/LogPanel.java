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
package com.gitblit.wicket.panels;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.apache.wicket.MarkupContainer;
import org.apache.wicket.behavior.SimpleAttributeModifier;
import org.apache.wicket.markup.html.WebMarkupContainer;
import org.apache.wicket.markup.html.basic.Label;
import org.apache.wicket.markup.html.link.BookmarkablePageLink;
import org.apache.wicket.markup.repeater.Item;
import org.apache.wicket.markup.repeater.data.DataView;
import org.apache.wicket.markup.repeater.data.ListDataProvider;
import org.apache.wicket.model.StringResourceModel;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;

import com.gitblit.Constants;
import com.gitblit.Keys;
import com.gitblit.models.RefModel;
import com.gitblit.models.RepositoryCommit;
import com.gitblit.servlet.BranchGraphServlet;
import com.gitblit.utils.ArrayUtils;
import com.gitblit.utils.JGitUtils;
import com.gitblit.utils.StringUtils;
import com.gitblit.wicket.ExternalImage;
import com.gitblit.wicket.WicketUtils;
import com.gitblit.wicket.pages.CommitDiffPage;
import com.gitblit.wicket.pages.CommitPage;
import com.gitblit.wicket.pages.GitSearchPage;
import com.gitblit.wicket.pages.LogPage;
import com.gitblit.wicket.pages.TreePage;

public class LogPanel extends BasePanel {

	private static final long serialVersionUID = 1L;

	private final boolean hasMore;

	public LogPanel(String wicketId, final String repositoryName, final String objectId,
			Repository r, int limit, int pageOffset, boolean showRemoteRefs) {
		super(wicketId);
		boolean pageResults = limit <= 0;
		int itemsPerPage = app().settings().getInteger(Keys.web.itemsPerPage, 50);
		if (itemsPerPage <= 1) {
			itemsPerPage = 50;
		}

		Map<ObjectId, List<RefModel>> allRefs = JGitUtils.getAllRefs(r, showRemoteRefs);
		List<RevCommit> commits;
		if (pageResults) {
			// Paging result set
			commits = JGitUtils.getRevLog(r, objectId, pageOffset * itemsPerPage, itemsPerPage);
		} else {
			// Fixed size result set
			commits = JGitUtils.getRevLog(r, objectId, 0, limit);
		}

		// inaccurate way to determine if there are more commits.
		// works unless commits.size() represents the exact end.
		hasMore = commits.size() >= itemsPerPage;

		String baseUrl = WicketUtils.getGitblitURL(getRequest());
		boolean showGraph = app().settings().getBoolean(Keys.web.showBranchGraph, true);

		MarkupContainer graph = new WebMarkupContainer("graph");
		add(graph);
		if (!showGraph || commits.isEmpty()) {
			// not showing or nothing to show
			graph.setVisible(false);
		} else {
			// set the rowspan on the graph row and +1 for the graph row itself
			graph.add(new SimpleAttributeModifier("rowspan", "" + (commits.size() + 1)));
			graph.add(new ExternalImage("image", BranchGraphServlet.asLink(baseUrl, repositoryName, commits.get(0).name(), commits.size())));
		}

		// header
		if (pageResults) {
			// shortlog page
			add(new Label("header", objectId));
		} else {
			// summary page
			// show shortlog page link
			add(new LinkPanel("header", "title", objectId, LogPage.class,
					WicketUtils.newRepositoryParameter(repositoryName)));
		}

		final int hashLen = app().settings().getInteger(Keys.web.shortCommitIdLength, 6);
		List<RepositoryCommit> repoCommits = new ArrayList<>(commits.size());
		for (RevCommit c : commits) {
			RepositoryCommit repoCommit = new RepositoryCommit(repositoryName, "", c);
			if (allRefs.containsKey(c)) {
				repoCommit.setRefs(allRefs.get(c));
			}
			repoCommits.add(repoCommit);
		}
		ListDataProvider<RepositoryCommit> dp = new ListDataProvider<RepositoryCommit>(repoCommits);
		DataView<RepositoryCommit> logView = new DataView<RepositoryCommit>("commit", dp) {
			private static final long serialVersionUID = 1L;
			int counter;

			@Override
			public void populateItem(final Item<RepositoryCommit> item) {
				final RepositoryCommit entry = item.getModelObject();
				final Date date = entry.getAuthorIdent().getWhen();
				final boolean isMerge = entry.getParentCount() > 1;

				item.add(WicketUtils.createDateLabel("commitDate", date, getTimeZone(), getTimeUtils()));

				// author search link
				String author = entry.getAuthorIdent().getName();
				LinkPanel authorLink = new LinkPanel("commitAuthor", "list", author,
						GitSearchPage.class, WicketUtils.newSearchParameter(repositoryName,
								null, author, Constants.SearchType.AUTHOR));
				setPersonSearchTooltip(authorLink, author, Constants.SearchType.AUTHOR);
				item.add(authorLink);

				// merge icon
				if (isMerge) {
					item.add(WicketUtils.newImage("commitIcon", "commit_merge_16x16.png"));
				} else {
					item.add(WicketUtils.newBlankImage("commitIcon"));
				}

				// short message
				String shortMessage = entry.getShortMessage();
				String trimmedMessage = shortMessage;
				if (!ArrayUtils.isEmpty(entry.getRefs())) {
					trimmedMessage = StringUtils.trimString(shortMessage, Constants.LEN_SHORTLOG_REFS);
				} else {
					trimmedMessage = StringUtils.trimString(shortMessage, Constants.LEN_SHORTLOG);
				}
				LinkPanel shortlog = new LinkPanel("commitShortMessage", "list subject" + (isMerge ? " merge" : ""),
						trimmedMessage, CommitPage.class, WicketUtils.newObjectParameter(
								repositoryName, entry.getName()));
				if (!shortMessage.equals(trimmedMessage)) {
					WicketUtils.setHtmlTooltip(shortlog, shortMessage);
				}
				item.add(shortlog);

				item.add(new RefsPanel("commitRefs", repositoryName, entry.getRefs()));

				// commit hash link
				LinkPanel commitHash = new LinkPanel("hashLink", null, entry.getName().substring(0, hashLen),
						CommitPage.class, WicketUtils.newObjectParameter(
								repositoryName, entry.getName()));
				WicketUtils.setCssClass(commitHash, "shortsha1");
				WicketUtils.setHtmlTooltip(commitHash, entry.getName());
				item.add(commitHash);

				item.add(new BookmarkablePageLink<Void>("diff", CommitDiffPage.class, WicketUtils
						.newObjectParameter(repositoryName, entry.getName())).setEnabled(entry
						.getParentCount() > 0));
				item.add(new BookmarkablePageLink<Void>("tree", TreePage.class, WicketUtils
						.newObjectParameter(repositoryName, entry.getName())));

				String clazz = counter % 2 == 0 ? "light commit" : "dark commit";
				WicketUtils.setCssClass(item, clazz);

				counter++;
			}
		};
		add(logView);

		// determine to show pager, more, or neither
		if (limit <= 0) {
			// no display limit
			add(new Label("moreLogs", "").setVisible(false));
		} else {
			if (pageResults) {
				// paging
				add(new Label("moreLogs", "").setVisible(false));
			} else {
				// more
				if (commits.size() == limit) {
					// show more
					add(new LinkPanel("moreLogs", "link", new StringResourceModel("gb.moreLogs",
							this, null), LogPage.class,
							WicketUtils.newRepositoryParameter(repositoryName)));
				} else {
					// no more
					add(new Label("moreLogs", "").setVisible(false));
				}
			}
		}
	}

	public boolean hasMore() {
		return hasMore;
	}
}
