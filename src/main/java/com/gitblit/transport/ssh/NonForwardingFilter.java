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

import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.apache.sshd.server.forward.ForwardingFilter;

public class NonForwardingFilter implements ForwardingFilter {

    @Override
    public boolean canConnect(Type type, SshdSocketAddress address, Session session) {
        return false;
    }

    @Override
    public boolean canForwardAgent(Session session, String requestType) {
        return false;
    }

    @Override
    public boolean canForwardX11(Session session, String requestType) {
        return false;
    }

    @Override
    public boolean canListen(SshdSocketAddress address, Session session) {
        return false;
    }
}
