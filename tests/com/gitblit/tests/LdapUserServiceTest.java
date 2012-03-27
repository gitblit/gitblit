package com.gitblit.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.BasicAttribute;
import javax.naming.directory.BasicAttributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.SearchResult;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.gitblit.GitBlit;
import com.gitblit.IStoredSettings;
import com.gitblit.LdapUserService;
import com.gitblit.models.TeamModel;
import com.gitblit.models.UserModel;
import com.gitblit.tests.mock.MemorySettings;
import com.gitblit.tests.mock.MockDirContext;

/*
 * A test case covering the following setup (LDAP Is Mocked):
 * 
 * Users -> Persisted in LDAP
 *   - Repo List persisted in users.conf
 * 
 * Teams -> Persisted in LDAP
 *   - Repo List persisted in users.conf
 *   - User List persisted in LDAP
 */
public class LdapUserServiceTest {
	
	private LdapUserService ldapUserService;
	
	@Before
	public void setup() throws Exception {
		GitBlit gitBlit = new GitBlit();
		
		Map<Object, Object> props = new HashMap<Object, Object>();
		props.put("realm_ldap.alternateConfiguration", "ldapUserServiceTest.conf");
		props.put("realm_ldap.serverUrl", "notneeded");
		props.put("realm_ldap.principal", "jcrygier");
		props.put("realm_ldap.credentials", "mocked");
		props.put("realm_ldap.usersRootNodeDn", "OU=User_Accounts,OU=User_Control,OU=myOrganization,DC=myCompany");
		props.put("realm_ldap.allUsersSearchCriteria", "(objectClass=user)");
		props.put("realm_ldap.teamsRootNodeDn", "OU=Security_Groups,OU=User_Control,OU=myOrganization,DC=myCompany");
		props.put("realm_ldap.allTeamsSearchCriteria", "(&(objectClass=group)(cn=git*))");
		props.put("realm_ldap.userNameAttribute", "name");
		props.put("realm_ldap.adminAttributeName", "memberOf");
		props.put("realm_ldap.adminAttributeValue", "CN=Git_Admins,OU=Security_Groups,OU=User_Control,OU=myOrganization,DC=myCompany");
		props.put("realm_ldap.teamNameAttribute", "name");
		props.put("realm_ldap.isUserRepositoryLinkInLdap", "false");
		props.put("realm_ldap.isTeamRepositoryLinkInLdap", "false");
		props.put("realm_ldap.userTeamLinkAttributeName", "memberOf");
		props.put("realm_ldap.userTeamLinkAttributeRegex", "cn=([^,]+),");
		props.put("realm_ldap.userTeamLinkAttributeRegexGroup", "1");
		props.put("realm_ldap.teamUserLinkAttributeName", "member");
		props.put("realm_ldap.teamUserLinkAttributeRegex", "cn=([^,]+),");
		props.put("realm_ldap.teamUserLinkAttributeRegexGroup", "1");
		
		// Mock out our settings
		IStoredSettings settings = new MemorySettings(props);
		gitBlit.configureContext(settings, false);
		
		// Mock out our LDAP
		ldapUserService = new LdapUserService() {
			@Override
			protected DirContext getLdapDirContext(String principal, String credentials) {
				return getMockDirContext();
			}
		};
		ldapUserService.setup(settings);
		
		// Mock out the backing configuration
		File f = GitBlit.getFileOrFolder("ldapUserServiceTest.conf");
		if (f.createNewFile()) {
			BufferedWriter out = new BufferedWriter(new FileWriter("ldapUserServiceTest.conf"));
			out.write("[team \"Git_Users\"]\n");
			out.write("\trepository = helloworld.git\n");
			out.write("\trepository = repoTwo.git\n");
			out.write("\trepository = myRepos/nestedRepo.git\n");
			out.write("[user \"jcrygier\"]\n");
			out.write("\trepository = repothree.git\n");
			
			out.flush();
			out.close();
		}
	}
	
	@After
	public void teardown() throws Exception {
		File f = GitBlit.getFileOrFolder("ldapUserServiceTest.conf");
		f.delete();
	}
	
