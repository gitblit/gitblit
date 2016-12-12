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
package com.gitblit.auth;

import java.util.Set;
import java.util.TreeSet;

import com.gitblit.utils.WindowsLogonInfo;
import waffle.windows.auth.IWindowsAccount;
import waffle.windows.auth.IWindowsAuthProvider;
import waffle.windows.auth.IWindowsComputer;
import waffle.windows.auth.IWindowsIdentity;
import waffle.windows.auth.impl.WindowsAuthProviderImpl;

import com.gitblit.Constants;
import com.gitblit.Constants.AccountType;
import com.gitblit.Constants.Role;
import com.gitblit.Keys;
import com.gitblit.auth.AuthenticationProvider.UsernamePasswordAuthenticationProvider;
import com.gitblit.models.TeamModel;
import com.gitblit.models.UserModel;
import com.sun.jna.platform.win32.Win32Exception;

/**
 * Implementation of a Windows authentication provider.
 *
 * @author James Moger
 */
public class WindowsAuthProvider extends UsernamePasswordAuthenticationProvider {

    private IWindowsAuthProvider waffle;

    public WindowsAuthProvider() {
        super("windows");
    }

    @Override
    public void setup() {

        waffle = new WindowsAuthProviderImpl();
        IWindowsComputer computer = waffle.getCurrentComputer();
        logger.info("Windows Authentication Provider");
        logger.info("      name = " + computer.getComputerName());
        logger.info("    status = " + describeJoinStatus(computer.getJoinStatus()));
        logger.info("  memberOf = " + computer.getMemberOf());
        //logger.info("  groups     = " + Arrays.asList(computer.getGroups()));
    }

    protected String describeJoinStatus(String value) {
        if ("NetSetupUnknownStatus".equals(value)) {
            return "unknown";
        }
        else if ("NetSetupUnjoined".equals(value)) {
            return "not joined";
        }
        else if ("NetSetupWorkgroupName".equals(value)) {
            return "joined to a workgroup";
        }
        else if ("NetSetupDomainName".equals(value)) {
            return "joined to a domain";
        }
        return value;
    }

    @Override
    public boolean supportsCredentialChanges() {
        return false;
    }

    @Override
    public boolean supportsDisplayNameChanges() {
        return false;
    }

    @Override
    public boolean supportsEmailAddressChanges() {
        return true;
    }

    @Override
    public boolean supportsTeamMembershipChanges() {
        return true;
    }

    @Override
    public boolean supportsRoleChanges(UserModel user, Role role) {
        return true;
    }

    @Override
    public boolean supportsRoleChanges(TeamModel team, Role role) {
        return true;
    }

    @Override
    public AccountType getAccountType() {
        return AccountType.WINDOWS;
    }

    @Override
    public UserModel authenticate(String rawLogin, char[] password) {
        String defaultDomain = settings.getString(Keys.realm.windows.defaultDomain, null);
        boolean isMultiDomainAllowed = settings.getBoolean(Keys.realm.windows.allowMultipleDomainAuthentication, false);

        WindowsLogonInfo loginInfo = WindowsLogonInfo.Parse(rawLogin, defaultDomain);
        if (!loginInfo.isValid()) {
            logger.warn("Failed to parse provided Windows logon info.");
            return null;
        }
        if (!isMultiDomainAllowed && !loginInfo.getNetBIOSDomain().equalsIgnoreCase(WindowsLogonInfo.trimDNSsuffixFromDomainName(defaultDomain))){
            logger.warn("Login with a domain different than the default is not permitted.");
            return null;
        }

        IWindowsIdentity identity = null;
        try {
            try {
                identity = waffle.logonDomainUser(loginInfo.getUser(), loginInfo.getNetBIOSDomain(), new String(password));
            } catch (Win32Exception e) {
                logger.error(e.getMessage());
                return null;
            }

            if (identity.isGuest() && !settings.getBoolean(Keys.realm.windows.allowGuests, false)) {
                logger.warn("Guest account access is disabled");
                return null;
            }
            // If authentication from multiple domains is permitted, then use DOMAIN_user as the key for user in user.conf,
            // to avoid ambiguity.
            // If not, it is safe to use the user name only, which makes for nicer looking download urls and private repo folders.
            String gitblitUser = isMultiDomainAllowed ? loginInfo.getNetBIOSDomain() + "_" + loginInfo.getUser() : loginInfo.getUser();
            UserModel user = userManager.getUserModel(gitblitUser);
            if (user == null) {
                // create user object for new authenticated user
                user = new UserModel(gitblitUser);
            }

            // create a user cookie
            setCookie(user, password);

            // update user attributes from Windows identity
            user.accountType = getAccountType();
            String fqn = identity.getFqn();
            if (fqn.indexOf('\\') > -1) {
                user.displayName = fqn.substring(fqn.lastIndexOf('\\') + 1);
            }
            else {
                user.displayName = fqn;
            }
            user.password = Constants.EXTERNAL_ACCOUNT;

            Set<String> groupNames = new TreeSet<String>();
            for (IWindowsAccount group : identity.getGroups()) {
                groupNames.add(group.getFqn());
            }

            if (settings.getBoolean(Keys.realm.windows.permitBuiltInAdministrators, true)) {
                if (groupNames.contains("BUILTIN\\Administrators")) {
                    // local administrator
                    user.canAdmin = true;
                }
            }

            // TODO consider mapping Windows groups to teams

            // push the changes to the backing user service
            updateUser(user);
            return user;
        } finally {
            if (identity != null) {
                // cleanup resources
                identity.dispose();
            }
        }
    }
}
