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

import java.io.Serializable;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.wicket.Component;
import org.apache.wicket.PageParameters;
import org.apache.wicket.RestartResponseException;
import org.apache.wicket.behavior.SimpleAttributeModifier;
import org.apache.wicket.markup.html.basic.Label;
import org.apache.wicket.markup.html.form.DropDownChoice;
import org.apache.wicket.markup.html.form.TextField;
import org.apache.wicket.markup.html.link.ExternalLink;
import org.apache.wicket.markup.html.panel.Fragment;
import org.apache.wicket.model.IModel;
import org.apache.wicket.model.Model;
import org.apache.wicket.request.target.basic.RedirectRequestTarget;
import org.eclipse.jgit.diff.DiffEntry.ChangeType;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gitblit.Constants;
import com.gitblit.GitBlitException;
import com.gitblit.Keys;
import com.gitblit.extensions.RepositoryNavLinkExtension;
import com.gitblit.models.NavLink;
import com.gitblit.models.NavLink.ExternalNavLink;
import com.gitblit.models.NavLink.PageNavLink;
import com.gitblit.models.ProjectModel;
import com.gitblit.models.RefModel;
import com.gitblit.models.RepositoryModel;
import com.gitblit.models.SubmoduleModel;
import com.gitblit.models.UserModel;
import com.gitblit.models.UserRepositoryPreferences;
import com.gitblit.servlet.PagesServlet;
import com.gitblit.servlet.SyndicationServlet;
import com.gitblit.utils.ArrayUtils;
import com.gitblit.utils.BugtraqProcessor;
import com.gitblit.utils.DeepCopier;
import com.gitblit.utils.JGitUtils;
import com.gitblit.utils.ModelUtils;
import com.gitblit.utils.RefLogUtils;
import com.gitblit.utils.StringUtils;
import com.gitblit.wicket.CacheControl;
import com.gitblit.wicket.GitBlitWebSession;
import com.gitblit.wicket.SessionlessForm;
import com.gitblit.wicket.WicketUtils;
import com.gitblit.wicket.panels.LinkPanel;
import com.gitblit.wicket.panels.NavigationPanel;
import com.gitblit.wicket.panels.RefsPanel;
import com.google.common.base.Optional;

public abstract class RepositoryPage extends RootPage {

	protected final Logger logger = LoggerFactory.getLogger(getClass());

	private final String PARAM_STAR = "star";

	protected final String projectName;
	protected final String repositoryName;
	protected final String objectId;

	private transient Repository r;

	private RepositoryModel m;

	private Map<String, SubmoduleModel> submodules;

	private boolean showAdmin;
	private boolean isOwner;

