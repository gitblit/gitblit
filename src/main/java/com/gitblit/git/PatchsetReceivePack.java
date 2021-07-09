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
package com.gitblit.git;

import static org.eclipse.jgit.transport.BasePackPushConnection.CAPABILITY_SIDE_BAND_64K;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jgit.lib.BatchRefUpdate;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceiveCommand.Result;
import org.eclipse.jgit.transport.ReceiveCommand.Type;
import org.eclipse.jgit.transport.ReceivePack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gitblit.Constants;
import com.gitblit.Keys;
import com.gitblit.extensions.PatchsetHook;
import com.gitblit.manager.IGitblit;
import com.gitblit.models.RepositoryModel;
import com.gitblit.models.TicketModel;
import com.gitblit.models.TicketModel.Change;
import com.gitblit.models.TicketModel.Field;
import com.gitblit.models.TicketModel.Patchset;
import com.gitblit.models.TicketModel.PatchsetType;
import com.gitblit.models.TicketModel.Status;
import com.gitblit.models.TicketModel.TicketAction;
import com.gitblit.models.TicketModel.TicketLink;
import com.gitblit.models.UserModel;
import com.gitblit.tickets.BranchTicketService;
import com.gitblit.tickets.ITicketService;
import com.gitblit.tickets.TicketMilestone;
import com.gitblit.tickets.TicketNotifier;
import com.gitblit.utils.ArrayUtils;
import com.gitblit.utils.DiffUtils;
import com.gitblit.utils.DiffUtils.DiffStat;
import com.gitblit.utils.JGitUtils;
import com.gitblit.utils.JGitUtils.MergeResult;
import com.gitblit.utils.JGitUtils.MergeStatus;
import com.gitblit.utils.RefLogUtils;
import com.gitblit.utils.StringUtils;
import com.google.common.collect.Lists;


/**
 * PatchsetReceivePack processes receive commands and allows for creating, updating,
 * and closing Gitblit tickets.  It also executes Groovy pre- and post- receive
 * hooks.
 *
 * The patchset mechanism defined in this class is based on the ReceiveCommits class
 * from the Gerrit code review server.
 *
 * The general execution flow is:
 * <ol>
 *    <li>onPreReceive()</li>
 *    <li>executeCommands()</li>
 *    <li>onPostReceive()</li>
 * </ol>
 *
 * @author Android Open Source Project
 * @author James Moger
 *
 */
public class PatchsetReceivePack extends GitblitReceivePack {

	protected static final List<String> MAGIC_REFS = Arrays.asList(Constants.R_FOR, Constants.R_TICKET);

	protected static final Pattern NEW_PATCHSET =
			Pattern.compile("^refs/tickets/(?:[0-9a-zA-Z][0-9a-zA-Z]/)?([1-9][0-9]*)(?:/new)?$");

	private static final Logger LOGGER = LoggerFactory.getLogger(PatchsetReceivePack.class);

	protected final ITicketService ticketService;

	protected final TicketNotifier ticketNotifier;

	private boolean requireMergeablePatchset;

	public PatchsetReceivePack(IGitblit gitblit, Repository db, RepositoryModel repository, UserModel user) {
		super(gitblit, db, repository, user);
		this.ticketService = gitblit.getTicketService();
		this.ticketNotifier = ticketService.createNotifier();
	}

	/** Returns the patchset ref root from the ref */
	private String getPatchsetRef(String refName) {
		for (String patchRef : MAGIC_REFS) {
			if (refName.startsWith(patchRef)) {
				return patchRef;
			}
		}
		return null;
	}

	/** Checks if the supplied ref name is a patchset ref */
	private boolean isPatchsetRef(String refName) {
		return !StringUtils.isEmpty(getPatchsetRef(refName));
	}

	/** Checks if the supplied ref name is a change ref */
	private boolean isTicketRef(String refName) {
		return refName.startsWith(Constants.R_TICKETS_PATCHSETS);
	}

	/** Extracts the integration branch from the ref name */
	private String getIntegrationBranch(String refName) {
		String patchsetRef = getPatchsetRef(refName);
		String branch = refName.substring(patchsetRef.length());
		if (branch.indexOf('%') > -1) {
			branch = branch.substring(0, branch.indexOf('%'));
		}

		String defaultBranch = "master";
		try {
			defaultBranch = getRepository().getBranch();
		} catch (Exception e) {
			LOGGER.error("failed to determine default branch for " + repository.name, e);
		}

		if (!StringUtils.isEmpty(getRepositoryModel().mergeTo)) {
			// repository settings specifies a default integration branch
			defaultBranch = Repository.shortenRefName(getRepositoryModel().mergeTo);
		}

		long ticketId = 0L;
		try {
			ticketId = Long.parseLong(branch);
		} catch (Exception e) {
			// not a number
		}
		if (ticketId > 0 || branch.equalsIgnoreCase("default") || branch.equalsIgnoreCase("new")) {
			return defaultBranch;
		}
		return branch;
	}

	/** Extracts the ticket id from the ref name */
	private long getTicketId(String refName) {
		if (refName.indexOf('%') > -1) {
			refName = refName.substring(0, refName.indexOf('%'));
		}
		if (refName.startsWith(Constants.R_FOR)) {
			String ref = refName.substring(Constants.R_FOR.length());
			try {
				return Long.parseLong(ref);
			} catch (Exception e) {
				// not a number
			}
		} else if (refName.startsWith(Constants.R_TICKET) ||
				refName.startsWith(Constants.R_TICKETS_PATCHSETS)) {
			return PatchsetCommand.getTicketNumber(refName);
		}
		return 0L;
	}

