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
package com.gitblit.models;

import java.io.Serializable;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.gitblit.Constants;
import com.gitblit.Constants.AccessPermission;
import com.gitblit.Constants.AccessRestrictionType;
import com.gitblit.Constants.AccountType;
import com.gitblit.Constants.AuthorizationControl;
import com.gitblit.Constants.PermissionType;
import com.gitblit.Constants.RegistrantType;
import com.gitblit.utils.ArrayUtils;
import com.gitblit.utils.ModelUtils;
import com.gitblit.utils.SecureRandom;
import com.gitblit.utils.StringUtils;

/**
 * UserModel is a serializable model class that represents a user and the user's
 * restricted repository memberships. Instances of UserModels are also used as
 * servlet user principals.
 *
 * @author James Moger
 *
 */
public class UserModel implements Principal, Serializable, Comparable<UserModel> {

	private static final long serialVersionUID = 1L;

	public static final UserModel ANONYMOUS = new UserModel();

	private static final SecureRandom RANDOM = new SecureRandom();

	// field names are reflectively mapped in EditUser page
	public String username;
	public String password;
	public String cookie;
	public String displayName;
	public String emailAddress;
	public String organizationalUnit;
	public String organization;
	public String locality;
	public String stateProvince;
	public String countryCode;
	public boolean canAdmin;
	public boolean canFork;
	public boolean canCreate;
	public boolean excludeFromFederation;
	public boolean disabled;
	// retained for backwards-compatibility with RPC clients
	@Deprecated
	public final Set<String> repositories = new HashSet<String>();
	public final Map<String, AccessPermission> permissions = new LinkedHashMap<String, AccessPermission>();
	public final Set<TeamModel> teams = new TreeSet<TeamModel>();

	// non-persisted fields
	public boolean isAuthenticated;
	public AccountType accountType;

	public UserPreferences userPreferences;

	public UserModel(String username) {
		this.username = username;
		this.isAuthenticated = true;
		this.accountType = AccountType.LOCAL;
		this.userPreferences = new UserPreferences(this.username);
	}

	private UserModel() {
		this.username = "$anonymous";
		this.isAuthenticated = false;
		this.accountType = AccountType.LOCAL;
		this.userPreferences = new UserPreferences(this.username);
	}

	public boolean isLocalAccount() {
		return !Constants.EXTERNAL_ACCOUNT.equals(password)
				|| accountType == null
				|| accountType.isLocal();
	}

	/**
	 * Returns a list of repository permissions for this user exclusive of
	 * permissions inherited from team memberships.
	 *
	 * @return the user's list of permissions
	 */
	public List<RegistrantAccessPermission> getRepositoryPermissions() {
		List<RegistrantAccessPermission> list = new ArrayList<RegistrantAccessPermission>();
		if (canAdmin()) {
			// user has REWIND access to all repositories
			return list;
		}
		for (Map.Entry<String, AccessPermission> entry : permissions.entrySet()) {
			String registrant = entry.getKey();
			AccessPermission ap = entry.getValue();
			String source = null;
			boolean mutable = true;
			PermissionType pType = PermissionType.EXPLICIT;
			if (isMyPersonalRepository(registrant)) {
				pType = PermissionType.OWNER;
				ap = AccessPermission.REWIND;
				mutable = false;
			} else if (StringUtils.findInvalidCharacter(registrant) != null) {
				// a regex will have at least 1 invalid character
				pType = PermissionType.REGEX;
				source = registrant;
			}
			list.add(new RegistrantAccessPermission(registrant, ap, pType, RegistrantType.REPOSITORY, source, mutable));
		}
		Collections.sort(list);

		// include immutable team permissions, being careful to preserve order
		Set<RegistrantAccessPermission> set = new LinkedHashSet<RegistrantAccessPermission>(list);
		ArrayList<RegistrantAccessPermission> arrayList = new ArrayList<RegistrantAccessPermission>(list);
		for (TeamModel team : teams) {
			for (RegistrantAccessPermission teamPermission : team.getRepositoryPermissions()) {
				// we can not change an inherited team permission, though we can override
				teamPermission.registrantType = RegistrantType.REPOSITORY;
				teamPermission.permissionType = PermissionType.TEAM;
				teamPermission.source = team.name;
				teamPermission.mutable = false;
                if(arrayList.contains(teamPermission) && arrayList.get(arrayList.indexOf(teamPermission)).permissionType != PermissionType.REGEX){
                    //checking either to replace permission in set or not
                    if(teamPermission.permission.compareTo(arrayList.get(arrayList.indexOf(teamPermission)).permission) > 0 ){
                        arrayList.remove(teamPermission);
                        arrayList.add(teamPermission);
                        set.remove(teamPermission);
                        set.add(teamPermission);
                    }
                }
                else{
                    arrayList.add(teamPermission);
                }
				set.add(teamPermission);
			}
		}
		return new ArrayList<RegistrantAccessPermission>(set);
	}

