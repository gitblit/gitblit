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
package com.gitblit.wicket.pages;

import java.text.MessageFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.apache.wicket.Component;
import org.apache.wicket.PageParameters;
import org.apache.wicket.behavior.HeaderContributor;
import org.apache.wicket.markup.html.basic.Label;
import org.apache.wicket.markup.html.link.BookmarkablePageLink;
import org.apache.wicket.markup.html.panel.Fragment;
import org.apache.wicket.markup.repeater.Item;
import org.apache.wicket.markup.repeater.data.DataView;
import org.apache.wicket.markup.repeater.data.ListDataProvider;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;

import com.gitblit.Keys;
import com.gitblit.models.Metric;
import com.gitblit.models.RepositoryModel;
import com.gitblit.models.UserModel;
import com.gitblit.utils.JGitUtils;
import com.gitblit.utils.StringUtils;
import com.gitblit.wicket.CacheControl;
import com.gitblit.wicket.CacheControl.LastModified;
import com.gitblit.wicket.GitBlitWebSession;
import com.gitblit.wicket.MarkupProcessor;
import com.gitblit.wicket.MarkupProcessor.MarkupDocument;
import com.gitblit.wicket.MarkupProcessor.MarkupSyntax;
import com.gitblit.wicket.WicketUtils;
import com.gitblit.wicket.charting.Chart;
import com.gitblit.wicket.charting.Charts;
import com.gitblit.wicket.charting.Flotr2Charts;
import com.gitblit.wicket.panels.BranchesPanel;
import com.gitblit.wicket.panels.LinkPanel;
import com.gitblit.wicket.panels.LogPanel;
import com.gitblit.wicket.panels.RepositoryUrlPanel;
import com.gitblit.wicket.panels.TagsPanel;

@CacheControl(LastModified.REPOSITORY)
public class SummaryPage extends RepositoryPage {

	public SummaryPage(PageParameters params) {
		super(params);

		int numberCommits = app().settings().getInteger(Keys.web.summaryCommitCount, 20);
		if (numberCommits <= 0) {
			numberCommits = 20;
		}
		int numberRefs = app().settings().getInteger(Keys.web.summaryRefsCount, 5);

		Repository r = getRepository();
		final RepositoryModel model = getRepositoryModel();
		UserModel user = GitBlitWebSession.get().getUser();
		if (user == null) {
			user = UserModel.ANONYMOUS;
		}

		List<Metric> metrics = null;
		Metric metricsTotal = null;
		if (!model.skipSummaryMetrics && app().settings().getBoolean(Keys.web.generateActivityGraph, true)) {
			metrics = app().repositories().getRepositoryDefaultMetrics(model, r);
			metricsTotal = metrics.remove(0);
		}

		addSyndicationDiscoveryLink();

		// repository description
		add(new Label("repositoryDescription", getRepositoryModel().description));

		// owner links
		final List<String> owners = new ArrayList<String>(getRepositoryModel().owners);
		ListDataProvider<String> ownersDp = new ListDataProvider<String>(owners);
		DataView<String> ownersView = new DataView<String>("repositoryOwners", ownersDp) {
			private static final long serialVersionUID = 1L;
			int counter = 0;
			@Override
			public void populateItem(final Item<String> item) {
				String ownername = item.getModelObject();
				UserModel ownerModel = app().users().getUserModel(ownername);
				if (ownerModel != null) {
					item.add(new LinkPanel("owner", null, ownerModel.getDisplayName(), UserPage.class,
							WicketUtils.newUsernameParameter(ownerModel.username)).setRenderBodyOnly(true));
				} else {
					Label owner = new Label("owner", ownername);
					WicketUtils.setCssStyle(owner, "text-decoration: line-through;");
					WicketUtils.setHtmlTooltip(owner,  MessageFormat.format(getString("gb.failedToFindAccount"), ownername));
					item.add(owner);
				}
				counter++;
				item.add(new Label("comma", ",").setVisible(counter < owners.size()));
				item.setRenderBodyOnly(true);
			}
		};
		ownersView.setRenderBodyOnly(true);
		add(ownersView);

		add(WicketUtils.createTimestampLabel("repositoryLastChange",
				JGitUtils.getLastChange(r).when, getTimeZone(), getTimeUtils()));
		add(new Label("repositorySize", getRepositoryModel().size));
		if (metricsTotal == null) {
			add(new Label("branchStats", ""));
		} else {
			add(new Label("branchStats",
					MessageFormat.format(getString("gb.branchStats"), metricsTotal.count,
							metricsTotal.tag, getTimeUtils().duration(metricsTotal.duration))));
		}
		add(new BookmarkablePageLink<Void>("metrics", MetricsPage.class,
				WicketUtils.newRepositoryParameter(repositoryName)));

		add(new RepositoryUrlPanel("repositoryUrlPanel", false, user, model));

		add(new LogPanel("commitsPanel", repositoryName, getRepositoryModel().HEAD, r, numberCommits, 0, getRepositoryModel().showRemoteBranches));
		add(new TagsPanel("tagsPanel", repositoryName, r, numberRefs).hideIfEmpty());
		add(new BranchesPanel("branchesPanel", getRepositoryModel(), r, numberRefs, false).hideIfEmpty());

		if (app().settings().getBoolean(Keys.web.summaryShowReadme, false)) {
			// show a readme on the summary page
			MarkupDocument markupDoc = null;
			RevCommit head = JGitUtils.getCommit(r, null);
			if (head != null) {
				MarkupProcessor processor = new MarkupProcessor(app().settings(), app().xssFilter());
				markupDoc = processor.getReadme(r, repositoryName, getBestCommitId(head));
			}
			if (markupDoc == null || markupDoc.markup == null) {
				add(new Label("readme").setVisible(false));
			} else {
				Fragment fragment = new Fragment("readme", MarkupSyntax.PLAIN.equals(markupDoc.syntax) ? "plaintextPanel" : "markdownPanel", this);
				fragment.add(new Label("readmeFile", markupDoc.documentPath));
				// Add the html to the page
				Component content = new Label("readmeContent", markupDoc.html).setEscapeModelStrings(false);
				fragment.add(content.setVisible(!StringUtils.isEmpty(markupDoc.html)));
				add(fragment);
			}
		} else {
			// global, no readme on summary page
			add(new Label("readme").setVisible(false));
		}

		if (metrics == null || metrics.isEmpty()) {
			add(new Label("commitsChart").setVisible(false));
		} else {
			Charts charts = createCharts(metrics);
			add(new HeaderContributor(charts));
		}
	}

	@Override
	protected String getPageName() {
		return getString("gb.summary");
	}

	private Charts createCharts(List<Metric> metrics) {

		Charts charts = new Flotr2Charts();

		SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd");
		String displayFormat = "MMM dd";
		if(metrics.size() > 0 && metrics.get(0).name.length() == 7){
			df = new SimpleDateFormat("yyyy-MM");
			displayFormat = "yyyy MMM";
		}
		df.setTimeZone(getTimeZone());

		// build google charts
		Chart chart = charts.createLineChart("commitsChart", getString("gb.activity"), "day", getString("gb.commits"));
		chart.setDateFormat(displayFormat);

		for (Metric metric : metrics) {
			Date date;
			try {
				date = df.parse(metric.name);
			} catch (ParseException e) {
				logger().error("Unable to parse date: " + metric.name);
				return charts;
			}
			chart.addValue(date, (int)metric.count);
			if(metric.tag > 0 ){
				chart.addHighlight(date, (int)metric.count);
			}
		}
		charts.addChart(chart);

		return charts;
	}

}
