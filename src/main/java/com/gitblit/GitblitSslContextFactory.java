/*
 * Copyright 2012 gitblit.com.
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
package com.gitblit;

import java.io.File;
import java.security.KeyStore;
import java.security.cert.CRL;
import java.util.Collection;

import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gitblit.utils.StringUtils;

/**
 * Special SSL context factory that configures Gitblit GO and replaces the
 * primary trustmanager with a GitblitTrustManager.
 *
 * @author James Moger
 */
public class GitblitSslContextFactory extends SslContextFactory {

	private static final Logger logger = LoggerFactory.getLogger(GitblitSslContextFactory.class);

	private final File caRevocationList;

	public GitblitSslContextFactory(String certAlias, File keyStore, File clientTrustStore,
			String storePassword, File caRevocationList, String[] excludeProtocols) {
		super(keyStore.getAbsolutePath());

		this.caRevocationList = caRevocationList;

		if (!StringUtils.isEmpty(certAlias)) {
			logger.info("   certificate alias = " + certAlias);
			setCertAlias(certAlias);
		}
		setKeyStorePassword(storePassword);
		setTrustStorePath(clientTrustStore.getAbsolutePath());
		setTrustStorePassword(storePassword);
        if ((excludeProtocols != null) && (excludeProtocols.length > 0)) {
            addExcludeProtocols(excludeProtocols);
        }

		logger.info("   keyStorePath   = " + keyStore.getAbsolutePath());
		logger.info("   trustStorePath = " + clientTrustStore.getAbsolutePath());
		logger.info("   crlPath        = " + caRevocationList.getAbsolutePath());
	}

	@Override
	protected TrustManager[] getTrustManagers(KeyStore trustStore, Collection<? extends CRL> crls)
			throws Exception {
		TrustManager[] managers = super.getTrustManagers(trustStore, crls);
		X509TrustManager delegate = (X509TrustManager) managers[0];
		GitblitTrustManager root = new GitblitTrustManager(delegate, caRevocationList);

		// replace first manager with the GitblitTrustManager
		managers[0] = root;
		return managers;
	}
}
