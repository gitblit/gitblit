package com.gitblit.markdown;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TableOfContentsGenerator {

	public static final String	DEFAULT_HEADER		= "#";
	public static final char	DEFAULT_HEADER_CHAR	= '#';
	public static final String	ALT_1_HEADER		= "=";
	public static final String	ALT_2_HEADER		= "-";

	public static final String	TOC_HEADER			= "## Table of contents";

	private String				markdown;
	private List<ModifyModel>	rootModels			= new ArrayList<ModifyModel>();

	public TableOfContentsGenerator(String markdown) {
		this.markdown = markdown;
	}

	public String start() {

		if (!this.markdown.contains("__TOC__")) {
			return this.markdown;
		}

		int patternLineNumber = -1;
		int currentLine = 0;
		String[] items = markdown.split("\n");
		List<String> fileContent = new ArrayList<String>(Arrays.asList(items));

		String previousLine = null;

		for (String line : fileContent) {
			++currentLine;
			if (line.startsWith(DEFAULT_HEADER)) {
				String trim = line.trim();

				int count = getCount(trim);
				if (count < 1 || count > 6) {
					previousLine = line;
					continue;
				}
				String headerName = line.substring(count);
				rootModels.add(new ModifyModel(count, headerName, Utils.normalize(headerName)));
			} else if (line.startsWith(ALT_1_HEADER) && !Utils.isEmpty(previousLine)) {
				if (line.replaceAll(ALT_1_HEADER, "").isEmpty()) {
					rootModels.add(new ModifyModel(1, previousLine, Utils.normalize(previousLine)));
				}
			} else if (line.startsWith(ALT_2_HEADER) && !Utils.isEmpty(previousLine)) {
				if (line.replaceAll(ALT_2_HEADER, "").isEmpty()) {
					rootModels.add(new ModifyModel(2, previousLine, Utils.normalize(previousLine)));
				}
			} else if (line.trim().equals("__TOC__")) {
				patternLineNumber = currentLine;
			}
			previousLine = line;
		}

		return getMarkdown(fileContent, patternLineNumber).replaceAll("__TOC__", "");

	}

	private String getMarkdown(List<String> fileContent, int patternLineNumber) {
		StringBuilder writer = new StringBuilder();
		if (patternLineNumber == -1) {
			System.out.println("Pattern for replace not found!");
			return "";
		}
		fileContent.add(patternLineNumber - 1, TOC_HEADER);
		for (ModifyModel modifyModel : rootModels) {
			fileContent.add(patternLineNumber, modifyModel.create());
			patternLineNumber++;
		}

		for (String line : fileContent) {
			writer.append(line).append("\n");
		}

		return writer.toString();
	}

	private int getCount(String string) {
		int count = 0;
		for (int i = 0; i < string.length(); i++) {
			if (string.charAt(i) == DEFAULT_HEADER_CHAR) {
				++count;
			} else {
				break;
			}
		}
		return count;
	}

}
