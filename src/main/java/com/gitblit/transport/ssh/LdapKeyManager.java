/*
 * Copyright 2016 gitblit.com.
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

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.sshd.common.config.keys.AuthorizedKeyEntry;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.util.GenericUtils;

import com.gitblit.IStoredSettings;
import com.gitblit.Keys;
import com.gitblit.Constants.AccessPermission;
import com.gitblit.ldap.LdapConnection;
import com.gitblit.models.UserModel;
import com.gitblit.utils.StringUtils;
import com.google.common.base.Joiner;
import com.google.inject.Inject;
import com.unboundid.ldap.sdk.BindResult;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.ldap.sdk.SearchResult;
import com.unboundid.ldap.sdk.SearchResultEntry;

/**
 * LDAP-only public key manager
 *
 * Retrieves public keys from user's LDAP entries. Using this key manager,
 * no SSH keys can be edited, i.e. added, removed, permissions changed, etc.
 *
 * This key manager supports SSH key entries in LDAP of the following form:
 * [<prefix>:] [<options>] <type> <key> [<comment>]
 * This follows the required form of entries in the authenticated_keys file,
 * with an additional optional prefix. Key entries must have a key type
 * (like "ssh-rsa") and a key, and may have a comment at the end.
 *
 * An entry may specify login options as specified for the authorized_keys file.
 * The 'environment' option may be used to set the permissions for the key
 * by setting a 'gbPerm' environment variable. The key manager will interpret
 * such a environment variable option and use the set permission string to set
 * the permission on the key in Gitblit. Example:
 *   environment="gbPerm=V",pty ssh-rsa AAAxjka.....dv= Clone only key
 * Above entry would create a RSA key with the comment "Clone only key" and
 * set the key permission to CLONE. All other options are ignored.
 *
 * In Active Directory SSH public keys are sometimes stored in the attribute
 * 'altSecurityIdentity'. The attribute value is usually prefixed by a type
 * identifier. LDAP entries could have the following attribute values:
 *   altSecurityIdentity: X.509: ADKEJBAKDBZUPABBD...
 *   altSecurityIdentity: SshKey: ssh-dsa AAAAknenazuzucbhda...
 * This key manager supports this by allowing an optional prefix to identify
 * SSH keys. The prefix to be used should be set in the 'realm.ldap.sshPublicKey'
 * setting by separating it from the attribute name with a colon, e.g.:
 *    realm.ldap.sshPublicKey = altSecurityIdentity:SshKey
 *
 * @author Florian Zschocke
 *
 */
public class LdapKeyManager extends IPublicKeyManager {

	/**
	 * Pattern to find prefixes like 'SSHKey:' in key entries.
	 * These prefixes describe the type of an altSecurityIdentity.
	 * The pattern accepts anything but quote and colon up to the
	 * first colon at the start of a string.
	 */
	private static final Pattern PREFIX_PATTERN = Pattern.compile("^([^\":]+):");
	/**
	 * Pattern to find the string describing Gitblit permissions for a SSH key.
	 * The pattern matches on a string starting with 'gbPerm', matched case-insensitive,
	 * followed by '=' with optional whitespace around it, followed by a string of
	 * upper and lower case letters and '+' and '-' for the permission, which can optionally
	 * be enclosed in '"' or '\"' (only the leading quote is matched in the pattern).
	 * Only the group describing the permission is a capturing group.
	 */
	private static final Pattern GB_PERM_PATTERN = Pattern.compile("(?i:gbPerm)\\s*=\\s*(?:\\\\\"|\")?\\s*([A-Za-z+-]+)");


	private final IStoredSettings settings;



	@Inject
	public LdapKeyManager(IStoredSettings settings) {
		this.settings = settings;
	}


	@Override
	public String toString() {
		return getClass().getSimpleName();
	}

	@Override
	public LdapKeyManager start() {
		log.info(toString());
		return this;
	}

	@Override
	public boolean isReady() {
		return true;
	}

	@Override
	public LdapKeyManager stop() {
		return this;
	}

	@Override
	protected boolean isStale(String username) {
		// always return true so we gets keys from LDAP every time
		return true;
	}

