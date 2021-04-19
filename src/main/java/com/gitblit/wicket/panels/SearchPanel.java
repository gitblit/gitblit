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

import org.apache.wicket.markup.html.basic.Label;
import org.apache.wicket.markup.html.link.BookmarkablePageLink;
import org.apache.wicket.markup.repeater.Item;
import org.apache.wicket.markup.repeater.data.DataView;
import org.apache.wicket.markup.repeater.data.ListDataProvider;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;

import com.gitblit.Constants;
import com.gitblit.Keys;
import com.gitblit.models.RefModel;
import com.gitblit.models.RepositoryCommit;
import com.gitblit.utils.ArrayUtils;
import com.gitblit.utils.JGitUtils;
import com.gitblit.utils.StringUtils;
import com.gitblit.wicket.WicketUtils;
import com.gitblit.wicket.pages.CommitDiffPage;
import com.gitblit.wicket.pages.CommitPage;
import com.gitblit.wicket.pages.GitSearchPage;
import com.gitblit.wicket.pages.TreePage;

public class SearchPanel extends BasePanel {

	private static final long serialVersionUID = 1L;

	private final boolean hasMore;

	public SearchPanel(String wicketId, final String repositoryName, final String objectId,
			final String value, Constants.SearchType searchType, Repository r, int limit, int pageOffset,
			boolean showRemoteRefs) {
		super(wicketId);
		boolean pageResults = limit <= 0;
		int itemsPerPage = app().settings().getInteger(Keys.web.itemsPerPage, 50);
		if (itemsPerPage <= 1) {
			itemsPerPage = 50;
		}

		RevCommit commit = JGitUtils.getCommit(r, objectId);

		Map<ObjectId, List<RefModel>> allRefs = JGitUtils.getAllRefs(r, showRemoteRefs);
		List<RevCommit> commits;
		if (pageResults) {
			// Paging result set
			commits = JGitUtils.searchRevlogs(r, objectId, value, searchType, pageOffset
					* itemsPerPage, itemsPerPage);
		} else {
			// Fixed size result set
			commits = JGitUtils.searchRevlogs(r, objectId, value, searchType, 0, limit);
		}

		// inaccurate way to determine if there are more commits.
		// works unless commits.size() represents the exact end.
		hasMore = commits.size() >= itemsPerPage;

		// header
		add(new CommitHeaderPanel("commitHeader", repositoryName, commit));

		add(new Label("searchString", value));
		add(new Label("searchType", searchType.toString()));

		List<RepositoryCommit> repoCommits = new ArrayList<>(commits.size());
		for (RevCommit c : commits) {
			RepositoryCommit repoCommit = new RepositoryCommit(repositoryName, "", c);
			if (allRefs.containsKey(c)) {
				repoCommit.setRefs(allRefs.get(c));
			}
			repoCommits.add(repoCommit);
		}
		ListDataProvider<RepositoryCommit> dp = new ListDataProvider<RepositoryCommit>(repoCommits);
		DataView<RepositoryCommit> searchView = new DataView<RepositoryCommit>("commit", dp) {
			private static final long serialVersionUID = 1L;
			int counter;

			@Override
			public void populateItem(final Item<RepositoryCommit> item) {
				final RepositoryCommit entry = item.getModelObject();
				final Date date = entry.getAuthorIdent().getWhen();

				item.add(WicketUtils.createDateLabel("commitDate", date, getTimeZone(), getTimeUtils()));

				// author search link
				String author = entry.getAuthorIdent().getName();
				LinkPanel authorLink = new LinkPanel("commitAuthor", "list", author,
						GitSearchPage.class, WicketUtils.newSearchParameter(repositoryName, null,
								author, Constants.SearchType.AUTHOR));
				setPersonSearchTooltip(authorLink, author, Constants.SearchType.AUTHOR);
				item.add(authorLink);

				// merge icon
				if (entry.getParentCount() > 1) {
					item.add(WicketUtils.newImage("commitIcon", "commit_merge_16x16.png"));
				} else {
					item.add(WicketUtils.newBlankImage("commitIcon"));
				}

				String shortMessage = entry.getShortMessage();
				String trimmedMessage = shortMessage;
				if (!ArrayUtils.isEmpty(entry.getRefs())) {
					trimmedMessage = StringUtils.trimString(shortMessage, Constants.LEN_SHORTLOG_REFS);
				} else {
					trimmedMessage = StringUtils.trimString(shortMessage, Constants.LEN_SHORTLOG);
				}
				LinkPanel shortlog = new LinkPanel("commitShortMessage", "list subject",
						trimmedMessage, CommitPage.class, WicketUtils.newObjectParameter(
								repositoryName, entry.getName()));
				if (!shortMessage.equals(trimmedMessage)) {
					WicketUtils.setHtmlTooltip(shortlog, shortMessage);
				}
				item.add(shortlog);

				item.add(new RefsPanel("commitRefs", repositoryName, entry.getRefs()));

				item.add(new BookmarkablePageLink<Void>("commit", CommitPage.class, WicketUtils
						.newObjectParameter(repositoryName, entry.getName())));
				item.add(new BookmarkablePageLink<Void>("commitdiff", CommitDiffPage.class,
						WicketUtils.newObjectParameter(repositoryName, entry.getName())));
				item.add(new BookmarkablePageLink<Void>("tree", TreePage.class, WicketUtils
						.newObjectParameter(repositoryName, entry.getName())));

				WicketUtils.setAlternatingBackground(item, counter);
				counter++;
			}
		};
		add(searchView);
	}

	public boolean hasMore() {
		return hasMore;
	}
}
