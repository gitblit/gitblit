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

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.wicket.markup.html.basic.Label;
import org.apache.wicket.markup.html.link.BookmarkablePageLink;
import org.apache.wicket.markup.html.panel.Fragment;
import org.apache.wicket.markup.repeater.Item;
import org.apache.wicket.markup.repeater.data.DataView;
import org.apache.wicket.markup.repeater.data.ListDataProvider;
import org.apache.wicket.model.StringResourceModel;
import org.eclipse.jgit.diff.DiffEntry.ChangeType;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;

import com.gitblit.Constants;
import com.gitblit.Keys;
import com.gitblit.models.PathModel;
import com.gitblit.models.PathModel.PathChangeModel;
import com.gitblit.models.RefModel;
import com.gitblit.models.RepositoryCommit;
import com.gitblit.models.SubmoduleModel;
import com.gitblit.utils.ArrayUtils;
import com.gitblit.utils.JGitUtils;
import com.gitblit.utils.MarkdownUtils;
import com.gitblit.utils.StringUtils;
import com.gitblit.wicket.WicketUtils;
import com.gitblit.wicket.pages.BlobDiffPage;
import com.gitblit.wicket.pages.BlobPage;
import com.gitblit.wicket.pages.CommitDiffPage;
import com.gitblit.wicket.pages.CommitPage;
import com.gitblit.wicket.pages.GitSearchPage;
import com.gitblit.wicket.pages.HistoryPage;
import com.gitblit.wicket.pages.TreePage;

public class HistoryPanel extends BasePanel {

	private static final long serialVersionUID = 1L;

	private final boolean hasMore;