	/** Returns true if the ref namespace exists */
	private boolean hasRefNamespace(String ref) {
		Map<String, Ref> blockingFors;
		try {
			blockingFors = getRepository().getRefDatabase().getRefs(ref);
		} catch (IOException err) {
			sendError("Cannot scan refs in {0}", repository.name);
			LOGGER.error("Error!", err);
			return true;
		}
		if (!blockingFors.isEmpty()) {
			sendError("{0} needs the following refs removed to receive patchsets: {1}",
					repository.name, blockingFors.keySet());
			return true;
		}
		return false;
	}

	/** Removes change ref receive commands */
	private List<ReceiveCommand> excludeTicketCommands(Collection<ReceiveCommand> commands) {
		List<ReceiveCommand> filtered = new ArrayList<ReceiveCommand>();
		for (ReceiveCommand cmd : commands) {
			if (!isTicketRef(cmd.getRefName())) {
				// this is not a ticket ref update
				filtered.add(cmd);
			}
		}
		return filtered;
	}

	/** Removes patchset receive commands for pre- and post- hook integrations */
	private List<ReceiveCommand> excludePatchsetCommands(Collection<ReceiveCommand> commands) {
		List<ReceiveCommand> filtered = new ArrayList<ReceiveCommand>();
		for (ReceiveCommand cmd : commands) {
			if (!isPatchsetRef(cmd.getRefName())) {
				// this is a non-patchset ref update
				filtered.add(cmd);
			}
		}
		return filtered;
	}

	/**	Process receive commands EXCEPT for Patchset commands. */
	@Override
	public void onPreReceive(ReceivePack rp, Collection<ReceiveCommand> commands) {
		Collection<ReceiveCommand> filtered = excludePatchsetCommands(commands);
		super.onPreReceive(rp, filtered);
	}

	/**	Process receive commands EXCEPT for Patchset commands. */
	@Override
	public void onPostReceive(ReceivePack rp, Collection<ReceiveCommand> commands) {
		Collection<ReceiveCommand> filtered = excludePatchsetCommands(commands);
		super.onPostReceive(rp, filtered);

		// send all queued ticket notifications after processing all patchsets
		ticketNotifier.sendAll();
	}

	@Override
	protected void validateCommands() {
		// workaround for JGit's awful scoping choices
		//
		// set the patchset refs to OK to bypass checks in the super implementation
		for (final ReceiveCommand cmd : filterCommands(Result.NOT_ATTEMPTED)) {
			if (isPatchsetRef(cmd.getRefName())) {
				if (cmd.getType() == ReceiveCommand.Type.CREATE) {
					cmd.setResult(Result.OK);
				}
			}
		}

		super.validateCommands();
	}

