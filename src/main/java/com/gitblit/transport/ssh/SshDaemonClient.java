/*
 * Copyright 2014 gitblit.com.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.gitblit.transport.ssh;

import java.net.SocketAddress;


import com.gitblit.models.UserModel;
import org.apache.sshd.common.AttributeStore;

/**
 *
 * @author Eric Myrhe
 *
 */
public class SshDaemonClient {
	public static final AttributeStore.AttributeKey<SshDaemonClient> KEY = new AttributeStore.AttributeKey<SshDaemonClient>();

	private final SocketAddress remoteAddress;

	private volatile UserModel user;
	private volatile SshKey key;
	private volatile String repositoryName;

	SshDaemonClient(SocketAddress peer) {
		this.remoteAddress = peer;
	}

	public SocketAddress getRemoteAddress() {
		return remoteAddress;
	}

	public UserModel getUser() {
		return user;
	}

	public void setUser(UserModel user) {
		this.user = user;
	}

	public String getUsername() {
		return user == null ? null : user.username;
	}

	public void setRepositoryName(String repositoryName) {
		this.repositoryName = repositoryName;
	}

	public String getRepositoryName() {
		return repositoryName;
	}

	public SshKey getKey() {
		return key;
	}

	public void setKey(SshKey key) {
		this.key = key;
	}

}
