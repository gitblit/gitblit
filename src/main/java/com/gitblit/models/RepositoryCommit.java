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

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.text.MessageFormat;
import java.util.Date;
import java.util.List;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

import com.gitblit.wicket.GitBlitWebApp;

/**
 * Model class to represent a RevCommit, it's source repository, and the branch. This class is used by the activity page.
 *
 * @author James Moger
 */
public class RepositoryCommit implements Serializable, Comparable<RepositoryCommit> {

	private static final long serialVersionUID = -2214911650485772022L;

	public String repository;

	public String branch;

	private final String commitId;

	private List<RefModel> refs;

	private transient RevCommit commit;

	public RepositoryCommit(String repository, String branch, RevCommit commit) {
		this.repository = repository;
		this.branch = branch;
		this.commit = commit;
		this.commitId = commit.getName();
	}

	public void setRefs(List<RefModel> refs) {
		this.refs = refs;
	}

	public List<RefModel> getRefs() {
		return refs;
	}

	public ObjectId getId() {
		return commit.getId();
	}

	public String getName() {
		return commit.getName();
	}

	public String getShortName() {
		return commit.getName().substring(0, 8);
	}

	public String getShortMessage() {
		return commit.getShortMessage();
	}

	public Date getCommitDate() {
		return new Date(commit.getCommitTime() * 1000L);
	}

	public int getParentCount() {
		return commit.getParentCount();
	}

	public RevCommit[] getParents() {
		return commit.getParents();
	}

	public PersonIdent getAuthorIdent() {
		return commit.getAuthorIdent();
	}

	public PersonIdent getCommitterIdent() {
		return commit.getCommitterIdent();
	}

	public RevCommit getCommit() {
		return commit;
	}

	@Override
	public boolean equals(Object o) {
		if (o instanceof RepositoryCommit) {
			final RepositoryCommit commit = (RepositoryCommit) o;
			return repository.equals(commit.repository) && getName().equals(commit.getName());
		}
		return false;
	}

	@Override
	public int hashCode() {
		return (repository + commit).hashCode();
	}

	@Override
	public int compareTo(RepositoryCommit o) {
		// reverse-chronological order
		if (commit.getCommitTime() > o.commit.getCommitTime()) {
			return -1;
		} else if (commit.getCommitTime() < o.commit.getCommitTime()) {
			return 1;
		}
		return 0;
	}

	public RepositoryCommit clone(String withRef) {
		return new RepositoryCommit(repository, withRef, commit);
	}

	@Override
	public String toString() {
		return MessageFormat.format("{0} {1} {2,date,yyyy-MM-dd HH:mm} {3} {4}", getShortName(), branch, getCommitterIdent().getWhen(),
				getAuthorIdent().getName(), getShortMessage());
	}

	// Serialization: restore the JGit RevCommit on reading

	private void readObject(ObjectInputStream input) throws IOException, ClassNotFoundException {
		// Read in fields and any hidden stuff
		input.defaultReadObject();
		// Go find the commit again.
		final Repository repo = GitBlitWebApp.get().repositories().getRepository(repository);
		if (repo == null) {
			throw new IOException("Cannot find repositoy " + repository);
		}
		try (RevWalk walk = new RevWalk(repo)) {
			commit = walk.parseCommit(repo.resolve(commitId));
		}
	}

}