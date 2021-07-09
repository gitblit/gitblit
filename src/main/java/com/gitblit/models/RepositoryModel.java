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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.gitblit.Constants.AccessRestrictionType;
import com.gitblit.Constants.AuthorizationControl;
import com.gitblit.Constants.CommitMessageRenderer;
import com.gitblit.Constants.FederationStrategy;
import com.gitblit.Constants.MergeType;
import com.gitblit.utils.ArrayUtils;
import com.gitblit.utils.ModelUtils;
import com.gitblit.utils.StringUtils;

/**
 * RepositoryModel is a serializable model class that represents a Gitblit
 * repository including its configuration settings and access restriction.
 *
 * @author James Moger
 *
 */
public class RepositoryModel implements Serializable, Comparable<RepositoryModel> {

	private static final long serialVersionUID = 1L;

	// field names are reflectively mapped in EditRepository page
	public String name;
	public String description;
	public List<String> owners;
	public Date lastChange;
	public String lastChangeAuthor;
	public boolean hasCommits;
	public boolean showRemoteBranches;
	public boolean useIncrementalPushTags;
	public String incrementalPushTagPrefix;
	public AccessRestrictionType accessRestriction;
	public AuthorizationControl authorizationControl;
	public boolean allowAuthenticated;
	public boolean isFrozen;
	public FederationStrategy federationStrategy;
	public List<String> federationSets;
	public boolean isFederated;
	public boolean skipSizeCalculation;
	public boolean skipSummaryMetrics;
	public String frequency;
	public boolean isBare;
	public boolean isMirror;
	public String origin;
	public String HEAD;
	public List<String> availableRefs;
	public List<String> indexedBranches;
	public String size;
	public List<String> preReceiveScripts;
	public List<String> postReceiveScripts;
	public List<String> mailingLists;
	public Map<String, String> customFields;
	public String projectPath;
	private String displayName;
	public boolean allowForks;
	public Set<String> forks;
	public String originRepository;
	public boolean verifyCommitter;
	public String gcThreshold;
	public int gcPeriod;
	public int maxActivityCommits;
	public List<String> metricAuthorExclusions;
	public CommitMessageRenderer commitMessageRenderer;
	public boolean acceptNewPatchsets;
	public boolean acceptNewTickets;
	public boolean requireApproval;
	public int requireScore;
	public String writeSignoffCommit;
	public String mergeTo;
	public MergeType mergeType;

	public transient boolean isCollectingGarbage;
	public Date lastGC;
	public String sparkleshareId;

	public RepositoryModel() {
		this("", "", "", new Date(0));
	}

	public RepositoryModel(String name, String description, String owner, Date lastchange) {
		this.name = name;
		this.description = description;
		this.lastChange = lastchange;
		this.accessRestriction = AccessRestrictionType.NONE;
		this.authorizationControl = AuthorizationControl.NAMED;
		this.federationSets = new ArrayList<String>();
		this.federationStrategy = FederationStrategy.FEDERATE_THIS;
		this.projectPath = StringUtils.getFirstPathElement(name);
		this.owners = new ArrayList<String>();
		this.isBare = true;
		this.acceptNewTickets = true;
		this.acceptNewPatchsets = true;
		this.mergeType = MergeType.DEFAULT_MERGE_TYPE;

		addOwner(owner);
	}

	public List<String> getLocalBranches() {
		if (ArrayUtils.isEmpty(availableRefs)) {
			return new ArrayList<String>();
		}
		List<String> localBranches = new ArrayList<String>();
		for (String ref : availableRefs) {
			if (ref.startsWith("refs/heads")) {
				localBranches.add(ref);
			}
		}
		return localBranches;
	}

	public void addFork(String repository) {
		if (forks == null) {
			forks = new TreeSet<String>();
		}
		forks.add(repository);
	}

	public void removeFork(String repository) {
		if (forks == null) {
			return;
		}
		forks.remove(repository);
	}

	public void resetDisplayName() {
		displayName = null;
	}

	public String getRID() {
		return StringUtils.getSHA1(name);
	}

	@Override
	public int hashCode() {
		return name.hashCode();
	}

	@Override
	public boolean equals(Object o) {
		if (o instanceof RepositoryModel) {
			return name.equals(((RepositoryModel) o).name);
		}
		return false;
	}

	@Override
	public String toString() {
		if (displayName == null) {
			displayName = StringUtils.stripDotGit(name);
		}
		return displayName;
	}

	@Override
	public int compareTo(RepositoryModel o) {
		return StringUtils.compareRepositoryNames(name, o.name);
	}

	public boolean isFork() {
		return !StringUtils.isEmpty(originRepository);
	}

	public boolean isOwner(String username) {
		if (StringUtils.isEmpty(username) || ArrayUtils.isEmpty(owners)) {
			return isUsersPersonalRepository(username);
		}
		return owners.contains(username.toLowerCase()) || isUsersPersonalRepository(username);
	}

	public boolean isPersonalRepository() {
		return !StringUtils.isEmpty(projectPath) && ModelUtils.isPersonalRepository(projectPath);
	}

	public boolean isUsersPersonalRepository(String username) {
		return !StringUtils.isEmpty(projectPath) && ModelUtils.isUsersPersonalRepository(username, projectPath);
	}

	public boolean allowAnonymousView() {
		return !accessRestriction.atLeast(AccessRestrictionType.VIEW);
	}

	public boolean isShowActivity() {
		return maxActivityCommits > -1;
	}

	public boolean isSparkleshared() {
		return !StringUtils.isEmpty(sparkleshareId);
	}

	public RepositoryModel cloneAs(String cloneName) {
		RepositoryModel clone = new RepositoryModel();
		clone.originRepository = name;
		clone.name = cloneName;
		clone.projectPath = StringUtils.getFirstPathElement(cloneName);
		clone.isBare = true;
		clone.description = description;
		clone.accessRestriction = AccessRestrictionType.PUSH;
		clone.authorizationControl = AuthorizationControl.NAMED;
		clone.federationStrategy = federationStrategy;
		clone.showRemoteBranches = false;
		clone.allowForks = false;
		clone.acceptNewPatchsets = false;
		clone.acceptNewTickets = false;
		clone.skipSizeCalculation = skipSizeCalculation;
		clone.skipSummaryMetrics = skipSummaryMetrics;
		clone.sparkleshareId = sparkleshareId;
		return clone;
	}

	public void addOwner(String username) {
		if (!StringUtils.isEmpty(username)) {
			String name = username.toLowerCase();
			// a set would be more efficient, but this complicates JSON
			// deserialization so we enforce uniqueness with an arraylist
			if (!owners.contains(name)) {
				owners.add(name);
			}
		}
	}

	public void removeOwner(String username) {
		if (!StringUtils.isEmpty(username)) {
			owners.remove(username.toLowerCase());
		}
	}

	public void addOwners(Collection<String> usernames) {
		if (!ArrayUtils.isEmpty(usernames)) {
			for (String username : usernames) {
				addOwner(username);
			}
		}
	}

	public void removeOwners(Collection<String> usernames) {
		if (!ArrayUtils.isEmpty(owners)) {
			for (String username : usernames) {
				removeOwner(username);
			}
		}
	}
}