	public HistoryPanel(String wicketId, final String repositoryName, final String objectId,
			final String path, Repository r, int limit, int pageOffset, boolean showRemoteRefs) {
		super(wicketId);
		boolean pageResults = limit <= 0;
		int itemsPerPage = app().settings().getInteger(Keys.web.itemsPerPage, 50);
		if (itemsPerPage <= 1) {
			itemsPerPage = 50;
		}

		RevCommit commit = JGitUtils.getCommit(r, objectId);
		PathModel matchingPath = null;
		Map<String, SubmoduleModel> submodules = new HashMap<String, SubmoduleModel>();

		if (commit == null) {
			// commit missing
			String msg = MessageFormat.format("Failed to find history of **{0}** *{1}*",
					path, objectId);
			logger().error(msg + " " + repositoryName);
			add(new Label("commitHeader", MarkdownUtils.transformMarkdown(msg)).setEscapeModelStrings(false));
			add(new Label("breadcrumbs"));
		} else {
			// commit found
			List<PathChangeModel> paths = JGitUtils.getFilesInCommit(r, commit);
			add(new CommitHeaderPanel("commitHeader", repositoryName, commit));
			add(new PathBreadcrumbsPanel("breadcrumbs", repositoryName, path, objectId));
			for (SubmoduleModel model : JGitUtils.getSubmodules(r, commit.getTree())) {
				submodules.put(model.path, model);
			}

			for (PathModel p : paths) {
				if (p.path.equals(path)) {
					matchingPath = p;
					break;
				}
			}
			if (matchingPath == null) {
				// path not in commit
				// manually locate path in tree
				try (TreeWalk tw = new TreeWalk(r)) {
					tw.reset();
					tw.setRecursive(true);
					tw.addTree(commit.getTree());
					tw.setFilter(PathFilterGroup.createFromStrings(Collections.singleton(path)));
					while (tw.next()) {
						if (tw.getPathString().equals(path)) {
							matchingPath = new PathChangeModel(tw.getPathString(), tw.getPathString(), null, 0, tw
								.getRawMode(0), tw.getObjectId(0).getName(), commit.getId().getName(),
								ChangeType.MODIFY);
						}
					}
				} catch (Exception e) {
				}
			}
		}

		final boolean isTree = matchingPath == null ? true : matchingPath.isTree();
		final boolean isSubmodule = matchingPath == null ? false : matchingPath.isSubmodule();

		// submodule
		final String submodulePath;
		final boolean hasSubmodule;
		if (isSubmodule) {
			SubmoduleModel submodule = getSubmodule(submodules, repositoryName, matchingPath == null ? null : matchingPath.path);
			submodulePath = submodule.gitblitPath;
			hasSubmodule = submodule.hasSubmodule;
		} else {
			submodulePath = "";
			hasSubmodule = false;
		}

		Map<ObjectId, List<RefModel>> allRefs = JGitUtils.getAllRefs(r, showRemoteRefs);
		List<RevCommit> commits;
		if (pageResults) {
			// Paging result set
			commits = JGitUtils.getRevLog(r, objectId, path, pageOffset * itemsPerPage,
					itemsPerPage);
		} else {
			// Fixed size result set
			commits = JGitUtils.getRevLog(r, objectId, path, 0, limit);
		}

		// inaccurate way to determine if there are more commits.
		// works unless commits.size() represents the exact end.
		hasMore = commits.size() >= itemsPerPage;

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
				Date date = entry.getAuthorIdent().getWhen();

				item.add(WicketUtils.createDateLabel("commitDate", date, getTimeZone(), getTimeUtils()));

				// author search link
				String author = entry.getAuthorIdent().getName();
				LinkPanel authorLink = new LinkPanel("commitAuthor", "list", author,
						GitSearchPage.class,
						WicketUtils.newSearchParameter(repositoryName, null,
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

				if (isTree) {
					// tree
					item.add(new Label("hashLabel", getString("gb.tree") + "@"));
					LinkPanel commitHash = new LinkPanel("hashLink", null, entry.getName().substring(0, hashLen),
							TreePage.class, WicketUtils.newObjectParameter(
									repositoryName, entry.getName()));
					WicketUtils.setCssClass(commitHash, "shortsha1");
					WicketUtils.setHtmlTooltip(commitHash, entry.getName());
					item.add(commitHash);

					Fragment links = new Fragment("historyLinks", "treeLinks", this);
					links.add(new BookmarkablePageLink<Void>("commitdiff", CommitDiffPage.class,
							WicketUtils.newObjectParameter(repositoryName, entry.getName())));
					item.add(links);
				} else if (isSubmodule) {
					// submodule
					Repository repository = app().repositories().getRepository(repositoryName);
					String submoduleId = JGitUtils.getSubmoduleCommitId(repository, path, entry.getCommit());
					repository.close();
					if (StringUtils.isEmpty(submoduleId)) {
						// not a submodule at this commit, just a matching path
						item.add(new Label("hashLabel").setVisible(false));
						item.add(new Label("hashLink").setVisible(false));
					} else {
						// really a submodule
						item.add(new Label("hashLabel", submodulePath + "@"));
						LinkPanel commitHash = new LinkPanel("hashLink", null, submoduleId.substring(0, hashLen),
								TreePage.class, WicketUtils.newObjectParameter(
										submodulePath, submoduleId));
						WicketUtils.setCssClass(commitHash, "shortsha1");
						WicketUtils.setHtmlTooltip(commitHash, submoduleId);
						item.add(commitHash.setEnabled(hasSubmodule));
					}
					Fragment links = new Fragment("historyLinks", "treeLinks", this);
					links.add(new BookmarkablePageLink<Void>("commitdiff", CommitDiffPage.class,
							WicketUtils.newObjectParameter(repositoryName, entry.getName())));
					item.add(links);
				} else {
					// commit
					item.add(new Label("hashLabel", getString("gb.blob") + "@"));
					LinkPanel commitHash = new LinkPanel("hashLink", null, entry.getName().substring(0, hashLen),
							BlobPage.class, WicketUtils.newPathParameter(
									repositoryName, entry.getName(), path));
					WicketUtils.setCssClass(commitHash, "sha1");
					WicketUtils.setHtmlTooltip(commitHash, entry.getName());
					item.add(commitHash);

					Fragment links = new Fragment("historyLinks", "blobLinks", this);
					links.add(new BookmarkablePageLink<Void>("commitdiff", CommitDiffPage.class,
							WicketUtils.newObjectParameter(repositoryName, entry.getName())));
					links.add(new BookmarkablePageLink<Void>("difftocurrent", BlobDiffPage.class,
							WicketUtils.newBlobDiffParameter(repositoryName, entry.getName(),
									objectId, path)).setEnabled(counter > 0));
					item.add(links);
				}

				WicketUtils.setAlternatingBackground(item, counter);
				counter++;
			}
		};
		add(logView);

		// determine to show pager, more, or neither
		if (limit <= 0) {
			// no display limit
			add(new Label("moreHistory", "").setVisible(false));
		} else {
			if (pageResults) {
				// paging
				add(new Label("moreHistory", "").setVisible(false));
			} else {
				// more
				if (commits.size() == limit) {
					// show more
					add(new LinkPanel("moreHistory", "link", new StringResourceModel(
							"gb.moreHistory", this, null), HistoryPage.class,
							WicketUtils.newPathParameter(repositoryName, objectId, path)));
				} else {
					// no more
					add(new Label("moreHistory", "").setVisible(false));
				}
			}
		}
	}

