/*
 * Copyright 2012 gitblit.com.
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.apache.wicket.PageParameters;
import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.ajax.markup.html.form.AjaxButton;
import org.apache.wicket.markup.html.basic.Label;
import org.apache.wicket.markup.html.form.Form;
import org.apache.wicket.markup.html.panel.Fragment;
import org.apache.wicket.markup.repeater.Item;
import org.apache.wicket.markup.repeater.data.DataView;
import org.apache.wicket.markup.repeater.data.ListDataProvider;
import org.apache.wicket.model.IModel;
import org.apache.wicket.model.Model;

import com.gitblit.Constants.Transport;
import com.gitblit.GitBlitException;
import com.gitblit.Keys;
import com.gitblit.models.Menu.ParameterMenuItem;
import com.gitblit.models.NavLink;
import com.gitblit.models.NavLink.DropDownPageMenuNavLink;
import com.gitblit.models.ProjectModel;
import com.gitblit.models.RepositoryModel;
import com.gitblit.models.UserModel;
import com.gitblit.utils.StringUtils;
import com.gitblit.wicket.GitBlitWebApp;
import com.gitblit.wicket.GitBlitWebSession;
import com.gitblit.wicket.GitblitRedirectException;
import com.gitblit.wicket.WicketUtils;
import com.gitblit.wicket.panels.BooleanOption;
import com.gitblit.wicket.panels.ChoiceOption;
import com.gitblit.wicket.panels.ProjectRepositoryPanel;
import com.gitblit.wicket.panels.SshKeysPanel;
import com.gitblit.wicket.panels.TextOption;
import com.gitblit.wicket.panels.UserTitlePanel;

public class UserPage extends RootPage {

	List<ProjectModel> projectModels = new ArrayList<ProjectModel>();

	public UserPage() {
		super();
		throw new GitblitRedirectException(GitBlitWebApp.get().getHomePage());
	}

	public UserPage(PageParameters params) {
		super(params);
		setup(params);
	}

	@Override
	protected boolean reusePageParameters() {
		return true;
	}

	private void setup(PageParameters params) {
		setupPage("", "");
		// check to see if we should display a login message
		boolean authenticateView = app().settings().getBoolean(Keys.web.authenticateViewPages, true);
		if (authenticateView && !GitBlitWebSession.get().isLoggedIn()) {
			authenticationError("Please login");
			return;
		}

		String userName = WicketUtils.getUsername(params);
		if (StringUtils.isEmpty(userName)) {
			throw new GitblitRedirectException(GitBlitWebApp.get().getHomePage());
		}

		UserModel user = app().users().getUserModel(userName);
		if (user == null) {
			// construct a temporary user model
			user = new UserModel(userName);
		}


		add(new UserTitlePanel("userTitlePanel", user, user.username));

		UserModel sessionUser = GitBlitWebSession.get().getUser();
		boolean isMyProfile = sessionUser != null && sessionUser.equals(user);

		if (isMyProfile) {
			addPreferences(user);

			if (app().services().isServingSSH()) {
				// show the SSH key management tab
				addSshKeys(user);
			} else {
				// SSH daemon is disabled, hide keys tab
				add(new Label("sshKeysLink").setVisible(false));
				add(new Label("sshKeysTab").setVisible(false));
			}
		} else {
			// visiting user
			add(new Label("preferencesLink").setVisible(false));
			add(new Label("preferencesTab").setVisible(false));

			add(new Label("sshKeysLink").setVisible(false));
			add(new Label("sshKeysTab").setVisible(false));
		}

		List<RepositoryModel> repositories = getRepositories(params);

		Collections.sort(repositories, new Comparator<RepositoryModel>() {
			@Override
			public int compare(RepositoryModel o1, RepositoryModel o2) {
				// reverse-chronological sort
				return o2.lastChange.compareTo(o1.lastChange);
			}
		});

		final ListDataProvider<RepositoryModel> dp = new ListDataProvider<RepositoryModel>(repositories);
		DataView<RepositoryModel> dataView = new DataView<RepositoryModel>("repositoryList", dp) {
			private static final long serialVersionUID = 1L;

			@Override
			public void populateItem(final Item<RepositoryModel> item) {
				final RepositoryModel entry = item.getModelObject();

				ProjectRepositoryPanel row = new ProjectRepositoryPanel("repository",
						getLocalizer(), this, showAdmin, entry, getAccessRestrictions());
				item.add(row);
			}
		};
		add(dataView);
	}

	@Override
	protected void addDropDownMenus(List<NavLink> navLinks) {
		PageParameters params = getPageParameters();

		DropDownPageMenuNavLink menu = new DropDownPageMenuNavLink("gb.filters",
				UserPage.class);
		// preserve time filter option on repository choices
		menu.menuItems.addAll(getRepositoryFilterItems(params));

		// preserve repository filter option on time choices
		menu.menuItems.addAll(getTimeFilterItems(params));

		if (menu.menuItems.size() > 0) {
			// Reset Filter
			menu.menuItems.add(new ParameterMenuItem(getString("gb.reset")));
		}

		navLinks.add(menu);
	}
	
	static List<Language> getLanguages(){
		return  Arrays.asList(
				new Language("Русский","ru"),
				new Language("Česky","cs"),
				new Language("Deutsch","de"),
				new Language("English","en"),
				new Language("Español", "es"),
				new Language("Français", "fr"),
				new Language("Italiano", "it"),
				new Language("日本語", "ja"),
				new Language("한국말", "ko"),
				new Language("Nederlands", "nl"),
				new Language("Norsk", "no"),
				new Language("Język Polski", "pl"),
				new Language("Português", "pt_BR"),
				new Language("簡體中文", "zh_CN"),
				new Language("正體中文", "zh_TW"));
	}

	static Language getPreferredLanguage(Locale locale, List<Language> languages) {
		Language preferredLanguage = null;
		if (locale != null) {
			String localeCode = locale.getLanguage();
			if (!StringUtils.isEmpty(locale.getCountry())) {
				localeCode += "_" + locale.getCountry();
			}

			for (Language language : languages) {
				if (language.code.equals(localeCode)) {
					// language_COUNTRY match
					preferredLanguage = language;
				} else if (preferredLanguage == null && language.code.startsWith(locale.getLanguage())) {
					// language match
					preferredLanguage = language;
				}
			}
		}
		return preferredLanguage;
	}

	private void addPreferences(UserModel user) {
		// add preferences
		Form<Void> prefs = new Form<Void>("prefsForm");

		Locale locale = user.getPreferences().getLocale();
		if (locale == null) {
			// user has not specified language preference
			// try server default preference
			String lc = app().settings().getString(Keys.web.forceDefaultLocale, null);
			if (StringUtils.isEmpty(lc)) {
				// server default language is not configured
				// try browser preference
				Locale sessionLocale = GitBlitWebSession.get().getLocale();
				if (sessionLocale != null) {
					locale = sessionLocale;
				}
			}
		}

		List<Language> languages = getLanguages();
		Language preferredLanguage = getPreferredLanguage(locale, languages);

		final IModel<String> displayName = Model.of(user.getDisplayName());
		final IModel<String> emailAddress = Model.of(user.emailAddress == null ? "" : user.emailAddress);
		final IModel<Language> language = Model.of(preferredLanguage);
		final IModel<Boolean> emailMeOnMyTicketChanges = Model.of(user.getPreferences().isEmailMeOnMyTicketChanges());
		final IModel<Transport> transport = Model.of(user.getPreferences().getTransport());

		prefs.add(new TextOption("displayName",
				getString("gb.displayName"),
				getString("gb.displayNameDescription"),
				displayName).setVisible(app().authentication().supportsDisplayNameChanges(user)));

		prefs.add(new TextOption("emailAddress",
				getString("gb.emailAddress"),
				getString("gb.emailAddressDescription"),
				emailAddress).setVisible(app().authentication().supportsEmailAddressChanges(user)));

		prefs.add(new ChoiceOption<Language>("language",
				getString("gb.languagePreference"),
				getString("gb.languagePreferenceDescription"),
				language,
				languages));

		prefs.add(new BooleanOption("emailMeOnMyTicketChanges",
				getString("gb.emailMeOnMyTicketChanges"),
				getString("gb.emailMeOnMyTicketChangesDescription"),
				emailMeOnMyTicketChanges).setVisible(app().notifier().isSendingMail()));

		List<Transport> availableTransports = new ArrayList<>();
		if (app().services().isServingSSH()) {
			availableTransports.add(Transport.SSH);
		}
		if (app().services().isServingHTTP()) {
			availableTransports.add(Transport.HTTP);
		}
		if (app().services().isServingHTTPS()) {
			availableTransports.add(Transport.HTTPS);
		}
		if (app().services().isServingGIT()) {
			availableTransports.add(Transport.GIT);
		}

		prefs.add(new ChoiceOption<Transport>("transport",
				getString("gb.transportPreference"),
				getString("gb.transportPreferenceDescription"),
				transport,
				availableTransports));

		prefs.add(new AjaxButton("save") {

			private static final long serialVersionUID = 1L;

			@Override
			protected void onSubmit(AjaxRequestTarget target, Form<?> form) {

				UserModel user = GitBlitWebSession.get().getUser();

				user.displayName = displayName.getObject();
				user.emailAddress = emailAddress.getObject();

				Language lang = language.getObject();
				if (lang != null) {
					user.getPreferences().setLocale(lang.code);
				}

				user.getPreferences().setEmailMeOnMyTicketChanges(emailMeOnMyTicketChanges.getObject());
				user.getPreferences().setTransport(transport.getObject());

				try {
					app().gitblit().reviseUser(user.username, user);

					setRedirect(true);
					setResponsePage(UserPage.class, WicketUtils.newUsernameParameter(user.username));
				} catch (GitBlitException e) {
					// logger.error("Failed to update user " + user.username, e);
					// error(getString("gb.failedToUpdateUser"), false);
				}
			}
		});

		// add the preferences tab
		add(new Fragment("preferencesLink", "preferencesLinkFragment", this).setRenderBodyOnly(true));
		Fragment fragment = new Fragment("preferencesTab", "preferencesTabFragment", this);
		fragment.add(prefs);
		add(fragment.setRenderBodyOnly(true));
	}

	private void addSshKeys(final UserModel user) {
		Fragment keysTab = new Fragment("sshKeysTab", "sshKeysTabFragment", this);
		keysTab.add(new SshKeysPanel("sshKeysPanel", user));

		// add the SSH keys tab
		add(new Fragment("sshKeysLink", "sshKeysLinkFragment", this).setRenderBodyOnly(true));
		add(keysTab.setRenderBodyOnly(true));
	}
}
