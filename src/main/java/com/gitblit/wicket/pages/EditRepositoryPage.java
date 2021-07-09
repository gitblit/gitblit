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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.wicket.PageParameters;
import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.ajax.form.AjaxFormChoiceComponentUpdatingBehavior;
import org.apache.wicket.behavior.SimpleAttributeModifier;
import org.apache.wicket.extensions.markup.html.form.palette.Palette;
import org.apache.wicket.markup.html.WebMarkupContainer;
import org.apache.wicket.markup.html.basic.Label;
import org.apache.wicket.markup.html.form.Button;
import org.apache.wicket.markup.html.form.CheckBox;
import org.apache.wicket.markup.html.form.ChoiceRenderer;
import org.apache.wicket.markup.html.form.DropDownChoice;
import org.apache.wicket.markup.html.link.ExternalLink;
import org.apache.wicket.markup.html.form.Form;
import org.apache.wicket.markup.html.form.IChoiceRenderer;
import org.apache.wicket.markup.html.form.TextField;
import org.apache.wicket.markup.html.link.Link;
import org.apache.wicket.markup.html.list.ListItem;
import org.apache.wicket.markup.html.list.ListView;
import org.apache.wicket.model.CompoundPropertyModel;
import org.apache.wicket.model.IModel;
import org.apache.wicket.model.Model;
import org.apache.wicket.model.PropertyModel;
import org.apache.wicket.model.util.CollectionModel;
import org.apache.wicket.model.util.ListModel;
import org.eclipse.jgit.lib.Repository;

import com.gitblit.Constants;
import com.gitblit.Constants.AccessRestrictionType;
import com.gitblit.Constants.AuthorizationControl;
import com.gitblit.Constants.CommitMessageRenderer;
import com.gitblit.Constants.FederationStrategy;
import com.gitblit.Constants.MergeType;
import com.gitblit.Constants.RegistrantType;
import com.gitblit.GitBlitException;
import com.gitblit.Keys;
import com.gitblit.models.RegistrantAccessPermission;
import com.gitblit.models.RepositoryModel;
import com.gitblit.models.UserChoice;
import com.gitblit.models.UserModel;
import com.gitblit.utils.ArrayUtils;
import com.gitblit.utils.StringUtils;
import com.gitblit.wicket.GitBlitWebSession;
import com.gitblit.wicket.StringChoiceRenderer;
import com.gitblit.wicket.WicketUtils;
import com.gitblit.wicket.panels.AccessPolicyPanel;
import com.gitblit.wicket.panels.BasePanel.JavascriptEventConfirmation;
import com.gitblit.wicket.panels.BooleanOption;
import com.gitblit.wicket.panels.BulletListPanel;
import com.gitblit.wicket.panels.ChoiceOption;
import com.gitblit.wicket.panels.RegistrantPermissionsPanel;
import com.gitblit.wicket.panels.RepositoryNamePanel;
import com.gitblit.wicket.panels.TextOption;

public class EditRepositoryPage extends RootSubPage {

	private final boolean isCreate;

	RepositoryNamePanel namePanel;

	AccessPolicyPanel accessPolicyPanel;

	private boolean isAdmin;

	RepositoryModel repositoryModel;

	private IModel<String> metricAuthorExclusions;

	private IModel<String> mailingLists;

	private final static String COMMIT_MESSAGE_LINK = "https://git.wiki.kernel.org/index.php/CommitMessageConventions";

	public EditRepositoryPage() {
		// create constructor
		super();
		isCreate = true;
		RepositoryModel model = new RepositoryModel();
		String restriction = app().settings().getString(Keys.git.defaultAccessRestriction, "PUSH");
		model.accessRestriction = AccessRestrictionType.fromName(restriction);
		String authorization = app().settings().getString(Keys.git.defaultAuthorizationControl, null);
		model.authorizationControl = AuthorizationControl.fromName(authorization);

		GitBlitWebSession session = GitBlitWebSession.get();
		UserModel user = session.getUser();
		if (user != null && user.canCreate() && !user.canAdmin()) {
			// personal create permissions, inject personal repository path
			model.name = user.getPersonalPath() + "/";
			model.projectPath = user.getPersonalPath();
			model.addOwner(user.username);
			// personal repositories are private by default
			model.accessRestriction = AccessRestrictionType.VIEW;
			model.authorizationControl = AuthorizationControl.NAMED;
		}

		setupPage(model);
		setStatelessHint(false);
		setOutputMarkupId(true);
	}

