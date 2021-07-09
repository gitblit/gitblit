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
package com.gitblit.wicket.pages;

import java.io.IOException;
import java.text.DateFormat;
import java.text.MessageFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeSet;

import javax.servlet.http.HttpServletRequest;

import org.apache.wicket.AttributeModifier;
import org.apache.wicket.Component;
import org.apache.wicket.MarkupContainer;
import org.apache.wicket.PageParameters;
import org.apache.wicket.RestartResponseException;
import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.behavior.SimpleAttributeModifier;
import org.apache.wicket.markup.html.basic.Label;
import org.apache.wicket.markup.html.image.ContextImage;
import org.apache.wicket.markup.html.link.BookmarkablePageLink;
import org.apache.wicket.markup.html.link.ExternalLink;
import org.apache.wicket.markup.html.link.Link;
import org.apache.wicket.markup.html.pages.RedirectPage;
import org.apache.wicket.markup.html.panel.Fragment;
import org.apache.wicket.markup.repeater.Item;
import org.apache.wicket.markup.repeater.data.DataView;
import org.apache.wicket.markup.repeater.data.ListDataProvider;
import org.apache.wicket.model.Model;
import org.apache.wicket.protocol.http.RequestUtils;
import org.apache.wicket.protocol.http.WebRequest;
import org.eclipse.jgit.diff.DiffEntry.ChangeType;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;

import com.gitblit.Constants;
import com.gitblit.Constants.AccessPermission;
import com.gitblit.Constants.AuthorizationControl;
import com.gitblit.Keys;
import com.gitblit.git.PatchsetCommand;
import com.gitblit.git.PatchsetReceivePack;
import com.gitblit.models.PathModel.PathChangeModel;
import com.gitblit.models.RegistrantAccessPermission;
import com.gitblit.models.RepositoryModel;
import com.gitblit.models.RepositoryUrl;
import com.gitblit.models.SubmoduleModel;
import com.gitblit.models.TicketModel;
import com.gitblit.models.TicketModel.Change;
import com.gitblit.models.TicketModel.CommentSource;
import com.gitblit.models.TicketModel.Field;
import com.gitblit.models.TicketModel.Patchset;
import com.gitblit.models.TicketModel.PatchsetType;
import com.gitblit.models.TicketModel.Review;
import com.gitblit.models.TicketModel.Score;
import com.gitblit.models.TicketModel.Status;
import com.gitblit.models.UserModel;
import com.gitblit.tickets.TicketIndexer.Lucene;
import com.gitblit.tickets.TicketLabel;
import com.gitblit.tickets.TicketMilestone;
import com.gitblit.tickets.TicketResponsible;
import com.gitblit.utils.ArrayUtils;
import com.gitblit.utils.JGitUtils;
import com.gitblit.utils.JGitUtils.MergeStatus;
import com.gitblit.utils.CommitCache;
import com.gitblit.utils.MarkdownUtils;
import com.gitblit.utils.RefLogUtils;
import com.gitblit.utils.StringUtils;
import com.gitblit.utils.TimeUtils;
import com.gitblit.wicket.GitBlitWebSession;
import com.gitblit.wicket.TicketsUI;
import com.gitblit.wicket.WicketUtils;
import com.gitblit.wicket.panels.AvatarImage;
import com.gitblit.wicket.panels.BasePanel.JavascriptEventConfirmation;
import com.gitblit.wicket.panels.BasePanel.JavascriptTextPrompt;
import com.gitblit.wicket.panels.CommentPanel;
import com.gitblit.wicket.panels.DiffStatPanel;
import com.gitblit.wicket.panels.IconAjaxLink;
import com.gitblit.wicket.panels.LinkPanel;
import com.gitblit.wicket.panels.ShockWaveComponent;
import com.gitblit.wicket.panels.SimpleAjaxLink;

/**
 * The ticket page handles viewing and updating a ticket.
 *
 * @author James Moger
 *
 */
public class TicketPage extends RepositoryPage {

	static final String NIL = "<nil>";

	static final String ESC_NIL = StringUtils.escapeForHtml(NIL,  false);

	final int avatarWidth = 40;

	final TicketModel ticket;

