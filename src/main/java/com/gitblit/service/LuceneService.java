/*
 * Copyright 2012 gitblit.com.
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
package com.gitblit.service;

import static org.eclipse.jgit.treewalk.filter.TreeFilter.ANY_DIFF;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.text.MessageFormat;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.DateTools;
import org.apache.lucene.document.DateTools.Resolution;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexWriterConfig.OpenMode;
import org.apache.lucene.index.MultiReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopScoreDocCollector;
import org.apache.lucene.search.highlight.Fragmenter;
import org.apache.lucene.search.highlight.Highlighter;
import org.apache.lucene.search.highlight.InvalidTokenOffsetsException;
import org.apache.lucene.search.highlight.QueryScorer;
import org.apache.lucene.search.highlight.SimpleHTMLFormatter;
import org.apache.lucene.search.highlight.SimpleSpanFragmenter;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.eclipse.jgit.diff.DiffEntry.ChangeType;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryCache.FileKey;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.FS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gitblit.Constants.SearchObjectType;
import com.gitblit.IStoredSettings;
import com.gitblit.Keys;
import com.gitblit.manager.IRepositoryManager;
import com.gitblit.models.PathModel.PathChangeModel;
import com.gitblit.models.RefModel;
import com.gitblit.models.RepositoryModel;
import com.gitblit.models.SearchResult;
import com.gitblit.utils.ArrayUtils;
import com.gitblit.utils.JGitUtils;
import static com.gitblit.utils.JGitUtils.getDefaultBranch;
import com.gitblit.utils.StringUtils;
import java.io.ByteArrayInputStream;
import java.util.logging.Level;
import org.eclipse.jgit.lib.ObjectStream;
import org.eclipse.jgit.revwalk.RevBlob;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;

/**
 * The Lucene service handles indexing and searching repositories.
 *
 * @author James Moger
 *
 */
public class LuceneService implements Runnable {

    public interface Indexer {

        boolean index(String name, String content);
    }

    private static final int INDEX_VERSION = 6;

    private static final String FIELD_OBJECT_TYPE = "type";
    private static final String FIELD_PATH = "path";
    private static final String FIELD_COMMIT = "commit";
    private static final String FIELD_BRANCH = "branch";
    private static final String FIELD_ARCHIVE = "archive";
    private static final String FIELD_SUMMARY = "summary";
    private static final String FIELD_CONTENT = "content";
    private static final String FIELD_AUTHOR = "author";
    private static final String FIELD_COMMITTER = "committer";
    private static final String FIELD_DATE = "date";
    private static final String FIELD_TAG = "tag";

    private static final String CONF_ALIAS = "aliases";
    private static final String CONF_BRANCH = "branches";

    private final Logger logger = LoggerFactory.getLogger(LuceneService.class);

    private final IStoredSettings storedSettings;
    private final IRepositoryManager repositoryManager;
    private final File repositoriesFolder;

    private final Map<String, IndexSearcher> searchers = new ConcurrentHashMap<String, IndexSearcher>();
    private final Map<String, IndexWriter> writers = new ConcurrentHashMap<String, IndexWriter>();

    private final String luceneIgnoreExtensions = "7z arc arj bin bmp dll doc docx exe gif gz jar jpg lib lzh odg odf odt pdf ppt png so swf xcf xls xlsx zip";
    private final String tikaUseExtensions = "pdf doc xls xlsx docx";
    private Set<String> excludedExtensions;
    private Set<String> tikaExtensions;
    private boolean defaultAndOperator = false;

    public LuceneService(
            IStoredSettings settings,
            IRepositoryManager repositoryManager) {

        this.storedSettings = settings;
        this.repositoryManager = repositoryManager;
        this.repositoriesFolder = repositoryManager.getRepositoriesFolder();
        String exts = luceneIgnoreExtensions;
        String tikaExts = tikaUseExtensions;
        if (settings != null) {
            exts = settings.getString(Keys.web.luceneIgnoreExtensions, exts);
            tikaExts = settings.getString(Keys.web.tikaExtensions, tikaExts);
            defaultAndOperator = settings.getBoolean(Keys.web.luceneDefaultOperatorAnd, false);
        }
        excludedExtensions = new TreeSet<String>(StringUtils.getStringsFromValue(exts));
        tikaExtensions = new TreeSet<String>(StringUtils.getStringsFromValue(tikaExts));
    }

    /**
     * Run is executed by the Gitblit executor service. Because this is called
     * by an executor service, calls will queue - i.e. there can never be
     * concurrent execution of repository index updates.
     */
    @Override
    public void run() {
        if (!storedSettings.getBoolean(Keys.web.allowLuceneIndexing, true)) {
            // Lucene indexing is disabled
            return;
        }
        // reload the excluded extensions
        String exts = storedSettings.getString(Keys.web.luceneIgnoreExtensions, luceneIgnoreExtensions);
        excludedExtensions = new TreeSet<String>(StringUtils.getStringsFromValue(exts));

        if (repositoryManager.isCollectingGarbage()) {
            // busy collecting garbage, try again later
            return;
        }

        for (String repositoryName : repositoryManager.getRepositoryList()) {
            RepositoryModel model = repositoryManager.getRepositoryModel(repositoryName);
            if (model.hasCommits && !ArrayUtils.isEmpty(model.indexedBranches)) {
                Repository repository = repositoryManager.getRepository(model.name);
                if (repository == null) {
                    if (repositoryManager.isCollectingGarbage(model.name)) {
                        logger.info(MessageFormat.format("Skipping Lucene index of {0}, busy garbage collecting", repositoryName));
                    }
                    continue;
                }
                index(model, repository);
                repository.close();
                System.gc();
            }
        }
    }

