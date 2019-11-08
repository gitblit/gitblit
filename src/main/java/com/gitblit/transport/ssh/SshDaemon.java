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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.text.MessageFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.sshd.common.io.IoServiceFactoryFactory;
import org.apache.sshd.common.io.mina.MinaServiceFactoryFactory;
import org.apache.sshd.common.io.nio2.Nio2ServiceFactoryFactory;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.apache.sshd.common.util.security.bouncycastle.BouncyCastleSecurityProviderRegistrar;
import org.apache.sshd.common.util.security.eddsa.EdDSASecurityProviderRegistrar;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.pubkey.CachingPublicKeyAuthenticator;
import org.bouncycastle.openssl.PEMWriter;
import org.eclipse.jgit.internal.JGitText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gitblit.Constants;
import com.gitblit.IStoredSettings;
import com.gitblit.Keys;
import com.gitblit.manager.IGitblit;
import com.gitblit.transport.ssh.commands.SshCommandFactory;
import com.gitblit.utils.JnaUtils;
import com.gitblit.utils.StringUtils;
import com.gitblit.utils.WorkQueue;
import com.google.common.io.Files;

/**
 * Manager for the ssh transport. Roughly analogous to the
 * {@link com.gitblit.transport.git.GitDaemon} class.
 *
 */
public class SshDaemon {

	private final Logger log = LoggerFactory.getLogger(SshDaemon.class);

	private static final String AUTH_PUBLICKEY = "publickey";
	private static final String AUTH_PASSWORD = "password";
	private static final String AUTH_KBD_INTERACTIVE = "keyboard-interactive";
	private static final String AUTH_GSSAPI = "gssapi-with-mic";



	public static enum SshSessionBackend {
		MINA, NIO2
	}

	/**
	 * 22: IANA assigned port number for ssh. Note that this is a distinct
	 * concept from gitblit's default conf for ssh port -- this "default" is
	 * what the git protocol itself defaults to if it sees and ssh url without a
	 * port.
	 */
	public static final int DEFAULT_PORT = 22;

	private final AtomicBoolean run;

	private final IGitblit gitblit;
	private final SshServer sshd;

	/**
	 * Construct the Gitblit SSH daemon.
	 *
	 * @param gitblit
	 * @param workQueue
	 */
	public SshDaemon(IGitblit gitblit, WorkQueue workQueue) {
		this.gitblit = gitblit;

		IStoredSettings settings = gitblit.getSettings();

		// Ensure that Bouncy Castle is our JCE provider
		SecurityUtils.registerSecurityProvider(new BouncyCastleSecurityProviderRegistrar());
		// Add support for ED25519_SHA512
		SecurityUtils.registerSecurityProvider(new EdDSASecurityProviderRegistrar());
		if (SecurityUtils.isBouncyCastleRegistered()) {
			log.info("BouncyCastle is registered as a JCE provider");
		}

		// Generate host RSA and DSA keypairs and create the host keypair provider
		File rsaKeyStore = new File(gitblit.getBaseFolder(), "ssh-rsa-hostkey.pem");
		File dsaKeyStore = new File(gitblit.getBaseFolder(), "ssh-dsa-hostkey.pem");
		generateKeyPair(rsaKeyStore, "RSA", 2048);
		generateKeyPair(dsaKeyStore, "DSA", 0);
		FileKeyPairProvider hostKeyPairProvider = new FileKeyPairProvider();
		hostKeyPairProvider.setFiles(new String [] { rsaKeyStore.getPath(), dsaKeyStore.getPath(), dsaKeyStore.getPath() });


		// Configure the preferred SSHD backend
		String sshBackendStr = settings.getString(Keys.git.sshBackend,
				SshSessionBackend.NIO2.name());
		SshSessionBackend backend = SshSessionBackend.valueOf(sshBackendStr);
		System.setProperty(IoServiceFactoryFactory.class.getName(),
		    backend == SshSessionBackend.MINA
		    	? MinaServiceFactoryFactory.class.getName()
		    	: Nio2ServiceFactoryFactory.class.getName());

		// Create the socket address for binding the SSH server
		int port = settings.getInteger(Keys.git.sshPort, 0);
		String bindInterface = settings.getString(Keys.git.sshBindInterface, "");
		InetSocketAddress addr;
		if (StringUtils.isEmpty(bindInterface)) {
			addr = new InetSocketAddress(port);
		} else {
			addr = new InetSocketAddress(bindInterface, port);
		}

		// Create the SSH server
		sshd = SshServer.setUpDefaultServer();
		sshd.setPort(addr.getPort());
		sshd.setHost(addr.getHostName());
		sshd.setKeyPairProvider(hostKeyPairProvider);

		List<String> authMethods = settings.getStrings(Keys.git.sshAuthenticationMethods);
		if (authMethods.isEmpty()) {
			authMethods.add(AUTH_PUBLICKEY);
			authMethods.add(AUTH_PASSWORD);
		}
		// Keep backward compatibility with old setting files that use the git.sshWithKrb5 setting.
		if (settings.getBoolean("git.sshWithKrb5", false) && !authMethods.contains(AUTH_GSSAPI)) {
			authMethods.add(AUTH_GSSAPI);
			log.warn("git.sshWithKrb5 is obsolete!");
			log.warn("Please add {} to {} in gitblit.properties!", AUTH_GSSAPI, Keys.git.sshAuthenticationMethods);
			settings.overrideSetting(Keys.git.sshAuthenticationMethods,
					settings.getString(Keys.git.sshAuthenticationMethods, AUTH_PUBLICKEY + " " + AUTH_PASSWORD) + " " + AUTH_GSSAPI);
		}
		if (authMethods.contains(AUTH_PUBLICKEY)) {
			SshKeyAuthenticator keyAuthenticator = new SshKeyAuthenticator(gitblit.getPublicKeyManager(), gitblit);
			sshd.setPublickeyAuthenticator(new CachingPublicKeyAuthenticator(keyAuthenticator));
			log.info("SSH: adding public key authentication method.");
		}
		if (authMethods.contains(AUTH_PASSWORD) || authMethods.contains(AUTH_KBD_INTERACTIVE)) {
			sshd.setPasswordAuthenticator(new UsernamePasswordAuthenticator(gitblit));
			log.info("SSH: adding password authentication method.");
		}
		if (authMethods.contains(AUTH_GSSAPI)) {
			sshd.setGSSAuthenticator(new SshKrbAuthenticator(settings, gitblit));
			log.info("SSH: adding GSSAPI authentication method.");
		}

		sshd.setSessionFactory(new SshServerSessionFactory(sshd));
		sshd.setFileSystemFactory(new DisabledFilesystemFactory());
		sshd.setTcpipForwardingFilter(new NonForwardingFilter());
		sshd.setCommandFactory(new SshCommandFactory(gitblit, workQueue));
		sshd.setShellFactory(new WelcomeShell(gitblit));

		// Set the server id.  This can be queried with:
		//   ssh-keyscan -t rsa,dsa -p 29418 localhost
		String version = String.format("%s (%s-%s)", Constants.getGitBlitVersion().replace(' ', '_'),
				sshd.getVersion(), sshBackendStr);
		sshd.getProperties().put(SshServer.SERVER_IDENTIFICATION, version);

		run = new AtomicBoolean(false);
	}