	public TicketPage(PageParameters params) {
		super(params);

		final UserModel user = GitBlitWebSession.get().getUser() == null ? UserModel.ANONYMOUS : GitBlitWebSession.get().getUser();
		final RepositoryModel repository = getRepositoryModel();
		final String id = WicketUtils.getObject(params);
		long ticketId = Long.parseLong(id);
		ticket = app().tickets().getTicket(repository, ticketId);

		if (ticket == null) {
			// ticket not found
			throw new RestartResponseException(TicketsPage.class, WicketUtils.newRepositoryParameter(repositoryName));
		}

		final List<Change> revisions = new ArrayList<Change>();
		List<Change> comments = new ArrayList<Change>();
		List<Change> statusChanges = new ArrayList<Change>();
		List<Change> discussion = new ArrayList<Change>();
		for (Change change : ticket.changes) {
			if (change.hasComment() || (change.isStatusChange() && (change.getStatus() != Status.New))) {
				discussion.add(change);
			}
			if (change.hasComment()) {
				comments.add(change);
			}
			if (change.hasPatchset()) {
				revisions.add(change);
			}
			if (change.isStatusChange() && !change.hasPatchset()) {
				statusChanges.add(change);
			}
		}

		final Change currentRevision = revisions.isEmpty() ? null : revisions.get(revisions.size() - 1);
		final Patchset currentPatchset = ticket.getCurrentPatchset();

		/*
		 * TICKET HEADER
		 */
		String href = urlFor(TicketsPage.class, params).toString();
		add(new ExternalLink("ticketNumber", href, "#" + ticket.number));
		Label headerStatus = new Label("headerStatus", ticket.status.toString());
		WicketUtils.setCssClass(headerStatus, TicketsUI.getLozengeClass(ticket.status, false));
		add(headerStatus);
		add(new Label("ticketTitle", ticket.title));
		if (currentPatchset == null) {
			add(new Label("diffstat").setVisible(false));
		} else {
			// calculate the current diffstat of the patchset
			add(new DiffStatPanel("diffstat", ticket.insertions, ticket.deletions));
		}


		/*
		 * TAB TITLES
		 */
		add(new Label("commentCount", "" + comments.size()).setVisible(!comments.isEmpty()));
		add(new Label("commitCount", "" + (currentPatchset == null ? 0 : currentPatchset.commits)).setVisible(currentPatchset != null));


		/*
		 * TICKET AUTHOR and DATE (DISCUSSION TAB)
		 */
		UserModel createdBy = app().users().getUserModel(ticket.createdBy);
		if (createdBy == null) {
			add(new Label("whoCreated", ticket.createdBy));
		} else {
			add(new LinkPanel("whoCreated", null, createdBy.getDisplayName(),
					UserPage.class, WicketUtils.newUsernameParameter(createdBy.username)));
		}

		if (ticket.isProposal()) {
			// clearly indicate this is a change ticket
			add(new Label("creationMessage", getString("gb.proposedThisChange")));
		} else {
			// standard ticket
			add(new Label("creationMessage", getString("gb.createdThisTicket")));
		}

		String dateFormat = app().settings().getString(Keys.web.datestampLongFormat, "EEEE, MMMM d, yyyy");
		String timestampFormat = app().settings().getString(Keys.web.datetimestampLongFormat, "EEEE, MMMM d, yyyy");
		final TimeZone timezone = getTimeZone();
		final DateFormat df = new SimpleDateFormat(dateFormat);
		df.setTimeZone(timezone);
		final DateFormat tsf = new SimpleDateFormat(timestampFormat);
		tsf.setTimeZone(timezone);
		final Calendar cal = Calendar.getInstance(timezone);

		String fuzzydate;
		TimeUtils tu = getTimeUtils();
		Date createdDate = ticket.created;
		if (TimeUtils.isToday(createdDate, timezone)) {
			fuzzydate = tu.today();
		} else if (TimeUtils.isYesterday(createdDate, timezone)) {
			fuzzydate = tu.yesterday();
		} else {
			// calculate a fuzzy time ago date
        	cal.setTime(createdDate);
        	cal.set(Calendar.HOUR_OF_DAY, 0);
        	cal.set(Calendar.MINUTE, 0);
        	cal.set(Calendar.SECOND, 0);
        	cal.set(Calendar.MILLISECOND, 0);
        	createdDate = cal.getTime();
			fuzzydate = getTimeUtils().timeAgo(createdDate);
		}
		Label when = new Label("whenCreated", fuzzydate + ", " + df.format(createdDate));
		WicketUtils.setHtmlTooltip(when, tsf.format(ticket.created));
		add(when);

		String exportHref = urlFor(ExportTicketPage.class, params).toString();
		add(new ExternalLink("exportJson", exportHref, "json"));


		/*
		 * RESPONSIBLE (DISCUSSION TAB)
		 */
		if (StringUtils.isEmpty(ticket.responsible)) {
			add(new Label("responsible"));
		} else {
			UserModel responsible = app().users().getUserModel(ticket.responsible);
			if (responsible == null) {
				add(new Label("responsible", ticket.responsible));
			} else {
				add(new LinkPanel("responsible", null, responsible.getDisplayName(),
						UserPage.class, WicketUtils.newUsernameParameter(responsible.username)));
			}
		}

		/*
		 * MILESTONE PROGRESS (DISCUSSION TAB)
		 */
		if (StringUtils.isEmpty(ticket.milestone)) {
			add(new Label("milestone"));
		} else {
			// link to milestone query
			TicketMilestone tm = app().tickets().getMilestone(repository, ticket.milestone);
			if (tm == null) {
				tm = new TicketMilestone(ticket.milestone);
			}
			PageParameters milestoneParameters;
			if (tm.isOpen()) {
				milestoneParameters = WicketUtils.newOpenTicketsParameter(repositoryName);
			} else {
				milestoneParameters = WicketUtils.newRepositoryParameter(repositoryName);
			}
			milestoneParameters.put(Lucene.milestone.name(), ticket.milestone);
			int progress = 0;
			int open = 0;
			int closed = 0;
			if (tm != null) {
				progress = tm.getProgress();
				open = tm.getOpenTickets();
				closed = tm.getClosedTickets();
			}

			Fragment milestoneProgress = new Fragment("milestone", "milestoneProgressFragment", this);
			milestoneProgress.add(new LinkPanel("link", null, ticket.milestone, TicketsPage.class, milestoneParameters));
			Label label = new Label("progress");
			WicketUtils.setCssStyle(label, "width:" + progress + "%;");
			milestoneProgress.add(label);
			WicketUtils.setHtmlTooltip(milestoneProgress, MessageFormat.format(getString("gb.milestoneProgress"), open, closed));
			add(milestoneProgress);
		}


		/*
		 * TICKET DESCRIPTION (DISCUSSION TAB)
		 */
		String desc;
		if (StringUtils.isEmpty(ticket.body)) {
			desc = getString("gb.noDescriptionGiven");
		} else {
			String bugtraq = bugtraqProcessor().processText(getRepository(), repositoryName, ticket.body);
			String html = MarkdownUtils.transformGFM(app().settings(), bugtraq, ticket.repository);
			String safeHtml = app().xssFilter().relaxed(html);
			desc = safeHtml;
		}
		add(new Label("ticketDescription", desc).setEscapeModelStrings(false));


		/*
		 * PARTICIPANTS (DISCUSSION TAB)
		 */
		if (app().settings().getBoolean(Keys.web.allowGravatar, true)) {
			// gravatar allowed
			List<String> participants = ticket.getParticipants();
			add(new Label("participantsLabel", MessageFormat.format(getString(participants.size() > 1 ? "gb.nParticipants" : "gb.oneParticipant"),
					"<b>" + participants.size() + "</b>")).setEscapeModelStrings(false));
			ListDataProvider<String> participantsDp = new ListDataProvider<String>(participants);
			DataView<String> participantsView = new DataView<String>("participants", participantsDp) {
				private static final long serialVersionUID = 1L;

				@Override
				public void populateItem(final Item<String> item) {
					String username = item.getModelObject();
					UserModel user = app().users().getUserModel(username);
					if (user == null) {
						user = new UserModel(username);
					}
					item.add(new AvatarImage("participant", user.getDisplayName(),
							user.emailAddress, null, 25, true));
				}
			};
			add(participantsView);
		} else {
			// gravatar prohibited
			add(new Label("participantsLabel").setVisible(false));
			add(new Label("participants").setVisible(false));
		}


		/*
		 * LARGE STATUS INDICATOR WITH ICON (DISCUSSION TAB->SIDE BAR)
		 */
		Fragment ticketStatus = new Fragment("ticketStatus", "ticketStatusFragment", this);
		Label ticketIcon = TicketsUI.getStateIcon("ticketIcon", ticket);
		ticketStatus.add(ticketIcon);
		ticketStatus.add(new Label("ticketStatus", ticket.status.toString()));
		WicketUtils.setCssClass(ticketStatus, TicketsUI.getLozengeClass(ticket.status, false));
		add(ticketStatus);


		/*
		 * UPDATE FORM (DISCUSSION TAB)
		 */
		if (user.canEdit(ticket, repository) && app().tickets().isAcceptingTicketUpdates(repository)) {
			if (user.canAdmin(ticket, repository) && ticket.isOpen()) {
				/*
				 * OPEN TICKET
				 */
				Fragment controls = new Fragment("controls", "openControlsFragment", this);

				/*
				 * STATUS
				 */
				List<Status> choices = new ArrayList<Status>();
				if (ticket.isProposal()) {
					choices.addAll(Arrays.asList(TicketModel.Status.proposalWorkflow));
				} else if (ticket.isBug()) {
					choices.addAll(Arrays.asList(TicketModel.Status.bugWorkflow));
				} else {
					choices.addAll(Arrays.asList(TicketModel.Status.requestWorkflow));
				}
				choices.remove(ticket.status);

				ListDataProvider<Status> workflowDp = new ListDataProvider<Status>(choices);
				DataView<Status> statusView = new DataView<Status>("newStatus", workflowDp) {
					private static final long serialVersionUID = 1L;

					@Override
					public void populateItem(final Item<Status> item) {
						SimpleAjaxLink<Status> link = new SimpleAjaxLink<Status>("link", item.getModel()) {

							private static final long serialVersionUID = 1L;

							@Override
							public void onClick(AjaxRequestTarget target) {
								Status status = getModel().getObject();
								Change change = new Change(user.username);
								change.setField(Field.status, status);
								if (!ticket.isWatching(user.username)) {
									change.watch(user.username);
								}
								TicketModel update = app().tickets().updateTicket(repository, ticket.number, change);
								app().tickets().createNotifier().sendMailing(update);
								redirectTo(TicketsPage.class, getPageParameters());
							}
						};
						String css = TicketsUI.getStatusClass(item.getModel().getObject());
						WicketUtils.setCssClass(link, css);
						item.add(link);
					}
				};
				controls.add(statusView);

				/*
				 * RESPONSIBLE LIST
				 */
				Set<String> userlist = new TreeSet<String>(ticket.getParticipants());
				if (UserModel.ANONYMOUS.canPush(getRepositoryModel())
						|| AuthorizationControl.AUTHENTICATED == getRepositoryModel().authorizationControl) {
					// 	authorization is ANONYMOUS or AUTHENTICATED (i.e. all users can be set responsible)
					userlist.addAll(app().users().getAllUsernames());
				} else {
					// authorization is by NAMED users (users with PUSH permission can be set responsible)
					for (RegistrantAccessPermission rp : app().repositories().getUserAccessPermissions(getRepositoryModel())) {
						if (rp.permission.atLeast(AccessPermission.PUSH)) {
							userlist.add(rp.registrant);
						}
					}
				}
				List<TicketResponsible> responsibles = new ArrayList<TicketResponsible>();
				if (!StringUtils.isEmpty(ticket.responsible)) {
					// exclude the current responsible
					userlist.remove(ticket.responsible);
				}
				for (String username : userlist) {
					UserModel u = app().users().getUserModel(username);
					if (u != null) {
						responsibles.add(new TicketResponsible(u));
					}
				}
				Collections.sort(responsibles);
				responsibles.add(new TicketResponsible(ESC_NIL, "", ""));
				ListDataProvider<TicketResponsible> responsibleDp = new ListDataProvider<TicketResponsible>(responsibles);
				DataView<TicketResponsible> responsibleView = new DataView<TicketResponsible>("newResponsible", responsibleDp) {
					private static final long serialVersionUID = 1L;

					@Override
					public void populateItem(final Item<TicketResponsible> item) {
						SimpleAjaxLink<TicketResponsible> link = new SimpleAjaxLink<TicketResponsible>("link", item.getModel()) {

							private static final long serialVersionUID = 1L;

							@Override
							public void onClick(AjaxRequestTarget target) {
								TicketResponsible responsible = getModel().getObject();
								Change change = new Change(user.username);
								change.setField(Field.responsible, responsible.username);
								if (!StringUtils.isEmpty(responsible.username)) {
									if (!ticket.isWatching(responsible.username)) {
										change.watch(responsible.username);
									}
								}
								if (!ticket.isWatching(user.username)) {
									change.watch(user.username);
								}
								TicketModel update = app().tickets().updateTicket(repository, ticket.number, change);
								app().tickets().createNotifier().sendMailing(update);
								redirectTo(TicketsPage.class, getPageParameters());
							}
						};
						item.add(link);
					}
				};
				controls.add(responsibleView);

				/*
				 * MILESTONE LIST
				 */
				List<TicketMilestone> milestones = app().tickets().getMilestones(repository, Status.Open);
				if (!StringUtils.isEmpty(ticket.milestone)) {
					for (TicketMilestone milestone : milestones) {
						if (milestone.name.equals(ticket.milestone)) {
							milestones.remove(milestone);
							break;
						}
					}
				}
				milestones.add(new TicketMilestone(ESC_NIL));
				ListDataProvider<TicketMilestone> milestoneDp = new ListDataProvider<TicketMilestone>(milestones);
				DataView<TicketMilestone> milestoneView = new DataView<TicketMilestone>("newMilestone", milestoneDp) {
					private static final long serialVersionUID = 1L;

					@Override
					public void populateItem(final Item<TicketMilestone> item) {
						SimpleAjaxLink<TicketMilestone> link = new SimpleAjaxLink<TicketMilestone>("link", item.getModel()) {

							private static final long serialVersionUID = 1L;

							@Override
							public void onClick(AjaxRequestTarget target) {
								TicketMilestone milestone = getModel().getObject();
								Change change = new Change(user.username);
								if (NIL.equals(milestone.name) || ESC_NIL.equals(milestone.name)) {
									change.setField(Field.milestone, "");
								} else {
									change.setField(Field.milestone, milestone.name);
								}
								if (!ticket.isWatching(user.username)) {
									change.watch(user.username);
								}
								TicketModel update = app().tickets().updateTicket(repository, ticket.number, change);
								app().tickets().createNotifier().sendMailing(update);
								redirectTo(TicketsPage.class, getPageParameters());
							}
						};
						item.add(link);
					}
				};
				controls.add(milestoneView);

				String editHref = urlFor(EditTicketPage.class, params).toString();
				controls.add(new ExternalLink("editLink", editHref, getString("gb.edit")));

				add(controls);
			} else {
				/*
				 * CLOSED TICKET
				 */
				Fragment controls = new Fragment("controls", "closedControlsFragment", this);

				String editHref = urlFor(EditTicketPage.class, params).toString();
				controls.add(new ExternalLink("editLink", editHref, getString("gb.edit")));

				add(controls);
			}
		} else {
			add(new Label("controls").setVisible(false));
		}


		/*
		 * TICKET METADATA
		 */
		add(new Label("ticketType", ticket.type.toString()));

		add(new Label("priority", ticket.priority.toString()));
		add(new Label("severity", ticket.severity.toString()));

		if (StringUtils.isEmpty(ticket.topic)) {
			add(new Label("ticketTopic").setVisible(false));
		} else {
			// process the topic using the bugtraq config to link things
			String topic = bugtraqProcessor().processText(getRepository(), repositoryName, ticket.topic);
			String safeTopic = app().xssFilter().relaxed(topic);
			add(new Label("ticketTopic", safeTopic).setEscapeModelStrings(false));
		}




		/*
		 * VOTERS
		 */
		List<String> voters = ticket.getVoters();
		Label votersCount = new Label("votes", "" + voters.size());
		if (voters.size() == 0) {
			WicketUtils.setCssClass(votersCount, "badge");
		} else {
			WicketUtils.setCssClass(votersCount, "badge badge-info");
		}
		add(votersCount);
		if (user.isAuthenticated && app().tickets().isAcceptingTicketUpdates(repository)) {
			Model<String> model;
			if (ticket.isVoter(user.username)) {
				model = Model.of(getString("gb.removeVote"));
			} else {
				model = Model.of(MessageFormat.format(getString("gb.vote"), ticket.type.toString()));
			}
			SimpleAjaxLink<String> link = new SimpleAjaxLink<String>("voteLink", model) {

				private static final long serialVersionUID = 1L;

				@Override
				public void onClick(AjaxRequestTarget target) {
					Change change = new Change(user.username);
					if (ticket.isVoter(user.username)) {
						change.unvote(user.username);
					} else {
						change.vote(user.username);
					}
					app().tickets().updateTicket(repository, ticket.number, change);
					redirectTo(TicketsPage.class, getPageParameters());
				}
			};
			add(link);
		} else {
			add(new Label("voteLink").setVisible(false));
		}


		/*
		 * WATCHERS
		 */
		List<String> watchers = ticket.getWatchers();
		Label watchersCount = new Label("watchers", "" + watchers.size());
		if (watchers.size() == 0) {
			WicketUtils.setCssClass(watchersCount, "badge");
		} else {
			WicketUtils.setCssClass(watchersCount, "badge badge-info");
		}
		add(watchersCount);
		if (user.isAuthenticated && app().tickets().isAcceptingTicketUpdates(repository)) {
			Model<String> model;
			if (ticket.isWatching(user.username)) {
				model = Model.of(getString("gb.stopWatching"));
			} else {
				model = Model.of(MessageFormat.format(getString("gb.watch"), ticket.type.toString()));
			}
			SimpleAjaxLink<String> link = new SimpleAjaxLink<String>("watchLink", model) {

				private static final long serialVersionUID = 1L;

				@Override
				public void onClick(AjaxRequestTarget target) {
					Change change = new Change(user.username);
					if (ticket.isWatching(user.username)) {
						change.unwatch(user.username);
					} else {
						change.watch(user.username);
					}
					app().tickets().updateTicket(repository, ticket.number, change);
					redirectTo(TicketsPage.class, getPageParameters());
				}
			};
			add(link);
		} else {
			add(new Label("watchLink").setVisible(false));
		}


		/*
		 * TOPIC & LABELS (DISCUSSION TAB->SIDE BAR)
		 */
		ListDataProvider<String> labelsDp = new ListDataProvider<String>(ticket.getLabels());
		DataView<String> labelsView = new DataView<String>("labels", labelsDp) {
			private static final long serialVersionUID = 1L;

			@Override
			public void populateItem(final Item<String> item) {
				final String value = item.getModelObject();
				Label label = new Label("label", value);
				TicketLabel tLabel = app().tickets().getLabel(repository, value);
				String background = MessageFormat.format("background-color:{0};", tLabel.color);
				label.add(new SimpleAttributeModifier("style", background));
				item.add(label);
			}
		};

		add(labelsView);


		/*
		 * COMMENTS & STATUS CHANGES (DISCUSSION TAB)
		 */
		if (comments.size() == 0) {
			add(new Label("discussion").setVisible(false));
		} else {
			Fragment discussionFragment = new Fragment("discussion", "discussionFragment", this);
			ListDataProvider<Change> discussionDp = new ListDataProvider<Change>(discussion);
			DataView<Change> discussionView = new DataView<Change>("discussion", discussionDp) {
				private static final long serialVersionUID = 1L;

				@Override
				public void populateItem(final Item<Change> item) {
					final Change entry = item.getModelObject();
					if (entry.isMerge()) {
						/*
						 * MERGE
						 */
						String resolvedBy = entry.getString(Field.mergeSha);

						// identify the merged patch, it is likely the last
						Patchset mergedPatch = null;
						for (Change c : revisions) {
							if (c.patchset.tip.equals(resolvedBy)) {
								mergedPatch = c.patchset;
								break;
							}
						}

						String commitLink;
						if (mergedPatch == null) {
							// shouldn't happen, but just-in-case
							int len = app().settings().getInteger(Keys.web.shortCommitIdLength, 6);
							commitLink = resolvedBy.substring(0, len);
						} else {
							// expected result
							commitLink = mergedPatch.toString();
						}

						Fragment mergeFragment = new Fragment("entry", "mergeFragment", this);
						mergeFragment.add(new LinkPanel("commitLink", null, commitLink,
								CommitPage.class, WicketUtils.newObjectParameter(repositoryName, resolvedBy)));
						mergeFragment.add(new Label("toBranch", MessageFormat.format(getString("gb.toBranch"),
								"<b>" + ticket.mergeTo + "</b>")).setEscapeModelStrings(false));
						addUserAttributions(mergeFragment, entry, 0);
						addDateAttributions(mergeFragment, entry);

						item.add(mergeFragment);
					} else if (entry.isStatusChange()) {
						/*
						 *  STATUS CHANGE
						 */
						Fragment frag = new Fragment("entry", "statusFragment", this);
						Label status = new Label("statusChange", entry.getStatus().toString());
						String css = TicketsUI.getLozengeClass(entry.getStatus(), false);
						WicketUtils.setCssClass(status, css);
						frag.add(status);
						addUserAttributions(frag, entry, avatarWidth);
						addDateAttributions(frag, entry);
						item.add(frag);
					} else {
						/*
						 * COMMENT
						 */
						String bugtraq = bugtraqProcessor().processText(getRepository(), repositoryName, entry.comment.text);
						String comment = MarkdownUtils.transformGFM(app().settings(), bugtraq, repositoryName);
						String safeComment = app().xssFilter().relaxed(comment);
						Fragment frag = new Fragment("entry", "commentFragment", this);
						Label commentIcon = new Label("commentIcon");
						if (entry.comment.src == CommentSource.Email) {
							WicketUtils.setCssClass(commentIcon, "iconic-mail");
						} else {
							WicketUtils.setCssClass(commentIcon, "iconic-comment-alt2-stroke");
						}
						frag.add(commentIcon);
						frag.add(new Label("comment", safeComment).setEscapeModelStrings(false));
						addUserAttributions(frag, entry, avatarWidth);
						addDateAttributions(frag, entry);
						item.add(frag);
					}
				}
			};
			discussionFragment.add(discussionView);
			add(discussionFragment);
		}

		/*
		 * ADD COMMENT PANEL
		 */
		if (UserModel.ANONYMOUS.equals(user)
				|| !repository.isBare
				|| repository.isFrozen
				|| repository.isMirror) {

			// prohibit comments for anonymous users, local working copy repos,
			// frozen repos, and mirrors
			add(new Label("newComment").setVisible(false));
		} else {
			// permit user to comment
			Fragment newComment = new Fragment("newComment", "newCommentFragment", this);
			AvatarImage img = new AvatarImage("newCommentAvatar", user.username, user.emailAddress,
					"gravatar-round", avatarWidth, true);
			newComment.add(img);
			CommentPanel commentPanel = new CommentPanel("commentPanel", user, ticket, null, TicketsPage.class);
			commentPanel.setRepository(repositoryName);
			newComment.add(commentPanel);
			add(newComment);
		}


		/*
		 *  PATCHSET TAB
		 */
		if (currentPatchset == null) {
			// no patchset available
			RepositoryUrl repoUrl = getRepositoryUrl(user, repository);
			boolean canPropose = repoUrl != null && repoUrl.hasPermission() && repoUrl.permission.atLeast(AccessPermission.CLONE) && !UserModel.ANONYMOUS.equals(user);
			if (ticket.isOpen() && app().tickets().isAcceptingNewPatchsets(repository) && canPropose) {
				// ticket & repo will accept a proposal patchset
				// show the instructions for proposing a patchset
				Fragment changeIdFrag = new Fragment("patchset", "proposeFragment", this);
				changeIdFrag.add(new Label("proposeInstructions", MarkdownUtils.transformMarkdown(getString("gb.proposeInstructions"))).setEscapeModelStrings(false));
				changeIdFrag.add(new Label("ptWorkflow", MessageFormat.format(getString("gb.proposeWith"), "Barnum")));
				changeIdFrag.add(new Label("ptWorkflowSteps", getProposeWorkflow("propose_pt.md", repoUrl.url, ticket.number)).setEscapeModelStrings(false));
				changeIdFrag.add(new Label("gitWorkflow", MessageFormat.format(getString("gb.proposeWith"), "Git")));
				changeIdFrag.add(new Label("gitWorkflowSteps", getProposeWorkflow("propose_git.md", repoUrl.url, ticket.number)).setEscapeModelStrings(false));
				add(changeIdFrag);
			} else {
				// explain why you can't propose a patchset
				Fragment fragment = new Fragment("patchset", "canNotProposeFragment", this);
				String reason = "";
				if (ticket.isClosed()) {
					reason = getString("gb.ticketIsClosed");
				} else if (repository.isMirror) {
					reason = getString("gb.repositoryIsMirror");
				} else if (repository.isFrozen) {
					reason = getString("gb.repositoryIsFrozen");
				} else if (!repository.acceptNewPatchsets) {
					reason = getString("gb.repositoryDoesNotAcceptPatchsets");
				} else if (!canPropose) {
					if (UserModel.ANONYMOUS.equals(user)) {
						reason = getString("gb.anonymousCanNotPropose");
					} else {
						reason = getString("gb.youDoNotHaveClonePermission");
					}
				} else {
					reason = getString("gb.serverDoesNotAcceptPatchsets");
				}
				fragment.add(new Label("reason", reason));
				add(fragment);
			}
		} else {
			// show current patchset
			Fragment patchsetFrag = new Fragment("patchset", "patchsetFragment", this);
			patchsetFrag.add(new Label("commitsInPatchset", MessageFormat.format(getString("gb.commitsInPatchsetN"), currentPatchset.number)));

			patchsetFrag.add(createMergePanel(user, repository));

			if (ticket.isOpen()) {
				// current revision
				MarkupContainer panel = createPatchsetPanel("panel", repository, user);
				patchsetFrag.add(panel);
				addUserAttributions(patchsetFrag, currentRevision, avatarWidth);
				addUserAttributions(panel, currentRevision, 0);
				addDateAttributions(panel, currentRevision);
			} else {
				// current revision
				patchsetFrag.add(new Label("panel").setVisible(false));
			}

			// commits
			List<RevCommit> commits = JGitUtils.getRevLog(getRepository(), currentPatchset.base, currentPatchset.tip);
			ListDataProvider<RevCommit> commitsDp = new ListDataProvider<RevCommit>(commits);
			DataView<RevCommit> commitsView = new DataView<RevCommit>("commit", commitsDp) {
				private static final long serialVersionUID = 1L;

				@Override
				public void populateItem(final Item<RevCommit> item) {
					RevCommit commit = item.getModelObject();
					PersonIdent author = commit.getAuthorIdent();
					item.add(new AvatarImage("authorAvatar", author.getName(), author.getEmailAddress(), null, 16, false));
					item.add(new Label("author", commit.getAuthorIdent().getName()));
					item.add(new LinkPanel("commitId", null, getShortObjectId(commit.getName()),
							CommitPage.class, WicketUtils.newObjectParameter(repositoryName, commit.getName()), true));
					item.add(new LinkPanel("diff", "link", getString("gb.diff"), CommitDiffPage.class,
							WicketUtils.newObjectParameter(repositoryName, commit.getName()), true));
					item.add(new Label("title", StringUtils.trimString(commit.getShortMessage(), Constants.LEN_SHORTLOG_REFS)));
					item.add(WicketUtils.createDateLabel("commitDate", JGitUtils.getAuthorDate(commit), GitBlitWebSession
							.get().getTimezone(), getTimeUtils(), false));
					item.add(new DiffStatPanel("commitDiffStat", 0, 0, true));
				}
			};
			patchsetFrag.add(commitsView);
			add(patchsetFrag);
		}


		/*
		 * ACTIVITY TAB
		 */
		Fragment revisionHistory = new Fragment("activity", "activityFragment", this);
		List<Change> events = new ArrayList<Change>(ticket.changes);
		Collections.sort(events);
		Collections.reverse(events);
		ListDataProvider<Change> eventsDp = new ListDataProvider<Change>(events);
		DataView<Change> eventsView = new DataView<Change>("event", eventsDp) {
			private static final long serialVersionUID = 1L;

			@Override
			public void populateItem(final Item<Change> item) {
				Change event = item.getModelObject();

				addUserAttributions(item, event, 16);

				if (event.hasPatchset()) {
					// patchset
					Patchset patchset = event.patchset;
					String what;
					if (event.isStatusChange() && (Status.New == event.getStatus())) {
						what = getString("gb.proposedThisChange");
					} else if (patchset.rev == 1) {
						what = MessageFormat.format(getString("gb.uploadedPatchsetN"), patchset.number);
					} else {
						if (patchset.added == 1) {
							what = getString("gb.addedOneCommit");
						} else {
							what = MessageFormat.format(getString("gb.addedNCommits"), patchset.added);
						}
					}
					item.add(new Label("what", what));

					LinkPanel psr = new LinkPanel("patchsetRevision", null, patchset.number + "-" + patchset.rev,
							ComparePage.class, WicketUtils.newRangeParameter(repositoryName, patchset.parent == null ? patchset.base : patchset.parent, patchset.tip), true);
					WicketUtils.setHtmlTooltip(psr, patchset.toString());
					WicketUtils.setCssClass(psr, "aui-lozenge aui-lozenge-subtle");
					item.add(psr);
					String typeCss = getPatchsetTypeCss(patchset.type);
					Label typeLabel = new Label("patchsetType", patchset.type.toString());
					if (typeCss == null) {
						typeLabel.setVisible(false);
					} else {
						WicketUtils.setCssClass(typeLabel, typeCss);
					}
					item.add(typeLabel);

					Link<Void> deleteLink = createDeletePatchsetLink(repository, patchset);
					
					if (user.canDeleteRef(repository)) {
						item.add(deleteLink.setVisible(patchset.canDelete));
					} else {
						item.add(deleteLink.setVisible(false));
					}

					// show commit diffstat
					item.add(new DiffStatPanel("patchsetDiffStat", patchset.insertions, patchset.deletions, patchset.rev > 1));
				} else if (event.hasComment()) {
					// comment
					item.add(new Label("what", getString("gb.commented")));
					item.add(new Label("patchsetRevision").setVisible(false));
					item.add(new Label("patchsetType").setVisible(false));
					item.add(new Label("deleteRevision").setVisible(false));
					item.add(new Label("patchsetDiffStat").setVisible(false));
				} else if (event.hasReference()) {
					// reference
					switch (event.reference.getSourceType()) {
						case Commit: {
							final int shaLen = app().settings().getInteger(Keys.web.shortCommitIdLength, 6);
							
							item.add(new Label("what", getString("gb.referencedByCommit")));
							LinkPanel psr = new LinkPanel("patchsetRevision", null, event.reference.toString().substring(0, shaLen),
									CommitPage.class, WicketUtils.newObjectParameter(repositoryName, event.reference.toString()), true);
							WicketUtils.setHtmlTooltip(psr, event.reference.toString());
							WicketUtils.setCssClass(psr, "ticketReference-commit shortsha1");
							item.add(psr);
							
						} break;
						
						case Ticket: {
							final String text = MessageFormat.format("ticket/{0}", event.reference.ticketId);

							item.add(new Label("what", getString("gb.referencedByTicket")));
							//NOTE: Ideally reference the exact comment using reference.toString,
							//		however anchor hash is used and is escaped resulting in broken link
							LinkPanel psr = new LinkPanel("patchsetRevision", null,  text,
									TicketsPage.class, WicketUtils.newObjectParameter(repositoryName, event.reference.ticketId.toString()), true);
							WicketUtils.setCssClass(psr, "ticketReference-comment");
							item.add(psr);
						} break;
					
						default: {
							item.add(new Label("what").setVisible(false));
							item.add(new Label("patchsetRevision").setVisible(false));
						}
					}
					
					item.add(new Label("patchsetType").setVisible(false));
					item.add(new Label("deleteRevision").setVisible(false));
					item.add(new Label("patchsetDiffStat").setVisible(false));
				} else if (event.hasReview()) {
					// review
					String score;
					switch (event.review.score) {
					case approved:
						score = "<span style='color:darkGreen'>" + getScoreDescription(event.review.score) + "</span>";
						break;
					case vetoed:
						score = "<span style='color:darkRed'>" + getScoreDescription(event.review.score) + "</span>";
						break;
					default:
						score = getScoreDescription(event.review.score);
					}
					item.add(new Label("what", MessageFormat.format(getString("gb.reviewedPatchsetRev"),
							event.review.patchset, event.review.rev, score))
							.setEscapeModelStrings(false));
					item.add(new Label("patchsetRevision").setVisible(false));
					item.add(new Label("patchsetType").setVisible(false));
					item.add(new Label("deleteRevision").setVisible(false));
					item.add(new Label("patchsetDiffStat").setVisible(false));
				} else {
					// field change
					item.add(new Label("patchsetRevision").setVisible(false));
					item.add(new Label("patchsetType").setVisible(false));
					item.add(new Label("deleteRevision").setVisible(false));
					item.add(new Label("patchsetDiffStat").setVisible(false));

					String what = "";
					if (event.isStatusChange()) {
					switch (event.getStatus()) {
					case New:
						if (ticket.isProposal()) {
							what = getString("gb.proposedThisChange");
						} else {
							what = getString("gb.createdThisTicket");
						}
						break;
					default:
						break;
					}
					}
					item.add(new Label("what", what).setVisible(what.length() > 0));
				}

				addDateAttributions(item, event);

				if (event.hasFieldChanges()) {
					StringBuilder sb = new StringBuilder();
					sb.append("<table class=\"summary\"><tbody>");
					for (Map.Entry<Field, String> entry : event.fields.entrySet()) {
						String value;
						switch (entry.getKey()) {
							case body:
								String body = entry.getValue();
								if (event.isStatusChange() && Status.New == event.getStatus() && StringUtils.isEmpty(body)) {
									// ignore initial empty description
									continue;
								}
								// trim body changes
								if (StringUtils.isEmpty(body)) {
									value = "<i>" + ESC_NIL + "</i>";
								} else {
									value = StringUtils.trimString(body, Constants.LEN_SHORTLOG_REFS);
								}
								break;
							case status:
								// special handling for status
								Status status = event.getStatus();
								String css = TicketsUI.getLozengeClass(status, true);
								value = String.format("<span class=\"%1$s\">%2$s</span>", css, status.toString());
								break;
							default:
								value = StringUtils.isEmpty(entry.getValue()) ? ("<i>" + ESC_NIL + "</i>") : StringUtils.escapeForHtml(entry.getValue(), false);
								break;
						}
						sb.append("<tr><th style=\"width:70px;\">");
						try {
							sb.append(getString("gb." + entry.getKey().name()));
						} catch (Exception e) {
							sb.append(entry.getKey().name());
						}
						sb.append("</th><td>");
						sb.append(value);
						sb.append("</td></tr>");
					}
					sb.append("</tbody></table>");
					String safeHtml = app().xssFilter().relaxed(sb.toString());
					item.add(new Label("fields", safeHtml).setEscapeModelStrings(false));
				} else {
					item.add(new Label("fields").setVisible(false));
				}
			}
		};
		revisionHistory.add(eventsView);
		add(revisionHistory);
	}