	@Override
	protected List<SshKey> getKeysImpl(String username) {
		try (LdapConnection conn = new LdapConnection(settings)) {
			if (conn.connect()) {
				log.info("loading ssh key for {} from LDAP directory", username);

				BindResult bindResult = conn.bind();
				if (bindResult == null) {
					conn.close();
					return null;
				}

				// Search the user entity

				// Support prefixing the key data, e.g. when using altSecurityIdentities in AD.
				String pubKeyAttribute = settings.getString(Keys.realm.ldap.sshPublicKey, "sshPublicKey");
				String pkaPrefix = null;
				int idx = pubKeyAttribute.indexOf(':');
				if (idx > 0) {
					pkaPrefix = pubKeyAttribute.substring(idx +1);
					pubKeyAttribute = pubKeyAttribute.substring(0, idx);
				}

				SearchResult result = conn.searchUser(getSimpleUsername(username), Arrays.asList(pubKeyAttribute));
				conn.close();

				if (result != null && result.getResultCode() == ResultCode.SUCCESS) {
					if ( result.getEntryCount() > 1) {
						log.info("Found more than one entry for user {} in LDAP. Cannot retrieve SSH key.", username);
						return null;
					} else if ( result.getEntryCount() < 1) {
						log.info("Found no entry for user {} in LDAP. Cannot retrieve SSH key.", username);
						return null;
					}

					// Retrieve the SSH key attributes
					SearchResultEntry foundUser = result.getSearchEntries().get(0);
					String[] attrs = foundUser.getAttributeValues(pubKeyAttribute);
					if (attrs == null ||attrs.length == 0) {
						log.info("found no keys for user {} under attribute {} in directory", username, pubKeyAttribute);
						return null;
					}


					// Filter resulting list to match with required special prefix in entry
					List<GbAuthorizedKeyEntry> authorizedKeys = new ArrayList<>(attrs.length);
					Matcher m = PREFIX_PATTERN.matcher("");
					for (int i = 0; i < attrs.length; ++i) {
						// strip out line breaks
						String keyEntry = Joiner.on("").join(attrs[i].replace("\r\n", "\n").split("\n"));
						m.reset(keyEntry);
						try {
							if (m.lookingAt()) { // Key is prefixed in LDAP
								if (pkaPrefix == null) {
									continue;
								}
								String prefix = m.group(1).trim();
								if (! pkaPrefix.equalsIgnoreCase(prefix)) {
									continue;
								}
								String s = keyEntry.substring(m.end()); // Strip prefix off
								authorizedKeys.add(GbAuthorizedKeyEntry.parseAuthorizedKeyEntry(s));

							} else { // Key is not prefixed in LDAP
								if (pkaPrefix != null) {
									continue;
								}
								String s = keyEntry; // Strip prefix off
								authorizedKeys.add(GbAuthorizedKeyEntry.parseAuthorizedKeyEntry(s));
							}
						} catch (IllegalArgumentException e) {
							log.info("Failed to parse key entry={}:", keyEntry, e.getMessage());
						}
					}

					List<SshKey> keyList = new ArrayList<>(authorizedKeys.size());
					for (GbAuthorizedKeyEntry keyEntry : authorizedKeys) {
						try {
							SshKey key = new SshKey(keyEntry.resolvePublicKey(null));
							key.setComment(keyEntry.getComment());
							setKeyPermissions(key, keyEntry);
							keyList.add(key);
						} catch (GeneralSecurityException | IOException e) {
							log.warn("Error resolving key entry for user {}. Entry={}", username, keyEntry, e);
						}
					}
					return keyList;
				}
			}
		}

		return null;
	}


	@Override
	public boolean addKey(String username, SshKey key) {
		return false;
	}

	@Override
	public boolean removeKey(String username, SshKey key) {
		return false;
	}

	@Override
	public boolean removeAllKeys(String username) {
		return false;
	}


	public boolean supportsWritingKeys(UserModel user) {
		return false;
	}

	public boolean supportsCommentChanges(UserModel user) {
		return false;
	}

	public boolean supportsPermissionChanges(UserModel user) {
		return false;
	}


	private void setKeyPermissions(SshKey key, GbAuthorizedKeyEntry keyEntry) {
		List<String> env = keyEntry.getLoginOptionValues("environment");
		if (env != null && !env.isEmpty()) {
			// Walk over all entries and find one that sets 'gbPerm'. The last one wins.
			for (String envi : env) {
				Matcher m = GB_PERM_PATTERN.matcher(envi);
				if (m.find()) {
					String perm = m.group(1).trim();
					AccessPermission ap = AccessPermission.fromCode(perm);
					if (ap == AccessPermission.NONE) {
						ap = AccessPermission.valueOf(perm.toUpperCase());
					}

					if (ap != null && ap != AccessPermission.NONE) {
						try {
							key.setPermission(ap);
						} catch (IllegalArgumentException e) {
							log.warn("Incorrect permissions ({}) set for SSH key entry {}.", ap, envi, e);
						}
					}
				}
			}
		}
	}


	/**
	 * Returns a simple username without any domain prefixes.
	 *
	 * @param username
	 * @return a simple username
	 */
	private String getSimpleUsername(String username) {
		int lastSlash = username.lastIndexOf('\\');
		if (lastSlash > -1) {
			username = username.substring(lastSlash + 1);
		}

		return username;
	}


	/**
	 * Extension of the AuthorizedKeyEntry from Mina SSHD with better option parsing.
	 *
	 * The class makes use of code from the two methods copied from the original
	 * Mina SSHD AuthorizedKeyEntry class. The code is rewritten to improve user login
	 * option support. Options are correctly parsed even if they have whitespace within
	 * double quotes. Options can occur multiple times, which is needed for example for
	 * the "environment" option. Thus for an option a list of strings is kept, holding
	 * multiple option values.
	 */
	private static class GbAuthorizedKeyEntry extends AuthorizedKeyEntry {