	/** Execute commands to update references. */
	@Override
	protected void executeCommands() {
		// we process patchsets unless the user is pushing something special
		boolean processPatchsets = true;
		for (ReceiveCommand cmd : filterCommands(Result.NOT_ATTEMPTED)) {
			if (ticketService instanceof BranchTicketService
					&& BranchTicketService.BRANCH.equals(cmd.getRefName())) {
				// the user is pushing an update to the BranchTicketService data
				processPatchsets = false;
			}
		}

		// workaround for JGit's awful scoping choices
		//
		// reset the patchset refs to NOT_ATTEMPTED (see validateCommands)
		for (ReceiveCommand cmd : filterCommands(Result.OK)) {
			if (isPatchsetRef(cmd.getRefName())) {
				cmd.setResult(Result.NOT_ATTEMPTED);
			} else if (ticketService instanceof BranchTicketService
					&& BranchTicketService.BRANCH.equals(cmd.getRefName())) {
				// the user is pushing an update to the BranchTicketService data
				processPatchsets = false;
			}
		}

		List<ReceiveCommand> toApply = filterCommands(Result.NOT_ATTEMPTED);
		if (toApply.isEmpty()) {
			return;
		}

		ProgressMonitor updating = NullProgressMonitor.INSTANCE;
		boolean sideBand = isCapabilityEnabled(CAPABILITY_SIDE_BAND_64K);
		if (sideBand) {
			SideBandProgressMonitor pm = new SideBandProgressMonitor(msgOut);
			pm.setDelayStart(250, TimeUnit.MILLISECONDS);
			updating = pm;
		}

		BatchRefUpdate batch = getRepository().getRefDatabase().newBatchUpdate();
		batch.setAllowNonFastForwards(isAllowNonFastForwards());
		batch.setRefLogIdent(getRefLogIdent());
		batch.setRefLogMessage("push", true);

		ReceiveCommand patchsetRefCmd = null;
		PatchsetCommand patchsetCmd = null;
		for (ReceiveCommand cmd : toApply) {
			if (Result.NOT_ATTEMPTED != cmd.getResult()) {
				// Already rejected by the core receive process.
				continue;
			}

			if (isPatchsetRef(cmd.getRefName()) && processPatchsets) {

				if (ticketService == null) {
					sendRejection(cmd, "Sorry, the ticket service is unavailable and can not accept patchsets at this time.");
					continue;
				}

				if (!ticketService.isReady()) {
					sendRejection(cmd, "Sorry, the ticket service can not accept patchsets at this time.");
					continue;
				}

				if (UserModel.ANONYMOUS.equals(user)) {
					// server allows anonymous pushes, but anonymous patchset
					// contributions are prohibited by design
					sendRejection(cmd, "Sorry, anonymous patchset contributions are prohibited.");
					continue;
				}

				final Matcher m = NEW_PATCHSET.matcher(cmd.getRefName());
				if (m.matches()) {
					// prohibit pushing directly to a patchset ref
					long id = getTicketId(cmd.getRefName());
					sendError("You may not directly push directly to a patchset ref!");
					sendError("Instead, please push to one the following:");
					sendError(" - {0}{1,number,0}", Constants.R_FOR, id);
					sendError(" - {0}{1,number,0}", Constants.R_TICKET, id);
					sendRejection(cmd, "protected ref");
					continue;
				}

				if (hasRefNamespace(Constants.R_FOR)) {
					// the refs/for/ namespace exists and it must not
					LOGGER.error("{} already has refs in the {} namespace",
							repository.name, Constants.R_FOR);
					sendRejection(cmd, "Sorry, a repository administrator will have to remove the {} namespace", Constants.R_FOR);
					continue;
				}

				if (cmd.getNewId().equals(ObjectId.zeroId())) {
					// ref deletion request
					if (cmd.getRefName().startsWith(Constants.R_TICKET)) {
						if (user.canDeleteRef(repository)) {
							batch.addCommand(cmd);
						} else {
							sendRejection(cmd, "Sorry, you do not have permission to delete {}", cmd.getRefName());
						}
					} else {
						sendRejection(cmd, "Sorry, you can not delete {}", cmd.getRefName());
					}
					continue;
				}

				if (patchsetRefCmd != null) {
					sendRejection(cmd, "You may only push one patchset at a time.");
					continue;
				}

				LOGGER.info(MessageFormat.format("Verifying {0} push ref \"{1}\" received from {2}",
						repository.name, cmd.getRefName(), user.username));

				// responsible verification
				String responsible = PatchsetCommand.getSingleOption(cmd, PatchsetCommand.RESPONSIBLE);
				if (!StringUtils.isEmpty(responsible)) {
					UserModel assignee = gitblit.getUserModel(responsible);
					if (assignee == null) {
						// no account by this name
						sendRejection(cmd, "{0} can not be assigned any tickets because there is no user account by that name", responsible);
						continue;
					} else if (!assignee.canPush(repository)) {
						// account does not have RW permissions
						sendRejection(cmd, "{0} ({1}) can not be assigned any tickets because the user does not have RW permissions for {2}",
								assignee.getDisplayName(), assignee.username, repository.name);
						continue;
					}
				}

				// milestone verification
				String milestone = PatchsetCommand.getSingleOption(cmd, PatchsetCommand.MILESTONE);
				if (!StringUtils.isEmpty(milestone)) {
					TicketMilestone milestoneModel = ticketService.getMilestone(repository, milestone);
					if (milestoneModel == null) {
						// milestone does not exist
						sendRejection(cmd, "Sorry, \"{0}\" is not a valid milestone!", milestone);
						continue;
					}
				}

				// watcher verification
				List<String> watchers = PatchsetCommand.getOptions(cmd, PatchsetCommand.WATCH);
				if (!ArrayUtils.isEmpty(watchers)) {
					boolean verified = true;
					for (String watcher : watchers) {
						UserModel user = gitblit.getUserModel(watcher);
						if (user == null) {
							// watcher does not exist
							sendRejection(cmd, "Sorry, \"{0}\" is not a valid username for the watch list!", watcher);
							verified = false;
							break;
						}
					}
					if (!verified) {
						continue;
					}
				}

				patchsetRefCmd = cmd;
				patchsetCmd = preparePatchset(cmd);
				if (patchsetCmd != null) {
					batch.addCommand(patchsetCmd);
				}
				continue;
			}

			batch.addCommand(cmd);
		}

		if (!batch.getCommands().isEmpty()) {
			try {
				batch.execute(getRevWalk(), updating);
			} catch (IOException err) {
				for (ReceiveCommand cmd : toApply) {
					if (cmd.getResult() == Result.NOT_ATTEMPTED) {
						sendRejection(cmd, "lock error: {0}", err.getMessage());
						LOGGER.error(MessageFormat.format("failed to lock {0}:{1}",
								repository.name, cmd.getRefName()), err);
					}
				}
			}
		}

		//
		// set the results into the patchset ref receive command
		//
		if (patchsetRefCmd != null && patchsetCmd != null) {
			if (!patchsetCmd.getResult().equals(Result.OK)) {
				// patchset command failed!
				LOGGER.error(patchsetCmd.getType() + " " + patchsetCmd.getRefName()
						+ " " + patchsetCmd.getResult());
				patchsetRefCmd.setResult(patchsetCmd.getResult(), patchsetCmd.getMessage());
			} else {
				// all patchset commands were applied
				patchsetRefCmd.setResult(Result.OK);

				// update the ticket branch ref
				RefUpdate ru = updateRef(
						patchsetCmd.getTicketBranch(),
						patchsetCmd.getNewId(),
						patchsetCmd.getPatchsetType());
				updateReflog(ru);

				TicketModel ticket = processPatchset(patchsetCmd);
				if (ticket != null) {
					ticketNotifier.queueMailing(ticket);
				}
			}
		}

		//
		// if there are standard ref update receive commands that were
		// successfully processed, process referenced tickets, if any
		//
		List<ReceiveCommand> allUpdates = ReceiveCommand.filter(batch.getCommands(), Result.OK);
		List<ReceiveCommand> refUpdates = excludePatchsetCommands(allUpdates);
		List<ReceiveCommand> stdUpdates = excludeTicketCommands(refUpdates);
		if (!stdUpdates.isEmpty()) {
			int ticketsProcessed = 0;
			for (ReceiveCommand cmd : stdUpdates) {
				switch (cmd.getType()) {
				case CREATE:
				case UPDATE:
					if (cmd.getRefName().startsWith(Constants.R_HEADS)) {
						Collection<TicketModel> tickets = processReferencedTickets(cmd);
						ticketsProcessed += tickets.size();
						for (TicketModel ticket : tickets) {
							ticketNotifier.queueMailing(ticket);
						}
					}
					break;
					
				case UPDATE_NONFASTFORWARD:
					if (cmd.getRefName().startsWith(Constants.R_HEADS)) {
						String base = JGitUtils.getMergeBase(getRepository(), cmd.getOldId(), cmd.getNewId());
						List<TicketLink> deletedRefs = JGitUtils.identifyTicketsBetweenCommits(getRepository(), settings, base, cmd.getOldId().name());
						for (TicketLink link : deletedRefs) {
							link.isDelete = true;
						}
						Change deletion = new Change(user.username);
						deletion.pendingLinks = deletedRefs;
						ticketService.updateTicket(repository, 0, deletion);

						Collection<TicketModel> tickets = processReferencedTickets(cmd);
						ticketsProcessed += tickets.size();
						for (TicketModel ticket : tickets) {
							ticketNotifier.queueMailing(ticket);
						}
					}
					break;
				default:
					break;
				}
			}

			if (ticketsProcessed == 1) {
				sendInfo("1 ticket updated");
			} else if (ticketsProcessed > 1) {
				sendInfo("{0} tickets updated", ticketsProcessed);
			}
		}

		// reset the ticket caches for the repository
		ticketService.resetCaches(repository);
	}