	protected void addUserAttributions(MarkupContainer container, Change entry, int avatarSize) {
		UserModel commenter = app().users().getUserModel(entry.author);
		if (commenter == null) {
			// unknown user
			container.add(new AvatarImage("changeAvatar", entry.author,
					entry.author, null, avatarSize, false).setVisible(avatarSize > 0));
			container.add(new Label("changeAuthor", entry.author.toLowerCase()));
		} else {
			// known user
			container.add(new AvatarImage("changeAvatar", commenter.getDisplayName(),
					commenter.emailAddress, avatarSize > 24 ? "gravatar-round" : null,
							avatarSize, true).setVisible(avatarSize > 0));
			container.add(new LinkPanel("changeAuthor", null, commenter.getDisplayName(),
					UserPage.class, WicketUtils.newUsernameParameter(commenter.username)));
		}
	}

	protected void addDateAttributions(MarkupContainer container, Change entry) {
		container.add(WicketUtils.createDateLabel("changeDate", entry.date, GitBlitWebSession
				.get().getTimezone(), getTimeUtils(), false));

		// set the id attribute
		if (entry.hasComment()) {
			container.setOutputMarkupId(true);
			container.add(new AttributeModifier("id", Model.of(entry.getId())));
			ExternalLink link = new ExternalLink("changeLink", "#" + entry.getId());
			container.add(link);
		} else {
			container.add(new Label("changeLink").setVisible(false));
		}
	}