	public RepositoryPage(PageParameters params) {
		super(params);
		repositoryName = WicketUtils.getRepositoryName(params);
		String root = StringUtils.getFirstPathElement(repositoryName);
		if (StringUtils.isEmpty(root)) {
			projectName = app().settings().getString(Keys.web.repositoryRootGroupName, "main");
		} else {
			projectName = root;
		}
		objectId = WicketUtils.getObject(params);

		if (StringUtils.isEmpty(repositoryName)) {
			error(MessageFormat.format(getString("gb.repositoryNotSpecifiedFor"), getPageName()), true);
		}

		if (!getRepositoryModel().hasCommits && getClass() != EmptyRepositoryPage.class) {
			throw new RestartResponseException(EmptyRepositoryPage.class, params);
		}

		if (getRepositoryModel().isCollectingGarbage) {
			error(MessageFormat.format(getString("gb.busyCollectingGarbage"), getRepositoryModel().name), true);
		}

		if (objectId != null) {
			RefModel branch = null;
			if ((branch = JGitUtils.getBranch(getRepository(), objectId)) != null) {
				UserModel user = GitBlitWebSession.get().getUser();
				if (user == null) {
					// workaround until get().getUser() is reviewed throughout the app
					user = UserModel.ANONYMOUS;
				}
				boolean canAccess = user.canView(getRepositoryModel(),
								branch.reference.getName());
				if (!canAccess) {
					error(getString("gb.accessDenied"), true);
				}
			}
		}

		if (params.containsKey(PARAM_STAR)) {
			// set starred state
			boolean star = params.getBoolean(PARAM_STAR);
			UserModel user = GitBlitWebSession.get().getUser();
			if (user != null && user.isAuthenticated) {
				UserRepositoryPreferences prefs = user.getPreferences().getRepositoryPreferences(getRepositoryModel().name);
				prefs.starred = star;
				try {
					app().gitblit().reviseUser(user.username, user);
				} catch (GitBlitException e) {
					logger.error("Failed to update user " + user.username, e);
					error(getString("gb.failedToUpdateUser"), false);
				}
			}
		}

		showAdmin = false;
		if (app().settings().getBoolean(Keys.web.authenticateAdminPages, true)) {
			boolean allowAdmin = app().settings().getBoolean(Keys.web.allowAdministration, false);
			showAdmin = allowAdmin && GitBlitWebSession.get().canAdmin();
		} else {
			showAdmin = app().settings().getBoolean(Keys.web.allowAdministration, false);
		}
		isOwner = GitBlitWebSession.get().isLoggedIn()
				&& (getRepositoryModel().isOwner(GitBlitWebSession.get().getUsername()));

		// register the available navigation links for this page and user
		List<NavLink> navLinks = registerNavLinks();

		// standard navigation links
		NavigationPanel navigationPanel = new NavigationPanel("repositoryNavPanel", getRepoNavPageClass(), navLinks);
		add(navigationPanel);

		add(new ExternalLink("syndication", SyndicationServlet.asLink(getRequest()
				.getRelativePathPrefixToContextRoot(), getRepositoryName(), null, 0)));

		// add floating search form
		SearchForm searchForm = new SearchForm("searchForm", getRepositoryName());
		add(searchForm);
		searchForm.setTranslatedAttributes();

		// set stateless page preference
		setStatelessHint(true);
	}

	@Override
	protected Class<? extends BasePage> getRootNavPageClass() {
		return RepositoriesPage.class;
	}

	protected Class<? extends BasePage> getRepoNavPageClass() {
		return getClass();
	}

	protected BugtraqProcessor bugtraqProcessor() {
		return new BugtraqProcessor(app().settings());
	}

	private List<NavLink> registerNavLinks() {
		Repository r = getRepository();
		RepositoryModel model = getRepositoryModel();

		PageParameters params = null;
		PageParameters objectParams = null;
		if (!StringUtils.isEmpty(repositoryName)) {
			params = WicketUtils.newRepositoryParameter(getRepositoryName());
			objectParams = params;

			// preserve the objectid iff the objectid directly (or indirectly) refers to a ref
			if (isCommitPage() && !StringUtils.isEmpty(objectId)) {
				RevCommit commit = JGitUtils.getCommit(r, objectId);
				if (commit != null) {
					String bestId = getBestCommitId(commit);
					if (!commit.getName().equals(bestId)) {
						objectParams = WicketUtils.newObjectParameter(getRepositoryName(), bestId);
					}
				}
			}
		}
		List<NavLink> navLinks = new ArrayList<NavLink>();


		// standard links
		if (RefLogUtils.getRefLogBranch(r) == null) {
			navLinks.add(new PageNavLink("gb.summary", SummaryPage.class, params));
		} else {
			navLinks.add(new PageNavLink("gb.summary", SummaryPage.class, params));
			//			pages.put("overview", new PageRegistration("gb.overview", OverviewPage.class, params));
			navLinks.add(new PageNavLink("gb.reflog", ReflogPage.class, params));
		}

		if (!model.hasCommits) {
			return navLinks;
		}

		if( params != null ){
			WicketUtils.setCommitIdIf(params);
		}
		navLinks.add(new PageNavLink("gb.commits", LogPage.class, objectParams));
		navLinks.add(new PageNavLink("gb.tree", TreePage.class, objectParams));
		if (app().tickets().isReady() && (app().tickets().isAcceptingNewTickets(model) || app().tickets().hasTickets(model))) {
			PageParameters tParams = WicketUtils.newOpenTicketsParameter(getRepositoryName());
			WicketUtils.setCommitIdIf(tParams);
			navLinks.add(new PageNavLink("gb.tickets", TicketsPage.class, tParams));
		}
		navLinks.add(new PageNavLink("gb.docs", DocsPage.class, objectParams, true));
		if (app().settings().getBoolean(Keys.web.allowForking, true)) {
			navLinks.add(new PageNavLink("gb.forks", ForksPage.class, params, true));
		}
		navLinks.add(new PageNavLink("gb.compare", ComparePage.class, params, true));

		// conditional links
		// per-repository extra navlinks
		if (JGitUtils.getPagesBranch(r) != null) {
			ExternalNavLink pagesLink = new ExternalNavLink("gb.pages", PagesServlet.asLink(
					getRequest().getRelativePathPrefixToContextRoot(), getRepositoryName(), null), true);
			navLinks.add(pagesLink);
		}

		UserModel user = UserModel.ANONYMOUS;
		if (GitBlitWebSession.get().isLoggedIn()) {
			user = GitBlitWebSession.get().getUser();
		}

		// add repository nav link extensions
		List<RepositoryNavLinkExtension> extensions = app().plugins().getExtensions(RepositoryNavLinkExtension.class);
		for (RepositoryNavLinkExtension ext : extensions) {
			navLinks.addAll(ext.getNavLinks(user, model));
		}

		return navLinks;
	}

