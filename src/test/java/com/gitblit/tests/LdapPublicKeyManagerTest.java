/*
 * Copyright 2016 Florian Zschocke
 * Copyright 2016 gitblit.com
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
package com.gitblit.tests;

import static org.junit.Assume.assumeTrue;

import java.security.GeneralSecurityException;
import java.security.InvalidAlgorithmParameterException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.sshd.common.util.security.SecurityUtils;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import com.gitblit.Keys;
import com.gitblit.Constants.AccessPermission;
import com.gitblit.transport.ssh.LdapKeyManager;
import com.gitblit.transport.ssh.SshKey;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;

/**
 * Test LdapPublicKeyManager going against an in-memory UnboundID
 * LDAP server.
 *
 * @author Florian Zschocke
 *
 */
@RunWith(Parameterized.class)
public class LdapPublicKeyManagerTest extends LdapBasedUnitTest {

	private static Map<String,KeyPair> keyPairs = new HashMap<>(10);
	private static KeyPairGenerator rsaGenerator;
	private static KeyPairGenerator dsaGenerator;
	private static KeyPairGenerator ecGenerator;



	@BeforeClass
	public static void init() throws GeneralSecurityException {
		rsaGenerator = SecurityUtils.getKeyPairGenerator("RSA");
		dsaGenerator = SecurityUtils.getKeyPairGenerator("DSA");
		ecGenerator = SecurityUtils.getKeyPairGenerator("ECDSA");
	}



	@Test
	public void testGetKeys() throws LDAPException {
		String keyRsaOne = getRsaPubKey("UserOne@example.com");
		getDS().modify(DN_USER_ONE, new Modification(ModificationType.ADD, "sshPublicKey", keyRsaOne));

		String keyRsaTwo = getRsaPubKey("UserTwo@example.com");
		String keyDsaTwo = getDsaPubKey("UserTwo@example.com");
		getDS().modify(DN_USER_TWO, new Modification(ModificationType.ADD, "sshPublicKey", keyRsaTwo, keyDsaTwo));

		String keyRsaThree = getRsaPubKey("UserThree@example.com");
		String keyDsaThree = getDsaPubKey("UserThree@example.com");
		String keyEcThree  = getEcPubKey("UserThree@example.com");
		getDS().modify(DN_USER_THREE, new Modification(ModificationType.ADD, "sshPublicKey", keyEcThree, keyRsaThree, keyDsaThree));

		LdapKeyManager kmgr = new LdapKeyManager(settings);

		List<SshKey> keys = kmgr.getKeys("UserOne");
		assertNotNull(keys);
		assertTrue(keys.size() == 1);
		assertEquals(keyRsaOne, keys.get(0).getRawData());


		keys = kmgr.getKeys("UserTwo");
		assertNotNull(keys);
		assertTrue(keys.size() == 2);
		if (keyRsaTwo.equals(keys.get(0).getRawData())) {
			assertEquals(keyDsaTwo, keys.get(1).getRawData());
		} else if (keyDsaTwo.equals(keys.get(0).getRawData())) {
			assertEquals(keyRsaTwo, keys.get(1).getRawData());
		} else {
			fail("Mismatch in UserTwo keys.");
		}


		keys = kmgr.getKeys("UserThree");
		assertNotNull(keys);
		assertTrue(keys.size() == 3);
		assertEquals(keyEcThree, keys.get(0).getRawData());
		assertEquals(keyRsaThree, keys.get(1).getRawData());
		assertEquals(keyDsaThree, keys.get(2).getRawData());

		keys = kmgr.getKeys("UserFour");
		assertNotNull(keys);
		assertTrue(keys.size() == 0);
	}