	protected String getProposeWorkflow(String resource, String url, long ticketId) {
		String md = readResource(resource);
		md = md.replace("${url}", url);
		md = md.replace("${repo}", StringUtils.getLastPathElement(StringUtils.stripDotGit(repositoryName)));
		md = md.replace("${ticketId}", "" + ticketId);
		md = md.replace("${patchset}", "" + 1);
		md = md.replace("${reviewBranch}", Repository.shortenRefName(PatchsetCommand.getTicketBranch(ticketId)));
		String integrationBranch = Repository.shortenRefName(getRepositoryModel().mergeTo);
		if (!StringUtils.isEmpty(ticket.mergeTo)) {
			integrationBranch = ticket.mergeTo;
		}
		md = md.replace("${integrationBranch}", integrationBranch);
		return MarkdownUtils.transformMarkdown(md);
	}

	protected Fragment createPatchsetPanel(String wicketId, RepositoryModel repository, UserModel user) {
		final Patchset currentPatchset = ticket.getCurrentPatchset();
		List<Patchset> patchsets = new ArrayList<Patchset>(ticket.getPatchsetRevisions(currentPatchset.number));
		patchsets.remove(currentPatchset);
		Collections.reverse(patchsets);

		Fragment panel = new Fragment(wicketId, "collapsiblePatchsetFragment", this);

		// patchset header
		String ps = "<b>" + currentPatchset.number + "</b>";
		if (currentPatchset.rev == 1) {
			panel.add(new Label("uploadedWhat", MessageFormat.format(getString("gb.uploadedPatchsetN"), ps)).setEscapeModelStrings(false));
		} else {
			String rev = "<b>" + currentPatchset.rev + "</b>";
			panel.add(new Label("uploadedWhat", MessageFormat.format(getString("gb.uploadedPatchsetNRevisionN"), ps, rev)).setEscapeModelStrings(false));
		}
		panel.add(new LinkPanel("patchId", null, "rev " + currentPatchset.rev,
				CommitPage.class, WicketUtils.newObjectParameter(repositoryName, currentPatchset.tip), true));

		// compare menu
		panel.add(new LinkPanel("compareMergeBase", null, getString("gb.compareToMergeBase"),
				ComparePage.class, WicketUtils.newRangeParameter(repositoryName, currentPatchset.base, currentPatchset.tip), true));

		ListDataProvider<Patchset> compareMenuDp = new ListDataProvider<Patchset>(patchsets);
		DataView<Patchset> compareMenu = new DataView<Patchset>("comparePatch", compareMenuDp) {
			private static final long serialVersionUID = 1L;
			@Override
			public void populateItem(final Item<Patchset> item) {
				Patchset patchset = item.getModelObject();
				LinkPanel link = new LinkPanel("compareLink", null,
						MessageFormat.format(getString("gb.compareToN"), patchset.number + "-" + patchset.rev),
						ComparePage.class, WicketUtils.newRangeParameter(getRepositoryModel().name,
								patchset.tip, currentPatchset.tip), true);
				item.add(link);

			}
		};
		panel.add(compareMenu);


		// reviews
		List<Change> reviews = ticket.getReviews(currentPatchset);
		ListDataProvider<Change> reviewsDp = new ListDataProvider<Change>(reviews);
		DataView<Change> reviewsView = new DataView<Change>("reviews", reviewsDp) {
			private static final long serialVersionUID = 1L;

			@Override
			public void populateItem(final Item<Change> item) {
				Change change = item.getModelObject();
				final String username = change.author;
				UserModel user = app().users().getUserModel(username);
				if (user == null) {
					item.add(new Label("reviewer", username));
				} else {
					item.add(new LinkPanel("reviewer", null, user.getDisplayName(),
							UserPage.class, WicketUtils.newUsernameParameter(username)));
				}

				// indicate review score
				Review review = change.review;
				Label scoreLabel = new Label("score");
				String scoreClass = getScoreClass(review.score);
				String tooltip = getScoreDescription(review.score);
				WicketUtils.setCssClass(scoreLabel, scoreClass);
				if (!StringUtils.isEmpty(tooltip)) {
					WicketUtils.setHtmlTooltip(scoreLabel, tooltip);
				}
				item.add(scoreLabel);
			}
		};
		panel.add(reviewsView);


		if (ticket.isOpen() && user.canReviewPatchset(repository) && app().tickets().isAcceptingTicketUpdates(repository)) {
			// can only review open tickets
			Review myReview = null;
			for (Change change : ticket.getReviews(currentPatchset)) {
				if (change.author.equals(user.username)) {
					myReview = change.review;
				}
			}

			// user can review, add review controls
			Fragment reviewControls = new Fragment("reviewControls", "reviewControlsFragment", this);

			// show "approve" button if no review OR not current score
			if (user.canApprovePatchset(repository) && (myReview == null || Score.approved != myReview.score)) {
				reviewControls.add(createReviewLink("approveLink", Score.approved));
			} else {
				reviewControls.add(new Label("approveLink").setVisible(false));
			}

			// show "looks good" button if no review OR not current score
			if (myReview == null || Score.looks_good != myReview.score) {
				reviewControls.add(createReviewLink("looksGoodLink", Score.looks_good));
			} else {
				reviewControls.add(new Label("looksGoodLink").setVisible(false));
			}

			// show "needs improvement" button if no review OR not current score
			if (myReview == null || Score.needs_improvement != myReview.score) {
				reviewControls.add(createReviewLink("needsImprovementLink", Score.needs_improvement));
			} else {
				reviewControls.add(new Label("needsImprovementLink").setVisible(false));
			}

			// show "veto" button if no review OR not current score
			if (user.canVetoPatchset(repository) && (myReview == null || Score.vetoed != myReview.score)) {
				reviewControls.add(createReviewLink("vetoLink", Score.vetoed));
			} else {
				reviewControls.add(new Label("vetoLink").setVisible(false));
			}
			panel.add(reviewControls);
		} else {
			// user can not review
			panel.add(new Label("reviewControls").setVisible(false));
		}

		String insertions = MessageFormat.format("<span style=\"color:darkGreen;font-weight:bold;\">+{0}</span>", ticket.insertions);
		String deletions = MessageFormat.format("<span style=\"color:darkRed;font-weight:bold;\">-{0}</span>", ticket.deletions);
		panel.add(new Label("patchsetStat", MessageFormat.format(StringUtils.escapeForHtml(getString("gb.diffStat"), false),
				insertions, deletions)).setEscapeModelStrings(false));

		// changed paths list
		List<PathChangeModel> paths = JGitUtils.getFilesInRange(getRepository(), currentPatchset.base, currentPatchset.tip);
		ListDataProvider<PathChangeModel> pathsDp = new ListDataProvider<PathChangeModel>(paths);
		DataView<PathChangeModel> pathsView = new DataView<PathChangeModel>("changedPath", pathsDp) {
			private static final long serialVersionUID = 1L;
			int counter;

			@Override
			public void populateItem(final Item<PathChangeModel> item) {
				final PathChangeModel entry = item.getModelObject();
				Label changeType = new Label("changeType", "");
				WicketUtils.setChangeTypeCssClass(changeType, entry.changeType);
				setChangeTypeTooltip(changeType, entry.changeType);
				item.add(changeType);

				boolean hasSubmodule = false;
				String submodulePath = null;
				if (entry.isTree()) {
					// tree
					item.add(new LinkPanel("pathName", null, entry.path, TreePage.class,
							WicketUtils
									.newPathParameter(repositoryName, currentPatchset.tip, entry.path), true));
					item.add(new Label("diffStat").setVisible(false));
				} else if (entry.isSubmodule()) {
					// submodule
					String submoduleId = entry.objectId;
					SubmoduleModel submodule = getSubmodule(entry.path);
					submodulePath = submodule.gitblitPath;
					hasSubmodule = submodule.hasSubmodule;

					item.add(new LinkPanel("pathName", "list", entry.path + " @ " +
							getShortObjectId(submoduleId), TreePage.class,
							WicketUtils.newPathParameter(submodulePath, submoduleId, ""), true).setEnabled(hasSubmodule));
					item.add(new Label("diffStat").setVisible(false));
				} else {
					// blob
					String displayPath = entry.path;
					String path = entry.path;
					if (entry.isSymlink()) {
						RevCommit commit = JGitUtils.getCommit(getRepository(), PatchsetCommand.getTicketBranch(ticket.number));
						path = JGitUtils.getStringContent(getRepository(), commit.getTree(), path);
						displayPath = entry.path + " -> " + path;
					}

					if (entry.changeType.equals(ChangeType.ADD)) {
						// add show view
						item.add(new LinkPanel("pathName", "list", displayPath, BlobPage.class,
								WicketUtils.newPathParameter(repositoryName, currentPatchset.tip, path), true));
					} else if (entry.changeType.equals(ChangeType.DELETE)) {
						// delete, show label
						item.add(new Label("pathName", displayPath));
					} else {
						// mod, show diff
						item.add(new LinkPanel("pathName", "list", displayPath, BlobDiffPage.class,
								WicketUtils.newPathParameter(repositoryName, currentPatchset.tip, path), true));
					}
					item.add(new DiffStatPanel("diffStat", entry.insertions, entry.deletions, true));
				}

				// quick links
				if (entry.isSubmodule()) {
					// submodule
					item.add(setNewTarget(new BookmarkablePageLink<Void>("diff", BlobDiffPage.class, WicketUtils
							.newPathParameter(repositoryName, entry.commitId, entry.path)))
							.setEnabled(!entry.changeType.equals(ChangeType.ADD)));
					item.add(new BookmarkablePageLink<Void>("view", CommitPage.class, WicketUtils
							.newObjectParameter(submodulePath, entry.objectId)).setEnabled(hasSubmodule));
				} else {
					// tree or blob
					item.add(setNewTarget(new BookmarkablePageLink<Void>("diff", BlobDiffPage.class, WicketUtils
							.newBlobDiffParameter(repositoryName, currentPatchset.base, currentPatchset.tip, entry.path)))
							.setEnabled(!entry.changeType.equals(ChangeType.ADD)
									&& !entry.changeType.equals(ChangeType.DELETE)));
					item.add(setNewTarget(new BookmarkablePageLink<Void>("view", BlobPage.class, WicketUtils
							.newPathParameter(repositoryName, currentPatchset.tip, entry.path)))
							.setEnabled(!entry.changeType.equals(ChangeType.DELETE)));
				}

				WicketUtils.setAlternatingBackground(item, counter);
				counter++;
			}
		};
		panel.add(pathsView);

		addPtCheckoutInstructions(user, repository, panel);
		addGitCheckoutInstructions(user, repository, panel);

		return panel;
	}

