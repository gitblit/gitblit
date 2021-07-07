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
package com.gitblit.auth;

import java.io.File;
import java.math.BigInteger;

import javax.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gitblit.Constants.AccountType;
import com.gitblit.Constants.Role;
import com.gitblit.Constants.AuthenticationType;
import com.gitblit.IStoredSettings;
import com.gitblit.Keys;
import com.gitblit.manager.IRuntimeManager;
import com.gitblit.manager.IUserManager;
import com.gitblit.models.TeamModel;
import com.gitblit.models.UserModel;
import com.gitblit.utils.ArrayUtils;
import com.gitblit.utils.DeepCopier;
import com.gitblit.utils.StringUtils;

public abstract class AuthenticationProvider {

	public static NullProvider NULL_PROVIDER = new NullProvider();

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    protected final String serviceName;

	protected File baseFolder;

	protected IStoredSettings settings;

	protected IRuntimeManager runtimeManager;

	protected IUserManager userManager;

	protected AuthenticationProvider(String serviceName) {
		this.serviceName = serviceName;
	}

	/**
	 * Returns the file object for the specified configuration key.
	 *
	 * @return the file
	 */
	public File getFileOrFolder(String key, String defaultFileOrFolder) {
		return runtimeManager.getFileOrFolder(key, defaultFileOrFolder);
	}

	public final void setup(IRuntimeManager runtimeManager, IUserManager userManager) {
		this.baseFolder = runtimeManager.getBaseFolder();
		this.settings = runtimeManager.getSettings();
		this.runtimeManager = runtimeManager;
		this.userManager = userManager;
		setup();
	}

	public String getServiceName() {
		return serviceName;
	}

	public abstract AuthenticationType getAuthenticationType();

	protected void setCookie(UserModel user) {
		// create a user cookie
		if (StringUtils.isEmpty(user.cookie)) {
			user.cookie = user.createCookie();
		}
	}

	protected void updateUser(UserModel userModel) {
		final UserModel userLocalDB = userManager.getUserModel(userModel.getName());

		String loginedUserDefaultTeam = settings.getString(Keys.realm.loginedUserDefaultTeam,null);
		if(!StringUtils.isEmpty(loginedUserDefaultTeam)){
			TeamModel defaultTeam = userManager.getTeamModel(loginedUserDefaultTeam);
			if( defaultTeam != null ) {
				userModel.teams.add(defaultTeam);
			}
		}
		// Establish the checksum of the current version of the user
		final BigInteger userCurrentCheck = DeepCopier.checksum(userModel);

		// Establish the checksum of the stored version of the user
		final BigInteger userLocalDBcheck = DeepCopier.checksum(userLocalDB);

		
		// Compare the checksums
		if (!userCurrentCheck.equals(userLocalDBcheck)) {
			// If mismatch, save the new instance.
			userManager.updateUserModel(userModel);
		}
		
	
	}

	protected void updateTeam(TeamModel teamModel) {
		final TeamModel teamLocalDB = userManager.getTeamModel(teamModel.name);

		// Establish the checksum of the current version of the team
		final BigInteger teamCurrentCheck = DeepCopier.checksum(teamModel);

		// Establish the checksum of the stored version of the team
		final BigInteger teamLocalDBcheck = DeepCopier.checksum(teamLocalDB);

		// Compare the checksums
		if (!teamCurrentCheck.equals(teamLocalDBcheck)) {
			// If mismatch, save the new instance.
			userManager.updateTeamModel(teamModel);
		}
	}

	public abstract void setup();

	public abstract void stop();

	/**
	 * Used to handle requests for requests for pages requiring authentication.
	 * This allows authentication to occur based on the contents of the request
	 * itself.
	 *
	 * @param httpRequest
	 * @return
	 */
	public abstract UserModel authenticate(HttpServletRequest httpRequest);

	/**
	 * Used to authentication user/password credentials, both for login form
	 * and HTTP Basic authentication processing.
	 *
	 * @param username
	 * @param password
	 * @return
	 */
	public abstract UserModel authenticate(String username, char[] password);

	public abstract AccountType getAccountType();

	/**
	 * Returns true if the users's credentials can be changed.
	 *
	 * @return true if the authentication provider supports credential changes
	 * @since 1.0.0
	 */
	public abstract boolean supportsCredentialChanges();

	/**
	 * Returns true if the user's display name can be changed.
	 *
	 * @param user
	 * @return true if the authentication provider supports display name changes
	 */
	public abstract boolean supportsDisplayNameChanges();

	/**
	 * Returns true if the user's email address can be changed.
	 *
	 * @param user
	 * @return true if the authentication provider supports email address changes
	 */
	public abstract boolean supportsEmailAddressChanges();

	/**
	 * Returns true if the user's team memberships can be changed.
	 *
	 * @param user
	 * @return true if the authentication provider supports team membership changes
	 */
	public abstract boolean supportsTeamMembershipChanges();

	/**
	 * Returns true if the user's role can be changed.
	 *
	 * @param user
	 * @param role
	 * @return true if the user's role can be changed
	 */
	public abstract boolean supportsRoleChanges(UserModel user, Role role);

	/**
	 * Returns true if the team's role can be changed.
	 *
	 * @param user
	 * @param role
	 * @return true if the team's role can be changed
	 */
	public abstract boolean supportsRoleChanges(TeamModel team, Role role);

    @Override
    public String toString() {
    	return getServiceName() + " (" + getClass().getName() + ")";
    }

    public abstract static class UsernamePasswordAuthenticationProvider extends AuthenticationProvider {
    	protected UsernamePasswordAuthenticationProvider(String serviceName) {
    		super(serviceName);
    	}

		@Override
		public UserModel authenticate(HttpServletRequest httpRequest) {
			return null;
		}

		@Override
		public AuthenticationType getAuthenticationType() {
			return AuthenticationType.CREDENTIALS;
		}

    	@Override
		public void stop() {

		}
    }

    public static class NullProvider extends AuthenticationProvider {

		protected NullProvider() {
			super("NULL");
		}

		@Override
		public void setup() {

		}

		@Override
		public void stop() {

		}

		@Override
		public UserModel authenticate(HttpServletRequest httpRequest) {
			return null;
		}

		@Override
		public UserModel authenticate(String username, char[] password) {
			return null;
		}

		@Override
		public AccountType getAccountType() {
			return AccountType.LOCAL;
		}

		@Override
		public AuthenticationType getAuthenticationType() {
			return null;
		}

		@Override
		public boolean supportsCredentialChanges() {
			return true;
		}

		@Override
		public boolean supportsDisplayNameChanges() {
			return true;
		}

		@Override
		public boolean supportsEmailAddressChanges() {
			return true;
		}

		@Override
		public boolean supportsTeamMembershipChanges() {
			return true;
		}

		@Override
		public boolean supportsRoleChanges(UserModel user, Role role) {
			return true;
		}

		@Override
		public boolean supportsRoleChanges(TeamModel team, Role role) {
			return true;
		}

    }
}