	@Test
	public void testGetKeysAttributeName() throws LDAPException {
		settings.put(Keys.realm.ldap.sshPublicKey, "sshPublicKey");

		String keyRsaOne = getRsaPubKey("UserOne@example.com");
		getDS().modify(DN_USER_ONE, new Modification(ModificationType.ADD, "sshPublicKey", keyRsaOne));

		String keyDsaTwo = getDsaPubKey("UserTwo@example.com");
		getDS().modify(DN_USER_TWO, new Modification(ModificationType.ADD, "publicsshkey", keyDsaTwo));

		String keyRsaThree = getRsaPubKey("UserThree@example.com");
		String keyDsaThree = getDsaPubKey("UserThree@example.com");
		getDS().modify(DN_USER_THREE, new Modification(ModificationType.ADD, "sshPublicKey", keyRsaThree));
		getDS().modify(DN_USER_THREE, new Modification(ModificationType.ADD, "publicsshkey", keyDsaThree));


		LdapKeyManager kmgr = new LdapKeyManager(settings);

		List<SshKey> keys = kmgr.getKeys("UserOne");
		assertNotNull(keys);
		assertEquals(1, keys.size());
		assertEquals(keyRsaOne, keys.get(0).getRawData());

		keys = kmgr.getKeys("UserTwo");
		assertNotNull(keys);
		assertEquals(0, keys.size());

		keys = kmgr.getKeys("UserThree");
		assertNotNull(keys);
		assertEquals(1, keys.size());
		assertEquals(keyRsaThree, keys.get(0).getRawData());

		keys = kmgr.getKeys("UserFour");
		assertNotNull(keys);
		assertEquals(0, keys.size());


		settings.put(Keys.realm.ldap.sshPublicKey, "publicsshkey");

		keys = kmgr.getKeys("UserOne");
		assertNotNull(keys);
		assertEquals(0, keys.size());

		keys = kmgr.getKeys("UserTwo");
		assertNotNull(keys);
		assertEquals(1, keys.size());
		assertEquals(keyDsaTwo, keys.get(0).getRawData());

		keys = kmgr.getKeys("UserThree");
		assertNotNull(keys);
		assertEquals(1, keys.size());
		assertEquals(keyDsaThree, keys.get(0).getRawData());

		keys = kmgr.getKeys("UserFour");
		assertNotNull(keys);
		assertEquals(0, keys.size());
	}


	@Test
	public void testGetKeysPrefixed() throws LDAPException {
		// This test is independent from authentication mode, so run only once.
		assumeTrue(authMode == AuthMode.ANONYMOUS);

		String keyRsaOne = getRsaPubKey("UserOne@example.com");
		getDS().modify(DN_USER_ONE, new Modification(ModificationType.ADD, "sshPublicKey", keyRsaOne));

		String keyRsaTwo = getRsaPubKey("UserTwo@example.com");
		String keyDsaTwo = getDsaPubKey("UserTwo@example.com");
		getDS().modify(DN_USER_TWO, new Modification(ModificationType.ADD, "altSecurityIdentities", keyRsaTwo));
		getDS().modify(DN_USER_TWO, new Modification(ModificationType.ADD, "altSecurityIdentities", "SSHKey: " + keyDsaTwo));

		String keyRsaThree = getRsaPubKey("UserThree@example.com");
		String keyDsaThree = getDsaPubKey("UserThree@example.com");
		String keyEcThree =  getEcPubKey("UserThree@example.com");
		getDS().modify(DN_USER_THREE, new Modification(ModificationType.ADD, "altSecurityIdentities", " SshKey :\r\n" + keyRsaThree));
		getDS().modify(DN_USER_THREE, new Modification(ModificationType.ADD, "altSecurityIdentities", "	sshkey: " + keyDsaThree));
		getDS().modify(DN_USER_THREE, new Modification(ModificationType.ADD, "altSecurityIdentities", "ECDSAKey	:\n " + keyEcThree));


		LdapKeyManager kmgr = new LdapKeyManager(settings);

		settings.put(Keys.realm.ldap.sshPublicKey, "altSecurityIdentities");

		List<SshKey> keys = kmgr.getKeys("UserOne");
		assertNotNull(keys);
		assertEquals(0, keys.size());

		keys = kmgr.getKeys("UserTwo");
		assertNotNull(keys);
		assertEquals(1, keys.size());
		assertEquals(keyRsaTwo, keys.get(0).getRawData());

		keys = kmgr.getKeys("UserThree");
		assertNotNull(keys);
		assertEquals(0, keys.size());

		keys = kmgr.getKeys("UserFour");
		assertNotNull(keys);
		assertEquals(0, keys.size());



		settings.put(Keys.realm.ldap.sshPublicKey, "altSecurityIdentities:SSHKey");

		keys = kmgr.getKeys("UserOne");
		assertNotNull(keys);
		assertEquals(0, keys.size());

		keys = kmgr.getKeys("UserTwo");
		assertNotNull(keys);
		assertEquals(1, keys.size());
		assertEquals(keyDsaTwo, keys.get(0).getRawData());

		keys = kmgr.getKeys("UserThree");
		assertNotNull(keys);
		assertEquals(2, keys.size());
		assertEquals(keyRsaThree, keys.get(0).getRawData());
		assertEquals(keyDsaThree, keys.get(1).getRawData());

		keys = kmgr.getKeys("UserFour");
		assertNotNull(keys);
		assertEquals(0, keys.size());



		settings.put(Keys.realm.ldap.sshPublicKey, "altSecurityIdentities:ECDSAKey");

		keys = kmgr.getKeys("UserOne");
		assertNotNull(keys);
		assertEquals(0, keys.size());

		keys = kmgr.getKeys("UserTwo");
		assertNotNull(keys);
		assertEquals(0, keys.size());

		keys = kmgr.getKeys("UserThree");
		assertNotNull(keys);
		assertEquals(1, keys.size());
		assertEquals(keyEcThree, keys.get(0).getRawData());

		keys = kmgr.getKeys("UserFour");
		assertNotNull(keys);
		assertEquals(0, keys.size());
	}