		private static final long serialVersionUID = 1L;
		/**
		 * Pattern to extract the first part of the key entry without whitespace or only with quoted whitespace.
		 * The pattern essentially splits the line in two parts with two capturing groups. All other groups
		 * in the pattern are non-capturing. The first part is a continuous string that only includes double quoted
		 * whitespace and ends in whitespace. The second part is the rest of the line.
		 * The first part is at the beginning of the line, the lead-in. For a SSH key entry this can either be
		 * login options (see authorized keys file description) or the key type. Since options, other than the
		 * key type, can include whitespace and escaped double quotes within double quotes, the pattern takes
		 * care of that by searching for either "characters that are not whitespace and not double quotes"
		 * or "a double quote, followed by 'characters that are not a double quote or backslash, or a backslash
		 * and then a double quote, or a backslash', followed by a double quote".
		 */
		private static final Pattern LEADIN_PATTERN = Pattern.compile("^((?:[^\\s\"]*|(?:\"(?:[^\"\\\\]|\\\\\"|\\\\)*\"))*\\s+)(.+)");
		/**
		 * Pattern to split a comma separated list of options.
		 * Since an option could contain commas (as well as escaped double quotes) within double quotes
		 * in the option value, a simple split on comma is not enough. So the pattern searches for multiple
		 * occurrences of:
		 * characters that are not double quotes or a comma, or
		 * a double quote followed by: characters that are not a double quote or backslash, or
		 *                             a backslash and then a double quote, or
		 *                             a backslash,
		 *   followed by a double quote.
		 */
		private static final Pattern OPTION_PATTERN = Pattern.compile("([^\",]+|(?:\"(?:[^\"\\\\]|\\\\\"|\\\\)*\"))+");

		// for options that have no value, "true" is used
		private Map<String, List<String>> loginOptionsMulti = Collections.emptyMap();


		List<String> getLoginOptionValues(String option) {
			return loginOptionsMulti.get(option);
		}



		/**
		 * @param line Original line from an <code>authorized_keys</code> file
		 * @return {@link GbAuthorizedKeyEntry} or {@code null} if the line is
		 * {@code null}/empty or a comment line
		 * @throws IllegalArgumentException If failed to parse/decode the line
		 * @see #COMMENT_CHAR
		 */
		public static GbAuthorizedKeyEntry parseAuthorizedKeyEntry(String line) throws IllegalArgumentException {
			line = GenericUtils.trimToEmpty(line);
			if (StringUtils.isEmpty(line) || (line.charAt(0) == COMMENT_CHAR) /* comment ? */) {
				return null;
			}

			Matcher m = LEADIN_PATTERN.matcher(line);
			if (! m.lookingAt()) {
				throw new IllegalArgumentException("Bad format (no key data delimiter): " + line);
			}

			String keyType = m.group(1).trim();
			final GbAuthorizedKeyEntry entry;
			if (KeyUtils.getPublicKeyEntryDecoder(keyType) == null) {  // assume this is due to the fact that it starts with login options
				entry = parseAuthorizedKeyEntry(m.group(2));
				if (entry == null) {
					throw new IllegalArgumentException("Bad format (no key data after login options): " + line);
				}

				entry.parseAndSetLoginOptions(keyType);
			} else {
				int startPos = line.indexOf(' ');
				if (startPos <= 0) {
					throw new IllegalArgumentException("Bad format (no key data delimiter): " + line);
				}

				int endPos = line.indexOf(' ', startPos + 1);
				if (endPos <= startPos) {
					endPos = line.length();
				}

				String encData = (endPos < (line.length() - 1)) ? line.substring(0, endPos).trim() : line;
				String comment = (endPos < (line.length() - 1)) ? line.substring(endPos + 1).trim() : null;
				entry = parsePublicKeyEntry(new GbAuthorizedKeyEntry(), encData);
				entry.setComment(comment);
			}

			return entry;
		}

		private void parseAndSetLoginOptions(String options) {
			Matcher m = OPTION_PATTERN.matcher(options);
			if (! m.find()) {
				loginOptionsMulti = Collections.emptyMap();
			}
			Map<String, List<String>> optsMap = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

			do {
				String p = m.group();
				p = GenericUtils.trimToEmpty(p);
				if (StringUtils.isEmpty(p)) {
					continue;
				}

				int pos = p.indexOf('=');
				String name = (pos < 0) ? p : GenericUtils.trimToEmpty(p.substring(0, pos));
				CharSequence value = (pos < 0) ? null : GenericUtils.trimToEmpty(p.substring(pos + 1));
				value = GenericUtils.stripQuotes(value);

				// For options without value the value is set to TRUE.
				if (value == null) {
					value = Boolean.TRUE.toString();
				}

				List<String> opts = optsMap.get(name);
				if (opts == null) {
					opts = new ArrayList<String>();
					optsMap.put(name, opts);
				}
				opts.add(value.toString());
			} while(m.find());

			loginOptionsMulti = optsMap;
		}
	}

}