	/**
	 * Prepares a patchset command.
	 *
	 * @param cmd
	 * @return the patchset command
	 */
	private PatchsetCommand preparePatchset(ReceiveCommand cmd) {
		String branch = getIntegrationBranch(cmd.getRefName());
		long number = getTicketId(cmd.getRefName());

		TicketModel ticket = null;
		if (number > 0 && ticketService.hasTicket(repository, number)) {
			ticket = ticketService.getTicket(repository, number);
		}

		if (ticket == null) {
			if (number > 0) {
				// requested ticket does not exist
				sendError("Sorry, {0} does not have ticket {1,number,0}!", repository.name, number);
				sendRejection(cmd, "Invalid ticket number");
				return null;
			}
		} else {
			if (ticket.isMerged()) {
				// ticket already merged & resolved
				Change mergeChange = null;
				for (Change change : ticket.changes) {
					if (change.isMerge()) {
						mergeChange = change;
						break;
					}
				}
				if (mergeChange != null) {
					sendError("Sorry, {0} already merged {1} from ticket {2,number,0} to {3}!",
						mergeChange.author, mergeChange.patchset, number, ticket.mergeTo);
				}
				sendRejection(cmd, "Ticket {0,number,0} already resolved", number);
				return null;
			} else if (!StringUtils.isEmpty(ticket.mergeTo)) {
				// ticket specifies integration branch
				branch = ticket.mergeTo;
			}
		}

		final int shortCommitIdLen = settings.getInteger(Keys.web.shortCommitIdLength, 6);
		final String shortTipId = cmd.getNewId().getName().substring(0, shortCommitIdLen);
		final RevCommit tipCommit = JGitUtils.getCommit(getRepository(), cmd.getNewId().getName());
		final String forBranch = branch;
		RevCommit mergeBase = null;
		Ref forBranchRef = getAdvertisedRefs().get(Constants.R_HEADS + forBranch);
		if (forBranchRef == null || forBranchRef.getObjectId() == null) {
			// unknown integration branch
			sendError("Sorry, there is no integration branch named ''{0}''.", forBranch);
			sendRejection(cmd, "Invalid integration branch specified");
			return null;
		} else {
			// determine the merge base for the patchset on the integration branch
			String base = JGitUtils.getMergeBase(getRepository(), forBranchRef.getObjectId(), tipCommit.getId());
			if (StringUtils.isEmpty(base)) {
				sendError("");
				sendError("There is no common ancestry between {0} and {1}.", forBranch, shortTipId);
				sendError("Please reconsider your proposed integration branch, {0}.", forBranch);
				sendError("");
				sendRejection(cmd, "no merge base for patchset and {0}", forBranch);
				return null;
			}
			mergeBase = JGitUtils.getCommit(getRepository(), base);
		}

		// ensure that the patchset can be cleanly merged right now
		MergeStatus status = JGitUtils.canMerge(getRepository(), tipCommit.getName(), forBranch, repository.mergeType);
		switch (status) {
		case ALREADY_MERGED:
			sendError("");
			sendError("You have already merged this patchset.", forBranch);
			sendError("");
			sendRejection(cmd, "everything up-to-date");
			return null;
		case MERGEABLE:
			break;
		default:
			if (ticket == null || requireMergeablePatchset) {
				sendError("");
				sendError("Your patchset can not be cleanly merged into {0}.", forBranch);
				sendError("Please rebase your patchset and push again.");
				sendError("NOTE:", number);
				sendError("You should push your rebase to refs/for/{0,number,0}", number);
				sendError("");
				sendError("  git push origin HEAD:refs/for/{0,number,0}", number);
				sendError("");
				sendRejection(cmd, "patchset not mergeable");
				return null;
			}
		}
		
		// check to see if this commit is already linked to a ticket
		if (ticket != null && 
				JGitUtils.getTicketNumberFromCommitBranch(getRepository(), tipCommit) == ticket.number) {
			sendError("{0} has already been pushed to ticket {1,number,0}.", shortTipId, ticket.number);
			sendRejection(cmd, "everything up-to-date");
			return null;
		}
		
		List<TicketLink> ticketLinks = JGitUtils.identifyTicketsFromCommitMessage(getRepository(), settings, tipCommit);
		
		PatchsetCommand psCmd;
		if (ticket == null) {
			/*
			 *  NEW TICKET
			 */
			Patchset patchset = newPatchset(null, mergeBase.getName(), tipCommit.getName());

			int minLength = 10;
			int maxLength = 100;
			String minTitle = MessageFormat.format("  minimum length of a title is {0} characters.", minLength);
			String maxTitle = MessageFormat.format("  maximum length of a title is {0} characters.", maxLength);

			if (patchset.commits > 1) {
				sendError("");
				sendError("You may not create a ''{0}'' branch proposal ticket from {1} commits!",
						forBranch, patchset.commits);
				sendError("");
				// display an ellipsized log of the commits being pushed
				RevWalk walk = getRevWalk();
				walk.reset();
				walk.sort(RevSort.TOPO);
				int boundary = 3;
				int count = 0;
				try {
					walk.markStart(tipCommit);
					walk.markUninteresting(mergeBase);

					for (;;) {

						RevCommit c = walk.next();
						if (c == null) {
							break;
						}

						if (count < boundary || count >= (patchset.commits - boundary)) {

							walk.parseBody(c);
							sendError("   {0}  {1}", c.getName().substring(0, shortCommitIdLen),
								StringUtils.trimString(c.getShortMessage(), 60));

						} else if (count == boundary) {

							sendError("   ... more commits ...");

						}

						count++;
					}

				} catch (IOException e) {
					// Should never happen, the core receive process would have
					// identified the missing object earlier before we got control.
					LOGGER.error("failed to get commit count", e);
				} finally {
					walk.close();
				}

				sendError("");
				sendError("Possible Solutions:");
				sendError("");
				int solution = 1;
				String forSpec = cmd.getRefName().substring(Constants.R_FOR.length());
				if (forSpec.equals("default") || forSpec.equals("new")) {
					try {
						// determine other possible integration targets
						List<String> bases = Lists.newArrayList();
						for (Ref ref : getRepository().getRefDatabase().getRefs(Constants.R_HEADS).values()) {
							if (!ref.getName().startsWith(Constants.R_TICKET)
									&& !ref.getName().equals(forBranchRef.getName())) {
								if (JGitUtils.isMergedInto(getRepository(), ref.getObjectId(), tipCommit)) {
									bases.add(Repository.shortenRefName(ref.getName()));
								}
							}
						}

						if (!bases.isEmpty()) {

							if (bases.size() == 1) {
								// suggest possible integration targets
								String base = bases.get(0);
								sendError("{0}. Propose this change for the ''{1}'' branch.", solution++, base);
								sendError("");
								sendError("   git push origin HEAD:refs/for/{0}", base);
								sendError("   pt propose {0}", base);
								sendError("");
							} else {
								// suggest possible integration targets
								sendError("{0}. Propose this change for a different branch.", solution++);
								sendError("");
								for (String base : bases) {
									sendError("   git push origin HEAD:refs/for/{0}", base);
									sendError("   pt propose {0}", base);
									sendError("");
								}
							}

						}
					} catch (IOException e) {
						LOGGER.error(null, e);
					}
				}
				sendError("{0}. Squash your changes into a single commit with a meaningful message.", solution++);
				sendError("");
				sendError("{0}. Open a ticket for your changes and then push your {1} commits to the ticket.",
						solution++, patchset.commits);
				sendError("");
				sendError("   git push origin HEAD:refs/for/{id}");
				sendError("   pt propose {id}");
				sendError("");
				sendRejection(cmd, "too many commits");
				return null;
			}

			// require a reasonable title/subject
			String title = tipCommit.getFullMessage().trim().split("\n")[0];
			if (title.length() < minLength) {
				// reject, title too short
				sendError("");
				sendError("Please supply a longer title in your commit message!");
				sendError("");
				sendError(minTitle);
				sendError(maxTitle);
				sendError("");
				sendRejection(cmd, "ticket title is too short [{0}/{1}]", title.length(), maxLength);
				return null;
			}
			if (title.length() > maxLength) {
				// reject, title too long
				sendError("");
				sendError("Please supply a more concise title in your commit message!");
				sendError("");
				sendError(minTitle);
				sendError(maxTitle);
				sendError("");
				sendRejection(cmd, "ticket title is too long [{0}/{1}]", title.length(), maxLength);
				return null;
			}

			// assign new id
			long ticketId = ticketService.assignNewId(repository);

			// create the patchset command
			psCmd = new PatchsetCommand(user.username, patchset);
			psCmd.newTicket(tipCommit, forBranch, ticketId, cmd.getRefName());
		} else {
			/*
			 *  EXISTING TICKET
			 */
			Patchset patchset = newPatchset(ticket, mergeBase.getName(), tipCommit.getName());
			psCmd = new PatchsetCommand(user.username, patchset);
			psCmd.updateTicket(tipCommit, forBranch, ticket, cmd.getRefName());
		}

		// confirm user can push the patchset
		boolean pushPermitted = ticket == null
				|| !ticket.hasPatchsets()
				|| ticket.isAuthor(user.username)
				|| ticket.isPatchsetAuthor(user.username)
				|| ticket.isResponsible(user.username)
				|| user.canPush(repository);

		switch (psCmd.getPatchsetType()) {
		case Proposal:
			// proposals (first patchset) are always acceptable
			break;
		case FastForward:
			// patchset updates must be permitted
			if (!pushPermitted) {
				// reject
				sendError("");
				sendError("To push a patchset to this ticket one of the following must be true:");
				sendError("  1. you created the ticket");
				sendError("  2. you created the first patchset");
				sendError("  3. you are specified as responsible for the ticket");
				sendError("  4. you have push (RW) permissions to {0}", repository.name);
				sendError("");
				sendRejection(cmd, "not permitted to push to ticket {0,number,0}", ticket.number);
				return null;
			}
			break;
		default:
			// non-fast-forward push
			if (!pushPermitted) {
				// reject
				sendRejection(cmd, "non-fast-forward ({0})", psCmd.getPatchsetType());
				return null;
			}
			break;
		}

		Change change = psCmd.getChange();
		change.pendingLinks = ticketLinks;

		return psCmd;
	}

