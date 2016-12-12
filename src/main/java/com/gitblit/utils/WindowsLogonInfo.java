package com.gitblit.utils;

/**
 * A utility class that parses raw user provided logon as a string into a structured set of Windows logon info.
 * <p>Supported input formats are:</p>
 * <ul>
 * <li>Lowest-Level Logon name: domain\\user</li>
 * <li>User Principal Name: user@domain.example.com</li>
 * <li>Username only (provided a default domain name is specified): user</li>
 * </ul>
 * <p>If a domain name cannot be extracted from the raw input, an optionally provided default domain name is used instead.
 *
 * @author Frederic Thevenet
 */
public class WindowsLogonInfo {
    private String user;
    private String domain;
    private String netBIOSDomain;
    private boolean valid;

    /**
     * Gets the parsed user name.
     *
     * @return the parsed user name
     */
    public String getUser() {
        return user;
    }

    /**
     * Gets the parsed domain name
     * <p>The NetBIOS domain name is the legacy format that is trimmed from a DNS suffix.</p>
     *
     * @return the parsed domain name, in lower case.
     */
    public String getNetBIOSDomain() {
        return this.netBIOSDomain;
    }

    /**
     * Gets the parsed domain name as a UPN suffix
     * <p>The User Principal Name suffix is the made out of the domain name plus the DNS suffix (i.e. domain.example.com)/ </p>
     *
     * @return
     */
    public String getUPNSuffix() {
        return this.domain;
    }

    /**
     * Returns true if the input string was successfully parsed into a valid set of login info, false otherwise.
     *
     * @return true if the input string was successfully parsed into a valid set of login info, false otherwise.
     */
    public boolean isValid() {
        return this.valid;
    }

    /**
     * Returns the parsed user logon info in the Down-Level Logon Name format.
     * <p>The down-level logon name format is used to specify a domain and a user account in that domain, for example, DOMAIN\\user.</p>
     *
     * @return the parsed user logon info in the Down-Level Logon Name format.
     */
    public String getDownLevelLogonName() {
        if (!isValid()) {
            return null;
        }
        return getNetBIOSDomain() + "\\" + getUser();
    }

    /**
     * Returns the parsed user logon info in the User Principal Name format.
     * <p>User principal name (UPN) format is used to specify an Internet-style name, such as user@domain.example.com.</p>
     *
     * @return the parsed user logon info in the User Principal Name format.
     */
    public String getUserPrincipalName() {
        if (!isValid()) {
            return null;
        }
        return getUser() + "@" + getUPNSuffix();
    }

    /**
     * Parses an input string into a set of Windows login info.
     * <p>A domain name must be present in rawLoginString</p>
     *
     * @param rawLoginString the input string to parse
     * @return a set of Windows login info.
     */
    public static WindowsLogonInfo Parse(String rawLoginString) {
        return new WindowsLogonInfo(rawLoginString, null);
    }

    /**
     * Parses an input string into a set of Windows login info.
     * <p>If a domain isn't provided in the input, the provided default domain is used.</p>
     *
     * @param rawLoginString the input string to parse
     * @param defaultDomain  the domain name to use if one cannot be extracted from the provide input.
     * @return a set of Windows login info.
     */
    public static WindowsLogonInfo Parse(String rawLoginString, String defaultDomain) {
        return new WindowsLogonInfo(rawLoginString, defaultDomain);
    }

    @Override
    public String toString() {
        if (!this.isValid()) {
            return "invalid";
        }
        return getDownLevelLogonName();
    }

    private WindowsLogonInfo(String rawLoginString, String defaultDomain) {
        this.valid = tryParseLoginString(rawLoginString, defaultDomain);
    }

    private boolean tryParseLoginString(String rawUsername, String defaultDomain) {
        if (rawUsername == null || rawUsername.trim().length() == 0) {
            // Illegal Argument provided: input is null or blank
            return false;
        }
        // Parse raw string for domain and user name
        int slashIdx = rawUsername.indexOf('\\');
        int atIdx = rawUsername.indexOf('@');
        if (slashIdx != -1 && atIdx != -1) {
            // Failed to parse input: both separators found
            return false;
        }
        if (slashIdx != -1) {
            this.domain = rawUsername.substring(0, slashIdx);
            this.user = rawUsername.substring(slashIdx + 1, rawUsername.length());
        }
        else if (atIdx != -1) {
            this.domain = rawUsername.substring(atIdx + 1, rawUsername.length());
            this.user = rawUsername.substring(0, atIdx);
        }
        else {
            this.user = rawUsername;
        }
        if (this.user.trim().length() == 0) {
            // Failed to identify a valid user name.
            return false;
        }
        //Substitute default domain if a valid could not be extracted from raw string
        if (this.domain == null || this.domain.trim().length() == 0) {
            if (defaultDomain == null) {
                // Failed to identify a valid domain name.
                return false;
            }
            this.domain = defaultDomain;
        }
        // Extract legacy NetBIOS domain name.
        this.netBIOSDomain = trimDNSsuffixFromDomainName(domain).toUpperCase();
        // Success
        return true;
    }

    /**
     * Remove the DNS suffix from the provided domain name, if present.
     *
     * @param domainName
     * @return domain name with the DNS suffix removed.
     * <p>If "." (dot) is provided, it is return untouched.<br>
     * If null is provided, the method returns null.</p>
     */
    public static String trimDNSsuffixFromDomainName(String domainName) {
        if (domainName != null) {
            int idx = domainName.indexOf('.');
            idx = (idx < 1) ? domainName.length() : idx;
            domainName = domainName.substring(0, idx);
        }
        return domainName;
    }
}