	protected IconAjaxLink<String> createReviewLink(String wicketId, final Score score) {
		return new IconAjaxLink<String>(wicketId, getScoreClass(score), Model.of(getScoreDescription(score))) {

			private static final long serialVersionUID = 1L;

			@Override
			public void onClick(AjaxRequestTarget target) {
				review(score);
			}
		};
	}

	protected String getScoreClass(Score score) {
		switch (score) {
		case vetoed:
			return "fa fa-exclamation-circle";
		case needs_improvement:
			return "fa fa-thumbs-o-down";
		case looks_good:
			return "fa fa-thumbs-o-up";
		case approved:
			return "fa fa-check-circle";
		case not_reviewed:
		default:
			return "fa fa-minus-circle";
		}
	}

	protected String getScoreDescription(Score score) {
		String description;
		switch (score) {
		case vetoed:
			description = getString("gb.veto");
			break;
		case needs_improvement:
			description = getString("gb.needsImprovement");
			break;
		case looks_good:
			description = getString("gb.looksGood");
			break;
		case approved:
			description = getString("gb.approve");
			break;
		case not_reviewed:
		default:
			description = getString("gb.hasNotReviewed");
		}
		return String.format("%1$s (%2$+d)", description, score.getValue());
	}

	protected void review(Score score) {
		UserModel user = GitBlitWebSession.get().getUser();
		Patchset ps = ticket.getCurrentPatchset();
		Change change = new Change(user.username);
		change.review(ps, score, !ticket.isReviewer(user.username));
		if (!ticket.isWatching(user.username)) {
			change.watch(user.username);
		}
		TicketModel updatedTicket = app().tickets().updateTicket(getRepositoryModel(), ticket.number, change);
		app().tickets().createNotifier().sendMailing(updatedTicket);
		redirectTo(TicketsPage.class, getPageParameters());
	}