	/**
	 * Creates or updates an ticket with the specified patchset.
	 *
	 * @param cmd
	 * @return a ticket if the creation or update was successful
	 */
	private TicketModel processPatchset(PatchsetCommand cmd) {
		Change change = cmd.getChange();

		if (cmd.isNewTicket()) {
			// create the ticket object
			TicketModel ticket = ticketService.createTicket(repository, cmd.getTicketId(), change);
			if (ticket != null) {
				sendInfo("");
				sendHeader("#{0,number,0}: {1}", ticket.number, StringUtils.trimString(ticket.title, Constants.LEN_SHORTLOG));
				sendInfo("created proposal ticket from patchset");
				sendInfo(ticketService.getTicketUrl(ticket));
				sendInfo("");

				// log the new patch ref
				RefLogUtils.updateRefLog(user, getRepository(),
						Arrays.asList(new ReceiveCommand(cmd.getOldId(), cmd.getNewId(), cmd.getRefName())));

				// call any patchset hooks
				for (PatchsetHook hook : gitblit.getExtensions(PatchsetHook.class)) {
					try {
						hook.onNewPatchset(ticket);
					} catch (Exception e) {
						LOGGER.error("Failed to execute extension", e);
					}
				}

				return ticket;
			} else {
				sendError("FAILED to create ticket");
			}
		} else {
			// update an existing ticket
			TicketModel ticket = ticketService.updateTicket(repository, cmd.getTicketId(), change);
			if (ticket != null) {
				sendInfo("");
				sendHeader("#{0,number,0}: {1}", ticket.number, StringUtils.trimString(ticket.title, Constants.LEN_SHORTLOG));
				if (change.patchset.rev == 1) {
					// new patchset
					sendInfo("uploaded patchset {0} ({1})", change.patchset.number, change.patchset.type.toString());
				} else {
					// updated patchset
					sendInfo("added {0} {1} to patchset {2}",
							change.patchset.added,
							change.patchset.added == 1 ? "commit" : "commits",
							change.patchset.number);
				}
				sendInfo(ticketService.getTicketUrl(ticket));
				sendInfo("");

				// log the new patchset ref
				RefLogUtils.updateRefLog(user, getRepository(),
					Arrays.asList(new ReceiveCommand(cmd.getOldId(), cmd.getNewId(), cmd.getRefName())));

				// call any patchset hooks
				final boolean isNewPatchset = change.patchset.rev == 1;
				for (PatchsetHook hook : gitblit.getExtensions(PatchsetHook.class)) {
					try {
						if (isNewPatchset) {
							hook.onNewPatchset(ticket);
						} else {
							hook.onUpdatePatchset(ticket);
						}
					} catch (Exception e) {
						LOGGER.error("Failed to execute extension", e);
					}
				}

				// return the updated ticket
				return ticket;
			} else {
				sendError("FAILED to upload {0} for ticket {1,number,0}", change.patchset, cmd.getTicketId());
			}
		}

		return null;
	}