	public boolean hasMore() {
		return hasMore;
	}

	protected SubmoduleModel getSubmodule(Map<String, SubmoduleModel> submodules, String repositoryName, String path) {
		SubmoduleModel model = submodules.get(path);
		if (model == null) {
			// undefined submodule?!
			model = new SubmoduleModel(path.substring(path.lastIndexOf('/') + 1), path, path);
			model.hasSubmodule = false;
			model.gitblitPath = model.name;
			return model;
		} else {
			// extract the repository name from the clone url
			List<String> patterns = app().settings().getStrings(Keys.git.submoduleUrlPatterns);
			String submoduleName = StringUtils.extractRepositoryPath(model.url, patterns.toArray(new String[0]));

			// determine the current path for constructing paths relative
			// to the current repository
			String currentPath = "";
			if (repositoryName.indexOf('/') > -1) {
				currentPath = repositoryName.substring(0, repositoryName.lastIndexOf('/') + 1);
			}

			// try to locate the submodule repository
			// prefer bare to non-bare names
			List<String> candidates = new ArrayList<String>();

			// relative
			candidates.add(currentPath + StringUtils.stripDotGit(submoduleName));
			candidates.add(candidates.get(candidates.size() - 1) + ".git");

			// relative, no subfolder
			if (submoduleName.lastIndexOf('/') > -1) {
				String name = submoduleName.substring(submoduleName.lastIndexOf('/') + 1);
				candidates.add(currentPath + StringUtils.stripDotGit(name));
				candidates.add(candidates.get(candidates.size() - 1) + ".git");
			}

			// absolute
			candidates.add(StringUtils.stripDotGit(submoduleName));
			candidates.add(candidates.get(candidates.size() - 1) + ".git");

			// absolute, no subfolder
			if (submoduleName.lastIndexOf('/') > -1) {
				String name = submoduleName.substring(submoduleName.lastIndexOf('/') + 1);
				candidates.add(StringUtils.stripDotGit(name));
				candidates.add(candidates.get(candidates.size() - 1) + ".git");
			}

			// create a unique, ordered set of candidate paths
			Set<String> paths = new LinkedHashSet<String>(candidates);
			for (String candidate : paths) {
				if (app().repositories().hasRepository(candidate)) {
					model.hasSubmodule = true;
					model.gitblitPath = candidate;
					return model;
				}
			}

			// we do not have a copy of the submodule, but we need a path
			model.gitblitPath = candidates.get(0);
			return model;
		}
	}
}
