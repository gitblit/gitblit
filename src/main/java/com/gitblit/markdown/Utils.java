package com.gitblit.markdown;

import java.io.*;

/**
 * Created by Yuriy Aizenberg
 */
public class Utils {

    private static final String SPACES = " ";
    private static final String CODES = "%([abcdef]|\\d){2,2}";
    private static final String SPECIAL_CHARS = "[\\/?!:\\[\\]`.,()*\"';{}+=<>~\\$|#]";
    private static final String DASH = "-";
    private static final String EMPTY = "";


    public static boolean checkSourceFile(String fileName) {
        if (isEmpty(fileName)) return false;
        File sourceFile = new File(fileName);
        return sourceFile.exists() && sourceFile.canRead() && sourceFile.isFile();
    }

    public static boolean isEmpty(String string) {
        return string == null || string.isEmpty();
    }

    public static int getFileLines(String filePath, int defFaultValue) {
        LineNumberReader lineNumberReader = null;
        FileReader fileReader = null;
        try {
            fileReader = new FileReader(filePath);
            lineNumberReader = new LineNumberReader(fileReader);
            lineNumberReader.skip(Long.MAX_VALUE);
            return lineNumberReader.getLineNumber() + 1;
        } catch (IOException ignored) {
        } finally {
            closeStream(lineNumberReader, fileReader);
        }
        return defFaultValue;
    }

    public static void closeStream(Closeable... closeable) {
        for (Closeable c : closeable) {
            if (c != null) {
                try {
                    c.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    public static String normalize(final String taintedURL) {
        return taintedURL
                .trim()

                .replaceAll(SPACES, DASH)

                .replaceAll(CODES, EMPTY)

                .replaceAll(SPECIAL_CHARS, EMPTY).toLowerCase();
    }
}