    /**
     * Synchronously indexes a repository. This may build a complete index of a
     * repository or it may update an existing index.
     *
     * @param displayName the name of the repository
     * @param repository the repository object
     */
    private void index(RepositoryModel model, Repository repository) {
        try {
            if (shouldReindex(repository)) {
                // (re)build the entire index
                IndexResult result = reindex(model, repository);

                if (result.success) {
                    if (result.commitCount > 0) {
                        String msg = "Built {0} Lucene index from {1} commits and {2} files across {3} branches in {4} secs";
                        logger.info(MessageFormat.format(msg, model.name, result.commitCount,
                                result.blobCount, result.branchCount, result.duration()));
                    }
                } else {
                    String msg = "Could not build {0} Lucene index!";
                    logger.error(MessageFormat.format(msg, model.name));
                }
            } else {
                // update the index with latest commits
                IndexResult result = updateIndex(model, repository);
                if (result.success) {
                    if (result.commitCount > 0) {
                        String msg = "Updated {0} Lucene index with {1} commits and {2} files across {3} branches in {4} secs";
                        logger.info(MessageFormat.format(msg, model.name, result.commitCount,
                                result.blobCount, result.branchCount, result.duration()));
                    }
                } else {
                    String msg = "Could not update {0} Lucene index!";
                    logger.error(MessageFormat.format(msg, model.name));
                }
            }
        } catch (Throwable t) {
            logger.error(MessageFormat.format("Lucene indexing failure for {0}", model.name), t);
        }
    }

    /**
     * Close the writer/searcher objects for a repository.
     *
     * @param repositoryName
     */
    public synchronized void close(String repositoryName) {
        try {
            IndexSearcher searcher = searchers.remove(repositoryName);
            if (searcher != null) {
                searcher.getIndexReader().close();
            }
        } catch (Exception e) {
            logger.error("Failed to close index searcher for " + repositoryName, e);
        }

        try {
            IndexWriter writer = writers.remove(repositoryName);
            if (writer != null) {
                writer.close();
            }
        } catch (Exception e) {
            logger.error("Failed to close index writer for " + repositoryName, e);
        }
    }

    /**
     * Close all Lucene indexers.
     *
     */
    public synchronized void close() {
        // close all writers
        for (String writer : writers.keySet()) {
            try {
                writers.get(writer).close();
            } catch (Throwable t) {
                logger.error("Failed to close Lucene writer for " + writer, t);
            }
        }
        writers.clear();

        // close all searchers
        for (String searcher : searchers.keySet()) {
            try {
                searchers.get(searcher).getIndexReader().close();
            } catch (Throwable t) {
                logger.error("Failed to close Lucene searcher for " + searcher, t);
            }
        }
        searchers.clear();
    }

    /**
     * Deletes the Lucene index for the specified repository.
     *
     * @param repositoryName
     * @return true, if successful
     */
    public boolean deleteIndex(String repositoryName) {
        // close any open writer/searcher
        close(repositoryName);

        // delete the index folder
        File repositoryFolder = FileKey.resolve(new File(repositoriesFolder, repositoryName), FS.DETECTED);
        LuceneRepoIndexStore luceneIndex = new LuceneRepoIndexStore(repositoryFolder, INDEX_VERSION);
        return luceneIndex.delete();
    }

    /**
     * Returns the author for the commit, if this information is available.
     *
     * @param commit
     * @return an author or unknown
     */
    private String getAuthor(RevCommit commit) {
        String name = "unknown";
        try {
            name = commit.getAuthorIdent().getName();
            if (StringUtils.isEmpty(name)) {
                name = commit.getAuthorIdent().getEmailAddress();
            }
        } catch (NullPointerException n) {
        }
        return name;
    }

    /**
     * Returns the committer for the commit, if this information is available.
     *
     * @param commit
     * @return an committer or unknown
     */
    private String getCommitter(RevCommit commit) {
        String name = "unknown";
        try {
            name = commit.getCommitterIdent().getName();
            if (StringUtils.isEmpty(name)) {
                name = commit.getCommitterIdent().getEmailAddress();
            }
        } catch (NullPointerException n) {
        }
        return name;
    }

    /**
     * Get the tree associated with the given commit.
     *
     * @param walk
     * @param commit
     * @return tree
     * @throws IOException
     */
    private RevTree getTree(final RevWalk walk, final RevCommit commit)
            throws IOException {
        final RevTree tree = commit.getTree();
        if (tree != null) {
            return tree;
        }
        walk.parseHeaders(commit);
        return commit.getTree();
    }

    /**
     * Construct a keyname from the branch.
     *
     * @param branchName
     * @return a keyname appropriate for the Git config file format
     */
    private String getBranchKey(String branchName) {
        return StringUtils.getSHA1(branchName);
    }

    /**
     * Returns the Lucene configuration for the specified repository.
     *
     * @param repository
     * @return a config object
     */
    private FileBasedConfig getConfig(Repository repository) {
        LuceneRepoIndexStore luceneIndex = new LuceneRepoIndexStore(repository.getDirectory(), INDEX_VERSION);
        FileBasedConfig config = new FileBasedConfig(luceneIndex.getConfigFile(), FS.detect());
        return config;
    }

    /**
     * Checks if an index exists for the repository, that is compatible with
     * INDEX_VERSION and the Lucene version.
     *
     * @param repository
     * @return true if no index is found for the repository, false otherwise.
     */
    private boolean shouldReindex(Repository repository) {
        return !(new LuceneRepoIndexStore(repository.getDirectory(), INDEX_VERSION).hasIndex());
    }