	@Test
	public void testGetKeysPermissions() throws LDAPException {
		// This test is independent from authentication mode, so run only once.
		assumeTrue(authMode == AuthMode.ANONYMOUS);

		String keyRsaOne = getRsaPubKey("UserOne@example.com");
		String keyRsaTwo = getRsaPubKey("");
		String keyDsaTwo = getDsaPubKey("UserTwo at example.com");
		String keyRsaThree = getRsaPubKey("UserThree@example.com");
		String keyDsaThree = getDsaPubKey("READ key for user 'Three' @example.com");
		String keyEcThree =  getEcPubKey("UserThree@example.com");

		getDS().modify(DN_USER_ONE, new Modification(ModificationType.ADD, "sshPublicKey", keyRsaOne));
		getDS().modify(DN_USER_ONE, new Modification(ModificationType.ADD, "sshPublicKey", "  	 " + keyRsaTwo));
		getDS().modify(DN_USER_ONE, new Modification(ModificationType.ADD, "sshPublicKey", "no-agent-forwarding " + keyDsaTwo));
		getDS().modify(DN_USER_ONE, new Modification(ModificationType.ADD, "sshPublicKey", " command=\"sh /etc/netstart tun0 \" " + keyRsaThree));
		getDS().modify(DN_USER_ONE, new Modification(ModificationType.ADD, "sshPublicKey", " command=\"netstat -nult\",environment=\"gb=\\\"What now\\\"\" " + keyDsaThree));
		getDS().modify(DN_USER_ONE, new Modification(ModificationType.ADD, "sshPublicKey", "environment=\"SSH=git\",command=\"netstat -nult\",environment=\"gbPerms=VIEW\" " + keyEcThree));

		getDS().modify(DN_USER_TWO, new Modification(ModificationType.ADD, "sshPublicKey", "environment=\"gbPerm=R\" " + keyRsaOne));
		getDS().modify(DN_USER_TWO, new Modification(ModificationType.ADD, "sshPublicKey", " restrict,environment=\"gbperm=V\" 	 " + keyRsaTwo));
		getDS().modify(DN_USER_TWO, new Modification(ModificationType.ADD, "sshPublicKey", "restrict,environment=\"GBPerm=RW\",pty " + keyDsaTwo));
		getDS().modify(DN_USER_TWO, new Modification(ModificationType.ADD, "sshPublicKey", " environment=\"gbPerm=CLONE\",environment=\"X=\\\" Y \\\"\" " + keyRsaThree));
		getDS().modify(DN_USER_TWO, new Modification(ModificationType.ADD, "sshPublicKey", " environment=\"A = B \",from=\"*.example.com,!pc.example.com\",environment=\"gbPerm=VIEW\" " + keyDsaThree));
		getDS().modify(DN_USER_TWO, new Modification(ModificationType.ADD, "sshPublicKey", "environment=\"SSH=git\",environment=\"gbPerm=PUSH\",environment=\"XYZ='Ali Baba'\" " + keyEcThree));

		getDS().modify(DN_USER_THREE, new Modification(ModificationType.ADD, "sshPublicKey", "environment=\"gbPerm=R\",environment=\"josh=\\\"mean\\\"\",tunnel=\"0\" " + keyRsaOne));
		getDS().modify(DN_USER_THREE, new Modification(ModificationType.ADD, "sshPublicKey", " environment=\" gbPerm = V \" 	 " + keyRsaTwo));
		getDS().modify(DN_USER_THREE, new Modification(ModificationType.ADD, "sshPublicKey", "command=\"sh echo \\\"Nope, not you!\\\" \",user-rc,environment=\"gbPerm=RW\" " + keyDsaTwo));
		getDS().modify(DN_USER_THREE, new Modification(ModificationType.ADD, "sshPublicKey", "environment=\"gbPerm=VIEW\",command=\"sh /etc/netstart tun0 \",environment=\"gbPerm=CLONE\",no-pty " + keyRsaThree));
		getDS().modify(DN_USER_THREE, new Modification(ModificationType.ADD, "sshPublicKey", "	command=\"netstat -nult\",environment=\"gbPerm=VIEW\" " + keyDsaThree));
		getDS().modify(DN_USER_THREE, new Modification(ModificationType.ADD, "sshPublicKey", "environment=\"SSH=git\",command=\"netstat -nult\",environment=\"gbPerm=PUSH\" " + keyEcThree));


		LdapKeyManager kmgr = new LdapKeyManager(settings);

		List<SshKey> keys = kmgr.getKeys("UserOne");
		assertNotNull(keys);
		assertEquals(6, keys.size());
		for (SshKey key : keys) {
			assertEquals(AccessPermission.PUSH, key.getPermission());
		}

		keys = kmgr.getKeys("UserTwo");
		assertNotNull(keys);
		assertEquals(6, keys.size());
		int seen = 0;
		for (SshKey key : keys) {
			if (keyRsaOne.equals(key.getRawData())) {
				assertEquals(AccessPermission.CLONE, key.getPermission());
				seen += 1 << 0;
			}
			else if (keyRsaTwo.equals(key.getRawData())) {
				assertEquals(AccessPermission.VIEW, key.getPermission());
				seen += 1 << 1;
			}
			else if (keyDsaTwo.equals(key.getRawData())) {
				assertEquals(AccessPermission.PUSH, key.getPermission());
				seen += 1 << 2;
			}
			else if (keyRsaThree.equals(key.getRawData())) {
				assertEquals(AccessPermission.CLONE, key.getPermission());
				seen += 1 << 3;
			}
			else if (keyDsaThree.equals(key.getRawData())) {
				assertEquals(AccessPermission.VIEW, key.getPermission());
				seen += 1 << 4;
			}
			else if (keyEcThree.equals(key.getRawData())) {
				assertEquals(AccessPermission.PUSH, key.getPermission());
				seen += 1 << 5;
			}
		}
		assertEquals(63, seen);

		keys = kmgr.getKeys("UserThree");
		assertNotNull(keys);
		assertEquals(6, keys.size());
		seen = 0;
		for (SshKey key : keys) {
			if (keyRsaOne.equals(key.getRawData())) {
				assertEquals(AccessPermission.CLONE, key.getPermission());
				seen += 1 << 0;
			}
			else if (keyRsaTwo.equals(key.getRawData())) {
				assertEquals(AccessPermission.VIEW, key.getPermission());
				seen += 1 << 1;
			}
			else if (keyDsaTwo.equals(key.getRawData())) {
				assertEquals(AccessPermission.PUSH, key.getPermission());
				seen += 1 << 2;
			}
			else if (keyRsaThree.equals(key.getRawData())) {
				assertEquals(AccessPermission.CLONE, key.getPermission());
				seen += 1 << 3;
			}
			else if (keyDsaThree.equals(key.getRawData())) {
				assertEquals(AccessPermission.VIEW, key.getPermission());
				seen += 1 << 4;
			}
			else if (keyEcThree.equals(key.getRawData())) {
				assertEquals(AccessPermission.PUSH, key.getPermission());
				seen += 1 << 5;
			}
		}
		assertEquals(63, seen);
	}


