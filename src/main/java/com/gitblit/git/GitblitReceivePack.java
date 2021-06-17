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

import java.io.File;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import org.codehaus.groovy.runtime.InvokerHelper;
import org.eclipse.jgit.lib.BatchRefUpdate;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.PostReceiveHook;
import org.eclipse.jgit.transport.PreReceiveHook;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceiveCommand.Result;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gitblit.Constants;
import com.gitblit.Constants.AccessRestrictionType;
import com.gitblit.IStoredSettings;
import com.gitblit.Keys;
import com.gitblit.client.Translation;
import com.gitblit.extensions.ReceiveHook;
import com.gitblit.manager.IGitblit;
import com.gitblit.models.RepositoryModel;
import com.gitblit.models.TicketModel;
import com.gitblit.models.TicketModel.Change;
import com.gitblit.models.TicketModel.Field;
import com.gitblit.models.TicketModel.Status;
import com.gitblit.models.TicketModel.TicketLink;
import com.gitblit.models.UserModel;
import com.gitblit.tickets.BranchTicketService;
import com.gitblit.tickets.ITicketService;
import com.gitblit.tickets.TicketNotifier;
import com.gitblit.utils.ArrayUtils;
import com.gitblit.utils.ClientLogger;
import com.gitblit.utils.CommitCache;
import com.gitblit.utils.JGitUtils;
import com.gitblit.utils.RefLogUtils;
import com.gitblit.utils.StringUtils;

import groovy.lang.Binding;
import groovy.util.GroovyScriptEngine;