    /**
     * This completely indexes the repository and will destroy any existing
     * index.
     *
     * @param repositoryName
     * @param repository
     * @return IndexResult
     */
    public IndexResult reindex(RepositoryModel model, Repository repository) {
        IndexResult result = new IndexResult();
        if (!deleteIndex(model.name)) {
            return result;
        }
        try {
            String[] encodings = storedSettings.getStrings(Keys.web.blobEncodings).toArray(new String[0]);
            FileBasedConfig config = getConfig(repository);
            Set<String> indexedCommits = new TreeSet<String>();
            final IndexWriter writer = getIndexWriter(model.name);
            // build a quick lookup of tags
            Map<String, List<String>> tags = new HashMap<String, List<String>>();
            for (RefModel tag : JGitUtils.getTags(repository, false, -1)) {
                if (!tag.isAnnotatedTag()) {
                    // skip non-annotated tags
                    continue;
                }
                if (!tags.containsKey(tag.getReferencedObjectId().getName())) {
                    tags.put(tag.getReferencedObjectId().getName(), new ArrayList<String>());
                }
                tags.get(tag.getReferencedObjectId().getName()).add(tag.displayName);
            }

            ObjectReader reader = repository.newObjectReader();

            // get the local branches
            List<RefModel> branches = JGitUtils.getLocalBranches(repository, true, -1);

            // sort them by most recently updated
            Collections.sort(branches, new Comparator<RefModel>() {
                @Override
                public int compare(RefModel ref1, RefModel ref2) {
                    return ref2.getDate().compareTo(ref1.getDate());
                }
            });

            // reorder default branch to first position
            RefModel defaultBranch = null;
            ObjectId defaultBranchId = JGitUtils.getDefaultBranch(repository);
            for (RefModel branch : branches) {
                if (branch.getObjectId().equals(defaultBranchId)) {
                    defaultBranch = branch;
                    break;
                }
            }
            branches.remove(defaultBranch);
            branches.add(0, defaultBranch);

            // walk through each branch
            for (RefModel branch : branches) {

                boolean indexBranch = false;
                if (model.indexedBranches.contains(com.gitblit.Constants.DEFAULT_BRANCH)
                        && branch.equals(defaultBranch)) {
                    // indexing "default" branch
                    indexBranch = true;
                } else if (branch.getName().startsWith(com.gitblit.Constants.R_META)) {
                    // skip internal meta branches
                    indexBranch = false;
                } else {
                    // normal explicit branch check
                    indexBranch = model.indexedBranches.contains(branch.getName());
                }

                // if this branch is not specifically indexed then skip
                if (!indexBranch) {
                    continue;
                }

                final String branchName = branch.getName();
                final RevWalk revWalk = new RevWalk(reader);
                final RevCommit tip = revWalk.parseCommit(branch.getObjectId());
                final String tipId = tip.getId().getName();

                String keyName = getBranchKey(branchName);
                config.setString(CONF_ALIAS, null, keyName, branchName);
                config.setString(CONF_BRANCH, null, keyName, tipId);

                // index the blob contents of the tree
                TreeWalk treeWalk = new TreeWalk(repository);
                treeWalk.addTree(tip.getTree());
                treeWalk.setRecursive(true);

                Map<String, ObjectId> paths = new TreeMap<String, ObjectId>();
                while (treeWalk.next()) {
                    // ensure path is not in a submodule
                    if (treeWalk.getFileMode(0) != FileMode.GITLINK) {
                        paths.put(treeWalk.getPathString(), treeWalk.getObjectId(0));
                    }
                }

                ByteArrayOutputStream os = new ByteArrayOutputStream();
                byte[] tmp = new byte[32767];

                RevWalk commitWalk = new RevWalk(reader);
                commitWalk.markStart(tip);

                RevCommit commit;
                while ((paths.size() > 0) && (commit = commitWalk.next()) != null) {
                    TreeWalk diffWalk = new TreeWalk(reader);
                    int parentCount = commit.getParentCount();
                    switch (parentCount) {
                        case 0:
                            diffWalk.addTree(new EmptyTreeIterator());
                            break;
                        case 1:
                            diffWalk.addTree(getTree(commitWalk, commit.getParent(0)));
                            break;
                        default:
                            // skip merge commits
                            continue;
                    }
                    diffWalk.addTree(getTree(commitWalk, commit));
                    diffWalk.setFilter(ANY_DIFF);
                    diffWalk.setRecursive(true);
                    while ((paths.size() > 0) && diffWalk.next()) {
                        final String path = diffWalk.getPathString();
                        if (!paths.containsKey(path)) {
                            continue;
                        }

                        // remove path from set
                        ObjectId blobId = paths.remove(path);
                        result.blobCount++;

                        // index the blob metadata
                        final String blobAuthor = getAuthor(commit);
                        final String blobCommitter = getCommitter(commit);
                        final String blobDate = DateTools.timeToString(commit.getCommitTime() * 1000L,
                                Resolution.MINUTE);
                        final String commitName = commit.getName();
                        Document doc = new Document();
                        doc.add(new Field(FIELD_OBJECT_TYPE, SearchObjectType.blob.name(), StringField.TYPE_STORED));
                        doc.add(new Field(FIELD_BRANCH, branchName, TextField.TYPE_STORED));
                        doc.add(new Field(FIELD_COMMIT, commitName, TextField.TYPE_STORED));
                        doc.add(new Field(FIELD_PATH, path, TextField.TYPE_STORED));
                        doc.add(new Field(FIELD_DATE, blobDate, StringField.TYPE_STORED));
                        doc.add(new Field(FIELD_AUTHOR, blobAuthor, TextField.TYPE_STORED));
                        doc.add(new Field(FIELD_COMMITTER, blobCommitter, TextField.TYPE_STORED));

                        // determine extension to compare to the extension
                        // blacklist
                        String ext = null;
                        String name = path.toLowerCase();
                        if (name.indexOf('.') > -1) {
                            ext = name.substring(name.lastIndexOf('.') + 1);
                        }

                        // index the blob content
                        if (StringUtils.isEmpty(ext) || !excludedExtensions.contains(ext)) {
                            boolean useTika = useTika(ext);
                            ObjectLoader ldr = repository.open(blobId, Constants.OBJ_BLOB);
                            String str;
                            if (useTika) {
                                try (InputStream is = ldr.openStream()) {
                                    str = TikaUtils.extractText(ext, name, is, this, path, new Indexer() {
                                        @Override
                                        public boolean index(String name, String content) {
                                            try {
                                                Document doc = new Document();
                                                doc.add(new Field(FIELD_OBJECT_TYPE, SearchObjectType.blob.name(), StringField.TYPE_STORED));
                                                doc.add(new Field(FIELD_BRANCH, branchName, TextField.TYPE_STORED));
                                                doc.add(new Field(FIELD_COMMIT, commitName, TextField.TYPE_STORED));
                                                doc.add(new Field(FIELD_PATH, name, TextField.TYPE_STORED));
                                                doc.add(new Field(FIELD_ARCHIVE, path, TextField.TYPE_STORED));
                                                doc.add(new Field(FIELD_DATE, blobDate, StringField.TYPE_STORED));
                                                doc.add(new Field(FIELD_AUTHOR, blobAuthor, TextField.TYPE_STORED));
                                                doc.add(new Field(FIELD_COMMITTER, blobCommitter, TextField.TYPE_STORED));
                                                doc.add(new Field(FIELD_CONTENT, content, TextField.TYPE_STORED));
                                                writer.addDocument(doc);
                                                return true;
                                            } catch (IOException ex) {
                                                java.util.logging.Logger.getLogger(LuceneService.class.getName()).log(Level.SEVERE, null, ex);
                                                return false;
                                            }
                                        }
                                    });
                                }
                            } else {
                                InputStream in = ldr.openStream();
                                int n;
                                while ((n = in.read(tmp)) > 0) {
                                    os.write(tmp, 0, n);
                                }
                                in.close();
                                byte[] content = os.toByteArray();
                                str = StringUtils.decodeString(content, encodings);
                            }
                            if (str != null) {
                                doc.add(new Field(FIELD_CONTENT, str, TextField.TYPE_STORED));
                            }
                            os.reset();
                        }

                        // add the blob to the index
                        writer.addDocument(doc);
                    }
                }

                os.close();

                // index the tip commit object
                if (indexedCommits.add(tipId)) {
                    Document doc = createDocument(tip, tags.get(tipId));
                    doc.add(new Field(FIELD_BRANCH, branchName, TextField.TYPE_STORED));
                    writer.addDocument(doc);
                    result.commitCount += 1;
                    result.branchCount += 1;
                }

                // traverse the log and index the previous commit objects
                RevWalk historyWalk = new RevWalk(reader);
                historyWalk.markStart(historyWalk.parseCommit(tip.getId()));
                RevCommit rev;
                while ((rev = historyWalk.next()) != null) {
                    String hash = rev.getId().getName();
                    if (indexedCommits.add(hash)) {
                        Document doc = createDocument(rev, tags.get(hash));
                        doc.add(new Field(FIELD_BRANCH, branchName, TextField.TYPE_STORED));
                        writer.addDocument(doc);
                        result.commitCount += 1;
                    }
                }
            }

            // finished
            reader.close();

            // commit all changes and reset the searcher
            config.save();
            writer.commit();
            resetIndexSearcher(model.name);
            result.success();
        } catch (Exception e) {
            logger.error("Exception while reindexing " + model.name, e);
        }
        return result;
    }