	@Test
	public void testGetKeysPrefixedPermissions() throws LDAPException {
		// This test is independent from authentication mode, so run only once.
		assumeTrue(authMode == AuthMode.ANONYMOUS);

		String keyRsaOne = getRsaPubKey("UserOne@example.com");
		String keyRsaTwo = getRsaPubKey("UserTwo at example.com");
		String keyDsaTwo = getDsaPubKey("UserTwo@example.com");
		String keyRsaThree = getRsaPubKey("example.com: user Three");
		String keyDsaThree = getDsaPubKey("");
		String keyEcThree =  getEcPubKey("  ");

		getDS().modify(DN_USER_ONE, new Modification(ModificationType.ADD, "altSecurityIdentities",          "permitopen=\"host:220\"" + keyRsaOne));
		getDS().modify(DN_USER_ONE, new Modification(ModificationType.ADD, "altSecurityIdentities", "sshkey:" + "  	 " + keyRsaTwo));
		getDS().modify(DN_USER_ONE, new Modification(ModificationType.ADD, "altSecurityIdentities", "SSHKEY :" + "no-agent-forwarding " + keyDsaTwo));
		getDS().modify(DN_USER_ONE, new Modification(ModificationType.ADD, "altSecurityIdentities", "pubkey: " + " command=\"sh /etc/netstart tun0 \" " + keyRsaThree));
		getDS().modify(DN_USER_ONE, new Modification(ModificationType.ADD, "altSecurityIdentities", "pubkey: " + " command=\"netstat -nult\",environment=\"gb=\\\"What now\\\"\" " + keyDsaThree));
		getDS().modify(DN_USER_ONE, new Modification(ModificationType.ADD, "altSecurityIdentities", "pubkey: " + "environment=\"SSH=git\",command=\"netstat -nult\",environment=\"gbPerms=VIEW\" " + keyEcThree));

		getDS().modify(DN_USER_TWO, new Modification(ModificationType.ADD, "altSecurityIdentities", "SSHkey: " + "environment=\"gbPerm=R\" " + keyRsaOne));
		getDS().modify(DN_USER_TWO, new Modification(ModificationType.ADD, "altSecurityIdentities", "SSHKey : " + " restrict,environment=\"gbPerm=V\",permitopen=\"sshkey: 220\" " + keyRsaTwo));
		getDS().modify(DN_USER_TWO, new Modification(ModificationType.ADD, "altSecurityIdentities", "SSHkey: " + "permitopen=\"sshkey: 443\",restrict,environment=\"gbPerm=RW\",pty " + keyDsaTwo));
		getDS().modify(DN_USER_TWO, new Modification(ModificationType.ADD, "altSecurityIdentities", "pubkey: " + "environment=\"gbPerm=CLONE\",permitopen=\"pubkey: 29184\",environment=\"X=\\\" Y \\\"\" " + keyRsaThree));
		getDS().modify(DN_USER_TWO, new Modification(ModificationType.ADD, "altSecurityIdentities", "pubkey: " + " environment=\"A = B \",from=\"*.example.com,!pc.example.com\",environment=\"gbPerm=VIEW\" " + keyDsaThree));
		getDS().modify(DN_USER_TWO, new Modification(ModificationType.ADD, "altSecurityIdentities", "pubkey: " + "environment=\"SSH=git\",environment=\"gbPerm=PUSH\",environemnt=\"XYZ='Ali Baba'\" " + keyEcThree));

		getDS().modify(DN_USER_THREE, new Modification(ModificationType.ADD, "altSecurityIdentities", "SSHkey: " + "environment=\"gbPerm=R\",environment=\"josh=\\\"mean\\\"\",tunnel=\"0\" " + keyRsaOne));
		getDS().modify(DN_USER_THREE, new Modification(ModificationType.ADD, "altSecurityIdentities", "SSHkey : " + " environment=\" gbPerm = V \" 	 " + keyRsaTwo));
		getDS().modify(DN_USER_THREE, new Modification(ModificationType.ADD, "altSecurityIdentities", "SSHkey: " + "command=\"sh echo \\\"Nope, not you! \\b (bell)\\\" \",user-rc,environment=\"gbPerm=RW\" " + keyDsaTwo));
		getDS().modify(DN_USER_THREE, new Modification(ModificationType.ADD, "altSecurityIdentities", "pubkey: " + "environment=\"gbPerm=VIEW\",command=\"sh /etc/netstart tun0 \",environment=\"gbPerm=CLONE\",no-pty " + keyRsaThree));
		getDS().modify(DN_USER_THREE, new Modification(ModificationType.ADD, "altSecurityIdentities", "pubkey: " + "	command=\"netstat -nult\",environment=\"gbPerm=VIEW\" " + keyDsaThree));
		getDS().modify(DN_USER_THREE, new Modification(ModificationType.ADD, "altSecurityIdentities", "pubkey: " + "environment=\"SSH=git\",command=\"netstat -nult\",environment=\"gbPerm=PUSH\" " + keyEcThree));

		// Weird stuff, not to specification but shouldn't make it stumble.
		getDS().modify(DN_USER_THREE, new Modification(ModificationType.ADD, "altSecurityIdentities", "opttest: " + "permitopen=host:443,command=,environment=\"gbPerm=CLONE\",no-pty= " + keyRsaThree));
		getDS().modify(DN_USER_THREE, new Modification(ModificationType.ADD, "altSecurityIdentities", " opttest: " + "	cmd=git,environment=\"gbPerm=\\\"VIEW\\\"\" " + keyDsaThree));
		getDS().modify(DN_USER_THREE, new Modification(ModificationType.ADD, "altSecurityIdentities", "	opttest:" + "environment=,command=netstat,environment=gbperm=push " + keyEcThree));


		LdapKeyManager kmgr = new LdapKeyManager(settings);

		settings.put(Keys.realm.ldap.sshPublicKey, "altSecurityIdentities:SSHkey");

		List<SshKey> keys = kmgr.getKeys("UserOne");
		assertNotNull(keys);
		assertEquals(2, keys.size());
		int seen = 0;
		for (SshKey key : keys) {
			assertEquals(AccessPermission.PUSH, key.getPermission());
			if (keyRsaOne.equals(key.getRawData())) {
				seen += 1 << 0;
			}
			else if (keyRsaTwo.equals(key.getRawData())) {
				seen += 1 << 1;
			}
			else if (keyDsaTwo.equals(key.getRawData())) {
				seen += 1 << 2;
			}
			else if (keyRsaThree.equals(key.getRawData())) {
				seen += 1 << 3;
			}
			else if (keyDsaThree.equals(key.getRawData())) {
				seen += 1 << 4;
			}
			else if (keyEcThree.equals(key.getRawData())) {
				seen += 1 << 5;
			}
		}
		assertEquals(6, seen);

		keys = kmgr.getKeys("UserTwo");
		assertNotNull(keys);
		assertEquals(3, keys.size());
		seen = 0;
		for (SshKey key : keys) {
			if (keyRsaOne.equals(key.getRawData())) {
				assertEquals(AccessPermission.CLONE, key.getPermission());
				seen += 1 << 0;
			}
			else if (keyRsaTwo.equals(key.getRawData())) {
				assertEquals(AccessPermission.VIEW, key.getPermission());
				seen += 1 << 1;
			}
			else if (keyDsaTwo.equals(key.getRawData())) {
				assertEquals(AccessPermission.PUSH, key.getPermission());
				seen += 1 << 2;
			}
			else if (keyRsaThree.equals(key.getRawData())) {
				assertEquals(AccessPermission.CLONE, key.getPermission());
				seen += 1 << 3;
			}
			else if (keyDsaThree.equals(key.getRawData())) {
				assertEquals(AccessPermission.VIEW, key.getPermission());
				seen += 1 << 4;
			}
			else if (keyEcThree.equals(key.getRawData())) {
				assertEquals(AccessPermission.PUSH, key.getPermission());
				seen += 1 << 5;
			}
		}
		assertEquals(7, seen);

		keys = kmgr.getKeys("UserThree");
		assertNotNull(keys);
		assertEquals(3, keys.size());
		seen = 0;
		for (SshKey key : keys) {
			if (keyRsaOne.equals(key.getRawData())) {
				assertEquals(AccessPermission.CLONE, key.getPermission());
				seen += 1 << 0;
			}
			else if (keyRsaTwo.equals(key.getRawData())) {
				assertEquals(AccessPermission.VIEW, key.getPermission());
				seen += 1 << 1;
			}
			else if (keyDsaTwo.equals(key.getRawData())) {
				assertEquals(AccessPermission.PUSH, key.getPermission());
				seen += 1 << 2;
			}
			else if (keyRsaThree.equals(key.getRawData())) {
				assertEquals(AccessPermission.CLONE, key.getPermission());
				seen += 1 << 3;
			}
			else if (keyDsaThree.equals(key.getRawData())) {
				assertEquals(AccessPermission.VIEW, key.getPermission());
				seen += 1 << 4;
			}
			else if (keyEcThree.equals(key.getRawData())) {
				assertEquals(AccessPermission.PUSH, key.getPermission());
				seen += 1 << 5;
			}
		}
		assertEquals(7, seen);



		settings.put(Keys.realm.ldap.sshPublicKey, "altSecurityIdentities:pubKey");

		keys = kmgr.getKeys("UserOne");
		assertNotNull(keys);
		assertEquals(3, keys.size());
		seen = 0;
		for (SshKey key : keys) {
			assertEquals(AccessPermission.PUSH, key.getPermission());
			if (keyRsaOne.equals(key.getRawData())) {
				seen += 1 << 0;
			}
			else if (keyRsaTwo.equals(key.getRawData())) {
				seen += 1 << 1;
			}
			else if (keyDsaTwo.equals(key.getRawData())) {
				seen += 1 << 2;
			}
			else if (keyRsaThree.equals(key.getRawData())) {
				seen += 1 << 3;
			}
			else if (keyDsaThree.equals(key.getRawData())) {
				seen += 1 << 4;
			}
			else if (keyEcThree.equals(key.getRawData())) {
				seen += 1 << 5;
			}
		}
		assertEquals(56, seen);

		keys = kmgr.getKeys("UserTwo");
		assertNotNull(keys);
		assertEquals(3, keys.size());
		seen = 0;
		for (SshKey key : keys) {
			if (keyRsaOne.equals(key.getRawData())) {
				assertEquals(AccessPermission.CLONE, key.getPermission());
				seen += 1 << 0;
			}
			else if (keyRsaTwo.equals(key.getRawData())) {
				assertEquals(AccessPermission.VIEW, key.getPermission());
				seen += 1 << 1;
			}
			else if (keyDsaTwo.equals(key.getRawData())) {
				assertEquals(AccessPermission.PUSH, key.getPermission());
				seen += 1 << 2;
			}
			else if (keyRsaThree.equals(key.getRawData())) {
				assertEquals(AccessPermission.CLONE, key.getPermission());
				seen += 1 << 3;
			}
			else if (keyDsaThree.equals(key.getRawData())) {
				assertEquals(AccessPermission.VIEW, key.getPermission());
				seen += 1 << 4;
			}
			else if (keyEcThree.equals(key.getRawData())) {
				assertEquals(AccessPermission.PUSH, key.getPermission());
				seen += 1 << 5;
			}
		}
		assertEquals(56, seen);

		keys = kmgr.getKeys("UserThree");
		assertNotNull(keys);
		assertEquals(3, keys.size());
		seen = 0;
		for (SshKey key : keys) {
			if (keyRsaOne.equals(key.getRawData())) {
				assertEquals(AccessPermission.CLONE, key.getPermission());
				seen += 1 << 0;
			}
			else if (keyRsaTwo.equals(key.getRawData())) {
				assertEquals(AccessPermission.VIEW, key.getPermission());
				seen += 1 << 1;
			}
			else if (keyDsaTwo.equals(key.getRawData())) {
				assertEquals(AccessPermission.PUSH, key.getPermission());
				seen += 1 << 2;
			}
			else if (keyRsaThree.equals(key.getRawData())) {
				assertEquals(AccessPermission.CLONE, key.getPermission());
				seen += 1 << 3;
			}
			else if (keyDsaThree.equals(key.getRawData())) {
				assertEquals(AccessPermission.VIEW, key.getPermission());
				seen += 1 << 4;
			}
			else if (keyEcThree.equals(key.getRawData())) {
				assertEquals(AccessPermission.PUSH, key.getPermission());
				seen += 1 << 5;
			}
		}
		assertEquals(56, seen);


		settings.put(Keys.realm.ldap.sshPublicKey, "altSecurityIdentities:opttest");
		keys = kmgr.getKeys("UserThree");
		assertNotNull(keys);
		assertEquals(3, keys.size());
		seen = 0;
		for (SshKey key : keys) {
			if (keyRsaOne.equals(key.getRawData())) {
				assertEquals(AccessPermission.CLONE, key.getPermission());
				seen += 1 << 0;
			}
			else if (keyRsaTwo.equals(key.getRawData())) {
				assertEquals(AccessPermission.VIEW, key.getPermission());
				seen += 1 << 1;
			}
			else if (keyDsaTwo.equals(key.getRawData())) {
				assertEquals(AccessPermission.PUSH, key.getPermission());
				seen += 1 << 2;
			}
			else if (keyRsaThree.equals(key.getRawData())) {
				assertEquals(AccessPermission.CLONE, key.getPermission());
				seen += 1 << 3;
			}
			else if (keyDsaThree.equals(key.getRawData())) {
				assertEquals(AccessPermission.VIEW, key.getPermission());
				seen += 1 << 4;
			}
			else if (keyEcThree.equals(key.getRawData())) {
				assertEquals(AccessPermission.PUSH, key.getPermission());
				seen += 1 << 5;
			}
		}
		assertEquals(56, seen);

	}


