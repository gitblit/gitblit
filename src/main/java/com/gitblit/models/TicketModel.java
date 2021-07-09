/*
 * Copyright 2014 gitblit.com.
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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jgit.util.RelativeDateFormatter;

import com.gitblit.Constants;

/**
 * The Gitblit Ticket model, its component classes, and enums.
 *
 * @author James Moger
 *
 */
public class TicketModel implements Serializable, Comparable<TicketModel> {

	private static final long serialVersionUID = 1L;

	public String project;

	public String repository;

	public long number;

	public Date created;

	public String createdBy;

	public Date updated;

	public String updatedBy;

	public String title;

	public String body;

	public String topic;

	public Type type;

	public Status status;

	public String responsible;

	public String milestone;

	public String mergeSha;

	public String mergeTo;

	public List<Change> changes;

	public Integer insertions;

	public Integer deletions;

	public Priority priority;
	
	public Severity severity;
	
	/**
	 * Builds an effective ticket from the collection of changes.  A change may
	 * Add or Subtract information from a ticket, but the collection of changes
	 * is only additive.
	 *
	 * @param changes
	 * @return the effective ticket
	 */
	public static TicketModel buildTicket(Collection<Change> changes) {
		TicketModel ticket;
		List<Change> effectiveChanges = new ArrayList<Change>();
		Map<String, Change> comments = new HashMap<String, Change>();
		Map<String, Change> references = new HashMap<String, Change>();
		Map<Integer, Integer> latestRevisions = new HashMap<Integer, Integer>();
		
		int latestPatchsetNumber = -1;
		
		List<Integer> deletedPatchsets = new ArrayList<Integer>();
		
		for (Change change : changes) {
			if (change.patchset != null) {
				if (change.patchset.isDeleted()) {
					deletedPatchsets.add(change.patchset.number);
				} else {
					Integer latestRev = latestRevisions.get(change.patchset.number);
					
					if (latestRev == null || change.patchset.rev > latestRev) {
						latestRevisions.put(change.patchset.number, change.patchset.rev);
					}
					
					if (change.patchset.number > latestPatchsetNumber) {
						latestPatchsetNumber = change.patchset.number;
					}	
				}
			}
		}
		
		for (Change change : changes) {
			if (change.comment != null) {
				if (comments.containsKey(change.comment.id)) {
					Change original = comments.get(change.comment.id);
					Change clone = copy(original);
					clone.comment.text = change.comment.text;
					clone.comment.deleted = change.comment.deleted;
					int idx = effectiveChanges.indexOf(original);
					effectiveChanges.remove(original);
					effectiveChanges.add(idx, clone);
					comments.put(clone.comment.id, clone);
				} else {
					effectiveChanges.add(change);
					comments.put(change.comment.id, change);
				}
			} else if (change.patchset != null) {
				//All revisions of a deleted patchset are not displayed
				if (!deletedPatchsets.contains(change.patchset.number)) {
					
					Integer latestRev = latestRevisions.get(change.patchset.number);
					
					if (    (change.patchset.number < latestPatchsetNumber) 
						 && (change.patchset.rev == latestRev)) {
						change.patchset.canDelete = true;
					}
					
					effectiveChanges.add(change);
				}
			} else if (change.reference != null){
				if (references.containsKey(change.reference.toString())) {
					Change original = references.get(change.reference.toString());
					Change clone = copy(original);
					clone.reference.deleted = change.reference.deleted;
					int idx = effectiveChanges.indexOf(original);
					effectiveChanges.remove(original);
					effectiveChanges.add(idx, clone);
				} else {
					effectiveChanges.add(change);
					references.put(change.reference.toString(), change);
				}
			} else {
				effectiveChanges.add(change);
			}
		}

		// effective ticket
		ticket = new TicketModel();
		for (Change change : effectiveChanges) {
			//Ensure deleted items are not included
			if (!change.hasComment()) {
				change.comment = null;
			}
			if (!change.hasReference()) {
				change.reference = null;
			}
			if (!change.hasPatchset()) {
				change.patchset = null;
			}
			ticket.applyChange(change);
		}
		return ticket;
	}

	public TicketModel() {
		// the first applied change set the date appropriately
		created = new Date(0);
		changes = new ArrayList<Change>();
		status = Status.New;
		type = Type.defaultType;
		priority = Priority.defaultPriority;
		severity = Severity.defaultSeverity;
	}

	public boolean isOpen() {
		return !status.isClosed();
	}

	public boolean isClosed() {
		return status.isClosed();
	}

	public boolean isMerged() {
		return isClosed() && !isEmpty(mergeSha);
	}

	public boolean isProposal() {
		return Type.Proposal == type;
	}

	public boolean isBug() {
		return Type.Bug == type;
	}