	/**
	 * Automatically closes open tickets that have been merged to their integration
	 * branch by a client and adds references to tickets if made in the commit message.
	 *
	 * @param cmd
	 */
	private Collection<TicketModel> processReferencedTickets(ReceiveCommand cmd) {
		Map<Long, TicketModel> mergedTickets = new LinkedHashMap<Long, TicketModel>();
		final RevWalk rw = getRevWalk();
		try {
			rw.reset();
			rw.markStart(rw.parseCommit(cmd.getNewId()));
			if (!ObjectId.zeroId().equals(cmd.getOldId())) {
				rw.markUninteresting(rw.parseCommit(cmd.getOldId()));
			}

			RevCommit c;
			while ((c = rw.next()) != null) {
				rw.parseBody(c);
				List<TicketLink> ticketLinks = JGitUtils.identifyTicketsFromCommitMessage(getRepository(), settings, c);
				if (ticketLinks == null) {
					continue;
				}

				for (TicketLink link : ticketLinks) {
					
					if (mergedTickets.containsKey(link.targetTicketId)) {
						continue;
					}
	
					TicketModel ticket = ticketService.getTicket(repository, link.targetTicketId);
					if (ticket == null) {
						continue;
					}
					String integrationBranch;
					if (StringUtils.isEmpty(ticket.mergeTo)) {
						// unspecified integration branch
						integrationBranch = null;
					} else {
						// specified integration branch
						integrationBranch = Constants.R_HEADS + ticket.mergeTo;
					}
	
					Change change;
					Patchset patchset = null;
					String mergeSha = c.getName();
					String mergeTo = Repository.shortenRefName(cmd.getRefName());

					if (link.action == TicketAction.Commit) {
						//A commit can reference a ticket in any branch even if the ticket is closed.
						//This allows developers to identify and communicate related issues
						change = new Change(user.username);
						change.referenceCommit(mergeSha);
					} else {
						// ticket must be open and, if specified, the ref must match the integration branch
						if (ticket.isClosed() || (integrationBranch != null && !integrationBranch.equals(cmd.getRefName()))) {
							continue;
						}
	
						String baseRef = PatchsetCommand.getBasePatchsetBranch(ticket.number);
						boolean knownPatchset = false;
						Set<Ref> refs = getRepository().getAllRefsByPeeledObjectId().get(c.getId());
						if (refs != null) {
							for (Ref ref : refs) {
								if (ref.getName().startsWith(baseRef)) {
									knownPatchset = true;
									break;
								}
							}
						}
	
						if (knownPatchset) {
							// identify merged patchset by the patchset tip
							for (Patchset ps : ticket.getPatchsets()) {
								if (ps.tip.equals(mergeSha)) {
									patchset = ps;
									break;
								}
							}
	
							if (patchset == null) {
								// should not happen - unless ticket has been hacked
								sendError("Failed to find the patchset for {0} in ticket {1,number,0}?!",
										mergeSha, ticket.number);
								continue;
							}
	
							// create a new change
							change = new Change(user.username);
						} else {
							// new patchset pushed by user
							String base = cmd.getOldId().getName();
							patchset = newPatchset(ticket, base, mergeSha);
							PatchsetCommand psCmd = new PatchsetCommand(user.username, patchset);
							psCmd.updateTicket(c, mergeTo, ticket, null);
	
							// create a ticket patchset ref
							updateRef(psCmd.getPatchsetBranch(), c.getId(), patchset.type);
							RefUpdate ru = updateRef(psCmd.getTicketBranch(), c.getId(), patchset.type);
							updateReflog(ru);
	
							// create a change from the patchset command
							change = psCmd.getChange();
						}
	
						// set the common change data about the merge
						change.setField(Field.status, Status.Merged);
						change.setField(Field.mergeSha, mergeSha);
						change.setField(Field.mergeTo, mergeTo);
	
						if (StringUtils.isEmpty(ticket.responsible)) {
							// unassigned tickets are assigned to the closer
							change.setField(Field.responsible, user.username);
						}
					}
	
					ticket = ticketService.updateTicket(repository, ticket.number, change);
	
					if (ticket != null) {
						sendInfo("");
						sendHeader("#{0,number,0}: {1}", ticket.number, StringUtils.trimString(ticket.title, Constants.LEN_SHORTLOG));

						switch (link.action) {
							case Commit: {
								sendInfo("referenced by push of {0} to {1}", c.getName(), mergeTo);
							}
							break;

							case Close: {
								sendInfo("closed by push of {0} to {1}", patchset, mergeTo);
								mergedTickets.put(ticket.number, ticket);	
							}
							break;

							default: {
								
							}
						}

						sendInfo(ticketService.getTicketUrl(ticket));
						sendInfo("");

					} else {
						String shortid = mergeSha.substring(0, settings.getInteger(Keys.web.shortCommitIdLength, 6));
						
						switch (link.action) {
							case Commit: {
								sendError("FAILED to reference ticket {0,number,0} by push of {1}", link.targetTicketId, shortid);
							}
							break;
							case Close: {
								sendError("FAILED to close ticket {0,number,0} by push of {1}", link.targetTicketId, shortid);	
							} break;
							
							default: {
								
							}
						}
					}
				}
			}
				
		} catch (IOException e) {
			LOGGER.error("Can't scan for changes to reference or close", e);
		} finally {
			rw.reset();
		}

		return mergedTickets.values();
	}

	

	

