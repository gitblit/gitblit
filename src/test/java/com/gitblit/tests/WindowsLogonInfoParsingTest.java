package com.gitblit.tests;

import com.gitblit.utils.WindowsLogonInfo;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * WindowsLogonInfo tests
 *
 * @author Frederic Thevenet
 */
public class WindowsLogonInfoParsingTest {
    @Test
    public void testLoginInfoParsing() {
        Map<String, Boolean> testCases = new HashMap<>();
        testCases.put("bob@corp", true);
        testCases.put("corp\\bob", true);
        testCases.put("bob", true);
        testCases.put("corp\\", false);
        testCases.put("\\bob", true);
        testCases.put("@corp", false);
        testCases.put("bob@", true);
        testCases.put(null, false);
        testCases.put("bob@corp\\bob", false);
        testCases.put(".\\bob", true);
        testCases.put("bob@corp.company.com", true);
        testCases.put("corp.company.com\\bob", true);
        runTest(testCases, "default");
    }

    @Test
    public void testLoginInfoParsingWithDefaultFqdn() {
        Map<String, Boolean> testCases = new HashMap<>();
        testCases.put("bob@corp", true);
        testCases.put("corp\\bob", true);
        testCases.put("bob", true);
        testCases.put("corp\\", false);
        testCases.put("\\bob", true);
        testCases.put("@corp", false);
        testCases.put("bob@", true);
        testCases.put(null, false);
        testCases.put("bob@corp\\bob", false);
        testCases.put(".\\bob", true);
        testCases.put("bob@corp.company.com", true);
        testCases.put("corp.company.com\\bob", true);
        runTest(testCases, "default.company.com");
    }

    @Test
    public void testLoginInfoParsingWithoutDefaultDomain() {
        Map<String, Boolean> testCases = new HashMap<>();
        testCases.put("bob@corp", true);
        testCases.put("corp\\bob", true);
        testCases.put("bob", false);
        testCases.put("corp\\", false);
        testCases.put("\\bob", false);
        testCases.put("@corp", false);
        testCases.put("bob@", false);
        testCases.put(null, false);
        testCases.put("bob@corp\\bob", false);
        testCases.put(".\\bob", true);
        testCases.put("bob@corp.company.com", true);
        testCases.put("corp.company.com\\bob", true);
        runTest(testCases, null);
    }

    private void runTest(Map<String, Boolean> testCases, String defaultDomain) {
        for (Map.Entry<String, Boolean> test : testCases.entrySet()) {
            WindowsLogonInfo loginInfo = defaultDomain != null ?
                    WindowsLogonInfo.Parse(test.getKey(), defaultDomain) : WindowsLogonInfo.Parse(test.getKey());

            assertTrue("Test case " + test.toString() + ": does not match the predicted result", loginInfo.isValid() == test.getValue());
            if (loginInfo.isValid()) {
                assertNotNull("Test case " + test.toString() + ": domain shouldn't be null", loginInfo.getNetBIOSDomain());
                assertNotNull("Test case " + test.toString() + ": user shouldn't be null", loginInfo.getUser());
                assertTrue("Test case " + test.toString() + ": Unexpected user name: " + loginInfo.getUser(),
                        "bob".equalsIgnoreCase(loginInfo.getUser()));
                assertTrue("Test case " + test.toString() + ": Unexpected domain name: "+ loginInfo.getNetBIOSDomain(),
                        loginInfo.getNetBIOSDomain().equals("CORP") ||
                                loginInfo.getNetBIOSDomain().equals(".") ||
                                loginInfo.getNetBIOSDomain().equals("DEFAULT"));
            }
        }
    }
}