/**
 * GitblitReceivePack processes receive commands.  It also executes Groovy pre-
 * and post- receive hooks.
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
public class GitblitReceivePack extends ReceivePack implements PreReceiveHook, PostReceiveHook {

	private static final Logger LOGGER = LoggerFactory.getLogger(GitblitReceivePack.class);
	
	protected final RepositoryModel repository;

	protected final UserModel user;

	protected final File groovyDir;

	protected String gitblitUrl;

	protected GroovyScriptEngine gse;

	protected final IStoredSettings settings;

	protected final IGitblit gitblit;
	
	protected final ITicketService ticketService;

	protected final TicketNotifier ticketNotifier;
	

	public GitblitReceivePack(
			IGitblit gitblit,
			Repository db,
			RepositoryModel repository,
			UserModel user) {

		super(db);
		this.settings = gitblit.getSettings();
		this.gitblit = gitblit;
		this.repository = repository;
		this.user = user;
		this.groovyDir = gitblit.getHooksFolder();
		try {
			// set Grape root
			File grapeRoot = gitblit.getGrapesFolder();
			grapeRoot.mkdirs();
			System.setProperty("grape.root", grapeRoot.getAbsolutePath());
			this.gse = new GroovyScriptEngine(groovyDir.getAbsolutePath());
		} catch (IOException e) {
		}

		if (gitblit.getTicketService().isAcceptingTicketUpdates(repository)) {
			this.ticketService = gitblit.getTicketService();
			this.ticketNotifier = this.ticketService.createNotifier();
		} else {
			this.ticketService = null;
			this.ticketNotifier = null;
		}
		
		// set advanced ref permissions
		setAllowCreates(user.canCreateRef(repository));
		setAllowDeletes(user.canDeleteRef(repository));
		setAllowNonFastForwards(user.canRewindRef(repository));

		int maxObjectSz = settings.getInteger(Keys.git.maxObjectSizeLimit, -1);
		if (maxObjectSz >= 0) {
			setMaxObjectSizeLimit(maxObjectSz);
		}
		int maxPackSz = settings.getInteger(Keys.git.maxPackSizeLimit, -1);
		if (maxPackSz >= 0) {
			setMaxPackSizeLimit(maxPackSz);
		}
		setCheckReceivedObjects(settings.getBoolean(Keys.git.checkReceivedObjects, true));
		setCheckReferencedObjectsAreReachable(settings.getBoolean(Keys.git.checkReferencedObjectsAreReachable, true));

		// setup pre and post receive hook
		setPreReceiveHook(this);
		setPostReceiveHook(this);
	}

	/**
	 * Returns true if the user is permitted to apply the receive commands to
	 * the repository.
	 *
	 * @param commands
	 * @return true if the user may push these commands
	 */
	protected boolean canPush(Collection<ReceiveCommand> commands) {
		// TODO Consider supporting branch permissions here (issue-36)
		// Not sure if that should be Gerrit-style, refs/meta/config, or
		// gitolite-style, permissions in users.conf
		//
		// How could commands be empty?
		//
		// Because a subclass, like PatchsetReceivePack, filters receive
		// commands before this method is called.  This makes it possible for
		// this method to test an empty list.  In this case, we assume that the
		// subclass receive pack properly enforces push restrictions. for the
		// ref.
		//
		// The empty test is not explicitly required, it's written here to
		// clarify special-case behavior.

		return commands.isEmpty() ? true : user.canPush(repository);
	}

	/**
	 * Instrumentation point where the incoming push event has been parsed,
	 * validated, objects created BUT refs have not been updated. You might
	 * use this to enforce a branch-write permissions model.
	 */
	@Override
	public void onPreReceive(ReceivePack rp, Collection<ReceiveCommand> commands) {

		if (commands.size() == 0) {
			// no receive commands to process
			// this can happen if receive pack subclasses intercept and filter
			// the commands
			LOGGER.debug("skipping pre-receive processing, no refs created, updated, or removed");
			return;
		}

		if (repository.isMirror) {
			// repository is a mirror
			for (ReceiveCommand cmd : commands) {
				sendRejection(cmd, "Gitblit does not allow pushes to \"{0}\" because it is a mirror!", repository.name);
			}
			return;
		}

		if (repository.isFrozen) {
			// repository is frozen/readonly
			for (ReceiveCommand cmd : commands) {
				sendRejection(cmd, "Gitblit does not allow pushes to \"{0}\" because it is frozen!", repository.name);
			}
			return;
		}

		if (!repository.isBare) {
			// repository has a working copy
			for (ReceiveCommand cmd : commands) {
				sendRejection(cmd, "Gitblit does not allow pushes to \"{0}\" because it has a working copy!", repository.name);
			}
			return;
		}

		if (!canPush(commands)) {
			// user does not have push permissions
			for (ReceiveCommand cmd : commands) {
				sendRejection(cmd, "User \"{0}\" does not have push permissions for \"{1}\"!", user.username, repository.name);
			}
			return;
		}

		if (repository.accessRestriction.atLeast(AccessRestrictionType.PUSH) && repository.verifyCommitter) {
			// enforce committer verification
			if (StringUtils.isEmpty(user.emailAddress)) {
				// reject the push because the pushing account does not have an email address
				for (ReceiveCommand cmd : commands) {
					sendRejection(cmd, "Sorry, the account \"{0}\" does not have an email address set for committer verification!", user.username);
				}
				return;
			}

			// Optionally enforce that the committer of first parent chain
			// match the account being used to push the commits.
			//
			// This requires all merge commits are executed with the "--no-ff"
			// option to force a merge commit even if fast-forward is possible.
			// This ensures that the chain first parents has the commit
			// identity of the merging user.
			boolean allRejected = false;
			for (ReceiveCommand cmd : commands) {
				String firstParent = null;
				try {
					List<RevCommit> commits = JGitUtils.getRevLog(rp.getRepository(), cmd.getOldId().name(), cmd.getNewId().name());
					for (RevCommit commit : commits) {

						if (firstParent != null) {
		            		if (!commit.getName().equals(firstParent)) {
		            			// ignore: commit is right-descendant of a merge
		            			continue;
		            		}
		            	}

						// update expected next commit id
						if (commit.getParentCount() == 0) {
		                	firstParent = null;
						} else {
							firstParent = commit.getParents()[0].getId().getName();
						}

						PersonIdent committer = commit.getCommitterIdent();
						if (!user.is(committer.getName(), committer.getEmailAddress())) {
							// verification failed
							String reason = MessageFormat.format("{0} by {1} <{2}> was not committed by {3} ({4}) <{5}>",
									commit.getId().name(), committer.getName(), StringUtils.isEmpty(committer.getEmailAddress()) ? "?":committer.getEmailAddress(), user.getDisplayName(), user.username, user.emailAddress);
							LOGGER.warn(reason);
							cmd.setResult(Result.REJECTED_OTHER_REASON, reason);
							allRejected &= true;
							break;
						} else {
							allRejected = false;
						}
					}
				} catch (Exception e) {
					LOGGER.error("Failed to verify commits were made by pushing user", e);
				}
			}

			if (allRejected) {
				// all ref updates rejected, abort
				return;
			}
		}

		for (ReceiveCommand cmd : commands) {
			String ref = cmd.getRefName();
			if (ref.startsWith(Constants.R_HEADS)) {
				switch (cmd.getType()) {
				case UPDATE_NONFASTFORWARD:
				case DELETE:
					// reset branch commit cache on REWIND and DELETE
					CommitCache.instance().clear(repository.name, ref);
					break;
				default:
					break;
				}
			} else if (ref.equals(BranchTicketService.BRANCH)) {
				// ensure pushing user is an administrator OR an owner
				// i.e. prevent ticket tampering
				boolean permitted = user.canAdmin() || repository.isOwner(user.username);
				if (!permitted) {
					sendRejection(cmd, "{0} is not permitted to push to {1}", user.username, ref);
				}
			} else if (ref.startsWith(Constants.R_FOR)) {
				// prevent accidental push to refs/for
				sendRejection(cmd, "{0} is not configured to receive patchsets", repository.name);
			}
		}

		// call pre-receive plugins
		for (ReceiveHook hook : gitblit.getExtensions(ReceiveHook.class)) {
			try {
				hook.onPreReceive(this, commands);
			} catch (Exception e) {
				LOGGER.error("Failed to execute extension", e);
			}
		}

		Set<String> scripts = new LinkedHashSet<String>();
		scripts.addAll(gitblit.getPreReceiveScriptsInherited(repository));
		if (!ArrayUtils.isEmpty(repository.preReceiveScripts)) {
			scripts.addAll(repository.preReceiveScripts);
		}
		runGroovy(commands, scripts);
		runGitblitfile(commands, scripts, "PreReceive");
		for (ReceiveCommand cmd : commands) {
			if (!Result.NOT_ATTEMPTED.equals(cmd.getResult())) {
				LOGGER.warn(MessageFormat.format("{0} {1} because \"{2}\"", cmd.getNewId()
						.getName(), cmd.getResult(), cmd.getMessage()));
			}
		}
	}

	/**
	 * Instrumentation point where the incoming push has been applied to the
	 * repository. This is the point where we would trigger a Jenkins build
	 * or send an email.
	 */
	@Override
	public void onPostReceive(ReceivePack rp, Collection<ReceiveCommand> commands) {
		if (commands.size() == 0) {
			LOGGER.debug("skipping post-receive processing, no refs created, updated, or removed");
			return;
		}

		logRefChange(commands);
		updateIncrementalPushTags(commands);
		updateGitblitRefLog(commands);

		// check for updates pushed to the BranchTicketService branch
		// if the BranchTicketService is active it will reindex, as appropriate
		for (ReceiveCommand cmd : commands) {
			if (Result.OK.equals(cmd.getResult())
					&& BranchTicketService.BRANCH.equals(cmd.getRefName())) {
				rp.getRepository().fireEvent(new ReceiveCommandEvent(repository, cmd));
			}
		}

		// call post-receive plugins
		for (ReceiveHook hook : gitblit.getExtensions(ReceiveHook.class)) {
			try {
				hook.onPostReceive(this, commands);
			} catch (Exception e) {
				LOGGER.error("Failed to execute extension", e);
			}
		}

		// run Groovy hook scripts
		Set<String> scripts = new LinkedHashSet<String>();
		scripts.addAll(gitblit.getPostReceiveScriptsInherited(repository));
		if (!ArrayUtils.isEmpty(repository.postReceiveScripts)) {
			scripts.addAll(repository.postReceiveScripts);
		}
		runGroovy(commands, scripts);
		runGitblitfile(commands, scripts, "PostReceive");
	}

	/**
	 * Log the ref changes in the container log.
	 *
	 * @param commands
	 */
	protected void logRefChange(Collection<ReceiveCommand> commands) {
		boolean isRefCreationOrDeletion = false;

		// log ref changes
		for (ReceiveCommand cmd : commands) {

			if (Result.OK.equals(cmd.getResult())) {
				// add some logging for important ref changes
				switch (cmd.getType()) {
				case DELETE:
					LOGGER.info(MessageFormat.format("{0} DELETED {1} in {2} ({3})", user.username, cmd.getRefName(), repository.name, cmd.getOldId().name()));
					isRefCreationOrDeletion = true;
					break;
				case CREATE:
					LOGGER.info(MessageFormat.format("{0} CREATED {1} in {2}", user.username, cmd.getRefName(), repository.name));
					isRefCreationOrDeletion = true;
					break;
				case UPDATE:
					LOGGER.info(MessageFormat.format("{0} UPDATED {1} in {2} (from {3} to {4})", user.username, cmd.getRefName(), repository.name, cmd.getOldId().name(), cmd.getNewId().name()));
					break;
				case UPDATE_NONFASTFORWARD:
					LOGGER.info(MessageFormat.format("{0} UPDATED NON-FAST-FORWARD {1} in {2} (from {3} to {4})", user.username, cmd.getRefName(), repository.name, cmd.getOldId().name(), cmd.getNewId().name()));
					break;
				default:
					break;
				}
			}
		}

		if (isRefCreationOrDeletion) {
			gitblit.resetRepositoryCache(repository.name);
		}
	}

	/**
	 * Optionally update the incremental push tags.
	 *
	 * @param commands
	 */
	protected void updateIncrementalPushTags(Collection<ReceiveCommand> commands) {
		if (!repository.useIncrementalPushTags) {
			return;
		}

		// tag each pushed branch tip
		String emailAddress = user.emailAddress == null ? getRefLogIdent().getEmailAddress() : user.emailAddress;
		PersonIdent userIdent = new PersonIdent(user.getDisplayName(), emailAddress);

		for (ReceiveCommand cmd : commands) {
			if (!cmd.getRefName().startsWith(Constants.R_HEADS)) {
				// only tag branch ref changes
				continue;
			}

			if (!ReceiveCommand.Type.DELETE.equals(cmd.getType())
					&& ReceiveCommand.Result.OK.equals(cmd.getResult())) {
				String objectId = cmd.getNewId().getName();
				String branch = cmd.getRefName().substring(Constants.R_HEADS.length());
				// get translation based on the server's locale setting
				String template = Translation.get("gb.incrementalPushTagMessage");
				String msg = MessageFormat.format(template, branch);
				String prefix;
				if (StringUtils.isEmpty(repository.incrementalPushTagPrefix)) {
					prefix = settings.getString(Keys.git.defaultIncrementalPushTagPrefix, "r");
				} else {
					prefix = repository.incrementalPushTagPrefix;
				}

				JGitUtils.createIncrementalRevisionTag(
						getRepository(),
						objectId,
						userIdent,
						prefix,
						"0",
						msg);
			}
		}
	}

	/**
	 * Update Gitblit's internal reflog.
	 *
	 * @param commands
	 */
	protected void updateGitblitRefLog(Collection<ReceiveCommand> commands) {
		try {
			RefLogUtils.updateRefLog(user, getRepository(), commands);
			LOGGER.debug(MessageFormat.format("{0} reflog updated", repository.name));
		} catch (Exception e) {
			LOGGER.error(MessageFormat.format("Failed to update {0} reflog", repository.name), e);
		}
	}

	/** Execute commands to update references. */
	@Override
	protected void executeCommands() {
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

		for (ReceiveCommand cmd : toApply) {
			if (Result.NOT_ATTEMPTED != cmd.getResult()) {
				// Already rejected by the core receive process.
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
					}
				}
			}
		}
		
		//
		// if there are ref update receive commands that were
		// successfully processed and there is an active ticket service for the repository
		// then process any referenced tickets
		//
		if (ticketService != null) {
			List<ReceiveCommand> allUpdates = ReceiveCommand.filter(batch.getCommands(), Result.OK);
			if (!allUpdates.isEmpty()) {
				int ticketsProcessed = 0;
				for (ReceiveCommand cmd : allUpdates) {
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
					case DELETE:
						//Identify if the branch has been merged 
						SortedMap<Integer, String> bases =  new TreeMap<Integer, String>();
						try {
							ObjectId dObj = cmd.getOldId();
							Collection<Ref> tips = getRepository().getRefDatabase().getRefs(Constants.R_HEADS).values();
							for (Ref ref : tips) {
								ObjectId iObj = ref.getObjectId();
								String mergeBase = JGitUtils.getMergeBase(getRepository(), dObj, iObj);
								if (mergeBase != null) {
									int d = JGitUtils.countCommits(getRepository(), getRevWalk(), mergeBase, dObj.name());
									bases.put(d, mergeBase);
									//All commits have been merged into some other branch
									if (d == 0) {
										break;
									}
								}
							}
							
							if (bases.isEmpty()) {
								//TODO: Handle orphan branch case
							} else {
								if (bases.firstKey() > 0) {
									//Delete references from the remaining commits that haven't been merged
									String mergeBase = bases.get(bases.firstKey());
									List<TicketLink> deletedRefs = JGitUtils.identifyTicketsBetweenCommits(getRepository(),
											settings, mergeBase, dObj.name());
									
									for (TicketLink link : deletedRefs) {
										link.isDelete = true;
									}
									Change deletion = new Change(user.username);
									deletion.pendingLinks = deletedRefs;
									ticketService.updateTicket(repository, 0, deletion);
								}
							}
							
						} catch (IOException e) {
							LOGGER.error(null, e);
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
	}

	protected void setGitblitUrl(String url) {
		this.gitblitUrl = url;
	}

	public void sendRejection(final ReceiveCommand cmd, final String why, Object... objects) {
		String text;
		if (ArrayUtils.isEmpty(objects)) {
			text = why;
		} else {
			text = MessageFormat.format(why, objects);
		}
		cmd.setResult(Result.REJECTED_OTHER_REASON, text);
		LOGGER.error(text + " (" + user.username + ")");
	}

	public void sendHeader(String msg, Object... objects) {
		sendInfo("--> ", msg, objects);
	}

	public void sendInfo(String msg, Object... objects) {
		sendInfo("    ", msg, objects);
	}

	private void sendInfo(String prefix, String msg, Object... objects) {
		String text;
		if (ArrayUtils.isEmpty(objects)) {
			text = msg;
			super.sendMessage(prefix + msg);
		} else {
			text = MessageFormat.format(msg, objects);
			super.sendMessage(prefix + text);
		}
		if (!StringUtils.isEmpty(msg)) {
			LOGGER.info(text + " (" + user.username + ")");
		}
	}

	public void sendError(String msg, Object... objects) {
		String text;
		if (ArrayUtils.isEmpty(objects)) {
			text = msg;
			super.sendError(msg);
		} else {
			text = MessageFormat.format(msg, objects);
			super.sendError(text);
		}
		if (!StringUtils.isEmpty(msg)) {
			LOGGER.error(text + " (" + user.username + ")");
		}
	}

	/**
	 * Runs the specified Groovy hook scripts.
	 *
	 * @param repository
	 * @param user
	 * @param commands
	 * @param scripts
	 */
	private void runGroovy(Collection<ReceiveCommand> commands, Set<String> scripts) {
		if (scripts == null || scripts.size() == 0) {
			// no Groovy scripts to execute
			return;
		}

		Binding binding = new Binding();
		binding.setVariable("gitblit", gitblit);
		binding.setVariable("repository", repository);
		binding.setVariable("receivePack", this);
		binding.setVariable("user", user);
		binding.setVariable("commands", commands);
		binding.setVariable("url", gitblitUrl);
		binding.setVariable("logger", LOGGER);
		binding.setVariable("clientLogger", new ClientLogger(this));
		for (String script : scripts) {
			if (StringUtils.isEmpty(script)) {
				continue;
			}
			// allow script to be specified without .groovy extension
			// this is easier to read in the settings
			File file = new File(groovyDir, script);
			if (!file.exists() && !script.toLowerCase().endsWith(".groovy")) {
				file = new File(groovyDir, script + ".groovy");
				if (file.exists()) {
					script = file.getName();
				}
			}
			try {
				Object result = gse.run(script, binding);
				if (result instanceof Boolean) {
					if (!((Boolean) result)) {
						LOGGER.error(MessageFormat.format(
								"Groovy script {0} has failed!  Hook scripts aborted.", script));
						break;
					}
				}
			} catch (Exception e) {
				LOGGER.error(
						MessageFormat.format("Failed to execute Groovy script {0}", script), e);
			}
		}
	}
	
	/**
     * Runs the specified Gitblitfile hook scripts.
     *
     * @param commands
     * @param scripts
     * @param event
     */
    private void runGitblitfile(Collection<ReceiveCommand> commands, Set<String> scripts, String event) {
        LOGGER.info("Start executing the Gitblitfile script...");
        @SuppressWarnings("rawtypes")
        Class cls = loadGitblitfile();
        if (null == cls) {
            // no Gitblitfile scripts to execute
            LOGGER.info("No found Gitblitfile script to execute.");
            return;
        }

        Binding binding = new Binding();
        binding.setVariable("gitblit", gitblit);
        binding.setVariable("repository", repository);
        binding.setVariable("receivePack", this);
        binding.setVariable("user", user);
        binding.setVariable("commands", commands);
        binding.setVariable("url", gitblitUrl);
        binding.setVariable("logger", LOGGER);
        binding.setVariable("clientLogger", new ClientLogger(this));
        binding.setVariable("event", event);
        
        try {
            Object result = InvokerHelper.createScript(cls, binding).run();
            if (result instanceof Boolean && !((Boolean) result)) {
                LOGGER.error("Gitblitfile has failed! Script aborted.");
            }
        } catch (Exception e) {
            LOGGER.error("Failed to execute Gitblitfile", e);
        }
    }
    
    @SuppressWarnings({ "rawtypes" })
    private Class loadGitblitfile() {
        Repository db = getRepository();
        try {
            Ref ref = db.findRef(db.getBranch());
            ObjectId objId = ref.getObjectId();
            RevCommit revCommit = getRevWalk().parseCommit(objId);
            RevTree revTree = revCommit.getTree();
            TreeWalk treeWalk = TreeWalk.forPath(db, "Gitblitfile", revTree);
            if (null == treeWalk) {
                return null;
            }

            ObjectId blobId = treeWalk.getObjectId(0);
            ObjectLoader loader = db.open(blobId);
            byte[] bytes = loader.getBytes();
            if (null == bytes || bytes.length == 0) {
                return null;
            }

            return gse.getGroovyClassLoader().parseClass(new String(bytes));
        } catch (Exception e) {
            LOGGER.error("Failed to parse Gitblitfile script", e);
            return null;
        }
    }

	public IGitblit getGitblit() {
		return gitblit;
	}

	public RepositoryModel getRepositoryModel() {
		return repository;
	}

	public UserModel getUserModel() {
		return user;
	}
	
	/**
	 * Automatically closes open tickets and adds references to tickets if made in the commit message.
	 *
	 * @param cmd
	 */
	private Collection<TicketModel> processReferencedTickets(ReceiveCommand cmd) {
		Map<Long, TicketModel> changedTickets = new LinkedHashMap<Long, TicketModel>();

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
					
					TicketModel ticket = ticketService.getTicket(repository, link.targetTicketId);
					if (ticket == null) {
						continue;
					}
					
					Change change = null;
					String commitSha = c.getName();
					String branchName = Repository.shortenRefName(cmd.getRefName());
					
					switch (link.action) {
						case Commit: {
							//A commit can reference a ticket in any branch even if the ticket is closed.
							//This allows developers to identify and communicate related issues
							change = new Change(user.username);
							change.referenceCommit(commitSha);
						} break;
						
						case Close: {
							// As this isn't a patchset theres no merging taking place when closing a ticket
							if (ticket.isClosed()) {
								continue;
							}
							
							change = new Change(user.username);
							change.setField(Field.status, Status.Fixed);
							
							if (StringUtils.isEmpty(ticket.responsible)) {
								// unassigned tickets are assigned to the closer
								change.setField(Field.responsible, user.username);
							}
						}
						
						default: {
							//No action
						} break;
					}
					
					if (change != null) {
						ticket = ticketService.updateTicket(repository, ticket.number, change);
					}
	
					if (ticket != null) {
						sendInfo("");
						sendHeader("#{0,number,0}: {1}", ticket.number, StringUtils.trimString(ticket.title, Constants.LEN_SHORTLOG));

						switch (link.action) {
							case Commit: {
								sendInfo("referenced by push of {0} to {1}", commitSha, branchName);
								changedTickets.put(ticket.number, ticket);
							} break;

							case Close: {
								sendInfo("closed by push of {0} to {1}", commitSha, branchName);
								changedTickets.put(ticket.number, ticket);
							} break;

							default: { }
						}

						sendInfo(ticketService.getTicketUrl(ticket));
						sendInfo("");
					} else {
						switch (link.action) {
							case Commit: {
								sendError("FAILED to reference ticket {0} by push of {1}", link.targetTicketId, commitSha);
							} break;
							
							case Close: {
								sendError("FAILED to close ticket {0} by push of {1}", link.targetTicketId, commitSha);	
							} break;
							
							default: { }
						}
					}
				}
			}
				
		} catch (IOException e) {
			LOGGER.error("Can't scan for changes to reference or close", e);
		} finally {
			rw.reset();
		}

		return changedTickets.values();
	}
}