	/**
	 * Returns true if the user has any type of specified access permission for
	 * this repository.
	 *
	 * @param name
	 * @return true if user has a specified access permission for the repository
	 */
	public boolean hasRepositoryPermission(String name) {
		String repository = AccessPermission.repositoryFromRole(name).toLowerCase();
		if (permissions.containsKey(repository)) {
			// exact repository permission specified
			return true;
		} else {
			// search for regex permission match
			for (String key : permissions.keySet()) {
				if (name.matches(key)) {
					AccessPermission p = permissions.get(key);
					if (p != null) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/**
	 * Returns true if the user has an explicitly specified access permission for
	 * this repository.
	 *
	 * @param name
	 * @return if the user has an explicitly specified access permission
	 */
	public boolean hasExplicitRepositoryPermission(String name) {
		String repository = AccessPermission.repositoryFromRole(name).toLowerCase();
		return permissions.containsKey(repository);
	}

	/**
	 * Returns true if the user's team memberships specify an access permission for
	 * this repository.
	 *
	 * @param name
	 * @return if the user's team memberships specifi an access permission
	 */
	public boolean hasTeamRepositoryPermission(String name) {
		if (teams != null) {
			for (TeamModel team : teams) {
				if (team.hasRepositoryPermission(name)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Adds a repository permission to the team.
	 * <p>
	 * Role may be formatted as:
	 * <ul>
	 * <li> myrepo.git <i>(this is implicitly RW+)</i>
	 * <li> RW+:myrepo.git
	 * </ul>
	 * @param role
	 */
	public void addRepositoryPermission(String role) {
		AccessPermission permission = AccessPermission.permissionFromRole(role);
		String repository = AccessPermission.repositoryFromRole(role).toLowerCase();
		repositories.add(repository);
		permissions.put(repository, permission);
	}

	public AccessPermission removeRepositoryPermission(String name) {
		String repository = AccessPermission.repositoryFromRole(name).toLowerCase();
		repositories.remove(repository);
		return permissions.remove(repository);
	}

	public void setRepositoryPermission(String repository, AccessPermission permission) {
		if (permission == null) {
			// remove the permission
			permissions.remove(repository.toLowerCase());
		} else {
			// set the new permission
			permissions.put(repository.toLowerCase(), permission);
		}
	}

	public RegistrantAccessPermission getRepositoryPermission(RepositoryModel repository) {
		RegistrantAccessPermission ap = new RegistrantAccessPermission();
		ap.registrant = username;
		ap.registrantType = RegistrantType.USER;
		ap.permission = AccessPermission.NONE;
		ap.mutable = false;

		// determine maximum permission for the repository
		final AccessPermission maxPermission =
				(repository.isFrozen || !repository.isBare || repository.isMirror) ?
						AccessPermission.CLONE : AccessPermission.REWIND;

		if (AccessRestrictionType.NONE.equals(repository.accessRestriction)) {
			// anonymous rewind
			ap.permissionType = PermissionType.ANONYMOUS;
			if (AccessPermission.REWIND.atMost(maxPermission)) {
				ap.permission = AccessPermission.REWIND;
			} else {
				ap.permission = maxPermission;
			}
			return ap;
		}

		// administrator
		if (canAdmin()) {
			ap.permissionType = PermissionType.ADMINISTRATOR;
			if (AccessPermission.REWIND.atMost(maxPermission)) {
				ap.permission = AccessPermission.REWIND;
			} else {
				ap.permission = maxPermission;
			}
			if (!canAdmin) {
				// administator permission from team membership
				for (TeamModel team : teams) {
					if (team.canAdmin) {
						ap.source = team.name;
						break;
					}
				}
			}
			return ap;
		}

		// repository owner - either specified owner or personal repository
		if (repository.isOwner(username) || repository.isUsersPersonalRepository(username)) {
			ap.permissionType = PermissionType.OWNER;
			if (AccessPermission.REWIND.atMost(maxPermission)) {
				ap.permission = AccessPermission.REWIND;
			} else {
				ap.permission = maxPermission;
			}
			return ap;
		}

		if (AuthorizationControl.AUTHENTICATED.equals(repository.authorizationControl) && isAuthenticated) {
			// AUTHENTICATED is a shortcut for authorizing all logged-in users RW+ access
			if (AccessPermission.REWIND.atMost(maxPermission)) {
				ap.permission = AccessPermission.REWIND;
			} else {
				ap.permission = maxPermission;
			}
			return ap;
		}

		// explicit user permission OR user regex match is used
		// if that fails, then the best team permission is used
		if (permissions.containsKey(repository.name.toLowerCase())) {
			// exact repository permission specified, use it
			AccessPermission p = permissions.get(repository.name.toLowerCase());
			if (p != null && repository.accessRestriction.isValidPermission(p)) {
				ap.permissionType = PermissionType.EXPLICIT;
				if (p.atMost(maxPermission)) {
					ap.permission = p;
				} else {
					ap.permission = maxPermission;
				}
				ap.mutable = true;
				return ap;
			}
		} else {
			// search for case-insensitive regex permission match
			for (String key : permissions.keySet()) {
				if (StringUtils.matchesIgnoreCase(repository.name, key)) {
					AccessPermission p = permissions.get(key);
					if (p != null && repository.accessRestriction.isValidPermission(p)) {
						// take first match
						ap.permissionType = PermissionType.REGEX;
						if (p.atMost(maxPermission)) {
							ap.permission = p;
						} else {
							ap.permission = maxPermission;
						}
						ap.source = key;
						return ap;
					}
				}
			}
		}

		// try to find a team match
		for (TeamModel team : teams) {
			RegistrantAccessPermission p = team.getRepositoryPermission(repository);
			if (p.permission.atMost(maxPermission) && p.permission.exceeds(ap.permission) && PermissionType.ANONYMOUS != p.permissionType) {
				// use highest team permission that is not an implicit permission
				ap.permission = p.permission;
				ap.source = team.name;
				ap.permissionType = PermissionType.TEAM;
			}
		}

		// still no explicit, regex, or team match, check for implicit permissions
		if (AccessPermission.NONE == ap.permission) {
			switch (repository.accessRestriction) {
			case VIEW:
				// no implicit permissions possible
				break;
			case CLONE:
				// implied view permission
				ap.permission = AccessPermission.VIEW;
				ap.permissionType = PermissionType.ANONYMOUS;
				break;
			case PUSH:
				// implied clone permission
				ap.permission = AccessPermission.CLONE;
				ap.permissionType = PermissionType.ANONYMOUS;
				break;
			case NONE:
				// implied REWIND or CLONE
				ap.permission = maxPermission;
				ap.permissionType = PermissionType.ANONYMOUS;
				break;
			}
		}

		return ap;
	}

	protected boolean canAccess(RepositoryModel repository, AccessRestrictionType ifRestriction, AccessPermission requirePermission) {
		if (repository.accessRestriction.atLeast(ifRestriction)) {
			RegistrantAccessPermission ap = getRepositoryPermission(repository);
			return ap.permission.atLeast(requirePermission);
		}
		return true;
	}

	public boolean canView(RepositoryModel repository) {
		return canAccess(repository, AccessRestrictionType.VIEW, AccessPermission.VIEW);
	}

	public boolean canView(RepositoryModel repository, String ref) {
		// Default UserModel doesn't implement ref-level security.
		// Other Realms (i.e. Gerrit) may override this method.
		return canView(repository);
	}

	public boolean canClone(RepositoryModel repository) {
		return canAccess(repository, AccessRestrictionType.CLONE, AccessPermission.CLONE);
	}

	public boolean canPush(RepositoryModel repository) {
		if (repository.isFrozen) {
			return false;
		}
		return canAccess(repository, AccessRestrictionType.PUSH, AccessPermission.PUSH);
	}

	public boolean canCreateRef(RepositoryModel repository) {
		if (repository.isFrozen) {
			return false;
		}
		return canAccess(repository, AccessRestrictionType.PUSH, AccessPermission.CREATE);
	}

	public boolean canDeleteRef(RepositoryModel repository) {
		if (repository.isFrozen) {
			return false;
		}
		return canAccess(repository, AccessRestrictionType.PUSH, AccessPermission.DELETE);
	}

	public boolean canRewindRef(RepositoryModel repository) {
		if (repository.isFrozen) {
			return false;
		}
		return canAccess(repository, AccessRestrictionType.PUSH, AccessPermission.REWIND);
	}

	public boolean canFork(RepositoryModel repository) {
		if (repository.isUsersPersonalRepository(username)) {
			// can not fork your own repository
			return false;
		}
		if (canAdmin() || repository.isOwner(username)) {
			return true;
		}
		if (!repository.allowForks) {
			return false;
		}
		if (!isAuthenticated || !canFork()) {
			return false;
		}
		return canClone(repository);
	}

	public boolean canDelete(RepositoryModel model) {
		return canAdmin() || model.isUsersPersonalRepository(username);
	}

	public boolean canEdit(RepositoryModel model) {
		return canAdmin() || model.isUsersPersonalRepository(username) || model.isOwner(username);
	}

	public boolean canEdit(TicketModel ticket, RepositoryModel repository) {
		 return isAuthenticated() &&
				 (canPush(repository)
				 || (ticket != null && username.equals(ticket.responsible))
				 || (ticket != null && username.equals(ticket.createdBy)));
	}

	public boolean canAdmin(TicketModel ticket, RepositoryModel repository) {
		 return isAuthenticated() &&
				 (canPush(repository)
				 || ticket != null && username.equals(ticket.responsible));
	}

	public boolean canReviewPatchset(RepositoryModel model) {
		return isAuthenticated() && canClone(model);
	}

	public boolean canApprovePatchset(RepositoryModel model) {
		return isAuthenticated() && canPush(model);
	}

	public boolean canVetoPatchset(RepositoryModel model) {
		return isAuthenticated() && canPush(model);
	}

	/**
	 * This returns true if the user has fork privileges or the user has fork
	 * privileges because of a team membership.
	 *
	 * @return true if the user can fork
	 */
	public boolean canFork() {
		if (canFork) {
			return true;
		}
		if (!ArrayUtils.isEmpty(teams)) {
			for (TeamModel team : teams) {
				if (team.canFork) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * This returns true if the user has admin privileges or the user has admin
	 * privileges because of a team membership.
	 *
	 * @return true if the user can admin
	 */
	public boolean canAdmin() {
		if (canAdmin) {
			return true;
		}
		if (!ArrayUtils.isEmpty(teams)) {
			for (TeamModel team : teams) {
				if (team.canAdmin) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * This returns true if the user has create privileges or the user has create
	 * privileges because of a team membership.
	 *
	 * @return true if the user can admin
	 */
	public boolean canCreate() {
		if (canCreate) {
			return true;
		}
		if (!ArrayUtils.isEmpty(teams)) {
			for (TeamModel team : teams) {
				if (team.canCreate) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Returns true if the user is allowed to create the specified repository.
	 *
	 * @param repository
	 * @return true if the user can create the repository
	 */
	public boolean canCreate(String repository) {
		if (canAdmin()) {
			// admins can create any repository
			return true;
		}
		if (canCreate()) {
			String projectPath = StringUtils.getFirstPathElement(repository);
			if (!StringUtils.isEmpty(projectPath) && projectPath.equalsIgnoreCase(getPersonalPath())) {
				// personal repository
				return true;
			}
		}
		return false;
	}

	/**
	 * Returns true if the user is allowed to administer the specified repository
	 *
	 * @param repo
	 * @return true if the user can administer the repository
	 */
	public boolean canAdmin(RepositoryModel repo) {
		return canAdmin() || repo.isOwner(username) || isMyPersonalRepository(repo.name);
	}

	public boolean isAuthenticated() {
		return !UserModel.ANONYMOUS.equals(this) && isAuthenticated;
	}

	public boolean isTeamMember(String teamname) {
		for (TeamModel team : teams) {
			if (team.name.equalsIgnoreCase(teamname)) {
				return true;
			}
		}
		return false;
	}

	public TeamModel getTeam(String teamname) {
		if (teams == null) {
			return null;
		}
		for (TeamModel team : teams) {
			if (team.name.equalsIgnoreCase(teamname)) {
				return team;
			}
		}
		return null;
	}

	@Override
	public String getName() {
		return username;
	}

	public String getDisplayName() {
		if (StringUtils.isEmpty(displayName)) {
			return username;
		}
		return displayName;
	}

	public String getPersonalPath() {
		return ModelUtils.getPersonalPath(username);
	}

	public UserPreferences getPreferences() {
		return userPreferences;
	}

	@Override
	public int hashCode() {
		return username.hashCode();
	}

	@Override
	public boolean equals(Object o) {
		if (o instanceof UserModel) {
			return username.equals(((UserModel) o).username);
		}
		return false;
	}

	@Override
	public String toString() {
		return username;
	}

	@Override
	public int compareTo(UserModel o) {
		return username.compareTo(o.username);
	}

	/**
	 * Returns true if the name/email pair match this user account.
	 *
	 * @param name
	 * @param email
	 * @return true, if the name and email address match this account
	 */
	public boolean is(String name, String email) {
		// at a minimum a username or display name AND email address must be supplied
		if (StringUtils.isEmpty(name) || StringUtils.isEmpty(email)) {
			return false;
		}
		boolean nameVerified = name.equalsIgnoreCase(username) || name.equalsIgnoreCase(getDisplayName());
		boolean emailVerified = false;
		if (StringUtils.isEmpty(emailAddress)) {
			// user account has not specified an email address
			// fail
			emailVerified = false;
		} else {
			// user account has specified an email address
			emailVerified = email.equalsIgnoreCase(emailAddress);
		}
		return nameVerified && emailVerified;
	}

	public boolean isMyPersonalRepository(String repository) {
		String projectPath = StringUtils.getFirstPathElement(repository);
		return !StringUtils.isEmpty(projectPath) && projectPath.equalsIgnoreCase(getPersonalPath());
	}

	public String createCookie() {
		return StringUtils.getSHA1(RANDOM.randomBytes(32));
	}
}