	public EditRepositoryPage(PageParameters params) {
		// edit constructor
		super(params);
		isCreate = false;
		String name = WicketUtils.getRepositoryName(params);
		RepositoryModel model = app().repositories().getRepositoryModel(name);
		setupPage(model);
		setStatelessHint(false);
		setOutputMarkupId(true);
	}

	@Override
	protected boolean requiresPageMap() {
		return true;
	}

	@Override
	protected Class<? extends BasePage> getRootNavPageClass() {
		return RepositoriesPage.class;
	}

	protected void setupPage(RepositoryModel model) {
		this.repositoryModel = model;

		// ensure this user can create or edit this repository
		checkPermissions(repositoryModel);

		List<String> indexedBranches = new ArrayList<String>();
		List<String> federationSets = new ArrayList<String>();
		final List<RegistrantAccessPermission> repositoryUsers = new ArrayList<RegistrantAccessPermission>();
		final List<RegistrantAccessPermission> repositoryTeams = new ArrayList<RegistrantAccessPermission>();
		List<String> preReceiveScripts = new ArrayList<String>();
		List<String> postReceiveScripts = new ArrayList<String>();

		GitBlitWebSession session = GitBlitWebSession.get();
		final UserModel user = session.getUser() == null ? UserModel.ANONYMOUS : session.getUser();
		final boolean allowEditName = isCreate || isAdmin || repositoryModel.isUsersPersonalRepository(user.username);

		if (isCreate) {
			if (user.canAdmin()) {
				super.setupPage(getString("gb.newRepository"), "");
			} else {
				super.setupPage(getString("gb.newRepository"), user.getDisplayName());
			}
		} else {
			super.setupPage(getString("gb.edit"), repositoryModel.name);
			repositoryUsers.addAll(app().repositories().getUserAccessPermissions(repositoryModel));
			repositoryTeams.addAll(app().repositories().getTeamAccessPermissions(repositoryModel));
			Collections.sort(repositoryUsers);
			Collections.sort(repositoryTeams);

			federationSets.addAll(repositoryModel.federationSets);
			if (!ArrayUtils.isEmpty(repositoryModel.indexedBranches)) {
				indexedBranches.addAll(repositoryModel.indexedBranches);
			}
		}

		final String oldName = repositoryModel.name;

		final RegistrantPermissionsPanel usersPalette = new RegistrantPermissionsPanel("users",
				RegistrantType.USER, app().users().getAllUsernames(), repositoryUsers, getAccessPermissions());
		final RegistrantPermissionsPanel teamsPalette = new RegistrantPermissionsPanel("teams",
				RegistrantType.TEAM, app().users().getAllTeamNames(), repositoryTeams, getAccessPermissions());

		// owners palette
		List<UserChoice> owners = new ArrayList<UserChoice>();
		List<UserChoice> persons = new ArrayList<UserChoice>();
		for (String owner : repositoryModel.owners) {
			UserModel o = app().users().getUserModel(owner);
			if (o != null) {
				owners.add(new UserChoice(o.getDisplayName(), o.username, o.emailAddress));
			} else {
				UserChoice userChoice = new UserChoice(owner);
				owners.add(userChoice);
				persons.add(userChoice);
			}
		}

		for (String person : app().users().getAllUsernames()) {
			UserModel o = app().users().getUserModel(person);
			if (o != null) {
				persons.add(new UserChoice(o.getDisplayName(), o.username, o.emailAddress));
			} else {
				persons.add(new UserChoice(person));
			}
		}
		final Palette<UserChoice> ownersPalette = new Palette<UserChoice>("owners", new ListModel<UserChoice>(owners), new CollectionModel<UserChoice>(
		      persons), new ChoiceRenderer<UserChoice>(null, "userId"), 12, false);

		// indexed local branches palette
		List<String> allLocalBranches = new ArrayList<String>();
		allLocalBranches.add(Constants.DEFAULT_BRANCH);
		allLocalBranches.addAll(repositoryModel.getLocalBranches());
		boolean luceneEnabled = app().settings().getBoolean(Keys.web.allowLuceneIndexing, true);
		final Palette<String> indexedBranchesPalette = new Palette<String>("indexedBranches", new ListModel<String>(
				indexedBranches), new CollectionModel<String>(allLocalBranches),
				new StringChoiceRenderer(), 8, false);
		indexedBranchesPalette.setEnabled(luceneEnabled);

		// federation sets palette
		List<String> sets = app().settings().getStrings(Keys.federation.sets);
		final Palette<String> federationSetsPalette = new Palette<String>("federationSets",
				new ListModel<String>(federationSets), new CollectionModel<String>(sets),
				new StringChoiceRenderer(), 8, false);

		// pre-receive palette
		if (!ArrayUtils.isEmpty(repositoryModel.preReceiveScripts)) {
			preReceiveScripts.addAll(repositoryModel.preReceiveScripts);
		}
		final Palette<String> preReceivePalette = new Palette<String>("preReceiveScripts",
				new ListModel<String>(preReceiveScripts), new CollectionModel<String>(app().repositories()
						.getPreReceiveScriptsUnused(repositoryModel)),
				new StringChoiceRenderer(), 12, true);

		// post-receive palette
		if (!ArrayUtils.isEmpty(repositoryModel.postReceiveScripts)) {
			postReceiveScripts.addAll(repositoryModel.postReceiveScripts);
		}
		final Palette<String> postReceivePalette = new Palette<String>("postReceiveScripts",
				new ListModel<String>(postReceiveScripts), new CollectionModel<String>(app().repositories()
						.getPostReceiveScriptsUnused(repositoryModel)),
				new StringChoiceRenderer(), 12, true);

		// custom fields
		final Map<String, String> customFieldsMap = app().settings().getMap(Keys.groovy.customFields);
		List<String> customKeys = new ArrayList<String>(customFieldsMap.keySet());
		final ListView<String> customFieldsListView = new ListView<String>("customFieldsListView", customKeys) {

			private static final long serialVersionUID = 1L;

			@Override
			protected void populateItem(ListItem<String> item) {
				String key = item.getModelObject();
				item.add(new Label("customFieldLabel", customFieldsMap.get(key)));

				String value = "";
				if (repositoryModel.customFields != null && repositoryModel.customFields.containsKey(key)) {
					value = repositoryModel.customFields.get(key);
				}
				TextField<String> field = new TextField<String>("customFieldValue", new Model<String>(value));
				item.add(field);
			}
		};
		customFieldsListView.setReuseItems(true);

		CompoundPropertyModel<RepositoryModel> rModel = new CompoundPropertyModel<RepositoryModel>(
				repositoryModel);
		Form<RepositoryModel> form = new Form<RepositoryModel>("editForm", rModel) {

			private static final long serialVersionUID = 1L;

			@Override
			protected void onSubmit() {
				try {
					if (!namePanel.updateModel(repositoryModel)) {
						return;
					}

					// confirm access restriction selection
					if (repositoryModel.accessRestriction == null) {
						error(getString("gb.selectAccessRestriction"));
						return;
					}

					// confirm federation strategy selection
					if (repositoryModel.federationStrategy == null) {
						error(getString("gb.selectFederationStrategy"));
						return;
					}

					// save federation set preferences
					if (repositoryModel.federationStrategy.exceeds(FederationStrategy.EXCLUDE)) {
						repositoryModel.federationSets.clear();
						Iterator<String> sets = federationSetsPalette.getSelectedChoices();
						while (sets.hasNext()) {
							repositoryModel.federationSets.add(sets.next());
						}
					}

					// set author metric exclusions
					String ax = metricAuthorExclusions.getObject();
					if (StringUtils.isEmpty(ax)) {
						repositoryModel.metricAuthorExclusions = new ArrayList<String>();
					} else {
						Set<String> list = new HashSet<String>();
						for (String exclusion : StringUtils.getStringsFromValue(ax,  " ")) {
							if (StringUtils.isEmpty(exclusion)) {
								continue;
							}
							if (exclusion.indexOf(' ') > -1) {
								list.add("\"" + exclusion + "\"");
							} else {
								list.add(exclusion);
							}
						}
						repositoryModel.metricAuthorExclusions = new ArrayList<String>(list);
					}

					// set mailing lists
					String ml = mailingLists.getObject();
					if (StringUtils.isEmpty(ml)) {
						repositoryModel.mailingLists = new ArrayList<String>();
					} else {
						Set<String> list = new HashSet<String>();
						for (String address : ml.split("(,|\\s)")) {
							if (StringUtils.isEmpty(address)) {
								continue;
							}
							list.add(address.toLowerCase());
						}
						repositoryModel.mailingLists = new ArrayList<String>(list);
					}

					// indexed branches
					List<String> indexedBranches = new ArrayList<String>();
					Iterator<String> branches = indexedBranchesPalette.getSelectedChoices();
					while (branches.hasNext()) {
						indexedBranches.add(branches.next());
					}
					repositoryModel.indexedBranches = indexedBranches;

					// owners
					repositoryModel.owners.clear();
					Iterator<UserChoice> owners = ownersPalette.getSelectedChoices();
					while (owners.hasNext()) {
						repositoryModel.addOwner(owners.next().getUserId());
					}

					// pre-receive scripts
					List<String> preReceiveScripts = new ArrayList<String>();
					Iterator<String> pres = preReceivePalette.getSelectedChoices();
					while (pres.hasNext()) {
						preReceiveScripts.add(pres.next());
					}
					repositoryModel.preReceiveScripts = preReceiveScripts;

					// post-receive scripts
					List<String> postReceiveScripts = new ArrayList<String>();
					Iterator<String> post = postReceivePalette.getSelectedChoices();
					while (post.hasNext()) {
						postReceiveScripts.add(post.next());
					}
					repositoryModel.postReceiveScripts = postReceiveScripts;

					// custom fields
					repositoryModel.customFields = new LinkedHashMap<String, String>();
					for (int i = 0; i < customFieldsListView.size(); i++) {
						ListItem<String> child = (ListItem<String>) customFieldsListView.get(i);
						String key = child.getModelObject();

						TextField<String> field = (TextField<String>) child.get("customFieldValue");
						String value = field.getValue();

						repositoryModel.customFields.put(key, value);
					}

					// save the repository
					app().gitblit().updateRepositoryModel(oldName, repositoryModel, isCreate);

					// repository access permissions
					if (repositoryModel.accessRestriction.exceeds(AccessRestrictionType.NONE)) {
						app().gitblit().setUserAccessPermissions(repositoryModel, repositoryUsers);
						app().gitblit().setTeamAccessPermissions(repositoryModel, repositoryTeams);
					}
				} catch (GitBlitException e) {
					error(e.getMessage());
					return;
				}
				setRedirect(false);
				setResponsePage(SummaryPage.class, WicketUtils.newRepositoryParameter(repositoryModel.name));
			}
		};

		// Determine available refs & branches
		List<String> availableRefs = new ArrayList<String>();
		List<String> availableBranches = new ArrayList<String>();
		if (!ArrayUtils.isEmpty(repositoryModel.availableRefs)) {
			for (String ref : repositoryModel.availableRefs) {
				if (!ref.startsWith(Constants.R_TICKET)) {
					availableRefs.add(ref);
					if (ref.startsWith(Constants.R_HEADS)) {
						availableBranches.add(Repository.shortenRefName(ref));
					}
				}
			}
		}

		// do not let the browser pre-populate these fields
		form.add(new SimpleAttributeModifier("autocomplete", "off"));

		// adds documentation link for localization
		form.add(new ExternalLink("commitMessageLink", COMMIT_MESSAGE_LINK));

		//
		//
		// GENERAL
		//
		namePanel = new RepositoryNamePanel("namePanel", repositoryModel);
		namePanel.setEditable(allowEditName);
		form.add(namePanel);

		// XXX AccessPolicyPanel is defined later.

		form.add(new ChoiceOption<String>("head",
				getString("gb.headRef"),
				getString("gb.headRefDescription"),
				new PropertyModel<String>(repositoryModel, "HEAD"),
				availableRefs));


		//
		// PERMISSIONS
		//
		form.add(ownersPalette);
		form.add(usersPalette);
		form.add(teamsPalette);

		//
		// TICKETS
		//
		form.add(new BooleanOption("acceptNewPatchsets",
				getString("gb.acceptNewPatchsets"),
				getString("gb.acceptNewPatchsetsDescription"),
				new PropertyModel<Boolean>(repositoryModel, "acceptNewPatchsets")));

		form.add(new BooleanOption("acceptNewTickets",
				getString("gb.acceptNewTickets"),
				getString("gb.acceptNewTicketsDescription"),
				new PropertyModel<Boolean>(repositoryModel, "acceptNewTickets")));

		form.add(new BooleanOption("requireApproval",
				getString("gb.requireApproval"),
				getString("gb.requireApprovalDescription"),
				new PropertyModel<Boolean>(repositoryModel, "requireApproval")));
		List<Integer> scores = Arrays.asList(-1, 0, 2, 4, 5, 6, 8);
		final boolean allowRequireScore = repositoryModel.requireApproval;
		form.add(new ChoiceOption<Integer>("requireScore",
				getString("gb.requireScore"),
				getString("gb.requireScoreDescription"),
				new DropDownChoice<Integer>("choice",
						new PropertyModel<Integer>(repositoryModel, "requireScore"),
						scores)).setEnabled(allowRequireScore));
		List<String> signoffCommitMsgs = Arrays.asList(
			null,
			"Signed-off-by",
			"Reviewed-by",
			"Acked-by"
		);
		form.add(new ChoiceOption<String>("writeSignoffCommit",
				getString("gb.writeSignoffCommit"),
				new String(),
				new PropertyModel<String>(repositoryModel, "writeSignoffCommit"),
				signoffCommitMsgs));
		form.add(new ChoiceOption<String>("mergeTo",
				getString("gb.mergeTo"),
				getString("gb.mergeToDescription"),
				new PropertyModel<String>(repositoryModel, "mergeTo"),
				availableBranches));
		form.add(new ChoiceOption<MergeType>("mergeType",
				getString("gb.mergeType"),
				getString("gb.mergeTypeDescription"),
				new PropertyModel<MergeType>(repositoryModel, "mergeType"),
				Arrays.asList(MergeType.values())));

		//
		// RECEIVE
		//
		form.add(new BooleanOption("isFrozen",
				getString("gb.isFrozen"),
				getString("gb.isFrozenDescription"),
				new PropertyModel<Boolean>(repositoryModel, "isFrozen")));

		form.add(new BooleanOption("incrementalPushTags",
				getString("gb.enableIncrementalPushTags"),
				getString("gb.useIncrementalPushTagsDescription"),
				new PropertyModel<Boolean>(repositoryModel, "useIncrementalPushTags")));

		final CheckBox verifyCommitter = new CheckBox("checkbox", new PropertyModel<Boolean>(repositoryModel, "verifyCommitter"));
		verifyCommitter.setOutputMarkupId(true);
		form.add(new BooleanOption("verifyCommitter",
				getString("gb.verifyCommitter"),
				getString("gb.verifyCommitterDescription") + "<br/>" + getString("gb.verifyCommitterNote"),
				verifyCommitter).setIsHtmlDescription(true));

		form.add(preReceivePalette);
		form.add(new BulletListPanel("inheritedPreReceive", getString("gb.inherited"), app().repositories()
				.getPreReceiveScriptsInherited(repositoryModel)));
		form.add(postReceivePalette);
		form.add(new BulletListPanel("inheritedPostReceive", getString("gb.inherited"), app().repositories()
				.getPostReceiveScriptsInherited(repositoryModel)));

		WebMarkupContainer customFieldsSection = new WebMarkupContainer("customFieldsSection");
		customFieldsSection.add(customFieldsListView);
		form.add(customFieldsSection.setVisible(!app().settings().getString(Keys.groovy.customFields, "").isEmpty()));

		//
		// FEDERATION
		//
		List<FederationStrategy> federationStrategies = new ArrayList<FederationStrategy>(
				Arrays.asList(FederationStrategy.values()));
		// federation strategies - remove ORIGIN choice if this repository has no origin.
		if (StringUtils.isEmpty(repositoryModel.origin)) {
			federationStrategies.remove(FederationStrategy.FEDERATE_ORIGIN);
		}

		form.add(new ChoiceOption<FederationStrategy>("federationStrategy",
				getString("gb.federationStrategy"),
				getString("gb.federationStrategyDescription"),
				new DropDownChoice<FederationStrategy>(
						"choice",
						new PropertyModel<FederationStrategy>(repositoryModel, "federationStrategy"),
						federationStrategies,
						new FederationTypeRenderer())));

		form.add(federationSetsPalette);

		//
		// SEARCH
		//
		form.add(indexedBranchesPalette);

		//
		// GARBAGE COLLECTION
		//
		boolean gcEnabled = app().settings().getBoolean(Keys.git.enableGarbageCollection, false);
		int defaultGcPeriod = app().settings().getInteger(Keys.git.defaultGarbageCollectionPeriod, 7);
		if (repositoryModel.gcPeriod == 0) {
			repositoryModel.gcPeriod = defaultGcPeriod;
		}
		List<Integer> gcPeriods = Arrays.asList(1, 2, 3, 4, 5, 7, 10, 14 );
		form.add(new ChoiceOption<Integer>("gcPeriod",
				getString("gb.gcPeriod"),
				getString("gb.gcPeriodDescription"),
				new DropDownChoice<Integer>("choice",
						new PropertyModel<Integer>(repositoryModel, "gcPeriod"),
						gcPeriods,
						new GCPeriodRenderer())).setEnabled(gcEnabled));

		form.add(new TextOption("gcThreshold",
				getString("gb.gcThreshold"),
				getString("gb.gcThresholdDescription"),
				"span1",
				new PropertyModel<String>(repositoryModel, "gcThreshold")).setEnabled(gcEnabled));

		//
		// MISCELLANEOUS
		//

		form.add(new TextOption("origin",
				getString("gb.origin"),
				getString("gb.originDescription"),
				"span6",
				new PropertyModel<String>(repositoryModel, "origin")).setEnabled(false));

		form.add(new BooleanOption("showRemoteBranches",
				getString("gb.showRemoteBranches"),
				getString("gb.showRemoteBranchesDescription"),
				new PropertyModel<Boolean>(repositoryModel, "showRemoteBranches")));

		form.add(new BooleanOption("skipSizeCalculation",
				getString("gb.skipSizeCalculation"),
				getString("gb.skipSizeCalculationDescription"),
				new PropertyModel<Boolean>(repositoryModel, "skipSizeCalculation")));

		form.add(new BooleanOption("skipSummaryMetrics",
				getString("gb.skipSummaryMetrics"),
				getString("gb.skipSummaryMetricsDescription"),
				new PropertyModel<Boolean>(repositoryModel, "skipSummaryMetrics")));

		List<Integer> maxActivityCommits  = Arrays.asList(-1, 0, 25, 50, 75, 100, 150, 200, 250, 500);
		form.add(new ChoiceOption<Integer>("maxActivityCommits",
				getString("gb.maxActivityCommits"),
				getString("gb.maxActivityCommitsDescription"),
				new DropDownChoice<Integer>("choice",
						new PropertyModel<Integer>(repositoryModel, "maxActivityCommits"),
						maxActivityCommits,
						new MaxActivityCommitsRenderer())));

		List<CommitMessageRenderer> renderers = Arrays.asList(CommitMessageRenderer.values());
		form.add(new ChoiceOption<CommitMessageRenderer>("commitMessageRenderer",
				getString("gb.commitMessageRenderer"),
				getString("gb.commitMessageRendererDescription"),
				new DropDownChoice<CommitMessageRenderer>("choice",
						new PropertyModel<CommitMessageRenderer>(repositoryModel, "commitMessageRenderer"),
						renderers)));

		metricAuthorExclusions = new Model<String>(ArrayUtils.isEmpty(repositoryModel.metricAuthorExclusions) ? ""
				: StringUtils.flattenStrings(repositoryModel.metricAuthorExclusions, " "));

		form.add(new TextOption("metricAuthorExclusions",
				getString("gb.metricAuthorExclusions"),
				getString("gb.metricAuthorExclusions"),
				"span6",
				metricAuthorExclusions));

		mailingLists = new Model<String>(ArrayUtils.isEmpty(repositoryModel.mailingLists) ? ""
				: StringUtils.flattenStrings(repositoryModel.mailingLists, " "));

		form.add(new TextOption("mailingLists",
				getString("gb.mailingLists"),
				getString("gb.mailingLists"),
				"span6",
				mailingLists));


		// initial enable/disable of permission controls
		if (repositoryModel.accessRestriction.equals(AccessRestrictionType.NONE)) {
			// anonymous everything, disable all controls
			usersPalette.setEnabled(false);
			teamsPalette.setEnabled(false);
			verifyCommitter.setEnabled(false);
		} else {
			// authenticated something
			// enable authorization controls
			verifyCommitter.setEnabled(true);

			boolean allowFineGrainedControls = repositoryModel.authorizationControl.equals(AuthorizationControl.NAMED);
			usersPalette.setEnabled(allowFineGrainedControls);
			teamsPalette.setEnabled(allowFineGrainedControls);
		}

		//
		// ACCESS POLICY PANEL (GENERAL)
		//
		AjaxFormChoiceComponentUpdatingBehavior callback = new AjaxFormChoiceComponentUpdatingBehavior() {

			private static final long serialVersionUID = 1L;

			@Override
			protected void onUpdate(AjaxRequestTarget target) {
				accessPolicyPanel.updateModel(repositoryModel);

				boolean allowAuthorizationControl = repositoryModel.accessRestriction.exceeds(AccessRestrictionType.NONE);
				verifyCommitter.setEnabled(allowAuthorizationControl);

				boolean allowFineGrainedControls = allowAuthorizationControl && repositoryModel.authorizationControl.equals(AuthorizationControl.NAMED);
				usersPalette.setEnabled(allowFineGrainedControls);
				teamsPalette.setEnabled(allowFineGrainedControls);

				if (allowFineGrainedControls) {
					repositoryModel.authorizationControl = AuthorizationControl.NAMED;
				}

				target.addComponent(verifyCommitter);
				target.addComponent(usersPalette);
				target.addComponent(teamsPalette);
			}
		};

		accessPolicyPanel = new AccessPolicyPanel("accessPolicyPanel", repositoryModel, callback);
		form.add(accessPolicyPanel);


		//
		// FORM CONTROLS
		//
		form.add(new Button("save"));
		Button cancel = new Button("cancel") {
			private static final long serialVersionUID = 1L;

			@Override
			public void onSubmit() {
				if (isCreate) {
					setResponsePage(RepositoriesPage.class);
				} else {
					setResponsePage(SummaryPage.class, WicketUtils.newRepositoryParameter(repositoryModel.name));
				}
			}
		};
		cancel.setDefaultFormProcessing(false);
		form.add(cancel);

		// the user can delete if deletions are allowed AND the user is an admin or the personal owner
		// assigned ownership is not sufficient to allow deletion
		boolean canDelete = !isCreate && app().repositories().canDelete(repositoryModel)
				&& (user.canAdmin() || user.isMyPersonalRepository(repositoryModel.name));

		Link<Void> delete = new Link<Void>("delete") {

			private static final long serialVersionUID = 1L;

			@Override
			public void onClick() {
				RepositoryModel latestModel = app().repositories().getRepositoryModel(repositoryModel.name);
				boolean canDelete = app().repositories().canDelete(latestModel);
				if (canDelete) {
					if (app().gitblit().deleteRepositoryModel(latestModel)) {
						info(MessageFormat.format(getString("gb.repositoryDeleted"), latestModel));
						if (latestModel.isPersonalRepository()) {
							// redirect to user's profile page
							String prefix = app().settings().getString(Keys.git.userRepositoryPrefix, "~");
							String username = latestModel.projectPath.substring(prefix.length());
							setResponsePage(UserPage.class, WicketUtils.newUsernameParameter(username));
						} else {
							// redirect to server repositories page
							setResponsePage(RepositoriesPage.class);
						}
					} else {
						error(MessageFormat.format(getString("gb.repositoryDeleteFailed"), latestModel));
					}
				} else {
					error(MessageFormat.format(getString("gb.repositoryDeleteFailed"), latestModel));
				}
			}
		};

		if (canDelete) {
			delete.add(new JavascriptEventConfirmation("onclick", MessageFormat.format(
				getString("gb.deleteRepository"), repositoryModel)));
		}
		form.add(delete.setVisible(canDelete));

		add(form);
	}

