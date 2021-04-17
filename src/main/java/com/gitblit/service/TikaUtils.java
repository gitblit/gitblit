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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveException;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.compress.archivers.zip.ZipUtil;
import org.apache.commons.io.IOUtils;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;

public class TikaUtils {

    public static String extractText(String ext, String filename, InputStream is, LuceneService service, String path, LuceneService.Indexer indexer) {
        Tika tika = new Tika();
        String fileType = tika.detect(filename);
        try {
            Logger.getLogger(TikaUtils.class.getName()).info("Tika parsing " + filename);
            if (isArchive(filename, ext)) {
                return extractTextFromArchive(ext, filename, is, service, path, indexer);
            }
            return tika.parseToString(is);
        } catch (Throwable tex) {
            Logger.getLogger(TikaUtils.class.getName()).log(Level.SEVERE, null, tex);
            return "";
        }
    }

    private static String extractTextFromArchive(String ext, String filename, InputStream is, LuceneService service, String path, LuceneService.Indexer indexer) {
        Logger.getLogger(TikaUtils.class.getName()).info("Tika zip parsing " + filename + " ");
        try (ArchiveInputStream in = new ArchiveStreamFactory().createArchiveInputStream(ArchiveStreamFactory.ZIP, is)) {
            ArchiveEntry nextEntry;
            while ((nextEntry = in.getNextEntry()) != null) {
                String archiveExt = null;
                String name = nextEntry.getName().toLowerCase();
                if (name.indexOf('.') > -1) {
                    archiveExt = name.substring(name.lastIndexOf('.') + 1);
                }
                name = filename + "/" + name;
                Logger.getLogger(TikaUtils.class.getName()).info("Tika zip parsing " + name);
                if (!nextEntry.isDirectory()) {
                    try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                        IOUtils.copy(in, bos);
                        bos.flush();
                        String result = service.getEncodedString(bos.toByteArray(), archiveExt);
                        if (result == null && service.useTika(ext)) {
                            result = extractText(archiveExt, path + "/" + nextEntry.getName(), new ByteArrayInputStream(bos.toByteArray()), service, path + "/" + nextEntry.getName(), indexer);
                        }
                        if (result != null) {
                            indexer.index(path + "/" + nextEntry.getName(), result);
                            Logger.getLogger(TikaUtils.class.getName()).info("Tika zip extract " + name + " " + result.length());
                        }

                    } catch (IOException ex) {
                        Logger.getLogger(TikaUtils.class.getName()).log(Level.SEVERE, null, ex);
                    }
                }
            }
        } catch (IOException ex) {
            Logger.getLogger(TikaUtils.class.getName()).log(Level.SEVERE, null, ex);
        } catch (ArchiveException ex) {
            Logger.getLogger(TikaUtils.class.getName()).log(Level.SEVERE, null, ex);
        }
        return null;
    }

    private static boolean isArchive(String filename, String ext) {
        return "zip".equals(ext);
    }

}