    public String getEncodedString(byte[] content, String ext) {
        if (excludedExtensions.contains(ext)) {
            return null;
        }
        String[] encodings = storedSettings.getStrings(Keys.web.blobEncodings).toArray(new String[0]);
        return StringUtils.decodeString(content, encodings);
    }
    	
    /**
     * Incrementally update the index with the specified commit for the
     * repository.
     *
     * @param repositoryName
     * @param repository
     * @param branch the fully qualified branch name (e.g. refs/heads/master)
     * @param commit
     * @return true, if successful
     */
    private IndexResult index(final String repositoryName, final Repository repository,
            final String branch, final RevCommit commit) {
        IndexResult result = new IndexResult();
        try {
            String[] encodings = storedSettings.getStrings(Keys.web.blobEncodings).toArray(new String[0]);
            List<PathChangeModel> changedPaths = JGitUtils.getFilesInCommit(repository, commit);

            final String revDate = DateTools.timeToString(commit.getCommitTime() * 1000L,
                    Resolution.MINUTE);
            final IndexWriter writer = getIndexWriter(repositoryName);
            for (PathChangeModel path : changedPaths) {
                if (path.isSubmodule()) {
                    continue;
                }
                final String spath = path.path;
                // delete the indexed blob
                deleteBlob(repositoryName, branch, path.name);

                // re-index the blob
                if (!ChangeType.DELETE.equals(path.changeType)) {
                    result.blobCount++;
                    Document doc = new Document();
                    doc.add(new Field(FIELD_OBJECT_TYPE, SearchObjectType.blob.name(), StringField.TYPE_STORED));
                    doc.add(new Field(FIELD_BRANCH, branch, TextField.TYPE_STORED));
                    doc.add(new Field(FIELD_COMMIT, commit.getName(), TextField.TYPE_STORED));
                    doc.add(new Field(FIELD_PATH, path.path, TextField.TYPE_STORED));
                    doc.add(new Field(FIELD_DATE, revDate, StringField.TYPE_STORED));
                    doc.add(new Field(FIELD_AUTHOR, getAuthor(commit), TextField.TYPE_STORED));
                    doc.add(new Field(FIELD_COMMITTER, getCommitter(commit), TextField.TYPE_STORED));

                    // determine extension to compare to the extension
                    // blacklist
                    String ext = null;
                    String name = path.name.toLowerCase();
                    if (name.indexOf('.') > -1) {
                        ext = name.substring(name.lastIndexOf('.') + 1);
                    }
                    if (StringUtils.isEmpty(ext) || !excludedExtensions.contains(ext)) {
                        boolean useTika = useTika(ext);
                        // read the blob content
                        String str;
                        if (useTika) {
                            RevWalk rw = new RevWalk(repository);
                            RevBlob blob = rw.lookupBlob(ObjectId.fromString(path.objectId));
                            ObjectLoader ldr = repository.open(blob.getId(), Constants.OBJ_BLOB);
                            try (ObjectStream is = ldr.openStream()) {
                            str = TikaUtils.extractText(ext, name,is , this, spath, new Indexer() {
                                @Override
                                public boolean index(String name, String content) {
                                    try {
                                        Document doc = new Document();
                                        doc.add(new Field(FIELD_OBJECT_TYPE, SearchObjectType.blob.name(), StringField.TYPE_STORED));
                                        doc.add(new Field(FIELD_BRANCH, branch, TextField.TYPE_STORED));
                                        doc.add(new Field(FIELD_COMMIT, commit.getName(), TextField.TYPE_STORED));
                                        doc.add(new Field(FIELD_PATH, name, TextField.TYPE_STORED));
                                        doc.add(new Field(FIELD_ARCHIVE, spath, TextField.TYPE_STORED));
                                        doc.add(new Field(FIELD_DATE, revDate, StringField.TYPE_STORED));
                                        doc.add(new Field(FIELD_AUTHOR, getAuthor(commit), TextField.TYPE_STORED));
                                        doc.add(new Field(FIELD_COMMITTER, getCommitter(commit), TextField.TYPE_STORED));
                                        doc.add(new Field(FIELD_CONTENT, content, TextField.TYPE_STORED));
                                        writer.addDocument(doc);
                                        return true;
                                    } catch (IOException ex) {
                                        java.util.logging.Logger.getLogger(LuceneService.class.getName()).log(Level.SEVERE, null, ex);
                                        return false;
                                    }
                                }
                            });
                            }                            
                           rw.dispose();
                        } else {
                            str = JGitUtils.getStringContent(repository, commit.getTree(),
                                    path.path, encodings);
                        }
                        if (str != null) {
                            doc.add(new Field(FIELD_CONTENT, str, TextField.TYPE_STORED));
                            writer.addDocument(doc);
                        }
                    }
                }
            }
            writer.commit();

            // get any annotated commit tags
            List<String> commitTags = new ArrayList<String>();
            for (RefModel ref : JGitUtils.getTags(repository, false, -1)) {
                if (ref.isAnnotatedTag() && ref.getReferencedObjectId().equals(commit.getId())) {
                    commitTags.add(ref.displayName);
                }
            }

            // create and write the Lucene document
            Document doc = createDocument(commit, commitTags);
            doc.add(new Field(FIELD_BRANCH, branch, TextField.TYPE_STORED));
            result.commitCount++;
            result.success = index(repositoryName, doc);
        } catch (Exception e) {
            logger.error(MessageFormat.format("Exception while indexing commit {0} in {1}", commit.getId().getName(), repositoryName), e);
        }
        return result;
    }

