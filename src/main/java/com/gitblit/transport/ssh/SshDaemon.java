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
package com.gitblit.transport.ssh;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.text.MessageFormat;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.sshd.SshServer;
import org.apache.sshd.server.keyprovider.PEMGeneratorHostKeyProvider;
import org.eclipse.jgit.internal.JGitText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gitblit.IStoredSettings;
import com.gitblit.Keys;
import com.gitblit.git.GitblitReceivePackFactory;
import com.gitblit.git.GitblitUploadPackFactory;
import com.gitblit.git.RepositoryResolver;
import com.gitblit.manager.IGitblit;
import com.gitblit.transport.ssh.commands.CreateRepository;
import com.gitblit.transport.ssh.commands.DispatchCommand;
import com.gitblit.transport.ssh.commands.VersionCommand;
import com.gitblit.utils.IdGenerator;
import com.gitblit.utils.StringUtils;
import com.gitblit.utils.WorkQueue;

/**
 * Manager for the ssh transport. Roughly analogous to the
 * {@link com.gitblit.git.GitDaemon} class.
 *
 * @author Eric Myhre
 *
 */
public class SshDaemon {

	private final Logger log = LoggerFactory.getLogger(SshDaemon.class);

	/**
	 * 22: IANA assigned port number for ssh. Note that this is a distinct
	 * concept from gitblit's default conf for ssh port -- this "default" is
	 * what the git protocol itself defaults to if it sees and ssh url without a
	 * port.
	 */
	public static final int DEFAULT_PORT = 22;

	private static final String HOST_KEY_STORE = "sshKeyStore.pem";

	private final AtomicBoolean run;

	@SuppressWarnings("unused")
	private final IGitblit gitblit;

	private final IdGenerator idGenerator;

	private final SshServer sshd;

	/**
	 * Construct the Gitblit SSH daemon.
	 *
	 * @param gitblit
	 */
	public SshDaemon(IGitblit gitblit, IdGenerator idGenerator) {
		this.gitblit = gitblit;
		this.idGenerator = idGenerator;

		IStoredSettings settings = gitblit.getSettings();
		int port = settings.getInteger(Keys.git.sshPort, 0);
		String bindInterface = settings.getString(Keys.git.sshBindInterface,
				"localhost");

		InetSocketAddress addr;
		if (StringUtils.isEmpty(bindInterface)) {
			addr = new InetSocketAddress(port);
		} else {
			addr = new InetSocketAddress(bindInterface, port);
		}

		sshd = SshServer.setUpDefaultServer();
		sshd.setPort(addr.getPort());
		sshd.setHost(addr.getHostName());
		sshd.setKeyPairProvider(new PEMGeneratorHostKeyProvider(new File(
				gitblit.getBaseFolder(), HOST_KEY_STORE).getPath()));
		sshd.setPublickeyAuthenticator(new SshKeyAuthenticator(gitblit));
		sshd.setPasswordAuthenticator(new SshPasswordAuthenticator(gitblit));
		sshd.setSessionFactory(new SshSessionFactory(idGenerator));
		sshd.setFileSystemFactory(new DisabledFilesystemFactory());
		sshd.setForwardingFilter(new NonForwardingFilter());

		DispatchCommand dispatcher = new DispatchCommand();
		dispatcher.registerCommand(CreateRepository.class);
		dispatcher.registerCommand(VersionCommand.class);

		SshCommandFactory commandFactory = new SshCommandFactory(
				new RepositoryResolver<SshSession>(gitblit),
				new GitblitUploadPackFactory<SshSession>(gitblit),
				new GitblitReceivePackFactory<SshSession>(gitblit),
				new WorkQueue(idGenerator),
				dispatcher);

		sshd.setCommandFactory(commandFactory);

		run = new AtomicBoolean(false);
	}

	public String formatUrl(String gituser, String servername, String repository) {
		if (sshd.getPort() == DEFAULT_PORT) {
			// standard port
			return MessageFormat.format("{0}@{1}/{2}", gituser, servername,
					repository);
		} else {
			// non-standard port
			return MessageFormat.format("ssh://{0}@{1}:{2,number,0}/{3}",
					gituser, servername, sshd.getPort(), repository);
		}
	}

	/**
	 * Start this daemon on a background thread.
	 *
	 * @throws IOException
	 *             the server socket could not be opened.
	 * @throws IllegalStateException
	 *             the daemon is already running.
	 */
	public synchronized void start() throws IOException {
		if (run.get()) {
			throw new IllegalStateException(JGitText.get().daemonAlreadyRunning);
		}

		sshd.start();
		run.set(true);

		log.info(MessageFormat.format(
				"SSH Daemon is listening on {0}:{1,number,0}",
				sshd.getHost(), sshd.getPort()));
	}

	/** @return true if this daemon is receiving connections. */
	public boolean isRunning() {
		return run.get();
	}

	/** Stop this daemon. */
	public synchronized void stop() {
		if (run.get()) {
			log.info("SSH Daemon stopping...");
			run.set(false);

			try {
				sshd.stop();
			} catch (InterruptedException e) {
				log.error("SSH Daemon stop interrupted", e);
			}
		}
	}
}