	@Test
	public void testKeyValidity() throws LDAPException, GeneralSecurityException {
		LdapKeyManager kmgr = new LdapKeyManager(settings);

		String comment = "UserTwo@example.com";
		String keyDsaTwo = getDsaPubKey(comment);
		getDS().modify(DN_USER_TWO, new Modification(ModificationType.ADD, "sshPublicKey", keyDsaTwo));


		List<SshKey> keys = kmgr.getKeys("UserTwo");
		assertNotNull(keys);
		assertEquals(1, keys.size());
		SshKey sshKey = keys.get(0);
		assertEquals(keyDsaTwo, sshKey.getRawData());

		Signature signature = SecurityUtils.getSignature("DSA");
		signature.initSign(getDsaKeyPair(comment).getPrivate());
		byte[] message = comment.getBytes();
		signature.update(message);
		byte[] sigBytes = signature.sign();

		signature.initVerify(sshKey.getPublicKey());
		signature.update(message);
		assertTrue("Verify failed with retrieved SSH key.", signature.verify(sigBytes));
	}








	private KeyPair getDsaKeyPair(String comment) {
		return getKeyPair("DSA", comment, dsaGenerator);
	}

	private KeyPair getKeyPair(String type, String comment, KeyPairGenerator generator) {
		String kpkey = type + ":" + comment;
		KeyPair kp = keyPairs.get(kpkey);
		if (kp == null) {
			if ("EC".equals(type)) {
				ECGenParameterSpec ecSpec = new ECGenParameterSpec("P-384");
				try {
					ecGenerator.initialize(ecSpec);
				} catch (InvalidAlgorithmParameterException e) {
					kp = generator.generateKeyPair();
					e.printStackTrace();
				}
				kp = ecGenerator.generateKeyPair();
			} else {
				kp = generator.generateKeyPair();
			}
			keyPairs.put(kpkey, kp);
		}

		return kp;
	}


	private String getRsaPubKey(String comment) {
		return getPubKey("RSA", comment, rsaGenerator);
	}

	private String getDsaPubKey(String comment) {
		return getPubKey("DSA", comment, dsaGenerator);
	}

	private String getEcPubKey(String comment) {
		return getPubKey("EC", comment, ecGenerator);
	}

	private String getPubKey(String type, String comment, KeyPairGenerator generator) {
		KeyPair kp = getKeyPair(type, comment, generator);
		if (kp == null) {
			return null;
		}

		SshKey sk = new SshKey(kp.getPublic());
		sk.setComment(comment);
		return sk.getRawData();
	}

}
