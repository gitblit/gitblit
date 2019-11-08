/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.gitblit.transport.ssh;

import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.security.KeyPair;
import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;

import org.apache.sshd.common.keyprovider.AbstractKeyPairProvider;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.bouncycastle.openssl.PEMDecryptorProvider;
import org.bouncycastle.openssl.PEMEncryptedKeyPair;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.PasswordFinder;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JcePEMDecryptorProviderBuilder;

/**
 * This host key provider loads private keys from the specified files.
 *
 * Note that this class has a direct dependency on BouncyCastle and won't work
 * unless it has been correctly registered as a security provider.
 *
 * @author <a href="mailto:dev@mina.apache.org">Apache MINA SSHD Project</a>
 */
public class FileKeyPairProvider extends AbstractKeyPairProvider {

    private String[] files;
    private PasswordFinder passwordFinder;

    public FileKeyPairProvider() {
    }

    public FileKeyPairProvider(String[] files) {
        this.files = files;
    }

    public FileKeyPairProvider(String[] files, PasswordFinder passwordFinder) {
        this.files = files;
        this.passwordFinder = passwordFinder;
    }

    public String[] getFiles() {
        return files;
    }

    public void setFiles(String[] files) {
        this.files = files;
    }

    public PasswordFinder getPasswordFinder() {
        return passwordFinder;
    }

    public void setPasswordFinder(PasswordFinder passwordFinder) {
        this.passwordFinder = passwordFinder;
    }

    public Iterable<KeyPair> loadKeys() {
        if (!SecurityUtils.isBouncyCastleRegistered()) {
            throw new IllegalStateException("BouncyCastle must be registered as a JCE provider");
        }
        return new Iterable<KeyPair>() {
            @Override
			public Iterator<KeyPair> iterator() {
                return new Iterator<KeyPair>() {
                    private final Iterator<String> iterator = Arrays.asList(files).iterator();
                    private KeyPair nextKeyPair;
                    private boolean nextKeyPairSet = false;
                    @Override
					public boolean hasNext() {
                        return nextKeyPairSet || setNextObject();
                    }
                    @Override
					public KeyPair next() {
                        if (!nextKeyPairSet) {
                            if (!setNextObject()) {
                                throw new NoSuchElementException();
                            }
                        }
                        nextKeyPairSet = false;
                        return nextKeyPair;
                    }
                    @Override
					public void remove() {
                        throw new UnsupportedOperationException();
                    }
                    private boolean setNextObject() {
                        while (iterator.hasNext()) {
                            String file = iterator.next();
                            nextKeyPair = doLoadKey(file);
                            if (nextKeyPair != null) {
                                nextKeyPairSet = true;
                                return true;
                            }
                        }
                        return false;
                    }

                };
            }
        };
    }

    protected KeyPair doLoadKey(String file) {
        try {
            PEMParser r = new PEMParser(new InputStreamReader(new FileInputStream(file)));
            try {
                Object o = r.readObject();

                JcaPEMKeyConverter pemConverter = new JcaPEMKeyConverter();
                pemConverter.setProvider("BC");
                if (passwordFinder != null && o instanceof PEMEncryptedKeyPair) {
                    JcePEMDecryptorProviderBuilder decryptorBuilder = new JcePEMDecryptorProviderBuilder();
                    PEMDecryptorProvider pemDecryptor = decryptorBuilder.build(passwordFinder.getPassword());
                    o = pemConverter.getKeyPair(((PEMEncryptedKeyPair) o).decryptKeyPair(pemDecryptor));
                }

                if (o instanceof PEMKeyPair) {
                    o = pemConverter.getKeyPair((PEMKeyPair)o);
                    return (KeyPair) o;
                } else if (o instanceof KeyPair) {
                    return (KeyPair) o;
                }
            } finally {
                r.close();
            }
        } catch (Exception e) {
            log.warn("Unable to read key " + file, e);
        }
        return null;
    }

}