	private MockDirContext getMockDirContext() {
		MockDirContext answer = new MockDirContext();
		
		// Mock User Search - jcrygier
		Attributes jcrygierSearch = new BasicAttributes();
		jcrygierSearch.put("name", "jcrygier");
		Attribute jcrygierMemberOfAttribute = new BasicAttribute("memberOf");
		jcrygierSearch.put(jcrygierMemberOfAttribute);
		jcrygierMemberOfAttribute.add("CN=Git_Admins,OU=Security_Groups,OU=User_Control,OU=myOrganization,DC=myCompany");
		jcrygierMemberOfAttribute.add("CN=Git_Users,OU=Security_Groups,OU=User_Control,OU=myOrganization,DC=myCompany");
		SearchResult jcrygierSearchResult = new SearchResult("cn", "jcrygier", jcrygierSearch);
		jcrygierSearchResult.setNameInNamespace("CN=jcrygier,OU=User_Accounts,OU=User_Control,OU=myOrganization,DC=myCompany");
		answer.addSearchResult("OU=User_Accounts,OU=User_Control,OU=myOrganization,DC=myCompany", "(name={0})", new Object[] { "jcrygier" }, jcrygierSearchResult);
		
		// Mock User Search - anotherUser
		Attributes anotherUserSearch = new BasicAttributes();
		anotherUserSearch.put("name", "anotherUser");
		anotherUserSearch.put("memberOf", "CN=Git_Users,OU=Security_Groups,OU=User_Control,OU=myOrganization,DC=myCompany");
		SearchResult anotherUserSearchResult = new SearchResult("cn", "anotherUser", anotherUserSearch);
		anotherUserSearchResult.setNameInNamespace("CN=anotherUser,OU=User_Accounts,OU=User_Control,OU=myOrganization,DC=myCompany");
		answer.addSearchResult("OU=User_Accounts,OU=User_Control,OU=myOrganization,DC=myCompany", "(name={0})", new Object[] { "anotherUser" }, anotherUserSearchResult);
		
		// All Users Search - re-use above users
		answer.addSearchResult("OU=User_Accounts,OU=User_Control,OU=myOrganization,DC=myCompany", "(objectClass=user)", new Object[] { }, jcrygierSearchResult, anotherUserSearchResult);
		
		// Mock Team Search - Git_Admins 
		Attributes gitAdmins = new BasicAttributes();
		gitAdmins.put("name", "Git_Admins");
		gitAdmins.put("member", "CN=jcrygier,CN=Git_Users,OU=Security_Groups,OU=User_Control,OU=myOrganization,DC=myCompany");
		SearchResult gitAdminsSearchResult = new SearchResult("cn", "Git_Admins", gitAdmins);
		gitAdminsSearchResult.setNameInNamespace("CN=Git_Admins,OU=Security_Groups,OU=User_Control,OU=myOrganization,DC=myCompany");
		answer.addSearchResult("OU=Security_Groups,OU=User_Control,OU=myOrganization,DC=myCompany", "(name={0})", new Object[] { "Git_Admins" }, gitAdminsSearchResult);
		
		// Mock Team Search - Git_Users 
		Attributes gitUsers = new BasicAttributes();
		gitUsers.put("name", "Git_Users");
		Attribute gitUsersMemberAttribute = new BasicAttribute("member");
		gitUsers.put(gitUsersMemberAttribute);
		gitUsersMemberAttribute.add("CN=jcrygier,CN=Git_Users,OU=Security_Groups,OU=User_Control,OU=myOrganization,DC=myCompany");
		gitUsersMemberAttribute.add("CN=anotherUser,CN=Git_Users,OU=Security_Groups,OU=User_Control,OU=myOrganization,DC=myCompany");
		SearchResult gitUsersSearchResult = new SearchResult("cn", "Git_Users", gitUsers);
		gitUsersSearchResult.setNameInNamespace("CN=Git_Users,OU=Security_Groups,OU=User_Control,OU=myOrganization,DC=myCompany");
		answer.addSearchResult("OU=Security_Groups,OU=User_Control,OU=myOrganization,DC=myCompany", "(name={0})", new Object[] { "Git_Users" }, gitUsersSearchResult);

		// All Team Search - re-use above teams
		answer.addSearchResult("OU=Security_Groups,OU=User_Control,OU=myOrganization,DC=myCompany", "(&(objectClass=group)(cn=git*))", new Object[] { }, gitAdminsSearchResult, gitUsersSearchResult);
		
		// Users for Git_Admins
		answer.addSearchResult("OU=User_Accounts,OU=User_Control,OU=myOrganization,DC=myCompany", "(memberOf=CN={0},OU=Security_Groups,OU=User_Control,OU=myOrganization,DC=myCompany)", new Object[] { "Git_Admins" }, jcrygierSearchResult);
		
		// Users for Git_Users
		answer.addSearchResult("OU=User_Accounts,OU=User_Control,OU=myOrganization,DC=myCompany", "(memberOf=CN={0},OU=Security_Groups,OU=User_Control,OU=myOrganization,DC=myCompany)", new Object[] { "Git_Users" }, jcrygierSearchResult, anotherUserSearchResult);
		
		// Teams for jcrygier
		answer.addSearchResult("OU=Security_Groups,OU=User_Control,OU=myOrganization,DC=myCompany", "(member={0})", new Object[] { "CN=jcrygier,OU=User_Accounts,OU=User_Control,OU=myOrganization,DC=myCompany" }, gitAdminsSearchResult, gitUsersSearchResult);

		return answer;
	}