    protected boolean useTika(String ext) {
        return tikaExtensions != null && ext != null && tikaExtensions.contains(ext);
    }

    /**
     * Delete a blob from the specified branch of the repository index.
     *
     * @param repositoryName
     * @param branch
     * @param path
     * @throws Exception
     * @return true, if deleted, false if no record was deleted
     */
    public boolean deleteBlob(String repositoryName, String branch, String path) throws Exception {
        String pattern = MessageFormat.format("{0}:'{'0} AND {1}:\"'{'1'}'\" AND {2}:\"'{'2'}'\"", FIELD_OBJECT_TYPE, FIELD_BRANCH, FIELD_PATH);
        String q = MessageFormat.format(pattern, SearchObjectType.blob.name(), branch, path);

        StandardAnalyzer analyzer = new StandardAnalyzer();
        QueryParser qp = new QueryParser(FIELD_SUMMARY, analyzer);
        if (defaultAndOperator) {
            qp.setDefaultOperator(QueryParser.Operator.AND);
        }
        BooleanQuery query = new BooleanQuery.Builder().add(qp.parse(q), Occur.MUST).build();

        IndexWriter writer = getIndexWriter(repositoryName);
        int numDocsBefore = writer.numDocs();
        writer.deleteDocuments(query);
        writer.commit();
        int numDocsAfter = writer.numDocs();
        if (numDocsBefore == numDocsAfter) {
            logger.debug(MessageFormat.format("no records found to delete {0}", query.toString()));
            return false;
        } else {
            logger.debug(MessageFormat.format("deleted {0} records with {1}", numDocsBefore - numDocsAfter, query.toString()));
            return true;
        }
    }