	/**
	 * Unfortunately must repeat part of AuthorizaitonStrategy here because that
	 * mechanism does not take PageParameters into consideration, only page
	 * instantiation.
	 *
	 * Repository Owners should be able to edit their repository.
	 */
	private void checkPermissions(RepositoryModel model) {
		boolean authenticateAdmin = app().settings().getBoolean(Keys.web.authenticateAdminPages, true);
		boolean allowAdmin = app().settings().getBoolean(Keys.web.allowAdministration, true);

		GitBlitWebSession session = GitBlitWebSession.get();
		UserModel user = session.getUser();

		if (allowAdmin) {
			if (authenticateAdmin) {
				if (user == null) {
					// No Login Available
					error(getString("gb.errorAdminLoginRequired"), true);
				}
				if (isCreate) {
					// Create Repository
					if (!user.canCreate() && !user.canAdmin()) {
						// Only administrators or permitted users may create
						error(getString("gb.errorOnlyAdminMayCreateRepository"), true);
					}
				} else {
					// Edit Repository
					if (user.canAdmin()) {
						// Admins can edit everything
						isAdmin = true;
						return;
					} else {
						if (!model.isOwner(user.username)) {
							// User is not an Admin nor Owner
							error(getString("gb.errorOnlyAdminOrOwnerMayEditRepository"), true);
						}
					}
				}
			}
		} else {
			// No Administration Permitted
			error(getString("gb.errorAdministrationDisabled"), true);
		}
	}