	@Test
	public void testAuthenticate() {
		UserModel userModel = ldapUserService.authenticate("domain\\jcrygier", "password".toCharArray());
		
		assertNotNull("UserModel not found", userModel);
		assertEquals("UserModel wrong username", "jcrygier", userModel.getName());
		
		UserModel userModel2 = ldapUserService.authenticate("doesNotExist", "anotherPassword".toCharArray());
		
		assertNull("UserModel found for bogus user", userModel2);
	}
	
	@Test
	public void testGetUserModel() {
		UserModel userModel = ldapUserService.getUserModel("jcrygier");
		
		assertNotNull("UserModel not found", userModel);
		assertEquals("UserModel wrong username", "jcrygier", userModel.getName());
		
		UserModel userModel2 = ldapUserService.getUserModel("anotherUser");
		
		assertNotNull("UserModel not found", userModel2);
		assertEquals("UserModel wrong username", "anotherUser", userModel2.getName());
		
		UserModel userModel3 = ldapUserService.getUserModel("doesNotExist");
		
		assertNull("UserModel incorrectly found", userModel3);
	}
	
	@Test
	public void testGetAllUsers() {
		List<String> allUserNames = ldapUserService.getAllUsernames();
		
		assertNotNull("No Usernames returned", allUserNames);
		assertFalse("No results", allUserNames.isEmpty());
		assertEquals("Number of users wrong", 2, allUserNames.size());
	}
	
	@Test
	public void testGetAllTeams() {
		List<TeamModel> allTeamNames = ldapUserService.getAllTeams();
		
		assertNotNull("No Team Names returned", allTeamNames);
		assertFalse("No resutls", allTeamNames.isEmpty());
		assertEquals("Number of teams wrong", 2, allTeamNames.size());
	}
	
	@Test
	public void testGetAllTeamNames() {
		List<String> allTeamNames = ldapUserService.getAllTeamNames();
		
		assertNotNull("No Team Names returned", allTeamNames);
		assertFalse("No resutls", allTeamNames.isEmpty());
		assertEquals("Number of teams wrong", 2, allTeamNames.size());
		
		assertEquals("Team 1 Wrong Name", "git_admins", allTeamNames.get(0));
		assertEquals("Team 1 Wrong Name", "git_users", allTeamNames.get(1));
	}
	
	@Test
	public void testGetUsersFromTeam() {
		TeamModel team = ldapUserService.getTeamModel("Git_Users");
		
		assertNotNull("No Team returned", team);
		assertEquals("Team Name Wrong", "Git_Users", team.name);
		assertEquals("Team Number of users wrong", 2, team.users.size());
		assertTrue("Team Member Wrong", team.users.contains("jcrygier"));
		assertTrue("Team Member Wrong", team.users.contains("anotheruser"));
	}
	
	@Test
	public void testGetRepositoriesFromTeam() {
		TeamModel team = ldapUserService.getTeamModel("Git_Users");
		
		assertNotNull("No Team returned", team);
		assertEquals("Team Name Wrong", "Git_Users", team.name);
		assertEquals("Team Number of repositories wrong", 3, team.repositories.size());
		assertTrue("Repository Wrong", team.hasRepository("helloworld.git"));
		assertTrue("Repository Wrong", team.hasRepository("repotwo.git"));
		assertTrue("Repository Wrong", team.hasRepository("myrepos/nestedrepo.git"));	
	}
	
	@SuppressWarnings("deprecation")
	@Test
	public void testGetRepositoriesFromUser() {
		UserModel user = ldapUserService.getUserModel("jcrygier");
		
		assertNotNull("No User returned", user);
		assertEquals("User Name Wrong", "jcrygier", user.getName());
		assertTrue("Repository Wrong", user.canAccessRepository("repothree.git"));
		assertTrue("Repository Wrong", user.canAccessRepository("helloworld.git"));
		assertTrue("Repository Wrong", user.canAccessRepository("repotwo.git"));
		assertTrue("Repository Wrong", user.canAccessRepository("myrepos/nestedrepo.git"));		
	}

}