    /**
     * Updates a repository index incrementally from the last indexed commits.
     *
     * @param model
     * @param repository
     * @return IndexResult
     */
    private IndexResult updateIndex(RepositoryModel model, Repository repository) {
        IndexResult result = new IndexResult();
        try {
            FileBasedConfig config = getConfig(repository);
            config.load();

            // build a quick lookup of annotated tags
            Map<String, List<String>> tags = new HashMap<String, List<String>>();
            for (RefModel tag : JGitUtils.getTags(repository, false, -1)) {
                if (!tag.isAnnotatedTag()) {
                    // skip non-annotated tags
                    continue;
                }
                if (!tags.containsKey(tag.getObjectId().getName())) {
                    tags.put(tag.getReferencedObjectId().getName(), new ArrayList<String>());
                }
                tags.get(tag.getReferencedObjectId().getName()).add(tag.displayName);
            }

            // detect branch deletion
            // first assume all branches are deleted and then remove each
            // existing branch from deletedBranches during indexing
            Set<String> deletedBranches = new TreeSet<String>();
            for (String alias : config.getNames(CONF_ALIAS)) {
                String branch = config.getString(CONF_ALIAS, null, alias);
                deletedBranches.add(branch);
            }

            // get the local branches
            List<RefModel> branches = JGitUtils.getLocalBranches(repository, true, -1);

            // sort them by most recently updated
            Collections.sort(branches, new Comparator<RefModel>() {
                @Override
                public int compare(RefModel ref1, RefModel ref2) {
                    return ref2.getDate().compareTo(ref1.getDate());
                }
            });

            // reorder default branch to first position
            RefModel defaultBranch = null;
            ObjectId defaultBranchId = JGitUtils.getDefaultBranch(repository);
            for (RefModel branch : branches) {
                if (branch.getObjectId().equals(defaultBranchId)) {
                    defaultBranch = branch;
                    break;
                }
            }
            branches.remove(defaultBranch);
            branches.add(0, defaultBranch);

            // walk through each branches
            for (RefModel branch : branches) {
                String branchName = branch.getName();

                boolean indexBranch = false;
                if (model.indexedBranches.contains(com.gitblit.Constants.DEFAULT_BRANCH)
                        && branch.equals(defaultBranch)) {
                    // indexing "default" branch
                    indexBranch = true;
                } else if (branch.getName().startsWith(com.gitblit.Constants.R_META)) {
                    // ignore internal meta branches
                    indexBranch = false;
                } else {
                    // normal explicit branch check
                    indexBranch = model.indexedBranches.contains(branch.getName());
                }

                // if this branch is not specifically indexed then skip
                if (!indexBranch) {
                    continue;
                }

                // remove this branch from the deletedBranches set
                deletedBranches.remove(branchName);

                // determine last commit
                String keyName = getBranchKey(branchName);
                String lastCommit = config.getString(CONF_BRANCH, null, keyName);

                List<RevCommit> revs;
                if (StringUtils.isEmpty(lastCommit)) {
                    // new branch/unindexed branch, get all commits on branch
                    revs = JGitUtils.getRevLog(repository, branchName, 0, -1);
                } else {
                    // pre-existing branch, get changes since last commit
                    revs = JGitUtils.getRevLog(repository, lastCommit, branchName);
                }

                if (revs.size() > 0) {
                    result.branchCount += 1;
                }

                // reverse the list of commits so we start with the first commit
                Collections.reverse(revs);
                for (RevCommit commit : revs) {
                    // index a commit
                    result.add(index(model.name, repository, branchName, commit));
                }

                // update the config
                config.setString(CONF_ALIAS, null, keyName, branchName);
                config.setString(CONF_BRANCH, null, keyName, branch.getObjectId().getName());
                config.save();
            }

            // the deletedBranches set will normally be empty by this point
            // unless a branch really was deleted and no longer exists
            if (deletedBranches.size() > 0) {
                for (String branch : deletedBranches) {
                    IndexWriter writer = getIndexWriter(model.name);
                    writer.deleteDocuments(new Term(FIELD_BRANCH, branch));
                    writer.commit();
                }
            }
            result.success = true;
        } catch (Throwable t) {
            logger.error(MessageFormat.format("Exception while updating {0} Lucene index", model.name), t);
        }
        return result;
    }

    /**
     * Creates a Lucene document for a commit
     *
     * @param commit
     * @param tags
     * @return a Lucene document
     */
    private Document createDocument(RevCommit commit, List<String> tags) {
        Document doc = new Document();
        doc.add(new Field(FIELD_OBJECT_TYPE, SearchObjectType.commit.name(), StringField.TYPE_STORED));
        doc.add(new Field(FIELD_COMMIT, commit.getName(), TextField.TYPE_STORED));
        doc.add(new Field(FIELD_DATE, DateTools.timeToString(commit.getCommitTime() * 1000L,
                Resolution.MINUTE), StringField.TYPE_STORED));
        doc.add(new Field(FIELD_AUTHOR, getAuthor(commit), TextField.TYPE_STORED));
        doc.add(new Field(FIELD_COMMITTER, getCommitter(commit), TextField.TYPE_STORED));
        doc.add(new Field(FIELD_SUMMARY, commit.getShortMessage(), TextField.TYPE_STORED));
        doc.add(new Field(FIELD_CONTENT, commit.getFullMessage(), TextField.TYPE_STORED));
        if (!ArrayUtils.isEmpty(tags)) {
            doc.add(new Field(FIELD_TAG, StringUtils.flattenStrings(tags), TextField.TYPE_STORED));
        }
        return doc;
    }

    /**
     * Incrementally index an object for the repository.
     *
     * @param repositoryName
     * @param doc
     * @return true, if successful
     */
    private boolean index(String repositoryName, Document doc) {
        try {
            IndexWriter writer = getIndexWriter(repositoryName);
            writer.addDocument(doc);
            writer.commit();
            resetIndexSearcher(repositoryName);
            return true;
        } catch (Exception e) {
            logger.error(MessageFormat.format("Exception while incrementally updating {0} Lucene index", repositoryName), e);
        }
        return false;
    }