	private class FederationTypeRenderer implements IChoiceRenderer<FederationStrategy> {

		private static final long serialVersionUID = 1L;

		private final Map<FederationStrategy, String> map;

		public FederationTypeRenderer() {
			map = getFederationTypes();
		}

		@Override
		public String getDisplayValue(FederationStrategy type) {
			return map.get(type);
		}

		@Override
		public String getIdValue(FederationStrategy type, int index) {
			return Integer.toString(index);
		}
	}

	private class GCPeriodRenderer implements IChoiceRenderer<Integer> {

		private static final long serialVersionUID = 1L;

		public GCPeriodRenderer() {
		}

		@Override
		public String getDisplayValue(Integer value) {
			if (value == 1) {
				return getString("gb.duration.oneDay");
			} else {
				return MessageFormat.format(getString("gb.duration.days"), value);
			}
		}

		@Override
		public String getIdValue(Integer value, int index) {
			return Integer.toString(index);
		}
	}

	private class MaxActivityCommitsRenderer implements IChoiceRenderer<Integer> {

		private static final long serialVersionUID = 1L;

		public MaxActivityCommitsRenderer() {
		}

		@Override
		public String getDisplayValue(Integer value) {
			if (value == -1) {
				return getString("gb.excludeFromActivity");
			} else if (value == 0) {
				return getString("gb.noMaximum");
			} else {
				return value + " " + getString("gb.commits");
			}
		}

		@Override
		public String getIdValue(Integer value, int index) {
			return Integer.toString(index);
		}
	}
}
