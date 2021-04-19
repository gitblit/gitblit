/*
 * Copyright 2016 gitblit.com.
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


import java.io.IOException;
import java.text.MessageFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.wicket.PageParameters;
import org.apache.wicket.markup.html.basic.Label;
import org.apache.wicket.markup.html.form.Form;
import org.apache.wicket.markup.html.form.TextArea;
import org.apache.wicket.markup.html.link.BookmarkablePageLink;
import org.apache.wicket.markup.html.link.ExternalLink;
import org.apache.wicket.markup.html.panel.Fragment;
import org.apache.wicket.model.Model;
import org.eclipse.jgit.api.errors.ConcurrentRefUpdateException;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;

import com.gitblit.Constants;
import com.gitblit.models.UserModel;
import com.gitblit.servlet.RawServlet;
import com.gitblit.utils.BugtraqProcessor;
import com.gitblit.utils.JGitUtils;
import com.gitblit.utils.StringUtils;
import com.gitblit.wicket.CacheControl;
import com.gitblit.wicket.GitBlitWebSession;
import com.gitblit.wicket.CacheControl.LastModified;
import com.gitblit.wicket.MarkupProcessor;
import com.gitblit.wicket.MarkupProcessor.MarkupDocument;
import com.gitblit.wicket.WicketUtils;

@CacheControl(LastModified.REPOSITORY)
public class EditFilePage extends RepositoryPage {

	public EditFilePage(final PageParameters params) {
		super(params);

		final UserModel currentUser = (GitBlitWebSession.get().getUser() != null) ? GitBlitWebSession.get().getUser() : UserModel.ANONYMOUS;
		
		final String path = WicketUtils.getPath(params).replace("%2f", "/").replace("%2F", "/");
		MarkupProcessor processor = new MarkupProcessor(app().settings(), app().xssFilter());

		Repository r = getRepository();
		RevCommit commit = JGitUtils.getCommit(r, objectId);
		String [] encodings = getEncodings();

		// Read raw markup content and transform it to html
		String documentPath = path;
		String markupText = JGitUtils.getStringContent(r, commit.getTree(), path, encodings);

		// Hunt for document
		if (StringUtils.isEmpty(markupText)) {
			String name = StringUtils.stripFileExtension(path);

			List<String> docExtensions = processor.getAllExtensions();
			for (String ext : docExtensions) {
				String checkName = name + "." + ext;
				markupText = JGitUtils.getStringContent(r, commit.getTree(), checkName, encodings);
				if (!StringUtils.isEmpty(markupText)) {
					// found it
					documentPath = path;
					break;
				}
			}
		}

		if (markupText == null) {
			markupText = "";
		}

		BugtraqProcessor bugtraq = new BugtraqProcessor(app().settings());
		markupText = bugtraq.processText(getRepository(), repositoryName, markupText);

		Fragment fragment;
		String displayedCommitId = commit.getId().getName();

		if (currentUser.canEdit(getRepositoryModel()) && JGitUtils.isTip(getRepository(), objectId.toString())) {
			
			final Model<String> documentContent = new Model<String>(markupText);
			final Model<String> commitMessage = new Model<String>("Document update");
			final Model<String> commitIdAtLoad = new Model<String>(displayedCommitId);
			
			fragment = new Fragment("doc", "markupContent", this);
			
			Form<Void> form = new Form<Void>("documentEditor") {
				
				private static final long serialVersionUID = 1L;

				@Override
				protected void onSubmit() {
					final Repository repository = getRepository();
					final String document = documentContent.getObject();
					final String message = commitMessage.getObject();
					
					final String branchName = JGitUtils.getBranch(getRepository(), objectId).getName();
					final String authorEmail = StringUtils.isEmpty(currentUser.emailAddress) ? (currentUser.username + "@gitblit") : currentUser.emailAddress;
					
					boolean success = false;

					try {			
						ObjectId docAtLoad = getRepository().resolve(commitIdAtLoad.getObject());
						
						logger().trace("Commiting Edit File page: " + commitIdAtLoad.getObject());
						
						DirCache index = DirCache.newInCore();
						DirCacheBuilder builder = index.builder();
						byte[] bytes = document.getBytes( Constants.ENCODING );
						
						final DirCacheEntry fileUpdate = new DirCacheEntry(path);
						fileUpdate.setLength(bytes.length);
						fileUpdate.setLastModified(System.currentTimeMillis());
						fileUpdate.setFileMode(FileMode.REGULAR_FILE);
						fileUpdate.setObjectId(repository.newObjectInserter().insert( org.eclipse.jgit.lib.Constants.OBJ_BLOB, bytes ));
						builder.add(fileUpdate);
						
						Set<String> ignorePaths = new HashSet<String>();
						ignorePaths.add(path);

						for (DirCacheEntry entry : JGitUtils.getTreeEntries(repository, branchName, ignorePaths)) {
							builder.add(entry);
						}
						
						builder.finish();
						
						final boolean forceCommit = false;
						
						success = JGitUtils.commitIndex(repository,  branchName,  index, docAtLoad, forceCommit, currentUser.getDisplayName(), authorEmail, message);
						
					} catch (IOException | ConcurrentRefUpdateException e) {
						e.printStackTrace();
					}
				
					if (success == false) {
						getSession().error(MessageFormat.format(getString("gb.fileNotMergeable"),path));
						return;
					}
					
					getSession().info(MessageFormat.format(getString("gb.fileCommitted"),path));
					setResponsePage(EditFilePage.class, params);
				}
			};

			final TextArea<String> docIO = new TextArea<String>("content", documentContent);
			docIO.setOutputMarkupId(false);
			
			form.add(new Label("commitAuthor", String.format("%s <%s>", currentUser.getDisplayName(), currentUser.emailAddress)));
			form.add(new TextArea<String>("commitMessage", commitMessage));
			
			
			form.setOutputMarkupId(false);
			form.add(docIO);
			
			addBottomScriptInline("attachDocumentEditor(document.querySelector('textarea#editor'), $('#commitDialog'));");
			
	        fragment.add(form);
	        
		} else {
			
			MarkupDocument markupDoc = processor.parse(repositoryName, displayedCommitId, documentPath, markupText);
			final Model<String> documentContent = new Model<String>(markupDoc.html);
			
			fragment = new Fragment("doc", "plainContent", this);
			
			fragment.add(new Label("content", documentContent).setEscapeModelStrings(false));
		}

		// document page links
		fragment.add(new BookmarkablePageLink<Void>("blameLink", BlamePage.class,
				WicketUtils.newPathParameter(repositoryName, objectId, documentPath)));
		fragment.add(new BookmarkablePageLink<Void>("historyLink", HistoryPage.class,
				WicketUtils.newPathParameter(repositoryName, objectId, documentPath)));
		String rawUrl = RawServlet.asLink(getContextUrl(), repositoryName, objectId, documentPath);
		fragment.add(new ExternalLink("rawLink", rawUrl));

		add(fragment);
     
	}

	@Override
	protected String getPageName() {
		return getString("gb.editFile");
	}

	@Override
	protected boolean isCommitPage() {
		return true;
	}

	@Override
	protected Class<? extends BasePage> getRepoNavPageClass() {
		return EditFilePage.class;
	}
	
	

}