	protected boolean allowForkControls() {
		return app().settings().getBoolean(Keys.web.allowForking, true);
	}

	@Override
	protected void setupPage(String repositoryName, String pageName) {
		
		//This method should only be called once in the page lifecycle.
		//However, it must be called after the constructor has run, hence not in onInitialize
		//It may be attempted to be called again if an info or error message is displayed.
		if (get("projectTitle") != null) { return; }
		
		String projectName = StringUtils.getFirstPathElement(repositoryName);
		ProjectModel project = app().projects().getProjectModel(projectName);

		if (project.isUserProject()) {
			// user-as-project
			add(new LinkPanel("projectTitle", null, project.getDisplayName(),
					UserPage.class, WicketUtils.newUsernameParameter(project.name.substring(1))));
		} else {
			// project
			add(new LinkPanel("projectTitle", null, project.name,
					ProjectPage.class, WicketUtils.newProjectParameter(project.name)));
		}

		String name = StringUtils.stripDotGit(repositoryName);
		if (!StringUtils.isEmpty(projectName) && name.startsWith(projectName)) {
			name = name.substring(projectName.length() + 1);
		}
		add(new LinkPanel("repositoryName", null, name, SummaryPage.class,
				WicketUtils.newRepositoryParameter(repositoryName)));

		UserModel user = GitBlitWebSession.get().getUser();
		if (user == null) {
			user = UserModel.ANONYMOUS;
		}

		// indicate origin repository
		RepositoryModel model = getRepositoryModel();
		if (StringUtils.isEmpty(model.originRepository)) {
			if (model.isMirror) {
				add(new Fragment("repoIcon", "mirrorIconFragment", this));
				Fragment mirrorFrag = new Fragment("originRepository", "mirrorFragment", this);
				Label lbl = new Label("originRepository", MessageFormat.format(getString("gb.mirrorOf"), "<b>" + model.origin + "</b>"));
				mirrorFrag.add(lbl.setEscapeModelStrings(false));
				add(mirrorFrag);
			} else {
				if (model.isBare) {
					add(new Fragment("repoIcon", "repoIconFragment", this));
				} else {
					add(new Fragment("repoIcon", "cloneIconFragment", this));
				}
				add(new Label("originRepository", Optional.of(model.description).or("")));
			}
		} else {
			RepositoryModel origin = app().repositories().getRepositoryModel(model.originRepository);
			if (origin == null) {
				// no origin repository, show description if available
				if (model.isBare) {
					add(new Fragment("repoIcon", "repoIconFragment", this));
				} else {
					add(new Fragment("repoIcon", "cloneIconFragment", this));
				}
				add(new Label("originRepository", Optional.of(model.description).or("")));
			} else if (!user.canView(origin)) {
				// show origin repository without link
				add(new Fragment("repoIcon", "forkIconFragment", this));
				Fragment forkFrag = new Fragment("originRepository", "originFragment", this);
				forkFrag.add(new Label("originRepository", StringUtils.stripDotGit(model.originRepository)));
				add(forkFrag);
			} else {
				// link to origin repository
				add(new Fragment("repoIcon", "forkIconFragment", this));
				Fragment forkFrag = new Fragment("originRepository", "originFragment", this);
				forkFrag.add(new LinkPanel("originRepository", null, StringUtils.stripDotGit(model.originRepository),
						SummaryPage.class, WicketUtils.newRepositoryParameter(model.originRepository)));
				add(forkFrag);
			}
		}

		// new ticket button
		if (user.isAuthenticated && app().tickets().isAcceptingNewTickets(getRepositoryModel())) {
			String newTicketUrl = getRequestCycle().urlFor(NewTicketPage.class, WicketUtils.newRepositoryParameter(repositoryName)).toString();
			addToolbarButton("newTicketLink", "fa fa-ticket", getString("gb.new"), newTicketUrl);
		} else {
			add(new Label("newTicketLink").setVisible(false));
		}

		// (un)star link allows a user to star a repository
		if (user.isAuthenticated && model.hasCommits) {
			PageParameters starParams = DeepCopier.copy(getPageParameters());
			starParams.put(PARAM_STAR, !user.getPreferences().isStarredRepository(model.name));
			String toggleStarUrl = getRequestCycle().urlFor(getClass(), starParams).toString();
			if (user.getPreferences().isStarredRepository(model.name)) {
				// show unstar button
				add(new Label("starLink").setVisible(false));
				addToolbarButton("unstarLink", "icon-star-empty", getString("gb.unstar"), toggleStarUrl);
			} else {
				// show star button
				addToolbarButton("starLink", "icon-star", getString("gb.star"), toggleStarUrl);
				add(new Label("unstarLink").setVisible(false));
			}
		} else {
			// anonymous user
			add(new Label("starLink").setVisible(false));
			add(new Label("unstarLink").setVisible(false));
		}

		// fork controls
		if (!allowForkControls() || !user.isAuthenticated) {
			// must be logged-in to fork, hide all fork controls
			add(new ExternalLink("forkLink", "").setVisible(false));
			add(new ExternalLink("myForkLink", "").setVisible(false));
		} else {
			String fork = app().repositories().getFork(user.username, model.name);
			String userRepo = ModelUtils.getPersonalPath(user.username) + "/" + StringUtils.stripDotGit(StringUtils.getLastPathElement(model.name));
			boolean hasUserRepo = app().repositories().hasRepository(userRepo);
			boolean hasFork = fork != null;
			boolean canFork = user.canFork(model) && model.hasCommits && !hasUserRepo;

			if (hasFork || !canFork) {
				// user not allowed to fork or fork already exists or repo forbids forking
				add(new ExternalLink("forkLink", "").setVisible(false));

				if (hasFork && !fork.equals(model.name)) {
					// user has fork, view my fork link
					String url = getRequestCycle().urlFor(SummaryPage.class, WicketUtils.newRepositoryParameter(fork)).toString();
					add(new ExternalLink("myForkLink", url));
				} else {
					// no fork, hide view my fork link
					add(new ExternalLink("myForkLink", "").setVisible(false));
				}
			} else if (canFork) {
				// can fork and we do not have one
				add(new ExternalLink("myForkLink", "").setVisible(false));
				String url = getRequestCycle().urlFor(ForkPage.class, WicketUtils.newRepositoryParameter(model.name)).toString();
				add(new ExternalLink("forkLink", url));
			}
		}

		if (showAdmin || isOwner) {
			String url = getRequestCycle().urlFor(EditRepositoryPage.class, WicketUtils.newRepositoryParameter(model.name)).toString();
			add(new ExternalLink("editLink", url));
		} else {
			add(new Label("editLink").setVisible(false));
		}

		super.setupPage(repositoryName, pageName);
	}

