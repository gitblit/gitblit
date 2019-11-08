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

import java.net.SocketAddress;

import org.apache.mina.transport.socket.SocketSessionConfig;
import org.apache.sshd.common.future.CloseFuture;
import org.apache.sshd.common.future.SshFutureListener;
import org.apache.sshd.common.io.IoSession;
import org.apache.sshd.common.io.mina.MinaSession;
import org.apache.sshd.common.session.helpers.AbstractSession;
import org.apache.sshd.server.ServerFactoryManager;
import org.apache.sshd.server.session.ServerSessionImpl;
import org.apache.sshd.server.session.SessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author James Moger
 *
 */
public class SshServerSessionFactory extends SessionFactory {

	private final Logger log = LoggerFactory.getLogger(getClass());

	public SshServerSessionFactory(ServerFactoryManager server) {
		super(server);
	}

	@Override
	protected ServerSessionImpl createSession(final IoSession io) throws Exception {
		log.info("creating ssh session from {}", io.getRemoteAddress());

		if (io instanceof MinaSession) {
			if (((MinaSession) io).getSession().getConfig() instanceof SocketSessionConfig) {
				((SocketSessionConfig) ((MinaSession) io).getSession().getConfig()).setKeepAlive(true);
			}
		}

		final SshServerSession session = (SshServerSession) super.createSession(io);
		SocketAddress peer = io.getRemoteAddress();
		SshDaemonClient client = new SshDaemonClient(peer);
		session.setAttribute(SshDaemonClient.KEY, client);

		// TODO(davido): Log a session close without authentication as a
		// failure.
		session.addCloseSessionListener(new SshFutureListener<CloseFuture>() {
			@Override
			public void operationComplete(CloseFuture future) {
				log.info("closed ssh session from {}", io.getRemoteAddress());
			}
		});
		return session;
	}

	@Override
	protected ServerSessionImpl doCreateSession(IoSession ioSession) throws Exception {
		return new SshServerSession(getServer(), ioSession);
	}
}