	protected <X extends MarkupContainer> X setNewTarget(X x) {
		x.add(new SimpleAttributeModifier("target", "_blank"));
		return x;
	}

	protected void addGitCheckoutInstructions(UserModel user, RepositoryModel repository, MarkupContainer panel) {
		panel.add(new Label("gitStep1", MessageFormat.format(getString("gb.stepN"), 1)));
		panel.add(new Label("gitStep2", MessageFormat.format(getString("gb.stepN"), 2)));

		String ticketBranch  = Repository.shortenRefName(PatchsetCommand.getTicketBranch(ticket.number));

		String step1 = "git fetch origin";
		String step2 = MessageFormat.format("git checkout {0} && git pull --ff-only\nOR\ngit checkout {0} && git reset --hard origin/{0}", ticketBranch);

		panel.add(new Label("gitPreStep1", step1));
		panel.add(new Label("gitPreStep2", step2));

		panel.add(createCopyFragment("gitCopyStep1", step1.replace("\n", " && ")));
		panel.add(createCopyFragment("gitCopyStep2", step2.replace("\n", " && ")));
	}

	protected void addPtCheckoutInstructions(UserModel user, RepositoryModel repository, MarkupContainer panel) {
		String step1 = MessageFormat.format("pt checkout {0,number,0}", ticket.number);
		panel.add(new Label("ptPreStep", step1));
		panel.add(createCopyFragment("ptCopyStep", step1));
	}