	/**
	 * Creates a new patchset with metadata.
	 *
	 * @param ticket
	 * @param mergeBase
	 * @param tip
	 */
	private Patchset newPatchset(TicketModel ticket, String mergeBase, String tip) {
		int totalCommits = JGitUtils.countCommits(getRepository(), getRevWalk(), mergeBase, tip);

		Patchset newPatchset = new Patchset();
		newPatchset.tip = tip;
		newPatchset.base = mergeBase;
		newPatchset.commits = totalCommits;

		Patchset currPatchset = ticket == null ? null : ticket.getCurrentPatchset();
		if (currPatchset == null) {
			/*
			 * PROPOSAL PATCHSET
			 * patchset 1, rev 1
			 */
			newPatchset.number = 1;
			newPatchset.rev = 1;
			newPatchset.type = PatchsetType.Proposal;

			// diffstat from merge base
			DiffStat diffStat = DiffUtils.getDiffStat(getRepository(), mergeBase, tip);
			newPatchset.insertions = diffStat.getInsertions();
			newPatchset.deletions = diffStat.getDeletions();
		} else {
			/*
			 * PATCHSET UPDATE
			 */
			int added = totalCommits - currPatchset.commits;
			boolean ff = JGitUtils.isMergedInto(getRepository(), currPatchset.tip, tip);
			boolean squash = added < 0;
			boolean rebase = !currPatchset.base.equals(mergeBase);

			// determine type, number and rev of the patchset
			if (ff) {
				/*
				 * FAST-FORWARD
				 * patchset number preserved, rev incremented
				 */

				boolean merged = JGitUtils.isMergedInto(getRepository(), currPatchset.tip, ticket.mergeTo);
				if (merged) {
					// current patchset was already merged
					// new patchset, mark as rebase
					newPatchset.type = PatchsetType.Rebase;
					newPatchset.number = currPatchset.number + 1;
					newPatchset.rev = 1;

					// diffstat from parent
					DiffStat diffStat = DiffUtils.getDiffStat(getRepository(), mergeBase, tip);
					newPatchset.insertions = diffStat.getInsertions();
					newPatchset.deletions = diffStat.getDeletions();
				} else {
					// FF update to patchset
					newPatchset.type = PatchsetType.FastForward;
					newPatchset.number = currPatchset.number;
					newPatchset.rev = currPatchset.rev + 1;
					newPatchset.parent = currPatchset.tip;

					// diffstat from parent
					DiffStat diffStat = DiffUtils.getDiffStat(getRepository(), currPatchset.tip, tip);
					newPatchset.insertions = diffStat.getInsertions();
					newPatchset.deletions = diffStat.getDeletions();
				}
			} else {
				/*
				 * NON-FAST-FORWARD
				 * new patchset, rev 1
				 */
				if (rebase && squash) {
					newPatchset.type = PatchsetType.Rebase_Squash;
					newPatchset.number = currPatchset.number + 1;
					newPatchset.rev = 1;
				} else if (squash) {
					newPatchset.type = PatchsetType.Squash;
					newPatchset.number = currPatchset.number + 1;
					newPatchset.rev = 1;
				} else if (rebase) {
					newPatchset.type = PatchsetType.Rebase;
					newPatchset.number = currPatchset.number + 1;
					newPatchset.rev = 1;
				} else {
					newPatchset.type = PatchsetType.Amend;
					newPatchset.number = currPatchset.number + 1;
					newPatchset.rev = 1;
				}

				// diffstat from merge base
				DiffStat diffStat = DiffUtils.getDiffStat(getRepository(), mergeBase, tip);
				newPatchset.insertions = diffStat.getInsertions();
				newPatchset.deletions = diffStat.getDeletions();
			}

			if (added > 0) {
				// ignore squash (negative add)
				newPatchset.added = added;
			}
		}

		return newPatchset;
	}

