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
import java.util.Date;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevTag;

import com.gitblit.utils.JGitUtils;

/**
 * RefModel is a serializable model class that represents a tag or branch and
 * includes the referenced object.
 *
 * @author James Moger
 *
 */
public class RefModel implements Serializable, Comparable<RefModel> {

	private static final long serialVersionUID = 876822269940583606L;

	public final String displayName;

	private final Date date;
	private final String name;
	private final int type;
	private final String id;
	private final String referencedId;
	private final boolean annotated;
	private final PersonIdent person;
	private final String shortMessage;
	private final String fullMessage;

	private transient ObjectId objectId;
	private transient ObjectId referencedObjectId;

	public transient Ref reference; // Used in too many places.

	public RefModel(String displayName, Ref ref, RevObject refObject) {
		this.reference = ref;
		this.displayName = displayName;
		this.date = internalGetDate(refObject);
		this.name = ref != null ? ref.getName() : displayName;
		this.type = internalGetReferencedObjectType(refObject);
		this.objectId = ref != null ? ref.getObjectId() : ObjectId.zeroId();
		this.id = this.objectId.getName();
		this.referencedObjectId = internalGetReferencedObjectId(refObject);
		this.referencedId = this.referencedObjectId.getName();
		this.annotated = internalIsAnnotatedTag(ref, refObject);
		this.person = internalGetAuthorIdent(refObject);
		this.shortMessage = internalGetShortMessage(refObject);
		this.fullMessage = internalGetFullMessage(refObject);
	}

	private Date internalGetDate(RevObject referencedObject) {
		Date date = new Date(0);
		if (referencedObject != null) {
			if (referencedObject instanceof RevTag) {
				RevTag tag = (RevTag) referencedObject;
				PersonIdent tagger = tag.getTaggerIdent();
				if (tagger != null) {
					date = tagger.getWhen();
				}
			} else if (referencedObject instanceof RevCommit) {
				RevCommit commit = (RevCommit) referencedObject;
				date = JGitUtils.getAuthorDate(commit);
			}
		}
		return date;
	}

	public Date getDate() {
		return date;
	}

	public String getName() {
		return name;
	}

	private int internalGetReferencedObjectType(RevObject referencedObject) {
		int type = referencedObject.getType();
		if (referencedObject instanceof RevTag) {
			type = ((RevTag) referencedObject).getObject().getType();
		}
		return type;
	}

	public int getReferencedObjectType() {
		return type;
	}

	private ObjectId internalGetReferencedObjectId(RevObject referencedObject) {
		if (referencedObject instanceof RevTag) {
			return ((RevTag) referencedObject).getObject().getId();
		}
		return referencedObject.getId();
	}

	public ObjectId getReferencedObjectId() {
		if (referencedObjectId == null) {
			referencedObjectId = ObjectId.fromString(referencedId);
		}
		return referencedObjectId;
	}

	private String internalGetShortMessage(RevObject referencedObject) {
		String message = "";
		if (referencedObject instanceof RevTag) {
			message = ((RevTag) referencedObject).getShortMessage();
		} else if (referencedObject instanceof RevCommit) {
			message = ((RevCommit) referencedObject).getShortMessage();
		}
		return message;
	}

	public String getShortMessage() {
		return shortMessage;
	}

	private String internalGetFullMessage(RevObject referencedObject) {
		String message = "";
		if (referencedObject instanceof RevTag) {
			message = ((RevTag) referencedObject).getFullMessage();
		} else if (referencedObject instanceof RevCommit) {
			message = ((RevCommit) referencedObject).getFullMessage();
		}
		return message;
	}

	public String getFullMessage() {
		return fullMessage;
	}

	private PersonIdent internalGetAuthorIdent(RevObject referencedObject) {
		if (referencedObject instanceof RevTag) {
			return ((RevTag) referencedObject).getTaggerIdent();
		} else if (referencedObject instanceof RevCommit) {
			return ((RevCommit) referencedObject).getAuthorIdent();
		}
		return null;
	}

	public PersonIdent getAuthorIdent() {
		return person;
	}

	public ObjectId getObjectId() {
		if (objectId == null) {
			objectId = ObjectId.fromString(id);
		}
		return objectId;
	}

	private boolean internalIsAnnotatedTag(Ref reference, RevObject referencedObject) {
		if (referencedObject instanceof RevTag) {
			return !getReferencedObjectId().equals(getObjectId());
		}
		return reference != null && reference.getPeeledObjectId() != null;
	}

	public boolean isAnnotatedTag() {
		return annotated;
	}

	@Override
	public int hashCode() {
		return getReferencedObjectId().hashCode() + getName().hashCode();
	}

	@Override
	public boolean equals(Object o) {
		if (o instanceof RefModel) {
			RefModel other = (RefModel) o;
			return getName().equals(other.getName());
		}
		return super.equals(o);
	}

	@Override
	public int compareTo(RefModel o) {
		return getDate().compareTo(o.getDate());
	}

	@Override
	public String toString() {
		return displayName;
	}
}