	/**
	 * Adds a merge panel for the patchset to the markup container.  The panel
	 * may just a message if the patchset can not be merged.
	 *
	 * @param c
	 * @param user
	 * @param repository
	 */
	protected Component createMergePanel(UserModel user, RepositoryModel repository) {
		Patchset patchset = ticket.getCurrentPatchset();
		if (patchset == null) {
			// no patchset to merge
			return new Label("mergePanel");
		}

		boolean allowMerge;
		if (repository.requireApproval) {
			// repository requires approval
			allowMerge = ticket.isOpen() && ticket.isApproved(patchset);
			if (repository.requireScore > -1) {
				allowMerge &= ticket.getScore(patchset) >= repository.requireScore; 
			}
		} else {
			// vetoes are binding
			allowMerge = ticket.isOpen() && !ticket.isVetoed(patchset);
		}

		MergeStatus mergeStatus = JGitUtils.canMerge(getRepository(), patchset.tip, ticket.mergeTo, repository.mergeType);
		if (allowMerge) {
			if (MergeStatus.MERGEABLE == mergeStatus) {
				// patchset can be cleanly merged to integration branch OR has already been merged
				Fragment mergePanel = new Fragment("mergePanel", "mergeableFragment", this);
				mergePanel.add(new Label("mergeTitle", MessageFormat.format(getString("gb.patchsetMergeable"), ticket.mergeTo)));
				if (user.canPush(repository)) {
					// user can merge locally
					SimpleAjaxLink<String> mergeButton = new SimpleAjaxLink<String>("mergeButton", Model.of(getString("gb.merge"))) {

						private static final long serialVersionUID = 1L;

						@Override
						public void onClick(AjaxRequestTarget target) {

							// ensure the patchset is still current AND not vetoed
							Patchset patchset = ticket.getCurrentPatchset();
							final TicketModel refreshedTicket = app().tickets().getTicket(getRepositoryModel(), ticket.number);
							if (patchset.equals(refreshedTicket.getCurrentPatchset())) {
								// patchset is current, check for recent veto
								if (!refreshedTicket.isVetoed(patchset)) {
									// patchset is not vetoed

									// execute the merge using the ticket service
									app().tickets().exec(new Runnable() {
										@Override
										public void run() {
											PatchsetReceivePack rp = new PatchsetReceivePack(
													app().gitblit(),
													getRepository(),
													getRepositoryModel(),
													GitBlitWebSession.get().getUser());
											MergeStatus result = rp.merge(refreshedTicket);
											if (MergeStatus.MERGED == result) {
												// notify participants and watchers
												rp.sendAll();
											} else {
												// merge failure
												String msg = MessageFormat.format("Failed to merge ticket {0,number,0}: {1}", ticket.number, result.name());
												logger.error(msg);
												GitBlitWebSession.get().cacheErrorMessage(msg);
											}
										}
									});
								} else {
									// vetoed patchset
									String msg = MessageFormat.format("Can not merge ticket {0,number,0}, patchset {1,number,0} has been vetoed!",
											ticket.number, patchset.number);
									GitBlitWebSession.get().cacheErrorMessage(msg);
									logger.error(msg);
								}
							} else {
								// not current patchset
								String msg = MessageFormat.format("Can not merge ticket {0,number,0}, the patchset has been updated!", ticket.number);
								GitBlitWebSession.get().cacheErrorMessage(msg);
								logger.error(msg);
							}
							
							redirectTo(TicketsPage.class, getPageParameters());
						}
					};
					mergePanel.add(mergeButton);
					Component instructions = getMergeInstructions(user, repository, "mergeMore", "gb.patchsetMergeableMore");
					mergePanel.add(instructions);
				} else {
					mergePanel.add(new Label("mergeButton").setVisible(false));
					mergePanel.add(new Label("mergeMore").setVisible(false));
				}
				return mergePanel;
			} else if (MergeStatus.ALREADY_MERGED == mergeStatus) {
				// patchset already merged
				Fragment mergePanel = new Fragment("mergePanel", "alreadyMergedFragment", this);
				mergePanel.add(new Label("mergeTitle", MessageFormat.format(getString("gb.patchsetAlreadyMerged"), ticket.mergeTo)));
				return mergePanel;
			} else if (MergeStatus.MISSING_INTEGRATION_BRANCH == mergeStatus) {
				// target/integration branch is missing
				Fragment mergePanel = new Fragment("mergePanel", "notMergeableFragment", this);
				mergePanel.add(new Label("mergeTitle", MessageFormat.format(getString("gb.patchsetNotMergeable"), ticket.mergeTo)));
				mergePanel.add(new Label("mergeMore", MessageFormat.format(getString("gb.missingIntegrationBranchMore"), ticket.mergeTo)));
				return mergePanel;
			} else {
				// patchset can not be cleanly merged
				Fragment mergePanel = new Fragment("mergePanel", "notMergeableFragment", this);
				mergePanel.add(new Label("mergeTitle", MessageFormat.format(getString("gb.patchsetNotMergeable"), ticket.mergeTo)));
				if (user.canPush(repository)) {
					// user can merge locally
					Component instructions = getMergeInstructions(user, repository, "mergeMore", "gb.patchsetNotMergeableMore");
					mergePanel.add(instructions);
				} else {
					mergePanel.add(new Label("mergeMore").setVisible(false));
				}
				return mergePanel;
			}
		} else {
			// merge not allowed
			if (MergeStatus.ALREADY_MERGED == mergeStatus) {
				// patchset already merged
				Fragment mergePanel = new Fragment("mergePanel", "alreadyMergedFragment", this);
				mergePanel.add(new Label("mergeTitle", MessageFormat.format(getString("gb.patchsetAlreadyMerged"), ticket.mergeTo)));
				return mergePanel;
			} else if (ticket.isVetoed(patchset)) {
				// patchset has been vetoed
				Fragment mergePanel =  new Fragment("mergePanel", "vetoedFragment", this);
				mergePanel.add(new Label("mergeTitle", MessageFormat.format(getString("gb.patchsetNotMergeable"), ticket.mergeTo)));
				return mergePanel;
			} else if (repository.requireApproval) {
				// patchset has been not been approved for merge
				Fragment mergePanel = new Fragment("mergePanel", "notApprovedFragment", this);
				mergePanel.add(new Label("mergeTitle", MessageFormat.format(getString("gb.patchsetNotApproved"), ticket.mergeTo)));
				mergePanel.add(new Label("mergeMore", MessageFormat.format(getString("gb.patchsetNotApprovedMore"), ticket.mergeTo)));
				return mergePanel;
			} else {
				// other case
				return new Label("mergePanel");
			}
		}
	}