	private RefUpdate updateRef(String ref, ObjectId newId, PatchsetType type) {
		ObjectId ticketRefId = ObjectId.zeroId();
		try {
			ticketRefId = getRepository().resolve(ref);
		} catch (Exception e) {
			// ignore
		}

		try {
			RefUpdate ru = getRepository().updateRef(ref,  false);
			ru.setRefLogIdent(getRefLogIdent());
			switch (type) {
			case Amend:
			case Rebase:
			case Rebase_Squash:
			case Squash:
				ru.setForceUpdate(true);
				break;
			default:
				break;
			}

			ru.setExpectedOldObjectId(ticketRefId);
			ru.setNewObjectId(newId);
			RefUpdate.Result result = ru.update(getRevWalk());
			if (result == RefUpdate.Result.LOCK_FAILURE) {
				sendError("Failed to obtain lock when updating {0}:{1}", repository.name, ref);
				sendError("Perhaps an administrator should remove {0}/{1}.lock?", getRepository().getDirectory(), ref);
				return null;
			}
			return ru;
		} catch (IOException e) {
			LOGGER.error("failed to update ref " + ref, e);
			sendError("There was an error updating ref {0}:{1}", repository.name, ref);
		}
		return null;
	}

	private void updateReflog(RefUpdate ru) {
		if (ru == null) {
			return;
		}

		ReceiveCommand.Type type = null;
		switch (ru.getResult()) {
		case NEW:
			type = Type.CREATE;
			break;
		case FAST_FORWARD:
			type = Type.UPDATE;
			break;
		case FORCED:
			type = Type.UPDATE_NONFASTFORWARD;
			break;
		default:
			LOGGER.error(MessageFormat.format("unexpected ref update type {0} for {1}",
					ru.getResult(), ru.getName()));
			return;
		}
		ReceiveCommand cmd = new ReceiveCommand(ru.getOldObjectId(), ru.getNewObjectId(), ru.getName(), type);
		RefLogUtils.updateRefLog(user, getRepository(), Arrays.asList(cmd));
	}

	/**
	 * Merge the specified patchset to the integration branch.
	 *
	 * @param ticket
	 * @param patchset
	 * @return true, if successful
	 */
	public MergeStatus merge(TicketModel ticket) {
		PersonIdent committer = new PersonIdent(user.getDisplayName(), StringUtils.isEmpty(user.emailAddress) ? (user.username + "@gitblit") : user.emailAddress);
		Patchset patchset = ticket.getCurrentPatchset();
		StringBuilder messageBuilder = new StringBuilder();
		messageBuilder.append(MessageFormat.format("Merged #{0,number,0} \"{1}\"", ticket.number, ticket.title));

		// Add sign-off tags to commit message footer if:
		//  - there are reviewers on this patchset
		//  - their review is positive
		//  - the setting is enabled on repo this patchset is for
		if (repository.writeSignoffCommit != null && repository.writeSignoffCommit.length() > 0) {
			messageBuilder.append("\n\n");
			for (Change change : ticket.getReviews(patchset)) {
				// Skip sign-off for negative reviews
				if (change.review.score.getValue() < 0) continue;
				UserModel ruser = gitblit.getUserModel(change.author);
				messageBuilder.append(MessageFormat.format(
							"{0}: {1} <{2}>\n", 
							repository.writeSignoffCommit,
							ruser.getDisplayName(), 
							StringUtils.isEmpty(ruser.emailAddress) ? (ruser.username + "@gitblit") : ruser.emailAddress
						)
				);
			}
			// Delete extra the line break at the end of the message
			messageBuilder.deleteCharAt(messageBuilder.length()-1);
		}
		
		// Convert the constructed message to String and continue
		String message = messageBuilder.toString();
		Ref oldRef = null;
		try {
			oldRef = getRepository().getRef(ticket.mergeTo);
		} catch (IOException e) {
			LOGGER.error("failed to get ref for " + ticket.mergeTo, e);
		}
		MergeResult mergeResult = JGitUtils.merge(
				getRepository(),
				patchset.tip,
				ticket.mergeTo,
				getRepositoryModel().mergeType,
				committer,
				message);

		if (StringUtils.isEmpty(mergeResult.sha)) {
			LOGGER.error("FAILED to merge {} to {} ({})", new Object [] { patchset, ticket.mergeTo, mergeResult.status.name() });
			return mergeResult.status;
		}
		Change change = new Change(user.username);
		change.setField(Field.status, Status.Merged);
		change.setField(Field.mergeSha, mergeResult.sha);
		change.setField(Field.mergeTo, ticket.mergeTo);

		if (StringUtils.isEmpty(ticket.responsible)) {
			// unassigned tickets are assigned to the closer
			change.setField(Field.responsible, user.username);
		}

		long ticketId = ticket.number;
		ticket = ticketService.updateTicket(repository, ticket.number, change);
		if (ticket != null) {
			ticketNotifier.queueMailing(ticket);

			if (oldRef != null) {
				ReceiveCommand cmd = new ReceiveCommand(oldRef.getObjectId(),
						ObjectId.fromString(mergeResult.sha), oldRef.getName());
				cmd.setResult(Result.OK);
				List<ReceiveCommand> commands = Arrays.asList(cmd);

				logRefChange(commands);
				updateIncrementalPushTags(commands);
				updateGitblitRefLog(commands);
			}

			// call patchset hooks
			for (PatchsetHook hook : gitblit.getExtensions(PatchsetHook.class)) {
				try {
					hook.onMergePatchset(ticket);
				} catch (Exception e) {
					LOGGER.error("Failed to execute extension", e);
				}
			}
			return mergeResult.status;
		} else {
			LOGGER.error("FAILED to resolve ticket {} by merge from web ui", ticketId);
		}
		return mergeResult.status;
	}

	public void sendAll() {
		ticketNotifier.sendAll();
	}
}