    private SearchResult createSearchResult(Document doc, float score, int hitId, int totalHits) throws ParseException {
        SearchResult result = new SearchResult();
        result.hitId = hitId;
        result.totalHits = totalHits;
        result.score = score;
        result.date = DateTools.stringToDate(doc.get(FIELD_DATE));
        result.summary = doc.get(FIELD_SUMMARY);
        result.author = doc.get(FIELD_AUTHOR);
        result.committer = doc.get(FIELD_COMMITTER);
        result.type = SearchObjectType.fromName(doc.get(FIELD_OBJECT_TYPE));
        result.branch = doc.get(FIELD_BRANCH);
        result.commitId = doc.get(FIELD_COMMIT);
        result.path = doc.get(FIELD_PATH);
        if (doc.get(FIELD_TAG) != null) {
            result.tags = StringUtils.getStringsFromValue(doc.get(FIELD_TAG));
        }
        return result;
    }

    private synchronized void resetIndexSearcher(String repository) throws IOException {
        IndexSearcher searcher = searchers.remove(repository);
        if (searcher != null) {
            searcher.getIndexReader().close();
        }
    }

    /**
     * Gets an index searcher for the repository.
     *
     * @param repository
     * @return
     * @throws IOException
     */
    private IndexSearcher getIndexSearcher(String repository) throws IOException {
        IndexSearcher searcher = searchers.get(repository);
        if (searcher == null) {
            IndexWriter writer = getIndexWriter(repository);
            searcher = new IndexSearcher(DirectoryReader.open(writer, true));
            searchers.put(repository, searcher);
        }
        return searcher;
    }

    /**
     * Gets an index writer for the repository. The index will be created if it
     * does not already exist or if forceCreate is specified.
     *
     * @param repository
     * @return an IndexWriter
     * @throws IOException
     */
    private IndexWriter getIndexWriter(String repository) throws IOException {
        IndexWriter indexWriter = writers.get(repository);
        if (indexWriter == null) {
            File repositoryFolder = FileKey.resolve(new File(repositoriesFolder, repository), FS.DETECTED);
            LuceneRepoIndexStore indexStore = new LuceneRepoIndexStore(repositoryFolder, INDEX_VERSION);
            indexStore.create();
            Directory directory = FSDirectory.open(indexStore.getPath());
            StandardAnalyzer analyzer = new StandardAnalyzer();
            IndexWriterConfig config = new IndexWriterConfig(analyzer);
            config.setOpenMode(OpenMode.CREATE_OR_APPEND);
            indexWriter = new IndexWriter(directory, config);
            writers.put(repository, indexWriter);
        }
        return indexWriter;
    }

    /**
     * Searches the specified repositories for the given text or query
     *
     * @param text if the text is null or empty, null is returned
     * @param page the page number to retrieve. page is 1-indexed.
     * @param pageSize the number of elements to return for this page
     * @param repositories a list of repositories to search. if no repositories
     * are specified null is returned.
     * @return a list of SearchResults in order from highest to the lowest score
     *
     */
    public List<SearchResult> search(String text, int page, int pageSize, List<String> repositories) {
        if (ArrayUtils.isEmpty(repositories)) {
            return null;
        }
        return search(text, page, pageSize, repositories.toArray(new String[0]));
    }

    /**
     * Searches the specified repositories for the given text or query
     *
     * @param text if the text is null or empty, null is returned
     * @param page the page number to retrieve. page is 1-indexed.
     * @param pageSize the number of elements to return for this page
     * @param repositories a list of repositories to search. if no repositories
     * are specified null is returned.
     * @return a list of SearchResults in order from highest to the lowest score
     *
     */
    public List<SearchResult> search(String text, int page, int pageSize, String... repositories) {
        if (StringUtils.isEmpty(text)) {
            return null;
        }
        if (ArrayUtils.isEmpty(repositories)) {
            return null;
        }
        Set<SearchResult> results = new LinkedHashSet<SearchResult>();
        StandardAnalyzer analyzer = new StandardAnalyzer();
        try {
            // default search checks summary and content
            BooleanQuery.Builder bldr = new BooleanQuery.Builder();
            QueryParser qp;
            qp = new QueryParser(FIELD_SUMMARY, analyzer);
            qp.setAllowLeadingWildcard(true);
            if (defaultAndOperator) {
                qp.setDefaultOperator(QueryParser.Operator.AND);
            }
            bldr.add(qp.parse(text), Occur.SHOULD);

            qp = new QueryParser(FIELD_CONTENT, analyzer);
            qp.setAllowLeadingWildcard(true);
            if (defaultAndOperator) {
                qp.setDefaultOperator(QueryParser.Operator.AND);
            }
            bldr.add(qp.parse(text), Occur.SHOULD);

            IndexSearcher searcher;
            if (repositories.length == 1) {
                // single repository search
                searcher = getIndexSearcher(repositories[0]);
            } else {
                // multiple repository search
                List<IndexReader> readers = new ArrayList<IndexReader>();
                for (String repository : repositories) {
                    IndexSearcher repositoryIndex = getIndexSearcher(repository);
                    readers.add(repositoryIndex.getIndexReader());
                }
                IndexReader[] rdrs = readers.toArray(new IndexReader[readers.size()]);
                MultiSourceReader reader = new MultiSourceReader(rdrs);
                searcher = new IndexSearcher(reader);
            }

            BooleanQuery query = bldr.build();
            Query rewrittenQuery = searcher.rewrite(query);
            logger.debug(rewrittenQuery.toString());

            TopScoreDocCollector collector = TopScoreDocCollector.create(5000);
            searcher.search(rewrittenQuery, collector);
            int offset = Math.max(0, (page - 1) * pageSize);
            ScoreDoc[] hits = collector.topDocs(offset, pageSize).scoreDocs;
            int totalHits = collector.getTotalHits();
            for (int i = 0; i < hits.length; i++) {
                int docId = hits[i].doc;
                Document doc = searcher.doc(docId);
                SearchResult result = createSearchResult(doc, hits[i].score, offset + i + 1, totalHits);
                if (repositories.length == 1) {
                    // single repository search
                    result.repository = repositories[0];
                } else {
                    // multi-repository search
                    MultiSourceReader reader = (MultiSourceReader) searcher.getIndexReader();
                    int index = reader.getSourceIndex(docId);
                    result.repository = repositories[index];
                }
                String content = doc.get(FIELD_CONTENT);
                result.fragment = getHighlightedFragment(analyzer, query, content, result);
                results.add(result);
            }
        } catch (Exception e) {
            logger.error(MessageFormat.format("Exception while searching for {0}", text), e);
        }
        return new ArrayList<SearchResult>(results);
    }