	protected Component getMergeInstructions(UserModel user, RepositoryModel repository, String markupId, String infoKey) {
		Fragment cmd = new Fragment(markupId, "commandlineMergeFragment", this);
		cmd.add(new Label("instructions", MessageFormat.format(getString(infoKey), ticket.mergeTo)));

		// git instructions
		cmd.add(new Label("mergeStep1", MessageFormat.format(getString("gb.stepN"), 1)));
		cmd.add(new Label("mergeStep2", MessageFormat.format(getString("gb.stepN"), 2)));
		cmd.add(new Label("mergeStep3", MessageFormat.format(getString("gb.stepN"), 3)));

		String ticketBranch = Repository.shortenRefName(PatchsetCommand.getTicketBranch(ticket.number));
		String reviewBranch = PatchsetCommand.getReviewBranch(ticket.number);

		String step1 = MessageFormat.format("git checkout -b {0} {1}", reviewBranch, ticket.mergeTo);
		String step2 = MessageFormat.format("git pull origin {0}", ticketBranch);
		String step3 = MessageFormat.format("git checkout {0}\ngit merge {1}\ngit push origin {0}\ngit branch -d {1}", ticket.mergeTo, reviewBranch);

		cmd.add(new Label("mergePreStep1", step1));
		cmd.add(new Label("mergePreStep2", step2));
		cmd.add(new Label("mergePreStep3", step3));

		cmd.add(createCopyFragment("mergeCopyStep1", step1.replace("\n", " && ")));
		cmd.add(createCopyFragment("mergeCopyStep2", step2.replace("\n", " && ")));
		cmd.add(createCopyFragment("mergeCopyStep3", step3.replace("\n", " && ")));

		// pt instructions
		String ptStep = MessageFormat.format("pt pull {0,number,0}", ticket.number);
		cmd.add(new Label("ptMergeStep", ptStep));
		cmd.add(createCopyFragment("ptMergeCopyStep", step1.replace("\n", " && ")));
		return cmd;
	}

	/**
	 * Returns the primary repository url
	 *
	 * @param user
	 * @param repository
	 * @return the primary repository url
	 */
	protected RepositoryUrl getRepositoryUrl(UserModel user, RepositoryModel repository) {
		HttpServletRequest req = ((WebRequest) getRequest()).getHttpServletRequest();
		List<RepositoryUrl> urls = app().services().getRepositoryUrls(req, user, repository);
		if (ArrayUtils.isEmpty(urls)) {
			return null;
		}
		RepositoryUrl primary = urls.get(0);
		return primary;
	}

	/**
	 * Returns the ticket (if any) that this commit references.
	 *
	 * @param commit
	 * @return null or a ticket
	 */
	protected TicketModel getTicket(RevCommit commit) {
		try {
			Map<String, Ref> refs = getRepository().getRefDatabase().getRefs(Constants.R_TICKETS_PATCHSETS);
			for (Map.Entry<String, Ref> entry : refs.entrySet()) {
				if (entry.getValue().getObjectId().equals(commit.getId())) {
					long id = PatchsetCommand.getTicketNumber(entry.getKey());
					TicketModel ticket = app().tickets().getTicket(getRepositoryModel(), id);
					return ticket;
				}
			}
		} catch (Exception e) {
			logger().error("failed to determine ticket from ref", e);
		}
		return null;
	}

	protected String getPatchsetTypeCss(PatchsetType type) {
		String typeCss;
		switch (type) {
			case Rebase:
			case Rebase_Squash:
				typeCss = TicketsUI.getLozengeClass(Status.Declined, false);
				break;
			case Squash:
			case Amend:
				typeCss = TicketsUI.getLozengeClass(Status.On_Hold, false);
				break;
			case Proposal:
				typeCss = TicketsUI.getLozengeClass(Status.New, false);
				break;
			case FastForward:
			default:
				typeCss = null;
			break;
		}
		return typeCss;
	}

	@Override
	protected String getPageName() {
		return getString("gb.ticket");
	}

	@Override
	protected Class<? extends BasePage> getRepoNavPageClass() {
		return TicketsPage.class;
	}

	@Override
	protected String getPageTitle(String repositoryName) {
		return "#" + ticket.number + " - " + ticket.title;
	}

	protected Fragment createCopyFragment(String wicketId, String text) {
		if (app().settings().getBoolean(Keys.web.allowFlashCopyToClipboard, true)) {
			// clippy: flash-based copy & paste
			Fragment copyFragment = new Fragment(wicketId, "clippyPanel", this);
			String baseUrl = WicketUtils.getGitblitURL(getRequest());
			ShockWaveComponent clippy = new ShockWaveComponent("clippy", baseUrl + "/clippy.swf");
			clippy.setValue("flashVars", "text=" + StringUtils.encodeURL(text));
			copyFragment.add(clippy);
			return copyFragment;
		} else {
			// javascript: manual copy & paste with modal browser prompt dialog
			Fragment copyFragment = new Fragment(wicketId, "jsPanel", this);
			ContextImage img = WicketUtils.newImage("copyIcon", "clippy.png");
			img.add(new JavascriptTextPrompt("onclick", "Copy to Clipboard (Ctrl+C, Enter)", text));
			copyFragment.add(img);
			return copyFragment;
		}
	}
	
	private Link<Void> createDeletePatchsetLink(final RepositoryModel repositoryModel, final Patchset patchset)
	{
		Link<Void> deleteLink = new Link<Void>("deleteRevision") {
			private static final long serialVersionUID = 1L;

			@Override
			public void onClick() {
				Repository r = app().repositories().getRepository(repositoryModel.name);
				UserModel user = GitBlitWebSession.get().getUser();
				
				if (r == null) {
					if (app().repositories().isCollectingGarbage(repositoryModel.name)) {
						error(MessageFormat.format(getString("gb.busyCollectingGarbage"), repositoryModel.name));
					} else {
						error(MessageFormat.format("Failed to find repository {0}", repositoryModel.name));
					}
					return;
				}
				
				//Construct the ref name based on the patchset
				String ticketShard = String.format("%02d", ticket.number);
				ticketShard = ticketShard.substring(ticketShard.length() - 2);
				final String refName = String.format("%s%s/%d/%d", Constants.R_TICKETS_PATCHSETS, ticketShard, ticket.number, patchset.number);

				Ref ref = null;
				boolean success = true;
				
				try {
					ref = r.getRef(refName);
					
					if (ref != null) {
						success = JGitUtils.deleteBranchRef(r, ref.getName());
					} else {
						success = false;
					}
					
					if (success) {
						// clear commit cache
						CommitCache.instance().clear(repositoryModel.name, refName);

						// optionally update reflog
						if (RefLogUtils.hasRefLogBranch(r)) {
							RefLogUtils.deleteRef(user, r, ref);
						}

						TicketModel updatedTicket = app().tickets().deletePatchset(ticket, patchset, user.username);
												
						if (updatedTicket == null) {
							success = false;
						}
					}
				} catch (IOException e) {
					logger().error("failed to determine ticket from ref", e);
					success = false;
				} finally {
					r.close();
				}

				if (success) {
					getSession().info(MessageFormat.format(getString("gb.deletePatchsetSuccess"), patchset.number));
					logger().info(MessageFormat.format("{0} deleted patchset {1} from ticket {2}", 
							user.username, patchset.number, ticket.number));
				} else {
					getSession().error(MessageFormat.format(getString("gb.deletePatchsetFailure"),patchset.number));
				}
				
				//Force reload of the page to rebuild ticket change cache
				String relativeUrl = urlFor(TicketsPage.class, getPageParameters()).toString();
				String absoluteUrl = RequestUtils.toAbsolutePath(relativeUrl);
				setResponsePage(new RedirectPage(absoluteUrl));
			}
		};

		WicketUtils.setHtmlTooltip(deleteLink, MessageFormat.format(getString("gb.deletePatchset"), patchset.number));
		
		deleteLink.add(new JavascriptEventConfirmation("onclick", MessageFormat.format(getString("gb.deletePatchset"), patchset.number)));
		
		return deleteLink;
	}
	
}