	public Date getLastUpdated() {
		return updated == null ? created : updated;
	}

	public boolean hasPatchsets() {
		return getPatchsets().size() > 0;
	}

	/**
	 * Returns true if multiple participants are involved in discussing a ticket.
	 * The ticket creator is excluded from this determination because a
	 * discussion requires more than one participant.
	 *
	 * @return true if this ticket has a discussion
	 */
	public boolean hasDiscussion() {
		for (Change change : getComments()) {
			if (!change.author.equals(createdBy)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Returns the list of changes with comments.
	 *
	 * @return
	 */
	public List<Change> getComments() {
		List<Change> list = new ArrayList<Change>();
		for (Change change : changes) {
			if (change.hasComment()) {
				list.add(change);
			}
		}
		return list;
	}

	/**
	 * Returns the list of participants for the ticket.
	 *
	 * @return the list of participants
	 */
	public List<String> getParticipants() {
		Set<String> set = new LinkedHashSet<String>();
		for (Change change : changes) {
			if (change.isParticipantChange()) {
				set.add(change.author);
			}
		}
		if (responsible != null && responsible.length() > 0) {
			set.add(responsible);
		}
		return new ArrayList<String>(set);
	}

	public boolean hasLabel(String label) {
		return getLabels().contains(label);
	}

	public List<String> getLabels() {
		return getList(Field.labels);
	}

	public boolean isResponsible(String username) {
		return username.equals(responsible);
	}

	public boolean isAuthor(String username) {
		return username.equals(createdBy);
	}

	public boolean isReviewer(String username) {
		return getReviewers().contains(username);
	}

	public List<String> getReviewers() {
		return getList(Field.reviewers);
	}

	public boolean isWatching(String username) {
		return getWatchers().contains(username);
	}

	public List<String> getWatchers() {
		return getList(Field.watchers);
	}

	public boolean isVoter(String username) {
		return getVoters().contains(username);
	}

	public List<String> getVoters() {
		return getList(Field.voters);
	}

	public List<String> getMentions() {
		return getList(Field.mentions);
	}

	protected List<String> getList(Field field) {
		Set<String> set = new TreeSet<String>();
		for (Change change : changes) {
			if (change.hasField(field)) {
				String values = change.getString(field);
				for (String value : values.split(",")) {
					switch (value.charAt(0)) {
					case '+':
						set.add(value.substring(1));
						break;
					case '-':
						set.remove(value.substring(1));
						break;
					default:
						set.add(value);
					}
				}
			}
		}
		if (!set.isEmpty()) {
			return new ArrayList<String>(set);
		}
		return Collections.emptyList();
	}

	public Attachment getAttachment(String name) {
		Attachment attachment = null;
		for (Change change : changes) {
			if (change.hasAttachments()) {
				Attachment a = change.getAttachment(name);
				if (a != null) {
					attachment = a;
				}
			}
		}
		return attachment;
	}

	public boolean hasAttachments() {
		for (Change change : changes) {
			if (change.hasAttachments()) {
				return true;
			}
		}
		return false;
	}

	public boolean hasReferences() {
		for (Change change : changes) {
			if (change.hasReference()) {
				return true;
			}
		}
		return false;
	}
	
	public List<Attachment> getAttachments() {
		List<Attachment> list = new ArrayList<Attachment>();
		for (Change change : changes) {
			if (change.hasAttachments()) {
				list.addAll(change.attachments);
			}
		}
		return list;
	}

	public List<Reference> getReferences() {
		List<Reference> list = new ArrayList<Reference>();
		for (Change change : changes) {
			if (change.hasReference()) {
				list.add(change.reference);
			}
		}
		return list;
	}
	
	public List<Patchset> getPatchsets() {
		List<Patchset> list = new ArrayList<Patchset>();
		for (Change change : changes) {
			if (change.patchset != null) {
				list.add(change.patchset);
			}
		}
		return list;
	}

	public List<Patchset> getPatchsetRevisions(int number) {
		List<Patchset> list = new ArrayList<Patchset>();
		for (Change change : changes) {
			if (change.patchset != null) {
				if (number == change.patchset.number) {
					list.add(change.patchset);
				}
			}
		}
		return list;
	}

	public Patchset getPatchset(String sha) {
		for (Change change : changes) {
			if (change.patchset != null) {
				if (sha.equals(change.patchset.tip)) {
					return change.patchset;
				}
			}
		}
		return null;
	}

	public Patchset getPatchset(int number, int rev) {
		for (Change change : changes) {
			if (change.patchset != null) {
				if (number == change.patchset.number && rev == change.patchset.rev) {
					return change.patchset;
				}
			}
		}
		return null;
	}

	public Patchset getCurrentPatchset() {
		Patchset patchset = null;
		for (Change change : changes) {
			if (change.patchset != null) {
				if (patchset == null) {
					patchset = change.patchset;
				} else if (patchset.compareTo(change.patchset) == 1) {
					patchset = change.patchset;
				}
			}
		}
		return patchset;
	}

	public boolean isCurrent(Patchset patchset) {
		if (patchset == null) {
			return false;
		}
		Patchset curr = getCurrentPatchset();
		if (curr == null) {
			return false;
		}
		return curr.equals(patchset);
	}

	public List<Change> getReviews(Patchset patchset) {
		if (patchset == null) {
			return Collections.emptyList();
		}
		// collect the patchset reviews by author
		// the last review by the author is the
		// official review
		Map<String, Change> reviews = new LinkedHashMap<String, TicketModel.Change>();
		for (Change change : changes) {
			if (change.hasReview()) {
				if (change.review.isReviewOf(patchset)) {
					reviews.put(change.author, change);
				}
			}
		}
		return new ArrayList<Change>(reviews.values());
	}

	public boolean isApproved(Patchset patchset) {
		if (patchset == null) {
			return false;
		}
		boolean approved = false;
		boolean vetoed = false;
		for (Change change : getReviews(patchset)) {
			if (change.hasReview()) {
				if (change.review.isReviewOf(patchset)) {
					if (Score.approved == change.review.score) {
						approved = true;
					} else if (Score.vetoed == change.review.score) {
						vetoed = true;
					}
				}
			}
		}
		return approved && !vetoed;
	}

	public int getScore(Patchset patchset) {
		int score = 0;
		
		for (Change change : getReviews(patchset)) {
			score += change.review.score.getValue();
		}
		return score;
	}

	public boolean isVetoed(Patchset patchset) {
		if (patchset == null) {
			return false;
		}
		for (Change change : getReviews(patchset)) {
			if (change.hasReview()) {
				if (change.review.isReviewOf(patchset)) {
					if (Score.vetoed == change.review.score) {
						return true;
					}
				}
			}
		}
		return false;
	}

	public Review getReviewBy(String username) {
		for (Change change : getReviews(getCurrentPatchset())) {
			if (change.author.equals(username)) {
				return change.review;
			}
		}
		return null;
	}

	public boolean isPatchsetAuthor(String username) {
		for (Change change : changes) {
			if (change.hasPatchset()) {
				if (change.author.equals(username)) {
					return true;
				}
			}
		}
		return false;
	}

	public void applyChange(Change change) {
		if (changes.size() == 0) {
			// first change created the ticket
			created = change.date;
			createdBy = change.author;
			status = Status.New;
		} else if (created == null || change.date.after(created)) {
			// track last ticket update
			updated = change.date;
			updatedBy = change.author;
		}

		if (change.isMerge()) {
			// identify merge patchsets
			if (isEmpty(responsible)) {
				responsible = change.author;
			}
			status = Status.Merged;
		}

		if (change.hasFieldChanges()) {
			for (Map.Entry<Field, String> entry : change.fields.entrySet()) {
				Field field = entry.getKey();
				Object value = entry.getValue();
				switch (field) {
				case type:
					type = TicketModel.Type.fromObject(value, type);
					break;
				case status:
					status = TicketModel.Status.fromObject(value, status);
					break;
				case title:
					title = toString(value);
					break;
				case body:
					body = toString(value);
					break;
				case topic:
					topic = toString(value);
					break;
				case responsible:
					responsible = toString(value);
					break;
				case milestone:
					milestone = toString(value);
					break;
				case mergeTo:
					mergeTo = toString(value);
					break;
				case mergeSha:
					mergeSha = toString(value);
					break;
				case priority:
					priority = TicketModel.Priority.fromObject(value, priority);
					break;
				case severity:
					severity = TicketModel.Severity.fromObject(value, severity);
					break;
				default:
					// unknown
					break;
				}
			}
		}

		// add real changes to the ticket and ensure deleted changes are removed
		if (change.isEmptyChange()) {
			changes.remove(change);
		} else {
			changes.add(change);
		}
	}

	protected String toString(Object value) {
		if (value == null) {
			return null;
		}
		return value.toString();
	}

	public String toIndexableString() {
		StringBuilder sb = new StringBuilder();
		if (!isEmpty(title)) {
			sb.append(title).append('\n');
		}
		if (!isEmpty(body)) {
			sb.append(body).append('\n');
		}
		for (Change change : changes) {
			if (change.hasComment()) {
				sb.append(change.comment.text);
				sb.append('\n');
			}
		}
		return sb.toString();
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append("#");
		sb.append(number);
		sb.append(": " + title + "\n");
		for (Change change : changes) {
			sb.append(change);
			sb.append('\n');
		}
		return sb.toString();
	}

	@Override
	public int compareTo(TicketModel o) {
		return o.created.compareTo(created);
	}

	@Override
	public boolean equals(Object o) {
		if (o instanceof TicketModel) {
			return number == ((TicketModel) o).number;
		}
		return super.equals(o);
	}

	@Override
	public int hashCode() {
		return (repository + number).hashCode();
	}

	/**
	 * Encapsulates a ticket change
	 */
	public static class Change implements Serializable, Comparable<Change> {

		private static final long serialVersionUID = 1L;

		public final Date date;

		public final String author;

		public Comment comment;

		public Reference reference;

		public Map<Field, String> fields;

		public Set<Attachment> attachments;

		public Patchset patchset;

		public Review review;

		private transient String id;

		//Once links have been made they become a reference on the target ticket
		//The ticket service handles promoting links to references
		public transient List<TicketLink> pendingLinks;

		public Change(String author) {
			this(author, new Date());
		}

		public Change(String author, Date date) {
			this.date = date;
			this.author = author;
		}

		public boolean isStatusChange() {
			return hasField(Field.status);
		}

		public Status getStatus() {
			Status state = Status.fromObject(getField(Field.status), null);
			return state;
		}

		public boolean isMerge() {
			return hasField(Field.status) && hasField(Field.mergeSha);
		}

		public boolean hasPatchset() {
			return patchset != null && !patchset.isDeleted();
		}

		public boolean hasReview() {
			return review != null;
		}

		public boolean hasComment() {
			return comment != null && !comment.isDeleted() && comment.text != null;
		}
		
		public boolean hasReference() {
			return reference != null && !reference.isDeleted();
		}

		public boolean hasPendingLinks() {
			return pendingLinks != null && pendingLinks.size() > 0;
		}

		public Comment comment(String text) {
			comment = new Comment(text);
			comment.id = TicketModel.getSHA1(date.toString() + author + text);

			// parse comment looking for ref #n
			//TODO: Ideally set via settings
			String x = "(?:ref|task|issue|bug)?[\\s-]*#(\\d+)";

			try {
				Pattern p = Pattern.compile(x, Pattern.CASE_INSENSITIVE);
				Matcher m = p.matcher(text);
				while (m.find()) {
					String val = m.group(1);
					long targetTicketId = Long.parseLong(val);
					
					if (targetTicketId > 0) {
						if (pendingLinks == null) {
							pendingLinks = new ArrayList<TicketLink>();
						}
						
						pendingLinks.add(new TicketLink(targetTicketId, TicketAction.Comment));
					}
				}
			} catch (Exception e) {
				// ignore
			}
			
			try {
				Pattern mentions = Pattern.compile(Constants.REGEX_TICKET_MENTION);
				Matcher m = mentions.matcher(text);
				while (m.find()) {
					String username = m.group("user");
					plusList(Field.mentions, username);
				}
			} catch (Exception e) {
				// ignore
			}
			return comment;
		}

		public Reference referenceCommit(String commitHash) {
			reference = new Reference(commitHash);
			return reference;
		}

		public Reference referenceTicket(long ticketId, String changeHash) {
			reference = new Reference(ticketId, changeHash);
			return reference;
		}
		
		public Review review(Patchset patchset, Score score, boolean addReviewer) {
			if (addReviewer) {
				plusList(Field.reviewers, author);
			}
			review = new Review(patchset.number, patchset.rev);
			review.score = score;
			return review;
		}

		public boolean hasAttachments() {
			return !TicketModel.isEmpty(attachments);
		}

		public void addAttachment(Attachment attachment) {
			if (attachments == null) {
				attachments = new LinkedHashSet<Attachment>();
			}
			attachments.add(attachment);
		}

		public Attachment getAttachment(String name) {
			if (attachments != null) {
				for (Attachment attachment : attachments) {
					if (attachment.name.equalsIgnoreCase(name)) {
						return attachment;
					}
				}
			}
			return null;
		}

		public boolean isParticipantChange() {
			if (hasComment()
					|| hasReview()
					|| hasPatchset()
					|| hasAttachments()) {
				return true;
			}

			if (TicketModel.isEmpty(fields)) {
				return false;
			}

			// identify real ticket field changes
			Map<Field, String> map = new HashMap<Field, String>(fields);
			map.remove(Field.watchers);
			map.remove(Field.voters);
			return !map.isEmpty();
		}

		public boolean hasField(Field field) {
			return !TicketModel.isEmpty(getString(field));
		}

		public boolean hasFieldChanges() {
			return !TicketModel.isEmpty(fields);
		}

		public String getField(Field field) {
			if (fields != null) {
				return fields.get(field);
			}
			return null;
		}

		public void setField(Field field, Object value) {
			if (fields == null) {
				fields = new LinkedHashMap<Field, String>();
			}
			if (value == null) {
				fields.put(field, null);
			} else if (Enum.class.isAssignableFrom(value.getClass())) {
				fields.put(field, ((Enum<?>) value).name());
			} else {
				fields.put(field, value.toString());
			}
		}

		public void remove(Field field) {
			if (fields != null) {
				fields.remove(field);
			}
		}

		public String getString(Field field) {
			String value = getField(field);
			if (value == null) {
				return null;
			}
			return value;
		}

		public void watch(String... username) {
			plusList(Field.watchers, username);
		}

		public void unwatch(String... username) {
			minusList(Field.watchers, username);
		}

		public void vote(String... username) {
			plusList(Field.voters, username);
		}

		public void unvote(String... username) {
			minusList(Field.voters, username);
		}

		public void label(String... label) {
			plusList(Field.labels, label);
		}

		public void unlabel(String... label) {
			minusList(Field.labels, label);
		}

		protected void plusList(Field field, String... items) {
			modList(field, "+", items);
		}

		protected void minusList(Field field, String... items) {
			modList(field, "-", items);
		}

		private void modList(Field field, String prefix, String... items) {
			List<String> list = new ArrayList<String>();
			for (String item : items) {
				list.add(prefix + item);
			}
			if (hasField(field)) {
				String flat = getString(field);
				if (isEmpty(flat)) {
					// field is empty, use this list
					setField(field, join(list, ","));
				} else {
					// merge this list into the existing field list
					Set<String> set = new TreeSet<String>(Arrays.asList(flat.split(",")));
					set.addAll(list);
					setField(field, join(set, ","));
				}
			} else {
				// does not have a list for this field
				setField(field, join(list, ","));
			}
		}

		public String getId() {
			if (id == null) {
				id = getSHA1(Long.toHexString(date.getTime()) + author);
			}
			return id;
		}

		@Override
		public int compareTo(Change c) {
			return date.compareTo(c.date);
		}

		@Override
		public int hashCode() {
			return getId().hashCode();
		}

		@Override
		public boolean equals(Object o) {
			if (o instanceof Change) {
				return getId().equals(((Change) o).getId());
			}
			return false;
		}
		
		/*
		 * Identify if this is an empty change. i.e. only an author and date is defined.
		 * This can occur when items have been deleted
		 * @returns true if the change is empty
		 */
		private boolean isEmptyChange() {
			return ((comment == null) && (reference == null) && 
					(fields == null) && (attachments == null) && 
					(patchset == null) && (review == null));
		}

		@Override
		public String toString() {
			StringBuilder sb = new StringBuilder();
			sb.append(RelativeDateFormatter.format(date));
			if (hasComment()) {
				sb.append(" commented on by ");
			} else if (hasPatchset()) {
				sb.append(MessageFormat.format(" {0} uploaded by ", patchset));
			} else if (hasReference()) {
				sb.append(MessageFormat.format(" referenced in {0} by ", reference));
			} else {
				sb.append(" changed by ");
			}
			sb.append(author).append(" - ");
			if (hasComment()) {
				if (comment.isDeleted()) {
					sb.append("(deleted) ");
				}
				sb.append(comment.text).append(" ");
			}

			if (hasFieldChanges()) {
				for (Map.Entry<Field, String> entry : fields.entrySet()) {
					sb.append("\n  ");
					sb.append(entry.getKey().name());
					sb.append(':');
					sb.append(entry.getValue());
				}
			}
			return sb.toString();
		}
	}

	/**
	 * Returns true if the string is null or empty.
	 *
	 * @param value
	 * @return true if string is null or empty
	 */
	static boolean isEmpty(String value) {
		return value == null || value.trim().length() == 0;
	}

	/**
	 * Returns true if the collection is null or empty
	 *
	 * @param collection
	 * @return
	 */
	static boolean isEmpty(Collection<?> collection) {
		return collection == null || collection.size() == 0;
	}

	/**
	 * Returns true if the map is null or empty
	 *
	 * @param map
	 * @return
	 */
	static boolean isEmpty(Map<?, ?> map) {
		return map == null || map.size() == 0;
	}

	/**
	 * Calculates the SHA1 of the string.
	 *
	 * @param text
	 * @return sha1 of the string
	 */
	static String getSHA1(String text) {
		try {
			byte[] bytes = text.getBytes("iso-8859-1");
			return getSHA1(bytes);
		} catch (UnsupportedEncodingException u) {
			throw new RuntimeException(u);
		}
	}

	/**
	 * Calculates the SHA1 of the byte array.
	 *
	 * @param bytes
	 * @return sha1 of the byte array
	 */
	static String getSHA1(byte[] bytes) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-1");
			md.update(bytes, 0, bytes.length);
			byte[] digest = md.digest();
			return toHex(digest);
		} catch (NoSuchAlgorithmException t) {
			throw new RuntimeException(t);
		}
	}

	/**
	 * Returns the hex representation of the byte array.
	 *
	 * @param bytes
	 * @return byte array as hex string
	 */
	static String toHex(byte[] bytes) {
		StringBuilder sb = new StringBuilder(bytes.length * 2);
		for (int i = 0; i < bytes.length; i++) {
			if ((bytes[i] & 0xff) < 0x10) {
				sb.append('0');
			}
			sb.append(Long.toString(bytes[i] & 0xff, 16));
		}
		return sb.toString();
	}

	/**
	 * Join the list of strings into a single string with a space separator.
	 *
	 * @param values
	 * @return joined list
	 */
	static String join(Collection<String> values) {
		return join(values, " ");
	}

	/**
	 * Join the list of strings into a single string with the specified
	 * separator.
	 *
	 * @param values
	 * @param separator
	 * @return joined list
	 */
	static String join(String[]  values, String separator) {
		return join(Arrays.asList(values), separator);
	}

	/**
	 * Join the list of strings into a single string with the specified
	 * separator.
	 *
	 * @param values
	 * @param separator
	 * @return joined list
	 */
	static String join(Collection<String> values, String separator) {
		StringBuilder sb = new StringBuilder();
		for (String value : values) {
			sb.append(value).append(separator);
		}
		if (sb.length() > 0) {
			// truncate trailing separator
			sb.setLength(sb.length() - separator.length());
		}
		return sb.toString().trim();
	}


	/**
	 * Produce a deep copy of the given object. Serializes the entire object to
	 * a byte array in memory. Recommended for relatively small objects.
	 */
	@SuppressWarnings("unchecked")
	static <T> T copy(T original) {
		T o = null;
		try {
			ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
			ObjectOutputStream oos = new ObjectOutputStream(byteOut);
			oos.writeObject(original);
			ByteArrayInputStream byteIn = new ByteArrayInputStream(byteOut.toByteArray());
			ObjectInputStream ois = new ObjectInputStream(byteIn);
			try {
				o = (T) ois.readObject();
			} catch (ClassNotFoundException cex) {
				// actually can not happen in this instance
			}
		} catch (IOException iox) {
			// doesn't seem likely to happen as these streams are in memory
			throw new RuntimeException(iox);
		}
		return o;
	}

	public static class Patchset implements Serializable, Comparable<Patchset> {

		private static final long serialVersionUID = 1L;

		public int number;
		public int rev;
		public String tip;
		public String parent;
		public String base;
		public int insertions;
		public int deletions;
		public int commits;
		public int added;
		public PatchsetType type;

		public transient boolean canDelete = false;

		public boolean isFF() {
			return PatchsetType.FastForward == type;
		}

		public boolean isDeleted() {
			return PatchsetType.Delete == type;
		}

		@Override
		public int hashCode() {
			return toString().hashCode();
		}

		@Override
		public boolean equals(Object o) {
			if (o instanceof Patchset) {
				return hashCode() == o.hashCode();
			}
			return false;
		}

		@Override
		public int compareTo(Patchset p) {
			if (number > p.number) {
				return -1;
			} else if (p.number > number) {
				return 1;
			} else {
				// same patchset, different revision
				if (rev > p.rev) {
					return -1;
				} else if (p.rev > rev) {
					return 1;
				} else {
					// same patchset & revision
					return 0;
				}
			}
		}

		@Override
		public String toString() {
			return "patchset " + number + " revision " + rev;
		}
	}

	public static class Comment implements Serializable {

		private static final long serialVersionUID = 1L;

		public String text;

		public String id;

		public Boolean deleted;

		public CommentSource src;

		public String replyTo;

		Comment(String text) {
			this.text = text;
		}

		public boolean isDeleted() {
			return deleted != null && deleted;
		}

		@Override
		public String toString() {
			return text;
		}
	}
	
	
	public static enum TicketAction {
		Commit, Comment, Patchset, Close
	}
	
	//Intentionally not serialized, links are persisted as "references"
	public static class TicketLink {
		public long targetTicketId;
		public String hash;
		public TicketAction action;
		public boolean success;
		public boolean isDelete;
		
		public TicketLink(long targetTicketId, TicketAction action) {
			this.targetTicketId = targetTicketId;
			this.action = action;
			success = false;
			isDelete = false;
		}
		
		public TicketLink(long targetTicketId, TicketAction action, String hash) {
			this.targetTicketId = targetTicketId;
			this.action = action;
			this.hash = hash;
			success = false;
			isDelete = false;
		}
	}
	
	public static enum ReferenceType {
		Undefined, Commit, Ticket;
	
		@Override
		public String toString() {
			return name().toLowerCase().replace('_', ' ');
		}
		
		public static ReferenceType fromObject(Object o, ReferenceType defaultType) {
			if (o instanceof ReferenceType) {
				// cast and return
				return (ReferenceType) o;
			} else if (o instanceof String) {
				// find by name
				for (ReferenceType type : values()) {
					String str = o.toString();
					if (type.name().equalsIgnoreCase(str)
							|| type.toString().equalsIgnoreCase(str)) {
						return type;
					}
				}
			} else if (o instanceof Number) {
				// by ordinal
				int id = ((Number) o).intValue();
				if (id >= 0 && id < values().length) {
					return values()[id];
				}
			}

			return defaultType;
		}
	}
	
	public static class Reference implements Serializable {
	
		private static final long serialVersionUID = 1L;
		
		public String hash;
		public Long ticketId;
		
		public Boolean deleted;
		
		Reference(String commitHash) {
			this.hash = commitHash;
		}
		
		Reference(long ticketId, String changeHash) {
			this.ticketId = ticketId;
			this.hash = changeHash;
		}
		
		public ReferenceType getSourceType(){
			if (hash != null) {
				if (ticketId != null) {
					return ReferenceType.Ticket;
				} else {
					return ReferenceType.Commit;
				}
			}
			
			return ReferenceType.Undefined;
		}
		
		public boolean isDeleted() {
			return deleted != null && deleted;
		}
		
		@Override
		public String toString() {
			switch (getSourceType()) {
				case Commit: return hash;
				case Ticket: return ticketId.toString() + "#" + hash;
				default: {} break;
			}
			
			return String.format("Unknown Reference Type");
		}
	}

	public static class Attachment implements Serializable {

		private static final long serialVersionUID = 1L;

		public final String name;
		public long size;
		public byte[] content;
		public Boolean deleted;

		public Attachment(String name) {
			this.name = name;
		}

		public boolean isDeleted() {
			return deleted != null && deleted;
		}

		@Override
		public int hashCode() {
			return name.hashCode();
		}

		@Override
		public boolean equals(Object o) {
			if (o instanceof Attachment) {
				return name.equalsIgnoreCase(((Attachment) o).name);
			}
			return false;
		}

		@Override
		public String toString() {
			return name;
		}
	}

	public static class Review implements Serializable {

		private static final long serialVersionUID = 1L;

		public final int patchset;

		public final int rev;

		public Score score;

		public Review(int patchset, int revision) {
			this.patchset = patchset;
			this.rev = revision;
		}

		public boolean isReviewOf(Patchset p) {
			return patchset == p.number && rev == p.rev;
		}

		@Override
		public String toString() {
			return "review of patchset " + patchset + " rev " + rev + ":" + score;
		}
	}

	public static enum Score {
		approved(2), looks_good(1), not_reviewed(0), needs_improvement(-1), vetoed(
				-2);

		final int value;

		Score(int value) {
			this.value = value;
		}

		public int getValue() {
			return value;
		}

		@Override
		public String toString() {
			return name().toLowerCase().replace('_', ' ');
		}

		public static Score fromScore(int score) {
			for (Score s : values()) {
				if (s.getValue() == score) {
					return s;
				}
			}
			throw new NoSuchElementException(String.valueOf(score));
		}
	}

	public static enum Field {
		title, body, responsible, type, status, milestone, mergeSha, mergeTo,
		topic, labels, watchers, reviewers, voters, mentions, priority, severity;
	}

	public static enum Type {
		Enhancement, Task, Bug, Proposal, Question, Maintenance;

		public static Type defaultType = Task;

		public static Type [] choices() {
			return new Type [] { Enhancement, Task, Bug, Question, Maintenance };
		}

		@Override
		public String toString() {
			return name().toLowerCase().replace('_', ' ');
		}

		public static Type fromObject(Object o, Type defaultType) {
			if (o instanceof Type) {
				// cast and return
				return (Type) o;
			} else if (o instanceof String) {
				// find by name
				for (Type type : values()) {
					String str = o.toString();
					if (type.name().equalsIgnoreCase(str)
							|| type.toString().equalsIgnoreCase(str)) {
						return type;
					}
				}
			} else if (o instanceof Number) {
				// by ordinal
				int id = ((Number) o).intValue();
				if (id >= 0 && id < values().length) {
					return values()[id];
				}
			}

			return defaultType;
		}
	}

	public static enum Status {
		New, Open, Closed, Resolved, Fixed, Merged, Wontfix, Declined, Duplicate, Invalid, Abandoned, On_Hold, No_Change_Required;

		public static Status [] requestWorkflow = { Open, Resolved, Declined, Duplicate, Invalid, Abandoned, On_Hold, No_Change_Required };

		public static Status [] bugWorkflow = { Open, Fixed, Wontfix, Duplicate, Invalid, Abandoned, On_Hold, No_Change_Required };

		public static Status [] proposalWorkflow = { Open, Resolved, Declined, Abandoned, On_Hold, No_Change_Required };

		public static Status [] milestoneWorkflow = { Open, Closed, Abandoned, On_Hold };

		@Override
		public String toString() {
			return name().toLowerCase().replace('_', ' ');
		}

		public static Status fromObject(Object o, Status defaultStatus) {
			if (o instanceof Status) {
				// cast and return
				return (Status) o;
			} else if (o instanceof String) {
				// find by name
				String name = o.toString();
				for (Status state : values()) {
					if (state.name().equalsIgnoreCase(name)
							|| state.toString().equalsIgnoreCase(name)) {
						return state;
					}
				}
			} else if (o instanceof Number) {
				// by ordinal
				int id = ((Number) o).intValue();
				if (id >= 0 && id < values().length) {
					return values()[id];
				}
			}

			return defaultStatus;
		}

		public boolean isClosed() {
			return ordinal() > Open.ordinal();
		}
	}

	public static enum CommentSource {
		Comment, Email
	}

	public static enum PatchsetType {
		Proposal, FastForward, Rebase, Squash, Rebase_Squash, Amend, Delete;

		public boolean isRewrite() {
			return (this != FastForward) && (this != Proposal);
		}

		@Override
		public String toString() {
			return name().toLowerCase().replace('_', '+');
		}

		public static PatchsetType fromObject(Object o) {
			if (o instanceof PatchsetType) {
				// cast and return
				return (PatchsetType) o;
			} else if (o instanceof String) {
				// find by name
				String name = o.toString();
				for (PatchsetType type : values()) {
					if (type.name().equalsIgnoreCase(name)
							|| type.toString().equalsIgnoreCase(name)) {
						return type;
					}
				}
			} else if (o instanceof Number) {
				// by ordinal
				int id = ((Number) o).intValue();
				if (id >= 0 && id < values().length) {
					return values()[id];
				}
			}

			return null;
		}
	}

	public static enum Priority {
		Low(-1), Normal(0), High(1), Urgent(2);

		public static Priority defaultPriority = Normal;

		final int value;

		Priority(int value) {
			this.value = value;
		}

		public int getValue() {
			return value;
		}
		
		public static Priority [] choices() {
			return new Priority [] { Urgent, High, Normal, Low };
		}

		@Override
		public String toString() {
			return name().toLowerCase().replace('_', ' ');
		}

		public static Priority fromObject(Object o, Priority defaultPriority) {
			if (o instanceof Priority) {
				// cast and return
				return (Priority) o;
			} else if (o instanceof String) {
				// find by name
				for (Priority priority : values()) {
					String str = o.toString();
					if (priority.name().equalsIgnoreCase(str)
							|| priority.toString().equalsIgnoreCase(str)) {
						return priority;
					}
				}
			} else if (o instanceof Number) {

				switch (((Number) o).intValue()) {
					case -1: return Priority.Low;
					case 0:  return Priority.Normal;
					case 1:  return Priority.High;
					case 2:  return Priority.Urgent;
					default: return Priority.Normal;
				}
			}

			return defaultPriority;
		}
	}
	
	public static enum Severity {
		Unrated(-1), Negligible(1), Minor(2), Serious(3), Critical(4), Catastrophic(5);

		public static Severity defaultSeverity = Unrated;
		
		final int value;
		
		Severity(int value) {
			this.value = value;
		}

		public int getValue() {
			return value;
		}
		
		public static Severity [] choices() {
			return new Severity [] { Unrated, Negligible, Minor, Serious, Critical, Catastrophic };
		}

		@Override
		public String toString() {
			return name().toLowerCase().replace('_', ' ');
		}
		
		public static Severity fromObject(Object o, Severity defaultSeverity) {
			if (o instanceof Severity) {
				// cast and return
				return (Severity) o;
			} else if (o instanceof String) {
				// find by name
				for (Severity severity : values()) {
					String str = o.toString();
					if (severity.name().equalsIgnoreCase(str)
							|| severity.toString().equalsIgnoreCase(str)) {
						return severity;
					}
				}
			} else if (o instanceof Number) {
				
				switch (((Number) o).intValue()) {
					case -1: return Severity.Unrated;
					case 1:  return Severity.Negligible;
					case 2:  return Severity.Minor;
					case 3:  return Severity.Serious;
					case 4:  return Severity.Critical;
					case 5:  return Severity.Catastrophic;
					default: return Severity.Unrated;
				}
			}

			return defaultSeverity;
		}
	}
}