    /**
     *
     * @param analyzer
     * @param query
     * @param content
     * @param result
     * @return
     * @throws IOException
     * @throws InvalidTokenOffsetsException
     */
    private String getHighlightedFragment(Analyzer analyzer, Query query,
            String content, SearchResult result) throws IOException, InvalidTokenOffsetsException {
        if (content == null) {
            content = "";
        }

        int tabLength = storedSettings.getInteger(Keys.web.tabLength, 4);
        int fragmentLength = SearchObjectType.commit == result.type ? 512 : 150;

        QueryScorer scorer = new QueryScorer(query, "content");
        Fragmenter fragmenter = new SimpleSpanFragmenter(scorer, fragmentLength);

        // use an artificial delimiter for the token
        String termTag = "!!--[";
        String termTagEnd = "]--!!";
        SimpleHTMLFormatter formatter = new SimpleHTMLFormatter(termTag, termTagEnd);
        Highlighter highlighter = new Highlighter(formatter, scorer);
        highlighter.setTextFragmenter(fragmenter);

        String[] fragments = highlighter.getBestFragments(analyzer, "content", content, 3);
        if (ArrayUtils.isEmpty(fragments)) {
            if (SearchObjectType.blob == result.type) {
                return "";
            }
            // clip commit message
            String fragment = content;
            if (fragment.length() > fragmentLength) {
                fragment = fragment.substring(0, fragmentLength) + "...";
            }
            return "<pre class=\"text\">" + StringUtils.escapeForHtml(fragment, true, tabLength) + "</pre>";
        }

        // make sure we have unique fragments
        Set<String> uniqueFragments = new LinkedHashSet<String>();
        for (String fragment : fragments) {
            uniqueFragments.add(fragment);
        }
        fragments = uniqueFragments.toArray(new String[uniqueFragments.size()]);

        StringBuilder sb = new StringBuilder();
        for (int i = 0, len = fragments.length; i < len; i++) {
            String fragment = fragments[i];
            String tag = "<pre class=\"text\">";

            // resurrect the raw fragment from removing the artificial delimiters
            String raw = fragment.replace(termTag, "").replace(termTagEnd, "");

            // determine position of the raw fragment in the content
            int pos = content.indexOf(raw);

            // restore complete first line of fragment
            int c = pos;
            while (c > 0) {
                c--;
                if (content.charAt(c) == '\n') {
                    break;
                }
            }
            if (c > 0) {
                // inject leading chunk of first fragment line
                fragment = content.substring(c + 1, pos) + fragment;
            }

            if (SearchObjectType.blob == result.type) {
                // count lines as offset into the content for this fragment
                int line = Math.max(1, StringUtils.countLines(content.substring(0, pos)));

                // create fragment tag with line number and language
                String lang = "";
                String ext = StringUtils.getFileExtension(result.path).toLowerCase();
                if (!StringUtils.isEmpty(ext)) {
                    // maintain leading space!
                    lang = " lang-" + ext;
                }
                tag = MessageFormat.format("<pre class=\"prettyprint linenums:{0,number,0}{1}\">", line, lang);

            }

            sb.append(tag);

            // replace the artificial delimiter with html tags
            String html = StringUtils.escapeForHtml(fragment, false);
            html = html.replace(termTag, "<span class=\"highlight\">").replace(termTagEnd, "</span>");
            sb.append(html);
            sb.append("</pre>");
            if (i < len - 1) {
                sb.append("<span class=\"ellipses\">...</span><br/>");
            }
        }
        return sb.toString();
    }

    /**
     * Simple class to track the results of an index update.
     */
    private class IndexResult {

        long startTime = System.currentTimeMillis();
        long endTime = startTime;
        boolean success;
        int branchCount;
        int commitCount;
        int blobCount;

        void add(IndexResult result) {
            this.branchCount += result.branchCount;
            this.commitCount += result.commitCount;
            this.blobCount += result.blobCount;
        }

        void success() {
            success = true;
            endTime = System.currentTimeMillis();
        }

        float duration() {
            return (endTime - startTime) / 1000f;
        }
    }

    /**
     * Custom subclass of MultiReader to identify the source index for a given
     * doc id. This would not be necessary of there was a public method to
     * obtain this information.
     *
     */
    private class MultiSourceReader extends MultiReader {

        MultiSourceReader(IndexReader[] readers) throws IOException {
            super(readers, false);
        }

        int getSourceIndex(int docId) {
            int index = -1;
            try {
                index = super.readerIndex(docId);
            } catch (Exception e) {
                logger.error("Error getting source index", e);
            }
            return index;
        }
    }
}