	public String formatUrl(String gituser, String servername, String repository) {
		IStoredSettings settings = gitblit.getSettings();

		int port = sshd.getPort();
		int displayPort = settings.getInteger(Keys.git.sshAdvertisedPort, port);
		String displayServername = settings.getString(Keys.git.sshAdvertisedHost, "");
		if(displayServername.isEmpty()) {
			displayServername = servername;
		}
		if (displayPort == DEFAULT_PORT) {
			// standard port
			return MessageFormat.format("ssh://{0}@{1}/{2}", gituser, displayServername,
					repository);
		} else {
			// non-standard port
			return MessageFormat.format("ssh://{0}@{1}:{2,number,0}/{3}",
					gituser, displayServername, displayPort, repository);
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

		String sshBackendStr = gitblit.getSettings().getString(Keys.git.sshBackend,
				SshSessionBackend.NIO2.name());

		log.info(MessageFormat.format(
				"SSH Daemon ({0}) is listening on {1}:{2,number,0}",
				sshBackendStr, sshd.getHost(), sshd.getPort()));
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
				((SshCommandFactory) sshd.getCommandFactory()).stop();
				sshd.stop();
			} catch (IOException e) {
				log.error("SSH Daemon stop interrupted", e);
			}
		}
	}

    private void generateKeyPair(File file, String algorithm, int keySize) {
    	if (file.exists()) {
    		return;
    	}
        try {
            KeyPairGenerator generator = SecurityUtils.getKeyPairGenerator(algorithm);
            if (keySize != 0) {
            	generator.initialize(keySize);
                log.info("Generating {}-{} SSH host keypair...", algorithm, keySize);
            } else {
                log.info("Generating {} SSH host keypair...", algorithm);
            }
            KeyPair kp = generator.generateKeyPair();

            // create an empty file and set the permissions
            Files.touch(file);
            try {
            	JnaUtils.setFilemode(file, JnaUtils.S_IRUSR | JnaUtils.S_IWUSR);
            } catch (UnsatisfiedLinkError | UnsupportedOperationException e) {
            	// Unexpected/Unsupported OS or Architecture
            }

            FileOutputStream os = new FileOutputStream(file);
            PEMWriter w = new PEMWriter(new OutputStreamWriter(os));
            w.writeObject(kp);
            w.flush();
            w.close();
        } catch (Exception e) {
            log.warn(MessageFormat.format("Unable to generate {0} keypair", algorithm), e);
            return;
        }
    }
}
