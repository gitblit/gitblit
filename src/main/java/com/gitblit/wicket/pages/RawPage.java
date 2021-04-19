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

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import org.apache.wicket.IRequestTarget;
import org.apache.wicket.PageParameters;
import org.apache.wicket.RequestCycle;
import org.apache.wicket.protocol.http.WebRequest;
import org.apache.wicket.protocol.http.WebResponse;
import org.apache.wicket.protocol.http.servlet.AbortWithWebErrorCodeException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gitblit.Keys;
import com.gitblit.models.RepositoryModel;
import com.gitblit.models.UserModel;
import com.gitblit.utils.JGitUtils;
import com.gitblit.utils.StringUtils;
import com.gitblit.wicket.GitBlitWebSession;
import com.gitblit.wicket.WicketUtils;

public class RawPage extends SessionPage {

	private transient Logger logger;

	String contentType;

	public RawPage(final PageParameters params) {
		super(params);

		if (!params.containsKey("r")) {
			error(getString("gb.repositoryNotSpecified"));
			redirectToInterceptPage(new RepositoriesPage());
		}

		getRequestCycle().setRequestTarget(new IRequestTarget() {
			@Override
			public void detach(RequestCycle requestCycle) {
			}

			@Override
			public void respond(RequestCycle requestCycle) {
				WebResponse response = (WebResponse) requestCycle.getResponse();

				final String repositoryName = WicketUtils.getRepositoryName(params);
				final String objectId = WicketUtils.getObject(params);
				final String blobPath = WicketUtils.getPath(params);

				String[] encodings = getEncodings();
				GitBlitWebSession session = GitBlitWebSession.get();
				UserModel user = session.getUser();

				RepositoryModel model = app().repositories().getRepositoryModel(user, repositoryName);
				if (model == null) {
					// user does not have permission
					error(getString("gb.canNotLoadRepository") + " " + repositoryName);
					redirectToInterceptPage(new RepositoriesPage());
					return;
				}

				Repository r = app().repositories().getRepository(repositoryName);
				if (r == null) {
					error(getString("gb.canNotLoadRepository") + " " + repositoryName);
					redirectToInterceptPage(new RepositoriesPage());
					return;
				}

				if (StringUtils.isEmpty(blobPath)) {
					// objectid referenced raw view
					byte [] binary = JGitUtils.getByteContent(r, objectId);
					if (binary == null) {
						final String objectNotFound = MessageFormat.format("Raw page failed to find object {0} in {1}",
								objectId, repositoryName);
						logger().error(objectNotFound);
						throw new AbortWithWebErrorCodeException(HttpServletResponse.SC_NOT_FOUND, objectNotFound);
					}
					contentType = "application/octet-stream";
					response.setContentType(contentType);
					response.setContentLength(binary.length);
					try {
						response.getOutputStream().write(binary);
					} catch (Exception e) {
						logger().error("Failed to write binary response", e);
					}
				} else {
					// standard raw blob view
					RevCommit commit = JGitUtils.getCommit(r, objectId);
					if (commit == null) {
						final String commitNotFound = MessageFormat.format("Raw page failed to find commit {0} in {1}",
								objectId, repositoryName);
						logger().error(commitNotFound);
						throw new AbortWithWebErrorCodeException(HttpServletResponse.SC_NOT_FOUND, commitNotFound);
					}

					String filename = blobPath;
					if (blobPath.indexOf('/') > -1) {
						filename = blobPath.substring(blobPath.lastIndexOf('/') + 1);
					}

					String extension = null;
					if (blobPath.lastIndexOf('.') > -1) {
						extension = blobPath.substring(blobPath.lastIndexOf('.') + 1);
					}

					// Map the extensions to types
					Map<String, Integer> map = new HashMap<String, Integer>();
					for (String ext : app().settings().getStrings(Keys.web.imageExtensions)) {
						map.put(ext.toLowerCase(), 2);
					}
					for (String ext : app().settings().getStrings(Keys.web.binaryExtensions)) {
						map.put(ext.toLowerCase(), 3);
					}

					final String blobNotFound = MessageFormat.format("Raw page failed to find blob {0} in {1} @ {2}",
							blobPath, repositoryName, objectId);

					if (extension != null) {
						int type = 0;
						if (map.containsKey(extension)) {
							type = map.get(extension);
						}
						switch (type) {
						case 2:
							// image blobs
							byte[] image = JGitUtils.getByteContent(r, commit.getTree(), blobPath, true);
							if (image == null) {
								logger().error(blobNotFound);
								throw new AbortWithWebErrorCodeException(HttpServletResponse.SC_NOT_FOUND, blobNotFound);
							}
							contentType = "image/" + extension.toLowerCase();
							response.setContentType(contentType);
							response.setContentLength(image.length);
							try {
								response.getOutputStream().write(image);
							} catch (IOException e) {
								logger().error("Failed to write image response", e);
							}
							break;
						case 3:
							// binary blobs (download)
							byte[] binary = JGitUtils.getByteContent(r, commit.getTree(), blobPath, true);
							if (binary == null) {
								logger().error(blobNotFound);
								throw new AbortWithWebErrorCodeException(HttpServletResponse.SC_NOT_FOUND, blobNotFound);
							}
							contentType = "application/octet-stream";
							response.setContentLength(binary.length);
							response.setContentType(contentType);

						    try {
						    	WebRequest request = (WebRequest) requestCycle.getRequest();
						    	String userAgent = request.getHttpServletRequest().getHeader("User-Agent");

								if (userAgent != null && userAgent.indexOf("MSIE 5.5") > -1) {
								      response.setHeader("Content-Disposition", "filename=\""
								    		  +  URLEncoder.encode(filename, "UTF-8") + "\"");
								} else if (userAgent != null && userAgent.indexOf("MSIE") > -1) {
								      response.setHeader("Content-Disposition", "attachment; filename=\""
								    		  +  URLEncoder.encode(filename, "UTF-8") + "\"");
								} else {
										response.setHeader("Content-Disposition", "attachment; filename=\""
										      + new String(filename.getBytes("UTF-8"), "latin1") + "\"");
								}
							}
							catch (UnsupportedEncodingException e) {
								response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
							}

							try {
								response.getOutputStream().write(binary);
							} catch (IOException e) {
								logger().error("Failed to write binary response", e);
							}
							break;
						default:
							// plain text
							String content = JGitUtils.getStringContent(r, commit.getTree(),
									blobPath, encodings);
							if (content == null) {
								logger().error(blobNotFound);
								throw new AbortWithWebErrorCodeException(HttpServletResponse.SC_NOT_FOUND, blobNotFound);
							}
							contentType = "text/plain; charset=UTF-8";
							response.setContentType(contentType);
							try {
								response.getOutputStream().write(content.getBytes("UTF-8"));
							} catch (Exception e) {
								logger().error("Failed to write text response", e);
							}
						}

					} else {
						// plain text
						String content = JGitUtils.getStringContent(r, commit.getTree(), blobPath,
								encodings);
						if (content == null) {
							logger().error(blobNotFound);
							throw new AbortWithWebErrorCodeException(HttpServletResponse.SC_NOT_FOUND, blobNotFound);
						}
						contentType = "text/plain; charset=UTF-8";
						response.setContentType(contentType);
						try {
							response.getOutputStream().write(content.getBytes("UTF-8"));
						} catch (Exception e) {
							logger().error("Failed to write text response", e);
						}
					}
				}
				r.close();
			}
		});
	}

	protected Logger logger() {
		if (logger == null) {
			logger = LoggerFactory.getLogger(getClass());
		}
		return logger;
	}

	@Override
	protected void setHeaders(WebResponse response) {
		super.setHeaders(response);
		if (!StringUtils.isEmpty(contentType)) {
			response.setContentType(contentType);
		}
	}
}