	protected void addToolbarButton(String wicketId, String iconClass, String label, String url) {
		Fragment button = new Fragment(wicketId, "toolbarLinkFragment", this);
		Label icon = new Label("icon");
		WicketUtils.setCssClass(icon, iconClass);
		button.add(icon);
		button.add(new Label("label", label));
		button.add(new SimpleAttributeModifier("href", url));
		add(button);
	}

	protected void addSyndicationDiscoveryLink() {
		add(WicketUtils.syndicationDiscoveryLink(SyndicationServlet.getTitle(repositoryName,
				objectId), SyndicationServlet.asLink(getRequest()
				.getRelativePathPrefixToContextRoot(), repositoryName, objectId, 0)));
	}

	protected Repository getRepository() {
		if (r == null) {
			Repository r = app().repositories().getRepository(repositoryName);
			if (r == null) {
				error(getString("gb.canNotLoadRepository") + " " + repositoryName, true);
				return null;
			}
			this.r = r;
		}
		return r;
	}

	protected RepositoryModel getRepositoryModel() {
		if (m == null) {
			RepositoryModel model = app().repositories().getRepositoryModel(
					GitBlitWebSession.get().getUser(), repositoryName);
			if (model == null) {
				if (app().repositories().hasRepository(repositoryName, true)) {
					// has repository, but unauthorized
					authenticationError(getString("gb.unauthorizedAccessForRepository") + " " + repositoryName);
				} else {
					// does not have repository
					error(getString("gb.canNotLoadRepository") + " " + repositoryName, true);
				}
				return null;
			}
			m = model;
		}
		return m;
	}

