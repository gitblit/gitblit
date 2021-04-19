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
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

import org.apache.wicket.PageParameters;
import org.apache.wicket.behavior.HeaderContributor;
import org.apache.wicket.markup.html.basic.Label;
import org.eclipse.jgit.lib.Repository;

import com.gitblit.models.Metric;
import com.gitblit.utils.MetricUtils;
import com.gitblit.utils.StringUtils;
import com.gitblit.wicket.CacheControl;
import com.gitblit.wicket.CacheControl.LastModified;
import com.gitblit.wicket.WicketUtils;
import com.gitblit.wicket.charting.Chart;
import com.gitblit.wicket.charting.Charts;
import com.gitblit.wicket.charting.Flotr2Charts;

@CacheControl(LastModified.REPOSITORY)
public class MetricsPage extends RepositoryPage {

	public MetricsPage(PageParameters params) {
		super(params);
		Repository r = getRepository();
		if (StringUtils.isEmpty(objectId)) {
			add(new Label("branchTitle", getRepositoryModel().HEAD));
		} else {
			add(new Label("branchTitle", objectId));
		}
		Metric metricsTotal = null;
		List<Metric> metrics = MetricUtils.getDateMetrics(r, objectId, true, null, getTimeZone());
		metricsTotal = metrics.remove(0);
		if (metricsTotal == null) {
			add(new Label("branchStats", ""));
		} else {
			add(new Label("branchStats",
					MessageFormat.format(getString("gb.branchStats"), metricsTotal.count,
							metricsTotal.tag, getTimeUtils().duration(metricsTotal.duration))));
		}

		Charts charts =  new Flotr2Charts();

		add(WicketUtils.newBlankImage("commitsChart"));
		add(WicketUtils.newBlankImage("dayOfWeekChart"));
		add(WicketUtils.newBlankImage("authorsChart"));

		createLineChart(charts, "commitsChart", metrics);
		createBarChart(charts, "dayOfWeekChart", getDayOfWeekMetrics(r, objectId));
		createPieChart(charts, "authorsChart", getAuthorMetrics(r, objectId));

		add(new HeaderContributor(charts));

	}

	private void createLineChart(Charts charts, String id, List<Metric> metrics) {
		if ((metrics != null) && (metrics.size() > 0)) {

			Chart chart = charts.createLineChart(id, "", "day",
					getString("gb.commits"));
			SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd");
			String displayFormat = "MMM dd";
			if(metrics.size() > 0 && metrics.get(0).name.length() == 7){
				df = new SimpleDateFormat("yyyy-MM");
				displayFormat = "yyyy MMM";
			}
			df.setTimeZone(getTimeZone());
			chart.setDateFormat(displayFormat);
			for (Metric metric : metrics) {
				Date date;
				try {
					date = df.parse(metric.name);
				} catch (ParseException e) {
					logger().error("Unable to parse date: " + metric.name);
					return;
				}
				chart.addValue(date, (int)metric.count);
				if(metric.tag > 0 ){
					chart.addHighlight(date, (int)metric.count);
				}
			}
			charts.addChart(chart);
		}
	}

	private void createPieChart(Charts charts, String id, List<Metric> metrics) {
		if ((metrics != null) && (metrics.size() > 0)) {

			Chart chart = charts.createPieChart(id, "", "day",
					getString("gb.commits"));
			for (Metric metric : metrics) {
				chart.addValue(metric.name, (int)metric.count);
			}
			charts.addChart(chart);
		}
	}

	private void createBarChart(Charts charts, String id, List<Metric> metrics) {
		if ((metrics != null) && (metrics.size() > 0)) {
			Chart chart = charts.createBarChart(id, "", "day",
					getString("gb.commits"));
			for (Metric metric : metrics) {
				chart.addValue(metric.name, (int)metric.count);
			}
			charts.addChart(chart);
		}
	}

	private List<Metric> getDayOfWeekMetrics(Repository repository, String objectId) {
		List<Metric> list = MetricUtils.getDateMetrics(repository, objectId, false, "E", getTimeZone());
		SimpleDateFormat sdf = new SimpleDateFormat("E");
		Calendar cal = Calendar.getInstance();

		List<Metric> sorted = new ArrayList<Metric>();
		int firstDayOfWeek = cal.getFirstDayOfWeek();
		int dayOfWeek = cal.get(Calendar.DAY_OF_WEEK);

		// rewind date to first day of week
		cal.add(Calendar.DATE, firstDayOfWeek - dayOfWeek);
		for (int i = 0; i < 7; i++) {
			String day = sdf.format(cal.getTime());
			for (Metric metric : list) {
				if (metric.name.equals(day)) {
					sorted.add(metric);
					list.remove(metric);
					break;
				}
			}
			cal.add(Calendar.DATE, 1);
		}
		return sorted;
	}

	private List<Metric> getAuthorMetrics(Repository repository, String objectId) {
		List<Metric> authors = MetricUtils.getAuthorMetrics(repository, objectId, true);
		Collections.sort(authors, new Comparator<Metric>() {
			@Override
			public int compare(Metric o1, Metric o2) {
				if (o1.count > o2.count) {
					return -1;
				} else if (o1.count < o2.count) {
					return 1;
				}
				return 0;
			}
		});
		if (authors.size() > 10) {
			return authors.subList(0, 9);
		}
		return authors;
	}

	@Override
	protected String getPageName() {
		return getString("gb.metrics");
	}

	@Override
	protected boolean isCommitPage() {
		return true;
	}

	@Override
	protected Class<? extends BasePage> getRepoNavPageClass() {
		return SummaryPage.class;
	}
}
