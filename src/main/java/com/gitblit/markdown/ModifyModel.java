package com.gitblit.markdown;

/**
 * Created by Yuriy Aizenberg
 */
public class ModifyModel {

    private static final String PATTERN = "%s- [%s](#%s)";
    private static final String AFFIX = "&nbsp;&nbsp;&nbsp;";

    private int currentDeepLevel = 1;
    private String headerName;
    private String headerLink;


    public ModifyModel(int currentDeepLevel, String headerName, String headerLink) {
        this.currentDeepLevel = currentDeepLevel;
        this.headerName = headerName;
        this.headerLink = headerLink;
    }

    public int getCurrentDeepLevel() {
        return currentDeepLevel;
    }

    public void setCurrentDeepLevel(int currentDeepLevel) {
        this.currentDeepLevel = currentDeepLevel;
    }

    public String getHeaderName() {
        return headerName;
    }

    public void setHeaderName(String headerName) {
        this.headerName = headerName;
    }

    public String getHeaderLink() {
        return headerLink;
    }

    public void setHeaderLink(String headerLink) {
        this.headerLink = headerLink;
    }

    public String create() {
        String affixs = "";
        if (currentDeepLevel > 1) {
            for (int i = 0; i < currentDeepLevel - 1; i++) {
                affixs += AFFIX;
            }
        }
        return String.format(PATTERN, affixs, headerName, headerLink);
    }
}