	protected String getRepositoryName() {
		return getRepositoryModel().name;
	}

	protected RevCommit getCommit() {
		RevCommit commit = JGitUtils.getCommit(r, objectId);
		if (commit == null) {
			error(MessageFormat.format(getString("gb.failedToFindCommit"),
					objectId, repositoryName, getPageName()), null, LogPage.class,
					WicketUtils.newRepositoryParameter(repositoryName));
		}
		getSubmodules(commit);
		return commit;
	}

	protected String getBestCommitId(RevCommit commit) {
		String head = null;
		try {
			head = r.resolve(getRepositoryModel().HEAD).getName();
		} catch (Exception e) {
		}

		String id = commit.getName();
		if (!StringUtils.isEmpty(head) && head.equals(id)) {
			// match default branch
			return Repository.shortenRefName(getRepositoryModel().HEAD);
		}

		// find first branch match
		for (RefModel ref : JGitUtils.getLocalBranches(r, false, -1)) {
			if (ref.getObjectId().getName().equals(id)) {
				return Repository.shortenRefName(ref.getName());
			}
		}

		// return sha
		return id;
	}

	protected Map<String, SubmoduleModel> getSubmodules(RevCommit commit) {
		if (submodules == null) {
			submodules = new HashMap<String, SubmoduleModel>();
			for (SubmoduleModel model : JGitUtils.getSubmodules(r, commit.getTree())) {
				submodules.put(model.path, model);
			}
		}
		return submodules;
	}

