/*
 * Copyright 2011 gitblit.com.
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
package com.gitblit.wicket;

import java.text.DateFormat;
import java.text.MessageFormat;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;

import javax.servlet.http.HttpServletRequest;

import org.apache.wicket.Component;
import org.apache.wicket.PageParameters;
import org.apache.wicket.Request;
import org.apache.wicket.RequestCycle;
import org.apache.wicket.behavior.AttributeAppender;
import org.apache.wicket.behavior.HeaderContributor;
import org.apache.wicket.behavior.SimpleAttributeModifier;
import org.apache.wicket.markup.html.IHeaderContributor;
import org.apache.wicket.markup.html.IHeaderResponse;
import org.apache.wicket.markup.html.basic.Label;
import org.apache.wicket.markup.html.image.ContextImage;
import org.apache.wicket.model.Model;
import org.apache.wicket.protocol.http.WebRequest;
import org.apache.wicket.resource.ContextRelativeResource;
import org.eclipse.jgit.diff.DiffEntry.ChangeType;

import com.gitblit.Constants;
import com.gitblit.Constants.AccessPermission;
import com.gitblit.Constants.FederationPullStatus;
import com.gitblit.IStoredSettings;
import com.gitblit.Keys;
import com.gitblit.models.FederationModel;
import com.gitblit.models.Metric;
import com.gitblit.utils.DiffUtils.DiffComparator;
import com.gitblit.utils.HttpUtils;
import com.gitblit.utils.StringUtils;
import com.gitblit.utils.TimeUtils;

public class WicketUtils {

	public static void setCssClass(Component container, String value) {
		container.add(new SimpleAttributeModifier("class", value));
	}

	public static void addCssClass(Component container, String value) {
		container.add(new AttributeAppender("class", new Model<String>(value), " "));
	}

	public static void setCssStyle(Component container, String value) {
		container.add(new SimpleAttributeModifier("style", value));
	}

	public static void setCssBackground(Component container, String value) {
		String background = MessageFormat.format("background-color:{0};",
				StringUtils.getColor(value));
		container.add(new SimpleAttributeModifier("style", background));
	}

	public static Component setHtmlTooltip(Component container, String value) {
		return container.add(new SimpleAttributeModifier("title", value));
	}

	public static void setInputPlaceholder(Component container, String value) {
		container.add(new SimpleAttributeModifier("placeholder", value));
	}

	public static void setChangeTypeCssClass(Component container, ChangeType type) {
		switch (type) {
		case ADD:
			setCssClass(container, "addition");
			break;
		case COPY:
		case RENAME:
			setCssClass(container, "rename");
			break;
		case DELETE:
			setCssClass(container, "deletion");
			break;
		case MODIFY:
			setCssClass(container, "modification");
			break;
		}
	}

	public static void setPermissionClass(Component container, AccessPermission permission) {
		if (permission == null) {
			setCssClass(container, "badge");
			return;
		}
		switch (permission) {
		case REWIND:
		case DELETE:
		case CREATE:
			setCssClass(container, "badge badge-success");
			break;
		case PUSH:
			setCssClass(container, "badge badge-info");
			break;
		case CLONE:
			setCssClass(container, "badge badge-inverse");
			break;
		default:
			setCssClass(container, "badge");
			break;
		}
	}

	public static void setAlternatingBackground(Component c, int i) {
		String clazz = i % 2 == 0 ? "light" : "dark";
		setCssClass(c, clazz);
	}

	public static Label createAuthorLabel(String wicketId, String author) {
		Label label = new Label(wicketId, author);
		WicketUtils.setHtmlTooltip(label, author);
		return label;
	}

	public static ContextImage getPullStatusImage(String wicketId, FederationPullStatus status) {
		String filename = null;
		switch (status) {
		case MIRRORED:
		case PULLED:
			filename = "bullet_green.png";
			break;
		case SKIPPED:
			filename = "bullet_yellow.png";
			break;
		case FAILED:
			filename = "bullet_red.png";
			break;
		case EXCLUDED:
			filename = "bullet_white.png";
			break;
		case PENDING:
		case NOCHANGE:
		default:
			filename = "bullet_black.png";
		}
		return WicketUtils.newImage(wicketId, filename, status.name());
	}

	public static ContextImage getFileImage(String wicketId, String filename) {
		filename = filename.toLowerCase();
		if (filename.endsWith(".java")) {
			return newImage(wicketId, "file_java_16x16.png");
		} else if (filename.endsWith(".rb")) {
			return newImage(wicketId, "file_ruby_16x16.png");
		} else if (filename.endsWith(".php")) {
			return newImage(wicketId, "file_php_16x16.png");
		} else if (filename.endsWith(".cs")) {
			return newImage(wicketId, "file_cs_16x16.png");
		} else if (filename.endsWith(".cpp")) {
			return newImage(wicketId, "file_cpp_16x16.png");
		} else if (filename.endsWith(".c")) {
			return newImage(wicketId, "file_c_16x16.png");
		} else if (filename.endsWith(".h")) {
			return newImage(wicketId, "file_h_16x16.png");
		} else if (filename.endsWith(".sln")) {
			return newImage(wicketId, "file_vs_16x16.png");
		} else if (filename.endsWith(".csv") || filename.endsWith(".xls")
				|| filename.endsWith(".xlsx")) {
			return newImage(wicketId, "file_excel_16x16.png");
		} else if (filename.endsWith(".doc") || filename.endsWith(".docx")) {
			return newImage(wicketId, "file_doc_16x16.png");
		} else if (filename.endsWith(".ppt") || filename.endsWith(".pptx")) {
			return newImage(wicketId, "file_ppt_16x16.png");
		} else if (filename.endsWith(".zip")) {
			return newImage(wicketId, "file_zip_16x16.png");
		} else if (filename.endsWith(".pdf")) {
			return newImage(wicketId, "file_acrobat_16x16.png");
		} else if (filename.endsWith(".htm") || filename.endsWith(".html")) {
			return newImage(wicketId, "file_world_16x16.png");
		} else if (filename.endsWith(".xml")) {
			return newImage(wicketId, "file_code_16x16.png");
		} else if (filename.endsWith(".properties")) {
			return newImage(wicketId, "file_settings_16x16.png");
		}

		String ext = StringUtils.getFileExtension(filename).toLowerCase();
		IStoredSettings settings = GitBlitWebApp.get().settings();
		if (MarkupProcessor.getMarkupExtensions(settings).contains(ext)) {
			return newImage(wicketId, "file_world_16x16.png");
		}
		return newImage(wicketId, "file_16x16.png");
	}

	public static ContextImage getRegistrationImage(String wicketId, FederationModel registration,
			Component c) {
		if (registration.isResultData()) {
			return WicketUtils.newImage(wicketId, "information_16x16.png",
					c.getString("gb.federationResults"));
		} else {
			return WicketUtils.newImage(wicketId, "arrow_left.png",
					c.getString("gb.federationRegistration"));
		}
	}

	public static ContextImage newClearPixel(String wicketId) {
		return newImage(wicketId, "pixel.png");
	}

	public static ContextImage newBlankImage(String wicketId) {
		return newImage(wicketId, "blank.png");
	}

	public static ContextImage newImage(String wicketId, String file) {
		return newImage(wicketId, file, null);
	}

	public static ContextImage newImage(String wicketId, String file, String tooltip) {
		ContextImage img = new ContextImage(wicketId, file);
		if (!StringUtils.isEmpty(tooltip)) {
			setHtmlTooltip(img, tooltip);
		}
		return img;
	}

	public static Label newIcon(String wicketId, String css) {
		Label lbl = new Label(wicketId);
		setCssClass(lbl, css);
		return lbl;
	}

	public static Label newBlankIcon(String wicketId) {
		Label lbl = new Label(wicketId);
		setCssClass(lbl, "");
		lbl.setRenderBodyOnly(true);
		return lbl;
	}

	public static ContextRelativeResource getResource(String file) {
		return new ContextRelativeResource(file);
	}

	public static String getGitblitURL(Request request) {
		HttpServletRequest req = ((WebRequest) request).getHttpServletRequest();
		return HttpUtils.getGitblitURL(req);
	}

	public static HeaderContributor syndicationDiscoveryLink(final String feedTitle,
			final String url) {
		return new HeaderContributor(new IHeaderContributor() {
			private static final long serialVersionUID = 1L;

			@Override
			public void renderHead(IHeaderResponse response) {
				String contentType = "application/rss+xml";

				StringBuilder buffer = new StringBuilder();
				buffer.append("<link rel=\"alternate\" ");
				buffer.append("type=\"").append(contentType).append("\" ");
				buffer.append("title=\"").append(feedTitle).append("\" ");
				buffer.append("href=\"").append(url).append("\" />");
				response.renderString(buffer.toString());
			}
		});
	}

	public static PageParameters newTokenParameter(String token) {
		Map<String, String> parameterMap = new HashMap<String, String>();
		parameterMap.put("t", token);
		return new PageParameters(parameterMap);
	}

	public static PageParameters newRegistrationParameter(String url,
			String name) {
		Map<String, String> parameterMap = new HashMap<String, String>();
		parameterMap.put("u", url);
		parameterMap.put("n", name);
		return new PageParameters(parameterMap);
	}

	public static PageParameters newUsernameParameter(String username) {
		Map<String, String> parameterMap = new HashMap<String, String>();
		parameterMap.put("user", username);
		return new PageParameters(parameterMap);
	}

	public static PageParameters newTeamnameParameter(String teamname) {
		Map<String, String> parameterMap = new HashMap<String, String>();
		parameterMap.put("team", teamname);
		return new PageParameters(parameterMap);
	}

	public static PageParameters newProjectParameter(String projectName) {
		Map<String, String> parameterMap = new HashMap<String, String>();
		parameterMap.put("p", projectName);
		return new PageParameters(parameterMap);
	}

	public static PageParameters newRepositoryParameter(String repositoryName) {
		Map<String, String> parameterMap = new HashMap<String, String>();
		if (!StringUtils.isEmpty(repositoryName)) {
			parameterMap.put("r", repositoryName);
		}
		return new PageParameters(parameterMap);
	}

	public static PageParameters newObjectParameter(String objectId) {
		Map<String, String> parameterMap = new HashMap<String, String>();
		parameterMap.put("h", objectId);
		return new PageParameters(parameterMap);
	}

	public static PageParameters newObjectParameter(String repositoryName,
			String objectId) {
		Map<String, String> parameterMap = new HashMap<String, String>();
		if (StringUtils.isEmpty(objectId)) {
			return newRepositoryParameter(repositoryName);
		}
		parameterMap.put("r", repositoryName);
		parameterMap.put("h", objectId);
		return new PageParameters(parameterMap);
	}

	public static PageParameters newDiffParameter(String repositoryName,
			String objectId, DiffComparator diffComparator) {
		Map<String, String> parameterMap = new HashMap<String, String>();
		if (StringUtils.isEmpty(objectId)) {
			return newRepositoryParameter(repositoryName);
		}
		parameterMap.put("r", repositoryName);
		parameterMap.put("h", objectId);
		parameterMap.put("w", "" + diffComparator.ordinal());
		return new PageParameters(parameterMap);
	}

	public static PageParameters newDiffParameter(String repositoryName,
			String objectId, DiffComparator diffComparator, String blobPath) {
		Map<String, String> parameterMap = new HashMap<String, String>();
		if (StringUtils.isEmpty(objectId)) {
			return newRepositoryParameter(repositoryName);
		}
		parameterMap.put("r", repositoryName);
		parameterMap.put("h", objectId);
		parameterMap.put("w", "" + diffComparator.ordinal());
		parameterMap.put("f", blobPath);
		return new PageParameters(parameterMap);
	}

	public static PageParameters newRangeParameter(String repositoryName,
			String startRange, String endRange) {
		Map<String, String> parameterMap = new HashMap<String, String>();
		parameterMap.put("r", repositoryName);
		parameterMap.put("h", startRange + ".." + endRange);
		return new PageParameters(parameterMap);
	}

	public static PageParameters newPathParameter(String repositoryName,
			String objectId, String path) {
		Map<String, String> parameterMap = new HashMap<String, String>();
		if (StringUtils.isEmpty(path)) {
			return newObjectParameter(repositoryName, objectId);
		}
		if (StringUtils.isEmpty(objectId)) {
			parameterMap.put("r", repositoryName);
			parameterMap.put("f", path);
			return new PageParameters(parameterMap);
		}
		parameterMap.put("r", repositoryName);
		parameterMap.put("h", objectId);
		parameterMap.put("f", path);
		return new PageParameters(parameterMap);
	}

	public static PageParameters newLogPageParameter(String repositoryName,
			String objectId, int pageNumber) {
		Map<String, String> parameterMap = new HashMap<String, String>();
		if (pageNumber <= 1) {
			return newObjectParameter(repositoryName, objectId);
		}
		if (StringUtils.isEmpty(objectId)) {
			parameterMap.put("r", repositoryName);
			parameterMap.put("pg", String.valueOf(pageNumber));
			return new PageParameters(parameterMap);
		}
		parameterMap.put("r", repositoryName);
		parameterMap.put("h", objectId);
		parameterMap.put("pg", String.valueOf(pageNumber));
		return new PageParameters(parameterMap);
	}

	public static PageParameters newHistoryPageParameter(String repositoryName,
			String objectId, String path, int pageNumber) {
		Map<String, String> parameterMap = new HashMap<String, String>();
		if (pageNumber <= 1) {
			return newObjectParameter(repositoryName, objectId);
		}
		if (StringUtils.isEmpty(objectId)) {
			parameterMap.put("r", repositoryName);
			parameterMap.put("f", path);
			parameterMap.put("pg", String.valueOf(pageNumber));
			return new PageParameters(parameterMap);
		}
		parameterMap.put("r", repositoryName);
		parameterMap.put("h", objectId);
		parameterMap.put("f", path);
		parameterMap.put("pg", String.valueOf(pageNumber));
		return new PageParameters(parameterMap);
	}

	public static PageParameters newFilestorePageParameter(int pageNumber, String filter) {
		Map<String, String> parameterMap = new HashMap<String, String>();
		
		if (pageNumber > 1) {
			parameterMap.put("pg", String.valueOf(pageNumber));
		}
		if (filter != null) {
			parameterMap.put("s", String.valueOf(filter));
		}
		
		return new PageParameters(parameterMap);
	}

	public static PageParameters newBlobDiffParameter(String repositoryName,
			String baseCommitId, String commitId, String path) {
		Map<String, String> parameterMap = new HashMap<String, String>();
		if (StringUtils.isEmpty(commitId)) {
			parameterMap.put("r", repositoryName);
			parameterMap.put("f", path);
			parameterMap.put("hb", baseCommitId);
			return new PageParameters(parameterMap);
		}
		parameterMap.put("r", repositoryName);
		parameterMap.put("h", commitId);
		parameterMap.put("f", path);
		parameterMap.put("hb", baseCommitId);
		return new PageParameters(parameterMap);
	}

	public static PageParameters newSearchParameter(String repositoryName,
			String commitId, String search, Constants.SearchType type) {
		Map<String, String> parameterMap = new HashMap<String, String>();
		setCommitIdIf(commitId, parameterMap);
		parameterMap.put("r", repositoryName);
		parameterMap.put("s", search);
		parameterMap.put("st", type.name());
		return new PageParameters(parameterMap);
	}

	public static void setCommitIdIf(String commitId, Map<String, String> parameterMap) {
		if (StringUtils.isEmpty(commitId)) {
			final String branch = getRequestParameter("h");
			if (!StringUtils.isEmpty(branch)) {
				parameterMap.put("h", branch);
			}
		}else{
			parameterMap.put("h", commitId);
		}
	}

	protected static String getRequestParameter(final String key) {
		final HttpServletRequest req = ((WebRequest)RequestCycle.get().getRequest()).getHttpServletRequest();
		final String branch = req == null ? null : req.getParameter(key);
		return branch;
	}	
	public static void setCommitIdIf(final PageParameters  params) {
		if( params.get("h") == null ){
			final String branch = getRequestParameter("h");
			if (!StringUtils.isEmpty(branch)) {
				params.put("h", branch);
			}
		}
	}	
	public static PageParameters newSearchParameter(String repositoryName,
			String commitId, String search, Constants.SearchType type,
			int pageNumber) {
		Map<String, String> parameterMap = new HashMap<String, String>();
		setCommitIdIf(commitId, parameterMap);
		parameterMap.put("r", repositoryName);
		parameterMap.put("s", search);
		parameterMap.put("st", type.name());
		parameterMap.put("pg", String.valueOf(pageNumber));
		return new PageParameters(parameterMap);
	}

	public static PageParameters newBlameTypeParameter(String repositoryName,
			String commitId, String path, String blameType) {
		Map<String, String> parameterMap = new HashMap<String, String>();
		parameterMap.put("r", repositoryName);
		parameterMap.put("h", commitId);
		parameterMap.put("f", path);
		parameterMap.put("blametype", blameType);
		return new PageParameters(parameterMap);
	}

	public static PageParameters newTicketsParameters(String repositoryName, String... states) {
		PageParameters tParams = newRepositoryParameter(repositoryName);
		if (states != null) {
			for (String state : states) {
				tParams.add("status", state);
			}
		}
		return tParams;
	}

	public static PageParameters newOpenTicketsParameter(String repositoryName) {
		return newTicketsParameters(repositoryName, TicketsUI.openStatii);
	}

	public static String getProjectName(PageParameters params) {
		return params.getString("p", "");
	}

	public static String getRepositoryName(PageParameters params) {
		return params.getString("r", "");
	}

	public static String getObject(PageParameters params) {
		return params.getString("h", null);
	}

	public static String getPath(PageParameters params) {
		return params.getString("f", null);
	}

	public static String getBaseObjectId(PageParameters params) {
		return params.getString("hb", null);
	}

	public static String getSearchString(PageParameters params) {
		return params.getString("s", null);
	}

	public static String getSearchType(PageParameters params) {
		return params.getString("st", null);
	}

	public static DiffComparator getDiffComparator(PageParameters params) {
		int ordinal = params.getInt("w", 0);
		return DiffComparator.values()[ordinal];
	}

	public static int getPage(PageParameters params) {
		// index from 1
		return params.getInt("pg", 1);
	}

	public static String getRegEx(PageParameters params) {
		return params.getString("x", "");
	}

	public static String getSet(PageParameters params) {
		return params.getString("set", "");
	}

	public static String getTeam(PageParameters params) {
		return params.getString("team", "");
	}

	public static int getDaysBack(PageParameters params) {
		return params.getInt("db", 0);
	}

	public static String getUsername(PageParameters params) {
		return params.getString("user", "");
	}

	public static String getTeamname(PageParameters params) {
		return params.getString("team", "");
	}

	public static String getToken(PageParameters params) {
		return params.getString("t", "");
	}

	public static String getUrlParameter(PageParameters params) {
		return params.getString("u", "");
	}

	public static String getNameParameter(PageParameters params) {
		return params.getString("n", "");
	}

	public static Label createDateLabel(String wicketId, Date date, TimeZone timeZone, TimeUtils timeUtils) {
		return createDateLabel(wicketId, date, timeZone, timeUtils, true);
	}

	public static Label createDateLabel(String wicketId, Date date, TimeZone timeZone, TimeUtils timeUtils, boolean setCss) {
		String format = GitBlitWebApp.get().settings().getString(Keys.web.datestampShortFormat, "MM/dd/yy");
		DateFormat df = new SimpleDateFormat(format);
		if (timeZone == null) {
			timeZone = GitBlitWebApp.get().getTimezone();
		}
		df.setTimeZone(timeZone);
		String dateString;
		if (date.getTime() == 0) {
			dateString = "--";
		} else {
			dateString = df.format(date);
		}
		String title = null;
		if (date.getTime() <= System.currentTimeMillis()) {
			// past
			title = timeUtils.timeAgo(date);
		}
		if (title != null && (System.currentTimeMillis() - date.getTime()) < 10 * 24 * 60 * 60 * 1000L) {
			String tmp = dateString;
			dateString = title;
			title = tmp;
		}
		Label label = new Label(wicketId, dateString);
		if (setCss) {
			WicketUtils.setCssClass(label, timeUtils.timeAgoCss(date));
		}
		if (!StringUtils.isEmpty(title)) {
			WicketUtils.setHtmlTooltip(label, title);
		}
		return label;
	}

	public static Label createTimeLabel(String wicketId, Date date, TimeZone timeZone, TimeUtils timeUtils) {
		String format = GitBlitWebApp.get().settings().getString(Keys.web.timeFormat, "HH:mm");
		DateFormat df = new SimpleDateFormat(format);
		if (timeZone == null) {
			timeZone = GitBlitWebApp.get().getTimezone();
		}
		df.setTimeZone(timeZone);
		String timeString;
		if (date.getTime() == 0) {
			timeString = "--";
		} else {
			timeString = df.format(date);
		}
		String title = timeUtils.timeAgo(date);
		Label label = new Label(wicketId, timeString);
		if (!StringUtils.isEmpty(title)) {
			WicketUtils.setHtmlTooltip(label, title);
		}
		return label;
	}

	public static Label createDatestampLabel(String wicketId, Date date, TimeZone timeZone, TimeUtils timeUtils) {
		String format = GitBlitWebApp.get().settings().getString(Keys.web.datestampLongFormat, "EEEE, MMMM d, yyyy");
		DateFormat df = new SimpleDateFormat(format);
		if (timeZone == null) {
			timeZone = GitBlitWebApp.get().getTimezone();
		}
		df.setTimeZone(timeZone);
		String dateString;
		if (date.getTime() == 0) {
			dateString = "--";
		} else {
			dateString = df.format(date);
		}
		String title = null;
		if (TimeUtils.isToday(date, timeZone)) {
			title = timeUtils.today();
		} else if (TimeUtils.isYesterday(date, timeZone)) {
			title = timeUtils.yesterday();
		} else if (date.getTime() <= System.currentTimeMillis()) {
			// past
			title = timeUtils.timeAgo(date);
		} else {
			// future
			title = timeUtils.inFuture(date);
		}
		if ((System.currentTimeMillis() - date.getTime()) < 10 * 24 * 60 * 60 * 1000L) {
			String tmp = dateString;
			dateString = title;
			title = tmp;
		}
		Label label = new Label(wicketId, dateString);
		if (!StringUtils.isEmpty(title)) {
			WicketUtils.setHtmlTooltip(label, title);
		}
		return label;
	}

	public static Label createTimestampLabel(String wicketId, Date date, TimeZone timeZone, TimeUtils timeUtils) {
		String format = GitBlitWebApp.get().settings().getString(Keys.web.datetimestampLongFormat,
				"EEEE, MMMM d, yyyy HH:mm Z");
		DateFormat df = new SimpleDateFormat(format);
		if (timeZone == null) {
			timeZone = GitBlitWebApp.get().getTimezone();
		}
		df.setTimeZone(timeZone);
		String dateString;
		if (date.getTime() == 0) {
			dateString = "--";
		} else {
			dateString = df.format(date);
		}
		String title = null;
		if (date.getTime() <= System.currentTimeMillis()) {
			// past
			title = timeUtils.timeAgo(date);
		}
		Label label = new Label(wicketId, dateString);
		if (!StringUtils.isEmpty(title)) {
			WicketUtils.setHtmlTooltip(label, title);
		}
		return label;
	}

	public static double maxValue(Collection<Metric> metrics) {
		double max = Double.MIN_VALUE;
		for (Metric m : metrics) {
			if (m.count > max) {
				max = m.count;
			}
		}
		return max;
	}
}