	protected SubmoduleModel getSubmodule(String path) {
		SubmoduleModel model = null;
		if (submodules != null) {
			model = submodules.get(path);
		}
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

	protected String getShortObjectId(String objectId) {
		return objectId.substring(0, app().settings().getInteger(Keys.web.shortCommitIdLength, 6));
	}

	protected void addRefs(Repository r, RevCommit c) {
		add(new RefsPanel("refsPanel", repositoryName, c, JGitUtils.getAllRefs(r, getRepositoryModel().showRemoteBranches)));
	}

	protected void addFullText(String wicketId, String text) {
		RepositoryModel model = getRepositoryModel();
		String content = bugtraqProcessor().processCommitMessage(r, model, text);
		String html;
		switch (model.commitMessageRenderer) {
		case MARKDOWN:
			String safeContent = app().xssFilter().relaxed(content);
			html = MessageFormat.format("<div class='commit_message'>{0}</div>", safeContent);
			break;
		default:
			html = MessageFormat.format("<pre class='commit_message'>{0}</pre>", content);
			break;
		}
		add(new Label(wicketId, html).setEscapeModelStrings(false));
	}

	protected abstract String getPageName();

	protected boolean isCommitPage() {
		return false;
	}

	protected Component createPersonPanel(String wicketId, PersonIdent identity,
			Constants.SearchType searchType) {
		String name = identity == null ? "" : identity.getName();
		String address = identity == null ? "" : identity.getEmailAddress();
		name = StringUtils.removeNewlines(name);
		address = StringUtils.removeNewlines(address);
		boolean showEmail = app().settings().getBoolean(Keys.web.showEmailAddresses, false);
		if (!showEmail || StringUtils.isEmpty(name) || StringUtils.isEmpty(address)) {
			String value = name;
			if (StringUtils.isEmpty(value)) {
				if (showEmail) {
					value = address;
				} else {
					value = getString("gb.missingUsername");
				}
			}
			Fragment partial = new Fragment(wicketId, "partialPersonIdent", this);
			LinkPanel link = new LinkPanel("personName", "list", value, GitSearchPage.class,
					WicketUtils.newSearchParameter(repositoryName, objectId, value, searchType));
			setPersonSearchTooltip(link, value, searchType);
			partial.add(link);
			return partial;
		} else {
			Fragment fullPerson = new Fragment(wicketId, "fullPersonIdent", this);
			LinkPanel nameLink = new LinkPanel("personName", "list", name, GitSearchPage.class,
					WicketUtils.newSearchParameter(repositoryName, objectId, name, searchType));
			setPersonSearchTooltip(nameLink, name, searchType);
			fullPerson.add(nameLink);

			LinkPanel addressLink = new LinkPanel("personAddress", "hidden-phone list", "<" + address + ">",
					GitSearchPage.class, WicketUtils.newSearchParameter(repositoryName, objectId,
							address, searchType));
			setPersonSearchTooltip(addressLink, address, searchType);
			fullPerson.add(addressLink);
			return fullPerson;
		}
	}

	protected void setPersonSearchTooltip(Component component, String value,
			Constants.SearchType searchType) {
		if (searchType.equals(Constants.SearchType.AUTHOR)) {
			WicketUtils.setHtmlTooltip(component, getString("gb.searchForAuthor") + " " + value);
		} else if (searchType.equals(Constants.SearchType.COMMITTER)) {
			WicketUtils.setHtmlTooltip(component, getString("gb.searchForCommitter") + " " + value);
		}
	}

	protected void setChangeTypeTooltip(Component container, ChangeType type) {
		switch (type) {
		case ADD:
			WicketUtils.setHtmlTooltip(container, getString("gb.addition"));
			break;
		case COPY:
		case RENAME:
			WicketUtils.setHtmlTooltip(container, getString("gb.rename"));
			break;
		case DELETE:
			WicketUtils.setHtmlTooltip(container, getString("gb.deletion"));
			break;
		case MODIFY:
			WicketUtils.setHtmlTooltip(container, getString("gb.modification"));
			break;
		}
	}


	@Override
	protected void onBeforeRender() {
		// dispose of repository object
		if (r != null) {
			r.close();
			r = null;
		}
		
		// setup page header and footer
		setupPage(getRepositoryName(), "/ " + getPageName());

		super.onBeforeRender();
	}

	@Override
	protected void setLastModified() {
		if (getClass().isAnnotationPresent(CacheControl.class)) {
			CacheControl cacheControl = getClass().getAnnotation(CacheControl.class);
			switch (cacheControl.value()) {
			case REPOSITORY:
				RepositoryModel repository = getRepositoryModel();
				if (repository != null) {
					setLastModified(repository.lastChange);
				}
				break;
			case COMMIT:
				RevCommit commit = getCommit();
				if (commit != null) {
					Date commitDate = JGitUtils.getCommitDate(commit);
					setLastModified(commitDate);
				}
				break;
			default:
				super.setLastModified();
			}
		}
	}

	protected PageParameters newRepositoryParameter() {
		return WicketUtils.newRepositoryParameter(repositoryName);
	}

	protected PageParameters newCommitParameter() {
		return WicketUtils.newObjectParameter(repositoryName, objectId);
	}

	protected PageParameters newCommitParameter(String commitId) {
		return WicketUtils.newObjectParameter(repositoryName, commitId);
	}

	public boolean isShowAdmin() {
		return showAdmin;
	}

	public boolean isOwner() {
		return isOwner;
	}

	private class SearchForm extends SessionlessForm<Void> implements Serializable {
		private static final long serialVersionUID = 1L;

		private final String repositoryName;

		private final IModel<String> searchBoxModel = new Model<String>("");

		private final IModel<Constants.SearchType> searchTypeModel = new Model<Constants.SearchType>(
				Constants.SearchType.COMMIT);

		public SearchForm(String id, String repositoryName) {
			super(id, RepositoryPage.this.getClass(), RepositoryPage.this.getPageParameters());
			this.repositoryName = repositoryName;
			DropDownChoice<Constants.SearchType> searchType = new DropDownChoice<Constants.SearchType>(
					"searchType", Arrays.asList(Constants.SearchType.values()));
			searchType.setModel(searchTypeModel);
			add(searchType.setVisible(app().settings().getBoolean(Keys.web.showSearchTypeSelection, false)));
			TextField<String> searchBox = new TextField<String>("searchBox", searchBoxModel);
			add(searchBox);
		}

		void setTranslatedAttributes() {
			WicketUtils.setHtmlTooltip(get("searchType"), getString("gb.searchTypeTooltip"));
			WicketUtils.setHtmlTooltip(get("searchBox"),
					MessageFormat.format(getString("gb.searchTooltip"), repositoryName));
			WicketUtils.setInputPlaceholder(get("searchBox"), getString("gb.search"));
		}

		@Override
		public void onSubmit() {
			Constants.SearchType searchType = searchTypeModel.getObject();
			String searchString = searchBoxModel.getObject();
			if (StringUtils.isEmpty(searchString)) {
				// redirect to self to avoid wicket page update bug
				String absoluteUrl = getCanonicalUrl();
				getRequestCycle().setRequestTarget(new RedirectRequestTarget(absoluteUrl));
				return;
			}
			for (Constants.SearchType type : Constants.SearchType.values()) {
				if (searchString.toLowerCase().startsWith(type.name().toLowerCase() + ":")) {
					searchType = type;
					searchString = searchString.substring(type.name().toLowerCase().length() + 1)
							.trim();
					break;
				}
			}
			Class<? extends BasePage> searchPageClass = GitSearchPage.class;
			RepositoryModel model = app().repositories().getRepositoryModel(repositoryName);
			if (app().settings().getBoolean(Keys.web.allowLuceneIndexing, true)
					&& !ArrayUtils.isEmpty(model.indexedBranches)) {
				// this repository is Lucene-indexed
				searchPageClass = LuceneSearchPage.class;
			}
			// use an absolute url to workaround Wicket-Tomcat problems with
			// mounted url parameters (issue-111)
			PageParameters params = WicketUtils.newSearchParameter(repositoryName, null, searchString, searchType);
			String absoluteUrl = getCanonicalUrl(searchPageClass, params);
			getRequestCycle().setRequestTarget(new RedirectRequestTarget(absoluteUrl));
		}
	}
}
